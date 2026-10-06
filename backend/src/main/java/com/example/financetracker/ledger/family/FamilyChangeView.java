package com.example.financetracker.ledger.family;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.example.financetracker.ledger.family.FamilyRecordView.MemberRef;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One entry of a family ledger's change journal (D-16; ADR 0003, topic H), with members and categories named as they
 * are when it is read, so that a FORMER member reads "Former member" everywhere (D-20).
 *
 * @param action CREATE, UPDATE or DELETE of a record, or SPLIT_RULE_RESET, a system change
 * @param recordId the record it is about; null for a system change
 * @param author who made it; null for a system change
 * @param about the member a system change is about, such as the one whose leaving reset the split rule
 * @param record the record it is about as it is now, deleted or not, so that a change reads "… of Groceries, 12 Sep"
 *        wherever the record itself is (F4b); null for a system change
 */
public record FamilyChangeView(long id, Instant at, String action, Long recordId, MemberRef author, MemberRef about,
        List<Change> changes, RecordSummary record) {

    /**
     * A record as the journal names it: only family data, as in {@link FamilyRecordView}.
     *
     * @param category the family category's name; null for a record without one, such as a settlement
     * @param amount the record's amount in its own currency, with its minor unit's decimals
     * @param deleted whether the record is deleted, which only the journal still shows
     * @param type EXPENSE or SETTLEMENT (F4d, additive)
     * @param currency the record's currency, which {@code amount} is in (D-45; F8b, additive)
     */
    public record RecordSummary(LocalDate date, String category, BigDecimal amount, boolean deleted, String type,
            String currency) {
    }

    /**
     * A field's old and new value, as text: a date, an amount in the base currency, a category's or member's name, a
     * split method or rule, a comment. Null where there was none, or where a FORMER member's comment was erased.
     *
     * @param field date, category, amount, payer, payee (a settlement's receiver, F4d), splitMethod, share, comment
     *        or splitRule
     * @param member the member a share is of; null for other fields
     * @param oldValue sent as "old"
     * @param newValue sent as "new"
     */
    public record Change(String field, MemberRef member, @JsonProperty("old") String oldValue,
            @JsonProperty("new") String newValue) {
    }
}
