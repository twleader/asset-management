package com.steven.assets.service.tradereport;
public class TradeReportProblem extends RuntimeException {
 private final int status;
 public TradeReportProblem(String code,int status){super(code);this.status=status;}
 public int status(){return status;}
}
