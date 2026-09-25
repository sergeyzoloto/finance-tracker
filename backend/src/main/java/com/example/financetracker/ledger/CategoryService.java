package com.example.financetracker.ledger;

import java.util.List;

import com.example.financetracker.ledger.domain.CategoryType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.relational.core.conversion.DbActionExecutionException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The user's categories (rule 5). Callers pass the user id, the Keycloak "sub" claim (rule 11). Categories are
 * archived, never deleted (rule 12), and a category's type never changes: that was decided on 2026-09-25, because the
 * type decides whether a posting reads as an expense or as an income reversal.
 */
@Service
public class CategoryService {

    private final LedgerCategoryRepository categories;

    CategoryService(LedgerCategoryRepository categories) {
        this.categories = categories;
    }

    /** All the user's categories, archived ones included, by name. */
    @Transactional(readOnly = true)
    public List<CategoryView> list(String userId) {
        return categories.findAllByUserIdOrderByName(userId).stream().map(CategoryView::of).toList();
    }

    /** @throws ConflictException if the user has a category with this code already */
    @Transactional
    public CategoryView create(String userId, String code, String name, CategoryType type) {
        try {
            return CategoryView.of(categories.save(new LedgerCategory(null, userId, code, name, type, null)));
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
     * @throws NotFoundException if the user has no such category
     * @throws ConflictException if {@code type} differs from the category's type
     */
    @Transactional
    public CategoryView update(String userId, long categoryId, String name, Boolean archived, CategoryType type) {
        LedgerCategory category = categories.findByIdAndUserId(categoryId, userId)
                .orElseThrow(() -> new NotFoundException("Category " + categoryId + " not found"));
        if (type != null && type != category.type()) {
            throw new ConflictException("The category %s is %s, and a category's type can't be changed"
                    .formatted(category.code(), category.type()));
        }
        return CategoryView.of(categories.save(new LedgerCategory(category.id(), userId, category.code(),
                name != null ? name : category.name(), category.type(),
                AccountService.archivedAt(category.archivedAt(), archived))));
    }
}
