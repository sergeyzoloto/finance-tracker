package com.example.transactions.repository;

import com.example.transactions.model.CashFlowLine;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.List;

public interface CashFlowLineRepository extends JpaRepository<CashFlowLine, Integer> {
  List<CashFlowLine> findByUserId(Integer userId);
  Optional<CashFlowLine> findById(Integer id);
}