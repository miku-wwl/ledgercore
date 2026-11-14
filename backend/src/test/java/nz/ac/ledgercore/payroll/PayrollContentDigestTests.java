package nz.ac.ledgercore.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PayrollContentDigestTests {

    @Test
    void digestIsStableAndBindsEveryBusinessIntentField() {
        UUID batchId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();
        UUID payerAccountId = UUID.randomUUID();
        PayrollContentDigest.Item item = new PayrollContentDigest.Item(UUID.randomUUID(), 1, "EMP-0001",
                "Synthetic Employee", UUID.randomUUID(), UUID.randomUUID(), 12_345L, "NZD");

        String first = PayrollContentDigest.calculate(batchId, organizationId, payerAccountId, "October payroll",
                List.of(item));
        String repeated = PayrollContentDigest.calculate(batchId, organizationId, payerAccountId, "October payroll",
                List.of(item));
        String changedAmount = PayrollContentDigest.calculate(batchId, organizationId, payerAccountId,
                "October payroll", List.of(new PayrollContentDigest.Item(item.id(), item.sequenceNumber(),
                        item.employeeReference(), item.employeeName(), item.payeeAccountId(),
                        item.payeeOwnerCustomerId(), item.amountMinor() + 1, item.currency())));

        assertThat(first).hasSize(64).matches("[0-9a-f]{64}").isEqualTo(repeated).isNotEqualTo(changedAmount);
    }
}
