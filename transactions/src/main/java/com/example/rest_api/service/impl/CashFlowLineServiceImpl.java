package com.example.transactions.service.impl;

import com.example.transactions.model.CashFlowLine;
import com.example.transactions.repository.CashFlowLineRepository;
import com.example.transactions.service.CashFlowLineService;
import org.springframework.stereotype.Service;

/**
 * Service implementation for cashFlowLine operations.
 */
@Service
public class CashFlowLineServiceImpl implements CashFlowLineService {

  private final CashFlowLineRepository cashFlowLineRepository;

  public CashFlowLineServiceImpl(CashFlowLineRepository cashFlowLineRepository) {
    this.cashFlowLineRepository = cashFlowLineRepository;
  }

  /**
   * Returns all cashflow lines.
   *
   * @return all cashflow lines
   */
  @Override
  public Iterable<CashFlowLine> getAll() {
    return cashFlowLineRepository.findAll();
  }

  /**
   * Returns a cashFlowLine by its id.
   *
   * @param id  the id of the cashFlowLine
   * @return    the cashFlowLine with the given id
   */
  @Override
  public CashFlowLine get(Integer id) {
    return cashFlowLineRepository.findById(id).orElse(null);
  }

  /**
   * Removes a cashFlowLine by its id.
   *
   * @param id  the id of the cashFlowLine
   */
  @Override
  public void remove(Integer id) {
    cashFlowLineRepository.deleteById(id);
  }

  /**
   * Saves a cashFlowLine.
   *
   * @param cashFlowLine the cashFlowLine to save
   * @return the saved cashFlowLine
   */
  @Override
  public CashFlowLine save(CashFlowLine cashFlowLine) {
    return cashFlowLineRepository.save(cashFlowLine);
  }
}