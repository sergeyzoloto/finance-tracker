package com.example.rest_api.service;

import com.example.rest_api.model.BalanceSheetLine;

public interface BalanceSheetLineService {

  Iterable<BalanceSheetLine> getAll();

  BalanceSheetLine get(Integer id);

  void remove(Integer id);

  BalanceSheetLine save(BalanceSheetLine balanceSheetLine);

}