package com.example.rest_api.web;

import com.example.rest_api.model.Transaction; // Import the Transaction class
import com.example.rest_api.service.TransactionsService; // Import the TransactionServer class

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class TransactionController {

  private static final String TRANSACTION_NOT_FOUND = "Transaction not found with id ";

  private final TransactionsService transactionsService;

  public TransactionController(@Autowired TransactionsService transactionsService) {
    this.transactionsService = transactionsService;
  }

  @GetMapping("/transactions")
  public Iterable<Transaction> getTransactions() {
    return transactionsService.getAll();
  }

  @GetMapping("/transactions/{id}")
  public Transaction getOne(@PathVariable Integer id) {
    Transaction transaction = transactionsService.get(id);
    if (transaction == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, 
      TRANSACTION_NOT_FOUND + id);
    }
    return transaction;
  }

  @DeleteMapping("/transactions/{id}")
  public void deleteOne(@PathVariable Integer id) {
    transactionsService.remove(id);
  }

  @PostMapping("/transactions")
  public Transaction addOne(Transaction transaction) {
    return transactionsService.save(transaction);
  }
}