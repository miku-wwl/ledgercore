package nz.ac.ledgercore.faultlab;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import nz.ac.ledgercore.api.E6FaultInjectionHook;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * This configuration is absent from ordinary deployments. The active profile, explicit property, confirmation
 * token, disposable database name/port and run directory must all agree before it can be constructed.
 */
@Configuration
@Profile("e6-lab")
public class E6FaultLabConfiguration {

	private static final Pattern RUN_ID = Pattern.compile("[0-9a-fA-F-]{36}");
	private static final Map<String, String> ALLOWED_CASES = Map.of(
			"precommit-crash", E6FaultInjectionHook.TRANSFER_PRE_COMMIT,
			"postcommit-response-crash", E6FaultInjectionHook.TRANSFER_AFTER_COMMIT_BEFORE_RESPONSE,
			"outbox-before-dispatch", E6FaultInjectionHook.OUTBOX_PERSISTED_BEFORE_DISPATCH,
			"payroll-mark-failure-crash", E6FaultInjectionHook.PAYROLL_AFTER_ROLLBACK_BEFORE_FAILURE_MARK);
	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Bean
	@ConditionalOnProperty(name = "ledgercore.e6.enabled", havingValue = "true")
	E6FaultInjectionHook e6FaultInjectionHook(Environment environment, DataSource dataSource) {
		String caseName = requiredEnvironment("LEDGERCORE_E6_CASE");
		String configuredFault = requiredEnvironment("LEDGERCORE_E6_FAULT");
		String expectedFault = ALLOWED_CASES.get(caseName);
		if (expectedFault == null || !expectedFault.equals(configuredFault)) {
			throw new IllegalStateException("E6 case and fault point are not an allowed pair.");
		}
		if (!"DISPOSABLE_ONLY".equals(requiredEnvironment("LEDGERCORE_E6_CONFIRM"))) {
			throw new IllegalStateException("E6 mutation requires LEDGERCORE_E6_CONFIRM=DISPOSABLE_ONLY.");
		}
		String runId = requiredEnvironment("LEDGERCORE_E6_RUN_ID");
		if (!RUN_ID.matcher(runId).matches()) {
			throw new IllegalStateException("E6 run id must be a GUID.");
		}
		Path repository = Path.of("").toAbsolutePath().normalize();
		Path runRoot = repository.resolve("work/e6-lab/runs").normalize();
		Path runDirectory = Path.of(requiredEnvironment("LEDGERCORE_E6_RUN_DIR")).toAbsolutePath().normalize();
		if (!runDirectory.equals(runRoot.resolve(runId).normalize()) || !runDirectory.startsWith(runRoot)) {
			throw new IllegalStateException("E6 checkpoint directory must be this run's dedicated work/e6-lab directory.");
		}
		String databaseUrl = environment.getProperty("spring.datasource.url", "");
		if (!databaseUrl.matches("jdbc:postgresql://127\\.0\\.0\\.1:55437/ledgercore_e6_[a-z0-9_]+")) {
			throw new IllegalStateException("E6 requires a dedicated database on 127.0.0.1:55437 with the ledgercore_e6_ prefix.");
		}
		verifyDatabase(dataSource, databaseUrl.substring(databaseUrl.lastIndexOf('/') + 1));
		return new BlockingCheckpoint(configuredFault, caseName, runId, runDirectory);
	}

	private static void verifyDatabase(DataSource dataSource, String expectedDatabase) {
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("SELECT current_database(), host(inet_server_addr()), inet_server_port()")) {
			if (!result.next() || !expectedDatabase.equals(result.getString(1))
					|| !"127.0.0.1".equals(result.getString(2)) || result.getInt(3) != 55437) {
				throw new IllegalStateException("Connected PostgreSQL identity does not match the disposable E6 target.");
			}
		}
		catch (SQLException exception) {
			throw new IllegalStateException("E6 could not verify the PostgreSQL target identity.", exception);
		}
	}

	private static String requiredEnvironment(String name) {
		String value = System.getenv(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("Required E6 opt-in setting is missing: " + name);
		}
		return value;
	}

	private static final class BlockingCheckpoint implements E6FaultInjectionHook {

		private static final long MAX_BLOCK_MINUTES = 10;
		private final String configuredFault;
		private final String caseName;
		private final String runId;
		private final Path runDirectory;
		private final AtomicBoolean fired = new AtomicBoolean();
		private final CountDownLatch release = new CountDownLatch(1);

		private BlockingCheckpoint(String configuredFault, String caseName, String runId, Path runDirectory) {
			this.configuredFault = configuredFault;
			this.caseName = caseName;
			this.runId = runId;
			this.runDirectory = runDirectory;
		}

		@Override
		public void checkpoint(String point, Map<String, ?> facts) {
			if (!configuredFault.equals(point) || !fired.compareAndSet(false, true)) {
				return;
			}
			try {
				Files.createDirectories(runDirectory);
				Path target = runDirectory.resolve("checkpoint.json");
				Path temporary = runDirectory.resolve("checkpoint.json.tmp");
				byte[] content = JSON.writeValueAsBytes(new Checkpoint(runId, caseName, point,
						ProcessHandle.current().pid(), Instant.now().toString(), facts));
				Files.write(temporary, content);
				Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			}
			catch (IOException | JacksonException exception) {
				throw new IllegalStateException("The E6 checkpoint could not be durably written.", exception);
			}
			if (OUTBOX_PERSISTED_BEFORE_DISPATCH.equals(point)) {
				return;
			}
			try {
				if (!release.await(MAX_BLOCK_MINUTES, TimeUnit.MINUTES)) {
					throw new IllegalStateException("E6 fault checkpoint timed out; the intended process crash was not completed.");
				}
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("E6 checkpoint was interrupted before the harness completed the process crash.", exception);
			}
		}
	}

	private record Checkpoint(String runId, String caseName, String faultPoint, long processId,
			String reachedAt, Map<String, ?> facts) {
	}
}
