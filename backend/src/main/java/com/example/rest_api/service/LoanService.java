package com.example.rest_api.service;

import com.example.rest_api.model.Loan;

public interface LoanService {

  Iterable<Loan> getAll();

  Loan get(Integer id);

  void remove(Integer id);

  Loan save(Loan loan);

}