package com.example.transactions.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.sql.Timestamp;

/**
 * The Transaction class represents a transaction entity.
 */
@Data // Lombok annotation to create all the getters, setters, equals, hash, and toString methods
@Builder // Lombok annotation: builder pattern
@AllArgsConstructor // Lombok annotation: constructor with all arguments
@NoArgsConstructor // Lombok annotation: default constructor
@Entity // JPA annotation to make this class ready for storage in a JPA-based data store
@Table(name = "TRANSACTIONS")
public class Transaction {
  
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Integer id;
  
  @Column(name = "AMOUNT", nullable = false)
  private BigDecimal amount;
  
  @Column(
    name = "TIMESTAMP",
    nullable = false,
    columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP")
  private Timestamp timestamp;

  @Column(name = "USER_ID", nullable = false)
  private Integer userId;

  @Column(name = "CURRENCY_CODE", nullable = false)
  private Integer currencyCode;

  @Column(name = "CURRENCY_GROUP")
  private Integer currencyGroup;

  @Column(name = "GROUP_EXPENSE")
  private BigDecimal groupExpense;

  @Column(name = "GROUP_INCOME")
  private BigDecimal groupIncome;

  @Column(name = "GROUP_ID")
  private Integer groupId;

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

  @Column(name = "TYPE", nullable = false)
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

  @Column(name = "DATE", nullable = false)
  private String date;

  @Column(name = "DAY", nullable = false)
  private Integer day;

  @Column(name = "YEAR", nullable = false)
  private Integer year;

  @Column(name = "MONTH", nullable = false)
  private String month;

}
