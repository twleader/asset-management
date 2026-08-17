package com.steven.assets.service;

import com.steven.assets.dto.CurrentAllocationDto;
import com.steven.assets.dto.PortfolioAdviceResult;
import com.steven.assets.dto.RetirementProjectionDto;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 資產配置建議的本機配置模板引擎（Requirement 80 / Task 339）——{@code local} 與 {@code hybrid}
 * 兩檔位的目標比例來源。
 *
 * <p><b>⚠ 本機配置模板是常見經驗法則，不是個人化投資建議，也未經回測。</b>
 * 目標比例由「風險承受度 × 距退休年數」套 {@link #TEMPLATES} 這組明示對照表產生，屬廣為流傳的
 * 資產配置經驗法則（如股債比隨年齡遞減），<b>未經任何回測或個人情境驗證</b>。此定性同時出現於
 * 前端頁面的常駐說明，並固定為輸出 {@code warnings} 的首條（{@link #TEMPLATE_DISCLAIMER}）。
 * 不得讓使用者誤以為本機檔位的建議具有與完整 LLM 版相同的個人化程度。</p>
 *
 * <p><b>純函式邊界</b>：本類別不注入任何 Repository、不做 IO、不讀時鐘。現況金額由呼叫端
 * （{@link PortfolioAdviceService}）自既有 {@code getCurrentAllocation()} 取得後傳入，退休試算由既有
 * {@code getProjection()} 傳入，距退休年數亦由呼叫端以 {@link #yearsToRetirement} 算好傳入
 * （該方法把「今天」當參數收，同樣不讀時鐘）。故配置模板可在不啟動 Spring context、不連資料庫的
 * 情況下單元測試（CLAUDE.md 架構規範）。</p>
 *
 * <p><b>金額算術不在本引擎</b>：{@code targetAmount}／{@code deltaAmount} 一律由
 * {@code PortfolioAdviceService.enrich(result, totalAssets)} 這一支既有方法回填（三檔位共用），
 * 本引擎輸出的 {@code targetAllocation} 只有比例、分類與現況金額，兩個金額欄位刻意留 null。</p>
 *
 * <p><b>相對 LLM 的已知退化（明示接受）</b>（Requirement 84 / Task 344 起改寫）：{@code rebalancePlan}
 * 除了保留類別層級的三筆（{@code holding} 為 {@value #HOLDING_OVERALL}）之外，
 * {@link #withHoldingLevelRebalance} 會把各子類別的差額按<b>使用者既有部位的現值比重</b>攤到個別
 * 銀行／基金／個股（存款減碼改依提領優先序逐筆抽取）。<b>仍然做不到的是「指名你沒持有過的新標的」
 * 與「判斷哪一檔比較該賣」</b>——那需要選股、估值與時機判斷，本機規則沒有依據，硬給是臆測；
 * 等比例分攤也預設同一子類別內每一檔一樣好，未考慮套牢／獲利狀態、交易成本、稅務與基本面差異。
 * 此範圍與其預設列於 {@code warnings}（{@link #HOLDING_LEVEL_SCOPE_WARNING}）。</p>
 */
@Service
public class LocalPortfolioAllocationEngine {

    /**
     * 模板版本，寫入 {@code portfolio_advice.model}。
     * <b>{@link #TEMPLATES} 的任何比例調整都須提升此版本號</b>——否則歷史紀錄無法分辨是哪一版模板產生的建議。
     */
    public static final String ENGINE_VERSION = "local-allocation:v1";

    /**
     * 三類名稱<b>必須與 {@code PortfolioAdviceService.getCurrentAllocation()} 的三類逐字一致</b>，
     * 否則 {@code currentValue} 對不上、{@code deltaAmount} 失去意義。不得自創第四類或改名。
     */
    public static final String CLASS_CASH = "存款（現金）";
    public static final String CLASS_FUND = "信託基金";
    public static final String CLASS_STOCK = "股票";

    /**
     * 子類別名稱（Requirement 82）：「股票」「信託基金」兩桶再細分的五個子類別，套在
     * {@link AssetClassifier#classifyStockStyle}／{@link AssetClassifier#classifyBondTerm} 的值域之上。
     * <b>本類是這 5 個子類別字面字串的唯一宣告處</b>——{@code PortfolioAdviceService} 一律引用這幾個常數，
     * 不得另宣告一份同字面值的常數（避免兩邊打字不一致）。
     */
    public static final String SUBCLASS_GROWTH = "成長型";
    public static final String SUBCLASS_INCOME = "收益型（高股息）";
    public static final String SUBCLASS_BOND_SHORT = "短期債";
    public static final String SUBCLASS_BOND_MID = "中期債";
    public static final String SUBCLASS_BOND_LONG = "長期債";

    /** {@code rebalancePlan.holding} 的類別層級固定值（Task 344 起：標的層級明細掛在這三筆之下）。 */
    public static final String HOLDING_OVERALL = "整體";

    /** 目標比例與現況差額為 0 時的動作。 */
    public static final String ACTION_BUY = "BUY";
    public static final String ACTION_SELL = "SELL";
    public static final String ACTION_HOLD = "HOLD";

    /**
     * 第四種動作（Requirement 84 / Task 344）：<b>知道該動多少錢，但無法指名該動哪一檔</b>
     * ——該子類別目前零部位，沒有現值可當分攤的分母。
     *
     * <p>刻意不沿用 {@link #ACTION_BUY}：綠色「增碼」tag 是可執行動作的視覺語意，套在使用者無從執行的
     * 金額上會誤導；也不沿用 {@value #HOLDING_OVERALL}：那樣 render 出來與本功能之前一模一樣，
     * 使用者分不出「系統做不到」與「系統沒做」。</p>
     */
    public static final String ACTION_UNSPECIFIED = "UNSPECIFIED";

    /**
     * 最小操作金額門檻（元，Task 344.14）：同一群組內分攤金額低於此值者合併為一列，
     * 免得清單被幾百元的零頭灌爆。
     *
     * <p><b>此門檻未經回測</b>（比照 {@link #TEMPLATE_DISCLAIMER} 的定性），是經驗值——可參考的錨點為
     * 「券商最低手續費 20 元 ÷ 0.1425% ≈ 14,035 元」，取整為 1 萬。不得對外宣稱有研究依據。</p>
     */
    public static final BigDecimal MIN_REBALANCE_AMOUNT = BigDecimal.valueOf(10_000);

    /**
     * 視為「定期存款」的提領優先序下限（Task 344.22）：waterfall <b>真的抽到</b>這類存款時才附加
     * 解約損失的 warning。判準走 {@code deposit_type.withdrawal_order}，<b>不是名稱、也不是利率</b>
     * ——實測「美元定存」的 {@code annual_interest_rate} 為 NULL、「優利活存 1.5%」為 1.5%，用利率判兩者都判錯。
     */
    public static final int TIME_DEPOSIT_WITHDRAWAL_ORDER_MIN = 90;

    /** <b>{@code warnings} 的首條固定為此聲明</b>（Task 339 背景段的 ⚠ 定性）。 */
    public static final String TEMPLATE_DISCLAIMER =
            "本次建議的目標比例來自「風險承受度 × 距退休年數」的固定對照表，屬廣為流傳的資產配置經驗法則，"
                    + "未經任何回測或個人情境驗證，也不是個人化投資建議；需要更貼近個人狀況的分析請改用「完整 AI 分析」檔位。";

    /**
     * 本機檔位「標的層級做到哪、做不到哪」的明示範圍（Task 339.7 → Task 344.21 改名並整條替換文案）。
     *
     * <p>原名 {@code NO_HOLDING_LEVEL_WARNING} 意為「本機檔位<b>不產生</b>個股層級建議」，
     * Task 344 起 {@link #withHoldingLevelRebalance} 已把金額攤到個別標的，新文案語意正好相反，
     * 留著原名會變成反話，故一併改名。<b>做了什麼與沒做什麼都要說清楚</b>——等比例分攤是純算術、
     * 零市場觀點，但「同一子類別內每一檔一樣好」本身就是一個未明示即會誤導的預設。</p>
     */
    public static final String HOLDING_LEVEL_SCOPE_WARNING =
            "本檔位的調整動作已細到你「現有持有的」個別銀行、基金與個股，做法是把各子類別的差額按你目前的現值比重攤下去"
                    + "（存款的減碼則依提領優先序逐筆抽取，優先動活存以避免定存中途解約的利息損失）。"
                    + "但它不會推薦你沒持有過的新標的，也不判斷哪一檔比較該賣——等比例分攤預設同一子類別內每一檔一樣好，"
                    + "未考慮個別標的的套牢或獲利狀態、交易成本與最低手續費、稅務，以及基本面差異。"
                    + "需要具名新標的或個股優劣判斷，請切換為「完整 AI 分析」。";

    /** 風險承受度未填時的提醒。 */
    static final String RISK_UNKNOWN_WARNING =
            "尚未填寫「風險承受度」，本次採最保守檔（保守）計算；填寫後重新產生可得到較貼合的比例。";

    /** 距退休年數無法推算時的提醒。 */
    static final String HORIZON_UNKNOWN_WARNING =
            "「預計退休日期」與「生日」皆未填，距退休年數無法推算，本次採最保守檔計算。";

    /** 無資產快照時的提醒（此時只有比例、沒有金額）。 */
    static final String NO_SNAPSHOT_WARNING =
            "尚無資產快照（或資產總額為 0），本次只給目標比例，未計算各類目標金額與調整金額；"
                    + "請先於「管理資產」建立快照。";

    /**
     * 股票桶內持有債券型標的的提醒（Requirement 82 / Task 341.8）：本機的目標子分配假設債券曝險
     * 一律經由信託基金達成（見 {@link #SUB_TEMPLATES} 的設計原則），股票桶目標子分配的三個債券期別
     * 固定為 0；若使用者股票帳戶仍持有債券型標的（如直接持有 00679B），現況金額仍如實顯示，
     * 目標給 0 便會呈現「建議減碼」的落差，此提醒說明原因、避免使用者誤以為系統算錯。
     */
    static final String STOCK_BOND_HOLDING_WARNING_TEMPLATE =
            "你的股票部位中有 %s 元被歸類為債券型標的（如債券 ETF），本模型的目標配置假設債券曝險一律經由信託基金達成，"
                    + "故此處目標次分配為 0、會顯示為建議減碼；若為刻意持有可忽略此提示。";

    /**
     * 在途／轉帳中存款被排除於分攤之外的提醒（Task 344.11）：分子（桶 {@code deltaAmount}）是<b>含</b>在途款的
     * 淨額口徑、分母是<b>排除</b>在途款後的合計，兩者刻意不同源；差額由 clamp 與本提醒承擔，
     * <b>不得</b>回頭改動「② 我目前的資產配置」或 {@code targetAllocation} 的存款金額（會讓 Requirement 25 的現金口徑漂移）。
     */
    static final String TRANSIT_DEPOSIT_WARNING_TEMPLATE =
            "你的存款中有在途／轉帳中的部位（信用卡待付款、買股待付款等）合計 %s 元，不是可自由處分的部位，"
                    + "已排除在分攤之外，但仍計入上方的存款總額。";

    /** waterfall <b>真的抽到</b>定期存款時才附加的提醒（Task 344.22；不得無條件輸出）。 */
    static final String TIME_DEPOSIT_WARNING_TEMPLATE =
            "本次減碼會動到定期存款 %s 元（%s）。定存中途解約通常按實際存期折算利息，是可量化的損失；"
                    + "若你的銀行不支援部分解約，實際可動用金額會與此處不同。";

    /**
     * 低於 {@link #MIN_REBALANCE_AMOUNT} 的股票／基金列附加提示（Task 344.14）。
     * 門檻的錨點是券商最低手續費，故措辭用「交易成本」。
     */
    static final String SMALL_AMOUNT_HINT_TRADABLE =
            "本筆金額低於 1 萬元，可視交易成本考慮略過。";

    /**
     * 低於 {@link #MIN_REBALANCE_AMOUNT} 的<b>存款</b>列附加提示（Task 344.14）。
     * <b>不得沿用「交易成本」措辭</b>——提領無手續費，且存款減碼走 waterfall、該筆多半是<b>全額抽取</b>，
     * 略過會直接讓存款群組的守恆破功，故改為明示「略過會短少多少」。
     */
    static final String SMALL_AMOUNT_HINT_DEPOSIT_TEMPLATE =
            "本筆金額較小，可視作業便利性自行斟酌；略過會使存款群組合計短少 %s 元。";

    // ===== 風險承受度代碼（與 PortfolioAdviceService.RISK_OPTIONS 逐字一致；不是 LOW/MEDIUM/HIGH）=====

    public static final String RISK_CONSERVATIVE = "CONSERVATIVE";
    public static final String RISK_BALANCED = "BALANCED";
    public static final String RISK_AGGRESSIVE = "AGGRESSIVE";

    /**
     * 未填退休日期時的假設退休年齡（歲）：以生日 + 此年齡推估距退休年數。
     * 取 65 為台灣勞保老年年金的法定請領年齡（民國 115 年起為 65 歲）。
     */
    static final int ASSUMED_RETIREMENT_AGE = 65;

    // ===== 距退休年數分段（具名常數，不得散落魔術數字）=====

    /** 距退休 ≥ 此年數為「長期」。 */
    static final int HORIZON_LONG_MIN_YEARS = 20;
    /** 距退休 ≥ 此年數（且 < {@value #HORIZON_LONG_MIN_YEARS}）為「中期」。 */
    static final int HORIZON_MEDIUM_MIN_YEARS = 10;
    /** 距退休 ≥ 此年數（且 < {@value #HORIZON_MEDIUM_MIN_YEARS}）為「短期」；更短則為「臨退／已退」。 */
    static final int HORIZON_SHORT_MIN_YEARS = 3;

    /** 距退休年數分段。 */
    public enum Horizon {
        LONG("距退休 20 年以上"),
        MEDIUM("距退休 10～19 年"),
        SHORT("距退休 3～9 年"),
        IMMINENT("距退休不到 3 年或已退休");

        private final String label;

        Horizon(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** 對照表的一格：三類目標比例（加總恆為 100）與該格的中文理由。 */
    public record Template(BigDecimal cashPct, BigDecimal fundPct, BigDecimal stockPct, String rationale) {}

    /** 對照表的鍵：風險承受度 × 距退休年數分段。 */
    public record TemplateKey(String riskTolerance, Horizon horizon) {}

    /**
     * <b>配置模板對照表（經驗法則，未經回測）</b>：股票比重隨「風險承受度下降」與「距退休年數縮短」
     * 而遞減，缺口補到存款（現金）與信託基金。每格三數加總恆為 100。
     *
     * <p>最保守格為 {@code CONSERVATIVE × IMMINENT}——風險承受度未填、或距退休年數無法推算時即採該格。</p>
     */
    private static final Map<TemplateKey, Template> TEMPLATES = buildTemplates();

    private static Map<TemplateKey, Template> buildTemplates() {
        Map<TemplateKey, Template> m = new LinkedHashMap<>();
        // 積極：可承受較大波動追求高報酬
        put(m, RISK_AGGRESSIVE, Horizon.LONG, 10, 20, 70,
                "積極型且距退休尚有 20 年以上，波動有足夠時間回復，股票配置拉到全表最高的 70%，現金僅留最低緊急預備。");
        put(m, RISK_AGGRESSIVE, Horizon.MEDIUM, 10, 25, 65,
                "積極型且距退休 10～19 年，仍以成長為主，但把部分股票挪往分散度較高的基金以降低單一標的風險。");
        put(m, RISK_AGGRESSIVE, Horizon.SHORT, 15, 30, 55,
                "積極型但距退休已進入 10 年內，開始把資產從股票移向基金與現金，避免退休前一次大回檔傷及提領起點。");
        put(m, RISK_AGGRESSIVE, Horizon.IMMINENT, 20, 35, 45,
                "積極型但已臨退休或退休中，薪資淨投入已停止，股票降至 45%，並拉高現金以支應數年提領、避免低點被迫變現。");
        // 穩健：可承受中度波動
        put(m, RISK_BALANCED, Horizon.LONG, 15, 30, 55,
                "穩健型且距退休 20 年以上，以股票 55% 取得長期成長，同時保留三成基金分散波動。");
        put(m, RISK_BALANCED, Horizon.MEDIUM, 15, 35, 50,
                "穩健型且距退休 10～19 年，股債（基金）大致對半，兼顧成長與波動可承受度。");
        put(m, RISK_BALANCED, Horizon.SHORT, 20, 40, 40,
                "穩健型且距退休 10 年內，股票降至 40%、現金拉到 20%，為即將開始的提領期預留緩衝。");
        put(m, RISK_BALANCED, Horizon.IMMINENT, 30, 40, 30,
                "穩健型且已臨退休或退休中，以「現金支應近年提領、基金為中段、股票為長段」的分層配置降低順序報酬風險。");
        // 保守：不能忍受本金明顯虧損
        put(m, RISK_CONSERVATIVE, Horizon.LONG, 20, 40, 40,
                "保守型即使距退休 20 年以上，仍以不能忍受本金明顯虧損為前提，股票上限壓在 40%，主力放在分散度較高的基金。");
        put(m, RISK_CONSERVATIVE, Horizon.MEDIUM, 25, 45, 30,
                "保守型且距退休 10～19 年，股票再降至 30%，現金提高到 25% 以降低整體波動。");
        put(m, RISK_CONSERVATIVE, Horizon.SHORT, 30, 45, 25,
                "保守型且距退休 10 年內，以現金與基金為主（合計 75%），股票僅保留 25% 對抗長期通膨。");
        put(m, RISK_CONSERVATIVE, Horizon.IMMINENT, 40, 45, 15,
                "全表最保守的一格：保守型且已臨退休或退休中，現金拉到 40% 支應提領，股票僅留 15% 作為長期購買力的防線。");
        return Map.copyOf(m);
    }

    private static void put(Map<TemplateKey, Template> m, String risk, Horizon horizon,
                            int cashPct, int fundPct, int stockPct, String rationale) {
        if (cashPct + fundPct + stockPct != 100) {
            throw new IllegalStateException("配置模板比例加總須為 100：" + risk + "/" + horizon);
        }
        m.put(new TemplateKey(risk, horizon),
                new Template(BigDecimal.valueOf(cashPct), BigDecimal.valueOf(fundPct),
                        BigDecimal.valueOf(stockPct), rationale));
    }

    /**
     * 「股票」「信託基金」兩桶各自的子分配比例（Requirement 82）：五欄（成長/收益/短債/中債/長債）
     * 各自加總恆為 100。與 {@link #TEMPLATES} 同一組 {@link TemplateKey}（風險承受度 × 距退休年數）。
     */
    public record SubTemplate(
        BigDecimal stockGrowthPct, BigDecimal stockIncomePct,
        BigDecimal stockBondShortPct, BigDecimal stockBondMidPct, BigDecimal stockBondLongPct,
        BigDecimal fundGrowthPct, BigDecimal fundIncomePct,
        BigDecimal fundBondShortPct, BigDecimal fundBondMidPct, BigDecimal fundBondLongPct) {}

    /**
     * <b>子分配對照表（經驗法則，未經回測）</b>：<b>設計原則</b>——債券曝險一律經由信託基金達成，
     * 股票桶目標次分配固定只在成長/收益二者分配（{@code stockBondShort/Mid/LongPct} 全部為 0）；
     * 若使用者股票帳戶仍持有債券型標的（如直接持有 00679B），現況子分類（見
     * {@code PortfolioAdviceService.getCurrentAllocation()}）仍如實顯示非 0 金額，目標給 0，形成建議
     * 減碼的落差（見 {@link #STOCK_BOND_HOLDING_WARNING_TEMPLATE}）。方向性：距退休年數縮短或風險
     * 承受度降低時，成長比重下降、收益比重上升；基金債券期別隨距退休年數縮短由長轉短（降低利率存續期風險）。
     */
    private static final Map<TemplateKey, SubTemplate> SUB_TEMPLATES = buildSubTemplates();

    private static Map<TemplateKey, SubTemplate> buildSubTemplates() {
        Map<TemplateKey, SubTemplate> m = new LinkedHashMap<>();
        putSub(m, RISK_AGGRESSIVE, Horizon.LONG,      85, 15,  0, 0, 0,   55, 15, 10, 10, 10);
        putSub(m, RISK_AGGRESSIVE, Horizon.MEDIUM,    80, 20,  0, 0, 0,   45, 20, 10, 15, 10);
        putSub(m, RISK_AGGRESSIVE, Horizon.SHORT,     70, 30,  0, 0, 0,   35, 25, 15, 15, 10);
        putSub(m, RISK_AGGRESSIVE, Horizon.IMMINENT,  60, 40,  0, 0, 0,   25, 30, 25, 15, 5);
        putSub(m, RISK_BALANCED,   Horizon.LONG,      75, 25,  0, 0, 0,   45, 20, 10, 15, 10);
        putSub(m, RISK_BALANCED,   Horizon.MEDIUM,    65, 35,  0, 0, 0,   35, 25, 15, 15, 10);
        putSub(m, RISK_BALANCED,   Horizon.SHORT,     55, 45,  0, 0, 0,   25, 30, 20, 15, 10);
        putSub(m, RISK_BALANCED,   Horizon.IMMINENT,  45, 55,  0, 0, 0,   15, 30, 35, 15, 5);
        putSub(m, RISK_CONSERVATIVE, Horizon.LONG,    60, 40,  0, 0, 0,   35, 25, 15, 15, 10);
        putSub(m, RISK_CONSERVATIVE, Horizon.MEDIUM,  50, 50,  0, 0, 0,   25, 30, 20, 15, 10);
        putSub(m, RISK_CONSERVATIVE, Horizon.SHORT,   40, 60,  0, 0, 0,   20, 30, 25, 20, 5);
        putSub(m, RISK_CONSERVATIVE, Horizon.IMMINENT,30, 70,  0, 0, 0,   10, 30, 40, 15, 5);
        return Map.copyOf(m);
    }

    private static void putSub(Map<TemplateKey, SubTemplate> m, String risk, Horizon horizon,
                                int sGrowth, int sIncome, int sShort, int sMid, int sLong,
                                int fGrowth, int fIncome, int fShort, int fMid, int fLong) {
        if (sGrowth + sIncome + sShort + sMid + sLong != 100) {
            throw new IllegalStateException("股票次分配比例加總須為 100：" + risk + "/" + horizon);
        }
        if (fGrowth + fIncome + fShort + fMid + fLong != 100) {
            throw new IllegalStateException("基金次分配比例加總須為 100：" + risk + "/" + horizon);
        }
        m.put(new TemplateKey(risk, horizon), new SubTemplate(
            BigDecimal.valueOf(sGrowth), BigDecimal.valueOf(sIncome),
            BigDecimal.valueOf(sShort), BigDecimal.valueOf(sMid), BigDecimal.valueOf(sLong),
            BigDecimal.valueOf(fGrowth), BigDecimal.valueOf(fIncome),
            BigDecimal.valueOf(fShort), BigDecimal.valueOf(fMid), BigDecimal.valueOf(fLong)));
    }

    /** 各類別在配置中扮演的角色（組 {@code rationale} 用；與對照表理由拼成一句）。 */
    private static final String ROLE_CASH = "存款（現金）是緊急預備金與近年提領的緩衝，比重越高越不會在低點被迫變現";
    private static final String ROLE_FUND = "信託基金是分散度較高的中間部位，波動介於存款與個股之間";
    private static final String ROLE_STOCK = "股票是長期報酬的主要來源，同時是波動最大的部位";

    // ===== 對外純函式 =====

    /**
     * 配置模板核心（<b>純函式</b>）：風險承受度 × 距退休年數 → 三類目標比例。
     *
     * @param riskTolerance {@value #RISK_CONSERVATIVE}／{@value #RISK_BALANCED}／{@value #RISK_AGGRESSIVE}
     *                      三者之一；null 或不在清單內一律採最保守檔
     * @param yearsToRetirement 距退休年數；null 採最保守檔
     */
    public Template templateOf(String riskTolerance, Integer yearsToRetirement) {
        return TEMPLATES.get(new TemplateKey(normalizeRisk(riskTolerance), horizonOf(yearsToRetirement)));
    }

    /**
     * 子分配模板核心（<b>純函式</b>，Requirement 82）：風險承受度 × 距退休年數 → 「股票」「信託基金」
     * 兩桶各自的五類子分配比例。與 {@link #templateOf} 同一組 {@link TemplateKey}。
     */
    public SubTemplate subTemplateOf(String riskTolerance, Integer yearsToRetirement) {
        return SUB_TEMPLATES.get(new TemplateKey(normalizeRisk(riskTolerance), horizonOf(yearsToRetirement)));
    }

    /** 風險承受度正規化：不在白名單（含 null）→ 最保守檔 {@value #RISK_CONSERVATIVE}。 */
    public static String normalizeRisk(String riskTolerance) {
        if (RISK_AGGRESSIVE.equals(riskTolerance) || RISK_BALANCED.equals(riskTolerance)) {
            return riskTolerance;
        }
        return RISK_CONSERVATIVE;
    }

    /** 距退休年數 → 分段；null（無法推算）→ 最保守的 {@link Horizon#IMMINENT}。 */
    public static Horizon horizonOf(Integer yearsToRetirement) {
        if (yearsToRetirement == null) {
            return Horizon.IMMINENT;
        }
        if (yearsToRetirement >= HORIZON_LONG_MIN_YEARS) {
            return Horizon.LONG;
        }
        if (yearsToRetirement >= HORIZON_MEDIUM_MIN_YEARS) {
            return Horizon.MEDIUM;
        }
        if (yearsToRetirement >= HORIZON_SHORT_MIN_YEARS) {
            return Horizon.SHORT;
        }
        return Horizon.IMMINENT;
    }

    /**
     * 距退休年數（<b>純函式</b>，「今天」由呼叫端傳入，本方法不讀時鐘）：
     * 優先 {@code retirementDate − today}（整月數 / 12 無條件捨去，與既有 {@code retirementSpan} 同慣例）；
     * {@code retirementDate} 為 null 時退回以 {@code birthDate} ＋ {@value #ASSUMED_RETIREMENT_AGE} 歲推算；
     * 兩者皆 null 回 null（呼叫端據此採最保守檔）。已過退休日回 0。
     */
    public static Integer yearsToRetirement(LocalDate today, LocalDate retirementDate, LocalDate birthDate) {
        if (today == null) {
            return null;
        }
        if (retirementDate != null) {
            long months = ChronoUnit.MONTHS.between(today, retirementDate);
            return (int) Math.max(0, months / 12);
        }
        if (birthDate != null) {
            int age = Math.max(0, Period.between(birthDate, today).getYears());
            return Math.max(0, ASSUMED_RETIREMENT_AGE - age);
        }
        return null;
    }

    /**
     * 依配置模板產生一份完整建議（<b>純函式</b>）。
     *
     * <p><b>刻意不填 {@code targetAmount}／{@code deltaAmount}</b>：那兩個金額欄位由呼叫端以既有
     * {@code PortfolioAdviceService.enrich(result, totalAssets)} 回填（三檔位共用同一段算術）。
     * {@code rebalancePlan} 亦因此留空，由 {@link #withRebalancePlan} 在 enrich 之後補上。</p>
     *
     * <p>{@code references} 固定為空陣列——本路徑不搜尋網路、不得杜撰來源。</p>
     *
     * @param currentAllocation 既有 {@code getCurrentAllocation()} 的輸出（{@code currentValue} 的唯一來源）
     * @param projection        既有 {@code getProjection()} 的輸出（{@code riskAssessment} 據此敘述，不重算）
     */
    public PortfolioAdviceResult evaluate(String riskTolerance, Integer yearsToRetirement,
                                          CurrentAllocationDto currentAllocation,
                                          RetirementProjectionDto projection) {
        String risk = normalizeRisk(riskTolerance);
        Horizon horizon = horizonOf(yearsToRetirement);
        Template t = TEMPLATES.get(new TemplateKey(risk, horizon));

        Map<String, BigDecimal> currentValues = currentValuesOf(currentAllocation);
        BigDecimal total = currentAllocation == null ? null : currentAllocation.totalAssets();

        List<PortfolioAdviceResult.TargetAllocation> targets = List.of(
                allocation(CLASS_CASH, t.cashPct(), currentValues, ROLE_CASH, t.rationale()),
                allocation(CLASS_FUND, t.fundPct(), currentValues, ROLE_FUND, t.rationale()),
                allocation(CLASS_STOCK, t.stockPct(), currentValues, ROLE_STOCK, t.rationale()));

        List<String> warnings = new ArrayList<>();
        warnings.add(TEMPLATE_DISCLAIMER);          // 首條固定為 ⚠ 定性聲明（Task 339 背景段）
        warnings.add(HOLDING_LEVEL_SCOPE_WARNING);
        if (!risk.equals(riskTolerance)) {
            warnings.add(RISK_UNKNOWN_WARNING);
        }
        if (yearsToRetirement == null) {
            warnings.add(HORIZON_UNKNOWN_WARNING);
        }
        if (total == null || total.signum() <= 0) {
            warnings.add(NO_SNAPSHOT_WARNING);
        }
        if (!projectionAvailable(projection)) {
            warnings.add("退休現金流試算目前無法進行（" + safe(projection == null ? null : projection.unavailableReason())
                    + "），本次的風險評估未能納入退休提領是否足夠。");
        }
        BigDecimal stockBondHolding = stockBondHoldingAmount(currentAllocation);
        if (stockBondHolding.signum() > 0) {
            warnings.add(String.format(STOCK_BOND_HOLDING_WARNING_TEMPLATE, money(stockBondHolding)));
        }

        return new PortfolioAdviceResult(
                buildSummary(risk, horizon, yearsToRetirement, t, currentValues, total),
                buildRiskAssessment(risk, horizon, projection, currentValues, total, t),
                targets,
                List.of(),      // rebalancePlan 待 enrich 之後由 withRebalancePlan 補
                buildActions(horizon),
                List.copyOf(warnings),
                List.of());     // references：本路徑零外部搜尋，固定空陣列
    }

    /**
     * 依 enrich 回填後的 {@code deltaAmount} 產生類別層級的 {@code rebalancePlan}（<b>純函式</b>）：
     * {@code holding} 一律 {@value #HOLDING_OVERALL}、{@code action} 由差額正負決定、
     * {@code estimatedAmount} 為差額絕對值。差額未知（無資產快照）者略過——沒有金額就沒有可執行動作。
     */
    public PortfolioAdviceResult withRebalancePlan(PortfolioAdviceResult enriched) {
        if (enriched == null) {
            return null;
        }
        List<PortfolioAdviceResult.Rebalance> plan = new ArrayList<>();
        if (enriched.targetAllocation() != null) {
            for (PortfolioAdviceResult.TargetAllocation t : enriched.targetAllocation()) {
                if (t == null || t.deltaAmount() == null) {
                    continue;
                }
                int sign = t.deltaAmount().signum();
                String action = sign > 0 ? ACTION_BUY : (sign < 0 ? ACTION_SELL : ACTION_HOLD);
                plan.add(new PortfolioAdviceResult.Rebalance(
                        t.assetClass(),
                        null,           // 類別層級橫跨整桶，不屬於任何子類別（Task 344）
                        HOLDING_OVERALL,
                        action,
                        t.deltaAmount().abs(),
                        rebalanceRationale(t, action)));
            }
        }
        return new PortfolioAdviceResult(
                enriched.summary(), enriched.riskAssessment(), enriched.targetAllocation(),
                List.copyOf(plan), enriched.actions(), enriched.warnings(), enriched.references());
    }

    /**
     * 依子分配對照表（{@link #SUB_TEMPLATES}）補上「股票」「信託基金」兩桶的 {@code subAllocations}
     * （<b>純函式</b>，Requirement 82 / Task 341.9）：須在 {@link #withRebalancePlan} 之後或之前皆可，
     * 但一律要在 {@code enrich(...)} 之後（依賴頂層 {@code targetAmount}）。
     *
     * <p>目標比例為 0 的子類別（股票的三個債券期別）仍輸出一筆 {@code SubAllocation}（{@code targetPct=0}），
     * 不省略——否則使用者持有債券型股票時，落差呈現會缺一列。「存款（現金）」桶固定
     * {@code subAllocations = List.of()}。</p>
     *
     * @param currentAllocation 既有 {@code getCurrentAllocation()} 的輸出（子分配 {@code currentValue} 的唯一來源）
     */
    public PortfolioAdviceResult withSubAllocationAmounts(PortfolioAdviceResult enriched,
                                                          CurrentAllocationDto currentAllocation,
                                                          String riskTolerance, Integer yearsToRetirement) {
        if (enriched == null) {
            return null;
        }
        SubTemplate st = SUB_TEMPLATES.get(new TemplateKey(normalizeRisk(riskTolerance), horizonOf(yearsToRetirement)));
        Map<String, Map<String, BigDecimal>> currentSubValues = currentSubValuesOf(currentAllocation);

        List<PortfolioAdviceResult.TargetAllocation> out = new ArrayList<>();
        if (enriched.targetAllocation() != null) {
            for (PortfolioAdviceResult.TargetAllocation t : enriched.targetAllocation()) {
                if (t == null) {
                    continue;
                }
                List<PortfolioAdviceResult.SubAllocation> subs;
                if (CLASS_STOCK.equals(t.assetClass())) {
                    subs = buildSubAllocations(t, currentSubValues.get(CLASS_STOCK),
                            st.stockGrowthPct(), st.stockIncomePct(),
                            st.stockBondShortPct(), st.stockBondMidPct(), st.stockBondLongPct());
                } else if (CLASS_FUND.equals(t.assetClass())) {
                    subs = buildSubAllocations(t, currentSubValues.get(CLASS_FUND),
                            st.fundGrowthPct(), st.fundIncomePct(),
                            st.fundBondShortPct(), st.fundBondMidPct(), st.fundBondLongPct());
                } else {
                    subs = List.of();  // 存款（現金）不細分子類別
                }
                out.add(new PortfolioAdviceResult.TargetAllocation(
                        t.assetClass(), t.targetPct(), t.currentValue(), t.targetAmount(), t.deltaAmount(),
                        t.rationale(), subs));
            }
        }
        return new PortfolioAdviceResult(
                enriched.summary(), enriched.riskAssessment(), out, enriched.rebalancePlan(),
                enriched.actions(), enriched.warnings(), enriched.references());
    }

    private static List<PortfolioAdviceResult.SubAllocation> buildSubAllocations(
            PortfolioAdviceResult.TargetAllocation t, Map<String, BigDecimal> currentSub,
            BigDecimal growthPct, BigDecimal incomePct, BigDecimal shortPct, BigDecimal midPct, BigDecimal longPct) {
        List<PortfolioAdviceResult.SubAllocation> out = new ArrayList<>();
        out.add(subAllocation(t, SUBCLASS_GROWTH, growthPct, currentSub));
        out.add(subAllocation(t, SUBCLASS_INCOME, incomePct, currentSub));
        out.add(subAllocation(t, SUBCLASS_BOND_SHORT, shortPct, currentSub));
        out.add(subAllocation(t, SUBCLASS_BOND_MID, midPct, currentSub));
        out.add(subAllocation(t, SUBCLASS_BOND_LONG, longPct, currentSub));
        return out;
    }

    private static PortfolioAdviceResult.SubAllocation subAllocation(
            PortfolioAdviceResult.TargetAllocation t, String subClass, BigDecimal subPct,
            Map<String, BigDecimal> currentSub) {
        BigDecimal targetAmount = t.targetAmount() == null ? null
                : t.targetAmount().multiply(subPct).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
        BigDecimal currentValue = (currentSub == null) ? BigDecimal.ZERO
                : currentSub.getOrDefault(subClass, BigDecimal.ZERO);
        BigDecimal delta = targetAmount == null ? null : targetAmount.subtract(currentValue);
        String rationale = t.assetClass() + "－" + subClass + "本次目標 " + plain(subPct) + "%"
                + (targetAmount != null ? "（約 " + money(targetAmount) + " 元，現況 " + money(currentValue) + " 元）" : "") + "。";
        return new PortfolioAdviceResult.SubAllocation(subClass, subPct, currentValue, targetAmount, delta, rationale);
    }

    /** 由既有 {@code getCurrentAllocation()} 的 items 取「頂層桶 → (子類別 → 金額)」（不重查快照）。 */
    private static Map<String, Map<String, BigDecimal>> currentSubValuesOf(CurrentAllocationDto current) {
        Map<String, Map<String, BigDecimal>> m = new LinkedHashMap<>();
        if (current != null && current.items() != null) {
            for (CurrentAllocationDto.Item item : current.items()) {
                if (item == null || item.assetClass() == null) {
                    continue;
                }
                Map<String, BigDecimal> subMap = new LinkedHashMap<>();
                if (item.subItems() != null) {
                    for (CurrentAllocationDto.SubItem sub : item.subItems()) {
                        if (sub != null && sub.subClass() != null) {
                            subMap.put(sub.subClass(), sub.value());
                        }
                    }
                }
                m.put(item.assetClass(), subMap);
            }
        }
        return m;
    }

    /** 「股票」桶內三個債券期別子類別的金額加總（供 {@link #STOCK_BOND_HOLDING_WARNING_TEMPLATE} 判斷是否觸發）。 */
    private static BigDecimal stockBondHoldingAmount(CurrentAllocationDto currentAllocation) {
        if (currentAllocation == null || currentAllocation.items() == null) {
            return BigDecimal.ZERO;
        }
        for (CurrentAllocationDto.Item item : currentAllocation.items()) {
            if (item == null || !CLASS_STOCK.equals(item.assetClass()) || item.subItems() == null) {
                continue;
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (CurrentAllocationDto.SubItem sub : item.subItems()) {
                if (sub == null || sub.subClass() == null || sub.value() == null) {
                    continue;
                }
                if (SUBCLASS_BOND_SHORT.equals(sub.subClass()) || SUBCLASS_BOND_MID.equals(sub.subClass())
                        || SUBCLASS_BOND_LONG.equals(sub.subClass())) {
                    sum = sum.add(sub.value());
                }
            }
            return sum;
        }
        return BigDecimal.ZERO;
    }

    // ===== 標的層級再平衡（Requirement 84 / Task 344）=====

    /**
     * 逐筆持有明細（由 {@code PortfolioAdviceService} 查好後傳入；<b>本引擎不自行查詢</b>，
     * 維持「不注入任何 Repository、不做 IO、不讀時鐘」的純函式邊界）。
     *
     * <p><b>採扁平清單而非「群組 → 清單」的 Map</b>：分組是引擎的事，輸入端保持扁平才不可能組出
     * 「同一標的出現在兩個群組」這種不一致。</p>
     */
    public record HoldingBreakdown(List<Holding> holdings) {}

    /**
     * 一筆持有明細。
     *
     * <p><b>刻意不放 {@code shares}</b>：分攤全部以 {@code currentValue} 計算、顯示名稱只用主檔
     * {@code name} ＋ {@code stockCode}，留一個沒有消費點的欄位與「輸入面最小化」自相矛盾。
     * 反之 {@code currency} <b>有三個消費點</b>（344.10(a) 的在途款排除、344.11 的 warning 金額、
     * 存款 {@code stableKey} 的組成），故保留——判定一律沿用既有的「兩個字面值全等」寫法
     * （見 {@link #isTransitCurrency}），不改用前綴比對。</p>
     *
     * <p><b>不得放 {@code note} 或預先組好的文案</b>——呼叫端只送資料，{@code rationale} 一律由本引擎組。</p>
     */
    public record Holding(
            String assetClass,              // CLASS_CASH／CLASS_FUND／CLASS_STOCK 三者之一
            String subClass,                // SUBCLASS_* 五者之一；存款恆為 null
            String displayName,             // 畫面顯示字串（344.15）
            BigDecimal currentValue,        // 現值（台幣）
            Integer withdrawalOrder,        // 僅存款有值，其餘 null
            BigDecimal annualInterestRate,  // 僅存款有值，其餘 null
            String currency,                // 僅存款有值（TWD／USD／TRANSIT_*），其餘 null
            String stableKey                // 排序 tie-break ＋ 同標的合併鍵（344.8／344.13）
    ) {}

    /**
     * 把各群組已定好的差額按<b>使用者既有部位的現值比重</b>攤到個別銀行／基金／個股（<b>純函式</b>，
     * Requirement 84 / Task 344）。須在 {@link #withSubAllocationAmounts} 之後串接——群組鍵取自
     * {@code targetAllocation[].subAllocations}。
     *
     * <p><b>分攤群組鍵為 {@code (assetClass, subClass)}，不得在桶層級對股票／基金分攤</b>：頁面上方的
     * 子類別目標表已印出「股票－收益型（高股息）… 增碼 363,839」，若改用股票<b>類別</b> delta（負值）分攤，
     * 高股息 ETF 會全部被要求減碼，與同一頁上方直接矛盾。存款桶的 {@code subAllocations} 恆為空陣列
     * （Requirement 82 明訂），整桶為單一群組、{@code T_g} 取桶的 {@code deltaAmount}。</p>
     *
     * <p><b>類別層級的三筆一律保留</b>（344.19）：使用者仍需要「這一類總共要動多少」的總覽，
     * 標的層級明細掛在其下（前端渲染時成為分組標題）。</p>
     *
     * <p><b>無資產快照時整組略過</b>：此時 {@code deltaAmount} 全為 null，{@code rebalancePlan} 本來就是空的
     * ——比照 {@link #withRebalancePlan} 的既有慣例「沒有金額就沒有可執行動作」，不得走
     * {@code P_g = ∅} 分支（{@code T_g.signum()} 會直接 NPE）。</p>
     */
    public PortfolioAdviceResult withHoldingLevelRebalance(PortfolioAdviceResult enriched, HoldingBreakdown breakdown) {
        if (enriched == null) {
            return null;
        }
        if (enriched.targetAllocation() == null || enriched.rebalancePlan() == null
                || enriched.rebalancePlan().isEmpty()) {
            return enriched;   // 無快照（T_g 全為 null）／無類別層級可掛載 → 原封返回
        }

        // 344.10(a)：在途／轉帳中的存款一律排除，不進分子也不進分母（順序在 (b) 之前）。
        // 單靠 (b) 不夠——transit_fund_type 的「賣股待收款」「退稅」payable=false、金額為正，v > 0 攔不住。
        List<Holding> usable = new ArrayList<>();
        BigDecimal transitExcluded = BigDecimal.ZERO;
        for (Holding h : (breakdown == null || breakdown.holdings() == null) ? List.<Holding>of() : breakdown.holdings()) {
            if (h == null) {
                continue;
            }
            if (isTransitCurrency(h.currency())) {
                transitExcluded = transitExcluded.add(nz(h.currentValue()));
            } else {
                usable.add(h);
            }
        }

        WaterfallLog log = new WaterfallLog();
        Map<String, List<PortfolioAdviceResult.Rebalance>> details = new LinkedHashMap<>();
        for (PortfolioAdviceResult.TargetAllocation t : enriched.targetAllocation()) {
            if (t == null || t.assetClass() == null) {
                continue;
            }
            List<PortfolioAdviceResult.Rebalance> rows = new ArrayList<>();
            if (CLASS_CASH.equals(t.assetClass())) {
                rows.addAll(groupRows(t.assetClass(), null, t.deltaAmount(), usable, log));
            } else if (t.subAllocations() != null) {
                for (PortfolioAdviceResult.SubAllocation sa : t.subAllocations()) {
                    if (sa != null) {
                        rows.addAll(groupRows(t.assetClass(), sa.subClass(), sa.deltaAmount(), usable, log));
                    }
                }
            }
            details.put(t.assetClass(), rows);
        }

        // 類別層級三筆保留、標的層級明細接在各自那一筆之後（前端據此分組渲染）
        List<PortfolioAdviceResult.Rebalance> plan = new ArrayList<>();
        for (PortfolioAdviceResult.Rebalance r : enriched.rebalancePlan()) {
            plan.add(r);
            if (r != null && HOLDING_OVERALL.equals(r.holding())) {
                plan.addAll(details.getOrDefault(r.assetClass(), List.of()));
            }
        }

        List<String> warnings = new ArrayList<>(
                enriched.warnings() == null ? List.of() : enriched.warnings());
        if (transitExcluded.signum() != 0) {
            warnings.add(String.format(TRANSIT_DEPOSIT_WARNING_TEMPLATE, money(transitExcluded)));
        }
        if (!log.timeDepositRows.isEmpty()) {
            warnings.add(String.format(TIME_DEPOSIT_WARNING_TEMPLATE,
                    money(log.timeDepositTotal), String.join("、", log.timeDepositRows)));
        }

        return new PortfolioAdviceResult(
                enriched.summary(), enriched.riskAssessment(), enriched.targetAllocation(),
                List.copyOf(plan), enriched.actions(), List.copyOf(warnings), enriched.references());
    }

    /**
     * 在途／轉帳中的幣別判定。<b>判準是 {@code currency} 欄位、不是 {@code depositType}</b>
     * （實測存的是 {@code deposit_type='信用卡待付款'} ＋ {@code currency='TRANSIT_TWD'}），且
     * <b>沿用既有的「兩個字面值全等」寫法</b>（{@code AssetService} 三處同款），不改用
     * {@code startsWith("TRANSIT_")}——改用前綴等於在同一語意上開第四套判準，
     * 日後新增 {@code TRANSIT_EUR} 時新舊碼會靜默分歧。
     */
    static boolean isTransitCurrency(String currency) {
        return "TRANSIT_TWD".equals(currency) || "TRANSIT_USD".equals(currency);
    }

    /** waterfall 實際抽到定存的紀錄（供 {@link #TIME_DEPOSIT_WARNING_TEMPLATE} 條件式輸出）。 */
    private static final class WaterfallLog {
        private final List<String> timeDepositRows = new ArrayList<>();
        private BigDecimal timeDepositTotal = BigDecimal.ZERO;
    }

    /** 分攤過程中的一個標的（已合併同標的多列、已捨入到整數元）。 */
    private static final class Slice {
        private final Holding holding;
        private final int mergedRows;
        private final BigDecimal value;      // v_i（整數元）
        private BigDecimal amount = BigDecimal.ZERO;
        private boolean capped;

        private Slice(Holding holding, int mergedRows, BigDecimal value) {
            this.holding = holding;
            this.mergedRows = mergedRows;
            this.value = value;
        }
    }

    /** 單一群組 {@code (assetClass, subClass)} 的標的層級明細。 */
    private List<PortfolioAdviceResult.Rebalance> groupRows(
            String assetClass, String subClass, BigDecimal delta, List<Holding> usable, WaterfallLog log) {
        if (delta == null) {
            return List.of();   // T_g 未知（無快照）→ 整組略過，不得走 P_g = ∅ 分支（會 NPE）
        }
        // 344.7：方向由「捨入前」的 signum 決定，分攤總額用「捨入後」的 abs
        int sign = delta.signum();
        BigDecimal total = delta.setScale(0, RoundingMode.HALF_UP).abs();
        if (total.signum() == 0) {
            return List.of();   // 捨入後無金額可分攤（含 delta 恰為 0）
        }

        List<Slice> pool = poolOf(usable, assetClass, subClass);
        if (pool.isEmpty()) {
            return List.of(unspecifiedRow(assetClass, subClass, total, sign));
        }

        boolean sell = sign < 0;
        BigDecimal groupValue = BigDecimal.ZERO;
        for (Slice s : pool) {
            groupValue = groupValue.add(s.value);
        }
        BigDecimal shortfall;
        List<Slice> ordered;
        if (CLASS_CASH.equals(assetClass) && sell) {
            ordered = waterfallOrder(pool);
            shortfall = allocateWaterfall(ordered, total);   // 輸出順序即提領順序，本身已是全序
        } else {
            shortfall = allocateProportional(pool, total, sell);
            // 分攤後以「金額大→小、stableKey 小→大」輸出（全序，與輸入清單順序無關）
            ordered = new ArrayList<>(pool);
            ordered.sort(Comparator.comparing((Slice s) -> s.amount).reversed()
                    .thenComparing(s -> nullSafe(s.holding.stableKey())));
        }

        List<PortfolioAdviceResult.Rebalance> rows = new ArrayList<>();
        String action = sell ? ACTION_SELL : ACTION_BUY;
        boolean waterfall = CLASS_CASH.equals(assetClass) && sell;
        List<Integer> smallIndexes = new ArrayList<>();
        BigDecimal smallSum = BigDecimal.ZERO;
        for (Slice s : ordered) {
            if (s.amount.signum() <= 0) {
                continue;   // 未被抽到的存款列完全不出現（不是 HOLD）
            }
            if (waterfall && s.holding.withdrawalOrder() != null
                    && s.holding.withdrawalOrder() >= TIME_DEPOSIT_WITHDRAWAL_ORDER_MIN) {
                log.timeDepositRows.add(s.holding.displayName() + " " + money(s.amount) + " 元");
                log.timeDepositTotal = log.timeDepositTotal.add(s.amount);
            }
            rows.add(new PortfolioAdviceResult.Rebalance(assetClass, subClass, s.holding.displayName(), action,
                    s.amount, detailRationale(assetClass, subClass, s, groupValue, sell, waterfall, null)));
            if (s.amount.compareTo(MIN_REBALANCE_AMOUNT) < 0) {
                smallIndexes.add(rows.size() - 1);
                smallSum = smallSum.add(s.amount);
            }
        }
        // 344.14：同群組內低於門檻者合併為單一列；<b>N == 1 時保留原標的名稱、也保留原位置</b>，只附加提示
        // （存款走 waterfall 時，列的先後就是提領順序，把唯一一筆小額移到最後會讀不出來）
        if (smallIndexes.size() == 1) {
            int i = smallIndexes.get(0);
            PortfolioAdviceResult.Rebalance r = rows.get(i);
            rows.set(i, new PortfolioAdviceResult.Rebalance(r.assetClass(), r.subClass(), r.holding(),
                    r.action(), r.estimatedAmount(),
                    r.rationale() + smallAmountHint(assetClass, r.estimatedAmount())));
        } else if (smallIndexes.size() > 1) {
            for (int k = smallIndexes.size() - 1; k >= 0; k--) {
                rows.remove((int) smallIndexes.get(k));
            }
            // 不得把小額併進同群組最大一筆——那會讓各標的分到的金額不再等於「現值占比 × 群組差額」，
            // 直接破壞本功能唯一可解釋的規則。合併列同時保住守恆與可解釋性。
            rows.add(new PortfolioAdviceResult.Rebalance(assetClass, subClass,
                    "其餘 " + smallIndexes.size() + " 檔（每檔不足 " + money(MIN_REBALANCE_AMOUNT) + " 元）",
                    action, smallSum,
                    groupLabel(assetClass, subClass) + "另有 " + smallIndexes.size() + " 檔的分攤金額各自低於 "
                            + money(MIN_REBALANCE_AMOUNT) + " 元，合併為一列，合計 " + money(smallSum) + " 元。"
                            + smallAmountHint(assetClass, smallSum)));
        }
        // 344.12／344.18：shortfall 只寫進最後一列的 rationale，不進 estimatedAmount
        if (shortfall.signum() > 0 && !rows.isEmpty()) {
            int last = rows.size() - 1;
            PortfolioAdviceResult.Rebalance r = rows.get(last);
            rows.set(last, new PortfolioAdviceResult.Rebalance(r.assetClass(), r.subClass(), r.holding(),
                    r.action(), r.estimatedAmount(),
                    r.rationale() + "本群組可賣出上限 " + money(groupValue) + " 元，仍差 " + money(shortfall)
                            + " 元未能達成目標，需由其他群組補足。"));
        }
        return rows;
    }

    /**
     * 群組候選標的：先依 {@code (assetClass, subClass)} 挑出，再<b>合併同一標的的多列</b>（344.13，
     * 合併鍵即 {@code stableKey}：股票 {@code market|stockCode}、基金 {@code fundName}；
     * <b>存款的 stableKey 含列 id 故天然不合併</b>——同一銀行的兩筆定存到期日可能不同，
     * 合併後 {@code annualInterestRate} 該顯示哪一個無解），最後套 344.10(b) 的 {@code v ≤ 0} 過濾。
     */
    private static List<Slice> poolOf(List<Holding> usable, String assetClass, String subClass) {
        Map<String, List<Holding>> merged = new LinkedHashMap<>();
        for (Holding h : usable) {
            if (!assetClass.equals(h.assetClass()) || !Objects.equals(subClass, h.subClass())) {
                continue;
            }
            merged.computeIfAbsent(nullSafe(h.stableKey()), k -> new ArrayList<>()).add(h);
        }
        List<Slice> pool = new ArrayList<>();
        for (List<Holding> rows : merged.values()) {
            BigDecimal sum = BigDecimal.ZERO;
            for (Holding h : rows) {
                sum = sum.add(nz(h.currentValue()));
            }
            BigDecimal v = sum.setScale(0, RoundingMode.HALF_UP);
            if (v.signum() > 0) {   // 344.10(b)
                pool.add(new Slice(rows.get(0), rows.size(), v));
            }
        }
        return pool;
    }

    /**
     * 等比例分攤（344.8）＋ 減碼上限 clamp（344.12）。回傳 shortfall（全部觸頂仍不足的金額，
     * <b>不得跨群組轉嫁</b>——會讓上下兩區塊再度對不上）。
     *
     * <p>選 FLOOR 而非 HALF_UP：FLOOR 保證 {@code Σ floor_i ≤ T_g} 且缺口小於標的數，只需單向補足且必然終止；
     * HALF_UP 可能超過 {@code T_g}，得再定一套往回扣的規則。</p>
     */
    private static BigDecimal allocateProportional(List<Slice> pool, BigDecimal total, boolean clamp) {
        for (Slice s : pool) {
            s.amount = BigDecimal.ZERO;
            s.capped = false;
        }
        addLargestRemainder(pool, total);
        if (!clamp) {
            return BigDecimal.ZERO;   // BUY 方向不設上限
        }
        // 每一輪至少一筆固定觸頂 → 最多重複 |P| 輪，必然終止
        for (int round = 0; round <= pool.size(); round++) {
            BigDecimal overflow = BigDecimal.ZERO;
            List<Slice> free = new ArrayList<>();
            for (Slice s : pool) {
                if (s.amount.compareTo(s.value) > 0) {
                    overflow = overflow.add(s.amount.subtract(s.value));
                    s.amount = s.value;
                    s.capped = true;
                } else if (!s.capped) {
                    free.add(s);
                }
            }
            if (overflow.signum() == 0) {
                return BigDecimal.ZERO;
            }
            if (free.isEmpty()) {
                return overflow;   // 全部觸頂仍不足 → 記為該群組 shortfall
            }
            addLargestRemainder(free, overflow);
        }
        return BigDecimal.ZERO;
    }

    /**
     * FLOOR ＋ 最大餘額法把 {@code total} 加到各 slice 上（權重為 {@code v_i}）。
     *
     * <p><b>禁止先算權重再乘 T_g</b>（兩次捨入放大誤差）：只做一次乘除算 {@code floor_i}，
     * 餘數 {@code rem_i = v_i × T − floor_i × V} 為整數運算、零誤差。
     * <b>tie-break 必須是全序</b>（{@code rem 大→小, v 大→小, stableKey 小→大}），
     * 不得依賴 Map 迭代順序——那會讓同一份輸入在不同次執行產生不同答案。</p>
     */
    private static void addLargestRemainder(List<Slice> slices, BigDecimal total) {
        BigDecimal groupValue = BigDecimal.ZERO;
        for (Slice s : slices) {
            groupValue = groupValue.add(s.value);
        }
        if (groupValue.signum() <= 0) {
            return;
        }
        List<BigDecimal> floors = new ArrayList<>(slices.size());
        List<BigDecimal> remainders = new ArrayList<>(slices.size());
        BigDecimal assigned = BigDecimal.ZERO;
        for (Slice s : slices) {
            BigDecimal product = s.value.multiply(total);
            BigDecimal floor = product.divide(groupValue, 0, RoundingMode.FLOOR);
            floors.add(floor);
            remainders.add(product.subtract(floor.multiply(groupValue)));
            assigned = assigned.add(floor);
        }
        int rest = total.subtract(assigned).intValueExact();   // 數學上保證 0 ≤ R < |slices|
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < slices.size(); i++) {
            order.add(i);
        }
        final List<BigDecimal> rem = remainders;
        order.sort(Comparator.<Integer, BigDecimal>comparing(rem::get).reversed()
                .thenComparing(Comparator.<Integer, BigDecimal>comparing(i -> slices.get(i).value).reversed())
                .thenComparing(i -> nullSafe(slices.get(i).holding.stableKey())));
        for (int i = 0; i < slices.size(); i++) {
            slices.get(i).amount = slices.get(i).amount.add(floors.get(i));
        }
        for (int i = 0; i < rest; i++) {
            Slice s = slices.get(order.get(i));
            s.amount = s.amount.add(BigDecimal.ONE);
        }
    }

    /**
     * 存款減碼的提領優先序（344.9）：{@code (withdrawalOrder 小→大, annualInterestRate 小→大 NULLS FIRST,
     * currentValue 大→小, stableKey 小→大)}。{@code withdrawalOrder} 為 null（未設定的類型）排最後
     * ——不知道優先序時不該優先動它。
     */
    private static List<Slice> waterfallOrder(List<Slice> pool) {
        List<Slice> ordered = new ArrayList<>(pool);
        ordered.sort(Comparator
                .comparing((Slice s) -> s.holding.withdrawalOrder(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(s -> s.holding.annualInterestRate(),
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Comparator.comparing((Slice s) -> s.value).reversed())
                .thenComparing(s -> nullSafe(s.holding.stableKey())));
        return ordered;
    }

    /**
     * waterfall 逐筆抽取（344.9，<b>使用者拍板，覆蓋 344.8 的等比例</b>）：前一筆抽滿其 {@code v_i}
     * 才動下一筆，最後一筆為部分抽取。正當性來自「定存中途解約按實際存期折算利息」這個可量化的<b>賣出成本</b>
     * ——故<b>只用於減碼</b>，存款的增碼仍走等比例。回傳未能抽足的 shortfall。
     */
    private static BigDecimal allocateWaterfall(List<Slice> ordered, BigDecimal total) {
        BigDecimal remaining = total;
        for (Slice s : ordered) {
            s.amount = BigDecimal.ZERO;
            if (remaining.signum() <= 0) {
                continue;
            }
            BigDecimal take = remaining.min(s.value);
            s.amount = take;
            remaining = remaining.subtract(take);
        }
        return remaining;
    }

    /**
     * {@code P_g = ∅} 的兩個分支（344.16 增碼／344.17 防禦）——<b>必須輸出、且必須與「系統沒做」在視覺上可區分</b>。
     *
     * <p>三個被否決的替代方案：<b>略過</b> → 實測使用者信託基金四個零部位子類別占 {@code Σ|群組 T_g|} 約 46%，
     * 略過會讓守恆破功且畫面憑空少掉最大一塊；<b>用 {@value #HOLDING_OVERALL}</b> → 前端是
     * {@code r.holding || r.assetClass}，render 結果與本功能之前一模一樣，使用者無法分辨「系統做不到」與「系統沒做」；
     * <b>沿用 {@value #ACTION_BUY}</b> → 綠色「增碼」tag 是可執行動作的視覺語意，套在他無從執行的金額上會誤導。</p>
     *
     * <p>{@code holding} 寫成自我說明字串而非只寫子類別名，是因為這筆會經
     * {@code GET /api/public/portfolio-advice/latest} 以純 JSON 被讀走，脫離 tag 的視覺語境仍須成立。</p>
     */
    private static PortfolioAdviceResult.Rebalance unspecifiedRow(
            String assetClass, String subClass, BigDecimal amount, int sign) {
        String label = groupLabel(assetClass, subClass);
        String rationale = sign > 0
                ? assetClass + "「" + (subClass == null ? assetClass : subClass) + "」目標 " + money(amount)
                        + " 元、目前 0 元。本機引擎只能把金額按比重分攤到你已持有的標的，此子類別沒有部位可分攤，"
                        + "無法指名該買哪一檔；需要具名的基金／個股建議，請把上方「分析引擎」切換為「完整 AI 分析」後重新產生。"
                // 理論上不可達（要減碼代表現況 > 目標 ≥ 0，該群組必有部位），但桶 currentValue 來自彙總欄位、
                // P_g 來自逐筆明細，兩者漂移時可達。不得靜默吞掉，也不得拋例外（把使用者可自行修復的資料問題變成 500）。
                : label + "該群組現況金額來自快照彙總欄位，但找不到對應的持有明細，"
                        + "請於『管理資產』重新儲存快照以重算彙總。";
        return new PortfolioAdviceResult.Rebalance(assetClass, subClass,
                (subClass == null ? assetClass : subClass) + "：尚無持有標的",
                ACTION_UNSPECIFIED, amount, rationale);
    }

    private static String detailRationale(String assetClass, String subClass, Slice s, BigDecimal groupValue,
                                          boolean sell, boolean waterfall, String extraHint) {
        StringBuilder sb = new StringBuilder();
        if (waterfall) {
            sb.append("依提領優先序逐筆抽取（活存優先，以避開定期存款中途解約的利息損失）：本筆現有 ")
                    .append(money(s.value)).append(" 元，抽取 ").append(money(s.amount)).append(" 元")
                    .append(s.amount.compareTo(s.value) >= 0 ? "（全額）。" : "（部分）。");
        } else {
            sb.append(groupLabel(assetClass, subClass)).append("現有部位合計 ").append(money(groupValue))
                    .append(" 元，本筆 ").append(money(s.value)).append(" 元、占 ")
                    .append(plain(pctOf(s.value, groupValue))).append("%，按同一比重分攤本次")
                    .append(sell ? "減碼 " : "增碼 ").append(money(s.amount)).append(" 元。");
        }
        if (s.mergedRows > 1) {
            sb.append("本筆為 ").append(s.mergedRows).append(" 個帳戶／券商的合計。");
        }
        if (extraHint != null) {
            sb.append(extraHint);
        }
        return sb.toString();
    }

    /** 小額提示：存款不得用「交易成本」措辭（提領無手續費，且該筆多為 waterfall 的全額抽取）。 */
    private static String smallAmountHint(String assetClass, BigDecimal amount) {
        return CLASS_CASH.equals(assetClass)
                ? String.format(SMALL_AMOUNT_HINT_DEPOSIT_TEMPLATE, money(amount))
                : SMALL_AMOUNT_HINT_TRADABLE;
    }

    private static String groupLabel(String assetClass, String subClass) {
        return subClass == null ? assetClass : assetClass + "－" + subClass;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    // ===== 敘述組裝 =====

    private static PortfolioAdviceResult.TargetAllocation allocation(
            String assetClass, BigDecimal targetPct, Map<String, BigDecimal> currentValues,
            String role, String cellRationale) {
        return new PortfolioAdviceResult.TargetAllocation(
                assetClass,
                targetPct,
                currentValues.get(assetClass),
                null,   // targetAmount：由既有 enrich() 回填
                null,   // deltaAmount：由既有 enrich() 回填
                role + "。本次目標 " + plain(targetPct) + "%：" + cellRationale,
                List.of());   // subAllocations：由 withSubAllocationAmounts 於 enrich 之後補上
    }

    private static String rebalanceRationale(PortfolioAdviceResult.TargetAllocation t, String action) {
        String verb = switch (action) {
            case ACTION_BUY -> "低於目標，建議增加";
            case ACTION_SELL -> "高於目標，建議減少";
            default -> "已達目標，維持不動";
        };
        return t.assetClass() + "目前 " + money(t.currentValue()) + " 元、目標 " + money(t.targetAmount())
                + " 元（" + plain(t.targetPct()) + "%），" + verb
                + "；本類的總量如上，逐一標的的建議金額見以下明細。";
    }

    private String buildSummary(String risk, Horizon horizon, Integer yearsToRetirement,
                                Template t, Map<String, BigDecimal> currentValues, BigDecimal total) {
        StringBuilder sb = new StringBuilder();
        // 自我標示：歷史列表與純文字閱讀時即可分辨兩種來源，不必回查 model 欄位
        sb.append("【本機配置模板產生，非個人化投資建議】");
        sb.append("依「風險承受度：").append(riskLabel(risk)).append("、").append(horizon.label())
                .append(yearsToRetirement == null ? "" : "（約 " + yearsToRetirement + " 年）")
                .append("」套用配置模板，建議目標配置為存款（現金） ").append(plain(t.cashPct()))
                .append("%、信託基金 ").append(plain(t.fundPct()))
                .append("%、股票 ").append(plain(t.stockPct())).append("%。");
        if (total != null && total.signum() > 0) {
            sb.append("目前資產總額 ").append(money(total)).append(" 元，現況配置為存款（現金） ")
                    .append(pctOf(currentValues.get(CLASS_CASH), total)).append("%、信託基金 ")
                    .append(pctOf(currentValues.get(CLASS_FUND), total)).append("%、股票 ")
                    .append(pctOf(currentValues.get(CLASS_STOCK), total)).append("%。");
        } else {
            sb.append("目前尚無資產快照，故只給比例、未計算金額。");
        }
        sb.append("本結論由固定對照表得出，未經回測驗證，也未納入當前市場環境（本檔位不搜尋網路）；"
                + "調整動作只到類別層級，不含個股層級的買賣判斷。");
        return sb.toString();
    }

    /**
     * 風險評估：援引既有 {@code RetirementProjectionService} 的逐年試算結果組出敘述。
     * <b>本引擎不重算任何一年的現金流</b>——那是 Requirement 32 / Task 165 既有的唯一事實來源。
     */
    private String buildRiskAssessment(String risk, Horizon horizon, RetirementProjectionDto projection,
                                       Map<String, BigDecimal> currentValues, BigDecimal total, Template t) {
        StringBuilder sb = new StringBuilder();
        if (total != null && total.signum() > 0) {
            BigDecimal stockPct = pctOf(currentValues.get(CLASS_STOCK), total);
            BigDecimal cashPct = pctOf(currentValues.get(CLASS_CASH), total);
            sb.append("現況與目標的落差：股票占比 ").append(plain(stockPct)).append("%（目標 ")
                    .append(plain(t.stockPct())).append("%）、存款（現金）占比 ").append(plain(cashPct))
                    .append("%（目標 ").append(plain(t.cashPct())).append("%）。");
            int stockGap = stockPct.compareTo(t.stockPct());
            if (stockGap > 0) {
                sb.append("以").append(riskLabel(risk)).append("的屬性而言，股票部位偏高，"
                        + "遇到大回檔時的帳面波動會超過你自述能承受的範圍。");
            } else if (stockGap < 0) {
                sb.append("股票部位低於模板目標，長期購買力的成長動能相對不足，但短期波動也較小。");
            } else {
                sb.append("股票部位與模板目標一致。");
            }
            if (cashPct.compareTo(t.cashPct()) > 0) {
                sb.append("現金比重高於目標，安全但長期會被通膨侵蝕。");
            }
        } else {
            sb.append("尚無資產快照，無法評估現況與目標的落差。");
        }
        if (projectionAvailable(projection)) {
            sb.append("退休現金流試算（系統依你的假設所做的決定性逐年試算，非預測）");
            if (projection.retirementAge() != null) {
                sb.append("以約 ").append(projection.retirementAge()).append(" 歲退休為前提，");
            }
            if (projection.lastsToEndAge()) {
                sb.append("結論為資產可支應至 ").append(projection.endAge()).append(" 歲，屆時約剩 ")
                        .append(money(projection.endBalance())).append(" 元，未見缺口；")
                        .append(horizon == Horizon.IMMINENT
                                ? "臨退階段仍建議維持較高現金比重，以免在低點被迫變現。"
                                : "可依此維持目前的提領與投入節奏，並每年重新試算。");
            } else {
                sb.append("結論為資產預計在約 ").append(projection.depletionAge()).append(" 歲（")
                        .append(projection.depletionYear()).append(" 年）出現資金缺口。")
                        .append("建議優先檢視退休後年生活費、延後大額支出或提高退休前淨投入；"
                                + "單靠提高股票比重來補缺口會同時放大提領期的順序報酬風險。");
            }
        } else {
            sb.append("退休現金流試算本次無法進行（").append(safe(projection == null ? null : projection.unavailableReason()))
                    .append("），故此處未能回答「資產能撐到幾歲」。");
        }
        return sb.toString();
    }

    /** 非金額類的做法步驟（模板固定三項；金額類的加減碼一律在 rebalancePlan）。 */
    private static List<PortfolioAdviceResult.Action> buildActions(Horizon horizon) {
        List<PortfolioAdviceResult.Action> actions = new ArrayList<>();
        actions.add(new PortfolioAdviceResult.Action(
                "定期再平衡",
                "每半年檢視一次三類占比，任一類偏離目標超過 5 個百分點時，把超出的部分調回目標比例；"
                        + "再平衡的重點是紀律，不是預測轉折點。",
                "HIGH"));
        actions.add(new PortfolioAdviceResult.Action(
                "確認緊急預備金",
                "先確定存款（現金）中有至少 6 個月生活費的可動用資金，這筆錢不計入投資部位；"
                        + "不足時應優先補足，再談其餘配置。",
                horizon == Horizon.IMMINENT ? "HIGH" : "MEDIUM"));
        actions.add(new PortfolioAdviceResult.Action(
                "每年更新理財條件並重新試算",
                "退休日期、年薪、年支出與大筆花費一有變動就更新，退休現金流試算與本配置模板的距退休年數"
                        + "都會跟著改變；本檔位不會自動追蹤市場，需要納入當前總經環境時請改用「完整 AI 分析」檔位。",
                "MEDIUM"));
        return List.copyOf(actions);
    }

    // ===== 小工具 =====

    /** 由既有 {@code getCurrentAllocation()} 的 items 取三類現況金額（不重查 asset_snapshot）。 */
    private static Map<String, BigDecimal> currentValuesOf(CurrentAllocationDto current) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        if (current != null && current.items() != null) {
            for (CurrentAllocationDto.Item item : current.items()) {
                if (item != null && item.assetClass() != null) {
                    m.put(item.assetClass(), item.value());
                }
            }
        }
        return m;
    }

    private static boolean projectionAvailable(RetirementProjectionDto p) {
        return p != null && p.available();
    }

    private static String riskLabel(String risk) {
        return switch (risk) {
            case RISK_AGGRESSIVE -> "積極";
            case RISK_BALANCED -> "穩健";
            default -> "保守";
        };
    }

    /** 占比%（保留 1 位小數，與既有 {@code PortfolioAdviceService.pct} 同慣例）；total<=0 回 0。 */
    private static BigDecimal pctOf(BigDecimal value, BigDecimal total) {
        if (value == null || total == null || total.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return value.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP);
    }

    private static String plain(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    private static String money(BigDecimal v) {
        if (v == null) {
            return "0";
        }
        return String.format("%,.0f", v.setScale(0, RoundingMode.HALF_UP));
    }

    private static String safe(String s) {
        return s == null ? "原因未提供" : s;
    }
}
