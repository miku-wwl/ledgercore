package nz.ac.ledgercore.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import nz.ac.ledgercore.accounts.AccountService;
import nz.ac.ledgercore.identity.IdentityService;
import nz.ac.ledgercore.ledger.Money;
import nz.ac.ledgercore.organizations.OrganizationRole;
import nz.ac.ledgercore.organizations.OrganizationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * Opt-in fixture generator for the documented real process-kill/restart payroll experiment.
 * Run explicitly with -Dtest=PayrollRecoveryFixtureHarness; it is excluded from the default *Test(s) suite.
 */
@ActiveProfiles("test")
@SpringBootTest
class PayrollRecoveryFixtureHarness {

    private static final String PASSWORD = "Payroll-Recovery-Scenario!";

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    IdentityService identityService;

    @Autowired
    AccountService accountService;

    @Autowired
    OrganizationService organizationService;

    @Autowired
    PayrollService payrollService;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Test
    void createApprovedFiveHundredItemRecoveryBatch() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String makerEmail = "recovery-maker-" + suffix + "@example.test";
        String approverEmail = "recovery-approver-" + suffix + "@example.test";
        var maker = identityService.register(makerEmail, PASSWORD, "Recovery Fixture Maker");
        var approver = identityService.register(approverEmail, PASSWORD, "Recovery Fixture Approver");
        var organization = organizationService.create(maker.id(), "Recovery Fixture " + suffix.substring(0, 8));
        organizationService.addMember(maker.id(), organization.id(), makerEmail,
                List.of(OrganizationRole.PAYROLL_MAKER));
        organizationService.addMember(maker.id(), organization.id(), approverEmail,
                List.of(OrganizationRole.PAYROLL_APPROVER, OrganizationRole.OPERATIONS));

        UUID payerAccountId = accountService.openOrganizationAccount(organization.id(), "500-item recovery payer");
        List<UUID> payeeAccountIds = createSyntheticEmployees(500);
        List<PayrollService.NewPayrollItem> items = new ArrayList<>(500);
        for (int index = 0; index < payeeAccountIds.size(); index++) {
            String employeeNumber = "%04d".formatted(index + 1);
            items.add(new PayrollService.NewPayrollItem("REC-" + employeeNumber,
                    "Recovery Employee " + employeeNumber, payeeAccountIds.get(index), "1.00"));
        }

        var draft = payrollService.createDraft(maker.id(), organization.id(), "500-item process recovery " + suffix,
                payerAccountId);
        var populated = payrollService.addItems(maker.id(), draft.id(), items);
        assertThat(populated.itemCount()).isEqualTo(500);
        assertThat(populated.totalAmount()).isEqualTo("500.00");
        var submitted = payrollService.submit(maker.id(), draft.id());
        var approved = payrollService.approve(approver.id(), submitted.id());
        assertThat(approved.status()).isEqualTo("APPROVED");
        var processing = payrollService.startProcessing(approved.id());
        assertThat(processing.status()).isEqualTo("PROCESSING");

        Path fixture = Path.of(System.getProperty("user.dir"), "..", "work", "e2", "recovery-fixture.json")
                .normalize().toAbsolutePath();
        Files.createDirectories(fixture.getParent());
        Files.writeString(fixture, """
                {
                  "batchId": "%s",
                  "organizationId": "%s",
                  "payerAccountId": "%s",
                  "makerEmail": "%s",
                  "approverEmail": "%s",
                  "password": "%s",
                  "itemCount": 500,
                  "totalAmount": "500.00"
                }
                """.formatted(processing.id(), organization.id(), payerAccountId, makerEmail, approverEmail, PASSWORD));
        System.out.println("RECOVERY_FIXTURE=" + fixture);
        System.out.println("RECOVERY_BATCH_ID=" + processing.id());
        System.out.println("RECOVERY_EXPECTED_ITEMS=500");
    }

    private List<UUID> createSyntheticEmployees(int count) {
        String passwordHash = passwordEncoder.encode(PASSWORD);
        List<UUID> customers = new ArrayList<>(count);
        List<Object[]> customerRows = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            UUID customerId = UUID.randomUUID();
            customers.add(customerId);
            String employeeNumber = "%04d".formatted(index + 1);
            customerRows.add(new Object[] { customerId,
                    "recovery-employee-" + UUID.randomUUID() + "@example.test", passwordHash,
                    "Recovery Employee " + employeeNumber });
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO customers (id, email_normalized, password_hash, display_name) VALUES (?, ?, ?, ?)
                """, customerRows);

        List<UUID> accountIds = new ArrayList<>(count);
        for (int index = 0; index < customers.size(); index++) {
            String employeeNumber = "%04d".formatted(index + 1);
            accountIds.add(accountService.openAccount(customers.get(index),
                    "Recovery Employee " + employeeNumber + " account"));
        }
        return accountIds;
    }
}
