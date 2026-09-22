package com.example.financetracker.category;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

/** Every lookup is scoped by user id; callers never load a category by id alone. */
public interface CategoryRepository extends CrudRepository<Category, Long> {

    List<Category> findAllByUserIdOrderByName(long userId);

    Optional<Category> findByIdAndUserId(long id, long userId);

    boolean existsByIdAndUserId(long id, long userId);

    @Modifying
    @Query("DELETE FROM categories WHERE id = :id AND user_id = :userId")
    boolean deleteByIdAndUserId(long id, long userId);
}
