package com.example.rest_api.service;

import com.example.rest_api.model.Currency;

public interface CurrencyService {

  Iterable<Currency> getAll();

  Currency get(Integer id);

  void remove(Integer id);

  Currency save(Currency currency);

}