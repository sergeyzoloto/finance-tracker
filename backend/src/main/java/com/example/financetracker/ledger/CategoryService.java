package com.example.financetracker.ledger;

import java.util.List;
import java.util.Optional;

import com.example.financetracker.ledger.access.LedgerAccess;
import com.example.financetracker.ledger.access.LedgerScope;
import com.example.financetracker.ledger.access.LedgerType;
import com.example.financetracker.ledger.domain.CategoryType;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.relational.core.conversion.DbActionExecutionException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A ledger's categories (rule 5). Callers pass the {@link LedgerScope} that LedgerAccess resolved (rule 11).
 * Categories are archived, never deleted (rule 12), and a category's type never changes: that was decided on
 * 2026-09-25, because the type decides whether a posting reads as an expense or as an income reversal.
 * <p>
 * A personal ledger also sees the family categories of the family ledgers its member is an ACTIVE member of (D-11,
 * ADR 0003 topic F; F4a): listed with the family budget's id and name, usable in personal entries, and changed only
 * through the family budget. {@link LedgerAccess#families} decides which those are.
 */
@Service
public class CategoryService {

    private final LedgerCategoryRepository categories;
    private final JdbcClient jdbc;
    private final LedgerAccess access;

    CategoryService(LedgerCategoryRepository categories, JdbcClient jdbc, LedgerAccess access) {
        this.categories = categories;
        this.jdbc = jdbc;
        this.access = access;
    }

    /**
     * All the ledger's categories, archived ones included, by name. For a personal ledger, also the family categories
     * of its member's ACTIVE family memberships, each with its family budget.
     */
    @Transactional(readOnly = true)
    public List<CategoryView> list(LedgerScope ledger) {
        List<Long> families = families(ledger);
        if (families.isEmpty()) {
            return categories.findAll(ledger).stream().map(CategoryView::of).toList();
        }
        return jdbc.sql("""
                SELECT c.id, c.code, c.name, c.type, c.archived_at IS NOT NULL AS archived,
                       CASE WHEN c.ledger_id <> :ledgerId THEN c.ledger_id END AS family_ledger_id,
                       CASE WHEN c.ledger_id <> :ledgerId THEN l.name END AS family_ledger_name
                FROM category c JOIN ledger l ON l.id = c.ledger_id
                WHERE c.ledger_id = :ledgerId OR c.ledger_id IN (:families)
                ORDER BY c.name, c.id""")
                .param("ledgerId", ledger.ledgerId()).param("families", families)
                .query((row, n) -> new CategoryView(row.getLong("id"), row.getString("code"), row.getString("name"),
                        CategoryType.valueOf(row.getString("type")), row.getBoolean("archived"),
                        row.getObject("family_ledger_id", Long.class), row.getString("family_ledger_name")))
                .list();
    }

    /** The family ledgers whose categories a personal ledger sees: its member's ACTIVE memberships. */
    private List<Long> families(LedgerScope ledger) {
        if (ledger.type() != LedgerType.PERSONAL) {
            return List.of();
        }
        return access.families(ledger.userId()).stream().map(LedgerScope::ledgerId).toList();
    }

    /** The family category with this id or code that the personal ledger sees, with its family budget's name. */
    private Optional<CategoryView> familyCategory(LedgerScope ledger, Long id, String code) {
        List<Long> families = families(ledger);
        if (families.isEmpty()) {
            return Optional.empty();
        }
        return list(ledger).stream().filter(c -> c.familyLedgerId() != null)
                .filter(c -> id != null ? c.id() == id : c.code().equals(code))
                .findFirst();
    }

    /**
     * A new category of the ledger. In a family ledger it is a family category, a row without a user (D-11).
     *
     * @throws ConflictException if the ledger has a category with this code already, or, for a personal ledger, a
     *         family budget of its member does (ADR 0003 topic F: codes stay unambiguous in the personal list)
     */
    @Transactional
    public CategoryView create(LedgerScope ledger, String code, String name, CategoryType type) {
        Optional<CategoryView> family = familyCategory(ledger, null, code);
        if (family.isPresent()) {
            throw new ConflictException(("The family budget \"%s\" has a category with the code %s; use it, or choose "
                    + "another code").formatted(family.get().familyLedgerName(), code));
        }
        try {
            return CategoryView.of(categories.save(
                    new LedgerCategory(null, ledger.rowUserId(), ledger.ledgerId(), code, name, type, null)));
        } catch (DbActionExecutionException e) {
            if (e.getCause() instanceof DuplicateKeyException) {
                throw new ConflictException((ledger.type() == LedgerType.PERSONAL ? "You have"
                        : "The family budget has") + " a category with the code %s already".formatted(code));
            }
            throw e;
        }
    }

    /**
     * Deletes a family category that nothing uses (D-11): no family record, deleted ones included, and no posting in
     * any member's personal ledger. A personal category is archived, never deleted (rule 12), and has no endpoint for
     * this.
     *
     * @throws NotFoundException if the ledger has no such category
     * @throws ConflictException if something uses the category
     */
    @Transactional
    public void delete(LedgerScope ledger, long categoryId) {
        LedgerCategory category = categories.find(ledger, categoryId)
                .orElseThrow(() -> new NotFoundException("Category " + categoryId + " not found"));
        if (jdbc.sql("""
                SELECT EXISTS (SELECT FROM family_record WHERE category_id = :categoryId AND ledger_id = :ledgerId)
                    OR EXISTS (SELECT FROM posting WHERE category_id = :categoryId)""")
                .param("categoryId", category.id()).param("ledgerId", ledger.ledgerId())
                .query(Boolean.class).single()) {
            throw new ConflictException("The category %s is in use by family records or entries; archive it instead"
                    .formatted(category.code()));
        }
        try {
            categories.delete(category);
        } catch (DbActionExecutionException e) {
            if (e.getCause() instanceof DataIntegrityViolationException) {
                throw new ConflictException("The category %s is in use; archive it instead".formatted(category.code()));
            }
            throw e;
        }
    }

    /**
     * Renames, archives or restores a category. Fields left null stay as they are.
     *
     * @param type the category's type, if the caller sends it back; it must not differ
     * @throws NotFoundException if the ledger has no such category
     * @throws ConflictException if {@code type} differs from the category's type, or the category is a family
     *         category that a personal ledger sees: it changes in its family budget
     */
    @Transactional
    public CategoryView update(LedgerScope ledger, long categoryId, String name, Boolean archived, CategoryType type) {
        Optional<LedgerCategory> found = categories.find(ledger, categoryId);
        if (found.isEmpty()) {
            familyCategory(ledger, categoryId, null).ifPresent(family -> {
                throw new ConflictException(("The category %s belongs to the family budget \"%s\"; rename or archive it "
                        + "there").formatted(family.code(), family.familyLedgerName()));
            });
        }
        LedgerCategory category = found
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
