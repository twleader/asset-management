package com.steven.assets.bff.exchangerate;

/** USD/TWD live 或近一年 history 沒有可回傳資料。 */
final class UsdTwdUnavailableException extends RuntimeException {

    UsdTwdUnavailableException() {
        super("USD/TWD 匯率資料暫時不存在");
    }
}
