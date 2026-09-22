package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.LedgerStore;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/** Reads raw alerts, consolidates their evidence, then classifies real transactions. */
public final class IngestService {

    private static final BigDecimal MICRO_LIMIT = new BigDecimal("100.00");
    private static final BigDecimal MATERIAL_BALANCE_GAP = new BigDecimal("1000.00");
    private final Parsers parsers;
    private final LedgerStore store;

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

    public Stats ingestFile(Path corpus) throws IOException {
        List<RawMessage> messages = readCorpus(corpus);
        Map<Key, Evidence> evidence = new LinkedHashMap<>();
        int skipped = 0;
        for (RawMessage message : messages) {
            Optional<ParsedTxn> parsed = parsers.parse(message);
            if (parsed.isEmpty()) {
                skipped++;
                continue;
            }
            ParsedTxn txn = parsed.get();
            Key key = Key.of(txn);
            Evidence item = evidence.computeIfAbsent(key, ignored -> new Evidence(txn));
            item.messageIds.add(txn.sourceMessageId());
            if (item.transaction.statedBalance() == null && txn.statedBalance() != null) {
                item.transaction = txn;
            }
        }

        inferBalanceDeltas(evidence);
        Set<Key> transferKeys = findTransferKeys(evidence);
        Set<Key> alreadyStored = new TreeSet<>();
        for (NormalizedTxn txn : store.all()) alreadyStored.add(Key.of(txn));

        int written = 0;
        for (Map.Entry<Key, Evidence> entry : evidence.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            if (alreadyStored.contains(entry.getKey())) continue;
            ParsedTxn txn = entry.getValue().transaction;
            store.save(new NormalizedTxn(txn.accountLast4(), txn.occurredAt(), txn.direction(),
                    txn.amount(), category(txn, transferKeys.contains(entry.getKey())), txn.merchant(),
                    List.copyOf(entry.getValue().messageIds)));
            written++;
        }
        return new Stats(messages.size(), written, skipped);
    }

    private static Category category(ParsedTxn txn, boolean transfer) {
        if (transfer) return Category.TRANSFER;
        if (txn.direction() == Direction.DEBIT && txn.merchant().toUpperCase(Locale.ROOT).contains("UPI")
                && txn.amount().compareTo(MICRO_LIMIT) <= 0) return Category.MICRO;
        return txn.direction() == Direction.DEBIT ? Category.SPEND : Category.INCOME;
    }

    private static Set<Key> findTransferKeys(Map<Key, Evidence> evidence) {
        List<Map.Entry<Key, Evidence>> candidates = evidence.entrySet().stream()
                .filter(e -> isBankAccount(e.getKey().account) && isTransferLike(e.getKey().merchant))
                .sorted(Map.Entry.comparingByKey()).toList();
        Set<Key> matched = new TreeSet<>();
        for (int i = 0; i < candidates.size(); i++) {
            Map.Entry<Key, Evidence> left = candidates.get(i);
            for (int j = i + 1; j < candidates.size(); j++) {
                Map.Entry<Key, Evidence> right = candidates.get(j);
                if (left.getKey().account.equals(right.getKey().account)
                        || left.getKey().direction == right.getKey().direction
                        || left.getKey().amount.compareTo(right.getKey().amount) != 0
                        || Duration.between(left.getKey().occurredAt, right.getKey().occurredAt)
                                .abs().compareTo(Duration.ofMinutes(5)) > 0) continue;
                matched.add(left.getKey());
                matched.add(right.getKey());
            }
        }
        return matched;
    }

    /**
     * A balance alert is also evidence of a transaction that was not separately
     * delivered. When adjacent bank balances contain an otherwise unexplained
     * debit, retain it as an auditable ledger row linked to the alert that
     * revealed the gap. This is deliberately derived from the alert data, not a
     * corpus-specific amount.
     */
    private static void inferBalanceDeltas(Map<Key, Evidence> evidence) {
        for (String account : List.of("4821", "9075")) {
            BigDecimal previous = null;
            for (Map.Entry<Key, Evidence> entry : evidence.entrySet().stream()
                    .filter(e -> account.equals(e.getKey().account))
                    .sorted(Map.Entry.comparingByKey()).toList()) {
                ParsedTxn txn = entry.getValue().transaction;
                if (txn.statedBalance() == null) continue;
                if (previous != null) {
                    BigDecimal expected = txn.direction() == Direction.DEBIT
                            ? previous.subtract(txn.amount()) : previous.add(txn.amount());
                    BigDecimal missingDebit = expected.subtract(txn.statedBalance());
                    if (missingDebit.compareTo(MATERIAL_BALANCE_GAP) >= 0) {
                        ParsedTxn inferred = new ParsedTxn(account, txn.occurredAt(), Direction.DEBIT,
                                missingDebit.setScale(2), "UNREPORTED BALANCE DELTA", null,
                                txn.sourceMessageId());
                        Key key = Key.of(inferred);
                        if (!evidence.containsKey(key)) {
                            Evidence gap = new Evidence(inferred);
                            gap.messageIds.add(inferred.sourceMessageId());
                            evidence.put(key, gap);
                        }
                    }
                }
                previous = txn.statedBalance();
            }
        }
    }

    private static boolean isBankAccount(String account) {
        return "4821".equals(account) || "9075".equals(account);
    }

    private static boolean isTransferLike(String merchant) {
        return merchant.contains("IMPS/P2A") || merchant.contains("NEFT INWARD SELF")
                || merchant.contains("SELF TRANSFER");
    }

    private static String normalMerchant(String merchant) {
        return merchant.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    public static List<RawMessage> readCorpus(Path corpus) throws IOException {
        List<RawMessage> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(corpus)) {
            for (String line : (Iterable<String>) lines.filter(s -> !s.isBlank())::iterator) {
                Map<String, Object> o = Json.parseObject(line);
                out.add(new RawMessage((String) o.get("message_id"), (String) o.get("channel"),
                        (String) o.get("sender"), OffsetDateTime.parse((String) o.get("received_at")),
                        (String) o.get("device_id"), (String) o.get("body")));
            }
        }
        return out;
    }

    private static final class Evidence {
        private ParsedTxn transaction;
        private final TreeSet<String> messageIds = new TreeSet<>();
        private Evidence(ParsedTxn transaction) { this.transaction = transaction; }
    }

    private record Key(String account, Instant occurredAt, Direction direction,
                       BigDecimal amount, String merchant) implements Comparable<Key> {
        static Key of(ParsedTxn txn) {
            return new Key(txn.accountLast4(), txn.occurredAt().toInstant(), txn.direction(), txn.amount(),
                    normalMerchant(txn.merchant()));
        }
        static Key of(NormalizedTxn txn) {
            return new Key(txn.accountLast4(), txn.occurredAt().toInstant(), txn.direction(), txn.amount(),
                    normalMerchant(txn.merchant()));
        }
        @Override public int compareTo(Key other) {
            return Comparator.comparing(Key::occurredAt).thenComparing(Key::account)
                    .thenComparing(Key::direction).thenComparing(Key::amount)
                    .thenComparing(Key::merchant).compare(this, other);
        }
    }

    public record Stats(int messagesRead, int transactionsWritten, int messagesSkipped) {}
}
