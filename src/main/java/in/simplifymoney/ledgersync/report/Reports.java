package in.simplifymoney.ledgersync.report;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Deterministic ledger documents and aggregate reports. */
public final class Reports {
    private Reports() {}
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    public static Map<String, Object> summary(List<NormalizedTxn> ledger) {
        Map<String, Object> accounts = new TreeMap<>();
        for (NormalizedTxn txn : sorted(ledger)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> account = (Map<String, Object>) accounts.computeIfAbsent(
                    txn.accountLast4(), ignored -> account());
            add(account, txn);
        }
        for (Object value : accounts.values()) {
            @SuppressWarnings("unchecked") Map<String, Object> account = (Map<String, Object>) value;
            for (String key : List.of("spend", "income", "micro_total", "transferred_out", "transferred_in"))
                account.put(key, ((BigDecimal) account.get(key)).toPlainString());
        }
        Map<String, Object> categories = new LinkedHashMap<>();
        for (Category category : Category.values()) {
            BigDecimal amount = ZERO;
            long count = 0;
            for (NormalizedTxn txn : ledger) if (txn.category() == category) {
                amount = amount.add(txn.amount()); count++;
            }
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("count", count); value.put("total", amount.toPlainString());
            categories.put(category.name(), value);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("transaction_count", ledger.size());
        out.put("categories", categories);
        out.put("accounts", accounts);
        return out;
    }

    private static Map<String, Object> account() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("spend", ZERO); value.put("income", ZERO);
        value.put("micro_count", 0L); value.put("micro_total", ZERO);
        value.put("transferred_out", ZERO); value.put("transferred_in", ZERO);
        return value;
    }

    private static void add(Map<String, Object> account, NormalizedTxn txn) {
        String field = switch (txn.category()) {
            case SPEND -> "spend";
            case INCOME -> "income";
            case MICRO -> "micro_total";
            case TRANSFER -> txn.direction() == Direction.DEBIT ? "transferred_out" : "transferred_in";
        };
        account.put(field, ((BigDecimal) account.get(field)).add(txn.amount()));
        if (txn.category() == Category.MICRO) account.put("micro_count", (long) account.get("micro_count") + 1);
    }

    public static Map<String, Object> ledgerDocument(List<NormalizedTxn> ledger) {
        List<Object> rows = sorted(ledger).stream().map(t -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("account_last4", t.accountLast4()); row.put("occurred_at", t.occurredAt().toString());
            row.put("direction", t.direction().name().toLowerCase()); row.put("amount", t.amount().toPlainString());
            row.put("category", t.category().name()); row.put("merchant", t.merchant());
            row.put("source_message_ids", t.sourceMessageIds().stream().sorted().toList());
            return (Object) row;
        }).toList();
        return Map.of("transactions", rows);
    }

    /** Without a supplied expected baseline there is no honest discrepancy to assert. */
    public static Map<String, Object> reconciliation(List<NormalizedTxn> ledger) {
        return Map.of("discrepancies", List.of(), "transaction_count", ledger.size());
    }

    /** Compares produced aggregates with a caller-supplied fixture baseline. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> reconciliation(List<NormalizedTxn> ledger,
                                                       Map<String, Object> expected) {
        List<Map<String, Object>> discrepancies = new java.util.ArrayList<>();
        if (!String.valueOf(ledger.size()).equals(String.valueOf(expected.get("transactions_expected")))) {
            discrepancies.add(Map.of("field", "transactions", "expected",
                    String.valueOf(expected.get("transactions_expected")), "actual", String.valueOf(ledger.size())));
        }
        Map<String, Object> actualAccounts = (Map<String, Object>) summary(ledger).get("accounts");
        Map<String, Object> expectedAccounts = (Map<String, Object>) expected.get("accounts");
        for (Map.Entry<String, Object> entry : expectedAccounts.entrySet()) {
            Map<String, Object> want = (Map<String, Object>) entry.getValue();
            Map<String, Object> got = (Map<String, Object>) actualAccounts.get(entry.getKey());
            if (got == null) {
                discrepancies.add(Map.of("account_last4", entry.getKey(), "note", "account missing"));
                continue;
            }
            for (String field : List.of("spend", "income", "micro_count", "micro_total",
                    "transferred_out", "transferred_in")) {
                if (!String.valueOf(want.get(field)).equals(String.valueOf(got.get(field)))) {
                    discrepancies.add(Map.of("account_last4", entry.getKey(), "field", field,
                            "expected", String.valueOf(want.get(field)), "actual", String.valueOf(got.get(field))));
                }
            }
        }
        return Map.of("discrepancies", discrepancies, "transaction_count", ledger.size());
    }

    public static Map<Category, BigDecimal> byCategory(List<NormalizedTxn> ledger) {
        Map<Category, BigDecimal> out = new LinkedHashMap<>();
        for (Category category : Category.values()) out.put(category, ZERO);
        for (NormalizedTxn txn : ledger) out.put(txn.category(), out.get(txn.category()).add(txn.amount()));
        return out;
    }

    private static List<NormalizedTxn> sorted(List<NormalizedTxn> ledger) {
        return ledger.stream().sorted(Comparator.comparing(NormalizedTxn::occurredAt)
                .thenComparing(NormalizedTxn::accountLast4).thenComparing(NormalizedTxn::merchant)).toList();
    }
}
