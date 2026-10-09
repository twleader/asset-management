package com.steven.assets.bff.publicsrpp;

/** Task 484：SRPP capture／evaluate 在 BFF 端產生的應用錯誤，由 {@link SrppCaptureController} 的本地 handler 轉成 problem。 */
final class SrppCaptureProblemException extends RuntimeException {
    private final SrppCaptureProblemCatalog problem;
    SrppCaptureProblemException(SrppCaptureProblemCatalog problem) { this(problem, null); }
    SrppCaptureProblemException(SrppCaptureProblemCatalog problem, Throwable cause) {
        super("SRPP capture request " + problem.name(), cause);
        this.problem = problem;
    }
    SrppCaptureProblemCatalog problem() { return problem; }
}
