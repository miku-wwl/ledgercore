package nz.ac.ledgercore.payroll;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

final class PayrollContentDigest {

    private static final byte[] FORMAT = "ledgercore-payroll-intent-v1".getBytes(StandardCharsets.US_ASCII);

    private PayrollContentDigest() {
    }

    static String calculate(UUID batchId, UUID organizationId, UUID payerAccountId, String batchName,
            List<Item> items) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(FORMAT.length);
                output.write(FORMAT);
                writeUuid(output, batchId);
                writeUuid(output, organizationId);
                writeUuid(output, payerAccountId);
                writeString(output, batchName);
                output.writeInt(items.size());
                for (Item item : items) {
                    writeUuid(output, item.id());
                    output.writeInt(item.sequenceNumber());
                    writeString(output, item.employeeReference());
                    writeString(output, item.employeeName());
                    writeUuid(output, item.payeeAccountId());
                    writeUuid(output, item.payeeOwnerCustomerId());
                    output.writeLong(item.amountMinor());
                    writeString(output, item.currency());
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        }
        catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Could not calculate the payroll content digest.", exception);
        }
    }

    private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    record Item(UUID id, int sequenceNumber, String employeeReference, String employeeName,
            UUID payeeAccountId, UUID payeeOwnerCustomerId, long amountMinor, String currency) {
    }
}
