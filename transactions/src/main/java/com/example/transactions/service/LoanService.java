package com.example.transactions.service;

import com.example.transactions.model.Loan;

public interface LoanService {

  Iterable<Loan> getAll();

  Loan get(Integer id);

  void remove(Integer id);

  Loan save(Loan loan);

}