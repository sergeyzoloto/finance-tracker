package com.example.rest_api.service.impl;

import com.example.rest_api.model.Transaction;
import com.example.rest_api.repository.TransactionsRepository;
import com.example.rest_api.service.TransactionsService;
import org.springframework.stereotype.Service;

/**
 * Service implementation for transaction operations.
 */
@Service
public class TransactionsServiceImpl implements TransactionsService {

  private final TransactionsRepository transactionsRepository;

  public TransactionsServiceImpl(TransactionsRepository transactionsRepository) {
    this.transactionsRepository = transactionsRepository;
  }

  /**
   * Returns all transactions.
   *
   * @return all transactions
   */
  @Override
  public Iterable<Transaction> getAll() {
    return transactionsRepository.findAll();
  }

  /**
   * Returns a transaction by its id.
   *
   * @param id  the id of the transaction
   * @return    the transaction with the given id
   */
  @Override
  public Transaction get(Integer id) {
    return transactionsRepository.findById(id).orElse(null);
  }

  /**
   * Removes a transaction by its id.
   *
   * @param id  the id of the transaction
   */
  @Override
  public void remove(Integer id) {
    transactionsRepository.deleteById(id);
  }

  /**
   * Saves a transaction.
   *
   * @param transaction the transaction to save
   * @return the saved transaction
   */
  @Override
  public Transaction save(Transaction transaction) {
    return transactionsRepository.save(transaction);
  }
}