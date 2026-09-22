package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.EmailParser;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EmailParserTest {

    private final EmailParser parser = new EmailParser();

    @Test
    void parsesCreditedInrAmount() {
        Optional<ParsedTxn> result = parser.parse(email("email-1",
                "Your account ending 4821 has been credited with INR 45,000.\n"
                        + "Merchant / Remarks: SALARY CREDIT"));

        assertTrue(result.isPresent());
        ParsedTxn txn = result.orElseThrow();
        assertEquals("4821", txn.accountLast4());
        assertEquals(Direction.CREDIT, txn.direction());
        assertEquals(new BigDecimal("45000.00"), txn.amount());
        assertEquals("SALARY CREDIT", txn.merchant());
        assertEquals(OffsetDateTime.parse("2026-07-01T09:02:00+05:30"), txn.occurredAt());
        assertEquals("email-1", txn.sourceMessageId());
    }

    @Test
    void parsesDebitedRupeeAmount() {
        Optional<ParsedTxn> result = parser.parse(email("email-2",
                "Your account ending 4821 has been debited with Rs.76.49.\n"
                        + "Merchant / Remarks: RELIANCE SMART"));

        assertTrue(result.isPresent());
        ParsedTxn txn = result.orElseThrow();
        assertEquals(Direction.DEBIT, txn.direction());
        assertEquals(new BigDecimal("76.49"), txn.amount());
        assertEquals("RELIANCE SMART", txn.merchant());
    }

    @Test
    void returnsEmptyForAnUnexpectedEmailFormat() {
        assertFalse(parser.parse(email("email-3", "Your account balance is INR 45,000.")).isPresent());
    }

    private static RawMessage email(String messageId, String transaction) {
        return new RawMessage(messageId, "email", "alerts@example.com",
                OffsetDateTime.parse("2026-07-01T09:47:00+05:30"), "device-1",
                "Date: Wed, 01 Jul 2026 09:02:00 +0530\n"
                        + "Subject: Transaction alert\n\n" + transaction);
    }
}
