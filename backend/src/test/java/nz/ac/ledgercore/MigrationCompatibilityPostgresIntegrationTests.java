package nz.ac.ledgercore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.CRC32;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Round 2 (R2-04) migration compatibility evidence. Each scenario migrates an isolated, disposable schema inside the
 * test database with the production Flyway scripts, seeds representative valid data at an older version, upgrades to
 * the latest version, and verifies that no row was lost or rewritten and that the new constraints hold.
 */
@ActiveProfiles("test")
@SpringBootTest
class MigrationCompatibilityPostgresIntegrationTests {

    /**
     * Flyway checksums of the published V1-V6 scripts as recorded in every Round 1 database built from committed
     * source. A change here means a historical migration was edited in place, which deployed databases reject.
     */
    private static final Map<String, Integer> PUBLISHED_CHECKSUMS = new LinkedHashMap<>();

    static {
        PUBLISHED_CHECKSUMS.put("V1__create_customer_and_account_tables.sql", -1500817779);
        PUBLISHED_CHECKSUMS.put("V2__create_ledger_tables.sql", 1445661118);
        PUBLISHED_CHECKSUMS.put("V3__create_transfers_and_idempotency.sql", -1568142027);
        PUBLISHED_CHECKSUMS.put("V4__enforce_idempotency_receipt_lifecycle.sql", 512865030);
        PUBLISHED_CHECKSUMS.put("V5__add_organizations_and_corporate_account_ownership.sql", -1572619169);
        PUBLISHED_CHECKSUMS.put("V6__add_durable_payroll_batches_and_items.sql", -371991565);
        // Round 4: V7-V11 were published by Rounds 2 and 3 and are pinned to the checksums in every V11 database.
        PUBLISHED_CHECKSUMS.put("V7__harden_payroll_intent_and_result_integrity.sql", 638694306);
        PUBLISHED_CHECKSUMS.put("V8__add_policy_b_payments_and_durable_events.sql", -1628563391);
        PUBLISHED_CHECKSUMS.put("V9__harden_clearing_callback_lifecycle.sql", -1866598791);
        PUBLISHED_CHECKSUMS.put("V10__index_pending_payroll_results_by_batch.sql", 794183703);
        PUBLISHED_CHECKSUMS.put("V11__drop_unhelpful_pending_payroll_index.sql", 1670107448);
    }

    private static final UUID RESERVE = UUID.fromString("00000000-0000-5000-8000-000000000001");

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    Flyway applicationFlyway;

    @Value("${spring.datasource.url}")
    String url;

    @Value("${spring.datasource.username}")
    String username;

    @Value("${spring.datasource.password:}")
    String password;

    @Test
    void applicationFlywayRegistersTheV5LegacyBackfillCallback() {
        assertThat(applicationFlyway.getConfiguration().getCallbacks())
                .anySatisfy(callback -> assertThat(callback).isInstanceOf(LegacyTransferOwnerBackfillCallback.class));
    }

    @Test
    void publishedMigrationsKeepTheirRecordedFlywayChecksums() throws IOException {
        for (Map.Entry<String, Integer> migration : PUBLISHED_CHECKSUMS.entrySet()) {
            assertThat(flywayChecksum("db/migration/" + migration.getKey()))
                    .as(migration.getKey()).isEqualTo(migration.getValue());
        }
    }

    @Test
    void freshSchemaMigratesToTheLatestVersionWithPublishedChecksums() {
        withSchema(schema -> {
            Flyway flyway = flyway(schema, MigrationVersion.LATEST);
            flyway.migrate();
            flyway.validate();
            List<MigrationInfo> applied = versioned(flyway);
            assertThat(applied).extracting(info -> info.getVersion().getVersion())
                    .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13");
            assertThat(applied).allSatisfy(info -> assertThat(info.getState().isApplied()).isTrue());
            for (MigrationInfo info : applied) {
                Integer published = PUBLISHED_CHECKSUMS.get(info.getScript());
                if (published != null) {
                    assertThat(info.getChecksum()).as(info.getScript()).isEqualTo(published);
                }
            }
            assertThat(unvalidatedConstraints(schema)).isZero();
        });
    }

    @Test
    void populatedV4PersonalBankingSchemaUpgradesWithoutChangingItsHistory() {
        withSchema(schema -> {
            flyway(schema, MigrationVersion.fromVersion("4")).migrate();
            Seed seed = new Seed(schema);
            UUID alice = seed.customer("alice");
            UUID bob = seed.customer("bob");
            UUID aliceAccount = seed.fundedAccount(alice, null, 100_000L);
            UUID bobAccount = seed.fundedAccount(bob, null, 100_000L);
            UUID transfer = seed.inTransaction(() -> seed.transfer(alice, aliceAccount, bobAccount, 2_500L, "v4-key",
                    false, null));
            String before = seed.ledgerFingerprint();

            flyway(schema, MigrationVersion.LATEST).migrate();

            assertThat(seed.ledgerFingerprint()).isEqualTo(before);
            assertThat(seed.jdbc.queryForObject("""
                    SELECT source_owner_customer_id = owner_customer_id AND source_organization_id IS NULL
                    FROM transfers WHERE id = ?
                    """, Boolean.class, transfer)).isTrue();
            assertThat(seed.balance(aliceAccount)).isEqualTo(97_500L);
            assertThat(seed.balance(bobAccount)).isEqualTo(102_500L);
            assertThat(seed.projectionMismatches()).isZero();
            assertThat(unvalidatedConstraints(schema)).isZero();
            // R2-08: the V5 backfill ran, and transfer history is append-only again afterwards.
            assertThat(seed.jdbc.queryForObject("""
                    SELECT tgenabled FROM pg_trigger
                    WHERE tgrelid = 'transfers'::regclass AND tgname = 'tr_transfers_append_only'
                    """, String.class)).isEqualTo("O");
            assertThatThrownBy(() -> seed.jdbc.update("UPDATE transfers SET amount_minor = amount_minor WHERE id = ?",
                    transfer)).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> seed.jdbc.update("""
                    INSERT INTO accounts (id, owner_customer_id, owner_organization_id, display_name)
                    VALUES (?, NULL, NULL, 'Ownerless')
                    """, UUID.randomUUID())).isInstanceOf(DataAccessException.class);
        });
    }

    @Test
    void populatedV5CorporateSchemaUpgradesThroughPayrollMigrations() {
        withSchema(schema -> {
            flyway(schema, MigrationVersion.fromVersion("5")).migrate();
            Seed seed = new Seed(schema);
            UUID admin = seed.customer("admin");
            UUID employee = seed.customer("employee");
            UUID organization = seed.organization(admin, "PAYROLL_MAKER");
            UUID corporate = seed.fundedAccount(null, organization, 100_000L);
            UUID personal = seed.fundedAccount(employee, null, 100_000L);
            UUID transfer = seed.inTransaction(() -> seed.transfer(admin, corporate, personal, 4_000L, "v5-corporate",
                    true, organization));
            String before = seed.ledgerFingerprint();

            flyway(schema, MigrationVersion.LATEST).migrate();

            assertThat(seed.ledgerFingerprint()).isEqualTo(before);
            assertThat(seed.jdbc.queryForObject("""
                    SELECT source_organization_id = ? AND source_owner_customer_id IS NULL AND owner_customer_id = ?
                    FROM transfers WHERE id = ?
                    """, Boolean.class, organization, admin, transfer)).isTrue();
            assertThat(seed.balance(corporate)).isEqualTo(96_000L);
            assertThat(seed.projectionMismatches()).isZero();
            assertThat(unvalidatedConstraints(schema)).isZero();
        });
    }

    @Test
    void populatedV6PayrollSchemaUpgradesToV8WithoutRewritingResults() {
        withSchema(schema -> {
            flyway(schema, MigrationVersion.fromVersion("6")).migrate();
            Seed seed = new Seed(schema);
            PayrollSeed payroll = seed.payrollFixture();
            List<Map<String, Object>> resultsBefore = seed.jdbc.queryForList("""
                    SELECT item_id, status, attempt_count, transfer_id, error_code, error_message, updated_at, completed_at
                    FROM payroll_item_results ORDER BY item_id
                    """);
            String ledgerBefore = seed.ledgerFingerprint();

            flyway(schema, MigrationVersion.LATEST).migrate();

            assertThat(seed.jdbc.queryForList("""
                    SELECT item_id, status, attempt_count, transfer_id, error_code, error_message, updated_at, completed_at
                    FROM payroll_item_results ORDER BY item_id
                    """)).isEqualTo(resultsBefore);
            assertThat(seed.jdbc.queryForObject("""
                    SELECT count(*) FROM payroll_item_results r JOIN payroll_items i ON i.id = r.item_id
                    WHERE r.batch_id IS DISTINCT FROM i.batch_id
                    """, Integer.class)).isZero();
            assertThat(seed.ledgerFingerprint()).isEqualTo(ledgerBefore);
            assertThat(unvalidatedConstraints(schema)).isZero();

            // The upgraded schema enforces R2-01 and R2-02 on the migrated rows.
            assertThatThrownBy(() -> seed.jdbc.update("UPDATE payroll_items SET batch_id = ?, sequence_no = 99 WHERE id = ?",
                    payroll.draftBatch(), payroll.processingSucceededItem())).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> seed.jdbc.update("DELETE FROM payroll_item_results WHERE item_id = ?",
                    payroll.processingSucceededItem())).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> seed.jdbc.update("""
                    UPDATE payroll_batches SET status = 'COMPLETED', completed_at = clock_timestamp() WHERE id = ?
                    """, payroll.processingBatch())).isInstanceOf(DataAccessException.class);
            UUID added = seed.addDraftItemLikeTheApplication(payroll.draftBatch());
            assertThat(seed.jdbc.queryForObject("SELECT batch_id FROM payroll_item_results WHERE item_id = ?",
                    UUID.class, added)).isEqualTo(payroll.draftBatch());
        });
    }

    @Test
    void populatedV8ClearingIntentUpgradesAndEnforcesForwardOnlyOutcomeTransitions() {
        withSchema(schema -> {
            flyway(schema, MigrationVersion.fromVersion("8")).migrate();
            Seed seed = new Seed(schema);
            ClearingSeed payment = seed.corporatePaymentAwaitingClearing();
            String bookingReference = "corporate-payment:" + payment.paymentId() + ":booking";
            assertThat(seed.jdbc.queryForObject("SELECT status FROM corporate_payment_intents WHERE id = ?",
                    String.class, payment.paymentId())).isEqualTo("PENDING_CLEARING");
            assertThat(seed.jdbc.queryForObject("SELECT count(*) FROM postings p JOIN journal_entries e ON e.id = p.journal_entry_id WHERE e.reference = ?",
                    Integer.class, bookingReference)).isEqualTo(2);

            flyway(schema, MigrationVersion.LATEST).migrate();

            assertThat(seed.jdbc.queryForObject("SELECT status FROM corporate_payment_intents WHERE id = ?",
                    String.class, payment.paymentId())).isEqualTo("PENDING_CLEARING");
            assertThat(seed.jdbc.queryForObject("SELECT status FROM clearing_instructions WHERE operation_id = ?",
                    String.class, payment.operationId())).isEqualTo("READY");
            assertThat(unvalidatedConstraints(schema)).isZero();
            seed.jdbc.update("UPDATE corporate_payment_intents SET external_status = 'UNKNOWN', status = 'UNKNOWN' WHERE id = ?",
                    payment.paymentId());
            seed.jdbc.update("UPDATE clearing_instructions SET status = 'UNKNOWN' WHERE operation_id = ?",
                    payment.operationId());
            assertThatThrownBy(() -> seed.jdbc.update("UPDATE corporate_payment_intents SET external_status = 'PENDING' WHERE id = ?",
                    payment.paymentId())).isInstanceOf(DataAccessException.class);
            seed.jdbc.update("UPDATE corporate_payment_intents SET external_status = 'SETTLED' WHERE id = ?",
                    payment.paymentId());
            seed.jdbc.update("UPDATE clearing_instructions SET status = 'SETTLED' WHERE operation_id = ?",
                    payment.operationId());
            assertThat(seed.jdbc.queryForObject("SELECT external_status FROM corporate_payment_intents WHERE id = ?",
                    String.class, payment.paymentId())).isEqualTo("SETTLED");
        });
    }

    @Test
    void populatedV11WithAStrandedSettlementUpgradesToDurableWorkWithoutPostingMoney() {
        withSchema(schema -> {
            flyway(schema, MigrationVersion.fromVersion("11")).migrate();
            Seed seed = new Seed(schema);
            ClearingSeed stranded = seed.corporatePaymentAwaitingClearing();
            ClearingSeed untouched = seed.corporatePaymentAwaitingClearing();
            // The pre-Round-4 failure state: an authenticated SETTLED outcome recorded, its settlement never posted.
            seed.jdbc.update("UPDATE clearing_instructions SET status = 'SETTLED' WHERE operation_id = ?",
                    stranded.operationId());
            seed.jdbc.update("UPDATE corporate_payment_intents SET external_status = 'SETTLED' WHERE id = ?",
                    stranded.paymentId());
            int journalsBefore = seed.jdbc.queryForObject("SELECT count(*) FROM journal_entries", Integer.class);
            long postingsBefore = seed.jdbc.queryForObject("SELECT COALESCE(sum(amount_minor), 0) FROM postings", Long.class);

            flyway(schema, MigrationVersion.LATEST).migrate();

            assertThat(versioned(flyway(schema, MigrationVersion.LATEST))).extracting(info -> info.getVersion().getVersion())
                    .last().isEqualTo("13");
            assertThat(seed.jdbc.queryForObject("SELECT count(*) FROM journal_entries", Integer.class)).isEqualTo(journalsBefore);
            assertThat(seed.jdbc.queryForObject("SELECT COALESCE(sum(amount_minor), 0) FROM postings", Long.class))
                    .isEqualTo(postingsBefore);
            assertThat(seed.jdbc.queryForObject("SELECT status || '/' || external_status FROM corporate_payment_intents WHERE id = ?",
                    String.class, stranded.paymentId())).isEqualTo("PENDING_CLEARING/SETTLED");
            assertThat(seed.jdbc.queryForObject("""
                    SELECT count(*) FROM outbox_events
                    WHERE event_key = ? AND event_type = 'clearing.financial-effect.backfill' AND status = 'PENDING'
                      AND aggregate_id = ? AND attempt_count = 0
                    """, Integer.class, "clearing.financial-effect.backfill:" + stranded.paymentId(),
                    stranded.operationId())).isEqualTo(1);
            assertThat(seed.jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE aggregate_id = ?",
                    Integer.class, untouched.operationId())).isZero();
            assertThat(seed.jdbc.queryForObject("""
                    SELECT kind || ':' || normal_side || ':' || is_system FROM ledger_accounts
                    WHERE id = '00000000-0000-5000-8000-000000000003'
                    """, String.class)).isEqualTo("SIMULATION_TREASURY:DEBIT:true");
            assertThat(seed.jdbc.queryForObject("""
                    SELECT balance_minor FROM account_balance_projection
                    WHERE ledger_account_id = '00000000-0000-5000-8000-000000000003'
                    """, Long.class)).isZero();
            assertThat(unvalidatedConstraints(schema)).isZero();
        });
    }

    @Test
    void v7RefusesToMigratePayrollHistoryWithAMissingResult() {
        withSchema(schema -> {
            flyway(schema, MigrationVersion.fromVersion("6")).migrate();
            Seed seed = new Seed(schema);
            PayrollSeed payroll = seed.payrollFixture();
            // V6 permitted this deletion (R2-02); V7 must refuse to install constraints over the damaged history.
            seed.jdbc.update("DELETE FROM payroll_item_results WHERE item_id = ?", payroll.processingPendingItem());

            assertThatThrownBy(() -> flyway(schema, MigrationVersion.LATEST).migrate())
                    .isInstanceOf(FlywayException.class)
                    .hasStackTraceContaining("V7 preflight refused to migrate inconsistent payroll history: 1 item(s)");
            assertThat(versioned(flyway(schema, MigrationVersion.LATEST)))
                    .extracting(info -> info.getVersion().getVersion()).last().isEqualTo("6");
            assertThat(seed.jdbc.queryForObject("""
                    SELECT count(*) FROM information_schema.columns
                    WHERE table_schema = ? AND table_name = 'payroll_item_results' AND column_name = 'batch_id'
                    """, Integer.class, schema)).isZero();
        });
    }

    // ----- infrastructure -----

    private void withSchema(java.util.function.Consumer<String> scenario) {
        String schema = "r2_migration_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try {
            scenario.accept(schema);
        }
        finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    /** Same scripts and callbacks as the application's Flyway bean, pointed at one isolated schema. */
    private Flyway flyway(String schema, MigrationVersion target) {
        return Flyway.configure()
                .dataSource(url, username, password)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .locations("classpath:db/migration")
                .callbacks(new LegacyTransferOwnerBackfillCallback())
                .target(target)
                .cleanDisabled(true)
                .load();
    }

    private List<MigrationInfo> versioned(Flyway flyway) {
        return Arrays.stream(flyway.info().applied()).filter(info -> info.getVersion() != null).toList();
    }

    private int unvalidatedConstraints(String schema) {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace
                WHERE n.nspname = ? AND NOT c.convalidated
                """, Integer.class, schema);
    }

    /** Flyway 11 checksum: CRC32 over each line's UTF-8 bytes without terminators, BOM removed from line one. */
    private static int flywayChecksum(String resource) throws IOException {
        CRC32 crc = new CRC32();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(resource).getInputStream(), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (line != null && line.startsWith("﻿")) {
                line = line.substring(1);
            }
            while (line != null) {
                crc.update(line.getBytes(StandardCharsets.UTF_8));
                line = reader.readLine();
            }
        }
        return (int) crc.getValue();
    }

    private record PayrollSeed(UUID draftBatch, UUID processingBatch, UUID processingSucceededItem,
            UUID processingPendingItem) {
    }

    private record ClearingSeed(UUID paymentId, UUID operationId) {
    }

    /** Writes representative, constraint-valid synthetic rows directly through SQL into one isolated schema. */
    private final class Seed {

        private final JdbcTemplate jdbc;
        private final TransactionTemplate transactions;

        private Seed(String schema) {
            String separator = url.contains("?") ? "&" : "?";
            DataSource dataSource = new DriverManagerDataSource(url + separator + "currentSchema=" + schema,
                    username, password);
            this.jdbc = new JdbcTemplate(dataSource);
            this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        }

        <T> T inTransaction(java.util.function.Supplier<T> work) {
            return transactions.execute(status -> work.get());
        }

        UUID customer(String label) {
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO customers (id, email_normalized, password_hash, display_name) VALUES (?, ?, ?, ?)",
                    id, label + "-" + id + "@example.test", "synthetic-hash", "Synthetic " + label);
            return id;
        }

        UUID organization(UUID member, String... roles) {
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO organizations (id, display_name) VALUES (?, ?)", id, "Synthetic Org " + id);
            jdbc.update("INSERT INTO organization_memberships (organization_id, customer_id) VALUES (?, ?)", id, member);
            for (String role : roles) {
                jdbc.update("INSERT INTO organization_membership_roles (organization_id, customer_id, role) VALUES (?, ?, ?)",
                        id, member, role);
            }
            return id;
        }

        void addMember(UUID organization, UUID member, String... roles) {
            jdbc.update("INSERT INTO organization_memberships (organization_id, customer_id) VALUES (?, ?)",
                    organization, member);
            for (String role : roles) {
                jdbc.update("INSERT INTO organization_membership_roles (organization_id, customer_id, role) VALUES (?, ?, ?)",
                        organization, member, role);
            }
        }

        /** Opens an account with a ledger account and balanced opening funding from the simulation reserve. */
        UUID fundedAccount(UUID ownerCustomer, UUID ownerOrganization, long openingMinor) {
            return inTransaction(() -> {
                UUID account = UUID.randomUUID();
                if (ownerOrganization == null) {
                    jdbc.update("INSERT INTO accounts (id, owner_customer_id, display_name) VALUES (?, ?, 'Synthetic')",
                            account, ownerCustomer);
                }
                else {
                    jdbc.update("""
                            INSERT INTO accounts (id, owner_customer_id, owner_organization_id, display_name)
                            VALUES (?, NULL, ?, 'Synthetic corporate')
                            """, account, ownerOrganization);
                }
                UUID ledger = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO ledger_accounts (id, customer_account_id, kind, normal_side, is_system)
                        VALUES (?, ?, 'CUSTOMER_DEPOSIT', 'CREDIT', FALSE)
                        """, ledger, account);
                jdbc.update("INSERT INTO account_balance_projection (ledger_account_id, balance_minor) VALUES (?, 0)",
                        ledger);
                UUID entry = UUID.randomUUID();
                jdbc.update("INSERT INTO journal_entries (id, entry_type, reference) VALUES (?, 'OPENING_FUNDING', ?)",
                        entry, "account:" + account);
                posting(entry, RESERVE, "DEBIT", openingMinor);
                posting(entry, ledger, "CREDIT", openingMinor);
                refreshProjection(ledger);
                refreshProjection(RESERVE);
                return account;
            });
        }

        /** Must run inside a transaction so the deferred ledger, transfer, and receipt constraints see all rows. */
        UUID transfer(UUID actor, UUID source, UUID target, long amountMinor, String key, boolean corporate,
                UUID sourceOrganization) {
            UUID sourceLedger = ledgerOf(source);
            UUID targetLedger = ledgerOf(target);
            UUID entry = UUID.randomUUID();
            UUID transfer = UUID.randomUUID();
            jdbc.update("INSERT INTO journal_entries (id, entry_type, reference) VALUES (?, 'TRANSFER', ?)", entry,
                    "transfer:" + transfer);
            posting(entry, sourceLedger, "DEBIT", amountMinor);
            posting(entry, targetLedger, "CREDIT", amountMinor);
            refreshProjection(sourceLedger);
            refreshProjection(targetLedger);
            boolean hasSourceOwnerColumns = jdbc.queryForObject("""
                    SELECT count(*) > 0 FROM information_schema.columns
                    WHERE table_schema = current_schema() AND table_name = 'transfers'
                      AND column_name = 'source_organization_id'
                    """, Boolean.class);
            if (!hasSourceOwnerColumns) {
                jdbc.update("""
                        INSERT INTO transfers (id, owner_customer_id, source_account_id, target_account_id, amount_minor,
                                               journal_entry_id)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, transfer, actor, source, target, amountMinor, entry);
            }
            else {
                jdbc.update("""
                        INSERT INTO transfers (id, owner_customer_id, source_owner_customer_id, source_organization_id,
                                               source_account_id, target_account_id, amount_minor, journal_entry_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """, transfer, actor, corporate ? null : actor, corporate ? sourceOrganization : null, source,
                        target, amountMinor, entry);
            }
            jdbc.update("""
                    INSERT INTO idempotency_records (owner_customer_id, operation, idempotency_key, request_fingerprint,
                                                     status, transfer_id, response_payload)
                    VALUES (?, 'TRANSFER', ?, ?, 'SUCCEEDED', ?, jsonb_build_object('transferId', ?::text))
                    """, actor, key, "0".repeat(64), transfer, transfer.toString());
            return transfer;
        }

        /** A V6 payroll history: one draft batch, and one processing batch with succeeded, failed, pending items. */
        PayrollSeed payrollFixture() {
            UUID maker = customer("maker");
            UUID approver = customer("approver");
            UUID employee = customer("employee");
            UUID organization = organization(maker, "PAYROLL_MAKER");
            addMember(organization, approver, "PAYROLL_APPROVER");
            UUID payer = fundedAccount(null, organization, 100_000L);
            UUID payee = fundedAccount(employee, null, 1_000L);
            UUID draft = batch(organization, payer, maker);
            UUID processing = batch(organization, payer, maker);
            List<UUID> draftItems = inTransaction(() -> List.of(item(draft, 1, employee, payee, 100L)));
            List<UUID> items = inTransaction(() -> List.of(item(processing, 1, employee, payee, 1_000L),
                    item(processing, 2, employee, payee, 900_000L), item(processing, 3, employee, payee, 300L)));
            jdbc.update("""
                    UPDATE payroll_batches SET status = 'SUBMITTED', submitted_by_customer_id = ?,
                           submitted_at = clock_timestamp(), content_digest = ? WHERE id = ?
                    """, maker, "ab".repeat(32), processing);
            jdbc.update("""
                    UPDATE payroll_batches SET status = 'APPROVED', approved_by_customer_id = ?,
                           approved_at = clock_timestamp(), approved_content_digest = ? WHERE id = ?
                    """, approver, "ab".repeat(32), processing);
            jdbc.update("UPDATE payroll_batches SET status = 'PROCESSING', started_at = clock_timestamp() WHERE id = ?",
                    processing);
            inTransaction(() -> {
                UUID transfer = transfer(maker, payer, payee, 1_000L, "payroll-" + processing + "-" + items.get(0), true,
                        organization);
                jdbc.update("""
                        UPDATE payroll_item_results SET status = 'SUCCEEDED', attempt_count = 1, transfer_id = ?,
                               completed_at = clock_timestamp() WHERE item_id = ?
                        """, transfer, items.get(0));
                return transfer;
            });
            jdbc.update("""
                    UPDATE payroll_item_results SET status = 'FAILED', attempt_count = 1, error_code = 'INSUFFICIENT_FUNDS',
                           error_message = 'Synthetic insufficient funds', completed_at = clock_timestamp()
                    WHERE item_id = ?
                    """, items.get(1));
            return new PayrollSeed(draft, processing, items.get(0), items.get(2));
        }

        ClearingSeed corporatePaymentAwaitingClearing() {
            UUID maker = customer("payment-maker");
            UUID approver = customer("payment-approver");
            UUID organization = organization(maker, "ORG_ADMIN", "PAYMENT_MAKER");
            addMember(organization, approver, "PAYMENT_APPROVER");
            UUID source = fundedAccount(null, organization, 10_000L);
            UUID sourceLedger = ledgerOf(source);
            UUID paymentId = UUID.randomUUID();
            String digest = "cd".repeat(32);
            return inTransaction(() -> {
                jdbc.update("""
                        INSERT INTO corporate_payment_intents (id, operation_id, organization_id, source_account_id,
                            created_by_customer_id, beneficiary_name, beneficiary_account_reference,
                            beneficiary_routing_code, amount_minor, currency)
                        VALUES (?, ?, ?, ?, ?, 'Synthetic beneficiary', 'NZ-MIGRATION-1', 'SIM-PENDING', 2500, 'NZD')
                        """, paymentId, paymentId, organization, source, maker);
                jdbc.update("""
                        UPDATE corporate_payment_intents SET status = 'SUBMITTED', submitted_by_customer_id = ?,
                            submitted_at = clock_timestamp(), intent_digest = ? WHERE id = ?
                        """, maker, digest, paymentId);
                jdbc.update("""
                        UPDATE corporate_payment_intents SET status = 'APPROVED', approved_by_customer_id = ?,
                            approved_at = clock_timestamp(), approved_version = 1, approved_digest = ? WHERE id = ?
                        """, approver, digest, paymentId);
                UUID bookingEntry = UUID.randomUUID();
                jdbc.update("""
                        INSERT INTO journal_entries (id, entry_type, currency, reference)
                        VALUES (?, 'CORPORATE_PAYMENT_BOOKING', 'NZD', ?)
                        """, bookingEntry, "corporate-payment:" + paymentId + ":booking");
                posting(bookingEntry, sourceLedger, "DEBIT", 2_500L);
                posting(bookingEntry, UUID.fromString("00000000-0000-5000-8000-000000000002"), "CREDIT", 2_500L);
                refreshProjection(sourceLedger);
                refreshProjection(UUID.fromString("00000000-0000-5000-8000-000000000002"));
                jdbc.update("""
                        UPDATE corporate_payment_intents SET status = 'PENDING_CLEARING',
                            booking_journal_entry_id = ? WHERE id = ?
                        """, bookingEntry, paymentId);
                jdbc.update("""
                        INSERT INTO clearing_instructions (operation_id, payment_intent_id, intent_digest)
                        VALUES (?, ?, ?)
                        """, paymentId, paymentId, digest);
                return new ClearingSeed(paymentId, paymentId);
            });
        }

        /** Adds a draft item exactly as PayrollService does (result inserted without batch_id) in one transaction. */
        UUID addDraftItemLikeTheApplication(UUID draftBatch) {
            UUID payee = jdbc.queryForObject("SELECT payee_account_id FROM payroll_items WHERE batch_id = ? LIMIT 1",
                    UUID.class, draftBatch);
            UUID owner = jdbc.queryForObject("SELECT owner_customer_id FROM accounts WHERE id = ?", UUID.class, payee);
            UUID item = UUID.randomUUID();
            inTransaction(() -> {
                jdbc.update("""
                        INSERT INTO payroll_items (id, batch_id, sequence_no, employee_reference, employee_name,
                                                   payee_account_id, payee_owner_customer_id, amount_minor)
                        VALUES (?, ?, 50, 'EMP-LATE', 'Late Draft Employee', ?, ?, 10)
                        """, item, draftBatch, payee, owner);
                return jdbc.update("INSERT INTO payroll_item_results (item_id) VALUES (?)", item);
            });
            return item;
        }

        private UUID batch(UUID organization, UUID payer, UUID maker) {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO payroll_batches (id, organization_id, payer_account_id, name, created_by_customer_id)
                    VALUES (?, ?, ?, ?, ?)
                    """, id, organization, payer, "Synthetic batch " + id, maker);
            return id;
        }

        private UUID item(UUID batch, int sequence, UUID employee, UUID payee, long amountMinor) {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO payroll_items (id, batch_id, sequence_no, employee_reference, employee_name,
                                               payee_account_id, payee_owner_customer_id, amount_minor)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, batch, sequence, "EMP-" + sequence, "Synthetic Employee " + sequence, payee, employee,
                    amountMinor);
            jdbc.update("INSERT INTO payroll_item_results (item_id) VALUES (?)", id);
            return id;
        }

        private void posting(UUID entry, UUID ledger, String side, long amountMinor) {
            jdbc.update("""
                    INSERT INTO postings (id, journal_entry_id, ledger_account_id, side, amount_minor)
                    VALUES (?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), entry, ledger, side, amountMinor);
        }

        private void refreshProjection(UUID ledger) {
            jdbc.update("""
                    UPDATE account_balance_projection p
                    SET balance_minor = (
                        SELECT COALESCE(sum(CASE WHEN x.side = la.normal_side THEN x.amount_minor ELSE -x.amount_minor END), 0)
                        FROM ledger_accounts la LEFT JOIN postings x ON x.ledger_account_id = la.id
                        WHERE la.id = p.ledger_account_id)
                    WHERE p.ledger_account_id = ?
                    """, ledger);
        }

        private UUID ledgerOf(UUID account) {
            return jdbc.queryForObject(
                    "SELECT id FROM ledger_accounts WHERE customer_account_id = ? AND kind = 'CUSTOMER_DEPOSIT'",
                    UUID.class, account);
        }

        long balance(UUID account) {
            return jdbc.queryForObject("""
                    SELECT p.balance_minor FROM ledger_accounts la
                    JOIN account_balance_projection p ON p.ledger_account_id = la.id
                    WHERE la.customer_account_id = ?
                    """, Long.class, account);
        }

        int projectionMismatches() {
            return jdbc.queryForObject("""
                    SELECT count(*) FROM account_balance_projection p JOIN ledger_accounts la ON la.id = p.ledger_account_id
                    WHERE p.balance_minor <> (
                        SELECT COALESCE(sum(CASE WHEN x.side = la.normal_side THEN x.amount_minor ELSE -x.amount_minor END), 0)
                        FROM postings x WHERE x.ledger_account_id = la.id)
                    """, Integer.class);
        }

        /** Stable digest of every financial history row that a migration must never change. */
        String ledgerFingerprint() {
            return jdbc.queryForObject("""
                    SELECT md5(concat_ws('|',
                        (SELECT string_agg(id || ':' || entry_type || ':' || reference, ',' ORDER BY id) FROM journal_entries),
                        (SELECT string_agg(id || ':' || journal_entry_id || ':' || ledger_account_id || ':' || side || ':'
                                || amount_minor, ',' ORDER BY id) FROM postings),
                        (SELECT string_agg(id || ':' || owner_customer_id || ':' || source_account_id || ':'
                                || target_account_id || ':' || amount_minor || ':' || journal_entry_id, ',' ORDER BY id)
                           FROM transfers),
                        (SELECT string_agg(owner_customer_id || ':' || idempotency_key || ':' || status || ':'
                                || COALESCE(transfer_id::text, '-'), ',' ORDER BY owner_customer_id, idempotency_key)
                           FROM idempotency_records),
                        (SELECT string_agg(p.ledger_account_id || ':' || p.balance_minor, ',' ORDER BY p.ledger_account_id)
                           FROM account_balance_projection p JOIN ledger_accounts a ON a.id = p.ledger_account_id
                           -- System accounts added later with a zero balance (V8 payable, V13 treasury) are not history.
                           WHERE a.kind NOT IN ('CLEARING_PAYABLE', 'SIMULATION_TREASURY'))))
                    """, String.class);
        }
    }
}
