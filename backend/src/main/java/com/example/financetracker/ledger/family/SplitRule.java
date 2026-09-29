package com.example.financetracker.ledger.family;

/**
 * A family ledger's default split of new records (D-12; ADR 0003, topic B): equal shares that follow the members, or
 * a custom share per member in basis points, which sum to 10000 over the members that are not LEFT or FORMER.
 */
public enum SplitRule {
    EQUAL, CUSTOM
}
