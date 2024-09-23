package com.example.transactions.web;

import com.example.transactions.model.Transaction;
import com.example.transactions.service.TransactionService;
import com.example.transactions.utils.PropertyUtils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.web.server.ResponseStatusException;

/**
 * The TransactionController class handles HTTP requests related
 * to transaction operations.
 */
@RestController
public class TransactionController {

  private static final String TRANSACTION_NOT_FOUND = "Transaction not found with id ";

  private final TransactionService transactionService;

  public TransactionController(@Autowired TransactionService transactionService) {
    this.transactionService = transactionService;
  }

  /**
   * Returns all transactions.
   *
   * @return all transactions
   */
  @Transactional(readOnly = true)
  @GetMapping("/transaction")
  public Iterable<Transaction> getTransactions() {
    return transactionService.getAll();
  }

  /**
   * Returns a transaction by its id.
   *
   * @param id  the id of the transaction
   * @return    the transaction with the given id
   */
  @Transactional(readOnly = true)
  @GetMapping("/transaction/{id}")
  public Transaction getOne(@PathVariable Integer id) {
    Transaction transaction = transactionService.get(id);
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
  @Transactional
  @DeleteMapping("/transaction/{id}")
  public void deleteOne(@PathVariable Integer id) {
    transactionService.remove(id);
  }

  /**
   * Adds a new transaction.
   *
   * @param transaction  the transaction to add
   * @return             the added transaction
   */
  @Transactional
  @PostMapping("/transaction")
  public Transaction addOne(Transaction transaction) {
    return transactionService.save(transaction);
  }

  /**
   * Add a new method to update a transaction by its id.
   * The method should accept an HTTP PUT request at /transaction/{id}.
   * The method should return the updated transaction.
   * If the transaction with the given id does not exist, the method should return a 404 Not Found status.
   * The method should use the TransactionService to update the transaction.
   * 
   * The method signature should be:
   * 
   * @param id  the id of the transaction
   * @param transaction  the updated transaction
   */
  @Transactional
  @PutMapping("/transaction/{id}")
  public Transaction updateOne(@PathVariable Integer id, @RequestBody Transaction transaction) {
    Transaction existingTransaction = transactionService.get(id);
    if (existingTransaction == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, TRANSACTION_NOT_FOUND + id);
    }

    PropertyUtils.copyNonNullProperties(transaction, existingTransaction);

    return transactionService.save(existingTransaction);
  }
}