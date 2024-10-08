package com.example.transactions.repository;

import com.example.transactions.model.BalanceSheetLine;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.List;

public interface BalanceSheetLineRepository extends JpaRepository<BalanceSheetLine, Integer> {
  List<BalanceSheetLine> findByUserId(Integer userId);
  Optional<BalanceSheetLine> findById(Integer id);
}