package com.example.rest_api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "TRANSACTIONS")
public class Transaction {

  @Id
  @GeneratedValue(strategy = GenerationType.AUTO)
  private Integer id;

  @Column(name = "AMOUNT", nullable = false)
  private BigDecimal amount;

  @Column(name = "TIMESTAMP", nullable = false, columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
  private LocalDateTime timestamp;

  public Transaction() {
    // Default constructor
  }

  public Transaction(Integer id, BigDecimal amount, LocalDateTime timestamp) {
    this.id = id;
    this.amount = amount;
    this.timestamp = timestamp;
  }

  public Transaction(Integer id, BigDecimal amount) {
    this.id = id;
    this.amount = amount;
  }

  // Getters and setters

  public Integer getId() {
    return id;
  }

  public void setId(Integer id) {
    this.id = id;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public void setAmount(BigDecimal amount) {
    this.amount = amount;
  }

  public LocalDateTime getTimestamp() {
    return timestamp;
  }

  public void setTimestamp(LocalDateTime timestamp) {
    this.timestamp = timestamp;
  }
}