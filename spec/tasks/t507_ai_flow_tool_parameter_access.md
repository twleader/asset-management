# [t507] AI 流程直接查閱公開工具與參數

**對應 Requirements:** Requirement 190（管理員從時間軸與慢速步驟查閱公開工具與參數）
**前置任務:** t501、t504
**Liquibase changeset:** 無

## 背景

collector 已於公開工具 START 保存 toolCall，但圖旁時間軸仍顯示「未收錄參數、指令或輸出」，慢速步驟沒有入口。既有按需明細難被找到，且 slowSteps.nodeId 是 END，不能直接向該 id 讀 START 專用參數。

## 要做什麼

- [x] 507.1 修改 `scripts/collect-srpp-ai-flow.py` 的固定內含工具摘要，取消「未收錄參數、指令」斷言，提示公開輸入在節點明細查閱、輸出不保存。更新 `scripts/tests/test_collect_srpp_ai_flow.py` 的精確文案與回歸；title/summary 不帶任何參數內容。tool_call 的原始 input/arguments、憑證遮蔽、清理、16384 code-point 保存與1048576 prescan 不變。不修改已入 state/pending/DB 事件、不重播、不回填。
- [x] 507.2 在 `frontend/src/utils/aiFlowView.js` 新增純 resolver `resolveAiFlowToolStartId(detail,eventId)`。只找 nodes 真 eventId；原節點須 operation.method=TOOL 或 stepId 以 call- 開頭。START 直接回自身；END 限 relation=CALL_RESULT，其 parentEventIds 須恰一筆既有同 stepId 的 START，重複 parent/multiple match 拒絕；其他回空字串。不依 title/summary 推測，不把 synthetic id 或 END 送工具參數 API。
- [x] 507.3 在 `frontend/src/views/AiFlowView.vue` 的時間軸工具事件、慢速表新增「查看工具與參數」。slowSteps.nodeId 是 END，經 resolver 找 START；找不到顯示「未記錄工具開始事件」且不能發請求。按鈕沿用 loader.selectNode 的 forceSelect=true，連續點相同入口不清選取；聚焦／捲動節點明細讓結果可見。原工具參數 BFF 路由與 abort/generation fence 不變，參數不預載、不加入 Node/ECharts/tooltip/時間軸/慢速表資料。
- [x] 507.4 明細標示「公開原始輸入」與輸入長度。toolCall=null 的成功讀取顯示「未記錄工具參數」，不可混同空字串「（無參數）」；loading/error 不顯示 null 提示。exec/run_code 說明整段程式碼是 wrapper 真實輸入，innerNames 僅從文字擷取，不保證逐項已執行，動態 nested 實際參數與逐項耗時未獨立記錄。禁止執行來源、regex 抽字面值當實際參數。truncated 保持「僅前16384」，sourceTruncated 保持「只反映已處理前綴」，redactedCount=100000 保持「至少」，不稱全量。用 Vue 純文字 pre，不 v-html/console，不顯示工具輸出。
- [x] 507.5 同步 `docs/ai-flow-observation.md` 與 `CLAUDE.md` 的公開工具參數說明。此任務具名取代舊不收錄文案與無入口呈現，保留 immutable/按需/sensitive constraints。API、DB、observer YAML、金融 Swagger identity、SRPP 專案/runtime/policy 均不改。
- [x] 507.6 在現有 `frontend/src/utils/aiFlowView.contract.test.js` 加行為測試：START、END→同step唯一START、slowSteps.nodeId=END、不同step、missing/duplicate/multiple parent、非CALL_RESULT、非工具與synthetic拒絕；精確請求START id、forceSelect重點不清空、null/empty/loading/error、race fence仍過。collector測試驗新summary及原始wrapper保存、敏感遮蔽/截斷未退化。
- [x] 507.7 驗收後依專案 run-stack 流程重建/recreate frontend（先核對main.env；不外洩secrets），再 commit/no-ff merge/push；main 重建部署後 restart 原 collector LaunchAgent 載入新程式，保留 plist/token/state/pending，不 replay。唯讀查 daemon receipt 與頁面資產/既有事件；若沒有新排程事件只能報靜態與部署通過，不能假稱新collector live事件已驗證。

## 驗證

```bash
bash scripts/spec-check.sh
python3 -B -m unittest discover -s scripts/tests -p test_collect_srpp_ai_flow.py
cd frontend
node --test src/utils/aiFlowView.contract.test.js
npm run build
```

Docker 驗收依 run-stack skill 執行實際 frontend image build + container recreate，查健康、已serve資產含入口及來源說明。兩個 renderer 僅 `--check --asset-only`，確認既有金融/observer契約 bytes 未變；不寫 SRPP、不人工 POST events、不寄信、不交易。重啟原 collector daemon 後僅回讀 receipt，絕不刪 state/pending。

## 完成報告

- 完成時間：2026-10-24（Asia/Taipei）。公開工具名稱與wrapper原始輸入沿用既有按需明細；新增時間軸／慢速步驟入口、CALL_RESULT→唯一START解析、forceSelect與明細捲動，清楚區分未記錄／空輸入／截斷／遮蔽。
- collector 154 tests、frontend contract 14 tests、Vite build、兩個renderer --check --asset-only、spec-check BLOCK=0/CHECK=0 與diff check均通過；獨立spec審查critical=0/major=0，arch審查critical=0/major=0/minor=0，code receipt 1fd7bd88bab0。
- feature已實際重建/recreate僅frontend，image `sha256:6311cde45dc17f344f63f618ea66ee1631c516cdb479bd8fb6410c4e32014f2c`；HTTP200 index載入的 `assets/AiFlowView-DTUUW-MK.js` 具新入口與明細文案。frontend effective環境與TLS mounts均與main一致；BFF UP、9090 quotes為JSON array。main env僅同步到原無設定的隔離驗收worktree，未覆寫main。
- run-stack依Terra不在delegation可選清單的明定備援繼承主agent；沒有browser控制工具／既有ADMIN session可用，未宣稱已live點擊驗證，互動以contract tests驗證。
- 此完成報告只回填checkbox與驗收receipt；兩段式Git landing、main再次build/recreate及既有collector job重啟的精確SHA／image／daemon回讀，由同次任務最終回報記錄。保留plist/token/state/pending；不人工POST、不replay、不寄信、不交易。新collector文案只有新自然事件才可live驗證，沒有新事件不得宣稱已驗。
