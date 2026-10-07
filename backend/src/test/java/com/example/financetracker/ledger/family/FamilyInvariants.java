package com.example.financetracker.ledger.family;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The invariants of family ledgers (ADR 0003, topic K), in SQL of their own rather than the application's, for the
 * tests to check after every change:
 * <ul>
 * <li>in each currency, the members' family balances add up to zero (D-1, D-45);
 * <li>in each currency, for every ACTIVE member with an account, the displayed balance of their debt account for the
 * family ledger equals their family balance, today and on each record's date from their join date on, counted with the
 * opening balance of a member who took a seat (D-10, D-18), and a currency of no record leaves it at zero (ADR 0004);
 * <li>every posted entry balances in each currency, and every expense or income that isn't deleted has shares that
 * add up to its amount, and a settlement has none;
 * <li>a member who left (F6a, D-19) is detached: no row of their personal ledger references the family ledger (no
 * debt account names it, no posting uses its categories), and none of their links is still attached; a member
 * without an account who left has no attached link either. A member who returned is ACTIVE again and checked as
 * every ACTIVE member is, from their new join date, their correction included (D-26).
 * <li>a family ledger that exists has an ACTIVE member with an account: the last one who leaves, or deletes their data,
 * deletes it (D-36, D-20).
 * </ul>
 */
public final class FamilyInvariants {

    /**
     * A member's family balance in a currency, of the records in it dated by then (ADR 0003, topic D; ADR 0004): their
     * expense shares − the expenses they paid + the incomes they received − their income shares − the settlements they
     * paid + the settlements they received.
     */
    private static final String BALANCES = """
            SELECT m.id, m.user_sub, m.status, m.join_date,
                   coalesce((SELECT sum(s.amount) FROM family_share s JOIN family_record r ON r.id = s.record_id
                             WHERE s.member_id = m.id AND r.deleted_at IS NULL AND r.type = 'EXPENSE'
                               AND r.currency = :currency AND r.record_date <= :day), 0)
                   - coalesce((SELECT sum(r.base_amount) FROM family_record r
                               WHERE r.payer_member_id = m.id AND r.deleted_at IS NULL AND r.type = 'EXPENSE'
                                 AND r.currency = :currency AND r.record_date <= :day), 0)
                   + coalesce((SELECT sum(r.base_amount) FROM family_record r
                               WHERE r.payer_member_id = m.id AND r.deleted_at IS NULL AND r.type = 'INCOME'
                                 AND r.currency = :currency AND r.record_date <= :day), 0)
                   - coalesce((SELECT sum(s.amount) FROM family_share s JOIN family_record r ON r.id = s.record_id
                               WHERE s.member_id = m.id AND r.deleted_at IS NULL AND r.type = 'INCOME'
                                 AND r.currency = :currency AND r.record_date <= :day), 0)
                   - coalesce((SELECT sum(r.base_amount) FROM family_record r
                               WHERE r.payer_member_id = m.id AND r.deleted_at IS NULL AND r.type = 'SETTLEMENT'
                                 AND r.currency = :currency AND r.record_date <= :day), 0)
                   + coalesce((SELECT sum(r.base_amount) FROM family_record r
                               WHERE r.payee_member_id = m.id AND r.deleted_at IS NULL AND r.type = 'SETTLEMENT'
                                 AND r.currency = :currency AND r.record_date <= :day), 0) AS balance
            FROM ledger_member m WHERE m.ledger_id = :family ORDER BY m.join_date, m.id""";

    private FamilyInvariants() {
    }

    /**
     * Checks every invariant of the family ledgers.
     *
     * @return each member's family balance today in the main currency, by member id, of the last ledger
     */
    public static Map<Long, BigDecimal> check(JdbcClient jdbc, long... families) {
        Map<Long, BigDecimal> today = Map.of();
        for (long family : families) {
            today = checkByCurrency(jdbc, family).getOrDefault(jdbc.sql("SELECT base_currency FROM ledger WHERE id = ?")
                    .param(family).query(String.class).optional().orElse(""), Map.of());
        }
        return today;
    }

    /**
     * Checks every invariant of the family ledger.
     *
     * @return each member's family balance today, by currency and member id
     */
    public static Map<String, Map<Long, BigDecimal>> checkByCurrency(JdbcClient jdbc, long family) {
        Map<String, Map<Long, BigDecimal>> today = new LinkedHashMap<>();
        assertThat(jdbc.sql("""
                SELECT l.id FROM ledger l
                WHERE l.id = ? AND NOT EXISTS (SELECT FROM ledger_member m
                                               WHERE m.ledger_id = l.id AND m.status = 'ACTIVE'
                                                 AND m.user_sub IS NOT NULL)""")
                .param(family).query(Long.class).list()).as("a family ledger without an active member with an "
                        + "account").isEmpty();
        List<LocalDate> days = new ArrayList<>(jdbc.sql("""
                SELECT DISTINCT record_date FROM family_record WHERE ledger_id = ? ORDER BY record_date""")
                .param(family).query(LocalDate.class).list());
        days.add(LocalDate.of(9999, 12, 31));
        // Every currency of a record, and of a posting on a debt account for the family ledger, which no record
        // explains when no record is in it.
        List<String> currencies = jdbc.sql("""
                SELECT currency FROM family_record WHERE ledger_id = :family
                UNION SELECT p.currency FROM account a JOIN posting p ON p.account_id = a.id
                      WHERE a.family_ledger_id = :family
                UNION SELECT base_currency FROM ledger WHERE id = :family
                ORDER BY 1""").param("family", family).query(String.class).list();
        for (String currency : currencies) {
            for (LocalDate day : days) {
                today.put(currency, checkOn(jdbc, family, currency, day));
            }
        }
        assertThat(jdbc.sql("""
                SELECT r.id FROM family_record r LEFT JOIN family_share s ON s.record_id = r.id
                WHERE r.ledger_id = ? AND r.deleted_at IS NULL AND r.type <> 'SETTLEMENT'
                GROUP BY r.id HAVING coalesce(sum(s.amount), 0) <> r.base_amount""")
                .param(family).query(Long.class).list()).as("records whose shares don't add up").isEmpty();
        assertThat(jdbc.sql("""
                SELECT r.id FROM family_record r JOIN family_share s ON s.record_id = r.id
                WHERE r.ledger_id = ? AND r.type = 'SETTLEMENT'""")
                .param(family).query(Long.class).list()).as("settlements with shares").isEmpty();
        // D-79: a refund, an expense with a minus, is negative in its amount, its paying side and every share; nothing
        // else is.
        assertThat(jdbc.sql("""
                SELECT r.id FROM family_record r LEFT JOIN family_share s ON s.record_id = r.id
                WHERE r.ledger_id = ? AND (r.base_amount = 0 OR sign(r.original_amount) <> sign(r.base_amount)
                      OR r.base_amount < 0 AND r.type <> 'EXPENSE'
                      OR sign(s.amount) = -sign(r.base_amount))""")
                .param(family).query(Long.class).list()).as("records or shares of the wrong sign (D-79)").isEmpty();
        // D-93: every row of a record's journal says its currency, and a system change has none.
        assertThat(jdbc.sql("""
                SELECT id FROM family_record_change WHERE ledger_id = ? AND (record_id IS NULL) <> (currency IS NULL)""")
                .param(family).query(Long.class).list()).as("journal rows without a currency (D-93)").isEmpty();
        assertThat(jdbc.sql("""
                SELECT l.entry_id FROM family_entry_link l JOIN posting p ON p.entry_id = l.entry_id
                WHERE l.family_ledger_id = ? GROUP BY l.entry_id, p.currency HAVING sum(p.amount) <> 0""")
                .param(family).query(Long.class).list()).as("posted entries that don't balance").isEmpty();
        assertThat(jdbc.sql("""
                SELECT l.id FROM family_entry_link l JOIN ledger_member m ON m.id = l.member_id
                WHERE l.family_ledger_id = ? AND l.detached_at IS NULL AND l.entry_id IS NULL""")
                .param(family).query(Long.class).list()).as("links without their entry").isEmpty();
        assertThat(jdbc.sql("""
                SELECT a.id FROM ledger_member m
                JOIN ledger_member p ON p.user_sub = m.user_sub AND p.ledger_type = 'PERSONAL'
                JOIN account a ON a.ledger_id = p.ledger_id
                WHERE m.ledger_id = ? AND m.status = 'LEFT' AND a.family_ledger_id = m.ledger_id""")
                .param(family).query(Long.class).list()).as("debt accounts of members who left").isEmpty();
        assertThat(jdbc.sql("""
                SELECT t.id FROM ledger_member m
                JOIN ledger_member p ON p.user_sub = m.user_sub AND p.ledger_type = 'PERSONAL'
                JOIN journal_entry e ON e.ledger_id = p.ledger_id
                JOIN posting t ON t.entry_id = e.id
                JOIN category c ON c.id = t.category_id
                WHERE m.ledger_id = ? AND m.status = 'LEFT' AND c.ledger_id = m.ledger_id""")
                .param(family).query(Long.class).list()).as("postings of members who left on a family category")
                .isEmpty();
        assertThat(jdbc.sql("""
                SELECT l.id FROM family_entry_link l JOIN ledger_member m ON m.id = l.member_id
                WHERE l.family_ledger_id = ? AND m.status <> 'ACTIVE' AND l.detached_at IS NULL""")
                .param(family).query(Long.class).list()).as("attached links of members who left or deleted their "
                        + "data").isEmpty();
        return today;
    }

    private static Map<Long, BigDecimal> checkOn(JdbcClient jdbc, long family, String currency, LocalDate day) {
        record Member(long id, String sub, String status, LocalDate joinDate, BigDecimal balance) {
        }
        List<Member> members = jdbc.sql(BALANCES).param("family", family).param("currency", currency)
                .param("day", day)
                .query((row, n) -> new Member(row.getLong("id"), row.getString("user_sub"), row.getString("status"),
                        row.getObject("join_date", LocalDate.class), row.getBigDecimal("balance")))
                .list();
        assertThat(members.stream().map(Member::balance).reduce(BigDecimal.ZERO, BigDecimal::add))
                .as("the balances of family ledger %d in %s on %s", family, currency, day)
                .isEqualByComparingTo(BigDecimal.ZERO);
        Map<Long, BigDecimal> balances = new LinkedHashMap<>();
        for (Member member : members) {
            balances.put(member.id(), member.balance());
            if (member.sub() == null || !member.status().equals("ACTIVE") || day.isBefore(member.joinDate())) {
                continue;
            }
            BigDecimal debt = jdbc.sql("""
                    SELECT coalesce(-sum(p.amount), 0)
                    FROM account a
                    JOIN ledger_member personal ON personal.ledger_id = a.ledger_id AND personal.ledger_type = 'PERSONAL'
                    JOIN posting p ON p.account_id = a.id
                    JOIN journal_entry e ON e.id = p.entry_id
                    WHERE a.family_ledger_id = :family AND personal.user_sub = :sub AND e.entry_date <= :day
                      AND p.currency = :currency""")
                    .param("family", family).param("sub", member.sub()).param("day", day).param("currency", currency)
                    .query(BigDecimal.class).single();
            assertThat(debt).as("the debt account of member %d of family ledger %d in %s on %s", member.id(), family,
                    currency, day)
                    .isEqualByComparingTo(member.balance());
        }
        return balances;
    }
}
