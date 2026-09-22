package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.HdfcSmsParser;
import in.simplifymoney.ledgersync.parse.IciciSmsParser;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class PipelineTest {
    @Test void parsesIciciCompactFormat() {
        var parsed = new IciciSmsParser().parse(raw("VM-ICICIB-T",
                "ICICI Bank Acct XX9075 Cr INR 5000.00 on 01-Aug-2026 14:22; "
                        + "IMPS/P2A/SELF ref no 1. BalAvl Rs 1.00")).orElseThrow();
        assertEquals(Direction.CREDIT, parsed.direction());
        assertEquals(new BigDecimal("5000.00"), parsed.amount());
    }

    @Test void parsesHdfcCardAgainstCardNumber() {
        var parsed = new HdfcSmsParser().parse(raw("AD-HDFCBK-S",
                "Rs 47.33 spent on HDFC Bank Card x3310 at DMART on 04-07-26 18:41.")).orElseThrow();
        assertEquals("3310", parsed.accountLast4());
        assertEquals(Direction.DEBIT, parsed.direction());
    }

    @Test void deduplicatesAndIsIdempotent() throws Exception {
        Path corpus = Files.createTempFile("ledger", ".jsonl");
        Files.writeString(corpus, line("m-2") + "\n" + line("m-1") + "\n");
        var store = new InMemoryLedgerStore();
        var ingest = new IngestService(new Parsers(), store);
        assertEquals(1, ingest.ingestFile(corpus).transactionsWritten());
        assertEquals(0, ingest.ingestFile(corpus).transactionsWritten());
        assertEquals(1, store.count());
        assertEquals(java.util.List.of("m-1", "m-2"), store.all().getFirst().sourceMessageIds());
    }

    @Test void ingestingTheFullCorpusTwiceLeavesTheLedgerUnchanged() throws Exception {
        var store = new InMemoryLedgerStore();
        var ingest = new IngestService(new Parsers(), store);
        Path corpus = Path.of("fixtures", "corpus-a.jsonl");

        ingest.ingestFile(corpus);
        assertEquals(257, store.count());
        var firstLedger = java.util.List.copyOf(store.all());

        ingest.ingestFile(corpus);
        assertEquals(257, store.count());
        assertEquals(firstLedger, store.all());
    }

    @Test void classifiesSmallUpiAsMicroAndCardDoesNotUseBankAccount() throws Exception {
        Path corpus = Files.createTempFile("ledger", ".jsonl");
        Files.writeString(corpus, line("m-1") + "\n" + cardLine("m-2") + "\n");
        var store = new InMemoryLedgerStore();
        new IngestService(new Parsers(), store).ingestFile(corpus);
        assertEquals(Category.MICRO, store.all().stream().filter(t -> t.accountLast4().equals("4821"))
                .findFirst().orElseThrow().category());
        assertTrue(store.all().stream().anyMatch(t -> t.accountLast4().equals("3310")
                && t.category() == Category.SPEND));
    }

    private static RawMessage raw(String sender, String body) {
        return new RawMessage("m", "sms", sender, OffsetDateTime.parse("2026-08-01T14:22:00+05:30"), "d", body);
    }
    private static String line(String id) {
        return "{\"message_id\":\"" + id + "\",\"channel\":\"sms\",\"sender\":\"AD-HDFCBK-S\",\"received_at\":\"2026-07-01T09:00:00+05:30\",\"device_id\":\"d\",\"body\":\"Rs.5 debited from a/c **4821 on 01-07-26 at 09:00 to UPI/TEA.\"}";
    }
    private static String cardLine(String id) {
        return "{\"message_id\":\"" + id + "\",\"channel\":\"sms\",\"sender\":\"AD-HDFCBK-S\",\"received_at\":\"2026-07-01T10:00:00+05:30\",\"device_id\":\"d\",\"body\":\"Rs.47.33 spent on HDFC Bank Card x3310 at DMART on 01-07-26 10:00.\"}";
    }
}
