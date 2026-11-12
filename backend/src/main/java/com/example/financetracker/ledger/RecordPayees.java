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
     * A {@code LEFT JOIN LATERAL} that gives the entry aliased {@code e} the column {@code alias.name}, null when the
     * entry has no family record's payee the reader may see.
     */
    static String join(String alias) {
        return " " + """
                LEFT JOIN LATERAL (
                    SELECT pc.name
                    FROM family_entry_link rl
                    JOIN family_record rr ON rr.id = rl.record_id AND rr.ledger_id = rl.family_ledger_id
                    JOIN ledger_member reader ON reader.ledger_id = rr.ledger_id AND reader.user_sub = :rpReader
                         AND reader.status = 'ACTIVE'
                    JOIN ledger_member payer ON payer.ledger_id = rr.ledger_id AND payer.id = rr.payer_member_id
                    JOIN family_entry_link pl ON pl.family_ledger_id = rr.ledger_id AND pl.record_id = rr.id
                         AND pl.member_id = payer.id AND pl.link_type = 'PAYMENT' AND pl.detached_at IS NULL
                    JOIN journal_entry pe ON pe.id = pl.entry_id
                    JOIN ledger_member payers ON payers.ledger_id = pe.ledger_id AND payers.ledger_type = 'PERSONAL'
                         AND payers.user_sub = payer.user_sub
                    JOIN counterparty pc ON pc.id = pe.payee_id AND pc.ledger_id = pe.ledger_id
                    WHERE :rpOn AND rl.entry_id = e.id AND rl.detached_at IS NULL
                      AND rl.link_type IN ('SHARE', 'PAYMENT') AND rr.type IN ('EXPENSE', 'INCOME')
                      AND payer.status = 'ACTIVE' AND payer.user_sub IS NOT NULL
                      AND (payer.id = reader.id OR payer.shows_payees)
                    LIMIT 1) %s ON TRUE
                """.formatted(alias);
    }

    /** The join's parameters: the reader, and whether the family budget is switched on for this ledger. */
    static void params(Map<String, Object> params, LedgerScope ledger, boolean familyOn) {
        params.put("rpReader", ledger.userId());
        params.put("rpOn", familyOn && ledger.type() == LedgerType.PERSONAL);
    }
}
