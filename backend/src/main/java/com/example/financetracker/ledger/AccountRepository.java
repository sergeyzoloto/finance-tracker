package com.example.financetracker.ledger;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.relational.core.sql.LockMode;
import org.springframework.data.relational.repository.Lock;

/** Every lookup is scoped by ledger id; there is none by id alone ({@link LedgerScopedRepository}). */
public interface AccountRepository extends LedgerScopedRepository<Account, Long> {

    Optional<Account> findByIdAndLedgerId(long id, long ledgerId);

    Optional<Account> findByLedgerIdAndCode(long ledgerId, String code);

    List<Account> findAllByLedgerIdOrderByCode(long ledgerId);

    List<Account> findAllByLedgerIdAndCodeIn(long ledgerId, Collection<String> codes);

    /**
     * Locked FOR SHARE until the transaction ends, so that the accounts can't change type or start requiring a
     * counterparty between checking an entry and writing it.
     */
    @Lock(LockMode.PESSIMISTIC_READ)
    List<Account> findAllByLedgerIdAndIdIn(long ledgerId, Collection<Long> ids);
}
