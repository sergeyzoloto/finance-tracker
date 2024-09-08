package com.example.rest_api.web;

import com.example.rest_api.model.Loan;
import com.example.rest_api.service.LoanService;
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
 * The LoanController class handles HTTP requests related
 * to loan operations.
 */
@RestController
public class LoanController {

  private static final String TRANSACTION_NOT_FOUND = "Loan not found with id ";

  private final LoanService loanService;

  public LoanController(@Autowired LoanService loanService) {
    this.loanService = loanService;
  }

  /**
   * Returns all loan counter agents.
   *
   * @return all loan counter agents
   */
  @GetMapping("/loan")
  public Iterable<Loan> getLoan() {
    return loanService.getAll();
  }

  /**
   * Returns a loanCA by its id.
   *
   * @param id  the id of the loan
   * @return    the loanCA with the given id
   */
  @GetMapping("/loan/{id}")
  public Loan getOne(@PathVariable Integer id) {
    Loan loan = loanService.get(id);
    if (loan == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, 
      TRANSACTION_NOT_FOUND + id);
    }
    return loan;
  }

  /**
   * Removes a loan by its id.
   *
   * @param id  the id of the loan
   */
  @DeleteMapping("/loan/{id}")
  public void deleteOne(@PathVariable Integer id) {
    loanService.remove(id);
  }

  /**
   * Adds a new loanCA.
   *
   * @param loan  the loan to add
   * @return             the added loan
   */
  @PostMapping("/loan")
  public Loan addOne(Loan loan) {
    return loanService.save(loan);
  }

  /**
   * Add a new method to update a loan by its id.
   * The method should accept an HTTP PUT request at /loan/{id}.
   * The method should return the updated loan.
   * If the loan with the given id does not exist, the method should return a 404 Not Found status.
   * The method should use the LoanService to update the loan.
   * 
   * The method signature should be:
   * 
   * @param id  the id of the loan
   * @param loan  the updated loan
   */
  @PutMapping("/loan/{id}")
  public Loan updateOne(@PathVariable Integer id, @RequestBody Loan loan) {
    Loan existingLoan = loanService.get(id);
    if (existingLoan == null) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, TRANSACTION_NOT_FOUND + id);
    }

    PropertyUtils.copyNonNullProperties(loan, existingLoan);

    return loanService.save(existingLoan);
  }
}