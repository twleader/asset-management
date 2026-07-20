# [t229] 濾除南海小型海上摩擦新聞（`EditorialNewsFilter` 新增 `SCS_SKIRMISH` 否決集）

**對應 Requirements:** Requirement 31（今日股市分析——每交易日於可設定時點由 AI 判斷當天台股走向；本任務收斂餵給分析的本地新聞收錄範圍，濾掉對台股大盤與全球經濟無實質影響的南海小型海上摩擦雜訊）
**前置任務:** 無（Task 199 建立 `EditorialNewsFilter` cascade、Task 221 加入 `ANECDOTE`／`SOCIAL_ODDITY` 兩條否決集，皆已在 main；本任務在其上疊加第三條否決集，屬同一模式的延伸。歷史脈絡見 `spec/tasks/archive/tasks-151-200.md` Task 199）
**Liquibase changeset:** 無（純關鍵詞邏輯變更，不新增資料表／欄位；`news_headline` 結構不變）

## 背景

**現在的錯誤行為：** 使用者於 2026-07-20 在「爬蟲資訊查詢」頁看到一則實際爬進 `news_headline` 的雜訊：

```
2026-07-20 19:05   新聞   ltn   TW   中國海警南海持棍傷人 菲律賓海軍1人遭打傷
```

這是南海海警／海軍間的低烈度肢體摩擦，對台股大盤與全球經濟毫無實質影響，卻被 `EditorialNewsFilter` 收錄、進而佔用「今日股市分析」的本地新聞 prompt 額度（`LOCAL_NEWS_MAX=40`）。

這則新聞來自自由時報（`source=ltn`），屬台灣「混合型」feed，會經過 `EditorialNewsFilter.retain()` 的關鍵詞優先序 cascade。其判定路徑（`external-materials-service/src/main/java/com/steven/assets/externalmaterials/client/EditorialNewsFilter.java` 的 `trace()`）：

- 規則 0（`isAnecdote`）：標題無「N歲」→ 跳過
- 規則 1（`FINANCE`）：標題無任何財經詞 → 不保留
- 規則 1b（`SOCIAL_ODDITY`）／規則 2（`LIFESTYLE`）：無命中 → 跳過
- **規則 3（`CHINA`）：命中「中國」「南海」→ 進中國分支；再命中 `BEIJING_REGIME` 的「海警」→ 回傳 `KEEP:china-regime`** ← 就是在此被收錄

目前 cascade 內**沒有任何規則會濾掉南海海上摩擦新聞**，也沒有「重大事件 vs 小型摩擦」的區分。

**正確行為（本任務）：** 新增第三條否決集 `SCS_SKIRMISH`（新規則①c），置於**財經豁免（規則①）與社會獵奇否決（規則①b）之後、生活否決（規則②）與中國判定（規則③）之前**，以三重 AND 守門濾掉南海小型海上摩擦，同時保留真正可能撼動市場或具地緣意義的南海新聞（封鎖航運、油運咽喉、重大軍事升級、南海仲裁／部署等）。比照 Task 221 `SOCIAL_ODDITY` 的最小爆炸半徑手法。

**設計約束的來源（對線上 3501 則真實 `news_headline` 全量抽驗得出，務必遵守）：**

1. **三重 AND 守門，缺一不濾**——只用「南海地區詞」單一條件會過廣；只用「摩擦詞」單一條件會誤殺荷姆茲對峙、不動產扣押等一堆非南海新聞。必須三者同時成立。
2. **「中南海」子字串陷阱**——「中南海」是中共領導層駐地，與南海（South China Sea）無關（實際語料「揭GDP真相觸怒中南海！ WSJ：習近平令蔡奇動手…」）。判定南海地區詞前**必須先 `replace("中南海","")`**，否則會把中共高層新聞誤判成南海摩擦。
3. **金門摩擦刻意不納入**——語料中「中國公務船夜闖金門海域…強勢驅離」「巴威剛走！中國4艘海警船又闖金門限制水域 海巡強勢驅離」屬台海／金門戰區、直接涉台灣安全，維持收錄；本規則只鎖定使用者指定的「南海」。
4. **刻意排除的觸發詞**（實測會誤傷或過廣，勿加入摩擦詞集）：`對抗`／`巡弋`／`侵襲`／`灰色`（打到「美調6艘海防隊…對抗中國在台海、南海灰色侵襲」「美海防隊艦艇加入南海巡弋」等真正的美軍部署／地緣政治）、`衝突`（`GEO_TRIGGER` 已用於正當地緣政治，「武裝衝突／利益衝突」過廣）、`軍演`／`軍事`／`海警`／`軍艦`（是事件主角而非「小」摩擦的標記，重大軍演具市場訊號意義，留給規則③）。

## 要做什麼

### 229.1 `EditorialNewsFilter.java`：新增三個關鍵詞集與判定 helper

檔案：`external-materials-service/src/main/java/com/steven/assets/externalmaterials/client/EditorialNewsFilter.java`

在既有 `SOCIAL_ODDITY` 集合（目前在第 221–224 行）之後、`retain(...)` 方法之前，新增三個 `static final Set<String>`：

```java
    // ===== 南海小型海上摩擦（Task 229）：置於 FINANCE／SOCIAL_ODDITY 之後、LIFESTYLE／CHINA 之前 =====
    // 「中國海警南海持棍傷人 菲律賓海軍1人遭打傷」這類南海海警／海軍低烈度肢體摩擦，對台股大盤與全球經濟
    // 無實質影響，但命中 CHINA(南海)＋BEIJING_REGIME(海警) 會在規則③被判 KEEP:china-regime 收錄。改以本
    // 否決集在規則③之前攔下。三重 AND 守門（缺一不濾）：南海地區詞 ∧ 低烈度摩擦詞 ∧ ¬重大升級詞。
    //
    // 對線上 3501 則真實 news_headline 全量抽驗：含南海地區詞者 8 則僅翻轉 1 則（使用者回報案）為 DROP、
    // 其餘 7 則（南海仲裁 14 國聯署／美海防隊南海巡弋／官媒 AI 影片酸菲／觸怒中南海 GDP…）維持 KEEP；
    // 含摩擦詞但無南海地區詞者 9 則（荷姆茲對峙／金門驅離×2／不動產扣押…）全不受影響。零反向翻轉。
    //
    // 刻意排除的觸發詞（實測會誤傷，勿加入 SCS_SKIRMISH）：對抗／巡弋／侵襲／灰色（打到「美海防隊…對抗
    // 中國在台海南海灰色侵襲」「美海防隊艦艇加入南海巡弋」等美軍部署／地緣政治）、衝突（GEO_TRIGGER 已用於
    // 正當地緣政治，且武裝衝突／利益衝突過廣）、軍演／軍事／海警／軍艦（是事件主角而非「小」的標記，重大
    // 軍演具市場訊號意義，留給規則③）。金門摩擦屬台海戰區、直接涉台灣安全，刻意不納入（只鎖南海）。
    private static final Set<String> SCS_FEATURE = set("黃岩島", "仁愛礁", "斯卡伯勒");
    private static final Set<String> SCS_SKIRMISH = set(
            "持棍", "棍棒", "木棍", "水砲", "水炮", "噴水", "射水", "水柱",
            "對峙", "驅離", "驅趕", "擦撞", "碰撞", "衝撞", "撞船", "撞擊",
            "登船", "登檢", "攔檢", "臨檢", "扣押", "扣船", "查扣",
            "雷射", "激光", "潑漆", "鳴笛", "傷人", "打傷", "受傷", "打人");
    private static final Set<String> SCS_ESCALATION = set(
            "開戰", "宣戰", "開火", "交火", "砲擊", "炮擊", "擊沉", "擊落", "擊毀",
            "空襲", "轟炸", "飛彈", "導彈", "魚雷", "封鎖", "禁運", "斷航", "動員", "戰爭");
```

並在 `isAnecdote(...)` 附近（其他 private 判定 helper 旁）新增：

```java
    /**
     * 南海小型海上摩擦判定（Task 229）：南海地區詞 ∧ 低烈度摩擦詞 ∧ ¬重大升級詞，三者皆成立才為真。
     * 南海地區詞須先剝除「中南海」（＝中共領導層駐地，與 South China Sea 無關）再比對「南海」子字串。
     */
    private static boolean isSouthChinaSeaSkirmish(String t) {
        boolean scsRegion = t.replace("中南海", "").contains("南海") || containsAny(t, SCS_FEATURE);
        return scsRegion
                && containsAny(t, SCS_SKIRMISH)
                && !containsAny(t, SCS_ESCALATION);
    }
```

**約束：**
- 三個集合與 helper 必須完全照上面的內容，不得增刪關鍵詞（每一個詞的取捨都經真實語料驗證，尤其**不得**把 `對抗`／`巡弋`／`侵襲`／`衝突`／`軍演`／`海警`／`軍艦` 加進 `SCS_SKIRMISH`）。
- `isSouthChinaSeaSkirmish` 內判定南海地區詞**必須先 `t.replace("中南海","")`** 再 `contains("南海")`——這是本任務唯一一個非過不可的正確性關卡（中南海子字串陷阱）。
- 沿用既有 private helper `set(...)` 與 `containsAny(...)`，不要新增等價的工具方法。

### 229.2 `EditorialNewsFilter.trace()`：在 cascade 插入規則①c

在 `trace()` 方法中，緊接既有的 `SOCIAL_ODDITY` 那一步（目前是第 265 行 `if (containsAny(t, SOCIAL_ODDITY)) return "DROP:social-oddity";`）之後、`LIFESTYLE` 那一步（目前第 268 行）之前，插入：

```java
        // 1c) 南海小型海上摩擦否決（Task 229）：南海地區詞＋低烈度摩擦詞＋未升級 → 濾。置於財經／社會獵奇
        //     之後、生活／中國之前，才攔得到原在規則③被 KEEP:china-regime 收錄的「南海海警持棍傷人」類雜訊。
        //     升級為真正衝突（開火／飛彈／封鎖…）者由 SCS_ESCALATION 守門救回、落回規則③以 KEEP:china-regime 收錄。
        if (isSouthChinaSeaSkirmish(t)) return "DROP:scs-skirmish";
```

**約束：**
- 插入位置**必須**在 `FINANCE`（規則①，第 261 行）之後——財經永遠最先贏，含南海但同時是財經（如「南海封鎖衝擊航運、油價飆漲」命中 `航運`／`油價`）者仍被 `KEEP:finance` 先攔下。
- 插入位置**必須**在 `CHINA`（規則③，第 272 行）之前——否則「中國海警南海持棍傷人」會先被 `KEEP:china-regime` 攔下，本規則永遠沒機會發言。
- 不得更動既有規則 0／1／1b／2／3…的順序與回傳字串。

### 229.3 `EditorialNewsFilterTest.java`：新增回歸錨點

檔案：`external-materials-service/src/test/java/com/steven/assets/externalmaterials/client/EditorialNewsFilterTest.java`

在既有最後一個 `@Nested`（`SocialOddity`）之後，新增一個 `@Nested` 類別（沿用檔內既有 `assertKeep`／`assertDrop` 靜態 helper）：

```java
    @Nested
    @DisplayName("南海小型海上摩擦（Task 229）")
    class SouthChinaSeaSkirmish {
        @Test void 南海低烈度摩擦濾除() {
            assertDrop("中國海警南海持棍傷人 菲律賓海軍1人遭打傷");   // 使用者回報案例
            assertDrop("中菲南海對峙 海警噴水驅離菲補給船");
            assertDrop("仁愛礁再起衝突 中國海警登檢並扣押菲漁船");     // 熱點礁名觸發、衝突為中性詞不救不殺
            assertDrop("黃岩島風波 中菲海警船擦撞互指責任");
        }
        // 南海重大事件（仲裁／部署／升級至開火）具市場或地緣意義，不得誤殺
        @Test void 南海重大事件與政治不誤殺() {
            assertKeep("14國聯署挺南海仲裁 南韓缺席遭韓媒酸「看中國眼色」");   // 語料實例
            assertKeep("嚇阻中國 美海防隊艦艇加入南海巡弋");                   // 語料實例：巡弋刻意排除
            assertKeep("美調6艘海防隊巡邏艦前進星、菲 對抗中國在台海、南海灰色侵襲"); // 語料實例：對抗/侵襲/灰色刻意排除
            assertKeep("南海對峙升級 中菲軍艦開火互射示警");                   // 開火＝SCS_ESCALATION 守門救回
            assertKeep("南海封鎖衝擊全球航運 國際油價飆漲");                   // 封鎖＋航運/油價 → 規則①KEEP:finance
        }
        // 中南海＝中共領導層駐地，非南海(South China Sea)；金門摩擦屬台海戰區、非南海
        @Test void 中南海與金門不誤殺() {
            assertKeep("揭GDP真相觸怒中南海！ 習近平令蔡奇動手");             // 語料實例：中南海子字串＋GDP財經
            assertKeep("中國公務船夜闖金門海域 海巡漏夜併航監控、強勢驅離");     // 語料實例：金門非南海
            assertKeep("巴威剛走！中國4艘海警船又闖金門限制水域 海巡強勢驅離");   // 語料實例：金門非南海
        }
        // 低烈度摩擦詞出現在非南海情境時，本規則不得誤觸（AND 守門靠南海地區詞）
        @Test void 摩擦詞在非南海情境不誤觸() {
            assertKeep("荷姆茲因美伊對峙實質封鎖 船東坦言陷入最壞情勢");       // 語料實例：對峙＋荷姆茲＝油運咽喉地緣政治
            assertKeep("中聯致癌油品案 四家公司不動產遭扣押");                 // 語料實例：扣押＋不動產＝財經
        }
    }
```

**約束：**
- 這些標題多數取自線上真實語料（上方註明「語料實例」者），少數為代表性合成標題；不得為了讓測試過而竄改關鍵詞集。
- 若任一 `assertKeep`／`assertDrop` 失敗，代表關鍵詞集或 cascade 位置有誤，**修的是實作、不是把測試放寬**。

### 229.4 不需要改動的地方（明確界定範圍，避免過度施工）

- **不改 `NewsFetchClient`**：`EditorialNewsFilter.retain()` 已在既有各混合型 feed（自由時報財經／政治／國際、經濟日報）抓取後套用；本規則自動隨之生效，無新增接線。
- **不改 backend／bff／frontend**：過濾發生在 `external-materials-service` 落庫之前，前端「爬蟲資訊查詢」與「今日股市分析」只讀已過濾結果，無契約變更。
- **不追溯清除舊列**：與所有既有過濾規則一致，本規則只在抓取時作用；已入庫的舊「持棍傷人」列不會被回溯刪除（`news_headline` 30 天保留期到期自然滾出）。
- **無 `@Scheduled` 變更**：不涉及排程，`SchedulePublicBffController.JOBS` 不動。

## 驗證

**單元測試（本規則的權威功能驗證；`external-materials-service` 為獨立 Maven module，須 `cd` 進該目錄，無 root pom）：**

```bash
cd external-materials-service && \
  /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q test -Dtest=EditorialNewsFilterTest && cd ..
```
（`EditorialNewsFilterTest` 為純靜態呼叫、不使用 Mockito，毋須 `-DargLine` byte-buddy 參數。斷言 `Failures: 0, Errors: 0`，且新 `@Nested SouthChinaSeaSkirmish` 4 個測試方法全過。）

**整模組建置：**
```bash
cd external-materials-service && \
  /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q package && cd ..
```

**跑起來真的有這個功能（本專案無 dev server，image rebuild + container recreate 才算改好；見 `.claude/skills/run-stack`）：**
```bash
# 從 worktree 跑 compose build 前，先把主 repo 的 .env 複製進來（env_file 相對 compose 檔解析）
cp /Users/steven/Project/asset-management/.env . 2>/dev/null || true
docker compose -p asset-management build --no-cache external-materials-service
docker compose -p asset-management up -d --no-deps --force-recreate external-materials-service
# 健康檢查（external-materials-service 無對外埠，健康端點為容器內 /internal/health）：
docker inspect --format '{{.State.Health.Status}}' asset-external-materials-service   # 期望 healthy
```

**部署後對「未來抓取」的行為確認（非追溯）：** 重建後的下一輪 `NewsPoller`（08:20／11:30／18:00 Asia/Taipei）起，形如「中國海警南海持棍傷人 菲律賓海軍1人遭打傷」的南海小摩擦即不再落入 `news_headline`；已存在的舊列不受影響（維持既有過濾層語意）。可於容器日誌確認無例外，落庫筆數正常。

## 完成報告

**實際改動檔案：**
- `external-materials-service/src/main/java/com/steven/assets/externalmaterials/client/EditorialNewsFilter.java`：新增 `SCS_FEATURE`（3 詞）／`SCS_SKIRMISH`（31 詞）／`SCS_ESCALATION`（19 詞）三個 `Set`、`isSouthChinaSeaSkirmish(String)` helper，並在 `trace()` cascade 於 `SOCIAL_ODDITY`（規則①b）之後、`LIFESTYLE`（規則②）之前插入規則①c `if (isSouthChinaSeaSkirmish(t)) return "DROP:scs-skirmish";`。
- `external-materials-service/src/test/java/com/steven/assets/externalmaterials/client/EditorialNewsFilterTest.java`：新增 `@Nested SouthChinaSeaSkirmish`（4 個測試方法、13 個斷言）。
- `spec/requirements.md`（Requirement 31 新增 Task 229 驗收準則）、`spec/design.md`（EditorialNewsFilter cascade 補規則①c）。
- 不改 `NewsFetchClient`／backend／bff／frontend（如計畫）；無 Liquibase changeset、無 `@Scheduled` 變更。

**驗證輸出：**
- 單元測試 `EditorialNewsFilterTest`：`Tests run: 26, Failures: 0, Errors: 0`（新 `SouthChinaSeaSkirmish` 4 方法全過、既有 22 個零回歸）。
- `docker compose build --no-cache external-materials-service`：`BUILD SUCCESS`；`grep -a` 驗證新 image 的 `EditorialNewsFilter.class` 常數池含 `scs-skirmish`／`中南海`／`南海`／`持棍`／`黃岩島`／`仁愛礁`／`斯卡伯勒`／`驅離`（非 stale jar）。
- `docker compose up -d --force-recreate external-materials-service`：新容器 15:39 UTC 起，`docker inspect … Health=healthy`，開機 warmup 乾淨（`NewsPoller warmup：upsert 351 則、失敗 0`），無例外。
- **端到端確認（運行中 stack）**：warmup（台北 23:39）重抓 ltn-world feed 並把通過項目的 `fetched_at` 刷新為 23:39；「中國海警南海持棍傷人 菲律賓海軍1人遭打傷」該列 `fetched_at` 仍停在 22:58（部署前值）未被刷新，而該文此刻仍在 ltn-world RSS feed（curl 抓到）→ 證明本輪確實被 `DROP:scs-skirmish` 擋下、未重新 upsert。
- **既有舊列不追溯清除**（如設計）：published 19:05 的舊列仍在 DB，由 30 天保留期自然滾出；「爬蟲資訊查詢」頁今日仍會顯示該舊列，直到到期或（若使用者要求）手動清除。

**與原計畫的偏差：**
- 無實質偏差。驗證段原寫 `curl /internal/health`，實測 external-materials-service 無對外埠、container 內亦無 `curl`（healthcheck 用 `wget`），改以 `docker inspect --format '{{.State.Health.Status}}'` 於 host 端確認 `healthy`（等價、更穩）。
- macOS `strings` 會把 `.class` 的 `CAFEBABE` 魔數誤判為 Mach-O fat binary，jar 內容驗證改用 `LC_ALL=C grep -a`。
