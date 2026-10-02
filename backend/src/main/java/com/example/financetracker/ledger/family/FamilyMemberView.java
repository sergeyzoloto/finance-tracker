package com.example.financetracker.ledger.family;

import java.time.LocalDate;

import com.example.financetracker.ledger.access.MemberRole;

/**
 * A member of a family ledger as every member sees them: never a sub, an email address or a login id (D-3).
 *
 * @param hasAccount whether a user holds the membership; false for a member without an account and a FORMER one
 * @param share the member's share under a CUSTOM split rule, in basis points; null under EQUAL, and for LEFT and
 *        FORMER members
 * @param leftDate the day a LEFT member left or was removed, or a FORMER one deleted their data (D-19, D-20); null
 *        for an ACTIVE one (additive, F6a)
 * @param claimedSeat whether the member took a seat by a claim (and hasn't left and returned since): they take part in
 *        records from the family ledger's start date, and their {@code joinDate} is the claim's, from which records
 *        are posted to them (D-35; additive, F6b)
 */
public record FamilyMemberView(long id, String displayName, MemberRole role, MemberStatus status, LocalDate joinDate,
        boolean hasAccount, Integer share, LocalDate leftDate, boolean claimedSeat) {
}
