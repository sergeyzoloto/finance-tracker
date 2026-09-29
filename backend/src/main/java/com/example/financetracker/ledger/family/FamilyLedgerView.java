package com.example.financetracker.ledger.family;

import java.time.Instant;

import com.example.financetracker.ledger.access.MemberRole;

/**
 * A family ledger as its member sees it.
 *
 * @param role the member's role in it
 * @param memberId the member's own membership, as the members list names it
 */
public record FamilyLedgerView(long id, String name, String baseCurrency, SplitRule splitRule, MemberRole role,
        long memberId, Instant createdAt) {
}
