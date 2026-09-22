package com.example.financetracker.category;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("categories")
public record Category(@Id Long id, @JsonIgnore Long userId, String name, Type type) {

    public enum Type { INCOME, EXPENSE }
}
