package com.example.financetracker.ledger.family;

import java.math.BigDecimal;
import java.util.List;

/**
 * Every member's balance in a family ledger (D-1, D1; ADR 0003, topic D), in its base currency: what the member owes
 * the family, positive, or what the family owes them, negative. They sum to zero.
 *
 * @param members by join order
 */
public record FamilyBalances(String currency, List<MemberBalance> members) {

    /** @param you whether this is the member who reads */
    public record MemberBalance(long memberId, String displayName, MemberStatus status, boolean hasAccount,
            BigDecimal balance, boolean you) {
    }
}
