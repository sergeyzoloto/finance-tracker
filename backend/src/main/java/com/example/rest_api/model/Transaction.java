package com.example.rest_api.model;

public class Transaction {

  private String id;

  public Transaction() {
  }

  public Transaction(String id) {
    this.id = id;
  }

  // Getters and setters

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  
}