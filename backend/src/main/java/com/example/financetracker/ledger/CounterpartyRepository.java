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
public interface CounterpartyRepository extends LedgerScopedRepository<Counterparty, Long> {

    default Optional<Counterparty> find(LedgerScope ledger, long id) {
        return findByIdAndLedgerId(id, ledger.ledgerId());
    }

    /** The ledger's counterparties, ordered by name. */
    default List<Counterparty> findAll(LedgerScope ledger) {
        return findAllByLedgerIdOrderByName(ledger.ledgerId());
    }

    /** The ledger's counterparties among these ids, locked FOR SHARE ({@link #findAllByLedgerIdAndIdIn}). */
    default List<Counterparty> lockAll(LedgerScope ledger, Collection<Long> ids) {
        return findAllByLedgerIdAndIdIn(ledger.ledgerId(), ids);
    }

    Optional<Counterparty> findByIdAndLedgerId(long id, long ledgerId);

    List<Counterparty> findAllByLedgerIdOrderByName(long ledgerId);

    /**
     * Locked FOR SHARE until the transaction ends, so that none can be deleted before an entry that uses it is
     * written.
     */
    @Lock(LockMode.PESSIMISTIC_READ)
    List<Counterparty> findAllByLedgerIdAndIdIn(long ledgerId, Collection<Long> ids);
}
