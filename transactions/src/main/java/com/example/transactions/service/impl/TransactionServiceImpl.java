package com.example.transactions.service.impl;

import com.example.transactions.model.Transaction;
import com.example.transactions.repository.TransactionRepository;
import com.example.transactions.service.TransactionService;
import org.springframework.stereotype.Service;

/**
 * Service implementation for transaction operations.
 */
@Service
public class TransactionServiceImpl implements TransactionService {

  private final TransactionRepository transactionRepository;

  public TransactionServiceImpl(TransactionRepository transactionRepository) {
    this.transactionRepository = transactionRepository;
  }

  /**
   * Returns all transactions.
   *
   * @return all transactions
   */
  @Override
  public Iterable<Transaction> getAll() {
    return transactionRepository.findAll();
  }

  /**
   * Returns a transaction by its id.
   *
   * @param id  the id of the transaction
   * @return    the transaction with the given id
   */
  @Override
  public Transaction get(Integer id) {
    return transactionRepository.findById(id).orElse(null);
  }

  /**
   * Removes a transaction by its id.
   *
   * @param id  the id of the transaction
   */
  @Override
  public void remove(Integer id) {
    transactionRepository.deleteById(id);
  }

  /**
   * Saves a transaction.
   *
   * @param transaction the transaction to save
   * @return the saved transaction
   */
  @Override
  public Transaction save(Transaction transaction) {
    return transactionRepository.save(transaction);
  }
}