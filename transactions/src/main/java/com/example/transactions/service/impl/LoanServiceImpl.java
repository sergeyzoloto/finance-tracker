package com.example.transactions.service.impl;

import com.example.transactions.model.Loan;
import com.example.transactions.repository.LoanRepository;
import com.example.transactions.service.LoanService;
import org.springframework.stereotype.Service;

/**
 * Service implementation for loan operations.
 */
@Service
public class LoanServiceImpl implements LoanService {

  private final LoanRepository loanRepository;

  public LoanServiceImpl(LoanRepository loanRepository) {
    this.loanRepository = loanRepository;
  }

  /**
   * Returns all loan counter agents.
   *
   * @return all loan counter agents
   */
  @Override
  public Iterable<Loan> getAll() {
    return loanRepository.findAll();
  }

  /**
   * Returns a loan by its id.
   *
   * @param id  the id of the loan
   * @return    the loan with the given id
   */
  @Override
  public Loan get(Integer id) {
    return loanRepository.findById(id).orElse(null);
  }

  /**
   * Removes a loan by its id.
   *
   * @param id  the id of the loan
   */
  @Override
  public void remove(Integer id) {
    loanRepository.deleteById(id);
  }

  /**
   * Saves a loan.
   *
   * @param loan the loan to save
   * @return the saved loan
   */
  @Override
  public Loan save(Loan loan) {
    return loanRepository.save(loan);
  }
}