# [t241] 濾除體育賽事新聞，但保留「影響國際政治的重大事件」（`EditorialNewsFilter` 新增 `SPORT` 規則①d）

**對應 Requirements:** Requirement 31（今日股市分析——每交易日於可設定時點由 AI 判斷當天台股走向；本任務收斂餵給分析的本地新聞收錄範圍，濾掉對經濟、股市、政局皆無影響的體育賽事報導）
**前置任務:** 無（Task 199 建立 `EditorialNewsFilter` cascade、Task 221 加 `ANECDOTE`／`SOCIAL_ODDITY`、Task 229 加 `SCS_SKIRMISH`、Task 240 加 `LOTTERY`／`ESTATE` 並建立 `traceFinanceFeed`，皆已在 main）
**Liquibase changeset:** 無（純關鍵詞邏輯變更，不新增資料表／欄位；`news_headline` 結構不變）

## 背景

**使用者需求（2026-07-27 原話）：** 「刪除所有體育賽事，除非體育賽事發生影響國際政治的大新聞，例如恐怖攻擊。」

這同時要求**兩個方向相反**的變更：

1. **更嚴**：純財經來源（`wantgoo` 玩股網／`moneydj`）的體育新聞目前**全部漏網**。Task 240 建立的 `traceFinanceFeed()` 只跑三條凌駕 `FINANCE` 的否決集（`isAnecdote`／`LOTTERY`／`ESTATE`），刻意不跑 `LIFESTYLE`，故「AI眼中的世界盃8強：14個模型集體押阿根廷」「巔峰對決！世足賽決賽開踢前夕 八大AI模型…押注阿根廷2:1勝出」這類純體育軟文直接落庫。
2. **更寬**：一個目前**完全不存在**的豁免。混合型 feed（`ltn`／`udn`）的體育新聞由 `LIFESTYLE`（規則②）無條件濾除，「慕尼黑奧運恐攻」「波士頓馬拉松爆炸案」這類影響國際政治的重大事件同樣會被濾掉。

**正確行為（本任務）：** 把體育詞從 `LIFESTYLE` 抽成獨立的 `SPORT` 集合，新增規則①d，並在其內部建立兩層豁免；兩個入口（`trace` 與 `traceFinanceFeed`）共用同一支判定 helper。

**設計約束的來源（對線上 4835 則真實 `news_headline` 台灣新聞全量對照與對抗測試得出，務必遵守）：**

1. **`SPORT` 絕對不可凌駕 `FINANCE`（規則①）。** 中文財經媒體大量借用體育語彙，語料實證：「高股息ETF受益人數 0056奪冠」「台股基金績效大爆發 元大高股息優質龍頭飆破120％奪冠」「好市多狂吸400萬會員 單店平均營收百億霸氣封王」「台灣大6月EPS 0.52元 蟬聯2個月電信股EPS冠軍」「卓榮泰喊話打造金融世界盃」，且 `udn` 有整個「隱形冠軍／…」專欄（語料 8 則以上）。**`球員`／`冠軍`／`奪冠`／`封王`／`電競`／`教練`／`金牌`／`賽事` 之所以安全，完全靠 `FINANCE` 排在 `SPORT` 之前。** 任何把 `SPORT`（或其子集）移到 `FINANCE` 之前的重構，第一天就會誤殺上列全部，外加 ASML「全球員工」股票獎勵、華碩電競周邊營收、友達電競顯示器、壽險智慧教練、高爾夫教練 AI 商機、黃金牌價。
2. **體育帶動的產業／營收新聞必須保留**（符合使用者一貫的「影響經濟」判準）：「世足經濟學／超過20個國家隊採用 台灣紡織大軍…」「世足經濟學／鞋尖上的台灣」「中鋼、燁輝搶澳洲奧運基建商機」「2026世足賽助攻 中華電信MOD收視再寫新猷」「華碩電競周邊營收翻倍」「世足加持 日本電視出貨量創今年高」「世界盃決賽Nike無緣亮相 adidas成贊助商大贏家」「2026世界盃落幕 FIFA狂攬90億美元」。
3. **豁免必須是正向 `KEEP`，不能只是「不否決、讓 cascade 續行」。** 實測反證：「俄羅斯重返奧運舞台 國際奧委會暫時解除處罰」若只是跳過否決往下走 → ②`LIFESTYLE` 無命中 → ③`CHINA` 無 → ④`GEO_REGION`(俄羅斯) ∧ `GEO_TRIGGER` 落空（「解除處罰」不含任何觸發詞）→ ⑤`POLITY_STRONG` 無 → 最終仍是 `DROP:non-finance-general`。「巴黎奧運遭恐怖攻擊」同理（法國不在 `GEO_REGION`）。**規則③④⑤救不回來**，故豁免必須在規則①d 內部直接回傳 `KEEP`。
4. **豁免只收人為攻擊與國家層級政治，不收意外災難。** 使用者 2026-07-27 裁示：「『足球場看台倒塌 逾百人罹難』如果不是人為攻擊，是建築物太爛，不會影響到國際政治、經濟、股市，這類新聞不要。」故場館事故詞（踩踏／倒塌／坍塌／暴動／騷亂）與傷亡數字守門一律不納入——它們指向工安與管理不善，而非國際政治事件。（此裁示推翻了設計初稿以希斯堡慘案為由納入場館災難的做法。）
5. **豁免詞不可單層。** 對抗測試實證：單層 `containsAny` 豁免會被一般體育新聞大量誤觸——`制裁`（「聯盟制裁違規球隊 罰款500萬」）、`抵制`（「球迷抵制球隊經營不善」）、`杯葛`（「世界盃抽籤爭議 球迷杯葛主辦單位」）、`爆炸`（「電競選手人氣爆炸 直播訂閱數翻倍」）、`暗殺`（「職棒球員酒駕遭球團暗殺式冷凍」）**5 個測試案例 5 個全部被誤救**，等同讓整個體育否決集失效。故必須拆成「強豁免（語意鎖死，單獨命中即救）」與「弱豁免（需與國家／國際政治層級守門詞 AND）」兩層。
6. **刻意排除的候選詞（逐詞實測會誤殺／誤救，勿加入）：**
   - `SPORT` 排除 `運動`（體育用法 0/16；子字串誤中「營**運動**能」實證 3 則〔麗豐-KY／大立光法說／汎瑋〕，另 6 則為葉門叛軍「青年**運動**」＝真地緣油價新聞）、`國家隊`（9 樣本體育用法 0 則，全為「無人機國家隊／SMR 國家隊／機器狗國家隊／中國國家隊護盤」，且會直接誤殺硬約束 2 明列的世足經濟學紡織則）、`決賽`（6 樣本中 4 則為必保產業／關稅新聞；必保的「世界盃決賽Nike無緣亮相」`FINANCE` 命中為零，連財經閘門都救不了）、`FIFA`（4 樣本中 2 則必保）、`足總`（裸詞會被「資金不**足總**額」子字串誤中，改收四字專名 `國際足總`）、`奧會`（裸詞誤中「中華奧會公布代表團名單」例行行政新聞，且與豁免詞 `奧會模式` 語意打架，改收 `奧委會`）、`助攻`／`終場`／`開打`／`押注`／`競賽`／`逆轉`／`稱霸`／`霸榜`／`黑馬`／`賽局`（全為財經比喻，語料體育用法合計 0：助攻 0/27〔營收助攻／AI助攻〕、終場 0/16〔台股盤後跌642點〕、開打 0/7〔稀土戰開打〕、競賽 0/8〔AI競賽〕、逆轉 0/7〔新台幣午後大逆轉〕、稱霸 0/3〔0050續稱霸〕）、`衛冕`／`王者`／`全壘打`／`破紀錄`／`蟬聯`／`爆冷`（語料零樣本，且同族比喻密集〔市值王寶座×2、ETF人氣王×3、稅收破紀錄、非農就業爆冷〕；**體育否決集是負向規則，零樣本詞在此誤中即誤刪財經**，與豁免集的零樣本例外方向相反，不得比照辦理）。
   - 豁免排除 `禁賽`（「球員禁賽3場／藥檢陽性遭禁賽」是台灣體育紀律報導第一用語，收進豁免等同讓否決集失效；驗證案例用字是「解除處罰」而非「禁賽」，排除不影響召回）、`攻擊`／`襲擊`（已在 `GEO_TRIGGER`，且體育語彙本身有「攻擊力／進攻」）、裸 `恐怖`（「恐怖打線／恐怖球速」）、裸 `炸彈`（「定時炸彈／債務炸彈」財經高頻比喻）、裸 `槍擊`（射擊項目「手槍擊出10環」）、`槍手`（「代寫槍手／得分槍手」）、`刺殺`（棒球術語「刺殺跑者／刺殺出局」，會直接放行棒球報導）、`中華台北`（出現在每一則我國選手的國際賽例行報導，誤中率極高；台灣專屬政治語意改由零歧義的 `奧會模式` 承接）、`引爆`（語料 23 則中唯一與體育詞共現者，正是**應濾除**的「世足》拍照嘲諷梅西…引爆球迷炎上」）。

## 要做什麼

### 241.1 `EditorialNewsFilter.java`：把體育詞自 `LIFESTYLE` 抽出為 `SPORT`

檔案：`external-materials-service/src/main/java/com/steven/assets/externalmaterials/client/EditorialNewsFilter.java`

**刪除** `LIFESTYLE` 中的整個體育區塊（目前的 `// 體育（全項；影響股市者已由規則1財經先留）` 註解與其下 4 行共 44 個詞），**原樣搬進**新的 `SPORT` 常數並增列 5 個賽事專名。

**約束：**
- 搬移的 44 詞**不增不減、不改字**，**詞集搬移本身**為 delta-neutral（實測 ltn+udn 3612 則 KEEP/DROP 翻轉 0；豁免層另使混合型 feed 對恐攻類事件由 DROP 轉 KEEP，此為本任務刻意的行為變更，現行語料無此類樣本故實測翻轉 0），使本次上線風險全部隔離在新增的詞上，任何回歸都能精確歸因。
- **特別不得趁機刪掉 `球員`／`冠軍`／`奪冠`／`封王`／`電競`／`教練`／`金牌`／`賽事` 這些高風險泛詞**——它們靠 `FINANCE` 排在前面才安全，刪除會降低對真體育新聞的召回，而保留成本為零。
- `LIFESTYLE` 其餘（餐飲、旅遊、時尚、演藝、消費、汽車、社會獵奇）全部不動；Task 240 的「禮儀性活動」群（同遊／搭船／宮廟／遶境／路跑／授旗／嘉年華／水樂園／合唱團／出家）**刻意留在 `LIFESTYLE`**，維持該 task 的分類敘事（`路跑` 雖是體育賽事，但它在該群是為攔截政治人物行程而設；移動對判定 delta 為 0，唯一差別是豁免可及性，而「路跑遭恐攻」的原型〔波士頓馬拉松〕已由 `SPORT` 的 `馬拉松` 覆蓋並實測通過）。

### 241.2 `EditorialNewsFilter.java`：新增五個常數集

在既有 `SCS_ESCALATION` 之後、`retain(...)` 之前新增：

```java
    // ===== 體育賽事（Task 241）：置於 FINANCE 之後、LIFESTYLE 之前 =====
    // 使用者需求：「刪除所有體育賽事，除非體育賽事發生影響國際政治的大新聞，例如恐怖攻擊」。
    // 44 詞自 LIFESTYLE 原樣搬出（不增不減），另增 5 個零誤中的賽事專名（語料各 1 則、皆為目標）。
    //
    // ⚠ **維護警告：本集合絕對不可移到 FINANCE（規則①）之前。**
    // 球員／冠軍／奪冠／封王／電競／教練／金牌／賽事 之所以安全，完全靠 FINANCE 先判。移到前面第一天
    // 就會誤殺：ASML「全球員工」股票獎勵、udn「隱形冠軍」專欄 8 則、0056奪冠、台股基金飆破120%奪冠、
    // 好市多霸氣封王、台灣大EPS冠軍、華碩電競周邊營收翻倍、友達電競顯示器、壽險智慧教練、
    // 高爾夫教練AI商機、卓榮泰金融世界盃、黃金牌價。
    //
    // 刻意排除（實測會誤殺，勿加入）：運動（誤中「營運動能」3 則、葉門「青年運動」6 則）、
    // 國家隊（9 樣本體育用法 0，全是無人機／SMR／機器狗國家隊、中國國家隊護盤）、決賽（6 樣本中
    // 4 則必保）、FIFA（4 樣本中 2 則必保）、足總（誤中「資金不足總額」）、奧會（誤中「中華奧會」
    // 例行新聞）、助攻／終場／開打／押注／競賽／逆轉／稱霸／霸榜／黑馬／賽局（全為財經比喻，語料
    // 體育用法合計 0）、衛冕／王者／全壘打／破紀錄／蟬聯／爆冷（語料零樣本；體育否決是負向規則，
    // 零樣本詞誤中即誤刪財經，不比照豁免集的零樣本例外）。
    private static final Set<String> SPORT = set(
            "世足", "世界盃", "奧運", "冬奧", "亞運", "職棒", "中職", "大聯盟", "美職", "日職", "英超", "足球",
            "棒球", "籃球", "排球", "網球", "羽球", "桌球", "高爾夫", "撞球", "游泳", "田徑", "馬拉松", "賽車",
            "拳擊", "柔道", "跆拳", "舉重", "電競", "選手", "球員", "球星", "球隊", "教練", "金牌", "銀牌", "銅牌",
            "奪冠", "封王", "冠軍", "賽事", "球迷", "揮棒", "開球",
            "奧委會", "國際足總", "開踢", "金盃", "公開賽");

    // 強豁免（Task 241）：恐攻／重大暴力事件專名，語意鎖死，單獨命中即保留。
    // 刻意只收複合詞：裸 恐怖（恐怖打線／恐怖球速）、裸 炸彈（定時炸彈／債務炸彈）、裸 槍擊
    // （射擊項目「手槍擊出10環」）、槍手（代寫槍手／得分槍手）、刺殺（棒球術語，會放行棒球報導）
    // 全部排除。中華台北 亦排除（每則我國選手國際賽報導都有），台灣專屬政治語意由 奧會模式 承接。
    private static final Set<String> SPORT_TERROR = set(
            "恐攻", "恐怖攻擊", "恐怖襲擊", "恐怖主義", "恐怖組織", "恐怖分子", "恐怖份子",
            "自殺炸彈", "炸彈客", "槍擊案", "爆炸案", "爆炸事件", "人質", "挾持",
            "國際奧會", "國際奧委會", "奧會模式");

    // 弱豁免（Task 241）：這些詞在體育語境本身極常見，**必須**與國家／國際政治層級守門詞同時命中。
    // 對抗測試實證：若單獨命中即豁免，「聯盟制裁違規球隊」「球迷抵制球隊」「世界盃抽籤爭議 球迷杯葛
    // 主辦單位」「電競選手人氣爆炸」「職棒球員酒駕遭球團暗殺式冷凍」5 則全部被誤救，否決集等同失效。
    // ⚠ **守門詞絕對不可用 GEO_REGION／CHINA／POLITY_STRONG 這類「有沒有提到某個國家或政治角色」的
    // 集合**——體育本質上就是國際的，巴西／韓國／越南／中國 是體育報導的日常詞彙。對抗測試實證：用它們
    // 當守門時，「世界盃巴西隊球迷抵制主辦單位售票制度」「韓國職棒球星遭球團驅逐出隊」「中國羽球選手
    // 遭禁藥制裁」等 8 則一般體育新聞 8 則全部被誤救。同理 政府（「政府制裁禁藥球員」）與 總統
    // （子字串誤中「**總統盃**」全國羽球錦標賽——本專案 北市⊂竹北市、陽明⊂陽明交大 的同型陷阱）
    // 亦刻意排除。守門只用下列語意鎖死於「國家層級政治行為」的詞。
    //
    // **只收人為攻擊與國家層級政治，不收意外災難**（使用者 2026-07-27 裁示：「看台倒塌 逾百人罹難」
    // 若非人為攻擊而是建築物太爛，影響不了國際政治／經濟／股市，這類不要）。故場館事故詞
    // （踩踏／倒塌／坍塌／暴動／騷亂）與傷亡數字守門一律不納入——它們指向工安與管理不善。
    private static final Set<String> SPORT_GEO = set("抵制", "杯葛", "制裁", "爆炸", "暗殺", "驅逐");
    private static final Set<String> SPORT_GEO_GUARD = set(
            "外交", "主權", "代表權", "國旗", "國歌", "多國", "聯合國",
            "反恐", "邦交", "斷交", "正名", "國安", "戰爭", "入侵");

    // FINANCE 白名單在「體育帶動之產業／贊助／關稅」報導上的已知召回缺口補丁（Task 241）。
    // **只在體育否決這一條當豁免用，不改動 FINANCE 本身**，避免與本任務無關的全域回歸。
    //   贊助商：moneydj「世界盃決賽Nike無緣亮相 adidas成贊助商大贏家」FINANCE 命中＝空，但屬必保
    //           （運動用品股報導）。全語料 贊助商 僅 1 則＝該則本身，爆炸半徑 1。
    //   加税  ：wantgoo「無物皆稅！FIFA世足冠軍賽…川普痛批並威脅要加税」用簡體「税」，FINANCE 的
    //           加稅／關稅 是繁體「稅」故完全漏接。全語料 加税 僅 1 則＝該則本身。這是該則標題自身的
    //           錯字（前半寫正體「無物皆稅」、後半誤植簡體「加税」），非繁簡管道缺口——全 DB 5625 列
    //           含「税」者僅此 1 列，另查 关税／股价／经济／营收 等 13 個常見簡體形皆 0 則。
    private static final Set<String> SPORT_ECON_EXEMPT = set("贊助商", "加税");
```

**約束：**
- 五個集合必須完全照上面的內容，不得增刪關鍵詞（每個詞的取捨都經 4835 則真實語料逐詞驗證與對抗測試）。
- **不得**加入場館事故詞（踩踏／倒塌／坍塌／暴動／騷亂）或傷亡數字守門。使用者 2026-07-27 明確裁示：意外／管理不善造成的傷亡不算「影響國際政治」，這類新聞不要。附帶好處是避免「世足決賽3死角戰術解析」這類含 `3死` 的標題被誤救。

### 241.3 `EditorialNewsFilter.java`：新增 `traceSport` helper

在 `isAnecdote(...)` 附近（其他 private 判定 helper 旁）新增：

```java
    /**
     * 體育賽事否決判定（Task 241）。回傳 {@code null} ＝本規則不表態，交由 cascade 續行。
     *
     * <p>兩層豁免：強豁免（{@link #SPORT_TERROR}，語意鎖死，單獨命中即保留）與弱豁免
     * （{@link #SPORT_GEO} ∧ {@link #SPORT_GEO_GUARD}）。豁免採**正向 KEEP** 而非「跳過否決續行 cascade」——實測後者救不回來：
     * 「俄羅斯重返奧運舞台 國際奧委會暫時解除處罰」續行後 ②③⑤ 皆無命中、④ 的 GEO_TRIGGER 落空，
     * 最終仍是 DROP:non-finance-general。
     *
     * <p><b>FINANCE 閘門內建於本方法</b>，使 {@link #trace} 與 {@link #traceFinanceFeed} 共用同一份
     * 語意、不會漂移（在 trace() 中規則① 已先回傳，此處的 FINANCE 檢查恆為 false，屬刻意的冗餘防呆）。
     */
    private static String traceSport(String t) {
        if (!containsAny(t, SPORT)) return null;
        if (containsAny(t, SPORT_TERROR)) return "KEEP:sport-intl-politics";
        if (containsAny(t, SPORT_GEO) && containsAny(t, SPORT_GEO_GUARD)) return "KEEP:sport-intl-politics";
        if (containsAny(t, FINANCE) || containsAny(t, SPORT_ECON_EXEMPT)) return null;
        return "DROP:sport";
    }
```

**約束：**
- **`SPORT` 成員檢查必須最先、`DROP:sport` 必須最後**；中間三步（強豁免／弱豁免／財經閘門）的相對順序**經實測不影響收錄結果**（全語料 diff＝0）——因為在 `trace()` 中規則① 已吃掉所有 `FINANCE` 命中，而在 `traceFinanceFeed()` 中閘門回 `null` 會落到兜底 `KEEP:finance-feed`、豁免回 `KEEP:sport-intl-politics`，兩條都是 KEEP。**故此處只影響 label 不影響收錄，不要為「順序不可調換」寫下未經驗證的斷言。**
- 回傳 `null` 的語意是「本規則不表態」，**不是** KEEP；`trace()` 與 `traceFinanceFeed()` 各自的後續步驟會決定最終結果。

### 241.4 `trace()`：插入規則①d

在 `trace()` 中，緊接 ①c `SCS_SKIRMISH` 之後、② `LIFESTYLE` 之前插入：

```java
        // 1d) 體育賽事否決（Task 241）：使用者需求「刪除所有體育賽事，除非發生影響國際政治的大新聞」。
        //     **必須置於規則①FINANCE 之後**（硬約束：冠軍／奪冠／封王／電競／球員 大量是財經比喻）；
        //     **必須置於規則②LIFESTYLE 之前**：241.1 已把體育詞自 LIFESTYLE 移出，故純體育標題不會
        //     被②攔下；真正的理由是**體育事件常同時命中非體育的 LIFESTYLE 詞**——實測「奧運開幕演唱會
        //     遭恐怖攻擊 多國元首緊急撤離」（演唱會）、「世界盃球迷粉絲見面會爆炸案 主辦國提升反恐」
        //     （粉絲），①d 在②後會被判 DROP:lifestyle、在②前才 KEEP:sport-intl-politics。
        //     排在 ①b SOCIAL_ODDITY／①c SCS 之後是刻意的：讓「奧運選手越獄」仍由 SOCIAL_ODDITY 處理。
        String sport = traceSport(t);
        if (sport != null) return sport;
```

**約束：** 不得更動既有規則⓪／⓪b／⓪c／①／①b／①c／②…⑨ 的順序與回傳字串。

### 241.5 `traceFinanceFeed()`：接上同一支 helper

在三條 ⓪ 否決之後、`return "KEEP:finance-feed";` 兜底之前插入：

```java
        // 體育賽事否決（Task 241）：與 trace() 共用 traceSport()，FINANCE 閘門內建其中。
        // 這裡沒有規則①，故純財經來源的體育新聞全靠 traceSport() 內建的 FINANCE 閘門豁免真財經——
        // 無閘門實測 1223 則中 12 則命中 SPORT、其中 8 則是誤殺（誤殺率 67%），含「世足加持 日本電視
        // 出貨量創今年高」「2026世界盃落幕 FIFA狂攬90億美元」「iPhone 17霸榜奪冠」「〈房產〉江子翠
        // 交易量奪冠」。加上閘門後這些全部回到 KEEP。
        String sport = traceSport(t);
        if (sport != null) return sport;
```

**約束：**
- **不得**把 `FINANCE` 提升為 `traceFinanceFeed` 的獨立 cascade 規則。Task 240 的既有 javadoc 反對的是「把 `FINANCE` 排在三條 ⓪ 否決**之前**」（會讓「7-11開出千萬中獎發票」退回 `KEEP:finance`）與「把 `FINANCE` 當**收錄白名單**」（純財經站大量財經用語不在其中，兜底本就該 KEEP）。本設計兩者都不觸犯：三條 ⓪ 仍排最前；`FINANCE` 在此**不是收錄白名單而是否決豁免**，命中只讓 `traceSport()` 回 `null`，最終仍走兜底 `KEEP:finance-feed`（label 不變，既有測試斷言不被打破）。
- 三條 ⓪ 否決的優先序不變。

### 241.6 同步 class javadoc 與 `LIFESTYLE` 註解

- class javadoc 的政策第 5 條（「全世界生活／軟文／體育一律不收」）與 cascade 說明，補上規則①d 與其豁免。
- `LIFESTYLE` 集合上方註解中「全項體育與演藝娛樂詞群納入 LIFESTYLE」等敘述改為指向 `SPORT`（Task 241 已移出）。

### 241.7 `EditorialNewsFilterTest.java`：新增回歸錨點

在既有最後一個 `@Nested`（`CivicSoftAndFinanceFeed`）之後新增：

```java
    @Nested
    @DisplayName("體育賽事與國際政治豁免（Task 241）")
    class SportsAndIntlPolitics {
        @Test void 體育賽事濾除() {
            assertDrop("AI眼中的世界盃8強：14個模型集體押阿根廷 英格蘭卻遭遇「爆冷」警報");   // 語料實例
            assertDrop("巔峰對決！世足賽決賽開踢前夕 八大AI模型僅Grok、DeepSeek押注阿根廷2:1勝出"); // 語料實例
            assertDrop("世足》拍照嘲諷梅西曾患「侏儒症」 日本藍髮哥引爆球迷炎上");            // 語料實例
            assertDrop("土銀羽球隊劉廣珩 許尹鏸勇奪加拿大公開賽銀牌");                      // 語料實例
        }
        // 使用者指定的豁免：影響國際政治的大新聞（例如恐怖攻擊）
        @Test void 國際政治重大事件豁免() {
            assertKeep("慕尼黑奧運遭恐怖攻擊 11名以色列選手遇害");
            assertKeep("世界盃期間發生恐怖襲擊 主辦國封鎖場館");            // 恐怖襲擊 變體
            assertKeep("波士頓馬拉松爆炸案 3死180傷");
            assertKeep("巴黎奧運場外發生爆炸 法國提升反恐等級");
            assertKeep("多國宣布抵制北京冬奧 外交杯葛升溫");
            assertKeep("國際奧會制裁俄羅斯 禁止其代表隊參加奧運");
            assertKeep("世界盃球場外槍擊案 主辦國緊急加強維安");
            assertKeep("奧運選手村遭挾持 恐怖組織宣稱犯案");
            assertKeep("我國以奧會模式參加亞運 正名運動再起");
            assertKeep("俄羅斯重返奧運舞台 國際奧委會暫時解除處罰");                      // 語料實例
        }
        // 使用者 2026-07-27 裁示：意外／管理不善造成的傷亡不算「影響國際政治」，不得豁免
        @Test void 場館意外災難不豁免() {
            assertDrop("足球場看台倒塌 逾百人罹難");
            assertDrop("奧運場館外傳出爆炸 至少10死");
            assertDrop("世界盃球場看台踩踏釀30死 主辦單位遭究責");   // 刻意含 SPORT 詞，確保行經規則①d
        }
        // 守門詞不可用「有沒有提到某國家／政治角色」——體育本質即國際，那樣會架空整個否決集
        @Test void 國家指涉不得作為豁免守門() {
            assertDrop("世界盃巴西隊球迷抵制主辦單位售票制度");
            assertDrop("韓國職棒球星遭球團驅逐出隊 引發球迷不滿");
            assertDrop("中國羽球選手遭禁藥制裁 兩年不得出賽");
            assertDrop("政府制裁禁藥球員 體育署祭出重罰");
            assertDrop("總統盃全國羽球錦標賽開打 選手人氣爆炸");     // 總統⊂總統盃 子字串陷阱
            assertDrop("大聯盟球星遭球隊驅逐 總統也發文力挺");
        }
        // 規則①d 必須在②之前的真正理由：體育事件常同時命中非體育的 LIFESTYLE 詞
        @Test void 體育事件命中非體育生活詞仍須豁免() {
            assertKeep("奧運開幕演唱會遭恐怖攻擊 多國元首緊急撤離");   // 演唱會∈LIFESTYLE
            assertKeep("世界盃球迷粉絲見面會爆炸案 主辦國提升反恐");   // 粉絲∈LIFESTYLE
        }
        // 弱豁免必須 AND 守門，否則一般體育新聞會把整個否決集架空（單層豁免時此組 5/5 全被誤救）
        @Test void 一般體育新聞不得被豁免詞救回() {
            assertDrop("聯盟制裁違規球隊 罰款500萬並扣除積分");
            assertDrop("球迷抵制球隊經營不善 場外拉布條抗議");
            assertDrop("世界盃抽籤爭議 球迷杯葛主辦單位");
            assertDrop("電競選手人氣爆炸 直播訂閱數翻倍");
            assertDrop("職棒球員酒駕遭球團暗殺式冷凍");
            assertDrop("大聯盟球星轉隊 身價爆炸性成長");
            assertDrop("世足決賽3死角戰術解析 教練團出奇招");
            assertDrop("球隊防線倒塌 慘遭逆轉輸球");
        }
        // 硬約束：SPORT 不得凌駕 FINANCE——體育語彙在財經媒體大量作為比喻
        @Test void 財經比喻含體育詞不誤殺() {
            assertKeep("高股息ETF受益人數 0056奪冠");
            assertKeep("隱形冠軍／銳禾獨特工法出頭天 小螺絲攻進晶片封裝");
            assertKeep("好市多狂吸400萬會員 單店平均營收百億霸氣封王");
            assertKeep("台灣大6月EPS 0.52元 蟬聯2個月電信股EPS冠軍");
            assertKeep("卓榮泰喊話打造金融世界盃 亞資中心瞄準超越香港追趕新加坡");
            assertKeep("ASML加入AI紅利分配行列 全球員工可獲價值74萬股票獎勵");
        }
        // 體育帶動的產業／營收新聞必須保留（使用者「影響經濟」判準）
        @Test void 體育產業財經不誤殺() {
            assertKeep("世足經濟學／鞋尖上的台灣！全球每五雙足球鞋就有一雙來自這");
            assertKeep("中鋼、燁輝搶澳洲奧運基建商機 台鏈有望迎訂單大潮");
            assertKeep("華碩電競周邊營收翻倍 快了");
            assertKeep("2026世足賽助攻 中華電信MOD、Hami Video收視再寫新猷");
        }
        // 純財經來源（無規則①FINANCE）全靠 traceSport 內建閘門
        @Test void 純財經來源的體育與財經分流() {
            assertThat(EditorialNewsFilter.traceFinanceFeed("AI眼中的世界盃8強：14個模型集體押阿根廷")).startsWith("DROP");
            assertThat(EditorialNewsFilter.traceFinanceFeed("巔峰對決！世足賽決賽開踢前夕 八大AI模型押注阿根廷")).startsWith("DROP");
            assertThat(EditorialNewsFilter.traceFinanceFeed("世足加持 日本電視出貨量創今年高；OLED大減4成")).startsWith("KEEP");
            assertThat(EditorialNewsFilter.traceFinanceFeed("2026世界盃落幕 FIFA狂攬90億美元 商業巔峰背後仍充滿爭議")).startsWith("KEEP");
            assertThat(EditorialNewsFilter.traceFinanceFeed("世界盃決賽Nike無緣亮相 adidas成贊助商大贏家")).startsWith("KEEP"); // 贊助商豁免
            assertThat(EditorialNewsFilter.traceFinanceFeed("台灣5月手機銷量42.9萬台月增7% iPhone 17連續5月霸榜奪冠")).startsWith("KEEP");
            assertThat(EditorialNewsFilter.traceFinanceFeed("0050定期定額人數突破120萬續稱霸 規模逾2.2兆元費率降至0.07%")).startsWith("KEEP");
            assertThat(EditorialNewsFilter.traceFinanceFeed("俄羅斯重返奧運舞台 國際奧委會暫時解除處罰")).startsWith("KEEP"); // 豁免
        }
    }
```

**約束：** 標題多數取自線上真實語料（註明「語料實例」者），其餘為對抗測試的代表性合成標題。任一斷言失敗代表詞集或 cascade 位置有誤，**修的是實作、不是把測試放寬**。

### 241.8 不需要改動的地方

- **不改 `NewsFetchClient`**：`trace()`／`traceFinanceFeed()` 已在既有各 feed 接線，本規則自動隨之生效。
- **不改 backend／bff／frontend**：過濾發生在落庫之前，無契約變更。
- **不改 `FINANCE` 本身**：體育產業的召回缺口以 `SPORT_ECON_EXEMPT`（2 詞）在體育規則內局部補，避免全域回歸無法歸因。
- **不追溯清除舊列**：只在抓取時作用，30 天保留期自然滾出。
- **無 Liquibase changeset、無 `@Scheduled` 變更。**

## 驗證

**單元測試：**

```bash
cd external-materials-service && /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q test -Dtest=EditorialNewsFilterTest
```

（斷言 `Failures: 0, Errors: 0`；新增 `SportsAndIntlPolitics` 9 個測試方法全過、既有 36 個零回歸，合計 45。）

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
docker run --rm --entrypoint sh asset-management-external-materials-service:latest -c 'cd /tmp && unzip -o -q /app/app.jar "BOOT-INF/classes/com/steven/assets/externalmaterials/client/EditorialNewsFilter.class" && LC_ALL=C grep -ac "sport-intl-politics" BOOT-INF/classes/com/steven/assets/externalmaterials/client/EditorialNewsFilter.class'
```

（期望 `1`；另可同法確認 `DROP:sport`／`奧會模式`／`爆炸案`／`贊助商`。）

**健康檢查：**

```bash
docker inspect --format '{{.State.Health.Status}}' asset-external-materials-service
```

## 已知殘留（刻意接受）

1. **IOC 例行行政新聞會被強豁免救回**：`國際奧會`／`國際奧委會` 置於強豁免（單獨命中即保留），故「國際奧會公布2036奧運主辦城市名單」「國際奧會宣布新增霹靂舞為奧運正式項目」實測為 `KEEP:sport-intl-politics`。這是為了救語料實例「俄羅斯重返奧運舞台 國際奧委會暫時解除處罰」（俄烏戰爭導致的禁賽與解禁＝典型的體育場合國際政治事件）所付的代價——該則的 `解除處罰`／`重返` 都不在 `SPORT_GEO`，若把 IOC 降級為守門詞就救不回來。語料中 IOC 相關僅 1 則，爆炸半徑小；且 IOC 決定多數確實具國際政治意涵（主辦權涉國家外交、參賽資格涉地緣）。

2. **弱豁免層在現行語料中是零樣本**：全語料 4835 則中，同時含 `SPORT` 詞與任一 `SPORT_GEO` 詞的列數為 **0**；實際行經規則①d 而出判定的僅 9 則。弱豁免（`SPORT_GEO` 6 詞 ＋ `SPORT_GEO_GUARD` 14 詞）與強豁免的多數詞，都只由合成對抗案例驗證，**不可將「全語料零反向翻轉」誤讀為整條規則都經真實語料驗證**。這是使用者明確指定的功能需求（「除非發生影響國際政治的大新聞」）且語料恰好無此類事件，屬 Task 229／240「零樣本推測詞不納入」紀律的**刻意例外**——例外只給豁免集（誤中方向是少濾一則體育），否決集 `SPORT` 仍嚴格遵守該紀律。

3. **wantgoo「白宮：川普出席世界盃決賽 預計與FIFA主席共同頒發冠軍金盃」仍 `KEEP`**：其摘要含場館名「大都會**人壽**體育場」（MetLife Stadium），誤中 `FINANCE` 的 `人壽` 而觸發財經閘門。同事件的 ltn 版本「世界盃決賽在即　白宮預告川普將出席」無此摘要，判 `DROP:sport`——同一事件跨來源判定不一致。唯一解是把 `金盃`／`開踢` 排在 `FINANCE` 之前，違反硬約束 1 故不做；根因屬 `FINANCE` 既有的場館名誤中，非本規則引入。

4. **`traceFinanceFeed()` 沒有兜底 DROP**：不含 `SPORT` 詞的體育周邊新聞（如「球場踩踏事故釀30死」，`球場` 不是 `SPORT` 成員）若來自 wantgoo／moneydj 仍會入庫。這是 Task 240 建立的既有邊界（純財經來源預設收錄），非本任務引入。

## 完成報告

**實際改動檔案：**
- `external-materials-service/.../client/EditorialNewsFilter.java`：`LIFESTYLE` 移出 44 個體育詞；新增 `SPORT`（49 詞）／`SPORT_TERROR`（17 詞）／`SPORT_GEO`（6 詞）／`SPORT_GEO_GUARD`（14 詞）／`SPORT_ECON_EXEMPT`（2 詞）五個常數與 `traceSport(String)` helper；`trace()` 於 ①c 之後、② 之前插入規則①d；`traceFinanceFeed()` 於三條 ⓪ 否決之後、兜底之前接上同一支 helper。
- `external-materials-service/src/test/.../EditorialNewsFilterTest.java`：新增 `@Nested SportsAndIntlPolitics`（9 個測試方法）。
- `spec/requirements.md`（Requirement 31 新增 Task 241 AC）、`spec/design.md`（cascade 補規則①d）。
- 未改 `NewsFetchClient`／backend／bff／frontend／`FINANCE`；無 Liquibase changeset、無 `@Scheduled` 變更（如計畫）。

**驗證輸出：**
- 單元測試：`Tests run: 45, Failures: 0, Errors: 0`（既有 36 零回歸 ＋ 新增 9 全過）。
- 整模組建置：`mvn -q package` 成功。
- 全語料 A/B（4835 則、baseline＝現行 main）：**KEEP→DROP 2 則、DROP→KEEP 0 則**，另 7 則僅 label 變更。新濾除的 2 則正是目標（wantgoo「AI眼中的世界盃8強」「巔峰對決！世足賽決賽開踢前夕」）。
- 對抗測試：國際政治重大事件保留 10/10、一般體育含豁免詞濾除 6/6、國家指涉不得作為守門 6/6、場館意外災難濾除 3/3、財經比喻含體育詞保留 6/6、體育產業財經保留 4/4。
- 前次 4 案例（Task 240）無回歸：分遺產／同遊新北／陽明交大教評會／7-11中獎發票 皆維持 DROP。

**與原計畫的偏差：**
- 設計在 spec-review（7/10）與使用者追加裁示後有兩處實質收斂，均已回寫本檔與 requirements／design：
  1. **移除場館災難豁免**（使用者裁示：意外／管理不善不算影響國際政治）——初稿的 `踩踏／倒塌／暴動／騷亂` 與傷亡數字 regex 全部刪除。
  2. **弱豁免守門大幅收緊**——移除 `GEO_REGION`／`CHINA`／`POLITY_STRONG` 三個泛集合與 `政府`／`總統`／`維安`。審查者實測用它們當守門時 8 則一般體育新聞 8 則全被誤救，且 `總統` 子字串誤中「總統盃」。修正後該 8 則全部正確濾除。
  3. 另補 `恐怖襲擊` 至強豁免（審查者指出的漏救變體），並修正 spec 中兩處經實測不成立的論證（「①d 必須在 ② 前是因為奧運恐攻會被 LIFESTYLE 攔死」實際成因是體育事件同時命中非體育生活詞；「`制裁` ∈ FINANCE」根本不成立、閘門順序只影響 label）。
