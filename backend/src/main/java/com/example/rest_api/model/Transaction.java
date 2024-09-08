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

  @Column(name = "DATE", nullable = false)
  private String date;

  @Column(name = "DAY", nullable = false)
  private Integer day;

  @Column(name = "YEAR", nullable = false)
  private Integer year;

  @Column(name = "MONTH", nullable = false)
  private String month;

  // Default constructor required by JPA
  public Transaction() {
  }

  private Transaction(Builder builder) {
    this.id = builder.id;
    this.currencyGroup = builder.currencyGroup;
    this.groupExpense = builder.groupExpense;
    this.groupIncome = builder.groupIncome;
    this.groupId = builder.groupId;
    this.counterAgent = builder.counterAgent;
    this.comment = builder.comment;
    this.currencyCredit = builder.currencyCredit;
    this.currencyDebit = builder.currencyDebit;
    this.sumCredit = builder.sumCredit;
    this.sumDebit = builder.sumDebit;
    this.type = builder.type;
    this.cashFlowId = builder.cashFlowId;
    this.codeDebitId = builder.codeDebitId;
    this.codeCreditId = builder.codeCreditId;
    this.typeDebitId = builder.typeDebitId;
    this.typeCreditId = builder.typeCreditId;
    this.debitChange = builder.debitChange;
    this.creditChange = builder.creditChange;
    this.date = builder.date;
    this.day = builder.day;
    this.year = builder.year;
    this.month = builder.month;
  }

  public static class Builder {
    private Integer id;
    private Integer currencyGroup;
    private BigDecimal groupExpense;
    private BigDecimal groupIncome;
    private Integer groupId;
    private String counterAgent;
    private String comment;
    private Integer currencyCredit;
    private Integer currencyDebit;
    private BigDecimal sumCredit;
    private BigDecimal sumDebit;
    private String type;
    private Integer cashFlowId;
    private Integer codeDebitId;
    private Integer codeCreditId;
    private Integer typeDebitId;
    private Integer typeCreditId;
    private BigDecimal debitChange;
    private BigDecimal creditChange;
    private String date;
    private Integer day;
    private Integer year;
    private String month;

    public Builder setId(Integer id) {
      this.id = id;
      return this;
    }

    public Builder setCurrencyGroup(Integer currencyGroup) {
      this.currencyGroup = currencyGroup;
      return this;
    }

    public Builder setGroupExpense(BigDecimal groupExpense) {
      this.groupExpense = groupExpense;
      return this;
    }

    public Builder setGroupIncome(BigDecimal groupIncome) {
      this.groupIncome = groupIncome;
      return this;
    }

    public Builder setGroupId(Integer groupId) {
      this.groupId = groupId;
      return this;
    }

    public Builder setCounterAgent(String counterAgent) {
      this.counterAgent = counterAgent;
      return this;
    }

    public Builder setComment(String comment) {
      this.comment = comment;
      return this;
    }

    public Builder setCurrencyCredit(Integer currencyCredit) {
      this.currencyCredit = currencyCredit;
      return this;
    }

    public Builder setCurrencyDebit(Integer currencyDebit) {
      this.currencyDebit = currencyDebit;
      return this;
    }

    public Builder setSumCredit(BigDecimal sumCredit) {
      this.sumCredit = sumCredit;
      return this;
    }

    public Builder setSumDebit(BigDecimal sumDebit) {
      this.sumDebit = sumDebit;
      return this;
    }

    public Builder setType(String type) {
      this.type = type;
      return this;
    }

    public Builder setCashFlowId(Integer cashFlowId) {
      this.cashFlowId = cashFlowId;
      return this;
    }

    public Builder setCodeDebitId(Integer codeDebitId) {
      this.codeDebitId = codeDebitId;
      return this;
    }

    public Builder setCodeCreditId(Integer codeCreditId) {
      this.codeCreditId = codeCreditId;
      return this;
    }

    public Builder setTypeDebitId(Integer typeDebitId) {
      this.typeDebitId = typeDebitId;
      return this;
    }

    public Builder setTypeCreditId(Integer typeCreditId) {
      this.typeCreditId = typeCreditId;
      return this;
    }

    public Builder setDebitChange(BigDecimal debitChange) {
      this.debitChange = debitChange;
      return this;
    }

    public Builder setCreditChange(BigDecimal creditChange) {
      this.creditChange = creditChange;
      return this;
    }

    public Builder setDate(String date) {
      this.date = date;
      return this;
    }

    public Builder setDay(Integer day) {
      this.day = day;
      return this;
    }

    public Builder setYear(Integer year) {
      this.year = year;
      return this;
    }

    public Builder setMonth(String month) {
      this.month = month;
      return this;
    }

    public Transaction build() {
      return new Transaction(this);
    }
  }

  // Getters and setters...

  public Integer getId() {
    return id;
  }

  public void setId(Integer id) {
    this.id = id;
  }

  public Integer getCurrencyGroup() {
    return currencyGroup;
  }

  public void setCurrencyGroup(Integer currencyGroup) {
    this.currencyGroup = currencyGroup;
  }

  public BigDecimal getGroupExpense() {
    return groupExpense;
  }

  public void setGroupExpense(BigDecimal groupExpense) {
    this.groupExpense = groupExpense;
  }

  public BigDecimal getGroupIncome() {
    return groupIncome;
  }

  public void setGroupIncome(BigDecimal groupIncome) {
    this.groupIncome = groupIncome;
  }

  public Integer getGroupId() {
    return groupId;
  }

  public void setGroupId(Integer groupId) {
    this.groupId = groupId;
  }

  public String getCounterAgent() {
    return counterAgent;
  }

  public void setCounterAgent(String counterAgent) {
    this.counterAgent = counterAgent;
  }

  public String getComment() {
    return comment;
  }

  public void setComment(String comment) {
    this.comment = comment;
  }

  public Integer getCurrencyCredit() {
    return currencyCredit;
  }

  public void setCurrencyCredit(Integer currencyCredit) {
    this.currencyCredit = currencyCredit;
  }

  public Integer getCurrencyDebit() {
    return currencyDebit;
  }

  public void setCurrencyDebit(Integer currencyDebit) {
    this.currencyDebit = currencyDebit;
  }

  public BigDecimal getSumCredit() {
    return sumCredit;
  }

  public void setSumCredit(BigDecimal sumCredit) {
    this.sumCredit = sumCredit;
  }

  public BigDecimal getSumDebit() {
    return sumDebit;
  }

  public void setSumDebit(BigDecimal sumDebit) {
    this.sumDebit = sumDebit;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public Integer getCashFlowId() {
    return cashFlowId;
  }

  public void setCashFlowId(Integer cashFlowId) {
    this.cashFlowId = cashFlowId;
  }

  public Integer getCodeDebitId() {
    return codeDebitId;
  }

  public void setCodeDebitId(Integer codeDebitId) {
    this.codeDebitId = codeDebitId;
  }

  public Integer getCodeCreditId() {
    return codeCreditId;
  }

  public void setCodeCreditId(Integer codeCreditId) {
    this.codeCreditId = codeCreditId;
  }

  public Integer getTypeDebitId() {
    return typeDebitId;
  }

  public void setTypeDebitId(Integer typeDebitId) {
    this.typeDebitId = typeDebitId;
  }

  public Integer getTypeCreditId() {
    return typeCreditId;
  }

  public void setTypeCreditId(Integer typeCreditId) {
    this.typeCreditId = typeCreditId;
  }

  public BigDecimal getDebitChange() {
    return debitChange;
  }

  public void setDebitChange(BigDecimal debitChange) {
    this.debitChange = debitChange;
  }

  public BigDecimal getCreditChange() {
    return creditChange;
  }

  public void setCreditChange(BigDecimal creditChange) {
    this.creditChange = creditChange;
  }

  public String getDate() {
    return date;
  }

  public void setDate(String date) {
    this.date = date;
  }

  public Integer getDay() {
    return day;
  }

  public void setDay(Integer day) {
    this.day = day;
  }

  public Integer getYear() {
    return year;
  }

  public void setYear(Integer year) {
    this.year = year;
  }

  public String getMonth() {
    return month;
  }

  public void setMonth(String month) {
    this.month = month;
  }
}