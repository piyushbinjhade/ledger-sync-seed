package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.Backfill;
import in.simplifymoney.ledgersync.store.ConsistencyChecker;
import in.simplifymoney.ledgersync.store.InMemoryDocumentStore;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentStoreTest {

    @Test
    void indexedQueriesReturnExpectedRowsAndMetrics() {
        var store = new InMemoryDocumentStore();
        NormalizedTxn older = txn("2026-07-01T09:00:00+05:30", "100.00", Category.MICRO, "m-old");
        NormalizedTxn newer = txn("2026-07-20T09:00:00+05:30", "250.00", Category.SPEND, "m-new");
        store.save(older);
        store.save(newer);

        assertEquals(List.of(newer, older), store.forAccountMonth("4821", YearMonth.of(2026, 7)));
        assertEquals(new InMemoryDocumentStore.QueryMetrics("account-month", 2, 2), store.lastQueryMetrics());
        assertEquals(new BigDecimal("350.00"), store.categoryTotals("4821").values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        assertEquals(2, store.lastQueryMetrics().examined());
        assertEquals(4, store.lastQueryMetrics().returned());
        assertEquals(newer, store.byMessageId("m-new").orElseThrow());
        assertEquals(new InMemoryDocumentStore.QueryMetrics("message-id", 1, 1), store.lastQueryMetrics());
    }

    @Test
    void saveMergesSameDocumentAndMessageIds() {
        var store = new InMemoryDocumentStore();
        NormalizedTxn first = txn("2026-07-01T09:00:00+05:30", "100.00", Category.MICRO, "m-2");
        NormalizedTxn second = txn("2026-07-01T09:00:00+05:30", "100.00", Category.MICRO, "m-1");
        store.save(first);
        store.save(second);
        assertEquals(1, store.all().size());
        assertEquals(List.of("m-1", "m-2"), store.all().getFirst().sourceMessageIds());
    }

    @Test
    void backfillCanonicalizesDuplicatesAndIsIdempotent() {
        var sql = new InMemoryLedgerStore();
        sql.save(txn("2026-07-01T09:00:00+05:30", "100.00", Category.MICRO, "m-2"));
        sql.save(txn("2026-07-01T09:00:00+05:30", "100.00", Category.MICRO, "m-1"));
        var documents = new InMemoryDocumentStore();
        assertEquals(new Backfill.Result(2, 1, 1), new Backfill(sql, documents).run());
        assertEquals(new Backfill.Result(2, 0, 2), new Backfill(sql, documents).run());
        assertEquals(1, documents.all().size());
    }

    @Test
    void consistencyCheckerReportsAlteredMissingAndExtraData() {
        var sql = new InMemoryLedgerStore();
        NormalizedTxn source = txn("2026-07-01T09:00:00+05:30", "100.00", Category.MICRO, "m-1");
        sql.save(source);
        var documents = new InMemoryDocumentStore();
        documents.save(new NormalizedTxn("4821", source.occurredAt(), Direction.DEBIT,
                source.amount(), Category.SPEND, source.merchant(), source.sourceMessageIds()));
        documents.save(txn("2026-07-02T09:00:00+05:30", "25.00", Category.SPEND, "m-extra"));
        var divergences = new ConsistencyChecker(sql, documents).check();
        assertTrue(divergences.stream().anyMatch(d -> d.what().equals("changed field category")));
        assertTrue(divergences.stream().anyMatch(d -> d.what().startsWith("extra transaction")));
        assertFalse(divergences.isEmpty());
    }

    private static NormalizedTxn txn(String when, String amount, Category category, String messageId) {
        return new NormalizedTxn("4821", OffsetDateTime.parse(when), Direction.DEBIT,
                new BigDecimal(amount), category, "UPI/TEST", List.of(messageId));
    }
}