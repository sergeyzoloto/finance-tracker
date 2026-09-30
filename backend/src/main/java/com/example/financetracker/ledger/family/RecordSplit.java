package com.example.financetracker.ledger.family;

import java.math.BigDecimal;
import java.util.List;

/**
 * How a family record's amount is to be split (D-12): by the family ledger's default rule, by percentages, by amounts,
 * or entirely on one member.
 *
 * @param shares for PERCENT (basis points) and AMOUNT (amounts); empty otherwise
 * @param memberId for ONE_MEMBER; null otherwise
 */
public record RecordSplit(Method method, List<ShareInput> shares, Long memberId) {

    public RecordSplit {
        shares = shares == null ? List.of() : List.copyOf(shares);
    }

    /** The family ledger's default rule: equal shares of the members, or its custom percentages. */
    public static RecordSplit rule() {
        return new RecordSplit(Method.RULE, List.of(), null);
    }

    public enum Method {
        RULE, PERCENT, AMOUNT, ONE_MEMBER
    }

    /** A member's share as entered: basis points out of 10000 for PERCENT, an amount for AMOUNT. */
    public record ShareInput(long memberId, Integer basisPoints, BigDecimal amount) {
    }
}
