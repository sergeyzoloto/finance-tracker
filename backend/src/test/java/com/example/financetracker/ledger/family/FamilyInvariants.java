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
 * <li>the members' family balances add up to zero (D-1);
 * <li>for every ACTIVE member with an account, the displayed balance of their debt account for the family ledger
 * equals their family balance, today and on each record's date (D-10);
 * <li>every posted entry balances in each currency, and every expense or income that isn't deleted has shares that
 * add up to its amount, and a settlement has none;
 * <li>the debt accounts hold postings in the family's base currency only (F4e).
 * </ul>
 */
public final class FamilyInvariants {

    /**
     * A member's family balance, of records dated by then (ADR 0003, topic D): their expense shares − the expenses they
     * paid + the incomes they received − their income shares − the settlements they paid + the settlements they
     * received.
     */
    private static final String BALANCES = """
            SELECT m.id, m.user_sub, m.status,
                   coalesce((SELECT sum(s.amount) FROM family_share s JOIN family_record r ON r.id = s.record_id
                             WHERE s.member_id = m.id AND r.deleted_at IS NULL AND r.type = 'EXPENSE'
                               AND r.record_date <= :day), 0)
                   - coalesce((SELECT sum(r.base_amount) FROM family_record r
                               WHERE r.payer_member_id = m.id AND r.deleted_at IS NULL AND r.type = 'EXPENSE'
                                 AND r.record_date <= :day), 0)
                   + coalesce((SELECT sum(r.base_amount) FROM family_record r
                               WHERE r.payer_member_id = m.id AND r.deleted_at IS NULL AND r.type = 'INCOME'
                                 AND r.record_date <= :day), 0)
                   - coalesce((SELECT sum(s.amount) FROM family_share s JOIN family_record r ON r.id = s.record_id
                               WHERE s.member_id = m.id AND r.deleted_at IS NULL AND r.type = 'INCOME'
                                 AND r.record_date <= :day), 0)
                   - coalesce((SELECT sum(r.base_amount) FROM family_record r
                               WHERE r.payer_member_id = m.id AND r.deleted_at IS NULL AND r.type = 'SETTLEMENT'
                                 AND r.record_date <= :day), 0)
                   + coalesce((SELECT sum(r.base_amount) FROM family_record r
                               WHERE r.payee_member_id = m.id AND r.deleted_at IS NULL AND r.type = 'SETTLEMENT'
                                 AND r.record_date <= :day), 0) AS balance
            FROM ledger_member m WHERE m.ledger_id = :family ORDER BY m.join_date, m.id""";

    private FamilyInvariants() {
    }

    /**
     * Checks every invariant of the family ledgers.
     *
     * @return each member's family balance today, by member id, of the last ledger
     */
    public static Map<Long, BigDecimal> check(JdbcClient jdbc, long... families) {
        Map<Long, BigDecimal> today = Map.of();
        for (long family : families) {
            List<LocalDate> days = new ArrayList<>(jdbc.sql("""
                    SELECT DISTINCT record_date FROM family_record WHERE ledger_id = ? ORDER BY record_date""")
                    .param(family).query(LocalDate.class).list());
            days.add(LocalDate.of(9999, 12, 31));
            for (LocalDate day : days) {
                today = checkOn(jdbc, family, day);
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
            assertThat(jdbc.sql("""
                    SELECT l.entry_id FROM family_entry_link l JOIN posting p ON p.entry_id = l.entry_id
                    WHERE l.family_ledger_id = ? GROUP BY l.entry_id, p.currency HAVING sum(p.amount) <> 0""")
                    .param(family).query(Long.class).list()).as("posted entries that don't balance").isEmpty();
            assertThat(jdbc.sql("""
                    SELECT l.id FROM family_entry_link l JOIN ledger_member m ON m.id = l.member_id
                    WHERE l.family_ledger_id = ? AND l.detached_at IS NULL AND l.entry_id IS NULL""")
                    .param(family).query(Long.class).list()).as("links without their entry").isEmpty();
            assertThat(jdbc.sql("""
                    SELECT p.id FROM account a JOIN posting p ON p.account_id = a.id JOIN ledger l ON l.id = ?
                    WHERE a.family_ledger_id = l.id AND p.currency <> l.base_currency""")
                    .param(family).query(Long.class).list()).as("postings to a debt account in another currency than "
                            + "the base currency").isEmpty();
        }
        return today;
    }

    private static Map<Long, BigDecimal> checkOn(JdbcClient jdbc, long family, LocalDate day) {
        record Member(long id, String sub, String status, BigDecimal balance) {
        }
        List<Member> members = jdbc.sql(BALANCES).param("family", family).param("day", day)
                .query((row, n) -> new Member(row.getLong("id"), row.getString("user_sub"), row.getString("status"),
                        row.getBigDecimal("balance")))
                .list();
        assertThat(members.stream().map(Member::balance).reduce(BigDecimal.ZERO, BigDecimal::add))
                .as("the balances of family ledger %d on %s", family, day).isEqualByComparingTo(BigDecimal.ZERO);
        Map<Long, BigDecimal> balances = new LinkedHashMap<>();
        for (Member member : members) {
            balances.put(member.id(), member.balance());
            if (member.sub() == null || !member.status().equals("ACTIVE")) {
                continue;
            }
            BigDecimal debt = jdbc.sql("""
                    SELECT coalesce(-sum(p.amount), 0)
                    FROM account a
                    JOIN ledger_member personal ON personal.ledger_id = a.ledger_id AND personal.ledger_type = 'PERSONAL'
                    JOIN posting p ON p.account_id = a.id
                    JOIN journal_entry e ON e.id = p.entry_id
                    WHERE a.family_ledger_id = :family AND personal.user_sub = :sub AND e.entry_date <= :day""")
                    .param("family", family).param("sub", member.sub()).param("day", day)
                    .query(BigDecimal.class).single();
            assertThat(debt).as("the debt account of member %d of family ledger %d on %s", member.id(), family, day)
                    .isEqualByComparingTo(member.balance());
        }
        return balances;
    }
}
