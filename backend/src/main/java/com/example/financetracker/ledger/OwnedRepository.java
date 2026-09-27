package com.example.financetracker.ledger;

import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

/**
 * The base of the repositories of rows that a user owns (rule 11). Unlike CrudRepository it has no lookups or deletes
 * by id alone, such as findById, findAll, existsById, count or deleteById: those reach every user's rows. Each
 * repository declares its lookups with the user id, and saves only what it loaded that way or builds for the user.
 */
@NoRepositoryBean
public interface OwnedRepository<T, ID> extends Repository<T, ID> {

    /** Inserts the row, or updates the row with the entity's id. */
    <S extends T> S save(S entity);
}
