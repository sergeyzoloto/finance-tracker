package com.example.financetracker.ledger.family;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * A family record as every member of its family ledger sees it (D-6, D-16). Only family data: members by their display
 * names, never an account, a personal category or a personal entry of anyone's (C4).
 *
 * @param type EXPENSE (F4a), INCOME or SETTLEMENT (F4d)
 * @param category the family category; null for a settlement
 * @param amount in the family's base currency, with its minor unit's decimals
 * @param payer who paid an expense, received an income, or paid in a settlement
 * @param splitMethod EQUAL, PERCENT, AMOUNT or ONE_MEMBER; null for a settlement
 * @param shares by the members' join order; none for a settlement
 * @param version what to pass back to change or delete the record
 * @param frozen whether a member it involves has left or deleted their data, so that nobody can change it (D-19)
 * @param canEdit whether the member who reads may change its family fields: its author or an owner (D-14); for a
 *        settlement, its comment: the side who recorded it, or between two members without an account its author or
 *        an owner (F4d)
 * @param canDelete whether the member who reads may delete it: its payer if they have an account, else its author or
 *        an owner (D-14); for a settlement, who may change it, unless its other side has put their part on an account
 *        of theirs ({@code lockedBy}, D-28)
 * @param canEditPayment whether the member who reads may change its date, amount and payer: its payer if they have an
 *        account, else its author or an owner (D-14; F4c, additive); for a settlement, its date and amount, by who may
 *        change it, unless it is locked as for {@code canDelete}
 * @param yourPayment how the member who reads paid it, only when they are its payer with an account, and left out for
 *        everyone else (F4c, additive); for an income, how its receiver received it; for a settlement, their own side, when they are its payer or receiver with an
 *        account (F4d)
 * @param payee who was paid in a settlement; left out for every other record (F4d, additive)
 * @param lockedBy for a settlement, the other side, who has put their part on an account of theirs, so that its date
 *        and amount don't change and it isn't deleted until they move it back to "Specify later" (D-28, additive);
 *        only in the answers of who would otherwise change it, and left out for everyone else and every other record
 */
public record FamilyRecordView(long id, String type, LocalDate date, CategoryRef category, BigDecimal amount,
        String currency, String comment, MemberRef payer, String splitMethod, List<ShareView> shares,
        MemberRef author, Instant createdAt, MemberRef updatedBy, Instant updatedAt, int version, boolean frozen,
        boolean canEdit, boolean canDelete, boolean canEditPayment,
        @JsonInclude(Include.NON_NULL) YourPayment yourPayment, @JsonInclude(Include.NON_NULL) MemberRef payee,
        @JsonInclude(Include.NON_NULL) MemberRef lockedBy) {

    /**
     * The payer's own view of their payment (D-16): their payment entry in their personal ledger, and the account they
     * paid with, or "Specify later" with both account fields null. Their private note stays in the entry. For a
     * settlement, the side of the member who reads: their entry, and the account they paid from or received into
     * (F4d).
     */
    public record YourPayment(long entryId, Long accountId, String accountName, boolean later) {
    }

    /** A member as the others see them: "Former member" once they deleted their data (D-20). */
    public record MemberRef(long memberId, String displayName) {
    }

    /** A family category. */
    public record CategoryRef(long id, String code, String name, boolean archived) {
    }

    /**
     * A member's share, with who changed it last and when (D-16).
     *
     * @param basisPoints the percentage it was split by, or null
     */
    public record ShareView(MemberRef member, BigDecimal amount, Integer basisPoints, MemberRef updatedBy,
            Instant updatedAt) {
    }
}
