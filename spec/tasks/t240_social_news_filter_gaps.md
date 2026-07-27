# [t240] 濾除「影響不了經濟／股市／政局」的社會與軟性新聞（`EditorialNewsFilter` 三個結構性缺口）

**對應 Requirements:** Requirement 31（今日股市分析——每交易日於可設定時點由 AI 判斷當天台股走向；本任務收斂餵給分析的本地新聞收錄範圍，濾掉對經濟、股市、政局皆無影響的社會與軟性新聞雜訊）
**前置任務:** 無（Task 199 建立 `EditorialNewsFilter` cascade、Task 221 加入 `ANECDOTE`／`SOCIAL_ODDITY`、Task 229 加入 `SCS_SKIRMISH`，皆已在 main。本任務在其上疊加第四、五條否決集並修正兩處既有缺陷，屬同一模式的延伸。歷史脈絡見 `spec/tasks/archive/tasks-151-200.md` Task 199）
**Liquibase changeset:** 無（純關鍵詞邏輯變更，不新增資料表／欄位；`news_headline` 結構不變）

## 背景

**現在的錯誤行為：** 使用者於 2026-07-27 連續回報 **4 則**實際爬進 `news_headline`、顯示在「爬蟲資訊查詢」頁的雜訊。使用者原話：「這種社會新聞影響不了經濟、股市，爬蟲時要濾掉」「這個新聞雖然屬於台灣六都的新聞，但是和財經完全無關，也影響不了政局發展，應該過濾掉」「這個教育界的新聞，沒有大到影響政局發展的話，也應該過濾」。

| # | 標題 | 來源 | 現行判定 |
|---|------|------|----------|
| 1 | 很多家庭急著分遺產　忘了另一位父母還活著 | `wantgoo` | 未經過濾（來源豁免）；若套 `trace()` 標題為 `DROP:non-finance-general`、**併入摘要後為 `KEEP:finance`** |
| 2 | 侯友宜、谷立言、片山和之同遊新北　搭船欣賞淡江大橋 | `ltn` | `KEEP:tw-whitelist-city`（規則⑧） |
| 3 | 陽明交大教評會爆爭議　教育部長：組成有瑕疵 | `ltn` | `KEEP:finance`（規則①） |
| 4 | 7-11開出千萬中獎發票　花150元買飲品成幸運兒 | `udn` | `KEEP:finance`（規則①） |

四則的成因分屬**三個互不相同的結構性缺口**，因此需要三組修正——**不是同一條規則的四個樣本**：

**缺口 A｜純財經來源完全豁免過濾（案例 1）。** Task 199 起 `EditorialNewsFilter` 只套用於混合型 feed（`ltn`／`udn`），`wantgoo`／`moneydj` 因「本就財經專屬」而豁免（`NewsFetchClient.fetchAll()` 中兩者直接 `out.addAll(safe(...))`，未包 `EditorialNewsFilter.retain`）。但玩股網 `all-headlines-by-category` 實際含理財生活軟文，故此類直接落庫。

**缺口 B｜`FINANCE` 白名單誤中，使社會軟文在規則①即被 `KEEP:finance` 攔下、後續所有否決規則失去發言機會（案例 1 的摘要、案例 3、案例 4）。** 三個成因：
- **子字串誤中**：`FINANCE` 含公司名 `陽明`（＝陽明海運），誤中「陽明交大」。
- **金額詞誤中**：發票／彩券新聞必帶 `千萬`／`加碼`／`億`。
- **家事遺產軟文**：摘要含「存款」「房子要不要賣」。

**缺口 C｜六都白名單無條件保留（案例 2）。** 規則⑧ `TW_WHITELIST_CITY` 只要命中 `台北`／`新北`／`高雄`／`雙北` 即無條件 `KEEP`，不看內容。

**正確行為（本任務）：** 三組修正如下方「要做什麼」。四則使用者案例最終判定須為 `DROP:family-estate`／`DROP:lifestyle`／`DROP:non-finance-general`／`DROP:lottery`。

**設計約束的來源（對線上 4807 則真實 `news_headline` 台灣新聞全量對照得出（`category='news' AND region='TW'`，2026-07-27 快照；語料每日成長，重跑時筆數會略增，判定結論不受影響），務必遵守）：**

1. **純財經來源不得套整套 cascade。** `FINANCE` 是為「從一般新聞版面挑出財經」而設的**白名單**，純財經站的財經用語大量不在其中。語料實測：對 `wantgoo` 套整套 `trace()` 會濾掉 **41 則**（`non-finance-general` 26／`lifestyle` 9／`china-nonfinance` 4／`social-oddity` 1／`tw-local-other-county` 1），其中含「Waymo 傳2028年後終止合作 Uber 跌逾4%」「〈華德動能訪廠〉拓海外版圖有成 樂估日本電巴市占率上看3成」「基本工資調漲至3萬 商總：對缺工問題仍無解」「比特幣未來十年大預言！Strategy創辦人」「舊制勞工也能自提6％到退休金專戶領『分紅』」等明確財經新聞。
2. **純財經來源亦不得套 `LIFESTYLE`／`SOCIAL_ODDITY`。** 即使保留 `FINANCE` 豁免，`LIFESTYLE` 仍誤殺**消費／旅遊類股**報導：「旅遊市況熱 雄獅東北亞賞楓行程銷售破5成 將擴大拓郵輪版圖」（雄獅 2731 為上市公司）、「《DJ在線》旅行社下半年團費趨穩」、「減肥藥大戰打進法院！諾和諾德怒告禮來廣告誤導」、「Nike大砍中國經銷商」、「世界盃決賽Nike無緣亮相 adidas成贊助商大贏家」；`SOCIAL_ODDITY` 的 `越獄` 則誤中 AI jailbreak：「Hugging Face遭OpenAI模型沙盒『越獄』攻擊」。
3. **三條否決集（`isAnecdote`／`LOTTERY`／`ESTATE`）之所以可跨來源套用**，正因其語意即「**即使帶財經詞也無投資資訊量**」，與來源是否財經專屬無關——這是本任務唯一的跨來源共用集合，其餘規則一律只作用於混合型 feed。
4. **`千萬`／`加碼` 不得從 `FINANCE` 移除——理由不是「移除會大量誤殺」，而是「移除根本解決不了問題」。** 實測（把 `千萬` 自 `FINANCE` 移除後全語料重跑，計原 `KEEP:finance` 翻為非 `KEEP:finance` 者）：`千萬` 為全篇唯一財經訊號者 **17 則，其中僅 2 則屬企業新聞**（「問題油脂延燒 泰山深夜聲明：暫停沙拉油上架、捐三千萬支持食安檢驗」「HH 草本新淨界公益邁入第五年 累積捐助突破千萬元」），其餘 15 則本就是本任務要濾的雜訊（彩券發票 7、家事遺產 2、社會軟文 4：老老照顧／共諜退休金／7旬夫妻節儉／首爾市長罰款）或另有規則接住（「翁曉玲…提案大砍3千萬業務費」→ `KEEP:china-regime`、「卓榮泰下令『千萬觀光人次一定要達到』」→ `KEEP:polity-strong`）。**關鍵在於移除 `千萬` 擋不住彩券新聞**——「大樂透頭獎連17摃　加碼100萬獎只剩7組」等 4 則移除後仍靠 `加碼` 判 `KEEP:finance`（實測），而 `加碼` 是「外資加碼」「加碼投資」的核心財經語彙、移除風險遠高於 `千萬`。故正解是否決集攔在 `FINANCE` 之前，而非削弱白名單。**「金管會裁罰銀行業近兩千萬」「青安3.0 千萬額度」「永固-KY 董座砸9千萬炒股護盤」這三則含 `千萬` 但另有其他財經詞（`金管會`／`青安`／`炒股`），移除 `千萬` 後仍 `KEEP:finance`，不可拿來當「唯一訊號」的例子。**
5. **刻意排除的候選詞（逐詞實測會誤殺，勿加入）：**
   - `家產` — 誤中「國**家產**業園區」，會誤殺「三星搶攻AI晶片商機！將龍仁半導體國家產業園區首座晶圓廠量產提前一年」。
   - `分產`／`爭產` — `充分產能`／`部分產品`／`競爭產業` 子字串風險；且語料中該類標題已由 `遺產` 命中，屬冗餘。
   - `開獎` — 財經媒體借喻財報公布，會誤殺「AI巨頭財報前瞻一表看！微軟、Meta、蘋果下周**開獎** 聚焦資本支出」。
   - 裸 `發票` — 會誤殺「政府挺團體訴訟求償！陳時中：食安基金補助打官司 退貨持**發票**憑證僅空瓶也收」。改以 `發票` ∧ `開出` 的 AND 組合承接「只花10元抱回200萬！7-ELEVEN 開出一張千萬、七張百萬發票」。
6. **語料零樣本的推測詞一律不納入**（比照 Task 229 紀律）：`對獎`／`四星彩`／`三星彩`（且 `三星彩` 有 Samsung `三星` 子字串疑慮）／`連摃`／`摃龜`／`槓龜`／`遊船`／`遊河`／`進香`／`園遊會`／`剪綵`／`揭幕`／`踩線`。

## 要做什麼

### 240.1 `EditorialNewsFilter.java`：修正 `FINANCE` 的 `陽明` 子字串誤中

檔案：`external-materials-service/src/main/java/com/steven/assets/externalmaterials/client/EditorialNewsFilter.java`

在 `FINANCE` 集合中（目前第 80 行的航運公司名那一行），把裸 `陽明` 改為 `陽明海運`；並在同集合的產業詞區塊（目前第 85 行 `"塑化", "鋼鐵", ...` 開頭處）補上 `運價`：

```java
            "中鋼", "東鋼", "豐興", "台塑", "南亞", "台化", "台泥", "亞泥", "長榮", "陽明海運", "萬海",
```

```java
            "運價", "塑化", "鋼鐵", "鋼品", "鋼筋", "型鋼", "熱軋", "冷軋", "鍍鋅", "廢鋼", "水泥", "航運", "貨櫃",
```

**約束：**
- **只改 `陽明` 這一個詞**，同一行的 `長榮`／`萬海`／`南亞`／`台化` 一律不動（語料中無對應誤中樣本，無證據支持更動）。
- **`運價` 是本項的配套，不可省略。** 窄化 `陽明` 後，唯一公司訊號為「陽明」的航運標題會失去保護——`運價`／`貨量`／`裝載率` 原本都不在 `FINANCE`，形如「陽明7月運價走弱 貨量持平」會翻為 `DROP:non-finance-general`。語料實測 `運價` **11 則（標題 10 ＋ 僅摘要 1）全部**為航運／空運財經新聞（「SCFI運價指數周跌4.3%」「運價走揚 長榮海6月營收創17個月新高」「AI伺服器搶艙潮 空運市場淡季不淡、運價走揚」「航空雙雄迎貨運大旺季 Q4運價可望再攀峰」、wantgoo「市場需求強勁 德國航運巨擘赫伯羅德上修全年財測」〔`運價` 在摘要內——`trace()` 的輸入是標題＋摘要，見 `text(NewsRow)`〕…），零歧義、零誤中風險。
- 零誤殺已驗證，實作時不得因「怕漏掉陽明海運新聞」而回改。語料含「陽明」者 **10 則 ＝ 7 則陽明海運 ＋ 2 則陽明交大產學 ＋ 1 則陽明交大教評會**：
  - 7 則陽明海運全部另含下列豁免詞之一而維持 `KEEP:finance`（並集＝`關稅`／`營收`／`航運`／`營運`／`集團`／`陽明海運`／`供應鏈`） —— 「美新關稅敲定後 陽明：運價後市將明朗」（`關稅`）、「運價走揚　陽明6月營收165.91億年月雙增」（`營收`）、「航海節登場 陽明董座蔡豐明：航運業攜手升級轉型、強化全球競爭力」（`航運`）、「加速減碳！陽明與PSA國際港務集團簽署永續合作備忘錄」（`集團`）、「陽明海運與 PSA 國際港務集團簽署永續合作備忘錄 加速全球供應鏈減碳」（`陽明海運`＋`供應鏈`）、「陽明蔡豐明：運價雖跌 貨量仍滿 後市視美關稅政策而定」（`關稅`＋`運價`）、「陽明、台驊6月營運亮麗」（`營運`）。**引用時務必使用上列完整標題**——截斷成「陽明蔡豐明：運價雖跌 貨量仍滿」會失去 `關稅`，在未加 `運價` 的版本下反而是 DROP。
  - 2 則陽明交大產學新聞（「鴻海研究院聯手陽明交大 研發超大容量矽光子技術」「TSIA首頒半導體設備創新獎 逢甲、陽明交大、明志科大脫穎而出」）另含 `鴻海`／`矽光子`／`半導體` 維持 `KEEP`。
  - 僅使用者回報的「陽明交大教評會爆爭議 教育部長：組成有瑕疵」翻為 `DROP:non-finance-general`。

### 240.2 `EditorialNewsFilter.java`：`LIFESTYLE` 增列觀光行程／禮儀性活動詞

在 `LIFESTYLE` 集合的「時尚 / 美妝 / 星座 / 寵物」區塊之後（目前 `"寵物", "萌寵", "貓咪", "毛孩", "減肥", "瘦身", "健身", "食譜",` 那一行之後），插入一行並附註解：

```java
            // 觀光行程／禮儀性活動（Task 240）：六都地方新聞若無財經訊號，僅因命中城市白名單就被規則⑧
            // KEEP:tw-whitelist-city 收錄（「侯友宜…同遊新北 搭船欣賞淡江大橋」）。置於規則②即可在走到
            // ⑧前攔下，故「台積電嘉義二期動土前7座宮廟遶境祈福」仍由規則①KEEP:finance。（語料中 嘉年華
            // 的唯一樣本「巨大外星人出沒！「風吹七Go」七股鹽山風箏嘉年華登場」現況為 KEEP:finance——FINANCE
            // 的裸「股」子字串命中「七股」，於規則①即攔下、走不到規則⑥；加入 嘉年華 後判定不變。）
            //
            // **注意：只有財經（規則①）豁免，強政治（POLITY_STRONG，規則⑤）並不豁免**——規則⑤在規則②
            // 之後，這是 Task 199 的既有設計（「置於中國/地緣/政治/城市之前，使政治人物名／城市名／中國詞
            // 皆不能救回軟文」）。故政治人物的純禮儀性行程會被本規則濾除（如「坪林川友會授旗 李四川…」、
            // 語料實例「陳世軒新莊辦水樂園…黃國昌扮大魔王」原為 KEEP:tw-politics），此為刻意取捨——使用者
            // 判準是「影響不了政局發展」，授旗／路跑／水樂園等行程符合該判準。
            //
            // 刻意不收「參拜」（只收「宮廟」）：裸「參拜」會誤殺「日相參拜靖國神社 中國外交部強烈抗議」
            // 這類真正牽動中日關係與市場的事件（規則②在規則③CHINA、⑤POLITY_STRONG 之前，政治無從救回）；
            // 使用者回報的「李四川參拜宮廟」已由「宮廟」命中，收「參拜」屬冗餘而有害。
            "同遊", "搭船", "宮廟", "遶境", "路跑", "授旗", "嘉年華",
            "水樂園", "合唱團", "出家",
```

**約束：**
- 不得新增獨立規則——本項刻意沿用 `LIFESTYLE` 既有的「旅遊／景點／打卡」語意與既有位置（規則②），cascade 不動。
- 詞集不得增刪。特別是**不得加入** `參拜`（誤殺靖國神社類中日關係事件，理由見上方註解）、`遊船`／`遊河`／`進香`／`園遊會`／`剪綵`／`揭幕`／`踩線`（語料零樣本，比照 Task 229 紀律不納入推測詞）。
- **不得在 spec 或註解中宣稱「強政治天然豁免」**——`POLITY_STRONG` 位於規則⑤、在 `LIFESTYLE`（規則②）之後，對本規則沒有豁免效果。

### 240.3 `EditorialNewsFilter.java`：新增 `LOTTERY` 與 `ESTATE` 兩個否決集

在既有 `SCS_ESCALATION` 集合之後、`retain(...)` 方法之前，新增：

```java
    // ===== 發票／彩券中獎（Task 240）：置於 FINANCE 之前 =====
    // 「7-11開出千萬中獎發票 花150元買飲品成幸運兒」「大樂透頭獎連17摃 加碼100萬獎只剩7組」這類
    // 統一發票／彩券開獎新聞，對判斷股市走向零資訊量，卻**必然**帶金額詞（千萬／加碼／億）而在規則①
    // 被 KEEP:finance 攔下——與 Task 221 ANECDOTE 同屬「必須凌駕 FINANCE 才攔得到」的文類。
    //
    // 刻意不從 FINANCE 移除 千萬／加碼：不是因為移除會大量誤殺（實測 千萬 為唯一財經訊號者 17 則，
    // 僅泰山捐三千萬、HH 草本公益捐助 2 則屬企業新聞），而是**移除解決不了問題**——「大樂透頭獎連17摃
    // 加碼100萬獎」等 4 則移除 千萬 後仍靠 加碼 判 KEEP:finance，而 加碼 是「外資加碼／加碼投資」的核心
    // 財經語彙、移除風險更高。故正解是否決集攔在 FINANCE 之前，而非削弱白名單。
    //
    // 刻意排除（實測會誤殺，勿加入）：開獎（財經媒體借喻財報公布，打到「AI巨頭財報前瞻一表看！微軟、
    // Meta、蘋果下周開獎 聚焦資本支出」）、裸的 發票（打到 wantgoo「政府挺團體訴訟求償！陳時中：食安基金補助
    // 打官司 退貨持發票憑證僅空瓶也收」——該則走 traceFinanceFeed，收裸「發票」會被本規則 DROP；ltn
    // 「中聯油脂風暴 財長：提供發票資料協助溯源追蹤」現況本就是 DROP:non-finance-general、非誤殺例）
    // ——改以 `發票` ∧ `開出` 的 AND 組合承接「7-ELEVEN 開出一張千萬、七張百萬發票」。特別獎（有「特別獎金」子字串風險，會打到「台積電
    // 發放特別獎金 每人平均逾百萬」這類年終／績效獎金財經標題；且語料中該類標題已由 統一發票／獎號 命中，
    // 屬冗餘）。語料零樣本的推測詞一律不納入：對獎／威力彩／今彩／四星彩／三星彩（另有 Samsung「三星」
    // 子字串疑慮）／連摃／摃龜／槓龜——**下列 8 詞每一個在語料中都有實際樣本**。
    private static final Set<String> LOTTERY = set(
            "統一發票", "中獎", "獎號", "兌獎", "刮刮樂", "幸運兒", "頭獎", "大樂透");

    // ===== 家事遺產糾紛（Task 240）：置於 FINANCE 之前 =====
    // 「很多家庭急著分遺產 忘了另一位父母還活著」這類家事／繼承糾紛軟文，對股市無資訊量。**標題單獨
    // 判定為 DROP:non-finance-general，是其摘要（「房子要不要賣？存款怎麼分？」）把它救回 KEEP:finance**
    // ——證明本規則必須凌駕 FINANCE，只把來源接上過濾（240.4）並不足夠。
    //
    // 三類豁免（ESTATE_EXEMPT）使這些**不被本規則否決**（能否收錄仍由後續 cascade 決定，非保證 KEEP）：
    // 法制（「遺產可免分兄弟姊妹？立院朝野拍板 特留分修法7/28處理」）、稅制（「被繼承人遺有應收股利
    // 遺產稅申報一次看」「分產喬不攏代價大！千萬遺產稅晚繳一個月」）、市場主體（「台積電配息創新高、
    // 繼承股票先別high！國稅局爆『這天』成股利報稅分水嶺」）。
    //
    // 刻意排除（實測會誤殺，勿加入 ESTATE_TOPIC）：家產（誤中「國家產業園區」→ 打到「三星…龍仁半導體
    // 國家產業園區首座晶圓廠量產提前一年」）、分產／爭產（充分產能／部分產品／競爭產業 子字串風險，且
    // 該類標題已由 遺產 命中，冗餘）。
    // ESTATE_EXEMPT 刻意不收「集團」：語料中「集團」與「遺產／繼承」零共現、無證據支撐，而它是高頻泛詞
    // （詐騙集團／犯罪集團）且同時存在於 FINANCE，收了會讓「詐騙集團騙走老翁遺產」落回 KEEP:finance。
    private static final Set<String> ESTATE_TOPIC = set("遺產", "遺囑", "繼承人", "特留分");
    private static final Set<String> ESTATE_EXEMPT = set(
            "立院", "立法院", "修法", "法案", "三讀", "朝野", "釋憲", "大法官",
            "國稅局", "財政部", "申報", "稅制", "課稅", "遺產稅", "贈與稅", "節稅",
            "台積電", "股利", "配息", "除息", "董事長", "董座", "經營權", "接班");
```

並在 `isAnecdote(...)` 附近（其他 private 判定 helper 旁）新增：

```java
    /** 發票／彩券中獎判定（Task 240）：命中專名，或「發票」與「開出」同時出現。 */
    private static boolean isLottery(String t) {
        return containsAny(t, LOTTERY) || (t.contains("發票") && t.contains("開出"));
    }

    /** 家事遺產糾紛判定（Task 240）：主題詞命中且未命中法制／稅制／市場主體豁免詞。 */
    private static boolean isFamilyEstate(String t) {
        return containsAny(t, ESTATE_TOPIC) && !containsAny(t, ESTATE_EXEMPT);
    }
```

**約束：**
- 兩個集合與 helper 必須完全照上面的內容，不得增刪關鍵詞（每一個詞的取捨都經 4807 則真實語料逐詞驗證）。
- 沿用既有 private helper `set(...)` 與 `containsAny(...)`，不要新增等價的工具方法。

### 240.4 `EditorialNewsFilter.trace()`：在 cascade 插入規則⓪b 與⓪c

在 `trace()` 中，緊接既有的 `isAnecdote` 那一步之後、`FINANCE` 那一步（`if (containsAny(t, FINANCE)) return "KEEP:finance";`）之前，插入：

```java
        // 0b) 發票／彩券中獎否決（Task 240）：此類必帶金額詞（千萬／加碼／億），不先於財經判定就永遠攔不到。
        if (isLottery(t)) return "DROP:lottery";

        // 0c) 家事遺產糾紛否決（Task 240）：標題雖多為非財經，但摘要常含「存款／房子」而在規則①被救回，
        //     故同樣必須凌駕 FINANCE；法制／稅制／市場主體三類由 ESTATE_EXEMPT 豁免。
        if (isFamilyEstate(t)) return "DROP:family-estate";
```

**約束：**
- 插入位置**必須**在 `FINANCE`（規則①）之前，否則兩條規則永遠沒有機會發言（這正是 Task 221 `ANECDOTE` 已確立的模式）。
- 兩條規則置於 `isAnecdote`（規則⓪）**之後**，維持既有規則⓪的判定與回傳字串不變。
- 不得更動既有規則⓪／①／①b／①c／②…⑨的順序與回傳字串。

### 240.5 `EditorialNewsFilter`：新增純財經來源專用的 `traceFinanceFeed` 與 `retainForFinanceFeed`

在既有 `retain(...)`／`keep(...)`／`trace(...)` 旁新增兩個 public 方法：

```java
    /**
     * 純財經來源（wantgoo／MoneyDJ）專用過濾（Task 240）：只套「三條凌駕 FINANCE 的否決集」
     * （⓪ 財經軼事、⓪b 發票彩券、⓪c 家事遺產），其餘一律保留。
     */
    public static List<NewsRow> retainForFinanceFeed(List<NewsRow> rows) {
        List<NewsRow> out = new ArrayList<>(rows.size());
        for (NewsRow r : rows) {
            if (traceFinanceFeed(text(r)).startsWith("KEEP")) out.add(r);
        }
        return out;
    }

    /**
     * 純財經來源的判定（Task 240），格式同 {@link #trace}。**刻意只跑三條凌駕 FINANCE 的否決集**——
     * 這三條的語意是「即使帶財經詞也無投資資訊量」，與來源是否財經專屬無關，故可跨來源套用。
     *
     * <p><b>刻意不套 LIFESTYLE／SOCIAL_ODDITY，也不套規則③～⑨的白名單 KEEP 與 non-finance-general 兜底</b>：
     * FINANCE 是為「從一般新聞版面挑出財經」而設的白名單，純財經站的財經用語大量不在其中。對線上語料實測，
     * 若對 wantgoo 套整套 trace()，41 則遭濾者含「Waymo傳2028年後終止合作 Uber跌逾4%」「〈華德動能訪廠〉
     * 樂估日本電巴市占率上看3成」「基本工資調漲至3萬」等明確財經；即使保留 FINANCE 豁免而僅加套 LIFESTYLE，
     * 仍誤殺「雄獅東北亞賞楓行程銷售破5成」「《DJ在線》旅行社下半年團費趨穩」「諾和諾德怒告禮來」等消費／
     * 旅遊類股報導，SOCIAL_ODDITY 的「越獄」亦誤中 AI jailbreak（「Hugging Face遭沙盒『越獄』攻擊」）。
     */
    public static String traceFinanceFeed(String text) {
        String t = text == null ? "" : text;
        if (isAnecdote(t)) return "DROP:anecdote";
        if (isLottery(t)) return "DROP:lottery";
        if (isFamilyEstate(t)) return "DROP:family-estate";
        return "KEEP:finance-feed";
    }
```

**約束：**
- `traceFinanceFeed` **不得**加入 `LIFESTYLE`／`SOCIAL_ODDITY`／`CHINA`／`GEO`／`POLITY` 任何一條，也**不得**以 `DROP:non-finance-general` 兜底——兜底改為 `KEEP:finance-feed`（純財經來源預設收錄）。
- `retainForFinanceFeed` 沿用既有 private helper `text(NewsRow)`（標題為主、有摘要則併入），不要另寫一份。
- 既有 `retain(...)`／`keep(...)`／`trace(...)` 的行為與簽章一律不動（混合型 feed 仍走整套 cascade）。
- **已知殘留（刻意接受，不要在本任務擴大範圍去修）**：純財經來源的**體育賽事軟文**仍會入庫，語料實測約 4／1219（「俄羅斯重返奧運舞台 國際奧委會暫時解除處罰」「AI眼中的世界盃8強：14個模型集體押阿根廷」「巔峰對決！世足賽決賽開踢前夕 八大AI模型…押注阿根廷2:1勝出」「白宮：川普出席世界盃決賽 預計與FIFA主席共同頒發冠軍金盃」）。這類確實符合使用者「影響不了經濟股市」的判準，但**正解是日後在 `traceFinanceFeed` 加一條窄的體育賽事否決集，而不是改套整套 `LIFESTYLE`**——後者會連帶誤殺消費／旅遊／運動品牌類股報導（雄獅賞楓銷售、旅行社團費、Nike 經銷商、adidas 贊助），得不償失。若使用者回報此類再另開任務。

### 240.6 `NewsFetchClient.fetchAll()`：把純財經來源接上 `retainForFinanceFeed`

檔案：`external-materials-service/src/main/java/com/steven/assets/externalmaterials/client/NewsFetchClient.java`

把目前的

```java
        // 純財經來源（玩股網／MoneyDJ）本就財經專屬，不套編輯政策過濾。
        out.addAll(safe("wantgoo", this::fetchWantgoo));
        out.addAll(safe("moneydj", this::fetchMoneydj));
```

改為

```java
        // 純財經來源（玩股網／MoneyDJ）：Task 240 起套「三條凌駕 FINANCE 的否決集」（財經軼事／發票彩券／
        // 家事遺產）——這三條的語意是「即使帶財經詞也無投資資訊量」，與來源是否財經專屬無關。**刻意不套
        // 整套 EditorialNewsFilter.retain**：FINANCE 是為「從一般新聞版面挑出財經」而設的白名單，套到純
        // 財經站會誤殺其財經用語不在白名單內的真財經新聞（實測 41 則，含「Uber跌逾4%」「華德動能訪廠」
        // 「基本工資調漲至3萬」）。
        out.addAll(safe("wantgoo", () -> EditorialNewsFilter.retainForFinanceFeed(fetchWantgoo())));
        out.addAll(safe("moneydj", () -> EditorialNewsFilter.retainForFinanceFeed(fetchMoneydj())));
```

**約束：**
- 美國財經 feed（CNBC／Nasdaq，`region=US`）**維持完全豁免**，不得套任何過濾——其為英文 feed，本過濾之關鍵詞皆為中文，套上去等於全部 `KEEP` 但徒增誤解。
- 既有 `ltn-business`／`ltn-politics`／`ltn-world`／`udn` 四支 `EditorialNewsFilter.retain(...)` 一律不動。
- 沿用既有 `safe(name, Fetcher)` 的逐來源 graceful 慣例（單一來源失敗只 log warn、回空 list）。

### 240.7 `EditorialNewsFilterTest.java`：新增回歸錨點

檔案：`external-materials-service/src/test/java/com/steven/assets/externalmaterials/client/EditorialNewsFilterTest.java`

在既有最後一個 `@Nested`（`SouthChinaSeaSkirmish`）之後，新增兩個 `@Nested` 類別（沿用檔內既有 `assertKeep`／`assertDrop` 靜態 helper）：

```java
    @Nested
    @DisplayName("發票／彩券中獎與家事遺產（Task 240）")
    class LotteryAndEstate {
        @Test void 發票彩券中獎濾除() {
            assertDrop("7-11開出千萬中獎發票　花150元買飲品成幸運兒");            // 使用者回報案例
            assertDrop("只花10元抱回200萬！7-ELEVEN 開出一張千萬、七張百萬發票");  // 發票∧開出 AND 組合
            assertDrop("大樂透頭獎連17摃  加碼100萬獎只剩7組");                   // 語料實例：加碼＝FINANCE
            assertDrop("快對發票！5-6月統一發票千萬獎「38548029」 完整獎號在這裡");
            assertDrop("最好的生日禮物！美國男買刮刮樂爽中3千萬");
        }
        // 金額詞 千萬／加碼 是真財經的常見訊號，不得因本規則而誤殺
        @Test void 金額詞的真財經不誤殺() {
            assertKeep("金管會上半年裁罰出爐 銀行業罰鍰較去年增加近兩千萬");
            assertKeep("＜財經週報-青安3.0＞青安3.0千萬額度不夠用？ 全台16縣市平均房貸不到千萬");
            assertKeep("影／混凝土大廠永固-KY董座砸9千萬炒股護盤 一家三人涉證交法送辦");
            assertKeep("AI巨頭財報前瞻一表看！微軟、Meta、蘋果下周開獎 聚焦資本支出");  // 開獎刻意排除
            assertKeep("台積電發放特別獎金 每人平均逾百萬");                            // 特別獎刻意排除（特別獎金）
        }
        @Test void 家事遺產糾紛濾除() {
            assertDrop("很多家庭急著分遺產 忘了另一位父母還活著");                  // 使用者回報案例（標題）
            assertDrop("他繼承父親1500萬遺產全丟進股市 慘痛代價曝光了");
            assertDrop("最受寵卻一毛都沒分到！阿公留4千萬遺產「被獨漏」 長孫看完遺囑傻眼了");
            assertDrop("孫女獲祖父母數百萬美元遺產 被要求發誓保密  父因「妻顧人怨」只能拿數萬美元");
        }
        // 法制／稅制／市場主體三類豁免
        @Test void 遺產的法制稅制與市場主體不誤殺() {
            assertKeep("遺產可免分兄弟姊妹？立院朝野拍板 特留分修法7/28處理");
            assertKeep("被繼承人遺有應收股利  遺產稅申報一次看");
            assertKeep("台積電配息創新高、繼承股票先別high！國稅局爆「這天」成股利報稅分水嶺");
            assertKeep("三星搶攻AI晶片商機！將龍仁半導體國家產業園區首座晶圓廠量產提前一年"); // 家產刻意排除
        }
        // 「集團」刻意不列入 ESTATE_EXEMPT：它是高頻泛詞且同時在 FINANCE，收了會讓此類落回 KEEP:finance
        @Test void 詐騙集團奪產仍應濾除() {
            assertDrop("詐騙集團騙走老翁遺產 檢警偵辦中");
        }
    }

    @Nested
    @DisplayName("六都軟文與純財經來源（Task 240）")
    class CivicSoftAndFinanceFeed {
        @Test void 六都純軟性行程濾除() {
            assertDrop("侯友宜、谷立言、片山和之同遊新北  搭船欣賞淡江大橋");        // 使用者回報案例
            assertDrop("（新北）蘇巧慧陪小朋友開心玩 李四川參拜宮廟");
            assertDrop("李四川現身動畫路跑 盼打造新北IP活動城市 釣出蔡詩萍留言");
            assertDrop("高雄佛光山修行》蔡壁如宣布出家 預告此時再相見");
            assertDrop("坪林川友會授旗 李四川：強化交通帶動新北茶鄉觀光升級");
        }
        // 只有財經（規則①）豁免；強政治（POLITY_STRONG，規則⑤）在 LIFESTYLE（規則②）之後，不豁免
        @Test void 六都的財經不誤殺() {
            assertKeep("全球最大AI晶片先進封裝廠　台積電嘉義二期動土前7座宮廟遶境祈福　員工、在地股東以信徒身分同行");
            assertKeep("新北大巨蛋落腳樹林！蘇巧慧謝侯友宜、盼加速完善當地交通建設");
            assertKeep("高雄市待售新成屋逼近1.6萬宅  楠梓和鳳山最多");
        }
        // 「參拜」刻意不收（只收「宮廟」）：裸「參拜」會在規則②攔下靖國神社類中日關係事件，
        // 而規則③CHINA／⑤POLITY_STRONG 都在其後、無從救回
        @Test void 參拜靖國神社類不誤殺() {
            assertKeep("日相高市早苗參拜靖國神社 中國外交部強烈抗議");
            assertKeep("習近平參拜毛澤東紀念堂 中共高層全數到齊");
        }
        // 陽明＝陽明海運，不得誤中陽明交大；運價為配套（唯一公司訊號為「陽明」的航運標題靠它保住）
        @Test void 陽明子字串不誤中() {
            assertDrop("陽明交大教評會爆爭議 教育部長：組成有瑕疵");                // 使用者回報案例
            assertKeep("運價走揚  陽明6月營收165.91億年月雙增");
            assertKeep("美新關稅敲定後 陽明：運價後市將明朗");
            assertKeep("陽明蔡豐明：運價雖跌 貨量仍滿 後市視美關稅政策而定");        // 語料實例：須用完整標題
            assertKeep("陽明、台驊6月營運亮麗");
            assertKeep("陽明7月運價走弱 貨量持平");                                // 運價為唯一財經訊號
            assertKeep("SCFI運價指數周跌4.3%");
            assertKeep("鴻海研究院聯手陽明交大 研發超大容量矽光子技術");
        }
        // 純財經來源只套三條凌駕 FINANCE 的否決集，其餘一律保留
        @Test void 純財經來源只濾三類雜訊() {
            assertThat(EditorialNewsFilter.traceFinanceFeed("很多家庭急著分遺產 忘了另一位父母還活著 當父親或母親其中一位離世後，多數家庭討論的第一件事，往往不是照顧，而是繼承，房子要不要賣？存款怎麼分？"))
                    .startsWith("DROP");   // 使用者回報案例：標題＋摘要（摘要含「存款」會在整套 cascade 被 KEEP:finance 救回）
            assertThat(EditorialNewsFilter.traceFinanceFeed("完整獎號一次看！5、6月統一發票開獎千萬特別獎「38548029」")).startsWith("DROP");
            assertThat(EditorialNewsFilter.traceFinanceFeed("68歲退休翁嫌定期定額賺太慢！看到半導體股狂飆就衝了 下場曝光")).startsWith("DROP");
            // 以下在整套 cascade 會被誤殺，故純財經來源刻意不套 LIFESTYLE／SOCIAL_ODDITY／non-finance-general
            assertThat(EditorialNewsFilter.traceFinanceFeed("旅遊市況熱 雄獅東北亞賞楓行程銷售破5成 將擴大拓郵輪版圖")).startsWith("KEEP");
            assertThat(EditorialNewsFilter.traceFinanceFeed("減肥藥大戰打進法院！諾和諾德怒告禮來廣告誤導")).startsWith("KEEP");
            assertThat(EditorialNewsFilter.traceFinanceFeed("Hugging Face遭OpenAI模型沙盒「越獄」攻擊 中國AI工具GLM-5.2臨危救場")).startsWith("KEEP");
            assertThat(EditorialNewsFilter.traceFinanceFeed("自駕合作恐生變 Waymo傳2028年後終止合作 Uber跌逾4%")).startsWith("KEEP");
            assertThat(EditorialNewsFilter.traceFinanceFeed("基本工資調漲至3萬 商總：對缺工問題仍無解、對雇外勞企業受衝擊最大")).startsWith("KEEP");
        }
    }
```

**約束：**
- 這些標題絕大多數取自線上真實語料；不得為了讓測試過而竄改關鍵詞集。
- 若任一 `assertKeep`／`assertDrop` 失敗，代表關鍵詞集或 cascade 位置有誤，**修的是實作、不是把測試放寬**。
- `assertThat` 已由檔頭既有的 `import static org.assertj.core.api.Assertions.assertThat;` 提供，不需新增 import。

### 240.8 不需要改動的地方（明確界定範圍，避免過度施工）

- **不改 backend／bff／frontend**：過濾發生在 `external-materials-service` 落庫之前，前端「爬蟲資訊查詢」與「今日股市分析」只讀已過濾結果，無契約變更。
- **不改 `PublicInfoStockFilter`**：個股過濾（Task 178）與編輯政策為兩層獨立過濾，本任務不觸及。
- **不追溯清除舊列**：與所有既有過濾規則一致，本規則只在抓取時作用；已入庫的 4 則使用者回報雜訊不會被回溯刪除（`news_headline` 30 天保留期到期自然滾出）。
- **無 `@Scheduled` 變更**：不涉及排程，`SchedulePublicBffController.JOBS` 不動。
- **無 Liquibase changeset**：純關鍵詞邏輯變更。

## 驗證

**單元測試（本規則的權威功能驗證；`external-materials-service` 為獨立 Maven module，須 `cd` 進該目錄，無 root pom）：**

```bash
cd external-materials-service && /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q test -Dtest=EditorialNewsFilterTest
```

（`EditorialNewsFilterTest` 為純靜態呼叫、不使用 Mockito，毋須 `-DargLine` byte-buddy 參數。斷言 `Failures: 0, Errors: 0`，新增的 `LotteryAndEstate` 5 個與 `CivicSoftAndFinanceFeed` 5 個測試方法全過（新增合計 10 個方法），既有 26 個零回歸。）

**整模組建置：**

```bash
cd external-materials-service && /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q package
```

**跑起來真的有這個功能（本專案無 dev server，image rebuild + container recreate 才算改好）：**

```bash
cp /Users/steven/Project/asset-management/.env . 2>/dev/null || true
docker compose -p asset-management build --no-cache external-materials-service
```

```bash
docker compose -p asset-management up -d --no-deps --force-recreate external-materials-service
```

**非 stale jar 驗證（JVM service 的 cached build 曾出過只有前端更新的坑）：**

```bash
docker run --rm --entrypoint sh asset-management-external-materials-service:latest -c 'cd /tmp && unzip -o -q /app/app.jar "BOOT-INF/classes/com/steven/assets/externalmaterials/client/EditorialNewsFilter.class" && LC_ALL=C grep -ac "family-estate" BOOT-INF/classes/com/steven/assets/externalmaterials/client/EditorialNewsFilter.class'
```

（期望輸出 `1`，即新 image 的 `.class` 常數池含 `family-estate`；另可同法確認 `lottery`／`陽明海運`／`同遊`／`finance-feed`。macOS `strings` 會把 `.class` 的 `CAFEBABE` 魔數誤判為 Mach-O fat binary，故用 `LC_ALL=C grep -a`。）

**健康檢查（external-materials-service 無對外埠、容器內無 curl，healthcheck 用 wget）：**

```bash
docker inspect --format '{{.State.Health.Status}}' asset-external-materials-service
```

**部署後對「未來抓取」的行為確認（非追溯）：** 重建後的下一輪 `NewsPoller`（warmup 或 08:20／11:30／18:00 Asia/Taipei）起，四則使用者回報案例形態的新聞即不再落入 `news_headline`。可比對該輪 log 的 `新聞抓取 wantgoo：N 則` 與落庫筆數，並確認無例外。

## 完成報告

**實際改動檔案：**
- `external-materials-service/.../client/EditorialNewsFilter.java`：`FINANCE` 的 `陽明`→`陽明海運`、補 `運價`；`LIFESTYLE` 增列 10 個觀光行程／禮儀性活動詞；新增 `LOTTERY`（8 詞）／`ESTATE_TOPIC`（4 詞）／`ESTATE_EXEMPT`（24 詞）三個 `Set` 與 `isLottery`／`isFamilyEstate` helper；`trace()` cascade 於 `isAnecdote`（⓪）之後、`FINANCE`（①）之前插入⓪b `DROP:lottery` 與⓪c `DROP:family-estate`；新增 public `traceFinanceFeed(String)`／`retainForFinanceFeed(List<NewsRow>)`。
- `external-materials-service/.../client/NewsFetchClient.java`：wantgoo／moneydj 兩支改為 `EditorialNewsFilter.retainForFinanceFeed(...)`；CNBC／Nasdaq 維持完全豁免、ltn／udn 四支維持 `retain(...)` 不動。
- `external-materials-service/src/test/.../EditorialNewsFilterTest.java`：新增 `@Nested LotteryAndEstate`（5 方法）與 `@Nested CivicSoftAndFinanceFeed`（5 方法）。
- `spec/requirements.md`（Requirement 31 新增 Task 240 AC）、`spec/design.md`（cascade 補⓪b／⓪c 與純財經來源接線）。
- 未改 backend／bff／frontend／`PublicInfoStockFilter`；無 Liquibase changeset、無 `@Scheduled` 變更（如計畫）。

**驗證輸出：**
- 單元測試：`Tests run: 36, Failures: 0, Errors: 0`（既有 26 零回歸＋新增 10 全過；`mvn -q test -Dtest=EditorialNewsFilterTest`，逐 `@Nested` 彙總）。
- 整模組建置：`mvn -q package` 成功，產出 `target/asset-external-materials-service-1.0.0.jar`。
- 全語料 A/B（4807 則、baseline＝現況）：**新增濾除 26 則、反向翻轉 0**；分布 `lottery` 13／`lifestyle` 8／`family-estate` 4／`non-finance-general` 1；純財經來源濾 3／1219。
- 4 則使用者案例終判：`DROP:family-estate`／`DROP:lifestyle`／`DROP:non-finance-general`／`DROP:lottery`，全數如預期且由預期的規則攔下。
- spec 對抗式審查（`/spec-review`）：第 3 輪 **quality_score 9/10**（門檻 8）通過；前兩輪各 7/10 的 findings（強政治誤稱豁免、`參拜` 誤殺靖國神社、陽明語料截斷引用與計數、`LOTTERY` 零樣本詞、`ESTATE_EXEMPT` 的 `集團`、`千萬` 統計錯誤、測試方法計數漂移等）已全數修正。

**與原計畫的偏差：**
- 無實質偏差。三組修正與插入位置皆照計畫；詞集在審查過程中收斂得比初稿更嚴（`LOTTERY` 由 11 詞縮為 8 詞、`LIFESTYLE` 觀光詞由 11 詞縮為 10 詞移除 `參拜`、`ESTATE_EXEMPT` 移除 `集團`），並新增 `運價` 作為窄化 `陽明` 的配套——皆為審查發現的誤殺風險所驅動，已回寫進本檔與 requirements／design。
