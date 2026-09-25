package com.example.financetracker.ledger;

/**
 * A counterparty as {@link CounterpartyService} hands it out.
 *
 * @param kind null until classified
 * @param lastCategoryId the category of the most recent entry with this payee that has one, to preselect in the next
 *        entry with it; null if there is none
 */
public record CounterpartyView(long id, String name, Counterparty.Kind kind, boolean archived, Long lastCategoryId) {
}
