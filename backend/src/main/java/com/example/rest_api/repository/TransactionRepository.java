package com.example.rest_api.repository;

import com.example.rest_api.model.Transaction;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, Integer>{
    
    Optional<Transaction> findById(Integer id);
    
}