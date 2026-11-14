VERDICT: CHANGES_REQUESTED

## 判定
critical: 0   major: 2   minor: 0

## Findings

### [Major] 缺少 wave state 時，技術止穩事實所需的觀察起點沒有來源
位置：spec/design.md:14009、spec/design.md:14011；spec/tasks/t588_srpp_dividend_stabilization.md:14  
證據：design 將 neutral fact 的 rows 定義為「固定anchor日起至本輪最近完成日」，但 anchor_date／anchor_low_raw 僅列在 neutral state；同段又要求「缺owner state仍會顯示……two-session事實」。Task 588.2 更要求 anchor 日 raw low 等於 immutable anchor_low_raw，仍未定義缺 state 時如何取得該錨點。現有市場 capture 只有 completedSessions、daily 等資料，沒有 wave anchor（SrppReportExtraCaptureAdapter.java:50）。

被違反的出處：Requirement 209 AC3（spec/requirements.md:6989）要求市場資料齊全時仍能發布技術事實，同時禁止自行建立 wave。依目前契約，實作者只能自行選觀察起點，或讓缺 state 一併阻斷技術事實。

修法：明定不依賴 owner state 的市場技術觀察起點、資料來源及輸出適用範圍，並與 wave 綁定的止穩結果區分；缺 wave state 時保留 STABILIZATION_STATE_UNAVAILABLE／0 額。新增「市場資料完整、state 缺失」的正式 module fixture，驗證技術事實仍可輸出且不產生 wave 或可用預算。

### [Major] design 的 RETEST 條件允許在回測當日確認，與需求及任務檔不一致
位置：spec/design.md:14013  
證據：design 只要求「更後low低於前完成日low、全程守anchor且最新close>前close」，未要求確認日嚴格晚於回測日；spec/requirements.md:6990 與 spec/tasks/t588_srpp_dividend_stabilization.md:16 都明定此時間限制。

例如 FIRST 後先出現較高 low，下一完成日同時出現較低 low 與較高 close，便符合 design 的全部 RETEST 條件，卻尚未符合需求要求的後續確認日。

被違反的出處：Requirement 209 AC4，以及 Task 588.4 的「最新確認日嚴格晚於回測日」。

修法：design 補上 `latest_completed_date > retest_date`，並在 fixtures 明確驗證「回測當日即使收高仍等待；後續完成日符合條件才通過」。

## 已查無發現

- **可查證性：** 以 `rg -an` 查到 SrppReportModuleCalculator、DIVIDEND_TOPUP、topupTier、strategicGate 與 canonical 市場欄位；驗證指令引用的腳本及測試類均存在。新 helper／policy 屬未來新增描述，未誤報為不存在的既有功能。
- **內部一致性：** 除上述兩項，已比對適用標的、階段比例、部分成交、跨週防重及含費 sizing，無其他發現。
- **機械證據：** 採用提供的 `BLOCK: 0／CHECK: 0`，未重跑檢查。
- **資料來源：** 已核對 ExtraCaptureAdapter 的 canonical daily／dividends／calendar 來源及既有缺口處理，無新增外部行情直連。無新增 schema 形貌斷言，未以 changelog 取代 db/schema.sql。
- **Controller 越層：** 否，無新增 Controller 存取或領域判斷。
- **DTO 可變性：** 無新增 transport DTO 或 `@Data`／setter 描述。
- **新頁專屬 BFF：** 不適用，無新增頁面。
- **跨頁 BFF／直打 resource／裸 fetch：** 否，無前端呼叫變更。
- **自創共用例外：** 否，未援引此類例外。
- **同義值共用 business API：** 無新增跨頁取值路徑。
- **服務反向依賴／外部行情直連：** 否，pure evaluator 與既有 capture 邊界未見違規。
- **冗餘持久化／衍生值入庫：** 否，本輪未新增持久化。
- **業務分類硬編 enum：** 未新增可管理業務分類；stage／kind 為計算協定碼，無發現。
- **Endpoint 命名：** 不適用，無新增 endpoint。
- **發布與採用界線：** 已對照 CLAUDE.md:510–512；部署、registry 與自然排程採用分開驗收，無發現。

## 自我挑戰

- 第一項並非要求補造生產 state；問題是缺 state 時仍須輸出技術事實，但其必要觀察起點只存在於 state。`資料充分時` 未消除此依賴，因此保留。
- 第二項已確認需求與任務檔皆正確；仍保留，因 design 列出的可執行條件較寬，會使兩端實作或 fixtures 接受不同時序。
- 撤下「topup-state.stabilization 尚不存在即為錯誤斷言」的候選：本輪明確承認來源缺口並要求維持 UNAVAILABLE，屬新增契約，不應誤報。
- 未把未勾選 checkbox、新識別字或尚未執行的發布驗收當成缺陷。全程未修改檔案或執行寫入操作。

