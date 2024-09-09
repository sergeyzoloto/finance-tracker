package com.example.transactions.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "loan_ca")
public class Loan {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  private Integer id;

  @Column(name = "loan_ca")
  private String loanCA;

  @Column(name = "user_id")
  private Integer userId;

  public Loan() {
    // Default constructor for JPA
  }

  public Loan(String loanCA, Integer userId) {
    this.loanCA = loanCA;
    this.userId = userId;
  }

  public Integer getId() {
    return id;
  }

  public void setId(Integer id) {
    this.id = id;
  }

  public String getLoanCA() {
    return loanCA;
  }

  public void setLoanCA(String loanCA) {
    this.loanCA = loanCA;
  }

  public Integer getUserId() {
    return userId;
  }

  public void setUserId(Integer userId) {
    this.userId = userId;
  }
}