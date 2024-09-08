package com.example.rest_api.web;

import com.example.rest_api.model.Transaction;
import com.example.rest_api.service.TransactionsService;
import com.example.rest_api.utils.PropertyUtils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

import org.springframework.web.server.ResponseStatusException;

/**
 * The TransactionController class handles HTTP requests related
 * to transaction operations.
 */
@RestController
public class TransactionController {

  private static final String TRANSACTION_NOT_FOUND = "Transaction not found with id ";

  private final TransactionsService transactionsService;

  public TransactionController(@Autowired TransactionsService transactionsService) {
    this.transactionsService = transactionsService;
  }

  /**
   * Returns all transactions.
   *
   * @return all transactions
   */
  @GetMapping("/transactions")
  public Iterable<Transaction> getTransactions() {
    return transactionsService.getAll();
  }

  /**
   * Returns a transaction by its id.
   *
   * @param id  the id of the transaction
   * @return    the transaction with the given id
   */
  @GetMapping("/transactions/{id}")
  public Transaction getOne(@PathVariable Integer id) {
    Transaction transaction = transactionsService.get(id);
    if (transaction == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, 
      TRANSACTION_NOT_FOUND + id);
    }
    return transaction;
  }

  /**
   * Removes a transaction by its id.
   *
   * @param id  the id of the transaction
   */
  @DeleteMapping("/transactions/{id}")
  public void deleteOne(@PathVariable Integer id) {
    transactionsService.remove(id);
  }

  /**
   * Adds a new transaction.
   *
   * @param transaction  the transaction to add
   * @return             the added transaction
   */
  @PostMapping("/transactions")
  public Transaction addOne(Transaction transaction) {
    return transactionsService.save(transaction);
  }

  /**
   * Add a new method to update a transaction by its id.
   * The method should accept an HTTP PUT request at /transactions/{id}.
   * The method should return the updated transaction.
   * If the transaction with the given id does not exist, the method should return a 404 Not Found status.
   * The method should use the TransactionsService to update the transaction.
   * 
   * The method signature should be:
   * 
   * @param id  the id of the transaction
   * @param transaction  the updated transaction
   */
  @PutMapping("/transactions/{id}")
  public Transaction updateOne(@PathVariable Integer id, @RequestBody Transaction transaction) {
    Transaction existingTransaction = transactionsService.get(id);
    if (existingTransaction == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, TRANSACTION_NOT_FOUND + id);
    }

    PropertyUtils.copyNonNullProperties(transaction, existingTransaction);

    return transactionsService.save(existingTransaction);
  }
}