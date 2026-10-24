# [t508] 兩軌 LLM 公開決策摘要與真正 producer

**對應 Requirements:** Requirement 190（保存並顯示 LLM 明確公開的全部候選與選擇，不讀私人思考鏈）
**前置任務:** t501、t504、t507
**Liquibase changeset:** 無

## 背景

現有choiceSet僅接受同一Codex工具START由外部exporter明示的publicChoiceSet，workspace沒有producer，且選中項必須精確等於實際toolCall。一般資料來源、報告採納、等待／否決等公開決策不能套成工具已執行。本任務新增獨立公開摘要通路，並真正修改／部署SRPP兩軌入口，不停在UI fixture或producer absent。使用者已授權修改SRPP、要求規則變更重算hash以免下個交易日9090不符，並要求兩repo都commit/no-ff merge/push。

## 要做什麼

- [x] 508.1 **固定closed raw與wire。** RawDecision恰有source、decisionId、topic、options、selectedOptionId；source固定LLM_PUBLIC_SUMMARY_V1，decisionId為[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}，topic為1..100 Unicode code points且既有public-safe。options為1..32，逐項恰有id、label、action、reason、evidenceRefs、tool。id同識別字規則且唯一；label/action為1..100 public-safe文字，action是任意安全字串、禁止新增業務enum。reason為1..500，憑證遮蔽及控制字元清理後超限整筆拒絕，不截斷；私人思考標記拒絕。evidenceRefs為0..3筆既有Evidence(role/id/hash)且沿原closed角色、opaque id與nullable SHA規則。tool為null或closed{name,input}，name沿工具識別字規則，input是公開字串。selectedOptionId為null或存在的option id。FinalDecision五個root keys相同，每option以toolCall取代tool；toolCall非null沿既有Task501六core及sourceTruncated true-only，innerNames=[]、參數單一標準化／遮蔽、16384保存與1048576 prescan，保留原精確截斷metadata。這只是擬議輸入，不能等同actual toolCall。
- [x] 508.2 **有界但不漏選項。** 每raw marker UTF-8≤131072 bytes，FinalDecision所有toolCall.parameters合計≤32768 code points、整物件JSON≤196608 bytes；超額、重複key/id、unknown/deep shape、invalid selected／版本／私人標記必整筆拒絕，collector receipt以無內容的具名code/count揭露，不刪候選、不清空理由冒充成功。通過驗證的全部候選均保存且呈現。reason及tool input只作憑證最佳努力遮蔽，不宣稱完整保密；只收當輪合法scope的使用者明確公開金融摘要，不讀任何reasoning。topic/label/action及event title/summary仍遵守既有public-safe規則；一般摘要不得新增金融數值或原始輸入。
- [x] 508.3 **Codex真producer。** 修改SRPP DAILY_TRADING_AUTOMATION §2C、共用入口srpp-daily.ENTRY及必要權威說明；台股於日曆／Swagger／policy全通過後，美盤22-00於原季節時間gate、本機us日曆／官方核對與原policy檢核通過後，每個對外工具／資料來源／採納決策執行前，LLM獨立公開commentary恰發<<<SRPP_PUBLIC_DECISION_V1>>>、單一RawDecision JSON、<<<END_SRPP_PUBLIC_DECISION_V1>>>（每個delimiter獨立一行），不得有其他本文。列實際公開考慮過的候選、理由、依據與選擇，不要求公開內部思考過程，不承諾全部隱藏考量。collector僅在既有明確commentary通道讀整塊marker，不掃analysis/final/user/tool output，不更改rollout；eventId綁真thread/turn/行號/decisionId，同run重複id同內容冪等、異內容拒絕。不為日曆前／no-op多讀文件、API或新增記錄義務；美盤時間gate失敗同樣完整no-op，不先讀摘要權威文件。
- [x] 508.4 **Claude真producer。** 修改兩個既有Claude入口SKILL，在同樣合法決策點明確公開候選，並在既有當輪final result.json新增選填publicDecisions array，內含相同RawDecision、最多128筆、整個array序列化UTF-8≤3145728 bytes。不得另造觀測HTTP或等待。final result含明確automation_id、有效finished_at，與摘要一次atomic終結寫入後凍結；未終結artifact不匯入候選。collector僅從原consumer=Claude、目錄日／檔內date、slot與automation相符的當輪artifact讀該欄，缺明確automation_id只拒絕摘要並保留既有結果事件。缺cliSessionId維持UNLINKED；有exact app link仍RAW_TRACE_UNAVAILABLE。沿既有raw artifact hash身分與array index/decisionId產穩定事件，不憑相鄰檔、mtime或模型內容猜run。array超額整組拒絕並具名receipt；每筆格式不合法只拒絕該筆並留code，不能宣稱其已記錄。Codex result不重複匯入。舊artifact缺欄不變。
- [x] 508.5 **collector共用路徑。** Codex marker和Claude array共用strict duplicate-key解析、raw驗證、standardize/redact、final-wire驗證。新增Event.publicDecision，只允許OBSERVATION/UNKNOWN，不新增STATE或UNCONFIRMED頂層碼，固定title／summary標「公開決策摘要（自述，未驗證執行）」。事件無actual工具耗時、無金融成功與交易授權推論。既有toolCall/choiceSet契約、舊state/pending/DB一律不回寫、不replay。明確source version是唯一來源；無marker/array固定未記錄，不推導。
- [x] 508.6 **backend/BFF與wire。** observer API升1.4.0，Event optional publicDecision僅允許缺省、不可null、unknown keys拒絕；collector grant僅Codex/CODEX_ROLLOUT或Claude/CLAUDE_ARTIFACT可寫，schedule grant在owner/DB前403。原金融YAML與9090 routes不變。一般Node新增nullable PublicDecisionSummary(decisionId,topic,selectedOptionId,options[id,label,action,selected])，selected只由selectedOptionId導出，不能帶理由、refs、參數。新增ADMIN只讀GET /api/ai-flow/runs/{runId}/nodes/{eventId}/public-decision與同源 /api/bff/ai-flow/.../public-decision；query沿date/consumer、owner/90天/UUID/hash/no-store/timeout規則，回{runId,eventId,publicDecision}，舊／非決策event回null。單一canonical payload不另建表，不更新舊event。
- [x] 508.7 **全部選項可見、明細完整。** 圖以獨立namespace ui-decision:的synthetic候選支線列出全部公開選項，標「已選擇（自述）」／「未選擇」，不畫成工具執行成功。點synthetic只導向真decision event；時間軸提供「查看公開決策摘要」。按需完整detail使用獨立abort/generation fence；理由、evidence refs與擬議tool參數只在明細以Vue文字/pre顯示，不進ECharts/tooltip/console。所有候選含未選項都顯示；固定提示「公開自述；不保證涵蓋所有內部考量，亦不證明工具已執行」。缺資料顯示「未記錄公開決策摘要」。task501截斷／遮蔽文案保留。
- [x] 508.8 **真正部署入口。** Codex既有09:05/12:00兩prompt必與入口代入時段後逐字一致，以App automation_update更新，不改模型/effort/通知/時段/狀態，不觸發。既有22-00美盤prompt本輪必接入同一marker，並在SRPP的docs/schedule_entries/README.md與新us-22-00.PUBLIC_DECISIONS.md保存受版控的觀測指令片段；只追加公開摘要義務，原America/New_York季節目標、±300秒、一次gate、us:false立即結束及US_EMAIL_SCOPE_ONLY逐字保留。Claude兩本機SKILL及已遷移遠端保存prompt均更新、讀回，遵守裝置綁定原生App proof；routine_bind_update_proof_required/缺原生工具能力時保留遠端舊狀態並標BLOCKED_REMOTE_PROOF，不能靠本機檔聲稱遠端已部署；不得因此擋住全部本機repo、Codex既有三automation與Claude本機SKILL等可完成工作，不擴張未知遠端存取。不新增排程、heartbeat、額外金融API、寄信或交易。22-00的真正prompt與權威觀測片段必讀回一致，不能以只接台股日報聲稱本輪完成。
- [x] 508.9 **跨repo發布與hash硬閘門。** 所有變更都從main新基線的managed feature開工，先掃撞檔。SRPP方案文件放spec/而非自動納policy的docs/；先提交方案成乾淨基線。Swagger metadata階段必在乾淨、絕對、非symlink、非primary、非main的SRPP feature執行asset renderer --srpp-root，再sync --srpp-root，接兩者--check --srpp-root；只允許原四固定unstaged產物，不放寬sync dirty/source allowlist。metadata階段提交形成乾淨基線後，才修改SRPP producer權威來源與入口，依原daily_runtime_contract build及policy_bundle build重產、check並記新bundle SHA（保留投資模型/公式/目標不變）；最後copy精確model/manifest進backend authority、原工具重產golden/formal/insert-only registry SQL，check-srpp-report-authority --srpp-root --check通過。不得用metadata-only sync掩蓋其他policy變更。SRPP與asset均feature commit、--no-ff merge至main、push與remote-ref readback後，release operator才執行insert-only SQL，不UPDATE/DELETE歷史；從乾淨main rebuild/recreate business-services/BFF/frontend，jar embedded authority與DB exact registry row讀回同新policy hash/Swagger hash，三份統一Swagger bytes相等。
- [x] 508.10 **下一交易日驗證。** 靜態與部署先完整做完；只讀9090 trading-calendar取得200及availability.tw=AVAILABLE，以days[].twTrading true選下個交易日（不能假設週一）；僅Asia/Taipei目標日09:05≤now<13:30、registry/jar exact新hash均確認後，以同組policyBundleSha256/swaggerSha256對report-calculations唯讀GET核對，不人工POST/replay/觸發報告。未到合法時窗明示NOT_YET_EXECUTED，靜態/healthy不能代替live GET。若新hash沒有FINAL package，具名區分合法context缺口與POLICY/SWAGGER_MISMATCH，不宣稱已有可採納交易建議。

## 驗證

```bash
bash scripts/spec-check.sh
python3 -B -m unittest discover -s scripts/tests -p test_collect_srpp_ai_flow.py
node --test frontend/src/utils/aiFlowView.contract.test.js
ruby scripts/render-ai-flow-openapi-docs.rb --check
ruby scripts/render-9090-openapi-docs.rb --check --asset-only
```

實作時補上實際backend/BFF選定測試類與前端build指令，不宣稱尚不存在測試類。共用fixture驗兩source相同accept/reject；包含marker精確通道、private canary、重複key/id、unknown/deep shape、32/33 options、null選擇、未選項保留、reason/parameter credential canary、超額receipt與no-silent-truncation、Claude身份/finalization/coverage、不可變重跑、grant403零寫入、ADMIN按需hash/no-store與race。SRPP驗入口逐字部署／runtime/policy checks、原日曆停止測試、無觀測HTTP等待。涉及執行的改動依run-stack由feature rebuild/recreate驗收後才merge；merge後main重新rebuild/recreate。正式collector最後restart、原state/pending/token保留，只讀真實新排程收據；fixture不能冒充LLM已產生摘要。

## 完成報告

- 2026-10-24完成實作及獨立spec/arch審查；arch critical/major/minor均0（e211bb7ef14c）。collector175 tests（42共用vectors）、backend64/BFF55與共享vectors、frontend17/build、SRPP runtime27/timing4、兩renderer與spec-check BLOCK0/CHECK0均通過。
- SRPP已依兩階段固定流程完成metadata commit f346c31後才改producer來源；final bundle `f15f77def2c97039daeb1357b0e198113f557d69969f0ae419fb0a081ebe85c0`、Swagger `40819f617271e21b33efbca040134c3024e4915d1d34c735fa3b6a2489d45afe`、runtime `c76ae550bcfc571ab26289cdc8f56740186dc6c78d43ae7920a3b8b78c442ad3`。精確embedded model/manifest、原工具insert-only SQL及22-case golden產生，authority check PASS；模型/公式/配置不變。
- feature僅business-services/BFF/frontend image build+recreate，全部環境與TLS/token mounts與main比對一致。HTTP200實際served AiFlowView-DuKivHeB.js含公開摘要入口，9090 quotes為JSON array，匿名ADMIN明細401。無既有ADMIN瀏覽器session，未虛稱live點擊或真LLM已產生摘要；以離線測試驗功能，不灌假資料。
- 同次發布後續精確Git SHA、main image、registry/jar readback與三個既有Codex prompt/App讀回、Claude本機SKILL部署及遠端BLOCKED_REMOTE_PROOF，由任務最終receipt列出。原collector最後重啟，保留state/pending/token，不replay。
- API已驗下一個TW交易日為2026-10-27（2026共365唯一日期、TW241，AVAILABLE）；現為非合法驗收時窗，report-calculations新hash live GET狀態 `NOT_YET_EXECUTED`。只有當日09:05≤台北時間<13:30才可唯讀GET；不人工POST、觸發報告、寄信或交易。
