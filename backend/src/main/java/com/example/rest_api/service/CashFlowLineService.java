package com.example.rest_api.service;

import com.example.rest_api.model.CashFlowLine;

public interface CashFlowLineService {

  Iterable<CashFlowLine> getAll();

  CashFlowLine get(Integer id);

  void remove(Integer id);

  CashFlowLine save(CashFlowLine cashFlowLine);

}