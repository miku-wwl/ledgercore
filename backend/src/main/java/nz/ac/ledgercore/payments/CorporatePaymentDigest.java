package nz.ac.ledgercore.payments;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

final class CorporatePaymentDigest {

    private static final byte[] FORMAT = "ledgercore-corporate-payment-v1".getBytes(StandardCharsets.US_ASCII);

    private CorporatePaymentDigest() {
    }

    static String calculate(CorporatePaymentService.PaymentRow payment) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(FORMAT.length);
                output.write(FORMAT);
                writeUuid(output, payment.id());
                writeUuid(output, payment.organizationId());
                writeUuid(output, payment.sourceAccountId());
                writeUuid(output, payment.createdByCustomerId());
                writeString(output, payment.beneficiaryName());
                writeString(output, payment.beneficiaryAccountReference());
                writeString(output, payment.beneficiaryRoutingCode());
                output.writeLong(payment.amountMinor());
                writeString(output, payment.currency());
                output.writeInt(payment.version());
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        }
        catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Could not calculate the corporate payment digest.", exception);
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
}
