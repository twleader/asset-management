# Task 593：Claude CLI 模型與思考程度自動探索

對應Requirement208修訂，取代Task592 Claude固定CONFIGURED三模型。這是待實作規格。

## 交付契約

- 修改scripts/srpp-local-ai-bridge.py、相關Python測試／去識別fixture、操作文件；如無wire變更不改Java/BFF/Vue/DB/9090/SRPP規則。首選Claude CLI同SDK supportedModels()的stream-json initialize控制回應，使用已裝executable與safe-mode/隔離cwd/乾淨env/空tools/strict空MCP/no-session-persistence。只送control_request initialize，不送user prompt或模型回合。
- 從models的resolvedModel、displayName、supportsEffort、supportedEffortLevels自動產生閉合AiModel={id,label,efforts,defaultEffort}，modelSource=RUNTIME。移除default_registry三個硬編碼模型。舊registry models可讀但不限制新清單、不回退為authority，保留token/registry原bytes與權限。
- 正規化先驗entry schema與付費語意，排除default/opusplan等行為模式，使用具體resolvedModel去重。不可刪付費[1m]等修飾而開放同名免費路徑。費用包含證據未知、requires credits或特定付費context不允執行；不從API目錄推定訂閱權限，不為清單多於3而放寬安全。fixture至少含4個可驗訂閱模型以證明無三模型上限；實機數量依安全CLI資料如實回報。
- effort支援必須來自CLI逐模型metadata；明確不支援為[]/null，未知不得捏造low/high/max。可用時選CLI預設（若有）；否則優先其已支持的medium，無medium選第一個已回報值，且不補其他能力。所有selection在host原閉合驗證及即時費用proof再次檢查，模型移除／effort改變需catalogRevision更新，舊selection拒絕且不fallback；歷史執行收據不變。
- 保留Codex/Claude個人訂閱proof、paid credits／extra usage／未知拒絕、無proxy/APIkey/自訂provider、無fallback、Ollama/LMStudio本機限制。model metadata不代表付費授權。CLI初始化timeout／輸出總量有界（不超現有2MiB），必須終止程序並關閉pipes；多工具能力讀取仍受既有BFF12秒client期限保護，不阻斷LOCAL數值模式。
- 探索失敗/空清單/unknown schema/非initialize或模型回合事件具名安全拒絕，此次不提供舊固定清單fallback。不能輸出auth token、email/account id或完整CLI原始stderr。

## 驗證與發布

- [ ] spec-check零BLOCK、獨立Codex spec審查與pass紀錄後才改程式。
- [ ] Python回歸含既有38項費用/工具安全測試，新metadata正負fixture、動態第4模型、duplicates、unsupported/unknown efforts、費用拒絕零推論、legacy registry不裁切、catalog stale/改effort、初始化timeout/清理/無userturn。
- [ ] 獨立Codex arch審查無未處理critical/major；實際host discovery只查metadata，業務center GET呈現RUNTIME動態資料，CLI最少一次訂閱內新discovered model/effort合成解讀成功。
- [ ] 依run-stack host部分以feature先kickstart部署驗收，保留token/registry，必要容器沒有變更不重建。用現行business API和安全9090讀取確認，不以health替代功能。
- [ ] 依commit-merge-push feature短中文commit/push、main --no-ff merge/push與remote-ref readback；乾淨main來源installer重啟host，重讀capabilities/business center與source身份、main/feature乾淨。不買點數、不改帳戶費用、不寄信、不交易，不改SRPP規則或正式報告。
