# [t256] 警示觸發匯出改為固定單檔 ＋ 3 天滾動視窗（修正美股交易日被台北午夜切開）

**對應 Requirements:** Requirement 54（警示觸發即時匯出 JSON 到指定目錄；本機一律照寫，Drive 為可選的附加副本）
**前置任務:** t254（本任務修正它的檔案切分方式，其餘設計——事件驅動、本機即時／Drive 合併去抖、owner join、權限、路徑驗證——完全不動）
**Liquibase changeset:** 無（不動 schema）

## 背景

t254 已上線並實測可用，但**檔案切分方式有結構性缺陷**：它以「台北日期」分檔（`alert_triggers_{ownerId}_{yyyyMMdd}.json`，內容為該台北日的觸發）。

**美股交易時段換算台北是 21:30 → 隔日 04:00，橫跨午夜**，所以同一個美股交易日的觸發**必然**被切成兩個檔案。2026-07-29 實測（`stock_alert_trigger` 真實資料，owner=1）：

| trigger id | 代號 | `triggered_at`（紐約牆鐘） | `created_at`（台北牆鐘） | 舊設計落到 |
|---|---|---|---|---|
| 1177 | VT | 2026-07-28 09:30:03 | 2026-07-28 21:30:03 | `_20260728.json` |
| 1178 | QQQ | 2026-07-28 09:30:04 | 2026-07-28 21:30:04 | `_20260728.json` |
| 1179 | VOO | 2026-07-28 09:40:05 | 2026-07-28 21:40:05 | `_20260728.json` |
| 1180 | AMZN | 2026-07-28 12:00:02 | **2026-07-29 00:00:02** | `_20260729.json` |

使用者打開 `_20260729.json` 只看到 AMZN 一筆，其餘三筆在另一個檔。**這是每天都會發生的**（台股 09:00–13:30 ＝ 台北同日、英股 15:00–23:30 ＝ 台北同日，都不跨午夜；只有美股中）。

> **t254 那條「當日一律用 `created_at` 而非 `triggered_at`」的論證本身沒有錯**——用 `triggered_at` 會讓檔名日期與檔案產生日期分家。錯的是「用日期分檔」這個前提：任何以**台北**日期分檔的方案，都無法把一個橫跨台北午夜的**市場**交易日放進同一個檔。本任務換掉的是分檔方式，不是時鐘選擇（篩選仍然用 `created_at`，理由見 256.2）。

**使用者已決定的方案**（2026-07-29）：固定單檔 ＋ 3 天滾動視窗。理由：下游永遠讀同一個檔就拿得到完整的近期觸發，不必猜日期、也不會被市場時區切開。

## 要做什麼

### 256.1 檔名改為固定單檔

- [ ] 256.1 檔名由 `alert_triggers_{ownerUserId}_{yyyyMMdd}.json` 改為 **`alert_triggers_{ownerUserId}.json`**（去掉日期段）。
  - `ownerUserId` **保留**：多使用者共用同一個 Drive 子路徑時不互相覆蓋（Requirement 51 的既有理由），不得以「實務上只有一人啟用」為由省略。
  - `StockAlertTriggerExportService.FILENAME_PATTERN` 同步改為 `alert_triggers_{使用者ID}.json`（前端設定卡直接顯示這個常數）。
- [ ] 256.1.1 **系統一律不刪改版前已寫出的日期檔**（`alert_triggers_1_20260729.json` 等）。那是使用者目錄裡的資料，既有九頁的匯出檔亦然。是否清理由使用者自行決定；前端設定卡不需要提這件事。

### 256.2 查詢改為 3 天滾動視窗

- [ ] 256.2 `StockAlertTriggerRepository.findByOwnerAndCreatedAtInDay(ownerId, start, end)` 改為
  **`findByOwnerAndCreatedAtAfter(ownerId, since)`**（無上界——未來時間不存在，多一個上界只是多一個會寫錯的參數）。
  其餘不變：仍以 `EXISTS` 子查詢對 `StockAlert` / `StockAlertGroup` 兩條路徑取 owner、仍 `ORDER BY t.createdAt ASC`。
- [ ] 256.2.1 **視窗起點＝`今日台北日期 − 2 天` 的 00:00**（今日 ＋ 前 2 個完整日 ＝ 3 個台北日曆日）。
  - **不得寫成「now − 72 小時」**：滾動小時數會讓同一筆觸發隨匯出時刻在檔案裡忽隱忽現——下游兩次讀到不同結果，卻沒有任何事件發生。用日曆日則同一天內每次重寫的視窗一致。
  - 3 天足以涵蓋週末與連假（週五盤中觸發，週一早上讀仍讀得到）。
- [ ] 256.2.2 **篩選仍然用 `created_at`，不可改用 `triggered_at`**。視窗起點是台北時刻，只有 `created_at` 與它同一個時鐘（`triggered_at` 是市場牆鐘：美股紐約、英股倫敦）。拿市場牆鐘比台北時刻，美股會整批位移 12 小時、英股 7 小時，視窗邊界附近的觸發時有時無。
- [ ] 256.2.3 視窗長度以常數表達（`WINDOW_DAYS = 3`），**須可注入以便測試**；不做成 DB 設定欄位（多一個沒人會調的欄位）。

### 256.3 JSON 結構調整

- [ ] 256.3 檔案層級欄位改為：

  ```json
  {
    "ownerUserId" : 1,
    "windowDays" : 3,
    "since" : "2026-07-27T00:00",
    "exportedAt" : "2026-07-29T13:36:08.177256836",
    "triggerCount" : 4,
    "triggers" : [ … ]
  }
  ```

  - **移除 `date`**：固定單檔沒有「這是哪一天的檔」這個語意，留著會誤導下游以為只含那一天。
  - **新增 `windowDays` 與 `since`**：下游才知道「沒有更早的資料」是視窗造成的，不是真的沒觸發過。`since` 為台北時刻。
- [ ] 256.3.1 **`triggers` 陣列內每一筆的欄位完全不變**（`triggerId`／`source`／`alertId`／`groupId`／`stockCode`／`stockName`／`market`／`condition`／`triggeredAt`／`triggeredAtZone`／`createdAt`／`price`／`monthlyMa`／`quarterlyMa`／`annualMa`／`kValue`／`dValue`）。數值仍為 JSON number、指標不足仍為 `null`、條件文案仍取自 `StockAlertService.buildLabel` / `buildGroupLabel`。

### 256.4 措辭與 UI

- [ ] 256.4 `runNow` 在視窗內無觸發時的訊息由「當日尚無觸發…」改為「**近 3 天尚無觸發**，已寫出空的觸發清單（可用於驗證落點）」。
- [ ] 256.4.1 `lastRunStatus` 的「N 筆觸發」語意改為視窗內筆數（字串本身不必改，但註解要對）。
- [ ] 256.4.2 前端 `StockAlertView.vue` 設定卡文案：檔名改為 `alert_triggers_{使用者ID}.json`，並說明「內容為**最近 3 天**的全部觸發，每次觸發覆寫同一個檔案」。
  **要明說「同一個美股交易日的觸發不會被切開」**——那是這次改版的目的，使用者看得到才知道問題已解。
- [ ] 256.4.3 「立即匯出」按鈕的說明由「當日已發生的觸發」改為「近 3 天已發生的觸發」。

### 256.5 不改的部分（明確排除，避免改壞已驗證的行為）

- [ ] 256.5.1 **不動**事件驅動的接入點（`recordTrigger` / `recordGroupTrigger` 之後、`stock_alert_trigger` 寫入之後）。
- [ ] 256.5.2 **不動**本機同步寫 ＋ Drive 非同步 60 秒去抖（含尾端補跑、被合併時不碰兩個 Drive 狀態欄）。
- [ ] 256.5.3 **不動** owner join、`@Filter` 不依賴、per-owner 鎖、tmp ＋ UUID 後綴 ＋ `ATOMIC_MOVE`。
- [ ] 256.5.4 **不動** Drive 權限（`resolveUpdate` 403 / `syncQuietly` 每輪複驗）、路徑驗證、`GdriveSelfCheck` 九表。
- [ ] 256.5.5 **不動** schema（`stock_alert_export_setting` 完全不變，無 changeset）。
- [ ] 256.5.6 **不動** 30 天 DB 清理（`cleanupOldTriggers`）——它與 3 天匯出視窗是兩個獨立概念：DB 留 30 天供警示頁／補發使用，匯出檔只給下游看近期。

### 256.6 測試

- [ ] 256.6.1 **跨午夜的美股交易日不得被切開**（這次修正的探針，缺了它改版等於沒驗）：造兩筆同一美股交易日的觸發，`created_at` 分別為台北 D 日 21:30 與 D+1 日 00:00，斷言**兩筆都在同一個檔案裡**。
- [ ] 256.6.2 **視窗邊界**：`created_at` 為 `今日 − 2 天 00:00` 的觸發**在**檔案內；`今日 − 2 天 00:00` 前一秒的**不在**。
- [ ] 256.6.3 **檔名不含日期**：產出的檔案為 `alert_triggers_{owner}.json`，且連續兩天匯出寫的是**同一個檔**（不會產生第二個檔）。
- [ ] 256.6.4 **JSON 結構**：含 `windowDays` / `since`、**不含** `date`；`triggers` 內每筆的欄位與 t254 完全相同。
- [ ] 256.6.5 t254 既有的 15 條測試中，凡斷言檔名或 `date` 欄位者一併更新；**其餘（owner 隔離、群組 label、未啟用不產檔、匯出失敗不影響觸發、Drive 去抖三條、run-now 語意）必須原樣保留並全綠**——那是這次改版沒有碰到的行為，全綠才證明是局部修正。

## 驗證

```bash
# 1. 後端測試
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true

# 2. 從 main 的 worktree 重建（本任務 merge 後）；worktree 需先有 .env
cp /Users/steven/Project/asset-management/.env .
docker compose -p asset-management build --no-cache business-services frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services frontend
docker compose -p asset-management restart bff

# 3. jar 真的含本次變更
docker exec asset-business-services sh -c 'unzip -l /app/app.jar | grep -i StockAlertTriggerExport'

# 4. 端到端：run-now 後檢查檔名與內容
docker exec asset-business-services sh -c 'curl -s -X POST \
  -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
  http://localhost:8080/api/stock-alerts/export-setting/run-now'

docker exec asset-business-services sh -c 'ls -la /home/steven/input/alert_triggers_*'
docker exec asset-business-services sh -c 'cat /home/steven/input/alert_triggers_1.json' | head -20

# 5. 關鍵驗收：那四筆美股觸發（VT/QQQ/VOO/AMZN，紐約 07-28）必須全部在同一個檔案裡
docker exec asset-business-services sh -c \
  'python3 -c "import json;d=json.load(open(\"/home/steven/input/alert_triggers_1.json\"));\
print(d[\"triggerCount\"], [t[\"stockCode\"] for t in d[\"triggers\"]])"' 2>/dev/null || \
docker exec asset-business-services sh -c 'grep -o "\"stockCode\" : \"[^\"]*\"" /home/steven/input/alert_triggers_1.json'

# 6. DB 側對照：視窗內該 owner 應有幾筆
docker exec asset-postgres psql -U assets -d assets -c \
  "SELECT t.stock_code, t.market, t.triggered_at, t.created_at
     FROM stock_alert_trigger t
     LEFT JOIN stock_alert a ON a.id=t.alert_id
     LEFT JOIN stock_alert_group g ON g.id=t.group_id
    WHERE COALESCE(a.owner_user_id, g.owner_user_id) = 1
      AND t.created_at >= (CURRENT_DATE - INTERVAL '2 days')
    ORDER BY t.created_at"
```

**必須確認的回歸：**

1. **舊的日期檔仍在、未被刪除**（`alert_triggers_1_20260729.json`）。
2. **Drive 同步仍正常**：run-now 回應的 `gdriveStatus` 為「成功：…」，且 Drive 端檔名為新的 `alert_triggers_1.json`。
3. **警示觸發的既有三個出口未受影響**：`last_triggered_*` 覆寫、`stock_alert_trigger` 新增、email enqueue。
4. **owner 隔離**：以 user 2 打 run-now，其檔案不含 user 1 的標的。

## 完成報告

（實作者做完後回填：實際改了哪些檔、測試輸出、run-now 產出的檔名與內容、四筆美股觸發是否同檔的實測、與原計畫的偏差及原因。）
