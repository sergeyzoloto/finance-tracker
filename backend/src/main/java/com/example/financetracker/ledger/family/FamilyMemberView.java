package com.example.financetracker.ledger.family;

import java.time.LocalDate;

import com.example.financetracker.ledger.access.MemberRole;

/**
 * A member of a family ledger as every member sees them: never a sub, an email address or a login id (D-3).
 *
 * @param hasAccount whether a user holds the membership; false for a member without an account and a FORMER one
 * @param share the member's share under a CUSTOM split rule, in basis points; null under EQUAL, and for LEFT and
 *        FORMER members
 */
public record FamilyMemberView(long id, String displayName, MemberRole role, MemberStatus status, LocalDate joinDate,
        boolean hasAccount, Integer share) {
}
