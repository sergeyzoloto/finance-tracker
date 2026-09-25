package com.example.financetracker.ledger;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.relational.core.sql.LockMode;
import org.springframework.data.relational.repository.Lock;
import org.springframework.data.repository.CrudRepository;

/** Every lookup is scoped by user id; callers never load a counterparty by id alone. */
public interface CounterpartyRepository extends CrudRepository<Counterparty, Long> {

    Optional<Counterparty> findByIdAndUserId(long id, String userId);

    List<Counterparty> findAllByUserIdOrderByName(String userId);

    /**
     * Locked FOR SHARE until the transaction ends, so that none can be deleted before an entry that uses it is
     * written.
     */
    @Lock(LockMode.PESSIMISTIC_READ)
    List<Counterparty> findAllByUserIdAndIdIn(String userId, Collection<Long> ids);
}
