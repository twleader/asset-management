# Task 592：情境模擬訂閱／本機 AI 選擇

對應Requirement208修訂。目標：資產配置建議→SRPP設定→情境模擬，可選可擴充工具、model、effort，沿既有訂閱或本機模型且無額外API費用。此為待實作規格。

## 實作契約

- 移除本功能SrppSettingsAnthropicAi直接SDK路徑。新增SrppSettingsLocalAi與擴充AiPort，backend與BFF兩份CenterResponse aiTools/aiCatalogRevision，同步SrppSettingsPresentation.center/run完整保留新欄位及舊列null，simulate明示aiSelection={toolId,model,effort,catalogRevision}，RunResponse aiSelection/aiExecution。可用工具才可選；effort依model清單、無能力為null/不適用。首批支援四種adapter，同一adapter可註冊多個工具；支援codex_cli/claude_cli/ollama/local_openai（LMStudio），host registry能新增id與模型，前端不寫死。
- 後端及host先驗證完整閉合selection、catalog版本、tool/model/effort組合，再執行，LOCAL不探測AI並拒絕非空selection。owner/CAS/有界背景工作不變。模型工作再次驗證，失敗保留數值且具名說明，永不自動換工具或model。
- host bridge僅127.0.0.1:8766，GET /capabilities、POST /interpret均驗token，Docker經host.docker.internal。token0600並唯讀mount，絕不回瀏覽器／Git／日誌。CLI憑證留host，不能mount整個home進Docker。registry為管理者host JSON、固定argv adapters；無任意shell／網頁命令／URL。HTTP body上限2MiB、輸出文字上限20000字、一次1工作、整段模型執行最長120秒（低於175秒總deadline），逾時殺子程序群組；無redirect/proxy。
- Codex：乾淨env/隔離cwd、不讀user custom config，固定OpenAI provider與ChatGPT auth，API keys/helper/provider/proxy不生效；每次auth+account/rateLimits/read，必須個人訂閱且credits.hasCredits=false、credits.unlimited=false，未知／可付費credit均拒絕。優先從app-server model/list取得支援model/effort，可回退本機CLI模型cache並標CACHE，僅subscription支援與非hidden條目；CLI仍可能按帳戶拒絕模型，須明示不得fallback。
- Claude：不得使用--bare（會排除訂閱OAuth）。乾淨env、safe mode、tools空、strict mcp空、disable skills/hooks、隔離cwd；每次auth status必須claude.ai及訂閱，OAuth usage extra_usage.is_enabled須明確false，true或未知不發模型請求。模型與effort由host明示能力清單且標CONFIGURED，不假稱動態驗證。禁fast模式／overage／API fallback，不修改帳戶費用設定。
- Ollama與local_openai只允許管理設定的numeric loopback固定port，禁remote endpoint/redirect/proxy，模型需本機存在，cloud/remote模型拒絕；可驗證effort才提供，否則null。Gemma經Ollama使用，但不是單獨寫死一個Gemma選項。
- 所選model與provider實際回報model分開：無證據actualModel=null，receipt明示REQUESTED_ONLY；不能假稱已驗證實際模型。選擇、執行時間、billingMode、adapter版本、status/code為歷史證據，新增可空ai_selection_json/ai_execution_json於srpp_settings_run；舊列null可讀、顯示未記錄。選擇與收據不進worker與deterministic hashes／release payload。遷移冪等且部署後重產schema.sql。
- 前端三個連動選單及使用方式/連線/不可用原因、執行結果receipt。工具／model變動清掉失效選擇，owner切換清掉，過期catalog可重載。沿原BFF，不新增9090／公網路由；不改SRPPrepo／policy/hash／報告排程／其他頁AI。
- 新增host bridge安裝/停止/狀態操作說明與LaunchAgent安裝腳本；保留既有token/registry，首次部署實際啟動。必要business/bff/frontend服務rebuild/recreate；不以health代替功能驗證。

## 驗收與交付

- [ ] spec機械檢查與獨立Codex對抗式審查（最多三輪）通過後才實作。
- [ ] Python bridge單元測試：訂閱/API-key/費用未知/credits/extra_usage拒絕零模型請求、環境隔離、host auth、非法selection、remote/cloud拒絕、無fallback、busy與timeout清理。
- [ ] Java測試：LOCAL零AI、owner/CAS不變、選擇驗證、收據儲存、舊列可讀、hash不變、AI失敗保留數值。
- [ ] 前端連動/無effort/不可用/owner切換驗證及build；經頁面BFF驗證新欄位與舊列null。
- [ ] 實作獨立Codex架構審查，必要修正；重建部署host bridge及business/bff/frontend，實測capabilities、LOCAL、至少一個本機模型AI成功、受限CLI拒絕與選擇收據；若CLI安全可用則實測成功。
- [ ] 生成schema.sql，留驗收紀錄，不洩漏持股與token。commit短中文、feature push、main --no-ff merge/push、remote SHA回讀。
