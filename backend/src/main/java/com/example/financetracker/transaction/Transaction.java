package com.example.financetracker.transaction;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/** Amounts are always positive; whether they add or subtract follows from the category type. */
@Table("transactions")
public record Transaction(@Id Long id, @JsonIgnore Long userId, Long categoryId, BigDecimal amount,
        LocalDate occurredOn, String note) {
}
