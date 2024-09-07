package com.example.rest_api.model;

import com.example.rest_api.utils.Month;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalDate;

/**
 * The Transaction class represents a transaction entity.
 */
@Entity
@Table(name = "TRANSACTIONS")
public class Transaction {

  @Id
  @GeneratedValue(strategy = GenerationType.AUTO)
  private Integer id;

  @Column(name = "AMOUNT", nullable = false)
  private BigDecimal amount;

  @Column(
    name = "TIMESTAMP", 
    nullable = false, 
    columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
  private LocalDateTime timestamp;

  @Column(name = "DATE", nullable = false)
  private LocalDate date;

  @Column(name = "YEAR", nullable = false)
  private Integer year;

  @Enumerated(EnumType.STRING)
  @Column(name = "MONTH", nullable = false)
  private Month month;

  @Column(name = "DAY", nullable = false)
  private byte day;

  @Column(name = "USER_ID", nullable = false)
  private Integer userId;

  @Column(name = "CURRENCY_CODE", nullable = false)
  private Integer currencyCode;

  @Column(name = "COUNTER_AGENT")
  private String counterAgent;

  @Column(name = "COMMENT")
  private String comment;

  @Column(name = "CURRENCY_CREDIT")
  private Integer currencyCredit;

  @Column(name = "CURRENCY_DEBIT")
  private Integer currencyDebit;

  @Column(name = "SUM_CREDIT")
  private BigDecimal sumCredit;

  @Column(name = "SUM_DEBIT")
  private BigDecimal sumDebit;

  @Column(name = "Type", nullable = false)
  private String type;

  @Column(name = "CASH_FLOW_ID", nullable = false)
  private Integer cashFlowId;

  @Column(name = "CODE_DEBIT_ID", nullable = false)
  private Integer codeDebitId;

  @Column(name = "CODE_CREDIT_ID", nullable = false)
  private Integer codeCreditId;

  @Column(name = "TYPE_DEBIT_ID", nullable = false)
  private Integer typeDebitId;

  @Column(name = "TYPE_CREDIT_ID", nullable = false)
  private Integer typeCreditId;

  @Column(name = "DEBIT_CHANGE", nullable = false)
  private BigDecimal debitChange;

  @Column(name = "CREDIT_CHANGE", nullable = false)
  private BigDecimal creditChange;

  @Column(name = "LOAN_COUNTER_AGENT")
  private String loanCounterAgent;

  @Column(name = "LOAN_COMMENT")
  private String loanComment;

  @Column(name = "LOAN_CURRENCY_CODE")
  private Integer loanCurrencyCode;

  @Column(name = "LOAN_CURRENCY_SUM")
  private BigDecimal loanCurrencySum;

  @Column(name = "CURRENCY_GROUP")
  private Integer currencyGroup;

  @Column(name = "GROUP_EXPENSE")
  private BigDecimal groupExpense;

  @Column(name = "GROUP_INCOME")
  private BigDecimal groupIncome;

  @Column(name = "GROUP_ID")
  private Integer groupId;

  public Transaction() {
    // Default constructor
  }

  public Transaction(
    Integer id, 
    BigDecimal amount, 
    LocalDateTime timestamp, 
    Integer userId
    )
  {
    this.id = id;
    this.amount = amount;
    this.timestamp = timestamp;
    this.userId = userId;
  }

  public Transaction(Integer id, BigDecimal amount, Integer userId) {
    this.id = id;
    this.amount = amount;
    this.userId = userId;
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

  public Integer getUserId() {
    return userId;
  }

  public void setUserId(Integer userId) {
    this.userId = userId;
  }
}