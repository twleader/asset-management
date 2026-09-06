package com.steven.assets.bff.publictransaction;

/** email selector 格式不合法；不得與既有交易紀錄篩選條件錯誤混用。 */
public class PublicTransactionHistoryEmailRequestException extends RuntimeException {
    public PublicTransactionHistoryEmailRequestException() {
        super("invalid public transaction history email");
    }
}
