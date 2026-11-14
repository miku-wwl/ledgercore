package nz.ac.ledgercore.performance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import com.sun.management.OperatingSystemMXBean;
import nz.ac.ledgercore.accounts.AccountService;
import nz.ac.ledgercore.ledger.LedgerBalanceReader;
import nz.ac.ledgercore.ledger.LedgerWriter;
import nz.ac.ledgercore.ledger.Money;
import nz.ac.ledgercore.organizations.OrganizationRole;
import nz.ac.ledgercore.organizations.OrganizationService;
import nz.ac.ledgercore.payroll.PayrollItemExecutor;
import nz.ac.ledgercore.payroll.PayrollService;
import nz.ac.ledgercore.transfers.TransferErrorCode;
import nz.ac.ledgercore.transfers.TransferException;
import nz.ac.ledgercore.transfers.TransferReceipt;
import nz.ac.ledgercore.transfers.TransferService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Opt-in, real-PostgreSQL E3 workloads. Excluded from the default Maven test patterns; invoke only
 * with evidence/e3/scripts/run-e3.ps1 after the environment record and source revision are fixed.
 */
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:postgresql://127.0.0.1:55434/ledgercore_e3_perf",
        "spring.datasource.username=ledgercore_test",
        "spring.datasource.password=",
        "spring.datasource.hikari.maximum-pool-size=10",
        "spring.datasource.hikari.minimum-idle=10",
        "spring.datasource.hikari.connection-timeout=30000"
})
class E3PerformanceExperimentHarness {

    private static final UUID RESERVE_ID = LedgerWriter.SIMULATION_RESERVE_ID;
    private static final long INITIAL_FUNDS_MINOR = 100_000L;
    private static final int OPERATIONS_PER_CLIENT = 100;
    private static final int[] CONCURRENCY_LEVELS = { 1, 10, 30, 50 };
    private static final DateTimeFormatter RUN_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired AccountService accountService;
    @Autowired OrganizationService organizationService;
    @Autowired PayrollService payrollService;
    @Autowired PayrollItemExecutor payrollItemExecutor;
    @Autowired LedgerBalanceReader balanceReader;
    @Autowired TransferService transferService;

    @Test
    void captureEnvironmentBeforeMeasurements() throws Exception {
        Path root = evidenceRoot();
        Files.createDirectories(root.resolve("environment"));
        Path output = root.resolve("environment/environment.json");
        if (Files.exists(output)) {
            System.out.println("E3_ENVIRONMENT_ALREADY_CAPTURED=" + output.toAbsolutePath());
            return;
        }

        List<List<String>> database = jdbc.query("""
                SELECT current_database(), current_setting('server_version'), current_setting('port'),
                       current_setting('shared_buffers'), current_setting('work_mem'),
                       current_setting('maintenance_work_mem'), current_setting('max_connections'),
                       current_setting('synchronous_commit'), current_setting('fsync'),
                       current_setting('effective_cache_size'), pg_database_size(current_database())
                """, (rs, row) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9),
                rs.getString(10), Long.toString(rs.getLong(11))));
        assertThat(database).hasSize(1);
        HikariDataSource hikari = hikari();
        OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        long diskFree = Files.getFileStore(repositoryRoot()).getUsableSpace();
        String sourceCommit = git("log", "-1", "--format=%H", "--",
                "backend/src/test/java/nz/ac/ledgercore/performance/E3PerformanceExperimentHarness.java").trim();
        String[] db = database.getFirst().toArray(String[]::new);
        assertThat(db[0]).isEqualTo("ledgercore_e3_perf");
        assertThat(db[1]).isEqualTo("18.7");
        assertThat(db[2]).isEqualTo("55434");
        String json = """
                {
                  "capturedAtUtc": "%s",
                  "sourceCommit": "%s",
                  "repositoryBranch": "%s",
                  "repositoryWorkingTree": "%s",
                  "host": {"name":"JIEJIE","model":"HONOR BRI-XX","cpu":"AMD Ryzen 7 8845HS with Radeon 780M Graphics","physicalCores":8,"logicalProcessors":%d,"physicalMemoryBytes":%d,"availableMemoryBytesAtCapture":%d,"disk":"D:","freeBytesAtCapture":%d,"os":"Windows 11 Home, build 26200"},
                  "runtime": {"java":"%s","maven":"3.10.0 (Maven Wrapper)","mavenProject":"backend/pom.xml","connectionPool":"HikariCP","poolMaximum":%d,"poolMinimumIdle":%d,"poolConnectionTimeoutMs":%d},
                  "database": {"name":"%s","host":"127.0.0.1","port":%s,"engine":"PostgreSQL","version":"%s","sharedBuffers":"%s","workMem":"%s","maintenanceWorkMem":"%s","maxConnections":%s,"synchronousCommit":"%s","fsync":"%s","effectiveCacheSize":"%s","initialDatabaseBytes":%s},
                  "databaseIsolation": "New disposable database ledgercore_e3_perf; separate from ledgercore_test and ledgercore_e1_test.",
                  "measurementBoundary": "Direct Spring service proxy calls with transaction commit included; HTTP and browser excluded."
                }
                """.formatted(Instant.now(), sourceCommit, git("branch", "--show-current").trim(),
                git("status", "--porcelain").trim().isEmpty() ? "clean before evidence capture" : "evidence capture pending",
                Runtime.getRuntime().availableProcessors(), os.getTotalMemorySize(), os.getFreeMemorySize(), diskFree,
                System.getProperty("java.runtime.version"), hikari.getMaximumPoolSize(), hikari.getMinimumIdle(),
                hikari.getConnectionTimeout(), db[0], db[2], db[1], db[3], db[4], db[5], db[6], db[7], db[8], db[9],
                db[10]);
        Files.writeString(output, json);
        System.out.println("E3_ENVIRONMENT=" + output.toAbsolutePath());
        System.out.println("E3_SOURCE_COMMIT=" + sourceCommit);
        System.out.println("E3_HOST_FREE_MEMORY_BYTES=" + os.getFreeMemorySize());
        System.out.println("E3_D_DRIVE_FREE_BYTES=" + diskFree);
        System.out.println("E3_DATABASE=" + db[0] + " PostgreSQL " + db[1] + " on port " + db[2]);
        System.out.println("E3_HIKARI_MAXIMUM=" + hikari.getMaximumPoolSize());
    }

    @Test
    void balanceHistoryExperiment() throws Exception {
        Path run = newRunDirectory("h01-balance-history");
        String summaryHeader = "history_postings,repeat,sql_p50_ms,sql_p95_ms,sql_p99_ms,service_p50_ms,service_p95_ms,service_p99_ms,service_ops_per_second,service_cpu_seconds,source_balance_minor,source_projection_minor,source_projection_matches,source_posting_count";
        String sampleHeader = "history_postings,repeat,sample_kind,sequence,latency_ns";
        for (int historySize : new int[] { 100, 1_000, 10_000, 100_000 }) {
            for (int repeat = 1; repeat <= 3; repeat++) {
                if (historySize == 100_000) {
                    preflightLargestFixture();
                }
                UUID actor = newCustomer("e3-h01-r" + repeat);
                List<AccountFixture> accounts = createPersonalAccounts(actor, 2,
                        "e3-h01-h" + historySize + "-r" + repeat, 0L);
                AccountFixture source = accounts.getFirst();
                AccountFixture target = accounts.get(1);
                seedDepositHistory(source, historySize, "e3-h01-" + historySize + "-" + repeat);
                long sourcePostings = postingCount(source.ledgerId());
                assertThat(sourcePostings).isEqualTo(historySize);
                assertThat(balanceReader.projectionMatchesLedger(source.accountId())).isTrue();

                String aggregateSql = """
                        SELECT COALESCE(sum(CASE WHEN p.side = la.normal_side
                                THEN p.amount_minor ELSE -p.amount_minor END), 0)
                        FROM ledger_accounts la LEFT JOIN postings p ON p.ledger_account_id = la.id
                        WHERE la.id = ? GROUP BY la.id, la.normal_side
                        """;
                capturePlan(run, "ledger-balance-history-" + historySize + "-repeat-" + repeat,
                        aggregateSql, source.ledgerId());

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
                OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
                long cpuBefore = os.getProcessCpuTime();
                long workloadStarted = System.nanoTime();
                long expectedSourceBalance = historySize;
                for (int index = 0; index < 30; index++) {
                    long started = System.nanoTime();
                    TransferReceipt receipt = transferService.transfer(actor, source.accountId(), target.accountId(),
                            new Money(1), "e3-h01-" + historySize + "-" + repeat + "-" + index);
                    transferSamples.add(System.nanoTime() - started);
                    assertThat(receipt.status()).isEqualTo("POSTED");
                    expectedSourceBalance--;
                }
                long elapsed = System.nanoTime() - workloadStarted;
                long cpuElapsed = os.getProcessCpuTime() - cpuBefore;
                long projection = balanceReader.projectionBalanceForLedgerAccount(source.ledgerId()).minorUnits();
                long balance = balanceReader.ledgerBalanceForLedgerAccount(source.ledgerId()).minorUnits();
                boolean projectionMatches = balance == projection;
                assertThat(balance).isEqualTo(expectedSourceBalance);
                assertThat(projectionMatches).isTrue();
                assertThat(balanceReader.projectionMatchesLedger(target.accountId())).isTrue();

                List<String> summary = List.of(Integer.toString(historySize), Integer.toString(repeat),
                        formatMillis(percentile(readSamples, 0.50)), formatMillis(percentile(readSamples, 0.95)),
                        formatMillis(percentile(readSamples, 0.99)), formatMillis(percentile(transferSamples, 0.50)),
                        formatMillis(percentile(transferSamples, 0.95)), formatMillis(percentile(transferSamples, 0.99)),
                        formatDouble(30.0 / (elapsed / 1_000_000_000.0)),
                        formatDouble(cpuElapsed / 1_000_000_000.0), Long.toString(balance), Long.toString(projection),
                        Boolean.toString(projectionMatches), Long.toString(sourcePostings));
                appendCsv(run.resolve("balance-history-summary.csv"), summaryHeader, List.of(summary));
                List<List<String>> samples = new ArrayList<>(130);
                addSamples(samples, historySize, repeat, "balance-reader", readSamples);
                addSamples(samples, historySize, repeat, "transfer-service", transferSamples);
                appendCsv(run.resolve("balance-history-samples.csv"), sampleHeader, samples);
                System.out.printf(Locale.ROOT,
                        "E3_H01 history=%d repeat=%d read-p50=%.3fms transfer-p50=%.3fms transfer-p95=%.3fms ops/s=%.2f%n",
                        historySize, repeat, percentile(readSamples, .50) / 1e6,
                        percentile(transferSamples, .50) / 1e6, percentile(transferSamples, .95) / 1e6,
                        30.0 / (elapsed / 1_000_000_000.0));
            }
        }
        Files.writeString(run.resolve("source-commit.txt"), sourceCommit() + System.lineSeparator());
        System.out.println("E3_H01_EVIDENCE=" + run.toAbsolutePath());
    }

    @Test
    void transferConcurrencyExperiment() throws Exception {
        Path run = newRunDirectory("h02-transfer-concurrency");
        String summaryHeader = "distribution,concurrency,repeat,operations,successes,expected_insufficient,unexpected_failures,ops_per_second,successes_per_second,p50_ms,p95_ms,p99_ms,pg_lock_wait_samples,pg_max_lock_wait_sessions,hikari_max_active,hikari_max_pending,hikari_pool_max,jvm_cpu_seconds,deadlock_delta,transfer_invariant_violations,projection_mismatches,negative_customer_balances";
        String sampleHeader = "distribution,concurrency,repeat,client,sequence,status,error_code,transfer_id,latency_ns";
        List<String> distributions = List.of("independent-account-pairs", "one-hot-payer",
                "opposing-direction-pairs", "80-percent-funded-20-percent-insufficient");
        for (String distribution : distributions) {
            for (int concurrency : CONCURRENCY_LEVELS) {
                for (int repeat = 1; repeat <= 3; repeat++) {
                    final String distributionName = distribution;
                    final int concurrencyLevel = concurrency;
                    final int repeatNumber = repeat;
                    UUID actor = newCustomer("e3-h02-" + shortName(distribution) + "-c" + concurrency + "-r" + repeat);
                    int pairCount = "independent-account-pairs".equals(distribution) ? concurrency : 1;
                    List<AccountFixture> fixtures = createPersonalAccounts(actor, pairCount * 2,
                            "e3-h02-" + shortName(distribution) + "-c" + concurrency + "-r" + repeat, INITIAL_FUNDS_MINOR);
                    List<UUID> transferIds = Collections.synchronizedList(new ArrayList<>());
                    long deadlocksBefore = deadlockCount();
                    ConcurrentResult result = runTransferClients(actor, distribution, concurrency, repeat, fixtures,
                            transferIds);
                    long deadlocksAfter = deadlockCount();
                    int expectedInsufficient = distribution.startsWith("80-percent")
                            ? concurrency * OPERATIONS_PER_CLIENT / 5 : 0;
                    int expectedSuccesses = result.totalOperations() - expectedInsufficient;
                    int unexpectedFailures = result.failures() - expectedInsufficient;
                    assertThat(result.totalOperations()).isEqualTo(concurrency * OPERATIONS_PER_CLIENT);
                    assertThat(result.successes()).isEqualTo(expectedSuccesses);
                    assertThat(unexpectedFailures).isZero();
                    if (distribution.equals("opposing-direction-pairs")) {
                        assertThat(deadlocksAfter - deadlocksBefore).isZero();
                    }

                    List<UUID> accountIds = fixtures.stream().map(AccountFixture::accountId).toList();
                    int transferViolations = verifyTransferRows(transferIds);
                    int projectionMismatches = verifyProjectionMatches(accountIds);
                    int negativeBalances = countNegativeCustomerBalances(accountIds);
                    assertThat(transferViolations).isZero();
                    assertThat(projectionMismatches).isZero();
                    assertThat(negativeBalances).isZero();

                    double seconds = result.elapsedNanos() / 1_000_000_000.0;
                    Latency latency = summarize(result.samples().stream().map(OperationSample::latencyNanos).toList());
                    List<String> summary = List.of(distribution, Integer.toString(concurrency), Integer.toString(repeat),
                            Integer.toString(result.totalOperations()), Integer.toString(result.successes()),
                            Integer.toString(expectedInsufficient), Integer.toString(unexpectedFailures),
                            formatDouble(result.totalOperations() / seconds), formatDouble(result.successes() / seconds),
                            formatMillis(latency.p50()), formatMillis(latency.p95()), formatMillis(latency.p99()),
                            Long.toString(result.monitor().lockWaitSamples()),
                            Long.toString(result.monitor().maxLockWaitSessions()),
                            Integer.toString(result.monitor().maxActive()), Integer.toString(result.monitor().maxPending()),
                            Integer.toString(result.monitor().poolMaximum()),
                            formatDouble(result.cpuNanos() / 1_000_000_000.0),
                            Long.toString(deadlocksAfter - deadlocksBefore), Integer.toString(transferViolations),
                            Integer.toString(projectionMismatches), Integer.toString(negativeBalances));
                    appendCsv(run.resolve("transfer-concurrency-summary.csv"), summaryHeader, List.of(summary));
                    List<List<String>> sampleRows = result.samples().stream().map(sample -> List.of(distributionName,
                            Integer.toString(concurrencyLevel), Integer.toString(repeatNumber), Integer.toString(sample.client()),
                            Integer.toString(sample.sequence()), sample.status(), sample.errorCode(),
                            sample.transferId() == null ? "" : sample.transferId().toString(),
                            Long.toString(sample.latencyNanos()))).toList();
                    appendCsv(run.resolve("transfer-concurrency-samples.csv"), sampleHeader, sampleRows);
                    if (distribution.equals("one-hot-payer") && concurrency == 50 && repeat == 3) {
                        persistHistoryPlanFixtures(run, actor, fixtures.getFirst());
                    }
                    System.out.printf(Locale.ROOT,
                            "E3_H02 distribution=%s c=%d repeat=%d ops/s=%.2f p95=%.3fms lockSamples=%d poolActiveMax=%d deadlocks=%d%n",
                            distribution, concurrency, repeat, result.totalOperations() / seconds,
                            latency.p95() / 1e6, result.monitor().lockWaitSamples(), result.monitor().maxActive(),
                            deadlocksAfter - deadlocksBefore);
                }
            }
        }
        Files.writeString(run.resolve("source-commit.txt"), sourceCommit() + System.lineSeparator());
        System.out.println("E3_H02_EVIDENCE=" + run.toAbsolutePath());
    }

    private ConcurrentResult runTransferClients(UUID actor, String distribution, int concurrency, int repeat,
            List<AccountFixture> fixtures, List<UUID> transferIds) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<OperationSample> samples = new ConcurrentLinkedQueue<>();
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        DatabaseMonitor monitor = new DatabaseMonitor();
        long cpuBefore = 0L;
        try {
            List<Future<?>> futures = new ArrayList<>(concurrency);
            for (int client = 0; client < concurrency; client++) {
                final int clientNumber = client;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    AccountFixture source;
                    AccountFixture target;
                    if (distribution.equals("independent-account-pairs")) {
                        source = fixtures.get(clientNumber * 2);
                        target = fixtures.get(clientNumber * 2 + 1);
                    }
                    else if (distribution.equals("opposing-direction-pairs") && clientNumber % 2 == 1) {
                        source = fixtures.get(1);
                        target = fixtures.get(0);
                    }
                    else {
                        source = fixtures.get(0);
                        target = fixtures.get(1);
                    }
                    for (int sequence = 0; sequence < OPERATIONS_PER_CLIENT; sequence++) {
                        boolean insufficient = distribution.startsWith("80-percent") && sequence % 5 == 4;
                        long amountMinor = insufficient ? 200_000L : 1L;
                        long startedAt = System.nanoTime();
                        try {
                            TransferReceipt receipt = transferService.transfer(actor, source.accountId(), target.accountId(),
                                    new Money(amountMinor), "e3-h02-" + shortName(distribution) + "-c" + concurrency
                                            + "-r" + repeat + "-w" + clientNumber + "-n" + sequence);
                            long latency = System.nanoTime() - startedAt;
                            successes.incrementAndGet();
                            transferIds.add(receipt.transferId());
                            samples.add(new OperationSample(clientNumber, sequence, "SUCCEEDED", "",
                                    receipt.transferId(), latency));
                        }
                        catch (TransferException exception) {
                            long latency = System.nanoTime() - startedAt;
                            failures.incrementAndGet();
                            samples.add(new OperationSample(clientNumber, sequence, "FAILED",
                                    exception.code().name(), null, latency));
                        }
                    }
                    return null;
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).as("transfer clients become ready").isTrue();
            monitor.start();
            Thread.sleep(100);
            cpuBefore = processCpuNanos();
            long startedAt = System.nanoTime();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(45, TimeUnit.MINUTES);
            }
            long elapsed = System.nanoTime() - startedAt;
            monitor.close();
            return new ConcurrentResult(concurrency * OPERATIONS_PER_CLIENT, successes.get(), failures.get(), elapsed,
                    processCpuNanos() - cpuBefore, List.copyOf(samples), monitor.snapshot());
        }
        finally {
            start.countDown();
            monitor.close();
            executor.shutdownNow();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    private void persistHistoryPlanFixtures(Path run, UUID ownerId, AccountFixture account) throws Exception {
        UUID actualOwner = jdbc.queryForObject("SELECT owner_customer_id FROM accounts WHERE id = ?",
                UUID.class, account.accountId());
        List<HistoryCursor> cursors = jdbc.query("""
                SELECT created_at, id FROM transfers WHERE source_account_id = ?
                ORDER BY created_at DESC, id DESC OFFSET 2500 LIMIT 1
                """, (rs, row) -> new HistoryCursor(rs.getTimestamp(1).toInstant(), rs.getObject(2, UUID.class)),
                account.accountId());
        assertThat(cursors).hasSize(1);
        HistoryCursor cursor = cursors.getFirst();
        String firstPage = historySql(false);
        String keysetPage = historySql(true);
        capturePlan(run, "transaction-history-first-page", firstPage, account.accountId(), actualOwner,
                account.accountId(), account.accountId(), 11);
        capturePlan(run, "transaction-history-keyset-page", keysetPage, account.accountId(), actualOwner,
                account.accountId(), account.accountId(), java.sql.Timestamp.from(cursor.createdAt()), cursor.id(), 11);
        Files.writeString(run.resolve("history-query-fixture.json"), """
                {"sourceAccountId":"%s","ownerCustomerId":"%s","cursorCreatedAt":"%s","cursorTransferId":"%s","sourcePostingCount":%d,"sourceCommit":"%s"}
                """.formatted(account.accountId(), actualOwner, cursor.createdAt(), cursor.id(),
                postingCount(account.ledgerId()), sourceCommit()));
    }

    private String historySql(boolean keyset) {
        return """
                SELECT t.id, t.source_account_id, t.target_account_id, t.amount_minor, t.currency,
                       t.created_at, source.display_name AS source_name, target.display_name AS target_name
                FROM transfers t
                JOIN accounts current_account ON current_account.id = ? AND current_account.owner_customer_id = ?
                JOIN accounts source ON source.id = t.source_account_id
                JOIN accounts target ON target.id = t.target_account_id
                WHERE (t.source_account_id = ? OR t.target_account_id = ?)
                """ + (keyset ? " AND (t.created_at, t.id) < (?, ?)" : "")
                + " ORDER BY t.created_at DESC, t.id DESC LIMIT ?";
    }

    @Test
    void payrollConcurrencyAndQueryPlans() throws Exception {
        Path run = newRunDirectory("h03-payroll-query-plans");
        String summaryHeader = "items,concurrency,repeat,successes,failures,ops_per_second,p50_ms,p95_ms,p99_ms,pg_lock_wait_samples,pg_max_lock_wait_sessions,hikari_max_active,hikari_max_pending,jvm_cpu_seconds,deadlock_delta,batch_status,unique_transfers,transfer_invariant_violations,projection_mismatches,negative_balances";
        String sampleHeader = "items,concurrency,repeat,sequence,worker,status,latency_ns";
        PayrollFixture fixture = createPayrollFixture();
        List<AccountFixture> employeeAccounts = createPersonalAccounts(fixture.employeeOwnerId(), 5_000,
                "e3-h03-employee", INITIAL_FUNDS_MINOR);
        List<PayrollService.NewPayrollItem> allItems = new ArrayList<>(5_000);
        for (int index = 0; index < employeeAccounts.size(); index++) {
            String number = "%05d".formatted(index + 1);
            allItems.add(new PayrollService.NewPayrollItem("E3-" + number, "E3 Employee " + number,
                    employeeAccounts.get(index).accountId(), "1.00"));
        }
        jdbc.execute("ANALYZE organizations");
        jdbc.execute("ANALYZE organization_memberships");
        jdbc.execute("ANALYZE organization_membership_roles");
        jdbc.execute("ANALYZE accounts");
        jdbc.execute("ANALYZE ledger_accounts");
        jdbc.execute("ANALYZE postings");

        createSyntheticOrganizationMembers(fixture.organizationId(), 5_000);
        jdbc.execute("ANALYZE organization_memberships");
        jdbc.execute("ANALYZE organization_membership_roles");
        String roleSql = """
                SELECT r.role FROM organization_memberships m
                JOIN organization_membership_roles r
                  ON r.organization_id = m.organization_id AND r.customer_id = m.customer_id
                JOIN organizations o ON o.id = m.organization_id AND o.status = 'ACTIVE'
                WHERE m.organization_id = ? AND m.customer_id = ? AND m.status = 'ACTIVE'
                ORDER BY r.role
                """;
        capturePlan(run, "organization-membership-role-authorization", roleSql,
                fixture.organizationId(), fixture.makerId());

        BatchFixture batch5000 = createApprovedBatch(fixture, "plan-5000", allItems);
        capturePayrollPlans(run, "5000", batch5000, fixture);
        BatchFixture batch500 = createApprovedBatch(fixture, "plan-500", allItems.subList(0, 500));
        capturePayrollPlans(run, "500", batch500, fixture);

        for (int itemCount : new int[] { 500, 5_000 }) {
            for (int concurrency : CONCURRENCY_LEVELS) {
                for (int repeat = 1; repeat <= 3; repeat++) {
                    final int concurrencyLevel = concurrency;
                    final int repeatNumber = repeat;
                    UUID payerId = accountService.openOrganizationAccount(fixture.organizationId(),
                            "E3 payroll payer " + itemCount + " c" + concurrency + " r" + repeat);
                    if (itemCount == 5_000) {
                        accountService.simulateDeposit(payerId, new Money(400_000L));
                    }
                    BatchFixture executionBatch = createApprovedBatch(fixture,
                            "execute-" + itemCount + "-c" + concurrency + "-r" + repeat,
                            allItems.subList(0, itemCount), payerId);
                    payrollService.startProcessing(executionBatch.batchId(), fixture.approverId());
                    long deadlocksBefore = deadlockCount();
                    PayrollRunResult result = runPayrollWorkers(executionBatch.batchId(), concurrency, itemCount,
                            fixture.approverId());
                    long deadlocksAfter = deadlockCount();
                    var finalBatch = payrollService.finishIfReady(executionBatch.batchId(), fixture.approverId());
                    assertThat(finalBatch.status()).isEqualTo("COMPLETED");
                    assertThat(result.successes()).isEqualTo(itemCount);
                    assertThat(result.failures()).isZero();
                    int completedItems = jdbc.queryForObject("""
                            SELECT count(*) FROM payroll_item_results r JOIN payroll_items i ON i.id = r.item_id
                            WHERE i.batch_id = ? AND r.status = 'SUCCEEDED' AND r.transfer_id IS NOT NULL
                            """, Integer.class, executionBatch.batchId());
                    int uniqueTransfers = jdbc.queryForObject("""
                            SELECT count(DISTINCT r.transfer_id) FROM payroll_item_results r
                            JOIN payroll_items i ON i.id = r.item_id WHERE i.batch_id = ? AND r.status = 'SUCCEEDED'
                            """, Integer.class, executionBatch.batchId());
                    List<UUID> accountIds = employeeAccounts.subList(0, itemCount).stream()
                            .map(AccountFixture::accountId).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
                    accountIds.add(payerId);
                    List<UUID> transferIds = jdbc.query("""
                            SELECT r.transfer_id FROM payroll_item_results r JOIN payroll_items i ON i.id = r.item_id
                            WHERE i.batch_id = ? AND r.status = 'SUCCEEDED'
                            """, (rs, row) -> rs.getObject(1, UUID.class), executionBatch.batchId());
                    int transferViolations = verifyTransferRows(transferIds);
                    int projectionMismatches = verifyProjectionMatches(accountIds);
                    int negativeBalances = countNegativeCustomerBalances(accountIds);
                    int payrollReceiptMismatches = jdbc.queryForObject("""
                            SELECT count(*) FROM payroll_item_results r
                            JOIN payroll_items i ON i.id = r.item_id
                            JOIN payroll_batches b ON b.id = i.batch_id
                            JOIN transfers t ON t.id = r.transfer_id
                            WHERE i.batch_id = ? AND (t.source_account_id <> b.payer_account_id
                                OR t.target_account_id <> i.payee_account_id OR t.amount_minor <> i.amount_minor)
                            """, Integer.class, executionBatch.batchId());
                    assertThat(completedItems).isEqualTo(itemCount);
                    assertThat(uniqueTransfers).isEqualTo(itemCount);
                    assertThat(transferViolations).isZero();
                    assertThat(payrollReceiptMismatches).isZero();
                    assertThat(projectionMismatches).isZero();
                    assertThat(negativeBalances).isZero();
                    double seconds = result.elapsedNanos() / 1_000_000_000.0;
                    Latency latency = summarize(result.samples().stream().map(OperationSample::latencyNanos).toList());
                    List<String> summary = List.of(Integer.toString(itemCount), Integer.toString(concurrency),
                            Integer.toString(repeat), Integer.toString(result.successes()),
                            Integer.toString(result.failures()), formatDouble(result.successes() / seconds),
                            formatMillis(latency.p50()), formatMillis(latency.p95()), formatMillis(latency.p99()),
                            Long.toString(result.monitor().lockWaitSamples()),
                            Long.toString(result.monitor().maxLockWaitSessions()), Integer.toString(result.monitor().maxActive()),
                            Integer.toString(result.monitor().maxPending()), formatDouble(result.cpuNanos() / 1e9),
                            Long.toString(deadlocksAfter - deadlocksBefore), finalBatch.status(),
                            Integer.toString(uniqueTransfers), Integer.toString(transferViolations),
                            Integer.toString(projectionMismatches), Integer.toString(negativeBalances));
                    appendCsv(run.resolve("payroll-concurrency-summary.csv"), summaryHeader, List.of(summary));
                    List<List<String>> sampleRows = result.samples().stream().map(sample -> List.of(Integer.toString(itemCount),
                            Integer.toString(concurrencyLevel), Integer.toString(repeatNumber),
                            Integer.toString(sample.sequence()), Integer.toString(sample.client()), sample.status(),
                            Long.toString(sample.latencyNanos()))).toList();
                    appendCsv(run.resolve("payroll-concurrency-samples.csv"), sampleHeader, sampleRows);
                    System.out.printf(Locale.ROOT,
                            "E3_H03 payroll items=%d c=%d repeat=%d items/s=%.2f p95=%.3fms poolActiveMax=%d deadlocks=%d%n",
                            itemCount, concurrency, repeat, result.successes() / seconds, latency.p95() / 1e6,
                            result.monitor().maxActive(), deadlocksAfter - deadlocksBefore);
                }
            }
        }
        InvariantCounts global = verifyGlobalFinancialInvariants();
        assertThat(global.unbalancedEntries()).isZero();
        assertThat(global.projectionMismatches()).isZero();
        assertThat(global.negativeLedgerBalances()).isZero();
        assertThat(global.transferReceiptMismatches()).isZero();
        assertThat(global.duplicateIdempotencyKeys()).isZero();
        Files.writeString(run.resolve("global-financial-invariants.json"), """
                {"unbalancedJournalEntries":%d,"projectionMismatches":%d,"negativeLedgerBalances":%d,"transferReceiptMismatches":%d,"duplicateIdempotencyKeys":%d}
                """.formatted(global.unbalancedEntries(), global.projectionMismatches(), global.negativeLedgerBalances(),
                global.transferReceiptMismatches(), global.duplicateIdempotencyKeys()));
        Files.writeString(run.resolve("source-commit.txt"), sourceCommit() + System.lineSeparator());
        System.out.println("E3_H03_EVIDENCE=" + run.toAbsolutePath());
    }

    private PayrollRunResult runPayrollWorkers(UUID batchId, int concurrency, int expectedItems, UUID executorId)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch ready = new CountDownLatch(concurrency);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger sequence = new AtomicInteger();
        ConcurrentLinkedQueue<OperationSample> samples = new ConcurrentLinkedQueue<>();
        DatabaseMonitor monitor = new DatabaseMonitor();
        long cpuBefore = 0L;
        try {
            List<Future<?>> futures = new ArrayList<>(concurrency);
            for (int worker = 0; worker < concurrency; worker++) {
                int workerNumber = worker;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    while (completed.get() < expectedItems) {
                        int sampleNumber = sequence.getAndIncrement();
                        long startedAt = System.nanoTime();
                        try {
                            var result = payrollItemExecutor.executeNext(batchId, executorId);
                            long latency = System.nanoTime() - startedAt;
                            if (result == null) {
                                if (completed.get() < expectedItems) {
                                    Thread.sleep(1);
                                }
                                continue;
                            }
                            if ("SUCCEEDED".equals(result.status())) {
                                successes.incrementAndGet();
                                completed.incrementAndGet();
                                samples.add(new OperationSample(workerNumber, sampleNumber, "SUCCEEDED", "",
                                        result.transferId(), latency));
                            }
                            else {
                                failures.incrementAndGet();
                                completed.incrementAndGet();
                                samples.add(new OperationSample(workerNumber, sampleNumber, result.status(),
                                        result.errorCode() == null ? "" : result.errorCode(), result.transferId(), latency));
                            }
                        }
                        catch (RuntimeException exception) {
                            failures.incrementAndGet();
                            samples.add(new OperationSample(workerNumber, sampleNumber, "FAILED",
                                    exception.getClass().getSimpleName(), null, System.nanoTime() - startedAt));
                            throw exception;
                        }
                    }
                    return null;
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).as("payroll workers become ready").isTrue();
            monitor.start();
            Thread.sleep(100);
            cpuBefore = processCpuNanos();
            long startedAt = System.nanoTime();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(45, TimeUnit.MINUTES);
            }
            long elapsed = System.nanoTime() - startedAt;
            monitor.close();
            return new PayrollRunResult(successes.get(), failures.get(), elapsed, processCpuNanos() - cpuBefore,
                    List.copyOf(samples), monitor.snapshot());
        }
        finally {
            start.countDown();
            monitor.close();
            executor.shutdownNow();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    private PayrollFixture createPayrollFixture() {
        UUID maker = newCustomer("e3-h03-maker");
        UUID approver = newCustomer("e3-h03-approver");
        UUID admin = newCustomer("e3-h03-admin");
        UUID employeeOwner = newCustomer("e3-h03-employee-owner");
        String makerEmail = emailFor(maker);
        var organization = organizationService.create(admin, "E3 payroll organization " + UUID.randomUUID());
        organizationService.addMember(admin, organization.id(), makerEmail, List.of(OrganizationRole.PAYROLL_MAKER));
        organizationService.addMember(admin, organization.id(), emailFor(approver),
                List.of(OrganizationRole.PAYROLL_APPROVER, OrganizationRole.OPERATIONS));
        return new PayrollFixture(maker, approver, employeeOwner, organization.id());
    }

    private BatchFixture createApprovedBatch(PayrollFixture fixture, String label,
            List<PayrollService.NewPayrollItem> items) {
        UUID payer = accountService.openOrganizationAccount(fixture.organizationId(), "E3 payer " + label);
        return createApprovedBatch(fixture, label, items, payer);
    }

    private BatchFixture createApprovedBatch(PayrollFixture fixture, String label,
            List<PayrollService.NewPayrollItem> items, UUID payer) {
        var draft = payrollService.createDraft(fixture.makerId(), fixture.organizationId(), "E3 " + label, payer);
        for (int offset = 0; offset < items.size(); offset += 1_000) {
            payrollService.addItems(fixture.makerId(), draft.id(),
                    items.subList(offset, Math.min(offset + 1_000, items.size())));
        }
        var submitted = payrollService.submit(fixture.makerId(), draft.id());
        var approved = payrollService.approve(fixture.approverId(), submitted.id());
        assertThat(approved.status()).isEqualTo("APPROVED");
        return new BatchFixture(approved.id(), payer);
    }

    private void capturePayrollPlans(Path run, String size, BatchFixture batch, PayrollFixture fixture) throws IOException {
        String pendingClaimSql = """
                SELECT b.id AS batch_id, b.status AS batch_status, b.payer_account_id,
                       b.created_by_customer_id, i.id AS item_id, i.payee_account_id,
                       i.amount_minor, r.status AS item_status, r.transfer_id
                FROM payroll_batches b
                JOIN payroll_items i ON i.batch_id = b.id
                JOIN payroll_item_results r ON r.item_id = i.id
                WHERE b.id = ? AND r.status = 'PENDING'
                ORDER BY i.sequence_no LIMIT 1 FOR UPDATE OF r SKIP LOCKED
                """;
        capturePlan(run, "payroll-pending-item-claim-" + size, pendingClaimSql, batch.batchId());
        String progressSql = """
                SELECT count(*) FILTER (WHERE r.status = 'PENDING') AS pending_count,
                       count(*) FILTER (WHERE r.status = 'SUCCEEDED') AS succeeded_count,
                       count(*) FILTER (WHERE r.status = 'FAILED') AS failed_count
                FROM payroll_items i JOIN payroll_item_results r ON r.item_id = i.id
                WHERE i.batch_id = ?
                """;
        capturePlan(run, "payroll-batch-progress-" + size, progressSql, batch.batchId());
        String summarySql = """
                SELECT b.id, b.organization_id, b.name, b.payer_account_id, b.status,
                       count(i.id) AS item_count, COALESCE(sum(i.amount_minor), 0) AS total_minor,
                       count(*) FILTER (WHERE r.status = 'PENDING') AS pending_count,
                       count(*) FILTER (WHERE r.status = 'SUCCEEDED') AS succeeded_count,
                       count(*) FILTER (WHERE r.status = 'FAILED') AS failed_count,
                       COALESCE(sum(i.amount_minor) FILTER (WHERE r.status = 'SUCCEEDED'), 0) AS succeeded_minor,
                       b.created_by_customer_id, b.submitted_by_customer_id, b.approved_by_customer_id,
                       b.rejected_by_customer_id, b.rejection_reason, b.content_digest,
                       b.approved_content_digest, b.created_at, b.submitted_at, b.approved_at,
                       b.rejected_at, b.started_at, b.completed_at
                FROM payroll_batches b LEFT JOIN payroll_items i ON i.batch_id = b.id
                LEFT JOIN payroll_item_results r ON r.item_id = i.id
                WHERE b.id = ? GROUP BY b.id
                """;
        capturePlan(run, "payroll-batch-summary-" + size, summarySql, batch.batchId());
        if (size.equals("5000")) {
            String roleSql = """
                    SELECT r.role FROM organization_memberships m
                    JOIN organization_membership_roles r
                      ON r.organization_id = m.organization_id AND r.customer_id = m.customer_id
                    JOIN organizations o ON o.id = m.organization_id AND o.status = 'ACTIVE'
                    WHERE m.organization_id = ? AND m.customer_id = ? AND m.status = 'ACTIVE'
                    ORDER BY r.role
                    """;
            capturePlan(run, "organization-membership-role-authorization", roleSql,
                    fixture.organizationId(), fixture.makerId());
        }
    }

    private void createSyntheticOrganizationMembers(UUID organizationId, int count) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement customers = connection.prepareStatement("""
                    INSERT INTO customers (id, email_normalized, password_hash, display_name) VALUES (?, ?, ?, ?)
                    """); PreparedStatement memberships = connection.prepareStatement("""
                    INSERT INTO organization_memberships (organization_id, customer_id) VALUES (?, ?)
                    """); PreparedStatement roles = connection.prepareStatement("""
                    INSERT INTO organization_membership_roles (organization_id, customer_id, role)
                    VALUES (?, ?, 'FINANCE_VIEWER')
                    """)) {
                for (int index = 0; index < count; index++) {
                    UUID memberId = UUID.randomUUID();
                    customers.setObject(1, memberId);
                    customers.setString(2, emailFor(memberId));
                    customers.setString(3, "synthetic-e3-hash-not-for-authentication");
                    customers.setString(4, "E3 synthetic member " + index);
                    customers.addBatch();
                    memberships.setObject(1, organizationId);
                    memberships.setObject(2, memberId);
                    memberships.addBatch();
                    roles.setObject(1, organizationId);
                    roles.setObject(2, memberId);
                    roles.addBatch();
                    if (index % 1_000 == 999) {
                        customers.executeBatch();
                        memberships.executeBatch();
                        roles.executeBatch();
                    }
                }
                customers.executeBatch();
                memberships.executeBatch();
                roles.executeBatch();
                connection.commit();
            }
            catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private UUID newCustomer(String label) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO customers (id, email_normalized, password_hash, display_name)
                VALUES (?, ?, 'synthetic-e3-hash-not-for-authentication', ?)
                """, id, emailFor(id), ("E3 " + label).substring(0, Math.min(100, ("E3 " + label).length())));
        return id;
    }

    private String emailFor(UUID id) {
        return "e3-" + id + "@example.test";
    }

    private List<AccountFixture> createPersonalAccounts(UUID owner, int count, String prefix, long openingBalanceMinor)
            throws SQLException {
        List<AccountFixture> accounts = new ArrayList<>(count);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement accountRows = connection.prepareStatement("""
                    INSERT INTO accounts (id, owner_customer_id, display_name) VALUES (?, ?, ?)
                    """); PreparedStatement ledgerRows = connection.prepareStatement("""
                    INSERT INTO ledger_accounts (id, customer_account_id, kind, normal_side, is_system, currency)
                    VALUES (?, ?, 'CUSTOMER_DEPOSIT', 'CREDIT', FALSE, 'NZD')
                    """); PreparedStatement projections = connection.prepareStatement("""
                    INSERT INTO account_balance_projection (ledger_account_id, currency, balance_minor)
                    VALUES (?, 'NZD', ?)
                    """); PreparedStatement journals = connection.prepareStatement("""
                    INSERT INTO journal_entries (id, entry_type, reference) VALUES (?, 'OPENING_FUNDING', ?)
                    """); PreparedStatement postings = connection.prepareStatement("""
                    INSERT INTO postings (id, journal_entry_id, ledger_account_id, side, amount_minor, currency)
                    VALUES (?, ?, ?, ?, ?, 'NZD')
                    """)) {
                for (int index = 0; index < count; index++) {
                    UUID accountId = UUID.randomUUID();
                    UUID ledgerId = UUID.randomUUID();
                    UUID journalId = UUID.randomUUID();
                    accounts.add(new AccountFixture(accountId, ledgerId, owner));
                    accountRows.setObject(1, accountId);
                    accountRows.setObject(2, owner);
                    String displayName = prefix + "-" + index + "-" + accountId.toString().substring(0, 8);
                    accountRows.setString(3, displayName.substring(0, Math.min(80, displayName.length())));
                    accountRows.addBatch();
                    ledgerRows.setObject(1, ledgerId);
                    ledgerRows.setObject(2, accountId);
                    ledgerRows.addBatch();
                    projections.setObject(1, ledgerId);
                    projections.setLong(2, openingBalanceMinor);
                    projections.addBatch();
                    if (openingBalanceMinor > 0) {
                        journals.setObject(1, journalId);
                        journals.setString(2, "e3-opening-" + accountId);
                        journals.addBatch();
                        addPosting(postings, journalId, RESERVE_ID, "DEBIT", openingBalanceMinor);
                        addPosting(postings, journalId, ledgerId, "CREDIT", openingBalanceMinor);
                    }
                    if (index % 1_000 == 999) {
                        accountRows.executeBatch();
                        ledgerRows.executeBatch();
                        projections.executeBatch();
                        if (openingBalanceMinor > 0) {
                            journals.executeBatch();
                            postings.executeBatch();
                        }
                    }
                }
                accountRows.executeBatch();
                ledgerRows.executeBatch();
                projections.executeBatch();
                if (openingBalanceMinor > 0) {
                    journals.executeBatch();
                    postings.executeBatch();
                    try (PreparedStatement reserveUpdate = connection.prepareStatement("""
                            UPDATE account_balance_projection SET balance_minor = balance_minor + ?
                            WHERE ledger_account_id = ?
                            """)) {
                        reserveUpdate.setLong(1, Math.multiplyExact(openingBalanceMinor, count));
                        reserveUpdate.setObject(2, RESERVE_ID);
                        assertThat(reserveUpdate.executeUpdate()).isEqualTo(1);
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

    private void addPosting(PreparedStatement statement, UUID journal, UUID ledger, String side, long amount)
            throws SQLException {
        statement.setObject(1, UUID.randomUUID());
        statement.setObject(2, journal);
        statement.setObject(3, ledger);
        statement.setString(4, side);
        statement.setLong(5, amount);
        statement.addBatch();
    }

    private void seedDepositHistory(AccountFixture account, int count, String namespace) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement journals = connection.prepareStatement("""
                    INSERT INTO journal_entries (id, entry_type, reference) VALUES (?, 'SIMULATED_DEPOSIT', ?)
                    """); PreparedStatement postings = connection.prepareStatement("""
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
                try (PreparedStatement projections = connection.prepareStatement("""
                        UPDATE account_balance_projection SET balance_minor = balance_minor + ?
                        WHERE ledger_account_id = ?
                        """)) {
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

    private long postingCount(UUID ledgerId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM postings WHERE ledger_account_id = ?", Long.class, ledgerId);
        return count == null ? 0 : count;
    }

    private int verifyTransferRows(List<UUID> transferIds) {
        if (transferIds.isEmpty()) return 0;
        String placeholders = String.join(",", Collections.nCopies(transferIds.size(), "?"));
        List<Object> args = new ArrayList<>(transferIds);
        return jdbc.queryForObject("""
                SELECT count(*) FROM transfers t JOIN journal_entries j ON j.id = t.journal_entry_id
                WHERE t.id IN (%s) AND (
                    j.entry_type <> 'TRANSFER' OR
                    (SELECT count(*) FROM postings p WHERE p.journal_entry_id = j.id) <> 2 OR
                    (SELECT COALESCE(sum(amount_minor),0) FROM postings p
                      JOIN ledger_accounts la ON la.id = p.ledger_account_id
                     WHERE p.journal_entry_id = j.id AND la.customer_account_id = t.source_account_id
                       AND p.side = 'DEBIT') <> t.amount_minor OR
                    (SELECT COALESCE(sum(amount_minor),0) FROM postings p
                      JOIN ledger_accounts la ON la.id = p.ledger_account_id
                     WHERE p.journal_entry_id = j.id AND la.customer_account_id = t.target_account_id
                       AND p.side = 'CREDIT') <> t.amount_minor OR
                    (SELECT count(*) FROM idempotency_records r
                     WHERE r.transfer_id = t.id AND r.status = 'SUCCEEDED'
                       AND r.response_payload->>'transferId' = t.id::text) <> 1)
                """.formatted(placeholders), Integer.class, args.toArray());
    }

    private InvariantCounts verifyGlobalFinancialInvariants() {
        return jdbc.queryForObject("""
                WITH balances AS (
                    SELECT la.id, COALESCE(sum(CASE WHEN p.side = la.normal_side
                        THEN p.amount_minor ELSE -p.amount_minor END), 0) AS ledger_minor
                    FROM ledger_accounts la LEFT JOIN postings p ON p.ledger_account_id = la.id
                    GROUP BY la.id, la.normal_side
                ), unbalanced AS (
                    SELECT j.id FROM journal_entries j LEFT JOIN postings p ON p.journal_entry_id = j.id
                    GROUP BY j.id
                    HAVING count(p.id) < 2 OR
                        COALESCE(sum(p.amount_minor) FILTER (WHERE p.side = 'DEBIT'), 0) <>
                        COALESCE(sum(p.amount_minor) FILTER (WHERE p.side = 'CREDIT'), 0)
                )
                SELECT
                    (SELECT count(*) FROM unbalanced) AS unbalanced_entries,
                    (SELECT count(*) FROM balances b JOIN account_balance_projection p ON p.ledger_account_id = b.id
                     WHERE b.ledger_minor <> p.balance_minor) AS projection_mismatches,
                    (SELECT count(*) FROM balances WHERE ledger_minor < 0) AS negative_ledger_balances,
                    (SELECT count(*) FROM transfers t JOIN journal_entries j ON j.id = t.journal_entry_id
                     WHERE j.entry_type <> 'TRANSFER' OR
                       (SELECT count(*) FROM postings p WHERE p.journal_entry_id = j.id) <> 2 OR
                       (SELECT count(*) FROM idempotency_records r
                        WHERE r.transfer_id = t.id AND r.status = 'SUCCEEDED'
                          AND r.response_payload->>'transferId' = t.id::text) <> 1) AS transfer_receipt_mismatches,
                    (SELECT count(*) FROM (
                        SELECT owner_customer_id, operation, idempotency_key FROM idempotency_records
                        GROUP BY owner_customer_id, operation, idempotency_key HAVING count(*) > 1
                    ) duplicates) AS duplicate_idempotency_keys
                """, (rs, row) -> new InvariantCounts(rs.getLong("unbalanced_entries"),
                rs.getLong("projection_mismatches"), rs.getLong("negative_ledger_balances"),
                rs.getLong("transfer_receipt_mismatches"), rs.getLong("duplicate_idempotency_keys")));
    }

    private int verifyProjectionMatches(List<UUID> accountIds) {
        if (accountIds.isEmpty()) return 0;
        String placeholders = String.join(",", Collections.nCopies(accountIds.size(), "?"));
        return jdbc.queryForObject("""
                WITH calculated AS (
                    SELECT la.id, COALESCE(sum(CASE WHEN p.side = la.normal_side
                        THEN p.amount_minor ELSE -p.amount_minor END), 0) AS balance_minor
                    FROM ledger_accounts la LEFT JOIN postings p ON p.ledger_account_id = la.id
                    WHERE la.customer_account_id IN (%s) GROUP BY la.id, la.normal_side
                )
                SELECT count(*) FROM calculated c JOIN account_balance_projection b ON b.ledger_account_id = c.id
                WHERE b.balance_minor <> c.balance_minor
                """.formatted(placeholders), Integer.class, accountIds.toArray());
    }

    private int countNegativeCustomerBalances(List<UUID> accountIds) {
        if (accountIds.isEmpty()) return 0;
        String placeholders = String.join(",", Collections.nCopies(accountIds.size(), "?"));
        return jdbc.queryForObject("""
                SELECT count(*) FROM (
                    SELECT la.id, COALESCE(sum(CASE WHEN p.side = la.normal_side
                        THEN p.amount_minor ELSE -p.amount_minor END), 0) AS balance_minor
                    FROM ledger_accounts la LEFT JOIN postings p ON p.ledger_account_id = la.id
                    WHERE la.customer_account_id IN (%s) GROUP BY la.id, la.normal_side
                    HAVING COALESCE(sum(CASE WHEN p.side = la.normal_side
                        THEN p.amount_minor ELSE -p.amount_minor END), 0) < 0
                ) negative
                """.formatted(placeholders), Integer.class, accountIds.toArray());
    }

    private void capturePlan(Path run, String name, String sql, Object... parameters) throws IOException {
        List<String> lines = jdbc.query("EXPLAIN (ANALYZE, BUFFERS, SETTINGS) " + sql,
                (rs, row) -> rs.getString(1), parameters);
        String boundValues = java.util.Arrays.stream(parameters).map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(", "));
        String contents = "SQL:\n" + sql + "\n\nPARAMETERS (synthetic): " + boundValues
                + "\n\nPLAN:\n" + String.join("\n", lines) + "\n";
        Path directory = run.resolve("query-plans");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(name + ".txt"), contents);
        System.out.println("E3_PLAN=" + name + "\n" + String.join(" | ", lines));
    }

    private void preflightLargestFixture() {
        OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        try {
            long freeDisk = Files.getFileStore(repositoryRoot()).getUsableSpace();
            assertThat(freeDisk).as("D drive free space before 100k-row setup").isGreaterThan(10L * 1024 * 1024 * 1024);
            assertThat(os.getFreeMemorySize()).as("host available memory before 100k-row setup")
                    .isGreaterThan(1L * 1024 * 1024 * 1024);
            assertThat(jdbc.queryForObject("SELECT pg_database_size(current_database())", Long.class))
                    .isLessThan(2L * 1024 * 1024 * 1024);
            System.out.println("E3_100K_PREFLIGHT=PASS freeDiskBytes=" + freeDisk + " availableMemoryBytes="
                    + os.getFreeMemorySize());
        }
        catch (IOException exception) {
            throw new IllegalStateException("Could not verify the 100k fixture resource preflight.", exception);
        }
    }

    private long deadlockCount() {
        Long count = jdbc.queryForObject("""
                SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()
                """, Long.class);
        return count == null ? 0 : count;
    }

    private HikariDataSource hikari() {
        if (!(dataSource instanceof HikariDataSource hikari)) {
            throw new IllegalStateException("E3 requires the configured Hikari connection pool.");
        }
        return hikari;
    }

    private Path evidenceRoot() {
        return Path.of(System.getProperty("e3.output", "../evidence/e3")).toAbsolutePath().normalize();
    }

    private Path repositoryRoot() {
        return evidenceRoot().getParent().getParent();
    }

    private Path newRunDirectory(String name) throws IOException {
        Path directory = evidenceRoot().resolve("measurements").resolve(name + "-" + RUN_STAMP.format(Instant.now()));
        Files.createDirectories(directory);
        return directory;
    }

    private String sourceCommit() throws IOException, InterruptedException {
        return git("log", "-1", "--format=%H", "--",
                "backend/src/test/java/nz/ac/ledgercore/performance/E3PerformanceExperimentHarness.java").trim();
    }

    private String git(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(repositoryRoot().toFile()).redirectErrorStream(true).start();
        String result = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (process.waitFor() != 0) throw new IOException("Git command failed: " + String.join(" ", command));
        return result;
    }

    private void appendCsv(Path file, String header, List<List<String>> rows) throws IOException {
        Files.createDirectories(file.getParent());
        StringBuilder text = new StringBuilder();
        if (!Files.exists(file)) text.append(header).append('\n');
        for (List<String> row : rows) {
            text.append(row.stream().map(this::csv).collect(java.util.stream.Collectors.joining(","))).append('\n');
        }
        Files.writeString(file, text.toString(), java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    private String csv(String value) {
        String normalized = value == null ? "" : value;
        return normalized.contains(",") || normalized.contains("\"")
                ? "\"" + normalized.replace("\"", "\"\"") + "\"" : normalized;
    }

    private void addSamples(List<List<String>> output, int history, int repeat, String kind, List<Long> values) {
        for (int index = 0; index < values.size(); index++) {
            output.add(List.of(Integer.toString(history), Integer.toString(repeat), kind, Integer.toString(index + 1),
                    Long.toString(values.get(index))));
        }
    }

    private long percentile(List<Long> data, double fraction) {
        if (data.isEmpty()) return 0;
        List<Long> sorted = data.stream().sorted().toList();
        int index = Math.max(0, (int) Math.ceil(fraction * sorted.size()) - 1);
        return sorted.get(index);
    }

    private Latency summarize(List<Long> values) {
        return new Latency(percentile(values, .50), percentile(values, .95), percentile(values, .99));
    }

    private String formatMillis(long nanos) { return formatDouble(nanos / 1_000_000.0); }
    private String formatDouble(double value) { return String.format(Locale.ROOT, "%.6f", value); }
    private String shortName(String value) { return value.replace("-", "").replace(" ", "").substring(0, Math.min(10, value.replace("-", "").replace(" ", "").length())); }

    private long processCpuNanos() {
        return ((OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime();
    }

    private final class DatabaseMonitor implements AutoCloseable {
        private final AtomicBoolean running = new AtomicBoolean();
        private final LongAdder lockWaitSamples = new LongAdder();
        private final AtomicInteger maxLockWaitSessions = new AtomicInteger();
        private final AtomicInteger maxActive = new AtomicInteger();
        private final AtomicInteger maxPending = new AtomicInteger();
        private volatile int poolMaximum;
        private Thread thread;

        void start() {
            running.set(true);
            thread = new Thread(this::sample, "e3-database-monitor");
            thread.setDaemon(true);
            thread.start();
        }

        private void sample() {
            HikariDataSource hikari = E3PerformanceExperimentHarness.this.hikari();
            poolMaximum = hikari.getMaximumPoolSize();
            while (running.get()) {
                try (Connection connection = DriverManager.getConnection(hikari.getJdbcUrl(), hikari.getUsername(),
                        hikari.getPassword()); Statement statement = connection.createStatement();
                     var rows = statement.executeQuery("""
                             SELECT count(*) FROM pg_stat_activity
                             WHERE datname = current_database() AND state = 'active' AND wait_event_type = 'Lock'
                             """)) {
                    rows.next();
                    int waiters = rows.getInt(1);
                    if (waiters > 0) lockWaitSamples.increment();
                    maxLockWaitSessions.accumulateAndGet(waiters, Math::max);
                }
                catch (SQLException ignored) {
                    // Sampling is observational; a transient monitor failure never changes worker outcomes.
                }
                HikariPoolMXBean poolBean = hikari.getHikariPoolMXBean();
                if (poolBean != null) {
                    maxActive.accumulateAndGet(poolBean.getActiveConnections(), Math::max);
                    maxPending.accumulateAndGet(poolBean.getThreadsAwaitingConnection(), Math::max);
                }
                try { Thread.sleep(25); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return; }
            }
        }

        MonitorSnapshot snapshot() {
            return new MonitorSnapshot(lockWaitSamples.sum(), maxLockWaitSessions.get(), maxActive.get(),
                    maxPending.get(), poolMaximum);
        }

        @Override public void close() {
            running.set(false);
            if (thread != null) {
                thread.interrupt();
                try { thread.join(5_000); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            }
        }
    }

    private record AccountFixture(UUID accountId, UUID ledgerId, UUID ownerId) { }
    private record PayrollFixture(UUID makerId, UUID approverId, UUID employeeOwnerId, UUID organizationId) { }
    private record BatchFixture(UUID batchId, UUID payerAccountId) { }
    private record HistoryCursor(Instant createdAt, UUID id) { }
    private record Latency(long p50, long p95, long p99) { }
    private record OperationSample(int client, int sequence, String status, String errorCode,
            UUID transferId, long latencyNanos) { }
    private record MonitorSnapshot(long lockWaitSamples, int maxLockWaitSessions, int maxActive,
            int maxPending, int poolMaximum) { }
    private record ConcurrentResult(int totalOperations, int successes, int failures, long elapsedNanos,
            long cpuNanos, List<OperationSample> samples, MonitorSnapshot monitor) { }
    private record PayrollRunResult(int successes, int failures, long elapsedNanos, long cpuNanos,
            List<OperationSample> samples, MonitorSnapshot monitor) { }
    private record InvariantCounts(long unbalancedEntries, long projectionMismatches, long negativeLedgerBalances,
            long transferReceiptMismatches, long duplicateIdempotencyKeys) { }
}
