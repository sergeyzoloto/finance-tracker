package com.example.financetracker.transaction;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

/** Every lookup is scoped by user id; callers never load a transaction by id alone. */
interface TransactionRepository extends CrudRepository<Transaction, Long> {

    /** Null filters are ignored; the date range is inclusive on both ends. */
    @Query("""
            SELECT * FROM transactions
            WHERE user_id = :userId
              AND (CAST(:from AS DATE) IS NULL OR occurred_on >= :from)
              AND (CAST(:to AS DATE) IS NULL OR occurred_on <= :to)
              AND (CAST(:categoryId AS BIGINT) IS NULL OR category_id = :categoryId)
            ORDER BY occurred_on DESC, id DESC""")
    List<Transaction> search(long userId, LocalDate from, LocalDate to, Long categoryId);

    Optional<Transaction> findByIdAndUserId(long id, long userId);

    @Modifying
    @Query("DELETE FROM transactions WHERE id = :id AND user_id = :userId")
    boolean deleteByIdAndUserId(long id, long userId);
}
