package com.example.transactions.service;

import com.example.transactions.model.CashFlowLine;

public interface CashFlowLineService {

  Iterable<CashFlowLine> getAll();

  CashFlowLine get(Integer id);

  void remove(Integer id);

  CashFlowLine save(CashFlowLine cashFlowLine);

}