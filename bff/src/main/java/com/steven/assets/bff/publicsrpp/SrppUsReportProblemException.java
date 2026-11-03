package com.steven.assets.bff.publicsrpp;
final class SrppUsReportProblemException extends RuntimeException {
 private final SrppUsReportProblemCatalog problem;
 SrppUsReportProblemException(SrppUsReportProblemCatalog p){this(p,null);}
 SrppUsReportProblemException(SrppUsReportProblemCatalog p,Throwable c){super("SRPP report "+p.name(),c);problem=p;}
 SrppUsReportProblemCatalog problem(){return problem;}
}
