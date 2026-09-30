package com.example.financetracker.ledger;

import com.example.financetracker.ledger.domain.CategoryType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * A category as {@link CategoryService} hands it out.
 *
 * @param familyLedgerId in the personal list, the family budget a family category belongs to (D-11, F4a); left out
 *        for a category of the ledger itself, so that a personal list without family categories reads as before
 * @param familyLedgerName that family budget's name, left out likewise
 */
public record CategoryView(long id, String code, String name, CategoryType type, boolean archived,
        @JsonInclude(Include.NON_NULL) Long familyLedgerId, @JsonInclude(Include.NON_NULL) String familyLedgerName) {

    static CategoryView of(LedgerCategory category) {
        return new CategoryView(category.id(), category.code(), category.name(), category.type(),
                category.archivedAt() != null, null, null);
    }
}
