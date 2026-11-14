package nz.ac.ledgercore.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import nz.ac.ledgercore.events.DurableEventStore;
import nz.ac.ledgercore.api.ApiNotFoundException;
import nz.ac.ledgercore.identity.IdentityService;
import nz.ac.ledgercore.organizations.OrganizationAuthorizationException;
import nz.ac.ledgercore.organizations.OrganizationRole;
import nz.ac.ledgercore.organizations.OrganizationService;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
class OperationsConsolePostgresIntegrationTests {

    private static final String PASSWORD = "Operations-Console-Integration-Password!";

    @Autowired
    OperationsConsoleService console;

    @Autowired
    DurableEventStore events;

    @Autowired
    IdentityService identities;

    @Autowired
    OrganizationService organizations;

    @Test
    void showsScopedMetricsAuditAndCorrelationSearchOnlyToOperationsMembers() {
        var admin = identities.register(email("admin"), PASSWORD, "Operations Console Admin");
        var organization = organizations.create(admin.id(), "Operations Console " + UUID.randomUUID());
        var operator = identities.register(email("operator"), PASSWORD, "Operations Console Operator");
        organizations.addMember(admin.id(), organization.id(), operator.email(), List.of(OrganizationRole.OPERATIONS));
        var outsider = identities.register(email("outsider"), PASSWORD, "Operations Console Outsider");

        String correlationId = "e11-ops-" + UUID.randomUUID();
        String previous = MDC.get("correlationId");
        try {
            MDC.put("correlationId", correlationId);
            UUID resourceId = UUID.randomUUID();
            UUID outboxId = events.enqueue("e11.ops.search:" + UUID.randomUUID(), "e11.ops.fixture",
                    "OPERATIONS_FIXTURE", resourceId, operator.id(), organization.id(), Map.of("synthetic", true));
            events.recordAudit(operator.id(), "E11_OPERATIONS_FIXTURE", "OPERATIONS_FIXTURE", resourceId,
                    organization.id(), Map.of("synthetic", true, "notExposedByTimeline", "fixture data"));

            var listedOutbox = events.outboxForOrganization(organization.id(), 100).stream()
                    .filter(event -> event.id().equals(outboxId)).findFirst().orElseThrow();
            assertThat(listedOutbox.correlationId()).isEqualTo(correlationId);

            var history = console.auditHistory(operator.id(), organization.id(), 100);
            assertThat(history).anySatisfy(event -> {
                assertThat(event.action()).isEqualTo("E11_OPERATIONS_FIXTURE");
                assertThat(event.resourceId()).isEqualTo(resourceId);
                assertThat(event.correlationId()).isEqualTo(correlationId);
            });
            var matches = console.search(operator.id(), organization.id(), correlationId);
            assertThat(matches).extracting(OperationsConsoleService.OperationSearchResult::resultType)
                    .contains("OUTBOX_EVENT", "AUDIT_EVENT");
            assertThat(matches).allSatisfy(match -> assertThat(match.correlationId()).isEqualTo(correlationId));

            var metrics = console.metrics(operator.id(), organization.id());
            assertThat(metrics.pendingClearing()).isGreaterThanOrEqualTo(0);
            assertThat(metrics.unknownClearing()).isGreaterThanOrEqualTo(0);
            assertThat(metrics.failedEvents()).isGreaterThanOrEqualTo(0);
            assertThat(metrics.openExceptions()).isGreaterThanOrEqualTo(0);

            assertThatThrownBy(() -> console.metrics(admin.id(), organization.id()))
                    .isInstanceOf(OrganizationAuthorizationException.class);
            assertThatThrownBy(() -> console.auditHistory(outsider.id(), organization.id(), 100))
                    .isInstanceOf(ApiNotFoundException.class);
        }
        finally {
            if (previous == null) {
                MDC.remove("correlationId");
            }
            else {
                MDC.put("correlationId", previous);
            }
        }
    }

    private String email(String role) {
        return "e11-operations-" + role + "-" + UUID.randomUUID() + "@example.test";
    }
}
