package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bank transaction alert emails.
 *
 * Not written yet. The corpus contains them and they are currently all dropped.
 */
public final class EmailParser implements MessageParser {

    private static final Pattern DATE_HEADER = Pattern.compile(
            "(?m)^Date:\\s*(?<date>[^\\r\\n]+)$");

    private static final Pattern TRANSACTION = Pattern.compile(
            "Your account ending (?<acct>\\d{4}) has been "
                    + "(?<direction>credited|debited) with "
                    + "(?:INR\\s+|Rs\\.\\s*)(?<amount>[0-9][0-9,]*(?:\\.[0-9]{2})?)\\."
                    + "\\RMerchant / Remarks:\\s*(?<merchant>[^\\r\\n]+)");

    @Override
    public boolean supports(RawMessage m) {
        return "email".equals(m.channel());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        Matcher transaction = TRANSACTION.matcher(m.body());
        if (!transaction.find()) return Optional.empty();

        Matcher dateHeader = DATE_HEADER.matcher(m.body());
        if (!dateHeader.find()) return Optional.empty();

        OffsetDateTime occurredAt;
        try {
            occurredAt = OffsetDateTime.parse(dateHeader.group("date").trim(),
                    DateTimeFormatter.RFC_1123_DATE_TIME);
        } catch (DateTimeParseException ignored) {
            return Optional.empty();
        }

        Direction direction = "debited".equals(transaction.group("direction"))
                ? Direction.DEBIT : Direction.CREDIT;
        BigDecimal amount = new BigDecimal(transaction.group("amount").replace(",", ""))
                .setScale(2);
        return Optional.of(new ParsedTxn(transaction.group("acct"), occurredAt, direction,
                amount, transaction.group("merchant").trim(), Amounts.statedBalance(m.body()),
                m.messageId()));
    }
}
