package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Moves everything already in the SQL store into the document store.
 *
 * NOT IMPLEMENTED - this is yours.
 *
 * Two things to know before you start:
 *  - the SQL store is not clean. It has been running without a uniqueness
 *    guarantee for a long time
 *  - this will be run more than once, including after a partial failure
 */
public final class Backfill {

    private final LedgerStore source;
    private final DocumentStore target;

    public Backfill(LedgerStore source, DocumentStore target) {
        this.source = source;
        this.target = target;
    }

    public Result run() {
        var rows = source.all();
        Map<InMemoryDocumentStore.DocumentKey, NormalizedTxn> canonical = new TreeMap<>();
        for (NormalizedTxn row : rows) {
            var key = InMemoryDocumentStore.DocumentKey.of(row);
            NormalizedTxn existing = canonical.get(key);
            if (existing == null) canonical.put(key, row);
            else canonical.put(key, merge(existing, row));
        }
        var existingKeys = new TreeSet<InMemoryDocumentStore.DocumentKey>();
        for (NormalizedTxn row : target.all()) existingKeys.add(InMemoryDocumentStore.DocumentKey.of(row));
        long written = 0;
        long skipped = rows.size() - canonical.size();
        for (Map.Entry<InMemoryDocumentStore.DocumentKey, NormalizedTxn> entry : canonical.entrySet()) {
            try {
                target.save(entry.getValue());
                if (!existingKeys.contains(entry.getKey())) written++;
                else skipped++;
            } catch (RuntimeException failure) {
                skipped++;
            }
        }
        return new Result(rows.size(), written, skipped);
    }

    private static NormalizedTxn merge(NormalizedTxn left, NormalizedTxn right) {
        var ids = new TreeSet<String>();
        ids.addAll(left.sourceMessageIds());
        ids.addAll(right.sourceMessageIds());
        return new NormalizedTxn(left.accountLast4(), left.occurredAt(), left.direction(), left.amount(),
                right.category(), right.merchant().isBlank() ? left.merchant() : right.merchant(),
                new ArrayList<>(ids));
    }

    public record Result(long read, long written, long skipped) {}
}
