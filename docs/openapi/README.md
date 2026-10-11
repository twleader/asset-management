# 9090 API 契約

| 契約 | 標準來源 | 閱讀文件 | 用途 |
| --- | --- | --- | --- |
| 金融與市場 API | [docker-external-api.yaml](docker-external-api.yaml) | [9090-api-swagger.md](9090-api-swagger.md) | 金融24個method/path pairs；閱讀文件已統一包含全部25個API，PublishedSwaggerIdentity為統一文件SHA256 |
| AI 判斷流程觀測 API | [ai-flow-observation-api.yaml](ai-flow-observation-api.yaml) | [9090-ai-flow-observation.md](9090-ai-flow-observation.md) | 新1個exact POST，只記錄公開流程；使用獨立版本及專用憑證，同樣納入統一Swagger發布身份 |

兩份契約各自解析local refs，路徑不重疊，union為25個method/path pairs（20GET、5POST）；Tailscale使用24個path handlers，寄信POST與其GET child沿用同一handler。

登入後在「系統資訊 → 開放API」切換閱讀金融／AI觀測文件；該頁只呈現契約，不執行API。「系統資訊 → AI判斷流程」只有ADMIN可查詢，按日期與Codex／Claude分開顯示早午盤紀錄，保留90個台北日期。

此觀測功能不修改SRPP規則或既有LLM金融排程。詳細收集方式與Claude遠端trace缺口見 [AI判斷流程操作說明](../ai-flow-observation.md)。只更新Tailscale觀測路由時，使用 `scripts/configure-tailscale-api-gateway.sh --observation-only`；此模式只追加新route，保留既有設定、不跑金融探測、不reset。

## 統一文件與 SRPP 身分維護

地端重產全部 9090 API 的單一 Swagger：

```bash
ruby scripts/render-9090-openapi-docs.rb
python3 scripts/sync-srpp-swagger-identity.py
ruby scripts/render-9090-openapi-docs.rb --check
python3 scripts/sync-srpp-swagger-identity.py --check
```

renderer同步本repo、backend classpath resource與SRPP/docs/9090 Port API Swagger.md。metadata同步工具先核原政策全部source hashes，拒絕其他漂移；只維護consumer schema的x-swagger-sha256、runtime contract的對應source hash及manifest，再同步backend authority artifact。模型／schema驗證條件／公式不變。新bundle需以`ruby scripts/srpp-report-policy-register.rb`產生並審閱冪等登錄SQL，保留歷史row；之後重建及recreate business-services，驗jar Swagger與policy resource SHA。此維護不得放進日報排程，不能藉此bless其他政策變更。雲端renderer仍只重產repo與backend兩份，地端補同步及metadata維護。
