package com.example.financetracker.ledger;

import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

/**
 * The base of the repositories of rows that belong to a ledger (ADR 0003, topics A and C). Unlike CrudRepository it
 * has no lookups or deletes by id alone, such as findById, findAll, existsById, count or deleteById: those reach every
 * ledger's rows. Each repository declares its lookups with the ledger id, which callers take from a
 * {@link com.example.financetracker.ledger.access.LedgerScope}, and saves only what it loaded that way or builds for
 * that ledger.
 */
@NoRepositoryBean
public interface LedgerScopedRepository<T, ID> extends Repository<T, ID> {

    /** Inserts the row, or updates the row with the entity's id. */
    <S extends T> S save(S entity);
}
