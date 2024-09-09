package com.example.transactions.repository;

import com.example.transactions.model.Loan;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.List;

public interface LoanRepository extends JpaRepository<Loan, Integer> {
  List<Loan> findByUserId(Integer userId);
  Optional<Loan> findById(Integer id);
}