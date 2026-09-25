package com.example.financetracker.ledger;

import com.example.financetracker.ledger.domain.CategoryType;

/** A category as {@link CategoryService} hands it out. */
public record CategoryView(long id, String code, String name, CategoryType type, boolean archived) {

    static CategoryView of(LedgerCategory category) {
        return new CategoryView(category.id(), category.code(), category.name(), category.type(),
                category.archivedAt() != null);
    }
}
