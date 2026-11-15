package nz.ac.ledgercore.faultlab;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import nz.ac.ledgercore.events.DurableEventStore;
import nz.ac.ledgercore.ledger.LedgerWriter;
import nz.ac.ledgercore.ledger.LedgerWriter.LiquidityDirection;
import nz.ac.ledgercore.ledger.LedgerWriter.LiquidityTransfer;
import nz.ac.ledgercore.ledger.Money;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Round 4 (R4-02) laboratory command: moves simulated liquidity between the simulation reserve and the simulation
 * treasury so that a genuine reserve shortage, and its replenishment, can be exercised without faking a balance. There is
 * no HTTP route. The command runs only under the {@code reserve-lab} profile with an explicit property, a confirmation
 * token, a reason, a request id and a verified loopback database whose name marks it as disposable. It prints one result
 * line and exits.
 */
@Component
@Profile("reserve-lab")
@ConditionalOnProperty(name = "ledgercore.reserve-lab.enabled", havingValue = "true")
class ReserveLiquidityLabRunner implements ApplicationRunner {

    private static final Pattern DISPOSABLE_URL = Pattern.compile(
            "jdbc:postgresql://127\\.0\\.0\\.1:(55437|55450|55460)/(ledgercore_(e6|e12|r4)_[a-z0-9_]+)");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final LedgerWriter ledgerWriter;
    private final DurableEventStore eventStore;
    private final DataSource dataSource;
    private final Environment environment;
    private final ApplicationContext context;

    ReserveLiquidityLabRunner(LedgerWriter ledgerWriter, DurableEventStore eventStore, DataSource dataSource,
            Environment environment, ApplicationContext context) {
        this.ledgerWriter = ledgerWriter;
        this.eventStore = eventStore;
        this.dataSource = dataSource;
        this.environment = environment;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments arguments) throws Exception {
        if (!"DISPOSABLE_ONLY".equals(System.getenv("LEDGERCORE_RESERVE_LAB_CONFIRM"))) {
            throw new IllegalStateException("The reserve lab requires LEDGERCORE_RESERVE_LAB_CONFIRM=DISPOSABLE_ONLY.");
        }
        String url = environment.getProperty("spring.datasource.url", "");
        var match = DISPOSABLE_URL.matcher(url);
        if (!match.matches()) {
            throw new IllegalStateException("The reserve lab only runs against a loopback ledgercore_{e6,e12,r4}_ database.");
        }
        verifyDatabase(match.group(2), Integer.parseInt(match.group(1)));
        LiquidityDirection direction = LiquidityDirection.valueOf(required("ledgercore.reserve-lab.direction"));
        Money amount = Money.parsePositiveNzd(required("ledgercore.reserve-lab.amount"));
        UUID requestId = UUID.fromString(required("ledgercore.reserve-lab.request-id"));
        String reason = required("ledgercore.reserve-lab.reason").trim();
        if (reason.length() > 200) {
            throw new IllegalArgumentException("The reserve lab reason must contain at most 200 characters.");
        }
        LiquidityTransfer transfer = ledgerWriter.transferReserveLiquidity(requestId, direction, amount);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("requestId", requestId.toString());
        details.put("direction", direction.name());
        details.put("amountMinor", amount.minorUnits());
        details.put("reason", reason);
        details.put("journalEntryId", transfer.journalEntryId().toString());
        details.put("replayed", transfer.replayed());
        details.put("reserveMinorAfter", transfer.reserveBalance().minorUnits());
        details.put("treasuryMinorAfter", transfer.treasuryBalance().minorUnits());
        if (!transfer.replayed()) {
            eventStore.recordAudit(null, "LAB_RESERVE_LIQUIDITY_TRANSFER", "LEDGER_ACCOUNT", LedgerWriter.SIMULATION_RESERVE_ID,
                    null, details);
        }
        System.out.println("RESERVE_LAB_RESULT " + JSON.writeValueAsString(details));
        System.out.flush();
        System.exit(SpringApplication.exit(context, () -> 0));
    }

    private String required(String property) {
        String value = environment.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing reserve lab setting: " + property);
        }
        return value;
    }

    private void verifyDatabase(String database, int port) {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT current_database(), host(inet_server_addr()), inet_server_port()")) {
            if (!result.next() || !database.equals(result.getString(1)) || !"127.0.0.1".equals(result.getString(2))
                    || result.getInt(3) != port) {
                throw new IllegalStateException("The connected PostgreSQL identity is not the disposable reserve lab target.");
            }
        }
        catch (SQLException exception) {
            throw new IllegalStateException("The reserve lab could not verify its PostgreSQL target.", exception);
        }
    }
}
