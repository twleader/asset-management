package com.steven.assets.bff.publicsrpp;

final class SrppOrchestratedProblemException extends RuntimeException {
    private final SrppOrchestratedProblemCatalog problem;
    SrppOrchestratedProblemException(SrppOrchestratedProblemCatalog problem) { this(problem, null); }
    SrppOrchestratedProblemException(SrppOrchestratedProblemCatalog problem, Throwable cause) {
        super("SRPP orchestrated request " + problem.name(), cause);
        this.problem = problem;
    }
    SrppOrchestratedProblemCatalog problem() { return problem; }
}
