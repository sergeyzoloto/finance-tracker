package com.example.transactions.service;

import com.example.transactions.model.Transaction;

public interface TransactionService {

  Iterable<Transaction> getAll();

  Transaction get(Integer id);

  void remove(Integer id);

  Transaction save(Transaction transaction);

}