package com.example.financetracker.ledger;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.relational.core.sql.LockMode;
import org.springframework.data.relational.repository.Lock;

/** Every lookup is scoped by user id; there is none by id alone ({@link OwnedRepository}). */
public interface AccountRepository extends OwnedRepository<Account, Long> {

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
