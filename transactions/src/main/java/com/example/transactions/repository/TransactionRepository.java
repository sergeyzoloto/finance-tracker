package com.example.transactions.repository;

import com.example.transactions.model.Transaction;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, Integer>{
    
    Optional<Transaction> findById(Integer id);
    
}