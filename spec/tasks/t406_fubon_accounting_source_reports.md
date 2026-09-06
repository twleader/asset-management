# [t406] 富邦帳務來源報表——延後且與入帳同步分離

**狀態:** 延後；不是本次 t394／t395 交付的一部分
**對應 Requirements:** 未來獨立需求，重新啟動前必須另寫 Requirements／Design／Task 並重新獨立審查

## 不可重用邊界

t406 若日後實作，只能保存來源報表供本人唯讀查看；不得改寫 `asset_snapshot`、`bank_deposit`、`stock_holding`、`asset_transaction` 或 `realized_gain`。它不得共享或改變下列已由 t394／t395 使用的能力：

- `FUBON_SETTLEMENT_SYNC_ENABLED`、`FUBON_REALIZED_GAIN_SYNC_ENABLED`；
- `/internal/brokers/fubon/settlement-sync`、`/internal/brokers/fubon/realized-gain-sync`；
- 兩支既有 scheduler、outcome、BFF API inventory 的 connected 狀態或在途／已實現損益寫入規則。

若重新啟動，必須採用新的 feature flag、internal route、scheduler 和 result contract；先定義來源報表資料模型、本人讀取授權、同資料去重與 migration，再開始程式碼。來源報表成功絕不可被描述為 t394／t395 入帳成功。
