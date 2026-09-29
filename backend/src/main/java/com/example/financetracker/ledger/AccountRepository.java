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
public interface AccountRepository extends LedgerScopedRepository<Account, Long> {

    default Optional<Account> find(LedgerScope ledger, long id) {
        return findByIdAndLedgerId(id, ledger.ledgerId());
    }

    default Optional<Account> findByCode(LedgerScope ledger, String code) {
        return findByLedgerIdAndCode(ledger.ledgerId(), code);
    }

    /** The ledger's accounts, ordered by code. */
    default List<Account> findAll(LedgerScope ledger) {
        return findAllByLedgerIdOrderByCode(ledger.ledgerId());
    }

    default List<Account> findAllByCode(LedgerScope ledger, Collection<String> codes) {
        return findAllByLedgerIdAndCodeIn(ledger.ledgerId(), codes);
    }

    /** The ledger's accounts among these ids, locked FOR SHARE ({@link #findAllByLedgerIdAndIdIn}). */
    default List<Account> lockAll(LedgerScope ledger, Collection<Long> ids) {
        return findAllByLedgerIdAndIdIn(ledger.ledgerId(), ids);
    }

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
