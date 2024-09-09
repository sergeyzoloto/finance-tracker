package com.example.transactions.web;

import com.example.transactions.model.BalanceSheetLine;
import com.example.transactions.service.BalanceSheetLineService;
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
 * The BalanceSheetLineController class handles HTTP requests related
 * to BalanceSheetLine operations.
 */
@RestController
public class BalanceSheetLineController {

  private static final String TRANSACTION_NOT_FOUND = "Cash Flow Line not found with id ";

  private final BalanceSheetLineService balanceSheetLineService;

  public BalanceSheetLineController(@Autowired BalanceSheetLineService balanceSheetLineService) {
    this.balanceSheetLineService = balanceSheetLineService;
  }

  /**
   * @return all lines from cash flow dictionary
   */
  @GetMapping("/balancesheet")
  public Iterable<BalanceSheetLine> getBalanceSheetLine() {
    return balanceSheetLineService.getAll();
  }

  /**
   * Returns a
   *
   * @param id  the id of the BalanceSheetLine
   * @return    the BalanceSheetLine with the given id
   */
  @GetMapping("/balancesheet/{id}")
  public BalanceSheetLine getOne(@PathVariable Integer id) {
    BalanceSheetLine balanceSheetLine = balanceSheetLineService.get(id);
    if (balanceSheetLine == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, 
      TRANSACTION_NOT_FOUND + id);
    }
    return balanceSheetLine;
  }

  /**
   * Removes a balanceSheetLine by its id.
   *
   * @param id  the id of the balanceSheetLine
   */
  @DeleteMapping("/balancesheet/{id}")
  public void deleteOne(@PathVariable Integer id) {
    balanceSheetLineService.remove(id);
  }

  /**
   * Adds a new balance sheet line to the dictionary.
   *
   * @param balanceSheetLine  the balanceSheetLine to add
   * @return             the added balanceSheetLine
   */
  @PostMapping("/balancesheet")
  public BalanceSheetLine addOne(BalanceSheetLine balanceSheetLine) {
    return balanceSheetLineService.save(balanceSheetLine);
  }

  /**
   * Add a new method to update a balance sheet line by its id.
   * The method should accept an HTTP PUT request at /balancesheet/{id}.
   * The method should return the updated balancesheet line.
   * If the balancesheet line with the given id does not exist, the method should return a 404 Not Found status.
   * The method should use the BalanceSheetLineService to update the balanceSheetLine.
   * 
   * The method signature should be:
   * 
   * @param id  the id of the balanceSheetLine
   * @param balanceSheetLine  the updated balanceSheetLine
   */
  @PutMapping("/balancesheet/{id}")
  public BalanceSheetLine updateOne(@PathVariable Integer id, @RequestBody BalanceSheetLine balanceSheetLine) {
    BalanceSheetLine existingBalanceSheetLine = balanceSheetLineService.get(id);
    if (existingBalanceSheetLine == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, TRANSACTION_NOT_FOUND + id);
    }

    PropertyUtils.copyNonNullProperties(balanceSheetLine, existingBalanceSheetLine);

    return balanceSheetLineService.save(existingBalanceSheetLine);
  }
}