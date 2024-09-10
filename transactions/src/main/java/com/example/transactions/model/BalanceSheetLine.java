package com.example.transactions.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "balance_sheet_line")
public class BalanceSheetLine {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  private Integer id;

  @Column(name = "user_id")
  private Integer userId;

  @Column(name = "line")
  private String line;

  @Column(name = "type")
  private String type;

  @Column(name = "code")
  private String code;

  public BalanceSheetLine() {
    // Default constructor for JPA
  }

  public BalanceSheetLine(String line, Integer userId, String type, String code) {
    this.line = line;
    this.userId = userId;
    this.type = type;
    this.code = code;
  }

  public Integer getId() {
    return id;
  }

  public void setId(Integer id) {
    this.id = id;
  }

  public String getLine() {
    return line;
  }

  public void setLine(String line) {
    this.line = line;
  }

  public Integer getUserId() {
    return userId;
  }

  public void setUserId(Integer userId) {
    this.userId = userId;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public String getCode() {
    return code;
  }

  public void setCode(String code) {
    this.code = code;
  }
}