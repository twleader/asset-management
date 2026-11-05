# 富邦未來交割內部觀測契約

`POST /internal/settlement/read` 僅查詢既有唯讀 SDK `query_settlement(...,"3d")`。成功 envelope 為明確帳戶綁定、當次日期／時間、`coverageStatus=SDK_RANGE_3D_RETURNED_ROWS`，不宣稱完整未交割帳。原始帳號／token 不進入回應。

AVAILABLE 明細保留來源 `sourceQueryDate`、`settlementDate` 與 TWD。十二個來源金額 key 都必須存在；只有方向稅額 `buyTax`、`sellTax` 可為 explicit null，表示來源未知，不是 0，也不以任何金額反推。其他十個金額必為 canonical signed exact integer 字串（precision≤20）；非 null 方向稅額同樣嚴格驗證。Java immutable DTO 與驗證保持此差異；缺 key、錯型別與不合法數字整批拒絕。

在途投影只取來源 `buySettlement≤0`／`sellSettlement≥0`，兩者和必須等於來源 `totalSettlementAmount`；不得重加費稅。future row按官方交割日期分列，未知方向稅額不影響已完整驗證的方向款項。其他帳戶／日期／重複／freshness／金額防線不變。已到期列先驗身份、原key、日期和TWD再排除；真正全null空佔位仍為NO_DATA_OBSERVED。

既有內部 `/internal/brokers/fubon/settlement-sync` 預設dry-run；寫入需原owner gate、snapshot與owner鎖、來源列保護及同一transaction，重跑不重複，缺新代號證據不清原notes。此內部契約不改公開API、SRPP金融Swagger或排程，不授權券商交易。
