package com.example.financetracker.ledger;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.relational.core.sql.LockMode;
import org.springframework.data.relational.repository.Lock;
import org.springframework.data.repository.CrudRepository;

/** Every lookup is scoped by user id; callers never load an account by id alone. */
public interface AccountRepository extends CrudRepository<Account, Long> {

    Optional<Account> findByIdAndUserId(long id, String userId);

    Optional<Account> findByUserIdAndCode(String userId, String code);

    List<Account> findAllByUserIdOrderByCode(String userId);

    List<Account> findAllByUserIdAndCodeIn(String userId, Collection<String> codes);

    /**
     * Locked FOR SHARE until the transaction ends, so that the accounts can't change type or start requiring a
     * counterparty between checking an entry and writing it.
     */
    @Lock(LockMode.PESSIMISTIC_READ)
    List<Account> findAllByUserIdAndIdIn(String userId, Collection<Long> ids);
}
