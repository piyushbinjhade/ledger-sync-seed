package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.Backfill;
import in.simplifymoney.ledgersync.store.ConsistencyChecker;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import in.simplifymoney.ledgersync.store.MongoDocumentStore;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class MongoDocumentStoreTest {

    @Test
    void backfillIsRepeatableAndDetectsAnAlteredField() {
        assumeTrue("true".equalsIgnoreCase(System.getenv("MONGODB_TEST")),
                "Set MONGODB_TEST=true after docker compose up to run MongoDB integration tests");
        String database = "ledger_sync_test_" + System.nanoTime();
        NormalizedTxn sourceTxn = txn();
        var sql = new InMemoryLedgerStore();
        sql.save(sourceTxn);
        try (var documents = new MongoDocumentStore(
                System.getenv().getOrDefault("MONGODB_URI", MongoDocumentStore.DEFAULT_URI), database)) {
            assertEquals(new Backfill.Result(1, 1, 0), new Backfill(sql, documents).run());
            assertEquals(new Backfill.Result(1, 0, 1), new Backfill(sql, documents).run());
            try (var client = MongoClients.create(System.getenv().getOrDefault("MONGODB_URI", MongoDocumentStore.DEFAULT_URI))) {
                client.getDatabase(database).getCollection(MongoDocumentStore.DEFAULT_COLLECTION)
                        .updateOne(Filters.eq("_id", MongoDocumentStore.identity(sourceTxn)),
                                Updates.set("category", Category.SPEND.name()));
            }
            var divergences = new ConsistencyChecker(sql, documents).check();
            assertTrue(divergences.stream().anyMatch(d -> d.what().equals("changed field category")));
        }
    }

    private static NormalizedTxn txn() {
        return new NormalizedTxn("4821", OffsetDateTime.parse("2026-07-01T09:00:00+05:30"),
                Direction.DEBIT, new BigDecimal("10.00"), Category.MICRO, "UPI/TEST", List.of("mongo-test-1"));
    }
}