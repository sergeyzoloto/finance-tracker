package com.example.rest_api.repository;

import org.springframework.data.repository.CrudRepository;

import com.example.rest_api.model.Transaction;

public interface TransactionsRepository extends CrudRepository<Transaction, Integer>{

}