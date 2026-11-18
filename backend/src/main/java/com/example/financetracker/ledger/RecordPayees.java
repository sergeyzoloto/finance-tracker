package com.example.financetracker.ledger;

import java.util.Map;

import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.access.LedgerType;

/**
 * UX-11, D-523: the payee of an entry's family record, as one SQL rule that the entry view, the entry list's search and
 * its summary all join, so that the three can't disagree. It is the name of the payee on the payer's own payment entry
 * of the record the entry is linked to, exactly when the reader may see that payee (D-479 to D-482, D-486), the rule
 * that {@code FamilyPostingService.shownPayees} follows: the reader is an ACTIVE member of the family budget, and is
 * the payer, or the payer is an ACTIVE member with an account who shows their payees there. Only for EXPENSE and INCOME
 * records (refunds included), never for a settlement, an opening balance or a correction; never with the family switch
 * off. Read when asked, never stored (rule 13).
 *
 * <p>Only fixed text goes into the SQL; the reader and the switch are parameters ({@link #params}).
 */
final class RecordPayees {

    private RecordPayees() {
    }

    /**
     * The rule's tables, the same for both joins. PERF-1 (D-972, D-973): a join condition that a composite foreign key
     * already guarantees is left out, so that the planner doesn't multiply its selectivity into the row estimate of the
     * chain; each one is named here, and every one holds for any data the database accepts, not for today's:
     * <ul>
     * <li>{@code rr.ledger_id = rl.family_ledger_id}, and {@code pl.family_ledger_id = rr.ledger_id}: the foreign key
     * {@code family_entry_link_family_ledger_id_record_id_fkey} (family_ledger_id, record_id) to
     * {@code family_record (ledger_id, id)} (both columns of the link are NOT NULL or the join on record_id drops it);</li>
     * <li>{@code payer.ledger_id = rr.ledger_id}: {@code family_record_ledger_id_payer_member_id_fkey} (ledger_id,
     * payer_member_id) to {@code ledger_member (ledger_id, id)}, both NOT NULL;</li>
     * <li>{@code pc.ledger_id = pe.ledger_id}: {@code journal_entry_ledger_id_payee_id_fkey} (ledger_id, payee_id) to
     * {@code counterparty (ledger_id, id)}, the join on payee_id dropping an entry without a payee.</li>
     * </ul>
     */
    private static final String RULE_FROM = """
            FROM family_entry_link rl
            JOIN family_record rr ON rr.id = rl.record_id
            JOIN ledger_member reader ON reader.ledger_id = rr.ledger_id AND reader.user_sub = :rpReader
                 AND reader.status = 'ACTIVE'
            JOIN ledger_member payer ON payer.id = rr.payer_member_id
            JOIN family_entry_link pl ON pl.record_id = rr.id
                 AND pl.member_id = payer.id AND pl.link_type = 'PAYMENT' AND pl.detached_at IS NULL
            JOIN journal_entry pe ON pe.id = pl.entry_id
            JOIN ledger_member payers ON payers.ledger_id = pe.ledger_id AND payers.ledger_type = 'PERSONAL'
                 AND payers.user_sub = payer.user_sub
            JOIN counterparty pc ON pc.id = pe.payee_id""";

    /** The rule's conditions on a link: live, of an expense or an income, to a payer the reader may see. */
    private static final String RULE_WHERE = """
            :rpOn AND rl.detached_at IS NULL
              AND rl.link_type IN ('SHARE', 'PAYMENT') AND rr.type IN ('EXPENSE', 'INCOME')
              AND payer.status = 'ACTIVE' AND payer.user_sub IS NOT NULL
              AND (payer.id = reader.id OR payer.shows_payees)""";

    /**
     * A {@code LEFT JOIN LATERAL} that gives the entry aliased {@code e} the column {@code alias.name}, null when the
     * entry has no family record's payee the reader may see, and {@code alias.family_ledger_id}, that record's family
     * budget (UX-12, D-548). Evaluated per entry: for a few entries, such as a page's.
     */
    static String join(String alias) {
        return " LEFT JOIN LATERAL (SELECT pc.name, rr.ledger_id AS family_ledger_id " + RULE_FROM
                + " WHERE " + RULE_WHERE + " AND rl.entry_id = e.id LIMIT 1) " + alias + " ON TRUE ";
    }

    /**
     * The same rule, worked out once for the ledger's entries and joined by entry id (ENT-3b, D-897): the columns of
     * {@link #join}, for the entries of the ledger {@code :ledgerId} (a parameter). An entry has at most one family link
     * ({@code family_entry_link.entry_id} is unique, V7), so it has at most one row. For the entry list, summary,
     * breakdown and the like, which would otherwise evaluate the rule for every matching entry.
     */
    static String joinSet(String alias) {
        return " LEFT JOIN (SELECT rl.entry_id, pc.name, rr.ledger_id AS family_ledger_id " + RULE_FROM
                + " JOIN journal_entry re ON re.id = rl.entry_id AND re.ledger_id = :ledgerId"
                + " WHERE " + RULE_WHERE + ") " + alias + " ON " + alias + ".entry_id = e.id ";
    }

    /** The join's parameters: the reader, and whether the family budget is switched on for this ledger. */
    static void params(Map<String, Object> params, LedgerScope ledger, boolean familyOn) {
        params.put("rpReader", ledger.userId());
        params.put("rpOn", familyOn && ledger.type() == LedgerType.PERSONAL);
    }
}
