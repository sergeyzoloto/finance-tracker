package com.example.financetracker.ledger.family;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A family record as every member of its family ledger sees it (D-6, D-16). Only family data: members by their display
 * names, never an account, a personal category or a personal entry of anyone's (C4).
 *
 * @param type EXPENSE (F4a)
 * @param amount in the family's base currency, with its minor unit's decimals
 * @param splitMethod EQUAL, PERCENT, AMOUNT or ONE_MEMBER
 * @param shares by the members' join order
 * @param version what to pass back to change or delete the record
 * @param frozen whether a member it involves has left or deleted their data, so that nobody can change it (D-19)
 * @param canEdit whether the member who reads may change its family fields: its author or an owner (D-14)
 * @param canDelete whether the member who reads may delete it: its payer if they have an account, else its author or
 *        an owner (D-14)
 */
public record FamilyRecordView(long id, String type, LocalDate date, CategoryRef category, BigDecimal amount,
        String currency, String comment, MemberRef payer, String splitMethod, List<ShareView> shares,
        MemberRef author, Instant createdAt, MemberRef updatedBy, Instant updatedAt, int version, boolean frozen,
        boolean canEdit, boolean canDelete) {

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
