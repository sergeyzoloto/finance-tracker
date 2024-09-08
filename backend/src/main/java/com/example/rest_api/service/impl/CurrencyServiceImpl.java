package com.example.rest_api.service.impl;

import com.example.rest_api.model.Currency;
import com.example.rest_api.repository.CurrencyRepository;
import com.example.rest_api.service.CurrencyService;
import org.springframework.stereotype.Service;

/**
 * Service implementation for currency operations.
 */
@Service
public class CurrencyServiceImpl implements CurrencyService {

  private final CurrencyRepository currencyRepository;

  public CurrencyServiceImpl(CurrencyRepository currencyRepository) {
    this.currencyRepository = currencyRepository;
  }

  /**
   * Returns all currencies.
   *
   * @return all currencies
   */
  @Override
  public Iterable<Currency> getAll() {
    return currencyRepository.findAll();
  }

  /**
   * Returns a currency by its id.
   *
   * @param id  the id of the currency
   * @return    the currency with the given id
   */
  @Override
  public Currency get(Integer id) {
    return currencyRepository.findById(id).orElse(null);
  }

  /**
   * Removes a currency by its id.
   *
   * @param id  the id of the currency
   */
  @Override
  public void remove(Integer id) {
    currencyRepository.deleteById(id);
  }

  /**
   * Saves a currency.
   *
   * @param currency the currency to save
   * @return the saved currency
   */
  @Override
  public Currency save(Currency currency) {
    return currencyRepository.save(currency);
  }
}