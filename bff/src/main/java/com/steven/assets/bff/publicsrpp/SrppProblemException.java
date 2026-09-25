package com.steven.assets.bff.publicsrpp;

/** 已分類的 SRPP 公開路由錯誤；訊息只含 code，不含帳號、URI、上游文字。 */
public class SrppProblemException extends RuntimeException {

    private final SrppProblemCatalog problem;

    public SrppProblemException(SrppProblemCatalog problem) {
        this(problem, null);
    }

    public SrppProblemException(SrppProblemCatalog problem, Throwable cause) {
        super("SRPP daily context " + problem.name(), cause);
        this.problem = problem;
    }

    public SrppProblemCatalog problem() {
        return problem;
    }
}
