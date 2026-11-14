package nz.ac.ledgercore;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;

/**
 * Round 2 remedy for R2-08. V5 backfills {@code transfers.source_owner_customer_id} with an UPDATE, but V3's
 * {@code tr_transfers_append_only} trigger rejects every UPDATE of a transfer row, so the committed V5 cannot upgrade a
 * V4 database that already holds transfers. V1-V6 must stay byte-identical because deployed databases record their
 * checksums, so V5 itself is not edited.
 *
 * <p>For V5 only, and only when transfer rows exist, this callback disables that single trigger inside V5's own
 * migration transaction and re-enables it before the transaction commits. It refuses to act outside a transaction, the
 * {@code ALTER TABLE} lock blocks concurrent transfer writers until commit, and any failure rolls V5 back together with
 * the trigger change. V5's only UPDATE writes the new nullable source-owner column; no financial value changes.
 */
public final class LegacyTransferOwnerBackfillCallback implements Callback {

    static final String NAME = "ledgercore-v5-legacy-transfer-owner-backfill";
    private static final MigrationVersion V5 = MigrationVersion.fromVersion("5");

    private final AtomicBoolean triggerDisabled = new AtomicBoolean();

    @Override
    public boolean supports(Event event, Context context) {
        return (event == Event.BEFORE_EACH_MIGRATE || event == Event.AFTER_EACH_MIGRATE
                || event == Event.AFTER_EACH_MIGRATE_ERROR)
                && context.getMigrationInfo() != null && V5.equals(context.getMigrationInfo().getVersion());
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        Connection connection = context.getConnection();
        try {
            if (event == Event.BEFORE_EACH_MIGRATE) {
                if (!hasTransfers(connection)) {
                    return;
                }
                if (connection.getAutoCommit()) {
                    throw new FlywayException("Refusing to relax transfer immutability outside V5's migration transaction.");
                }
                execute(connection, "ALTER TABLE transfers DISABLE TRIGGER tr_transfers_append_only");
                triggerDisabled.set(true);
            }
            else if (triggerDisabled.getAndSet(false)) {
                execute(connection, "ALTER TABLE transfers ENABLE TRIGGER tr_transfers_append_only");
                if (event == Event.AFTER_EACH_MIGRATE && !appendOnlyTriggerEnabled(connection)) {
                    throw new FlywayException("tr_transfers_append_only was not re-enabled after V5.");
                }
            }
        }
        catch (SQLException exception) {
            if (event == Event.AFTER_EACH_MIGRATE_ERROR) {
                // V5 failed; its transaction (including the trigger change) is rolled back by Flyway.
                return;
            }
            throw new FlywayException("V5 legacy transfer owner backfill callback failed.", exception);
        }
    }

    @Override
    public String getCallbackName() {
        return NAME;
    }

    private static boolean hasTransfers(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT EXISTS (SELECT 1 FROM transfers)")) {
            return rows.next() && rows.getBoolean(1);
        }
    }

    private static boolean appendOnlyTriggerEnabled(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT tgenabled = 'O' FROM pg_trigger
                     WHERE tgrelid = 'transfers'::regclass AND tgname = 'tr_transfers_append_only'
                     """)) {
            return rows.next() && rows.getBoolean(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
