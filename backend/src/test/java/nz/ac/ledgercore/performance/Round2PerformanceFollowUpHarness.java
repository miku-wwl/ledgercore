package nz.ac.ledgercore.performance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import com.sun.management.OperatingSystemMXBean;
import com.zaxxer.hikari.HikariDataSource;
import nz.ac.ledgercore.accounts.AccountService;
import nz.ac.ledgercore.ledger.LedgerBalanceReader;
import nz.ac.ledgercore.ledger.LedgerWriter;
import nz.ac.ledgercore.ledger.Money;
import nz.ac.ledgercore.organizations.OrganizationRole;
import nz.ac.ledgercore.organizations.OrganizationService;
import nz.ac.ledgercore.payroll.PayrollItemExecutor;
import nz.ac.ledgercore.payroll.PayrollService;
import nz.ac.ledgercore.transfers.TransferReceipt;
import nz.ac.ledgercore.transfers.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Opt-in Round 2 performance follow-ups, preregistered in evidence/round2/performance/PRE-REGISTRATION.md before any
 * measurement. Excluded from ordinary Surefire discovery by its name. The datasource comes from
 * LEDGERCORE_TEST_DB_URL and must be a disposable ledgercore_r2_perf_* database. The same source compiles against the
 * Round 1 baseline and the Round 2 code so R2-P3 can compare both builds with identical workload code.
 *
 * <p>Required system properties: {@code r2.evidence.dir} (output root) and {@code r2.run.label} (build label).
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.datasource.hikari.maximum-pool-size=10",
        "spring.datasource.hikari.minimum-idle=10",
        "spring.datasource.hikari.connection-timeout=30000"
})
class Round2PerformanceFollowUpHarness {

    private static final UUID RESERVE_ID = LedgerWriter.SIMULATION_RESERVE_ID;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final String BALANCE_SQL = """
            SELECT COALESCE(sum(CASE WHEN p.side = la.normal_side
                    THEN p.amount_minor ELSE -p.amount_minor END), 0)
            FROM ledger_accounts la LEFT JOIN postings p ON p.ledger_account_id = la.id
            WHERE la.id = ? GROUP BY la.id, la.normal_side
            """;
    /** Verbatim claim query of PayrollItemExecutor.executeNext in both the baseline and Round 2 builds. */
    private static final String CLAIM_SQL = """
            SELECT b.id AS batch_id, b.status AS batch_status, b.payer_account_id,
                   b.created_by_customer_id, i.id AS item_id, i.payee_account_id,
                   i.amount_minor, r.status AS item_status, r.transfer_id
            FROM payroll_batches b
            JOIN payroll_items i ON i.batch_id = b.id
            JOIN payroll_item_results r ON r.item_id = i.id
            WHERE b.id = ? AND r.status = 'PENDING'
            ORDER BY i.sequence_no
            LIMIT 1 FOR UPDATE OF r SKIP LOCKED
            """;

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired AccountService accountService;
    @Autowired OrganizationService organizationService;
    @Autowired PayrollService payrollService;
    @Autowired PayrollItemExecutor payrollItemExecutor;
    @Autowired LedgerBalanceReader balanceReader;
    @Autowired TransferService transferService;

    @BeforeEach
    void requireDisposablePerformanceDatabase() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).startsWith("ledgercore_r2_perf_");
        assertThat(System.getProperty("r2.evidence.dir")).as("r2.evidence.dir").isNotBlank();
        assertThat(System.getProperty("r2.run.label")).as("r2.run.label").isNotBlank();
    }

    /** R2-P2: the H01 workload with planner statistics refreshed and dirty buffers flushed after each fixture. */
    @Test
    void r2p2ControlledStatisticsBalanceHistory() throws Exception {
        Path run = runDirectory("r2-p2-h01-controlled-statistics");
        String summaryHeader = "history_postings,repeat,sql_p50_ms,sql_p95_ms,sql_p99_ms,service_p50_ms,service_p95_ms,"
                + "service_p99_ms,service_ops_per_second,plan_postings_node,plan_estimated_rows,plan_actual_rows,"
                + "plan_shared_hit,plan_shared_read,source_balance_minor,source_projection_matches";
        for (int historySize : new int[] { 100, 1_000, 10_000, 100_000 }) {
            for (int repeat = 1; repeat <= 3; repeat++) {
                UUID actor = newCustomer("r2-p2-h" + historySize + "-r" + repeat);
                List<AccountFixture> accounts = createPersonalAccounts(actor, 2, "r2-p2-h" + historySize + "-r" + repeat,
                        0L);
                AccountFixture source = accounts.getFirst();
                AccountFixture target = accounts.get(1);
                seedDepositHistory(source, historySize, "r2-p2-" + historySize + "-" + repeat);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM postings WHERE ledger_account_id = ?", Long.class,
                        source.ledgerId())).isEqualTo(historySize);
                // The single methodological change from H01: controlled planner statistics and buffer state.
                jdbc.execute("ANALYZE ledger_accounts");
                jdbc.execute("ANALYZE postings");
                jdbc.execute("ANALYZE account_balance_projection");
                jdbc.execute("CHECKPOINT");

                List<String> plan = capturePlan(run, "balance-history-" + historySize + "-repeat-" + repeat,
                        BALANCE_SQL, source.ledgerId());
                PlanFacts facts = postingsNodeFacts(plan);
                for (int index = 0; index < 10; index++) {
                    balanceReader.ledgerBalanceForLedgerAccount(source.ledgerId());
                }
                List<Long> readSamples = new ArrayList<>(100);
                for (int index = 0; index < 100; index++) {
                    long started = System.nanoTime();
                    balanceReader.ledgerBalanceForLedgerAccount(source.ledgerId());
                    readSamples.add(System.nanoTime() - started);
                }
                List<Long> transferSamples = new ArrayList<>(30);
                long workloadStarted = System.nanoTime();
                for (int index = 0; index < 30; index++) {
                    long started = System.nanoTime();
                    TransferReceipt receipt = transferService.transfer(actor, source.accountId(), target.accountId(),
                            new Money(1), "r2-p2-" + historySize + "-" + repeat + "-" + index);
                    transferSamples.add(System.nanoTime() - started);
                    assertThat(receipt.status()).isEqualTo("POSTED");
                }
                long elapsed = System.nanoTime() - workloadStarted;
                long balance = balanceReader.ledgerBalanceForLedgerAccount(source.ledgerId()).minorUnits();
                boolean matches = balance == balanceReader.projectionBalanceForLedgerAccount(source.ledgerId()).minorUnits();
                assertThat(balance).isEqualTo(historySize - 30L);
                assertThat(matches).isTrue();
                assertThat(balanceReader.projectionMatchesLedger(target.accountId())).isTrue();
                appendCsv(run.resolve("summary.csv"), summaryHeader, List.of(List.of(Integer.toString(historySize),
                        Integer.toString(repeat), ms(percentile(readSamples, .50)), ms(percentile(readSamples, .95)),
                        ms(percentile(readSamples, .99)), ms(percentile(transferSamples, .50)),
                        ms(percentile(transferSamples, .95)), ms(percentile(transferSamples, .99)),
                        decimal(30.0 / (elapsed / 1e9)), facts.node(), Long.toString(facts.estimatedRows()),
                        Long.toString(facts.actualRows()), Long.toString(facts.sharedHit()),
                        Long.toString(facts.sharedRead()), Long.toString(balance), Boolean.toString(matches))));
                List<List<String>> samples = new ArrayList<>(130);
                String historyLabel = Integer.toString(historySize);
                String repeatLabel = Integer.toString(repeat);
                readSamples.forEach(value -> samples.add(List.of(historyLabel, repeatLabel, "balance-reader",
                        Long.toString(value))));
                transferSamples.forEach(value -> samples.add(List.of(historyLabel, repeatLabel, "transfer-service",
                        Long.toString(value))));
                appendCsv(run.resolve("samples.csv"), "history_postings,repeat,sample_kind,latency_ns", samples);
                System.out.printf(Locale.ROOT, "R2_P2 history=%d repeat=%d sql-p50=%.3fms transfer-p50=%.3fms node=%s est=%d%n",
                        historySize, repeat, percentile(readSamples, .5) / 1e6, percentile(transferSamples, .5) / 1e6,
                        facts.node(), facts.estimatedRows());
            }
        }
        System.out.println("R2_P2_EVIDENCE=" + run.toAbsolutePath());
    }

    /**
     * R2-P3: single-worker payroll execution. Three 500-item batches measure per-item cost; one 5,000-item batch
     * records per-item latency by execution order and, at fixed checkpoints, the claim and payer-balance queries.
     */
    @Test
    void r2p3PayrollWithinBatchGrowthAndOverhead() throws Exception {
        Path run = runDirectory("r2-p3-h03-within-batch");
        UUID maker = newCustomer("r2-p3-maker");
        UUID approver = newCustomer("r2-p3-approver");
        UUID admin = newCustomer("r2-p3-admin");
        UUID employeeOwner = newCustomer("r2-p3-employee-owner");
        var organization = organizationService.create(admin, "R2-P3 payroll organization " + UUID.randomUUID());
        organizationService.addMember(admin, organization.id(), emailFor(maker), List.of(OrganizationRole.PAYROLL_MAKER));
        organizationService.addMember(admin, organization.id(), emailFor(approver),
                List.of(OrganizationRole.PAYROLL_APPROVER, OrganizationRole.OPERATIONS));
        List<AccountFixture> employees = createPersonalAccounts(employeeOwner, 5_000, "r2-p3-employee", 100_000L);
        List<PayrollService.NewPayrollItem> items = new ArrayList<>(5_000);
        for (int index = 0; index < employees.size(); index++) {
            String number = "%05d".formatted(index + 1);
            items.add(new PayrollService.NewPayrollItem("R2P3-" + number, "R2P3 Employee " + number,
                    employees.get(index).accountId(), "1.00"));
        }
        jdbc.execute("ANALYZE");

        String sampleHeader = "scenario,items,repeat,sequence,latency_ns";
        String summaryHeader = "scenario,items,repeat,items_per_second,p50_ms,p95_ms,first_decile_median_ms,"
                + "last_decile_median_ms,batch_status,unique_transfers";
        for (int repeat = 1; repeat <= 3; repeat++) {
            executeScenario(run, organization.id(), maker, approver, items.subList(0, 500), "batch-500", repeat,
                    sampleHeader, summaryHeader, false);
        }
        executeScenario(run, organization.id(), maker, approver, items, "batch-5000", 1, sampleHeader, summaryHeader,
                true);
        System.out.println("R2_P3_EVIDENCE=" + run.toAbsolutePath());
    }

    private void executeScenario(Path run, UUID organizationId, UUID maker, UUID approver,
            List<PayrollService.NewPayrollItem> items, String scenario, int repeat, String sampleHeader,
            String summaryHeader, boolean checkpoints) throws Exception {
        UUID payer = accountService.openOrganizationAccount(organizationId, "R2-P3 payer " + scenario + " r" + repeat);
        accountService.simulateDeposit(payer, new Money(Math.max(0L, items.size() * 100L - 100_000L) + 100L));
        var draft = payrollService.createDraft(maker, organizationId, "R2-P3 " + scenario + " r" + repeat, payer);
        for (int offset = 0; offset < items.size(); offset += 1_000) {
            payrollService.addItems(maker, draft.id(), items.subList(offset, Math.min(offset + 1_000, items.size())));
        }
        payrollService.submit(maker, draft.id());
        payrollService.approve(approver, draft.id());
        payrollService.startProcessing(draft.id(), approver);
        UUID payerLedger = jdbc.queryForObject(
                "SELECT id FROM ledger_accounts WHERE customer_account_id = ? AND kind = 'CUSTOMER_DEPOSIT'",
                UUID.class, payer);
        List<Integer> checkpointsAt = checkpoints ? List.of(0, 1_250, 2_500, 3_750, items.size() - 1) : List.of();
        List<Long> latencies = new ArrayList<>(items.size());
        long started = System.nanoTime();
        for (int completed = 0; completed < items.size(); completed++) {
            if (checkpointsAt.contains(completed)) {
                long pausedAt = System.nanoTime();
                captureCheckpoint(run, scenario, completed, draft.id(), payerLedger);
                started += System.nanoTime() - pausedAt;
            }
            long itemStarted = System.nanoTime();
            var result = payrollItemExecutor.executeNext(draft.id(), approver);
            latencies.add(System.nanoTime() - itemStarted);
            assertThat(result).isNotNull();
            assertThat(result.status()).isEqualTo("SUCCEEDED");
        }
        long elapsed = System.nanoTime() - started;
        var finished = payrollService.finishIfReady(draft.id(), approver);
        int uniqueTransfers = jdbc.queryForObject("""
                SELECT count(DISTINCT r.transfer_id) FROM payroll_item_results r JOIN payroll_items i ON i.id = r.item_id
                WHERE i.batch_id = ? AND r.status = 'SUCCEEDED'
                """, Integer.class, draft.id());
        assertThat(finished.status()).isEqualTo("COMPLETED");
        assertThat(uniqueTransfers).isEqualTo(items.size());
        assertThat(balanceReader.projectionMatchesLedger(payer)).isTrue();
        int decile = items.size() / 10;
        appendCsv(run.resolve("summary.csv"), summaryHeader, List.of(List.of(scenario, Integer.toString(items.size()),
                Integer.toString(repeat), decimal(items.size() / (elapsed / 1e9)), ms(percentile(latencies, .5)),
                ms(percentile(latencies, .95)), ms(percentile(latencies.subList(0, decile), .5)),
                ms(percentile(latencies.subList(items.size() - decile, items.size()), .5)), finished.status(),
                Integer.toString(uniqueTransfers))));
        List<List<String>> rows = new ArrayList<>(latencies.size());
        for (int index = 0; index < latencies.size(); index++) {
            rows.add(List.of(scenario, Integer.toString(items.size()), Integer.toString(repeat), Integer.toString(index),
                    Long.toString(latencies.get(index))));
        }
        appendCsv(run.resolve("samples.csv"), sampleHeader, rows);
        System.out.printf(Locale.ROOT, "R2_P3 %s r%d items/s=%.2f p50=%.3fms first-decile=%.3fms last-decile=%.3fms%n",
                scenario, repeat, items.size() / (elapsed / 1e9), percentile(latencies, .5) / 1e6,
                percentile(latencies.subList(0, decile), .5) / 1e6,
                percentile(latencies.subList(items.size() - decile, items.size()), .5) / 1e6);
    }

    private void captureCheckpoint(Path run, String scenario, int completed, UUID batchId, UUID payerLedger)
            throws IOException {
        List<String> claimPlan = capturePlan(run, scenario + "-claim-at-" + completed, CLAIM_SQL, batchId);
        List<String> balancePlan = capturePlan(run, scenario + "-payer-balance-at-" + completed, BALANCE_SQL, payerLedger);
        List<Long> claim = new ArrayList<>(20);
        List<Long> balance = new ArrayList<>(20);
        for (int index = 0; index < 25; index++) {
            long started = System.nanoTime();
            jdbc.queryForList(CLAIM_SQL, batchId);
            long claimNanos = System.nanoTime() - started;
            started = System.nanoTime();
            balanceReader.ledgerBalanceForLedgerAccount(payerLedger);
            long balanceNanos = System.nanoTime() - started;
            if (index >= 5) {
                claim.add(claimNanos);
                balance.add(balanceNanos);
            }
        }
        long payerPostings = jdbc.queryForObject("SELECT count(*) FROM postings WHERE ledger_account_id = ?", Long.class,
                payerLedger);
        appendCsv(run.resolve("checkpoints.csv"), "scenario,completed_items,payer_postings,claim_p50_ms,claim_p95_ms,"
                + "payer_balance_p50_ms,payer_balance_p95_ms,claim_plan_execution_ms,balance_plan_execution_ms",
                List.of(List.of(scenario, Integer.toString(completed), Long.toString(payerPostings),
                        ms(percentile(claim, .5)), ms(percentile(claim, .95)), ms(percentile(balance, .5)),
                        ms(percentile(balance, .95)), executionTime(claimPlan), executionTime(balancePlan))));
    }

    // ----- fixtures (synthetic, bulk JDBC; equivalent to the E3 harness helpers) -----

    private UUID newCustomer(String label) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO customers (id, email_normalized, password_hash, display_name)
                VALUES (?, ?, 'synthetic-r2-hash-not-for-authentication', ?)
                """, id, emailFor(id), ("R2 " + label).substring(0, Math.min(100, ("R2 " + label).length())));
        return id;
    }

    private String emailFor(UUID customerId) {
        return jdbc.query("SELECT email_normalized FROM customers WHERE id = ?", (rs, row) -> rs.getString(1), customerId)
                .stream().findFirst().orElse("r2-perf-" + customerId + "@example.test");
    }

    private List<AccountFixture> createPersonalAccounts(UUID owner, int count, String prefix, long openingMinor)
            throws SQLException {
        List<AccountFixture> accounts = new ArrayList<>(count);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement accountRows = connection.prepareStatement(
                    "INSERT INTO accounts (id, owner_customer_id, display_name) VALUES (?, ?, ?)");
                 PreparedStatement ledgerRows = connection.prepareStatement("""
                         INSERT INTO ledger_accounts (id, customer_account_id, kind, normal_side, is_system, currency)
                         VALUES (?, ?, 'CUSTOMER_DEPOSIT', 'CREDIT', FALSE, 'NZD')
                         """);
                 PreparedStatement projections = connection.prepareStatement(
                         "INSERT INTO account_balance_projection (ledger_account_id, currency, balance_minor) VALUES (?, 'NZD', ?)");
                 PreparedStatement journals = connection.prepareStatement(
                         "INSERT INTO journal_entries (id, entry_type, reference) VALUES (?, 'OPENING_FUNDING', ?)");
                 PreparedStatement postings = connection.prepareStatement("""
                         INSERT INTO postings (id, journal_entry_id, ledger_account_id, side, amount_minor, currency)
                         VALUES (?, ?, ?, ?, ?, 'NZD')
                         """)) {
                for (int index = 0; index < count; index++) {
                    UUID accountId = UUID.randomUUID();
                    UUID ledgerId = UUID.randomUUID();
                    accounts.add(new AccountFixture(accountId, ledgerId));
                    accountRows.setObject(1, accountId);
                    accountRows.setObject(2, owner);
                    String name = prefix + "-" + index;
                    accountRows.setString(3, name.substring(0, Math.min(80, name.length())));
                    accountRows.addBatch();
                    ledgerRows.setObject(1, ledgerId);
                    ledgerRows.setObject(2, accountId);
                    ledgerRows.addBatch();
                    projections.setObject(1, ledgerId);
                    projections.setLong(2, openingMinor);
                    projections.addBatch();
                    if (openingMinor > 0) {
                        UUID journalId = UUID.randomUUID();
                        journals.setObject(1, journalId);
                        journals.setString(2, "r2-opening-" + accountId);
                        journals.addBatch();
                        addPosting(postings, journalId, RESERVE_ID, "DEBIT", openingMinor);
                        addPosting(postings, journalId, ledgerId, "CREDIT", openingMinor);
                    }
                    if (index % 1_000 == 999) {
                        accountRows.executeBatch();
                        ledgerRows.executeBatch();
                        projections.executeBatch();
                        journals.executeBatch();
                        postings.executeBatch();
                    }
                }
                accountRows.executeBatch();
                ledgerRows.executeBatch();
                projections.executeBatch();
                journals.executeBatch();
                postings.executeBatch();
                if (openingMinor > 0) {
                    try (PreparedStatement reserve = connection.prepareStatement(
                            "UPDATE account_balance_projection SET balance_minor = balance_minor + ? WHERE ledger_account_id = ?")) {
                        reserve.setLong(1, Math.multiplyExact(openingMinor, count));
                        reserve.setObject(2, RESERVE_ID);
                        assertThat(reserve.executeUpdate()).isEqualTo(1);
                    }
                }
                connection.commit();
            }
            catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
        return accounts;
    }

    private void seedDepositHistory(AccountFixture account, int count, String namespace) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement journals = connection.prepareStatement(
                    "INSERT INTO journal_entries (id, entry_type, reference) VALUES (?, 'SIMULATED_DEPOSIT', ?)");
                 PreparedStatement postings = connection.prepareStatement("""
                         INSERT INTO postings (id, journal_entry_id, ledger_account_id, side, amount_minor, currency)
                         VALUES (?, ?, ?, ?, ?, 'NZD')
                         """)) {
                for (int index = 0; index < count; index++) {
                    UUID journalId = UUID.randomUUID();
                    journals.setObject(1, journalId);
                    journals.setString(2, namespace + "-" + index);
                    journals.addBatch();
                    addPosting(postings, journalId, RESERVE_ID, "DEBIT", 1);
                    addPosting(postings, journalId, account.ledgerId(), "CREDIT", 1);
                    if (index % 2_000 == 1_999) {
                        journals.executeBatch();
                        postings.executeBatch();
                    }
                }
                journals.executeBatch();
                postings.executeBatch();
                try (PreparedStatement projections = connection.prepareStatement(
                        "UPDATE account_balance_projection SET balance_minor = balance_minor + ? WHERE ledger_account_id = ?")) {
                    projections.setLong(1, count);
                    projections.setObject(2, account.ledgerId());
                    assertThat(projections.executeUpdate()).isEqualTo(1);
                    projections.setLong(1, count);
                    projections.setObject(2, RESERVE_ID);
                    assertThat(projections.executeUpdate()).isEqualTo(1);
                }
                connection.commit();
            }
            catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private void addPosting(PreparedStatement postings, UUID journalId, UUID ledgerId, String side, long amount)
            throws SQLException {
        postings.setObject(1, UUID.randomUUID());
        postings.setObject(2, journalId);
        postings.setObject(3, ledgerId);
        postings.setString(4, side);
        postings.setLong(5, amount);
        postings.addBatch();
    }

    // ----- evidence helpers -----

    private Path runDirectory(String name) throws IOException, SQLException {
        String label = System.getProperty("r2.run.label");
        Path run = Path.of(System.getProperty("r2.evidence.dir")).resolve(name + "-" + label + "-"
                + STAMP.format(Instant.now()));
        Files.createDirectories(run.resolve("query-plans"));
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        List<String> settings = jdbc.query("""
                SELECT name || '=' || current_setting(name) FROM pg_settings
                WHERE name IN ('server_version', 'port', 'shared_buffers', 'work_mem', 'effective_cache_size',
                               'synchronous_commit', 'fsync', 'max_connections', 'random_page_cost', 'jit')
                ORDER BY name
                """, (rs, row) -> rs.getString(1));
        Files.writeString(run.resolve("environment.json"), """
                {
                  "capturedAtUtc": "%s",
                  "runLabel": "%s",
                  "sourceCommit": "%s",
                  "database": "%s",
                  "databaseSettings": "%s",
                  "java": "%s",
                  "logicalProcessors": %d,
                  "physicalMemoryBytes": %d,
                  "hikariMaximumPoolSize": %d,
                  "measurementBoundary": "Direct Spring service proxy calls in one JVM thread, commit included; HTTP excluded."
                }
                """.formatted(Instant.now(), label, System.getProperty("r2.source.commit", "unrecorded"),
                jdbc.queryForObject("SELECT current_database()", String.class), String.join("; ", settings),
                System.getProperty("java.runtime.version"), Runtime.getRuntime().availableProcessors(),
                os.getTotalMemorySize(), hikari.getMaximumPoolSize()));
        return run;
    }

    private List<String> capturePlan(Path run, String name, String sql, Object... parameters) throws IOException {
        List<String> lines = jdbc.query("EXPLAIN (ANALYZE, BUFFERS, SETTINGS) " + sql, (rs, row) -> rs.getString(1),
                parameters);
        Files.writeString(run.resolve("query-plans").resolve(name + ".txt"), "SQL:\n" + sql + "\nPARAMETERS (synthetic): "
                + java.util.Arrays.stream(parameters).map(String::valueOf).collect(Collectors.joining(", "))
                + "\n\nPLAN:\n" + String.join("\n", lines) + "\n");
        return lines;
    }

    private PlanFacts postingsNodeFacts(List<String> plan) {
        for (int index = 0; index < plan.size(); index++) {
            String line = plan.get(index);
            if (line.contains(" on postings p")) {
                String node = line.trim().replaceFirst("^->\\s*", "").replaceAll(" on postings p.*", "");
                long estimated = number(line, "rows=", 0);
                long actual = number(line, "rows=", 1);
                long hit = 0;
                long read = 0;
                for (int next = index + 1; next < plan.size(); next++) {
                    if (plan.get(next).contains("Buffers:")) {
                        hit = number(plan.get(next), "hit=", 0);
                        read = number(plan.get(next), "read=", 0);
                        break;
                    }
                }
                return new PlanFacts(node, estimated, actual, hit, read);
            }
        }
        return new PlanFacts("not-found", -1, -1, -1, -1);
    }

    private long number(String line, String key, int occurrence) {
        int from = -1;
        for (int index = 0; index <= occurrence; index++) {
            from = line.indexOf(key, from + 1);
            if (from < 0) {
                return 0;
            }
        }
        int start = from + key.length();
        int end = start;
        while (end < line.length() && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '.')) {
            end++;
        }
        return (long) Double.parseDouble(line.substring(start, end));
    }

    private String executionTime(List<String> plan) {
        return plan.stream().filter(line -> line.trim().startsWith("Execution Time:")).findFirst()
                .map(line -> line.replaceAll("[^0-9.]", "")).orElse("");
    }

    private void appendCsv(Path file, String header, List<List<String>> rows) throws IOException {
        StringBuilder text = new StringBuilder();
        if (!Files.exists(file)) {
            text.append(header).append('\n');
        }
        for (List<String> row : rows) {
            text.append(String.join(",", row)).append('\n');
        }
        Files.writeString(file, text.toString(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private long percentile(List<Long> data, double fraction) {
        if (data.isEmpty()) {
            return 0;
        }
        List<Long> sorted = data.stream().sorted().toList();
        return sorted.get(Math.max(0, (int) Math.ceil(fraction * sorted.size()) - 1));
    }

    private String ms(long nanos) {
        return decimal(nanos / 1_000_000.0);
    }

    private String decimal(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private record AccountFixture(UUID accountId, UUID ledgerId) {
    }

    private record PlanFacts(String node, long estimatedRows, long actualRows, long sharedHit, long sharedRead) {
    }
}
