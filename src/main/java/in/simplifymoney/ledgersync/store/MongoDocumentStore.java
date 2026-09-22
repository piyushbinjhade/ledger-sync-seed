package in.simplifymoney.ledgersync.store;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.ExplainVerbosity;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.types.Decimal128;

/** Persistent MongoDB implementation of the three ledger access patterns. */
public final class MongoDocumentStore implements DocumentStore, AutoCloseable {

    public static final String DEFAULT_URI = "mongodb://localhost:27017";
    public static final String DEFAULT_DATABASE = "ledger_sync";
    public static final String DEFAULT_COLLECTION = "transactions";
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final MongoClient client;
    private final MongoCollection<Document> collection;
    private QueryMetrics lastQueryMetrics = new QueryMetrics("none", 0, 0);

    public MongoDocumentStore() {
        this(System.getenv().getOrDefault("MONGODB_URI", DEFAULT_URI),
                System.getenv().getOrDefault("MONGODB_DATABASE", DEFAULT_DATABASE));
    }

    public MongoDocumentStore(String uri, String databaseName) {
        client = MongoClients.create(uri);
        MongoDatabase database = client.getDatabase(databaseName);
        collection = database.getCollection(DEFAULT_COLLECTION);
        ensureIndexes();
    }

    private void ensureIndexes() {
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending("accountLast4"),
            Indexes.descending("occurredAtInstant")),
            new com.mongodb.client.model.IndexOptions().name("account_month_newest"));
        collection.createIndex(Indexes.ascending("sourceMessageIds"),
                new com.mongodb.client.model.IndexOptions().name("source_message_ids"));
    }

    @Override
    public void save(NormalizedTxn txn) {
        Document replacement = toDocument(txn);
        Document existing = collection.find(Filters.eq("_id", identity(txn))).first();
        if (existing != null) {
            Set<String> ids = new TreeSet<>(existing.getList("sourceMessageIds", String.class));
            ids.addAll(txn.sourceMessageIds());
            replacement.put("sourceMessageIds", new ArrayList<>(ids));
        }
        collection.replaceOne(Filters.eq("_id", identity(txn)), replacement,
                new ReplaceOptions().upsert(true));
    }

    @Override
    public List<NormalizedTxn> all() {
        return collection.find().sort(Sorts.ascending("occurredAtInstant", "accountLast4"))
                .map(MongoDocumentStore::fromDocument).into(new ArrayList<>());
    }

    @Override
    public List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
        OffsetDateTime start = month.atDay(1).atStartOfDay().atOffset(java.time.ZoneOffset.ofHoursMinutes(5, 30));
        OffsetDateTime end = month.plusMonths(1).atDay(1).atStartOfDay().atOffset(start.getOffset());
        Bson filter = Filters.and(Filters.eq("accountLast4", accountLast4),
                Filters.gte("occurredAtInstant", java.util.Date.from(start.toInstant())),
                Filters.lt("occurredAtInstant", java.util.Date.from(end.toInstant())));
        List<NormalizedTxn> result = collection.find(filter).sort(Sorts.descending("occurredAtInstant"))
                .map(MongoDocumentStore::fromDocument).into(new ArrayList<>());
        lastQueryMetrics = explainFind(filter, Sorts.descending("occurredAtInstant"), "account-month", result.size());
        return result;
    }

    @Override
    public Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        List<Bson> pipeline = List.of(
            Aggregates.match(Filters.eq("accountLast4", accountLast4)),
            Aggregates.group("$category", Accumulators.sum("total", "$amount")),
            Aggregates.sort(Sorts.ascending("_id")));
        Map<Category, BigDecimal> result = new LinkedHashMap<>();
        for (Category category : Category.values()) result.put(category, ZERO);
        for (Document row : collection.aggregate(pipeline)) {
            result.put(Category.valueOf(row.getString("_id")), decimal(row.get("total")));
        }
        Document explain = collection.aggregate(pipeline).explain(ExplainVerbosity.EXECUTION_STATS);
        lastQueryMetrics = metrics(explain, "category-totals", result.size());
        return result;
    }

    @Override
    public Optional<NormalizedTxn> byMessageId(String messageId) {
        Bson filter = Filters.eq("sourceMessageIds", messageId);
        Document result = collection.find(filter).first();
        lastQueryMetrics = explainFind(filter, null, "message-id", result == null ? 0 : 1);
        return Optional.ofNullable(result).map(MongoDocumentStore::fromDocument);
    }

    @Override
    public void close() {
        client.close();
    }

    public QueryMetrics lastQueryMetrics() {
        return lastQueryMetrics;
    }

    public void reset() {
        collection.drop();
        ensureIndexes();
    }

    private QueryMetrics explainFind(Bson filter, Bson sort, String query, long returned) {
        var find = collection.find(filter);
        if (sort != null) find.sort(sort);
        return metrics(find.explain(ExplainVerbosity.EXECUTION_STATS), query, returned);
    }

    private static QueryMetrics metrics(Document explain, String query, long fallbackReturned) {
        Document stats = executionStats(explain);
        if (stats == null) return new QueryMetrics(query, -1, fallbackReturned);
        return new QueryMetrics(query, number(stats.get("totalDocsExamined")),
                number(stats.get("nReturned")));
    }

    private static Document executionStats(Object value) {
        if (value instanceof Document document) {
            Document direct = document.get("executionStats", Document.class);
            if (direct != null) return direct;
            for (Object child : document.values()) {
                Document nested = executionStats(child);
                if (nested != null) return nested;
            }
        } else if (value instanceof Iterable<?> iterable) {
            for (Object child : iterable) {
                Document nested = executionStats(child);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private static Document toDocument(NormalizedTxn txn) {
        OffsetDateTime occurredAt = txn.occurredAt();
        return new Document("_id", identity(txn))
                .append("accountLast4", txn.accountLast4())
                .append("occurredAt", occurredAt.toString())
                .append("occurredAtInstant", java.util.Date.from(occurredAt.toInstant()))
                .append("direction", txn.direction().name())
                .append("amount", new Decimal128(txn.amount()))
                .append("category", txn.category().name())
                .append("merchant", txn.merchant())
                .append("sourceMessageIds", new ArrayList<>(new TreeSet<>(txn.sourceMessageIds())));
    }

    private static NormalizedTxn fromDocument(Document document) {
        List<String> ids = document.getList("sourceMessageIds", String.class);
        return new NormalizedTxn(document.getString("accountLast4"),
                OffsetDateTime.parse(document.getString("occurredAt")),
                Direction.valueOf(document.getString("direction")),
                decimal(document.get("amount")), Category.valueOf(document.getString("category")),
                document.getString("merchant"), ids.stream().sorted().toList());
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof Decimal128 decimal) return decimal.bigDecimalValue().setScale(2);
        if (value instanceof Number number) return new BigDecimal(number.toString()).setScale(2);
        return new BigDecimal(String.valueOf(value)).setScale(2);
    }

    public static String identity(NormalizedTxn txn) {
        String canonical = txn.accountLast4() + "|" + txn.occurredAt() + "|" + txn.direction()
                + "|" + txn.amount().toPlainString() + "|" + txn.merchant();
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder();
                for (byte value : digest) hex.append(String.format("%02x", value & 0xff));
                return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record QueryMetrics(String query, long examined, long returned) {}
}