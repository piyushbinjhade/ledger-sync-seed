package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Measurement of MongoDB execution statistics at 100k rows. */
public final class DocumentStoreBenchmark {
    private DocumentStoreBenchmark() {}

    public static void main(String[] args) {
        String uri = System.getenv().getOrDefault("MONGODB_URI", MongoDocumentStore.DEFAULT_URI);
        String database = System.getenv().getOrDefault("MONGODB_BENCHMARK_DATABASE", "ledger_sync_benchmark");
        try (var store = new MongoDocumentStore(uri, database)) {
            store.reset();
            run(store);
        }
    }

    private static void run(MongoDocumentStore store) {
        for (int i = 0; i < 100_000; i++) {
            String account = String.format("%04d", i % 100);
            int day = (i % 28) + 1;
            String messageId = "bench-" + i;
            store.save(new NormalizedTxn(account,
                    OffsetDateTime.parse("2026-07-" + String.format("%02d", day) + "T09:00:00+05:30"),
                    Direction.DEBIT, new BigDecimal("10.00"), Category.SPEND, "BENCH-" + i,
                    java.util.List.of(messageId)));
        }
        store.forAccountMonth("0042", java.time.YearMonth.of(2026, 7));
        print(store.lastQueryMetrics());
        store.categoryTotals("0042");
        print(store.lastQueryMetrics());
        store.byMessageId("bench-42");
        print(store.lastQueryMetrics());
    }

    private static void print(MongoDocumentStore.QueryMetrics metrics) {
        System.out.printf("%s examined=%d returned=%d%n", metrics.query(), metrics.examined(), metrics.returned());
    }
}