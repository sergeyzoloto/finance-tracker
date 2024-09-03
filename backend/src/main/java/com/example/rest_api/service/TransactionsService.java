package com.example.rest_api.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

import com.example.rest_api.model.Transaction;
import com.example.rest_api.repository.TransactionsRepository;

/**
 * Service implementation for transaction operations.
 */
@Service
public class TransactionsService {

  private final TransactionsRepository transactionsRepository;

  public TransactionsService(TransactionsRepository transactionsRepository) {
    this.transactionsRepository = transactionsRepository;
  }

  /**
   * Returns all transactions.
   *
   * @return all transactions
   */
  public Iterable<Transaction> getAll() {
    return transactionsRepository.findAll();
  }

  /**
   * Returns a transaction by its id.
   *
   * @param id  the id of the transaction
   * @return    the transaction with the given id
   */
  public Transaction get(Integer id) {
    return transactionsRepository.findById(id).orElse(null);
  }

  /**
   * Removes a transaction by its id.
   *
   * @param id  the id of the transaction
   */
  public void remove(Integer id) {
    transactionsRepository.deleteById(id); 
  }

  /**
   * Saves a transaction.
   *
   * @param transaction  the transaction to save
   * @return             the saved transaction
   */
  public Transaction save(Transaction transaction) {
    transactionsRepository.save(transaction);
    return transaction;
  }

}