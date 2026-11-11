package com.example.financetracker.ledger;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.financetracker.ledger.access.LedgerAccess;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.access.LedgerType;
import com.example.financetracker.ledger.domain.CategoryType;
import com.example.financetracker.ledger.domain.EntryKind;
import com.example.financetracker.ledger.domain.Money;
import com.example.financetracker.ledger.family.FamilySwitch;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The user's earlier entries like the one being typed on New entry (UX-8, D-453 to D-459, D-463), read only, in the
 * personal ledger's scope.
 *
 * <p>Which entries (D-457): for EXPENSE, the personal ledger's expenses, refunds included, and the user's own payments
 * of family expenses; for INCOME, its incomes and the user's receipts of family incomes. An entry is a personal expense
 * or income by its postings (rule 6): a posting with a category of that type, no family link of any kind, detached or
 * not, and no family kind; the old shared expense (SHARED_EXPENSE) only while family budgets are off (H4). A family row
 * is an entry linked to a record as the user's PAYMENT, the link not detached, the record not deleted and of the type,
 * in a family budget the user is an ACTIVE member of, while family budgets are on.
 *
 * <p>How they match (D-458, D-459): each word of the payee and of the memo appears in the entry's payee's name or memo,
 * case folded by the database's locale and ё read as е, as plain characters (no LIKE, so {@code %}, {@code _} and
 * {@code \} are plain); a line on the account; the category of the categorized line or of the record; and an amount
 * whose line, on an account that isn't EQUITY, written with its currency's decimals contains the amount's characters,
 * or is in the form's currency and less than 1 away from it.
 */
@Service
public class EntryMatchService {

    /** Every currency's decimals (its minor unit), for the amounts written as D-459 says; 2 for one Java doesn't know. */
    private static final String[] CURRENCY_CODES;
    private static final Integer[] CURRENCY_DIGITS;

    static {
        List<Currency> currencies = new ArrayList<>(Currency.getAvailableCurrencies());
        currencies.sort(Comparator.comparing(Currency::getCurrencyCode));
        CURRENCY_CODES = currencies.stream().map(Currency::getCurrencyCode).toArray(String[]::new);
        CURRENCY_DIGITS = currencies.stream().map(c -> Math.max(c.getDefaultFractionDigits(), 0))
                .toArray(Integer[]::new);
    }

    /** ё and Ё read as е (D-458), after the database's {@code lower}. */
    private static String folded(String sql) {
        return "translate(lower(" + sql + "), 'ёЁ', 'ее')";
    }

    private final JdbcClient jdbc;
    private final EntryService entries;
    private final LedgerAccess access;
    private final FamilySwitch familySwitch;

    EntryMatchService(JdbcClient jdbc, EntryService entries, LedgerAccess access, FamilySwitch familySwitch) {
        this.jdbc = jdbc;
        this.entries = entries;
        this.access = access;
        this.familySwitch = familySwitch;
    }

    /**
     * One page of the entries that match, newest first: by entry date, then by id, both descending.
     *
     * @param ledger the user's personal ledger
     * @param page the page's number, from 0
     * @param size the most entries a page holds
     */
    // The count, the page and its postings are read by separate queries; one snapshot keeps them consistent.
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EntryMatchPage matches(LedgerScope ledger, EntryMatchFilter filter, int page, int size) {
        // Only fixed fragments go into the SQL; every value is a parameter.
        Map<String, Object> params = new HashMap<>();
        params.put("ledgerId", ledger.ledgerId());
        params.put("type", filter.type().name());

        boolean familyOn = familySwitch.enabled() && ledger.type() == LedgerType.PERSONAL;
        List<LedgerScope> families = familyOn ? access.families(ledger.userId()) : List.of();
        StringBuilder sql = new StringBuilder("WITH fam AS (");
        if (families.isEmpty()) {
            sql.append("""
                    SELECT NULL::bigint AS entry_id, NULL::bigint AS record_id, NULL::bigint AS category_id,
                           NULL::numeric AS amount, NULL::text AS currency WHERE false""");
        } else {
            // The user's own PAYMENT links: their payment of an expense, or their receipt of an income.
            sql.append("""
                    SELECT l.entry_id, r.id AS record_id, r.category_id, r.base_amount AS amount, r.currency
                    FROM family_entry_link l
                    JOIN family_record r ON r.id = l.record_id AND r.ledger_id = l.family_ledger_id
                    WHERE l.family_ledger_id IN (:families) AND l.member_id IN (:members) AND l.link_type = 'PAYMENT'
                      AND l.detached_at IS NULL AND l.entry_id IS NOT NULL AND r.deleted_at IS NULL
                      AND r.type = :type""");
            params.put("families", families.stream().map(LedgerScope::ledgerId).toList());
            params.put("members", families.stream().map(LedgerScope::memberId).toList());
        }
        sql.append(")");

        StringBuilder where = new StringBuilder(" WHERE e.ledger_id = :ledgerId AND (f.entry_id IS NOT NULL OR (")
                .append("e.kind NOT LIKE 'FAMILY\\_%' AND cp.category_id IS NOT NULL")
                .append(" AND NOT EXISTS (SELECT FROM family_entry_link x WHERE x.entry_id = e.id)");
        if (familyOn) {
            // H4: a new entry can't be an old shared expense while family budgets are on.
            where.append(" AND e.kind <> '").append(EntryKind.SHARED_EXPENSE.name()).append("'");
        }
        where.append("))");
        words(where, params, "payee", filter.payeeWords(), "payee.name");
        words(where, params, "memo", filter.memoWords(), "e.memo");
        if (filter.accountId() != null) {
            where.append(" AND EXISTS (SELECT FROM posting p WHERE p.entry_id = e.id AND p.account_id = :accountId)");
            params.put("accountId", filter.accountId());
        }
        if (filter.categoryId() != null) {
            where.append(" AND (CASE WHEN f.entry_id IS NOT NULL THEN f.category_id = :categoryId")
                    .append(" ELSE EXISTS (SELECT FROM posting p WHERE p.entry_id = e.id")
                    .append(" AND p.category_id = :categoryId) END)");
            params.put("categoryId", filter.categoryId());
        }
        if (filter.amount() != null) {
            // An entry's amounts are its lines that aren't on an EQUITY account, each a magnitude in its currency.
            // A text block loses its leading space: the clause starts with one of its own.
            where.append(" AND EXISTS").append("""
                     (SELECT FROM posting p JOIN account a ON a.id = p.account_id
                        LEFT JOIN unnest(CAST(:codes AS text[]), CAST(:digits AS int[])) AS cd(code, digits)
                          ON cd.code = p.currency
                        WHERE p.entry_id = e.id AND a.type <> 'EQUITY'
                          AND (strpos(round(abs(p.amount), coalesce(cd.digits, 2))::text, :amountText) > 0""");
            params.put("codes", CURRENCY_CODES);
            params.put("digits", CURRENCY_DIGITS);
            params.put("amountText", filter.amount());
            if (filter.currency() != null) {
                where.append(" OR p.currency = :currency AND abs(abs(p.amount) - :amount) < 1");
                params.put("currency", filter.currency());
                params.put("amount", filter.amountValue());
            }
            where.append("))");
        }

        // The categorized line of the type, which makes a personal entry an expense or an income, and shows on its row.
        String from = sql + """
                 SELECT {columns} FROM journal_entry e
                LEFT JOIN fam f ON f.entry_id = e.id
                LEFT JOIN counterparty payee ON payee.id = e.payee_id
                LEFT JOIN LATERAL (SELECT p.category_id, p.amount FROM posting p
                    JOIN category c ON c.id = p.category_id
                    WHERE p.entry_id = e.id AND c.type = :type ORDER BY p.line_no LIMIT 1) cp ON true
                """ + where;

        long total = jdbc.sql(from.replace("{columns}", "count(*)")).params(params).query(Long.class).single();
        params.put("limit", size);
        params.put("offset", (long) page * size);
        Map<Long, Row> rows = new LinkedHashMap<>();
        List<EntryView> headers = new ArrayList<>();
        jdbc.sql(from.replace("{columns}", """
                        e.id, e.version, e.entry_date, e.kind, e.payee_id, e.memo, f.record_id,
                        f.category_id AS record_category, f.amount AS record_amount, f.currency AS record_currency,
                        cp.category_id AS line_category, cp.amount AS category_amount,
                        (SELECT p.amount FROM posting p JOIN account a ON a.id = p.account_id
                           WHERE p.entry_id = e.id AND a.type <> 'EQUITY' ORDER BY p.line_no LIMIT 1) AS account_amount,
                        (SELECT p.currency FROM posting p JOIN account a ON a.id = p.account_id
                           WHERE p.entry_id = e.id AND a.type <> 'EQUITY' ORDER BY p.line_no LIMIT 1) AS account_currency""")
                        + " ORDER BY e.entry_date DESC, e.id DESC LIMIT :limit OFFSET :offset")
                .params(params)
                .query(row -> {
                    EntryView header = EntryService.header(row, 0);
                    headers.add(header);
                    rows.put(header.id(), row.getObject("record_id", Long.class) != null
                            ? familyRow(row.getBigDecimal("record_amount"), row.getString("record_currency"),
                                    row.getObject("record_category", Long.class))
                            : personalRow(filter.type(), row.getBigDecimal("account_amount"),
                                    row.getString("account_currency"), row.getObject("line_category", Long.class),
                                    row.getBigDecimal("category_amount")));
                });
        List<EntryMatch> content = entries.withPostings(ledger, headers).stream()
                .map(entry -> rows.get(entry.id()).of(entry))
                .toList();
        return new EntryMatchPage(content, page, size, total, Math.toIntExact((total + size - 1) / size));
    }

    /** Each word must appear in the column, case and ё folded, as plain characters ({@code strpos}, not LIKE). */
    private static void words(StringBuilder where, Map<String, Object> params, String name, List<String> words,
            String column) {
        for (int i = 0; i < words.size(); i++) {
            String param = name + i;
            where.append(" AND strpos(").append(folded("coalesce(" + column + ", '')")).append(", ")
                    .append(folded("CAST(:" + param + " AS text)")).append(") > 0");
            params.put(param, words.get(i));
        }
    }

    /** What a family row shows: its record's amount, currency and category; a negative record is a refund (D-79). */
    private static Row familyRow(BigDecimal amount, String currency, Long categoryId) {
        return new Row(Money.normalize(amount.abs()), currency, categoryId, amount.signum() < 0);
    }

    /**
     * What a personal entry shows: its line on its account and its categorized line's category. An expense posts a
     * positive amount to the category and an income a negative one (rule 5); the opposite sign is a refund.
     */
    private static Row personalRow(CategoryType type, BigDecimal accountAmount, String currency, Long categoryId,
            BigDecimal categoryAmount) {
        boolean refund = categoryAmount != null
                && categoryAmount.signum() == (type == CategoryType.EXPENSE ? -1 : 1);
        return new Row(accountAmount == null ? null : Money.normalize(accountAmount.abs()), currency, categoryId,
                refund);
    }

    private record Row(BigDecimal amount, String currency, Long categoryId, boolean refund) {

        EntryMatch of(EntryView entry) {
            return new EntryMatch(entry, amount, currency, categoryId, refund);
        }
    }
}
