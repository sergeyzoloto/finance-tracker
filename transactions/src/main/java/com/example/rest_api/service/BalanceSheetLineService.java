package com.example.transactions.service;

import com.example.transactions.model.BalanceSheetLine;

public interface BalanceSheetLineService {

  Iterable<BalanceSheetLine> getAll();

  BalanceSheetLine get(Integer id);

  void remove(Integer id);

  BalanceSheetLine save(BalanceSheetLine balanceSheetLine);

}