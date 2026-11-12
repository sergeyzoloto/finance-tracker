package com.example.financetracker.ledger;

import java.util.List;

/**
 * A payee that family records show on the user's entries (UX-12, D-548), for Entries' "Payee or person" list and the
 * payee fields' suggestions: its name, as the newest entry showing it spells it, and the family budgets it comes from.
 *
 * @param name the payee's name
 * @param budgets the family budgets whose records show it on the user's entries, by name
 */
public record FamilyPayee(String name, List<Budget> budgets) {

    /** A family budget, by id and its current name. */
    public record Budget(long id, String name) {
    }
}
