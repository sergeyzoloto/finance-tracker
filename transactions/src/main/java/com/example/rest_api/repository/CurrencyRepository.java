package com.example.transactions.repository;

import com.example.transactions.model.Currency;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.List;

public interface CurrencyRepository extends JpaRepository<Currency, Integer> {
  List<Currency> findByUserId(Integer userId);
  Optional<Currency> findById(Integer id);
}