package com.example.rest_api.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import com.example.rest_api.model.Transaction;

@Service
public class TransactionsService {

  private Map<String, Transaction> db = new HashMap<>();
  
  public TransactionsService() {
    db.put("1", new Transaction("1"));
    db.put("2", new Transaction("2"));
  }

  public Collection<Transaction> getAll() {
    return db.values();
  }

  public Transaction get(String id) {
    return db.get(id);
  }

  public Transaction save(Transaction transaction) {
    db.put(transaction.getId(), transaction);
    return transaction;
  }

  public Transaction remove(String id) {
    return db.remove(id);
  }
}