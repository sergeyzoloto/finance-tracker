package com.example.rest_api.web;

import com.example.rest_api.model.Transaction; // Import the Transaction class
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
public class TransactionController {

  private List<Transaction> db = List.of(
    new Transaction("1"), 
    new Transaction("2")
  );

  @GetMapping("/transactions")
  public List<Transaction> getTransactions() {
    return db;
  }

  @GetMapping("/transactions/{id}")
  public Transaction getTransaction(@PathVariable String id) {
    return db.stream()
      .filter(transaction -> transaction.getId().equals(id))
      .findFirst()
      .orElse(null);
  }
}