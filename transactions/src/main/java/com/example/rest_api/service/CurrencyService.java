package com.example.transactions.service;

import com.example.transactions.model.Currency;

public interface CurrencyService {

  Iterable<Currency> getAll();

  Currency get(Integer id);

  void remove(Integer id);

  Currency save(Currency currency);

}