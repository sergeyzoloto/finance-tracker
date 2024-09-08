package com.example.rest_api.web;

import com.example.rest_api.model.Currency;
import com.example.rest_api.service.CurrencyService;
import com.example.rest_api.utils.PropertyUtils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

import org.springframework.web.server.ResponseStatusException;

/**
 * The CurrencyController class handles HTTP requests related
 * to currency operations.
 */
@RestController
public class CurrencyController {

  private static final String TRANSACTION_NOT_FOUND = "Currency not found with id ";

  private final CurrencyService currencyService;

  public CurrencyController(@Autowired CurrencyService currencyService) {
    this.currencyService = currencyService;
  }

  /**
   * Returns all currencies.
   *
   * @return all currencies
   */
  @GetMapping("/currency")
  public Iterable<Currency> getCurrency() {
    return currencyService.getAll();
  }

  /**
   * Returns a currency by its id.
   *
   * @param id  the id of the currency
   * @return    the currency with the given id
   */
  @GetMapping("/currency/{id}")
  public Currency getOne(@PathVariable Integer id) {
    Currency currency = currencyService.get(id);
    if (currency == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, 
      TRANSACTION_NOT_FOUND + id);
    }
    return currency;
  }

  /**
   * Removes a currency by its id.
   *
   * @param id  the id of the currency
   */
  @DeleteMapping("/currency/{id}")
  public void deleteOne(@PathVariable Integer id) {
    currencyService.remove(id);
  }

  /**
   * Adds a new currency.
   *
   * @param currency  the currency to add
   * @return             the added currency
   */
  @PostMapping("/currency")
  public Currency addOne(Currency currency) {
    return currencyService.save(currency);
  }

  /**
   * Add a new method to update a currency by its id.
   * The method should accept an HTTP PUT request at /currency/{id}.
   * The method should return the updated currency.
   * If the currency with the given id does not exist, the method should return a 404 Not Found status.
   * The method should use the CurrencyService to update the currency.
   * 
   * The method signature should be:
   * 
   * @param id  the id of the currency
   * @param currency  the updated currency
   */
  @PutMapping("/currency/{id}")
  public Currency updateOne(@PathVariable Integer id, @RequestBody Currency currency) {
    Currency existingCurrency = currencyService.get(id);
    if (existingCurrency == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, TRANSACTION_NOT_FOUND + id);
    }

    PropertyUtils.copyNonNullProperties(currency, existingCurrency);

    return currencyService.save(existingCurrency);
  }
}