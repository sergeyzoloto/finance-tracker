package com.example.rest_api.web;

import com.example.rest_api.model.Transaction; // Import the Transaction class

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.util.Collection;

@RestController
public class TransactionController {

  private static final String TRANSACTION_NOT_FOUND = "Transaction not found with id ";

  private Map<String, Transaction> db;

  public TransactionController() {
    db = new HashMap<>();
    db.put("1", new Transaction("1"));
    db.put("2", new Transaction("2"));
  }

  @GetMapping("/transactions")
  public Collection<Transaction> getTransactions() {
    return db.values();
  }

  @GetMapping("/transactions/{id}")
  public Transaction getTransaction(@PathVariable String id) {
    Transaction transaction = db.get(id);
    if (transaction == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, 
      TRANSACTION_NOT_FOUND + id);
    }
    return transaction;
  }

  @DeleteMapping("/transactions/{id}")
  public void deleteTransaction(@PathVariable String id) {
    Transaction transaction = db.remove(id);
    if (transaction == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, 
      TRANSACTION_NOT_FOUND + id);
    }
  }

  @PostMapping("/transactions")
  public Transaction addTransaction(Transaction transaction) {
    transaction.setId(UUID.randomUUID().toString());
    db.put(transaction.getId(), transaction);
    return transaction;
  }

  @PutMapping("/transactions/{id}")
  public Transaction updateTransaction(@PathVariable String id, Transaction transaction) {
    if (!db.containsKey(id)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, 
      TRANSACTION_NOT_FOUND + id);
    }
    transaction.setId(id);
    db.put(id, transaction);
    return transaction;
  }
}