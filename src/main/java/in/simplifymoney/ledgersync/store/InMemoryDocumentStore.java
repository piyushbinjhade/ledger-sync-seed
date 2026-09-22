package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** A dependency-free document-store implementation with the production indexes. */
public final class InMemoryDocumentStore implements DocumentStore {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private final Map<DocumentKey, NormalizedTxn> documents = new TreeMap<>();
    private final Map<AccountMonth, Set<DocumentKey>> monthIndex = new TreeMap<>();
    private final Map<String, Set<DocumentKey>> accountIndex = new TreeMap<>();
    private final Map<String, DocumentKey> messageIndex = new TreeMap<>();
    private QueryMetrics lastQueryMetrics = new QueryMetrics("none", 0, 0);

    @Override
    public synchronized void save(NormalizedTxn txn) {
        DocumentKey key = DocumentKey.of(txn);
        NormalizedTxn existing = documents.get(key);
        NormalizedTxn stored = existing == null ? txn : merge(existing, txn);
        if (existing != null) removeIndexes(key, existing);
        documents.put(key, stored);
        addIndexes(key, stored);
    }

    @Override
    public synchronized List<NormalizedTxn> all() {
        return documents.values().stream().sorted(DocumentKey.ORDER).toList();
    }

    @Override
    public synchronized List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
        Set<DocumentKey> keys = monthIndex.getOrDefault(new AccountMonth(accountLast4, month), Set.of());
        List<NormalizedTxn> result = keys.stream().map(documents::get)
                .sorted(DocumentKey.ORDER.reversed()).toList();
        lastQueryMetrics = new QueryMetrics("account-month", keys.size(), result.size());
        return result;
    }

    @Override
    public synchronized Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        Set<DocumentKey> keys = accountIndex.getOrDefault(accountLast4, Set.of());
        Map<Category, BigDecimal> result = new LinkedHashMap<>();
        for (Category category : Category.values()) result.put(category, ZERO);
        for (DocumentKey key : keys) {
            NormalizedTxn txn = documents.get(key);
            result.put(txn.category(), result.get(txn.category()).add(txn.amount()));
        }
        lastQueryMetrics = new QueryMetrics("category-totals", keys.size(), result.size());
        return result;
    }

    @Override
    public synchronized Optional<NormalizedTxn> byMessageId(String messageId) {
        DocumentKey key = messageIndex.get(messageId);
        Optional<NormalizedTxn> result = Optional.ofNullable(key == null ? null : documents.get(key));
        lastQueryMetrics = new QueryMetrics("message-id", key == null ? 0 : 1, result.isPresent() ? 1 : 0);
        return result;
    }

    public synchronized QueryMetrics lastQueryMetrics() {
        return lastQueryMetrics;
    }

    private void addIndexes(DocumentKey key, NormalizedTxn txn) {
        monthIndex.computeIfAbsent(new AccountMonth(txn.accountLast4(), YearMonth.from(txn.occurredAt())),
                ignored -> new TreeSet<>()).add(key);
        accountIndex.computeIfAbsent(txn.accountLast4(), ignored -> new TreeSet<>()).add(key);
        for (String messageId : txn.sourceMessageIds()) messageIndex.put(messageId, key);
    }

    private void removeIndexes(DocumentKey key, NormalizedTxn txn) {
        monthIndex.getOrDefault(new AccountMonth(txn.accountLast4(), YearMonth.from(txn.occurredAt())), Set.of()).remove(key);
        accountIndex.getOrDefault(txn.accountLast4(), Set.of()).remove(key);
        for (String messageId : txn.sourceMessageIds()) messageIndex.remove(messageId, key);
    }

    private static NormalizedTxn merge(NormalizedTxn left, NormalizedTxn right) {
        Set<String> ids = new TreeSet<>(left.sourceMessageIds());
        ids.addAll(right.sourceMessageIds());
        return new NormalizedTxn(left.accountLast4(), left.occurredAt(), left.direction(), left.amount(),
                right.category(), right.merchant().isBlank() ? left.merchant() : right.merchant(),
                new ArrayList<>(ids));
    }

    public record QueryMetrics(String query, long examined, long returned) {}

    private record AccountMonth(String account, YearMonth month) implements Comparable<AccountMonth> {
        @Override public int compareTo(AccountMonth other) {
            return account.compareTo(other.account) != 0
                    ? account.compareTo(other.account) : month.compareTo(other.month);
        }
    }

    static record DocumentKey(String account, java.time.OffsetDateTime occurredAt,
                              in.simplifymoney.ledgersync.model.Direction direction,
                              BigDecimal amount, String merchant) implements Comparable<DocumentKey> {
        static final Comparator<NormalizedTxn> ORDER = Comparator.comparing(NormalizedTxn::occurredAt)
                .thenComparing(NormalizedTxn::accountLast4).thenComparing(NormalizedTxn::direction)
                .thenComparing(NormalizedTxn::amount).thenComparing(NormalizedTxn::merchant);
        static DocumentKey of(NormalizedTxn txn) {
            return new DocumentKey(txn.accountLast4(), txn.occurredAt(), txn.direction(), txn.amount(), txn.merchant());
        }
        @Override public int compareTo(DocumentKey other) {
            return Comparator.comparing(DocumentKey::occurredAt).thenComparing(DocumentKey::account)
                    .thenComparing(DocumentKey::direction).thenComparing(DocumentKey::amount)
                    .thenComparing(DocumentKey::merchant).compare(this, other);
        }
    }
}