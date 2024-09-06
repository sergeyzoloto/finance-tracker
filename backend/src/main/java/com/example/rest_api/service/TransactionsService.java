package com.example.rest_api.service;

import com.example.rest_api.model.Transaction;

public interface TransactionsService {

  Iterable<Transaction> getAll();

  Transaction get(Integer id);

  void remove(Integer id);

  Transaction save(Transaction transaction);

}