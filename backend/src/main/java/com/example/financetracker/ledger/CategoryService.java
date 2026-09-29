package com.example.financetracker.ledger;

import java.util.List;

import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.domain.CategoryType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.relational.core.conversion.DbActionExecutionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A ledger's categories (rule 5). Callers pass the {@link LedgerScope} that LedgerAccess resolved (rule 11).
 * Categories are archived, never deleted (rule 12), and a category's type never changes: that was decided on
 * 2026-09-25, because the type decides whether a posting reads as an expense or as an income reversal.
 */
@Service
public class CategoryService {

    private final LedgerCategoryRepository categories;

    CategoryService(LedgerCategoryRepository categories) {
        this.categories = categories;
    }

    /** All the ledger's categories, archived ones included, by name. */
    @Transactional(readOnly = true)
    public List<CategoryView> list(LedgerScope ledger) {
        return categories.findAllByLedgerIdOrderByName(ledger.ledgerId()).stream().map(CategoryView::of).toList();
    }

    /** @throws ConflictException if the ledger has a category with this code already */
    @Transactional
    public CategoryView create(LedgerScope ledger, String code, String name, CategoryType type) {
        try {
            return CategoryView.of(categories.save(
                    new LedgerCategory(null, ledger.userId(), ledger.ledgerId(), code, name, type, null)));
        } catch (DbActionExecutionException e) {
            if (e.getCause() instanceof DuplicateKeyException) {
                throw new ConflictException("You have a category with the code %s already".formatted(code));
            }
            throw e;
        }
    }

    /**
     * Renames, archives or restores a category. Fields left null stay as they are.
     *
     * @param type the category's type, if the caller sends it back; it must not differ
     * @throws NotFoundException if the ledger has no such category
     * @throws ConflictException if {@code type} differs from the category's type
     */
    @Transactional
    public CategoryView update(LedgerScope ledger, long categoryId, String name, Boolean archived, CategoryType type) {
        LedgerCategory category = categories.findByIdAndLedgerId(categoryId, ledger.ledgerId())
                .orElseThrow(() -> new NotFoundException("Category " + categoryId + " not found"));
        if (type != null && type != category.type()) {
            throw new ConflictException("The category %s is %s, and a category's type can't be changed"
                    .formatted(category.code(), category.type()));
        }
        return CategoryView.of(categories.save(new LedgerCategory(category.id(), category.userId(),
                category.ledgerId(), category.code(),
                name != null ? name : category.name(), category.type(),
                AccountService.archivedAt(category.archivedAt(), archived))));
    }
}
