package com.steven.assets.srpp;

/** Requirement 163：拒絕發布的具名原因碼（只記 owner id 與原因碼，不含帳號、SQL 或例外堆疊）。 */
public class SrppRejectedException extends RuntimeException {
    private final String reasonCode;

    public SrppRejectedException(String reasonCode) {
        super(reasonCode);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
