# 9090 API 契約

| 契約 | 標準來源 | 閱讀文件 | 用途 |
| --- | --- | --- | --- |
| 金融與市場 API | [docker-external-api.yaml](docker-external-api.yaml) | [9090-api-swagger.md](9090-api-swagger.md) | 原24個method/path pairs；金融 PublishedSwaggerIdentity 維持原文件SHA256 |
| AI 判斷流程觀測 API | [ai-flow-observation-api.yaml](ai-flow-observation-api.yaml) | [9090-ai-flow-observation.md](9090-ai-flow-observation.md) | 新1個exact POST，只記錄公開流程；使用獨立版本及專用憑證，不參與金融Swagger身份 |

兩份契約各自解析local refs，路徑不重疊，union為25個method/path pairs（20GET、5POST）；Tailscale使用24個path handlers，寄信POST與其GET child沿用同一handler。

登入後在「系統資訊 → 開放API」切換閱讀金融／AI觀測文件；該頁只呈現契約，不執行API。「系統資訊 → AI判斷流程」只有ADMIN可查詢，按日期與Codex／Claude分開顯示早午盤紀錄，保留90個台北日期。

此觀測功能不修改SRPP規則或既有LLM金融排程。詳細收集方式與Claude遠端trace缺口見 [AI判斷流程操作說明](../ai-flow-observation.md)。只更新Tailscale觀測路由時，使用 `scripts/configure-tailscale-api-gateway.sh --observation-only`；此模式只追加新route，保留既有設定、不跑金融探測、不reset。
