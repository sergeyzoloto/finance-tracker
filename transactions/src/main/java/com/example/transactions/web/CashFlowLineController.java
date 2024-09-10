package com.example.transactions.web;

import com.example.transactions.model.CashFlowLine;
import com.example.transactions.service.CashFlowLineService;
import com.example.transactions.utils.PropertyUtils;

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
 * The CashFlowLineController class handles HTTP requests related
 * to CashFlowLine operations.
 */
@RestController
public class CashFlowLineController {

  private static final String TRANSACTION_NOT_FOUND = "Cash Flow Line not found with id ";

  private final CashFlowLineService cashFlowLineService;

  public CashFlowLineController(@Autowired CashFlowLineService cashFlowLineService) {
    this.cashFlowLineService = cashFlowLineService;
  }

  /**
   * @return all lines from cash flow dictionary
   */
  @GetMapping("/cashflow")
  public Iterable<CashFlowLine> getCashFlowLine() {
    return cashFlowLineService.getAll();
  }

  /**
   * Returns a
   *
   * @param id  the id of the CashFlowLine
   * @return    the CashFlowLine with the given id
   */
  @GetMapping("/cashflow/{id}")
  public CashFlowLine getOne(@PathVariable Integer id) {
    CashFlowLine cashFlowLine = cashFlowLineService.get(id);
    if (cashFlowLine == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, 
      TRANSACTION_NOT_FOUND + id);
    }
    return cashFlowLine;
  }

  /**
   * Removes a cashFlowLine by its id.
   *
   * @param id  the id of the cashFlowLine
   */
  @DeleteMapping("/cashflow/{id}")
  public void deleteOne(@PathVariable Integer id) {
    cashFlowLineService.remove(id);
  }

  /**
   * Adds a new cashflow line to the dictionary.
   *
   * @param cashFlowLine  the cashFlowLine to add
   * @return             the added cashFlowLine
   */
  @PostMapping("/cashflow")
  public CashFlowLine addOne(CashFlowLine cashFlowLine) {
    return cashFlowLineService.save(cashFlowLine);
  }

  /**
   * Add a new method to update a cashflow Line by its id.
   * The method should accept an HTTP PUT request at /cashflow/{id}.
   * The method should return the updated cashflow line.
   * If the cashflow line with the given id does not exist, the method should return a 404 Not Found status.
   * The method should use the CashFlowLineService to update the cashFlowLine.
   * 
   * The method signature should be:
   * 
   * @param id  the id of the cashFlowLine
   * @param cashFlowLine  the updated cashFlowLine
   */
  @PutMapping("/cashflow/{id}")
  public CashFlowLine updateOne(@PathVariable Integer id, @RequestBody CashFlowLine cashFlowLine) {
    CashFlowLine existingCashFlowLine = cashFlowLineService.get(id);
    if (existingCashFlowLine == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, TRANSACTION_NOT_FOUND + id);
    }

    PropertyUtils.copyNonNullProperties(cashFlowLine, existingCashFlowLine);

    return cashFlowLineService.save(existingCashFlowLine);
  }
}