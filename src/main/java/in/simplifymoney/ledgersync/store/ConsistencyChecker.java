package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.TreeMap;

/**
 * Proves the two stores agree, and says precisely where they do not.
 *
 * NOT IMPLEMENTED - this is yours.
 *
 * We will run your checker against a document store we have deliberately
 * altered. It has to find what we changed and name it. A checker that only
 * compares row counts will not.
 */
public final class ConsistencyChecker {

    private final LedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(LedgerStore sql, DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }

    public List<Divergence> check() {
        Map<InMemoryDocumentStore.DocumentKey, NormalizedTxn> sqlRows = index(sql.all());
        Map<InMemoryDocumentStore.DocumentKey, NormalizedTxn> documentRows = index(documents.all());
        var divergences = new java.util.ArrayList<Divergence>();
        var unmatchedSql = new java.util.HashSet<>(sqlRows.keySet());
        var unmatchedDocuments = new java.util.HashSet<>(documentRows.keySet());
        for (var entry : sqlRows.entrySet()) {
            NormalizedTxn document = documentRows.get(entry.getKey());
            if (document == null) {
                continue;
            } else if (!entry.getValue().equals(document)) {
                divergences.addAll(fieldDifferences(entry.getValue(), document));
            }
            unmatchedSql.remove(entry.getKey());
            unmatchedDocuments.remove(entry.getKey());
        }
        for (var sqlKey : unmatchedSql) {
            NormalizedTxn sqlTxn = sqlRows.get(sqlKey);
            var matchingDocument = documentRows.values().stream()
                    .filter(document -> sharesMessageId(sqlTxn, document)).findFirst();
            if (matchingDocument.isPresent()) {
                divergences.addAll(fieldDifferences(sqlTxn, matchingDocument.get()));
                unmatchedDocuments.remove(InMemoryDocumentStore.DocumentKey.of(matchingDocument.get()));
            } else {
                divergences.add(new Divergence("missing transaction " + sqlKey,
                        describe(sqlTxn), "missing"));
            }
        }
        for (var documentKey : unmatchedDocuments) {
            NormalizedTxn document = documentRows.get(documentKey);
            divergences.add(new Divergence("extra transaction " + documentKey,
                    "missing", describe(document)));
        }
        return divergences;
    }

    private static boolean sharesMessageId(NormalizedTxn left, NormalizedTxn right) {
        var ids = new HashSet<>(left.sourceMessageIds());
        ids.retainAll(right.sourceMessageIds());
        return !ids.isEmpty();
    }

    private static List<Divergence> fieldDifferences(NormalizedTxn sql, NormalizedTxn document) {
        var result = new java.util.ArrayList<Divergence>();
        addDifference(result, "accountLast4", sql.accountLast4(), document.accountLast4());
        addDifference(result, "occurredAt", sql.occurredAt(), document.occurredAt());
        addDifference(result, "direction", sql.direction(), document.direction());
        addDifference(result, "amount", sql.amount(), document.amount());
        addDifference(result, "category", sql.category(), document.category());
        addDifference(result, "merchant", sql.merchant(), document.merchant());
        addDifference(result, "sourceMessageIds", sql.sourceMessageIds(), document.sourceMessageIds());
        return result;
    }

    private static void addDifference(List<Divergence> result, String field, Object sql, Object document) {
        if (!java.util.Objects.equals(sql, document)) {
            result.add(new Divergence("changed field " + field, String.valueOf(sql), String.valueOf(document)));
        }
    }

    private static Map<InMemoryDocumentStore.DocumentKey, NormalizedTxn> index(List<NormalizedTxn> rows) {
        Map<InMemoryDocumentStore.DocumentKey, NormalizedTxn> out = new TreeMap<>();
        for (NormalizedTxn row : rows) {
            var key = InMemoryDocumentStore.DocumentKey.of(row);
            NormalizedTxn existing = out.get(key);
            if (existing == null) out.put(key, row);
            else out.put(key, merge(existing, row));
        }
        return out;
    }

    private static NormalizedTxn merge(NormalizedTxn left, NormalizedTxn right) {
        var ids = new java.util.TreeSet<String>();
        ids.addAll(left.sourceMessageIds());
        ids.addAll(right.sourceMessageIds());
        return new NormalizedTxn(left.accountLast4(), left.occurredAt(), left.direction(), left.amount(),
                right.category(), right.merchant().isBlank() ? left.merchant() : right.merchant(),
                new java.util.ArrayList<>(ids));
    }

    private static String describe(NormalizedTxn txn) {
        return txn.accountLast4() + "|" + txn.occurredAt() + "|" + txn.direction() + "|"
                + txn.amount().toPlainString() + "|" + txn.category() + "|" + txn.merchant()
                + "|" + String.join(",", txn.sourceMessageIds());
    }

    /** One place the two stores disagree. */
    public record Divergence(String what, String inSql, String inDocuments) {}
}
