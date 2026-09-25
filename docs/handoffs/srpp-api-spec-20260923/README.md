# 給資產管理系統的 API 實作包

先讀 [API_SPEC.md](API_SPEC.md)，再將 [openapi.yaml](openapi.yaml) 匯入 OpenAPI 編輯器。JSON 等價版本為 [openapi.json](openapi.json)。

提案端點：`GET /api/public/srpp/daily-context`。

包含資產對帳、配置差距、收益／稅後現金流、買前資金基礎及完成日技術資料；附版本、來源重播、失效與錯誤語意。

**目前僅交付規格。API 尚未實作，SRPP 未修改，也未新增或調整排程。** 完成 API 後再依使用者後續指示接入 SRPP；實作時不要讓 Swagger 產生器提前覆寫 SRPP 鏡像。

驗收要求見 [ACCEPTANCE.md](ACCEPTANCE.md)，本次文件驗證結果見 [validation.json](validation.json)。

如需重跑文件驗證，在獨立虛擬環境安裝 `requirements-validation.txt` 後執行 `python validate_contract.py`。此工具只讀本規格和範例，寫入本目錄的 validation.json，不會呼叫 API 或修改 SRPP。

範例均為合成資料。COMPLETE 範例只展示完整結構，不能拿其中的金額、日曆或公式版本當作真實政策。
