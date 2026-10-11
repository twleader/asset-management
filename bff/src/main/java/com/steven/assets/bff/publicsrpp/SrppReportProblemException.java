package com.steven.assets.bff.publicsrpp;
final class SrppReportProblemException extends RuntimeException {
 private final SrppReportProblemCatalog problem;
 SrppReportProblemException(SrppReportProblemCatalog p){this(p,null);}
 SrppReportProblemException(SrppReportProblemCatalog p,Throwable c){super("SRPP report "+p.name(),c);problem=p;}
 SrppReportProblemCatalog problem(){return problem;}
}
