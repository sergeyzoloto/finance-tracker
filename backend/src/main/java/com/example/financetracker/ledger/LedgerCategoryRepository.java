package com.example.financetracker.ledger;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.example.financetracker.ledger.access.LedgerScope;
import org.springframework.data.relational.core.sql.LockMode;
import org.springframework.data.relational.repository.Lock;

/**
 * Every lookup is scoped by ledger id; there is none by id alone ({@link LedgerScopedRepository}). Callers use the
 * default methods, which take a {@link LedgerScope}; the derived queries with a raw ledger id are called only from
 * them (ADR 0003, topic C; {@code ArchitectureTests}).
 */
public interface LedgerCategoryRepository extends LedgerScopedRepository<LedgerCategory, Long> {

    default Optional<LedgerCategory> find(LedgerScope ledger, long id) {
        return findByIdAndLedgerId(id, ledger.ledgerId());
    }

    /** The ledger's categories, ordered by name. */
    default List<LedgerCategory> findAll(LedgerScope ledger) {
        return findAllByLedgerIdOrderByName(ledger.ledgerId());
    }

    /** The ledger's categories among these ids, locked FOR SHARE ({@link #findAllByLedgerIdAndIdIn}). */
    default List<LedgerCategory> lockAll(LedgerScope ledger, Collection<Long> ids) {
        return findAllByLedgerIdAndIdIn(ledger.ledgerId(), ids);
    }

    Optional<LedgerCategory> findByIdAndLedgerId(long id, long ledgerId);

    List<LedgerCategory> findAllByLedgerIdOrderByName(long ledgerId);

    /**
     * Locked FOR SHARE until the transaction ends, so that none can be deleted before an entry that uses it is
     * written.
     */
    @Lock(LockMode.PESSIMISTIC_READ)
    List<LedgerCategory> findAllByLedgerIdAndIdIn(long ledgerId, Collection<Long> ids);

    /** Deletes a family category that {@link #find} loaded (CategoryService.delete). */
    void delete(LedgerCategory category);
}
