# [t321] 濾除「中國＋他國國名」的個人刑案雜訊（`EditorialNewsFilter` 規則③收緊 `GEO_REGION` disjunct）

**對應 Requirements:** Requirement 31（今日股市分析——每交易日於可設定時點由 AI 判斷當天台股走向；本任務收斂餵給分析的本地新聞收錄範圍，濾掉對外交、社會、財經股市皆無影響的個人刑案報導）
**前置任務:** 無（Task 199 建立 `EditorialNewsFilter` cascade、Task 221 加 `ANECDOTE`／`SOCIAL_ODDITY`、Task 229 加 `SCS_SKIRMISH`、Task 240 加 `LOTTERY`／`ESTATE` 並建立 `traceFinanceFeed`、Task 241 加 `SPORT`，皆已在 main）
**Liquibase changeset:** 無（純關鍵詞邏輯變更，不新增資料表／欄位；`news_headline` 結構不變）

## 背景

**使用者需求（2026-08-12 原話）：** 針對實際爬進 `news_headline` 的標題「**扯！南韓養老院中國女看護 用腳踹輪椅老者致死**」——「爬蟲裡，這種影響不了兩國外交關係，也沒有對社會造成重大衝擊，更不會影響財經、股市的新聞，要過濾。」

亦即使用者給了**三個並列判準**，三者皆不滿足才該濾：(a) 影響兩國外交關係、(b) 對社會造成重大衝擊、(c) 影響財經股市。

### 根因

該則實測判定為 `KEEP:china-regime`（規則③）。路徑是：

```
⓪⓪b⓪c 皆未命中 → ① FINANCE 未命中 → ①b ①c ①d 未命中 → ② LIFESTYLE 未命中
→ ③ CHINA 命中「中國」→ 四個 disjunct 中 BEIJING_REGIME／POLITY_STRONG／TW_POLITICS_GENERIC
   皆為空，但 GEO_REGION 命中「南韓」→ KEEP:china-regime
```

規則③的四個 disjunct 裡，前三個都是**政治行為者集合**（北京政權詞、美日歐台的機構與人物、台灣政黨），只有 `GEO_REGION` 是一份**裸國名清單**。它在 Task 199 的原始語意是「中國與他國的雙邊外交」，但實作上「標題同時出現中國與南韓」並不構成雙邊外交——**這正是使用者判準 (a) 的字面反例**。

對照專案自己的標準更清楚：同一份 `GEO_REGION` 在規則④是 `GEO_REGION ∧ GEO_TRIGGER` 的 AND 守門，規則③卻讓它單獨成立。**規則③對 `GEO_REGION` 的採信標準，比規則④寬鬆。**

### 這是唯一的漏口（非部分修補）

同型雜訊若不帶「中國」，現行 cascade 本來就會濾掉——實測：

| 合成標題 | 現行判定 |
|---|---|
| 南韓養老院**越南**女看護 用腳踹輪椅老者致死 | `DROP:non-finance-general`（規則④ `GEO_TRIGGER` 落空、⑨兜底） |
| **日本**養老院菲律賓女看護 踹死老者 | `DROP:non-finance-general`（日本不在 `GEO_REGION`） |

亦即 `CHINA ∧ GEO_REGION` 是這類個人刑案唯一能存活的路徑。修 `GEO_REGION` 這個 disjunct 即為完整修法，不需要新增全域否決規則。

## 設計

### 判定變更

規則③改寫為（語意等價於在 `GEO_REGION` disjunct 上加守門，其餘三個 disjunct **完全不動**）：

```java
if (containsAny(t, CHINA)) {
    if (containsAny(t, BEIJING_REGIME) || containsAny(t, POLITY_STRONG)
            || containsAny(t, TW_POLITICS_GENERIC)) return "KEEP:china-regime";
    if (containsAny(t, GEO_REGION)) {
        if (!isPersonalCrime(t) || containsAny(t, GEO_TRIGGER)) return "KEEP:china-regime";
        return "DROP:china-personal-crime";
    }
    return "DROP:china-nonfinance";
}
```

三層守門對應使用者的三個判準：

| 使用者判準 | 由誰承接 | 位置 |
|---|---|---|
| (c) 影響財經股市 | 規則① `FINANCE` | 在規則③之前，本任務不動它 |
| (a) 影響兩國外交關係 | `BEIJING_REGIME`／`POLITY_STRONG`／`TW_POLITICS_GENERIC` 三個 disjunct 原封不動；`GEO_REGION` 額外要求 `GEO_TRIGGER`；另加 `PERSONAL_CRIME_STATE_ACTION` 豁免 | 規則③內 ＋ `isPersonalCrime()` 內 |
| (b) 對社會造成重大衝擊 | `PERSONAL_CRIME_ESCALATION` 豁免 | `isPersonalCrime()` 內 |

**`PERSONAL_CRIME_STATE_ACTION` 是 spec-review 第 1 輪加上的，不是原始設計。** 審查者以合成案例證明：`BEIJING_REGIME` 的 `人權`／`民主`／`鎮壓`／`跨境鎮壓` 都要求**標題字面出現**該詞，平鋪直敘的跨境人權新聞（遣返／脫北者／平壤／首爾／河內）一個都不命中，於是「(a) 由另三個 disjunct 承接」的論證在這一類上不成立。實測（無此集合時）以下三則皆被誤殺為 `DROP:china-personal-crime`，加入後全部回到 `KEEP:china-regime`：

- 「中國強制遣返北韓脫北者 抵達平壤後遭凌虐致死」
- 「中國警方毆打北韓脫北婦女 首爾民間團體譴責」
- 「越南移工在中國工廠遭毆打 河內要求究責」

**`GEO_TRIGGER` 救回條款是必要的，不是保險。** 實測合成案例「中國與越南邊境爆發衝突 士兵遭毆打送醫」：`越南` 只在 `GEO_REGION`、不在 `BEIJING_REGIME`，`毆打` 命中刑案詞；若無此條款會被誤殺。加上後由 `衝突`（`GEO_TRIGGER`）救回為 `KEEP:china-regime`。等價說法：**個人刑案只是失去規則③的寬鬆待遇，退回規則④「國名＋地緣觸發詞」的同一標準**。

### 為什麼不採用另外兩個方案（皆經語料實測否決）

**方案 A｜直接把規則③的 `GEO_REGION` 改成 `GEO_REGION ∧ GEO_TRIGGER`（不引入刑案詞集）。**
實測否決。線上語料中 `KEEP:china-regime` 共 522 則，其中**僅靠 `GEO_REGION` 存活**（另三個 disjunct 皆空）者 14 則；這 14 則裡有 9 則不含任何 `GEO_TRIGGER`，方案 A 會全數翻 `DROP`，其中 **5 則為必保**：

- 「向中國企業洩露OLED關鍵技術　南韓樂金顯示器3名前員工遭判刑」（面板產業技術外洩）
- 「美跨黨派議員致函立陶宛政府　籲抗拒中國施壓堅定挺台」（台立中三邊）
- 「新聞360》烏克蘭炸伊朗不單純！學者曝「伊俄同盟」雙輸、中國也露餡」
- 「74％南韓人不信任中國 逾4成認為對日合作比歷史重要」（雙邊民意）
- 「謠言終結站》網傳中國士兵越境印度並挾持印軍 法新社：不實」（中印邊境）

**方案 B｜新增一條全域 `DROP:personal-crime` 否決規則（置於 `FINANCE` 之後、`LIFESTYLE` 之前），以自建守門集豁免。**
實測否決。它在語料上與本設計**捕獲完全相同的 3 則**（無額外收穫），卻在對抗測試多誤殺 2 則：「香港民主派人士遭港警毆打」與「日本首相遭持刀攻擊」。原因是全域規則必須自建一份「國家層級政治」守門集，而該集合的召回缺口是開放式的；本設計則直接沿用 cascade 既有的 `BEIJING_REGIME`／`POLITY_STRONG`（前者已含 `民主`、後者已含 `日本首相`），守門是免費且久經校準的。

擴充版方案 B 的守門集另暴露兩個典型陷阱，一併記錄為**永久禁用詞**：`下藥` 誤中「對症**下藥**」（李四川新北訪視），`偷吃`／`霸凌` 是政治口水的固定比喻（「駁斥**偷吃**」「**霸凌**國家團隊」）。

## 要做什麼

### 321.1 `EditorialNewsFilter.java`：新增兩個常數集與 `isPersonalCrime` helper

檔案：`external-materials-service/src/main/java/com/steven/assets/externalmaterials/client/EditorialNewsFilter.java`

在既有 `ESTATE_EXEMPT` 之後、`retain(...)` 之前新增：

```java
    // ===== 個人刑案／社會案件（Task 321）：只用於規則③的 GEO_REGION disjunct 守門 =====
    // 使用者需求（2026-08-12）：「影響不了兩國外交關係，也沒有對社會造成重大衝擊，更不會影響
    // 財經、股市的新聞，要過濾」——回報案例「扯！南韓養老院中國女看護 用腳踹輪椅老者致死」
    // 命中 CHINA(中國) ∧ GEO_REGION(南韓) 而在規則③被判 KEEP:china-regime。
    //
    // ⚠ **本集合不是全域否決集，只在規則③內取消「裸國名」這一個最弱的 KEEP 理由。**
    // 全域化（置於 FINANCE 之後、LIFESTYLE 之前的獨立規則）經實測在語料上零額外收穫，卻誤殺
    // 「香港民主派人士遭港警毆打」「日本首相遭持刀攻擊」——因為那樣得自建一份國家層級政治守門集，
    // 而本設計直接沿用 BEIJING_REGIME（已含 民主）與 POLITY_STRONG（已含 日本首相）。
    //
    // 詞集全部是**個人層級的人身／財產侵害**，刻意不含國家暴力語彙（鎮壓／屠殺／空襲／砲擊），
    // 那些本就該由規則③既有 disjunct 或規則④收錄。29 詞中 8 詞有語料樣本（致死／施虐／猥褻／
    // 性侵／竊盜 各 1，搶劫／酒駕 各 2，偷喝 1）、21 詞為零命中；
    // 零命中詞的納入是刻意的例外，理由是本規則的爆炸半徑被四重條件夾住（CHINA ∧ GEO_REGION
    // ∧ ¬BEIJING_REGIME ∧ ¬POLITY_STRONG ∧ ¬TW_POLITICS_GENERIC ∧ ¬GEO_TRIGGER），且
    // **規則①FINANCE 先於規則③**——財經比喻（融資重傷／不被工作綁架／營運動能）在此之前就已 KEEP，
    // 誤中財經的路徑不存在。此與 Task 241「否決集不收零樣本詞」的紀律不衝突：該紀律針對的是
    // 排在 LIFESTYLE 之前、能直接扣掉財經新聞的全域否決集。
    //
    // 刻意排除（逐詞實測會誤殺，勿加入）：
    //   踹  ：台灣政治用語「踹共」（語料 2 則：「民怨嗆普廷踹共」「苗博雅要國民黨踹共」）。
    //         **使用者回報的標題本身含「踹」，仍不得收**——它由 致死 命中即足夠。
    //   命案：子字串誤中「真除任**命案**獲參院批准」（美司法部長人事）。
    //   家暴：子字串誤中「就是國**家暴**力」（蔣萬安談深偽案）。
    //   殺人：誤中「川普：我寧願達成協議，因為我不想殺人」（美伊談判）。
    //   施暴：誤中「設專法反制跨境施暴」（跨境鎮壓法案）。
    //   綁架：財經比喻「想不被工作綁架，要存多少錢才夠？」。
    //   下藥：子字串誤中「對症**下藥**」。偷吃／霸凌：政治口水固定比喻（「駁斥偷吃」「霸凌國家團隊」）。
    //   逮捕／落網／嫌犯／身亡／死亡／重傷：語料實測大量命中必保新聞（以色列總理遭紐約市長嗆逮捕、
    //         中共預測抓人系統、北約間諜案、侵占7億當庭逮捕、解放軍空降兵墜落身亡、融資重傷…）。
    //   持刀／隨機攻擊：無差別攻擊屬使用者判準 (b)「對社會造成重大衝擊」，本就該保留，故不列入否決。
    private static final Set<String> PERSONAL_CRIME = set(
            "致死", "虐待", "虐死", "施虐", "凌虐", "毆打", "痛毆", "圍毆", "掌摑",
            "猥褻", "性騷擾", "性侵", "偷竊", "行竊", "扒竊", "竊盜", "搶劫", "強盜",
            "分屍", "棄屍", "縱火", "潑酸", "酒駕", "肇逃", "擄人", "撕票", "迷昏",
            "偷喝", "偷拿");

    // 重大社會衝擊豁免（Task 321）：對應使用者判準 (b)。命中即**不**視為個人刑案，
    // 落回原本的 KEEP:china-regime。恐攻／人質／無差別攻擊／暴動戒嚴等已非個人層級事件。
    // 刻意不用傷亡數字守門（如 `[0-9]+死`）：比照 Task 241 對場館災難的裁示，且「3死角」這類
    // 子字串誤中在中文標題中確有前例。
    private static final Set<String> PERSONAL_CRIME_ESCALATION = set(
            "恐攻", "恐怖攻擊", "恐怖襲擊", "恐怖組織", "恐怖分子", "恐怖份子",
            "人質", "挾持", "無差別", "隨機殺人", "隨機砍人", "屠殺", "暴動", "騷亂", "戒嚴");

    // 跨境人權／國家層級交涉豁免（Task 321，spec-review 第 1 輪加入）：對應使用者判準 (a)。
    // BEIJING_REGIME 的 人權／民主／鎮壓／跨境鎮壓 都要求標題**字面出現**該詞，但這類新聞的
    // 台媒標準寫法是平鋪直敘（遣返／脫北者／平壤／首爾／河內），一個都不命中。實測無此集合時
    // 「中國強制遣返北韓脫北者 抵達平壤後遭凌虐致死」「中國警方毆打北韓脫北婦女 首爾民間團體
    // 譴責」「越南移工在中國工廠遭毆打 河內要求究責」三則皆被誤殺，加入後全數回到 KEEP。
    // 9 詞中 8 詞有語料樣本（遣返2／脫北2／難民2／庇護2／究責5／譴責19／召見1／交涉4），
    // 僅 領事 為零命中——豁免集的零樣本詞誤中方向是「少濾一則」，比照 Task 241 的既有紀律可收。
    private static final Set<String> PERSONAL_CRIME_STATE_ACTION = set(
            "遣返", "脫北", "難民", "庇護", "究責", "譴責", "召見", "交涉", "領事");
```

在 `isFamilyEstate(...)` 附近（其他 private 判定 helper 旁）新增：

```java
    /**
     * 個人刑案判定（Task 321）：個人層級人身／財產侵害詞命中，且未命中兩層豁免——
     * {@link #PERSONAL_CRIME_ESCALATION}（使用者判準 b：對社會造成重大衝擊）與
     * {@link #PERSONAL_CRIME_STATE_ACTION}（使用者判準 a：跨境人權／國家層級交涉）。
     *
     * <p><b>本方法只被規則③的 {@code GEO_REGION} disjunct 呼叫</b>，不是獨立的 cascade 規則。
     * 回傳 true 的效果僅是「裸國名不足以構成保留理由」，並非直接 DROP——同一則若另含
     * {@code BEIJING_REGIME}／{@code POLITY_STRONG}／{@code TW_POLITICS_GENERIC}／{@code GEO_TRIGGER}
     * 任一訊號，仍為 {@code KEEP:china-regime}。
     */
    private static boolean isPersonalCrime(String t) {
        return containsAny(t, PERSONAL_CRIME)
                && !containsAny(t, PERSONAL_CRIME_ESCALATION)
                && !containsAny(t, PERSONAL_CRIME_STATE_ACTION);
    }
```

**約束：**
- 三個集合必須完全照上面的內容，不得增刪關鍵詞（每個詞的取捨都經 7526 則真實語料逐詞驗證與對抗測試）。
- **不得**把 `PERSONAL_CRIME` 提升為獨立的 cascade 否決規則（方案 B，已實測否決）。
- **不得**加入 `踹`／`命案`／`家暴`／`殺人`／`施暴`／`綁架`／`下藥`／`偷吃`／`霸凌`／`逮捕`／`落網`／`嫌犯`／`身亡`／`死亡`／`重傷`／`持刀`／`隨機攻擊`。

### 321.2 `trace()` 規則③：拆出 `GEO_REGION` disjunct 並加守門

把既有的**這 7 行（含上方兩行 `// 3)` 註解，註解一併取代、不得只換 `if` 區塊）**

```java
        // 3) 中國相關：財經（已判）／北京政權中央政治／美日台歐盟雙邊／與他國雙邊外交／台灣政黨對中 保留，
        //    其餘（純社會獵奇）濾除
        if (containsAny(t, CHINA)) {
            if (containsAny(t, BEIJING_REGIME) || containsAny(t, POLITY_STRONG)
                    || containsAny(t, TW_POLITICS_GENERIC) || containsAny(t, GEO_REGION)) return "KEEP:china-regime";
            return "DROP:china-nonfinance";
        }
```

改為（新片段自帶改寫過的 `// 3)` 註解；若只取代 `if` 區塊會留下兩段 `// 3)`，且舊那段仍宣稱「與他國雙邊外交」無條件保留、與新行為矛盾）

```java
        // 3) 中國相關：財經（已判）／北京政權中央政治／美日台歐盟雙邊／台灣政黨對中 保留，
        //    其餘（純社會獵奇）濾除。
        //    Task 321：GEO_REGION（裸國名）是四個 disjunct 中唯一**不是政治行為者集合**的一個，
        //    其原始語意為「中國與他國雙邊外交」，但「標題同時出現中國與南韓」不構成雙邊外交
        //    （使用者 2026-08-12 回報：「南韓養老院中國女看護 用腳踹輪椅老者致死」）。故個人刑案
        //    不得單靠裸國名保留，須另有 GEO_TRIGGER——**即規則④對同一份 GEO_REGION 採用的標準**。
        //    另三個 disjunct 不受影響：新疆凌虐（BEIJING_REGIME）、日相遇襲（POLITY_STRONG）皆照舊保留。
        if (containsAny(t, CHINA)) {
            if (containsAny(t, BEIJING_REGIME) || containsAny(t, POLITY_STRONG)
                    || containsAny(t, TW_POLITICS_GENERIC)) return "KEEP:china-regime";
            if (containsAny(t, GEO_REGION)) {
                if (!isPersonalCrime(t) || containsAny(t, GEO_TRIGGER)) return "KEEP:china-regime";
                return "DROP:china-personal-crime";
            }
            return "DROP:china-nonfinance";
        }
```

**約束：**
- 既有 label `KEEP:china-regime`／`DROP:china-nonfinance` 的字串與適用情境**完全不變**（既有測試斷言不得被打破）；新路徑用新 label `DROP:china-personal-crime`，以利日後稽核歸因。
- 不得更動規則⓪／⓪b／⓪c／①／①b／①c／①d／②／④～⑨ 的順序與回傳字串。
- **不得改動 `traceFinanceFeed()`**：純財經來源（wantgoo／moneydj）沒有規則③，此變更與之無關；實測對 1713 則純財經語料翻轉為 0。

### 321.3 `EditorialNewsFilterTest.java`：新增回歸錨點

在既有最後一個 `@Nested` 之後新增：

```java
    @Nested
    @DisplayName("中國＋他國國名的個人刑案（Task 321）")
    class ChinaBranchPersonalCrime {
        // 使用者 2026-08-12 回報案例與同類語料實例
        @Test void 個人刑案不得單靠裸國名保留() {
            assertDrop("扯！南韓養老院中國女看護 用腳踹輪椅老者致死");                    // 語料實例（使用者回報）
            assertDrop("中國男子在天津搶劫殺人 潛逃29年後於南韓落網");                    // 語料實例
            assertDrop("犯法秒換國籍！ 中國夫妻搭北捷偷喝水 被抓包硬凹「來自南韓」");      // 語料實例
            assertDrop("南韓補習班老師猥褻中國學童 遭判刑3年");
            assertDrop("中國移工在南韓縱火燒毀宿舍 4人受傷");
        }
        // 判準 (a)：另三個 disjunct 完全不受影響
        @Test void 國家層級政治訊號仍保留() {
            assertKeep("中國新疆再教育營傳出凌虐維吾爾人 美國宣布制裁北京官員");     // BEIJING_REGIME
            assertKeep("香港民主派人士遭港警毆打 引發國際關注");                     // BEIJING_REGIME(民主)
            assertKeep("中國異議人士遭凌虐致死 人權團體要求聯合國調查");             // BEIJING_REGIME
            assertKeep("美國會通過反跨境鎮壓法案 制裁中國施虐官員");                 // BEIJING_REGIME
            assertKeep("中國駐南韓大使館抗議僑民遭搶劫 要求首爾加強維安");           // BEIJING_REGIME(大使館)
            assertKeep("中國留學生在日本遭搶劫致死 兩國外交部門展開交涉");           // BEIJING_REGIME(外交)
        }
        // GEO_TRIGGER 救回：退回規則④「國名＋地緣觸發詞」的同一標準
        // ⚠ 本組與下兩組的標題都刻意**不含** BEIJING_REGIME／POLITY_STRONG／TW_POLITICS_GENERIC
        //    任一成員，否則會在規則③第一個 if 就短路 KEEP、根本走不到 GEO_REGION disjunct，
        //    測試變成空轉錨點（把守門邏輯整條刪掉也照樣通過）。
        //    spec-review 第 1 輪即抓到三則空轉：「…船員與海警爆發毆打衝突」（海警∈BEIJING_REGIME）、
        //    「中國籍男子…無差別攻擊」（中國籍∈BEIJING_REGIME 且 攻擊∈GEO_TRIGGER 雙重短路）、
        //    「中國留學生在南韓遭挾持為人質」（整句無任一 PERSONAL_CRIME 詞，isPersonalCrime 恆 false）。
        @Test void 地緣觸發詞救回國家層級衝突() {
            assertKeep("中國與越南邊境爆發衝突 士兵遭毆打送醫");                     // 衝突∈GEO_TRIGGER
            assertKeep("南韓漁民與中國船員在公海爆發衝突 多人遭毆打");               // 衝突∈GEO_TRIGGER
        }
        // 判準 (b)：對社會造成重大衝擊者豁免（三則皆不含 GEO_TRIGGER，確保是 ESCALATION 在起作用）
        @Test void 重大社會衝擊豁免() {
            assertKeep("中國男子在南韓縱火燒死30人 當局憂無差別犯案");               // 縱火＋無差別
            assertKeep("中國留學生在南韓遭擄人挾持為人質 警方攻堅救出");             // 擄人＋挾持／人質
            assertKeep("中國移工在南韓街頭暴動 多名警察遭毆打");                     // 毆打＋暴動
        }
        // 判準 (a)：跨境人權／國家層級交涉豁免（spec-review 第 1 輪加入 PERSONAL_CRIME_STATE_ACTION）
        // 無此集合時三則實測皆被誤殺為 DROP:china-personal-crime
        @Test void 跨境人權與國家交涉豁免() {
            assertKeep("中國強制遣返北韓脫北者 抵達平壤後遭凌虐致死");               // 遣返／脫北
            assertKeep("中國警方毆打北韓脫北婦女 首爾民間團體譴責");                 // 脫北／譴責
            assertKeep("越南移工在中國工廠遭毆打 河內要求究責");                     // 究責
        }
        // 判準 (c)：規則①FINANCE 先判，財經新聞完全不受影響
        @Test void 財經新聞不誤殺() {
            assertKeep("南韓對中國祭出反傾銷關稅 鋼鐵業受衝擊");
            assertKeep("中國在南韓部署間諜網 竊盜半導體技術遭起訴");
            assertKeep("台積電前工程師竊盜營業秘密 檢方起訴求刑");
        }
        // 永久禁用詞的回歸錨點：這些詞若被誤加進 PERSONAL_CRIME，本組會失敗
        @Test void 禁用詞不得誤殺政治與財經() {
            assertKeep("自由說新聞》直擊烏軍重創莫斯科命脈！俄國缺油再爆「斷水荒」民怨嗆普廷踹共");  // 踹共，語料實例
            assertKeep("藍營側翼竟是共諜！買全台個資恐嚇  苗博雅要國民黨踹共");                      // 踹共，語料實例
            assertKeep("前川普私人律師、代理司法部長布蘭希 真除任命案獲參院批准");                  // 任命案⊃命案，語料實例
            assertKeep("韋淳祐深偽總統聲音案被辦 蔣萬安堅稱「就是國家暴力」");                      // 國家暴力⊃家暴，語料實例
            assertKeep("談論與伊朗談判 川普：我寧願達成協議，因為我不想殺人");                      // 語料實例
            assertKeep("美參議員提「停止跨境鎮壓法案」》學者：台灣應師法美國 設專法反制跨境施暴");  // 語料實例
            assertKeep("李四川：跑遍新北29區 對症下藥才能解決問題");                                // 對症下藥⊃下藥，語料實例
        }
        // 方案 A（規則③改為 GEO_REGION ∧ GEO_TRIGGER）的否決證據：這 5 則必須維持 KEEP
        @Test void 裸國名新聞仍須保留() {
            assertKeep("向中國企業洩露OLED關鍵技術  南韓樂金顯示器3名前員工遭判刑");
            assertKeep("美跨黨派議員致函立陶宛政府  籲抗拒中國施壓堅定挺台");
            assertKeep("新聞360》烏克蘭炸伊朗不單純！學者曝「伊俄同盟」雙輸、中國也露餡");
            assertKeep("74％南韓人不信任中國 逾4成認為對日合作比歷史重要");
            assertKeep("謠言終結站》網傳中國士兵越境印度並挾持印軍 法新社：不實");
        }
    }
```

**約束：** 標題註明「語料實例」者取自線上真實 `news_headline`，其餘為對抗測試的代表性合成標題。任一斷言失敗代表詞集或守門位置有誤，**修的是實作、不是把測試放寬**。

### 321.4 不需要改動的地方

- **不改 `NewsFetchClient`**：`trace()` 已在既有各混合型 feed 接線，本規則自動隨之生效。
- **不改 `traceFinanceFeed()`／`retainForFinanceFeed()`**：純財經來源不走規則③。
- **不改 backend／bff／frontend**：過濾發生在落庫之前，無契約變更。
- **不改 `FINANCE`／`BEIJING_REGIME`／`POLITY_STRONG`／`GEO_REGION`／`GEO_TRIGGER` 任一既有集合。**
- **不追溯清除舊列**：只在抓取時作用，`news-scraper.retention-days` 天保留期自然滾出。
- **無 Liquibase changeset、無 `@Scheduled` 變更。**

## 驗證

**單元測試：**

```bash
cd external-materials-service && /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q test -Dtest=EditorialNewsFilterTest
```

（斷言 `Failures: 0, Errors: 0`；新增 `@Nested ChinaBranchPersonalCrime` **8 個測試方法**全過、既有 45 個測試方法／10 個 `@Nested` 零回歸，合計 53。既有測試中唯一命中 `PERSONAL_CRIME` 詞者為「職棒球員酒駕遭球團暗殺式冷凍」，它在規則①d 即判 `DROP:sport`、走不到規則③，故不受影響。）

**整模組建置：**

```bash
cd external-materials-service && /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q package
```

**部署（本專案無 dev server，image rebuild + container recreate 才算改好；共用 stack 故從 main 的 worktree 重建）：**

```bash
cd /Users/steven/Project/asset-management-main && docker compose -p asset-management build --no-cache external-materials-service
```

```bash
cd /Users/steven/Project/asset-management-main && docker compose -p asset-management up -d --no-deps --force-recreate external-materials-service
```

**非 stale jar 驗證：**

```bash
docker run --rm --entrypoint sh asset-management-external-materials-service:latest -c 'cd /tmp && unzip -o -q /app/app.jar "BOOT-INF/classes/com/steven/assets/externalmaterials/client/EditorialNewsFilter.class" && LC_ALL=C grep -ac "china-personal-crime" BOOT-INF/classes/com/steven/assets/externalmaterials/client/EditorialNewsFilter.class'
```

（期望 `1`；另可同法確認 `凌虐`／`戒嚴`。）

**健康檢查：**

```bash
docker inspect --format '{{.State.Health.Status}}' asset-external-materials-service
```

## 已知殘留（刻意接受）

1. **`BEIJING_REGIME` 命中的個人刑案仍會保留。** 合成案例「南韓男子酒駕撞死路人 中國籍妻子出面道歉」實測 `KEEP:china-regime`——`中國籍` 是 `BEIJING_REGIME` 成員。本任務刻意不對 `BEIJING_REGIME` 加守門：它是規則③的核心國家訊號集，加守門的風險（誤殺新疆／人權／跨境鎮壓類新聞）遠高於收益。語料中無此類實例。

2. **靠 `FINANCE` 偶然命中而保留的中國個人刑案不受本規則影響**（規則①在規則③之前）。語料實例一則：「中國商人猥褻韓女被拒絕入境 在濟州島擁7.6**億元**土地也沒用」——`億元` ∈ `FINANCE` 使其在規則①即 `KEEP:finance`，走不到規則③。比照 Task 241 已知殘留 3（wantgoo 川普世界盃因場館名「大都會**人壽**體育場」誤中 `FINANCE`）的處理原則：根因屬 `FINANCE` 白名單的既有誤中，**削弱 `FINANCE` 的風險遠大於收益**，故記錄而不修。（語料另有「日本岡山大學教授偷拍少女裙底…校方仍祭**處分**」「日本警察偵辦竊盜案 竟偷走…63萬**日圓**現金」兩則同型雜訊，但它們**不含任何 `CHINA` 成員**、本來就走不到規則③，屬 `FINANCE` 既有誤中的獨立案例，與本任務的規則順序論證無關。）

3. **本次只鎖「個人刑案」，同一 disjunct 下的非刑案個人糾紛仍靠裸國名存活。** 語料實例：「南韓醫院持續治療中國病患6年 家屬拒絕支付1880萬醫療費」——三判準同樣皆不滿足，但不含任何 `PERSONAL_CRIME` 詞，本規則不會濾掉它。刻意不擴充（醫療費／欠費／糾紛 這類詞與健保政策、商業糾紛高度重疊，誤殺風險遠高於本則的收益）。**因此不可把「這是唯一的漏口」讀成「使用者這類申訴已全數關閉」**——該論證的範圍僅限個人刑案文類。

4. **非中國的同類刑案雜訊由規則⑨兜底濾除，不經本規則**（見上方「這是唯一的漏口」）。若日後 `GEO_REGION` 擴充或規則④放寬，此前提需重新驗證。

5. **`PERSONAL_CRIME` 29 詞中 21 詞為語料零命中**，其安全性來自四重條件夾擊與「規則①先判」，而非逐詞真實樣本驗證。日後若有人把本集合移作他用（例如提升為全域否決規則），此安全論證即失效——集合註解已寫明此約束。

6. **`PERSONAL_CRIME_STATE_ACTION` 的 `譴責`（語料 19 則）是本次三個集合中最寬的詞。** 它會讓「中國男子在南韓行竊 當地團體譴責」這類個案也回到 `KEEP`。刻意接受：豁免集誤中的方向是「少濾一則」，且 `譴責` 在台媒語境幾乎都指向團體／政府的公開表態，與判準 (a) 相符。

## 完成報告

**實際改動檔案：**
- （待回填）

**驗證輸出：**
- （待回填）

**與原計畫的偏差：**
- （待回填）
