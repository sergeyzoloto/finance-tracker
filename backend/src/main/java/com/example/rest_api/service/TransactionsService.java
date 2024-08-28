package com.example.rest_api.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

import com.example.rest_api.model.Transaction;
import com.example.rest_api.repository.TransactionsRepository;

@Service
public class TransactionsService {

  private final TransactionsRepository transactionsRepository;

  public TransactionsService(TransactionsRepository transactionsRepository) {
    this.transactionsRepository = transactionsRepository;
  }

  public Iterable<Transaction> getAll() {
    return transactionsRepository.findAll();
  }

  public Transaction get(Integer id) {
    return transactionsRepository.findById(id).orElse(null);
  }
  public void remove(Integer id) {
    transactionsRepository.deleteById(id); 
  }

  public Transaction save(Transaction transaction) {
    transactionsRepository.save(transaction);
    return transaction;
  }

}