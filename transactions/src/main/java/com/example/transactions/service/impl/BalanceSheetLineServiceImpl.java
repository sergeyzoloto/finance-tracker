package com.example.transactions.service.impl;

import com.example.transactions.model.BalanceSheetLine;
import com.example.transactions.repository.BalanceSheetLineRepository;
import com.example.transactions.service.BalanceSheetLineService;
import org.springframework.stereotype.Service;

/**
 * Service implementation for balanceSheetLine operations.
 */
@Service
public class BalanceSheetLineServiceImpl implements BalanceSheetLineService {

  private final BalanceSheetLineRepository balanceSheetLineRepository;

  public BalanceSheetLineServiceImpl(BalanceSheetLineRepository balanceSheetLineRepository) {
    this.balanceSheetLineRepository = balanceSheetLineRepository;
  }

  /**
   * Returns all balance sheet lines.
   *
   * @return all balance sheet lines
   */
  @Override
  public Iterable<BalanceSheetLine> getAll() {
    return balanceSheetLineRepository.findAll();
  }

  /**
   * Returns a balanceSheetLine by its id.
   *
   * @param id  the id of the balanceSheetLine
   * @return    the balanceSheetLine with the given id
   */
  @Override
  public BalanceSheetLine get(Integer id) {
    return balanceSheetLineRepository.findById(id).orElse(null);
  }

  /**
   * Removes a balanceSheetLine by its id.
   *
   * @param id  the id of the balanceSheetLine
   */
  @Override
  public void remove(Integer id) {
    balanceSheetLineRepository.deleteById(id);
  }

  /**
   * Saves a balanceSheetLine.
   *
   * @param balanceSheetLine the balanceSheetLine to save
   * @return the saved balanceSheetLine
   */
  @Override
  public BalanceSheetLine save(BalanceSheetLine balanceSheetLine) {
    return balanceSheetLineRepository.save(balanceSheetLine);
  }
}