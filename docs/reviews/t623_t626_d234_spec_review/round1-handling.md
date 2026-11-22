# Round 1 審查處理紀錄（D-234 spec，t623–t626）

Worktree：`/private/tmp/asset-d234-calc-migration`（branch `claude/srpp-d234-calc-migration`）。只改 spec 檔，沒有改程式碼。修完後 `bash scripts/spec-check.sh` 結果為 BLOCK 0 / CHECK 0。

10 項 finding 都對照實際程式碼確認屬實，沒有誤報。

## [Major] t626 決定 7（00865B 緊急腿補價候選）在正式報告路徑上不可達：`SUBACCOUNT_UNVERIFIED` 永遠與報價缺口並存

**查證：屬實。** 依據如下：

- `DecisionInputCaptureAdapter.java:80` 把 `subaccounts` 硬編為 UNAVAILABLE/`SUBACCOUNT_UNVERIFIED`。
- 同檔 :113-114 對 00865B 的 pending 與 position 都取子帳證據。
- `SrppReportFubonOverlay.java:21` 又強制覆寫 00865B 的 pending。
- `SrppReportFubonCapturePort.java:15` 的 `subaccounts()` 恆為 UNAVAILABLE。
- `DailyDecisionCalculator` 的緊急段會重用 00865B 的全部收據。

結果是 00865B 的兩條腿永遠帶 `SUBACCOUNT_UNVERIFIED`，不可能成為補價候選。

**處理方式：** 保留決定 7 的 Java 變更，把前提寫明在各處，並要求以合成輸入測試。沒有另立子帳來源任務。

- t626:35（決定 7）加前提段：正式資料上暫不生效，完成報告須如實註明。
- t626:76（626.5）新增前提子項：
  - 子帳來源驗證前，00865B 的 STRATEGIC_FUBON 與 EMERGENCY_CATHAY 兩腿恆含 `SUBACCOUNT_UNVERIFIED`，都不是候選。
  - 現行正式包能成為候選的債券腿只有 00719B／00697B 的 STRATEGIC_FUBON。
  - 實作不得對 00865B 特判。
  - 測試用合成的 verified-subaccount／pending `Input`。
- t626:162（626.20）新增前提項，並要求測試全部用合成 verified-subaccount `Input`。另加一案，用現行 adapter 形狀證明 00865B 仍非候選。
- t626:196（情境 22）改為合成 verified-subaccount `Input`，並加「現行 adapter 形狀 → 非候選」一案。
- t626:224（完成報告）必須註明此變更在正式資料上尚未生效。
- requirements.md Requirement 223 的處理順序條（約 :7327）加入前提，並註明只有合成輸入測試。
- design.md Requirement 223 的 `DailyDecisionCalculator` 段（約 :14340）加入前提，並註明目前正式可補價的債券腿。

## [Major] t626 626.20 情況 B 不可達，且情況 A 測試與情境 22 使用 `DailyDecisionCalculator` 從不產生的 `LIVE_QUOTE_STALE`

**查證：屬實。** 依據如下：

- `D-195` 收據在 `!q.suitable()` 時為 BLOCK、reason=`q.reason()`，而且不在緊急段的排除集合（`TARGET`、`D-130_CAPACITY`、`D-195_SELECTION`），所以一定會進入 `eb`。
- `grep LIVE_QUOTE_STALE service/srpp/decision` 沒有結果。這個碼只出現在 `SrppReportModuleCalculator.java:406` 的 `quoteFreshness`。
- 債券腿實際產生的報價缺口碼只有三個：`LIVE_PRICE_UNVERIFIED`（:37）、`BEST_FIVE_UNVERIFIED`（:38）、`PREMIUM_OR_PRICE_UNVERIFIED`（`quality`）。

**處理方式：**

- t626:77（626.5）註明兩點：
  - `DailyDecisionCalculator` 實際只產生上述三碼。
  - `LIVE_QUOTE_STALE` 只出自 `quoteFreshness`；`FUBON_*` 會被 overlay 覆寫。
- t626:163-166（626.20）：
  - 寫明本類別只產生三碼。
  - 原情況 A 改為唯一分支。
  - 刪除情況 B，並在 :165 說明它為什麼不可達。
  - 「其他情況」的列舉改為 `eb` 空但 capacity 0。
- t626:168（測試）改用三碼中各自為唯一原因的案例，加上 `{LIVE_PRICE_UNVERIFIED, PREMIUM_OR_PRICE_UNVERIFIED}` 並存一案。
- t626:196（情境 22）唯一原因改為 `LIVE_PRICE_UNVERIFIED`。
- design.md Requirement 223 註明三碼，並說明原 B 分支已刪除。

## [Major] t623（及 t625）要求正數腿 PASS 收據引用來源，但未規定來源非 VERIFIED 時降級；BFF 會拒絕整包

**查證：屬實。** 依據如下：

- `SrppReportResponseValidator.java:223`：ACTIONABLE 腿中非 d130Optional 的收據，inputRefs 必須全在 `verifiedSources`，否則 `fail()`。
- `SrppReportExtraCaptureAdapter.assess` 在 quote 或 dividends 無效時，會把 `market:*` 判為 UNAVAILABLE（:44 會把 dividends 置 null）。
- 既有 DIVIDEND_TOPUP 在 PASS 前會先查 `sourceVector.ledger.validation`。

**新增規則（t623）：**

- **PASS 前提**（t623:87，623.12）：計算為正時，收據的實際 refs 在 `sourceVector` 必須全為 VERIFIED。不成立時：
  - 收據為 UNAVAILABLE `SOURCE_NOT_VERIFIED:<ref>`。
  - 腿改為 BLOCKED、0 量，module missing 同列此碼。
  - facts 維持純計算結果，golden 不受影響。
  - 計算為 0 時不加此碼，保留 626 的 `["QUOTE_UNVERIFIED"]` 候選判定。
  - `FX_CONVERSION` 適用同一前提。
- **mids 的處理**：選擇保留與 Python 的上限等價。Python 在 FX 未知時視為減半，上限仍為正。
  - t623:75（623.9）：mids 或 `fx` 任一非 VERIFIED 時，`usd_twd_rates=[]`，也就是減半、`fx_status=UNVERIFIED_ASSUMED_HALVED`。`fx` 非 VERIFIED 時 spot 也是 null（VT 會得 `FX_CONVERSION_UNVERIFIED`）。這比參考實作保守，列為已知差異。
  - t623:86（623.12 refs）：
    - 必含集合：009826 為 `{policy, assets, satellite-build-fills, market:台股:009826}`；VT 為 `{policy, assets, fx, satellite-build-fills, market:美股:VT}`。
    - `usd-twd-month-mids`（以及 009826 的 `fx`）只在 VERIFIED 時列入。
    - values 新增 `fxStatusVerified`（BOOLEAN），用來揭露 fx_status。
- **BFF 精確 refs 改寫**（t623:107，623.17）：
  - 必須包含必含集合。
  - 可選來源只能是上述兩項；已驗證的不得漏列，未驗證的不得列入。
  - 保留既有「全在 verifiedSources」的檢查，並接受 `fxStatusVerified`。
- **0 量腿說明**：t623:92（623.13）補上 `SOURCE_NOT_VERIFIED` 列表的情形。
- **測試**（t623:124，623.22）：
  - mids UNAVAILABLE 時減半、可為 ACTIONABLE、refs 不含 mids、`fxStatusVerified=0`，並通過 BFF。
  - 這取代審查者建議的「不得 ACTIONABLE」測試，因為採用的是不同但等價的選項。
  - market 來源 UNAVAILABLE 且計算為正時，腿為 0/BLOCKED。
  - 計算為 0 時不加碼。
- **t625 套用同一前提：**
  - t625:92（625.11）列為唯一例外碼。
  - t625:102 新增 PASS 前提段，含 VT 的 `market:美股:VT`。
  - t625:149（625.16 BFF 測試）加入此碼。
  - t625:223（625.24）新增來源未驗證的測試案例。
- **連帶修改 t626:116（626.9）**：009826 重跑時，刷新 frame 的 market validation 以 `assess` 重算，再套用同一前提。
- **requirements 與 design 同步：**
  - requirements.md Requirement 220 AC4、AC5（:7293-7294）；Requirement 222 新增一條（:7317）。
  - design.md Requirement 220 純計算與模組段（:14297-14298）；Requirement 222 的 PASS 前提（:14326）。

## [Major] t625 驗證區塊先重建並重啟容器，與同檔 625.19 的發布順序及 t623／t626 的重建時點衝突

**查證：屬實。** 原 t625 驗證區塊的 docker／curl 三行，位於 625.19 規定的發布順序之前，而且是從實作 worktree 執行。

**處理方式：**

- 從驗證區塊刪除這三行，並在 t625:242 註明驗證區塊不含任何重建。
- 三行移到 t625:250-256 的「正式發布」段，並明定順序：SRPP verifier → 規則包 → authority check → insert-only registry row 登錄。重建只在 registry row 登錄之後、從乾淨 main 執行。

## [Minor] t625／t626 驗證指令的 SRPP root 與「同一 D-234 worktree root」定案互相矛盾

**查證：屬實。** 依據如下：

- `check-srpp-report-authority.py:211` 的 `EMBEDDED_MODEL` 需要位元組完全相同。
- 同檔 :216 用同一個 root 執行 `generate-srpp-formula-v3.py --check`。
- main 與 worktree 的 `execution_policy.py` 內容不同。

**處理方式：**

- t625:238-239 與 t626:216-217 把 root 改成 D-234 SRPP worktree 的絕對路徑。
- 兩處都註明：這條指令要在 623.19 的 Swagger identity sync 之後才會通過，實作期間不列為必過項。

## [Minor] t626 626.5／626.19／Requirement 223：SRPP `QUOTE_REFRESH_GAPS` 不含 `QUOTE_UNVERIFIED`，「與參考實作逐欄比對」的已知差異與 SRPP 待辦漏列

**查證：屬實。** SRPP `trade_blocker_policy.py:42-43` 是六個 D-230 碼；`quote_refreshable(row)`（:199）要求原因集合 ⊆ 這六碼。

**處理方式：**

- t626:79（626.5）新增已知差異子項。
- t626:157（626.19）新增 SRPP 待辦：`QUOTE_REFRESH_GAPS` 或 `bind_api_leg` 要對 009826 建倉腿納入 `QUOTE_UNVERIFIED`。
- t626:184（情境 5）註明候選判定是 Java 規則，不以 `quote_refreshable` 比對。golden 改以「該零腿 row 的 `quote_refreshable(row)` 為 False」斷言這個差異。
- requirements.md Requirement 223 末條列為第二項已知差異與 SRPP 待辦。
- design.md Requirement 223 同步。

## [Minor] t626 626.11／626.12／626.16：被共用資金擋下而重判的腿，`baseLots` 對外語意未定

**查證：屬實。** `sharedFunds`（`SrppReportModuleCalculator.java:74` 起）會把 lots 歸 0 並清空價量。

**處理方式：**

- t626:134（626.11）：重判腿對外 `baseLots`／`baseUnits` 輸出取回的原量，並帶 `redecided:true`；其他腿為 false。SRPP 重播用同一個 base。
- t626:135（626.12）：不變量改為對照對外輸出的 base（`redecided` 腿就是取回的原量）。補價候選維持原有例外。
- t626:151（626.16）：leg 鍵清單加入 `redecided`。
- t626:157（626.19）：重播改用回應的 base。
- t626:201（情境 18）加上 `redecided`／base 的斷言。
- requirements.md 與 design.md 的 Requirement 223 同步。

## [Minor] t623 623.3 兩處敘述與既有程式不符

**查證：屬實。** 依據如下：

- `PublicTransactionHistoryService.java:84-88` 只在 `exchangeRate` 為 null 時退回 `amount`。
- BFF `UsdTwdPublicService.validateRates`（:252-256）另外拒絕 `buy > sell`，而且驗的是兩年窗（:134）與 live 回應（:198）。

**處理方式：**

- t623:32：更正為「只有 null 退回 amount；≤0 得 0 或負值」，並說明兩種情形各由哪一個條件擋下。
- t623:34：mids 有效性補上 `buyRate > sellRate`。驗證窗的差異選擇「列為已知差異並加 golden 斷言」。
- t623:76（623.9）新增兩個 golden 案 `api009826_/apiVt_mids_out_of_month_bad_row_known_difference`：
  - `expected` 為 Python 收到 502 時的減半結果。
  - `javaExpected` 為 Java 依當月有效列計算的結果。
  - 標記 `KNOWN_DIFFERENCE_MIDS_WINDOW`。
- t623:124（623.22 擷取測試）加入 `buy>sell` 案例，以及「當月以外壞列不影響本來源」案例。
- requirements.md Requirement 220 AC4 與 design.md Requirement 220 純計算段同步。

## [Minor] t626 626.9／626.11／626.14：helper「package-visible」跨 package 不足

**查證：屬實。** 下列成員都不在呼叫端可見的範圍：

- `DecisionInputCaptureAdapter.book`（:154）是 private static，`normalize`（:162）是 package-private。
- `DailyDecisionCalculator.cost` 是 private static。
- 呼叫端位於 `service.srpp.report` package。`SrppReportModuleCalculator.normalize`（:34）是 public。

**處理方式：**

- t626:115（626.9）：`book` 與 `Quote` 建構改為 decision package 的 public static helper。
- t626:127（626.11）：`DailyDecisionCalculator.cost` 改為 public static；report package 內的 Topup／MediumDip／ShortUs 維持 package-private。
- t626:146（626.14）：改用 public 的 `SrppReportModuleCalculator.normalize`。
- design.md Requirement 223 同步。

## [Minor] t626 626.17 reason code「封閉清單」與「另透傳原 blockingReasons」自相矛盾

**查證：屬實。** v1 的 `SrppOrderPlanLeg.reasonCodes`（`docker-external-api.yaml:17023`）是 `items: type: string`。

**處理方式：**

- t626:152（626.17）改寫：`reasonCodes` 維持自由字串、不加 enum，新碼只在 description 文字中列舉。
- t626:151（626.16）：`funding` 每個幣別的鍵加入 `reasonCodes`（恆存在，已知時為 `[]`）。
- design.md Requirement 223 同步。
