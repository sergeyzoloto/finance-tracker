package com.example.financetracker.ledger;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.relational.core.sql.LockMode;
import org.springframework.data.relational.repository.Lock;
import org.springframework.data.repository.CrudRepository;

/** Every lookup is scoped by user id; callers never load a category by id alone. */
public interface LedgerCategoryRepository extends CrudRepository<LedgerCategory, Long> {

    Optional<LedgerCategory> findByIdAndUserId(long id, String userId);

    List<LedgerCategory> findAllByUserIdOrderByName(String userId);

    /**
     * Locked FOR SHARE until the transaction ends, so that none can be deleted before an entry that uses it is
     * written.
     */
    @Lock(LockMode.PESSIMISTIC_READ)
    List<LedgerCategory> findAllByUserIdAndIdIn(String userId, Collection<Long> ids);
}
