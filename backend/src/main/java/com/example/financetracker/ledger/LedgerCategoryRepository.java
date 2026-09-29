package com.example.financetracker.ledger;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.relational.core.sql.LockMode;
import org.springframework.data.relational.repository.Lock;

/** Every lookup is scoped by ledger id; there is none by id alone ({@link LedgerScopedRepository}). */
public interface LedgerCategoryRepository extends LedgerScopedRepository<LedgerCategory, Long> {

    Optional<LedgerCategory> findByIdAndLedgerId(long id, long ledgerId);

    List<LedgerCategory> findAllByLedgerIdOrderByName(long ledgerId);

    /**
     * Locked FOR SHARE until the transaction ends, so that none can be deleted before an entry that uses it is
     * written.
     */
    @Lock(LockMode.PESSIMISTIC_READ)
    List<LedgerCategory> findAllByLedgerIdAndIdIn(long ledgerId, Collection<Long> ids);
}
