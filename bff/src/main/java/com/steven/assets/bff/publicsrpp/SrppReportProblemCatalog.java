package com.steven.assets.bff.publicsrpp;
import org.springframework.http.HttpStatus;

enum SrppReportProblemCatalog {
 INVALID_REQUEST(400,false,"查詢參數不合法。"), CALCULATION_NOT_FOUND(404,false,"查無此擁有者可讀取的計算包。"),
 NON_TRADING_DAY(409,false,"指定日期不是交易日。"), POLICY_UNSUPPORTED(409,false,"正式政策未通過驗證。"),
 SWAGGER_MISMATCH(409,false,"Swagger 身分不符。"), CONTEXT_IDENTITY_MISMATCH(409,false,"計算包身分不符。"),
 UPSTREAM_INVALID(502,false,"上游回應未通過驗證。"), OWNER_UNAVAILABLE(503,false,"指定帳號不可用。"),
 CALENDAR_UNAVAILABLE(503,true,"交易日曆尚未就緒。"), CONTEXT_NOT_READY(503,true,"本時段計算包尚未就緒。");
 private final HttpStatus status; private final boolean retryable; private final String detail;
 SrppReportProblemCatalog(int status,boolean retryable,String detail){this.status=HttpStatus.valueOf(status);this.retryable=retryable;this.detail=detail;}
 HttpStatus status(){return status;} boolean retryable(){return retryable;} String detail(){return detail;}
 String title(){return "SRPP report calculations "+name();}
}
