package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.StockFundamentalFetchClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 美股歷史估值序列推導（Requirement 74 / Task 334.3）：由<b>已入庫的官方財報</b>逐交易日推導
 * PE／PB／殖利率，寫成 {@code stock_valuation_daily} 的 {@code provider='SEC_DERIVED'} 列。
 *
 * <h2>(i) 資料來源與推導算式</h2>
 * <p>唯讀三張既有表、純本地計算，<b>不發任何 HTTP</b>：</p>
 * <ul>
 *   <li>{@code stock_financial_quarter}（{@code market='美股'}、{@code provider='SEC_EDGAR'}）：
 *       會計年度<b>累計</b> EPS／母公司淨利，以及期末母公司權益（時點值）。美股單位是原始 USD，
 *       <b>不做千元換算</b>（千元是台股口徑）。</li>
 *   <li>{@code stock_price_history}（{@code market='美股'}、{@code close_price>0}）：交易日集合與收盤價。
 *       美股收盤<b>已還原股票分割</b>。</li>
 *   <li>{@code stock_dividend_history}（{@code market='美股'}、{@code event_status='ACTIVE'}）：現金股利。</li>
 * </ul>
 * <pre>
 * 單季值(Q)   = 累計(Q) − 累計(Q−1)，Q1 直接取累計          （見 {@link #standaloneValue}）
 * TTM         = 最近 4 個「連續」季度單季值加總；缺季或不連續 → 該日不落列
 * pe_ratio    = 收盤 ÷ TTM EPS；TTM EPS ≤ 0 → NULL ＋ pe_loss_flag=true（<b>該列仍寫入</b>）
 * 推導股數     = TTM 母公司淨利 ÷ TTM EPS（分子分母必須同一組 TTM 季度）
 * pb_ratio    = 收盤 ÷（母公司權益 ÷ 推導股數）；每股淨值 ≤ 0／TTM EPS = 0／權益 null → NULL
 * yield_pct   = 近 365 日現金股利加總 ÷ 收盤 × 100；D 之前查無任何除息紀錄 → NULL（不得寫 0）
 * </pre>
 *
 * <h2>(i-b) 為何這支服務放在 external-materials-service，而不是 business-services</h2>
 * <p>Requirement 74 明訂的兩個理由，兩個都要成立才站得住：</p>
 * <ol>
 *   <li><b>落地必須走只存在於本服務的 {@link FundamentalObservationStore} append-on-change 去重路徑。</b>
 *       {@code stock_valuation_daily} 是 append-only 觀測表（DB 只有 PK(id)、無任何 unique index），
 *       去重完全靠寫入端「取該業務鍵最新一列比對、值相同就不寫」。若在 business-services 另開一條
 *       寫入路徑，同一張表會有兩套去重語意，日後必然分岔。</li>
 *   <li><b>{@code UsValuationDerivationScheduler} 步驟一的 SEC EDGAR 重抓本來就是對外 HTTP</b>，
 *       受架構鐵則「❌ business-services 直接打外部行情／NAV／配息 API（一律經 external-materials）」約束。</li>
 * </ol>
 * <p><b>但不要把第 2 點當成第 1 點的替代理由</b>：本類別的推導計算<b>不發任何 HTTP</b>（只讀三張既有表），
 * 那條鐵則管不到它——放在這裡的真正理由是第 1 點。代價是單季還原邏輯在 backend 與本服務各有一份
 * （兩個 module 無法互相呼叫），故 {@link #standaloneValue} 以相同輸入的斷言與 backend
 * {@code FundamentalAnalysisService.standaloneValue} 釘在一起；本專案已有
 * {@code expectedFinancialPeriodIndex} 兩份複本走樣的前例。business-services 對本功能的唯一改動是
 * provider 白名單與 {@code latestLoss} 排除兩處，不得新增任何對外 HTTP。</p>
 *
 * <h2>(ii) 為何刻意 denormalization（可由來源重算的值仍落地保存）</h2>
 * <p>這三個值理論上都能在需要時即時重算，但<b>分位計算需要一條固定且可重現的觀測序列</b>：
 * {@code FundamentalAnalysisService.valuationComponent(...)} 是拿「今天的值」對「該 provider 自身的
 * 整條歷史」算分位。逐次即時重算會讓同一個歷史日期的值隨(1)財報事後重述、(2)價格還原權息與分割調整
 * 而漂移，於是同一個因子在回測與 production 之間失去可比性——Requirement 65 的 walk-forward／holdout
 * 樣本外驗證正好建立在「歷史那一天看到的值不會變」之上。比照 {@code asset_snapshot.total_*}
 * 歷史快照匯總欄位的既有加註體例，這是刻意的 denormalization。</p>
 *
 * <h2>(iii) {@code SEC_DERIVED} 是推導值，不得被顯示成官方公告值</h2>
 * <p>{@code availability_basis} 一律寫 {@code RECONSTRUCTED}（不是 {@code PUBLISHED}／{@code OBSERVED}），
 * {@code source_urls} 除了 companyfacts URL 之外一定帶一個 {@link #DERIVED_URL_MARKER} 標記。任何 UI／
 * 報表都不得把這個 provider 呈現為交易所或發行人公告的 PE／PB／殖利率。同理，backend 的
 * {@code latestLoss(...)}（虧損與否屬一手觀測事實）必須排除本 provider（Task 334.5）。</p>
 *
 * <h2>point-in-time：先單調化 {@code source_available_at} 再過濾</h2>
 * <p>{@code stock_financial_quarter.source_available_at} 的語意<b>必須</b>是「該期別的<b>首次</b>申報時點」。
 * 寫入端 {@code StockFundamentalFetchClient.selectCumulative}／{@code selectInstant} 因此把「取哪個值」與
 * 「什麼時候可見」分開追蹤：值取 {@code filed} 最新的一筆（重述後的正確數字），可見時點取同一期間全部候選中
 * <b>最早</b>的 {@code filed}。這一點做錯不會有任何錯誤訊息——每一份 10-Q／10-K 都夾帶去年同季的比較數字，
 * 若可見時點跟著「最新 filed」跑，每個舊期別都會被推遲整整一年（實測 GOOGL 舊季 388–401 天、最新四季只有
 * 23–36 天），而<b>這個位移對期別是保序的，下面的單調化取不掉</b>，結果是同一條序列的歷史區段用落後約四季
 * 的 TTM 分母、最近一年用當期值，成長股的「今天」必然落在自身歷史 PE 的極低分位而輸出「現在最便宜」。</p>
 * <p>剩下的是真正倒置的日期（實測 AMZN 2025Q2 的 {@code 2026-07-31} 晚於 2025Q3 的 {@code 2025-10-31}、
 * MSFT 三個 Q4 共用同一個 {@code filed}），非單調會讓「4 個連續季度」時有時無。<b>規則：</b>
 * {@code effective_available_at(Q) = min{ source_available_at(Q') | Q' ≥ Q }}。較新期別既已公開，較舊期別
 * 必然早已公開，故此式是真實首次公布時點的<b>上界</b>、不引入未來資訊；它同時保證「可見期別集合恆為由最舊
 * 起算的連續前綴」，四季視窗因此不會有洞。之後才以「≤ D 當日美股收盤時刻」與「4 連續季」過濾。
 * <b>落地列的 {@code source_available_at} 也用單調化後的值</b>——改用 raw {@code filed} 會讓一整段推導列
 * 塌成同一天。</p>
 * <p>「當日收盤時刻」這個比較基準只有在 {@code filed} 的時刻部分晚於收盤時才不會 look-ahead：SEC
 * {@code filed} 只有日期精度，寫入端一律換算為<b>申報日 16:30 America/New_York</b>（收盤後），故某季
 * 從<b>申報日的下一個交易日</b>起才可見。若把它寫成當日中午 UTC（＝08:00 ET，開盤前），申報當日的推導列
 * 就會是「盤前價 ÷ 尚未公開的財報」——每季一天，且恰好是財報公布日。</p>
 * <p><b>已知限制（不在本服務可解範圍）：</b>{@code observed_at} 由 {@code FundamentalObservationStore}
 * 寫成 {@code now()}，整段歷史回補會共用同一個回補當下的 {@code observed_at}。backend
 * {@code FundamentalAnalysisService} 的 as-of 過濾同時看 {@code source_available_at} 與 {@code observed_at}，
 * 因此<b>早於回補時點的 walk-forward／holdout 決策時點仍會整組取不到 {@code SEC_DERIVED} 列</b>。
 * 這是所有歷史回補共有的性質、方向保守（只會缺值不會錯值），與 {@code source_available_at} 怎麼算無關；
 * 要讓回測看見必須另案處理 {@code observed_at} 的語意，<b>不得</b>靜默放寬 backend 那道檢查。</p>
 *
 * <h2>每股基準閘門</h2>
 * <p>收盤價是還原價、SEC 每股值是申報當下基準，兩者相除會讓<b>跨越分割點的區段整段錯一個分割倍數</b>
 * （PB 同步錯同一倍數），且分位是在整條被污染的序列上算的，症狀是長期輸出「現在最貴」。故計算每季的
 * <b>單季</b>推導股數（單季淨利 ÷ 單季 EPS），由新到舊掃描，相鄰季度比值 ≥ {@link #BASIS_CHANGE_MIN_RATIO}
 * 即視為基準變動點，只有比最新一個變動點更新的季度可用。<b>口徑必須寫死為單季</b>：TTM 口徑會把一次
 * 分割攤平成四個小台階（2:1 → 1.14/1.17/1.20/1.25；4:1 → 1.23/1.30/1.43/1.75，全部低於門檻），
 * 閘門會靜默失效。下列情形一律視為「基準不可驗證」＝等同變動點（fail closed）：淨利為 null、單季 EPS
 * 為 0、單季 EPS 或單季淨利不為正（虧損季的推導股數為負，而「比值 ≥ 1.8」對負數永遠為 false，
 * 等於對整個虧損區段靜默放行）。</p>
 *
 * <h2>門檻取值誠實登記</h2>
 * <p>{@link #BASIS_CHANGE_MIN_RATIO}（1.8）、股利去重的 {@link #DIVIDEND_PRECISION_MAX_RATIO}（1.01）與
 * {@link #SPLIT_RATIO_TOLERANCE}（0.10）<b>皆無回測量測依據，為判斷性取值</b>，比照
 * {@code spec/requirements.md} 既有的「無量測依據門檻清單」體例。取 1.8 而非所比照的
 * {@code DistributionAdjustedPriceService.SPLIT_FORWARD_MIN = 2.0}，是留餘裕給 EPS 只有兩位小數造成的
 * 推導股數雜訊（該餘裕隨 EPS 絕對值變動，EPS 落在 ±0.0x 時可達 ±25%，這也是非正 EPS 直接判為變動點的
 * 原因之一）。<b>本類別不宣稱、也不得被引用來宣稱這些取值能提升報酬或降低風險。</b></p>
 *
 * <h2>與 backend 的鏡像關係</h2>
 * <p>{@link #standaloneValue(int, BigDecimal, BigDecimal)} 是 backend
 * {@code com.steven.assets.service.FundamentalAnalysisService.standaloneValue(int, BigDecimal, BigDecimal)}
 * 的<b>語意鏡像</b>（兩個 module 無法互相呼叫）。兩份複本走樣在本專案已有前例
 * （{@code expectedFinancialPeriodIndex}），故 {@code UsValuationDerivationServiceTest} 以相同輸入的
 * 契約表把兩者釘在一起，改動任一邊都必須同步。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UsValuationDerivationService {

    /** 落地 {@code market} 欄一律寫這個值（跨表 join key，填錯會讓下游 {@code WHERE market='美股'} 靜默回零筆）。 */
    static final String US_MARKET = "美股";
    /** 推導值的可用性基準，不是 {@code PUBLISHED}／{@code OBSERVED}。varchar(20) 內，13 字元。 */
    static final String AVAILABILITY_BASIS = "RECONSTRUCTED";
    /** {@code source_urls} 一定帶這個標記，讓下游一眼看出這列不是觀測到的公告值。 */
    static final String DERIVED_URL_MARKER = "derived://sec-edgar-companyfacts/us-valuation";

    /** 單季推導股數相鄰比值達此倍數即視為每股基準變動點（判斷性取值，無回測依據）。 */
    static final BigDecimal BASIS_CHANGE_MIN_RATIO = new BigDecimal("1.8");
    /** 同一除息日兩個金額的比值在此以內視為「同一筆、兩個精度」（判斷性取值，無回測依據）。 */
    static final BigDecimal DIVIDEND_PRECISION_MAX_RATIO = new BigDecimal("1.01");
    /** 型態二判定對「該標的還原倍數眾數」的相對容差（判斷性取值，無回測依據）。 */
    static final BigDecimal SPLIT_RATIO_TOLERANCE = new BigDecimal("0.10");
    /** 還原倍數眾數至少要有這麼多個不同除息日支持，否則視為不可解（避免單票眾數自我驗證）。 */
    static final int MIN_SPLIT_RATIO_SUPPORT = 2;

    static final int DIVIDEND_LOOKBACK_DAYS = 365;
    /** 每輪一律重算最近這麼多個交易日（收盤價會被 18:00 ET 的 FinMind 校正事後覆寫）。 */
    static final int RECOMPUTE_TRADING_DAYS = 30;

    private static final ZoneId US_EXCHANGE_ZONE = ZoneId.of("America/New_York");
    private static final LocalTime US_CLOSE_TIME = LocalTime.of(16, 0);
    private static final int TTM_QUARTERS = 4;
    private static final int RATIO_SCALE = 8;
    /** 三個比率欄位在 schema 都是 {@code numeric(12,4)}；輸出就量化到 4 位，避免重跑被判成修正版。 */
    private static final int OUTPUT_SCALE = 4;
    /** {@code numeric(12,4)} 的整數位上限；超出會讓 INSERT 直接炸，寧可留 NULL 並記 DEBUG。 */
    private static final BigDecimal NUMERIC_12_4_LIMIT = new BigDecimal("100000000");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final FundamentalObservationStore store;
    private final StockSourceQuery stockSource;
    private final DividendHistoryQuery dividendHistory;

    /** 一輪推導的結果；{@code notes} 為被略過標的與原因，供排程收尾以 INFO 輸出。 */
    public record Summary(int codes, int written, int deleted, int skipped, List<String> notes) {}

    /**
     * <b>排程與開機自癒的唯一公開入口</b>（Task 334.4 步驟二會直接呼叫這一支）。
     *
     * <p>標的範圍資料驅動：{@code stock_financial_quarter} 中 {@code market='美股'} 且
     * {@code provider='SEC_EDGAR'} 的相異 {@code stock_code}，不硬編清單。</p>
     *
     * <p>寫入策略：預設只補「尚無 {@code SEC_DERIVED} 列」的交易日，但<b>一律重算最近
     * {@link #RECOMPUTE_TRADING_DAYS} 個交易日</b>（收盤價會被事後校正；值相同時 append-on-change
     * 本來就不會寫，成本只有計算）。另外，若本輪算出的「可用區段起點」<b>晚於</b>現有序列的
     * {@code min(trading_date)}，代表每股基準變動點往後移（通常是新的股票分割），整段刪除重寫並記 WARN。
     * <b>分割剛發生的那約四個季度</b>新基準湊不出四季視窗、本輪推導序列為空，起點比較永遠不會被執行，
     * 故那條路徑另外處理（見 {@link #deriveForCode(String)}）：只要閘門<b>真的偵測到基準變動</b>就先刪除，
     * 不等新序列成形。</p>
     *
     * <p>全程 fail-soft：單一標的失敗只記 WARN 並繼續下一檔，不擲例外到呼叫端。</p>
     */
    public Summary deriveAll() {
        List<String> codes = store.secEdgarUsStockCodes();
        List<String> notes = new ArrayList<>();
        int written = 0;
        int deleted = 0;
        int skipped = 0;
        for (String code : codes) {
            try {
                CodeResult result = deriveForCode(code);
                written += result.written();
                deleted += result.deleted();
                if (result.skippedReason() != null) {
                    skipped++;
                    notes.add(code + "：" + result.skippedReason());
                }
            } catch (Exception e) {
                skipped++;
                notes.add(code + "：推導失敗 " + e.getClass().getSimpleName() + " " + e.getMessage());
                log.warn("美股估值推導失敗 {}：{}", code, e.toString());
            }
        }
        log.info("美股估值推導完成：處理 {} 檔、新增 {} 列、刪除重寫 {} 列、略過 {} 檔{}",
                codes.size(), written, deleted, skipped, notes.isEmpty() ? "" : "（" + notes + "）");
        return new Summary(codes.size(), written, deleted, skipped, List.copyOf(notes));
    }

    /** 單一標的的推導結果；{@code skippedReason} 非 null 代表該檔本輪沒有可落地的序列。 */
    public record CodeResult(String stockCode, int written, int deleted, String skippedReason) {}

    /** 單一標的的推導與落地；供 {@link #deriveAll()} 與（需要時）針對性重跑使用。 */
    public CodeResult deriveForCode(String code) {
        List<FundamentalObservationStore.QuarterFact> quarters = store.secEdgarQuarters(code);
        List<StockSourceQuery.ClosePoint> closes = stockSource.loadAllCloses(code, US_MARKET);
        List<DividendHistoryQuery.CashDividendEvent> dividends =
                dividendHistory.activeCashDividends(code, US_MARKET);

        Derivation derivation = deriveDetailed(code, quarters, closes, dividends);
        List<StockFundamentalFetchClient.Valuation> series = derivation.rows();
        if (series.isEmpty()) {
            // 空序列有兩種完全相反的成因，不能一視同仁：
            // (i) 閘門真的偵測到每股基準變動（相鄰單季推導股數比值 ≥ BASIS_CHANGE_MIN_RATIO）。分割剛發生
            //     時新基準只有 1–3 季、湊不出四季視窗，若在這裡直接 return，已落地的整段舊基準序列會原地
            //     留存約四個季度——而它已經是混基準的（價格已還原、EPS 還沒），最新端 PE 被壓低 k 倍、
            //     分位逼近 0。這一段必須刪除，且不能等「新起點晚於舊起點」那條規則（它永遠到不了）。
            // (ii) 上游暫時缺季、或最新季 net_income_parent 為 null／非正。拿它當「基準變動」證據會在一次
            //     上游抖動就把整條歷史砍掉，故維持保守不刪。
            if (derivation.basisChangeDetected()) {
                int removed = store.deleteDerivedValuationSeries(code);
                log.warn("偵測到每股基準變動但新基準季數不足四季，先刪除 {} 的舊基準 SEC_DERIVED 序列（{} 列），"
                        + "待新基準累積滿四季後重寫", code, removed);
                return new CodeResult(code, 0, removed, "偵測到每股基準變動，新基準季數不足四季（已刪除舊基準序列）");
            }
            return new CodeResult(code, 0, 0, "無可用推導區段（季報不足、或每股基準不可驗證）");
        }

        LocalDate newStart = series.get(0).tradingDate();
        LocalDate existingStart = store.earliestDerivedValuationDate(code);
        int deleted = 0;
        Set<LocalDate> alreadyLanded;
        if (existingStart != null && newStart.isAfter(existingStart)) {
            deleted = store.deleteDerivedValuationSeries(code);
            log.warn("每股基準變動點後移，刪除並重寫 {} 的 SEC_DERIVED 序列：舊起點 {}、新起點 {}、刪除 {} 列",
                    code, existingStart, newStart, deleted);
            alreadyLanded = Set.of();
        } else {
            alreadyLanded = store.derivedValuationDates(code);
        }

        LocalDate recomputeFrom = series.get(Math.max(0, series.size() - RECOMPUTE_TRADING_DAYS)).tradingDate();
        List<StockFundamentalFetchClient.Valuation> pending = series.stream()
                .filter(row -> !alreadyLanded.contains(row.tradingDate())
                        || !row.tradingDate().isBefore(recomputeFrom))
                .toList();
        int written = store.append(
                new StockFundamentalFetchClient.Bundle(pending, List.of(), List.of())).valuations();
        return new CodeResult(code, written, deleted, null);
    }

    // ── 純計算（不碰 DB、不發 HTTP）：以下全部可單元測試 ─────────────────────────────

    /**
     * 一輪推導的完整結果。{@code basisChangeDetected} 是<b>閘門判斷</b>而非序列內容：即使
     * {@code rows} 為空（新基準季數不足四季），它仍可能為 {@code true}，那正是必須刪除舊基準序列的情形。
     */
    record Derivation(List<StockFundamentalFetchClient.Valuation> rows, boolean basisChangeDetected) {
        static final Derivation EMPTY = new Derivation(List.of(), false);
    }

    /**
     * 由季度事實、日收盤與現金股利推導出整段 {@code SEC_DERIVED} 序列，<b>由舊到新</b>。
     *
     * @param quarters  同一標的的 SEC EDGAR 季度事實（順序不拘，內部會去重排序）
     * @param closes    該標的美股日收盤，由舊到新（{@code close_price>0}）
     * @param dividends 該標的 ACTIVE 現金股利事件（順序不拘）
     */
    static List<StockFundamentalFetchClient.Valuation> derive(
            String stockCode,
            List<FundamentalObservationStore.QuarterFact> quarters,
            List<StockSourceQuery.ClosePoint> closes,
            List<DividendHistoryQuery.CashDividendEvent> dividends) {
        return deriveDetailed(stockCode, quarters, closes, dividends).rows();
    }

    /** {@link #derive} 的完整版：連同閘門是否偵測到每股基準變動一併回傳（{@link #deriveForCode} 需要）。 */
    static Derivation deriveDetailed(
            String stockCode,
            List<FundamentalObservationStore.QuarterFact> quarters,
            List<StockSourceQuery.ClosePoint> closes,
            List<DividendHistoryQuery.CashDividendEvent> dividends) {
        if (stockCode == null || stockCode.isBlank() || quarters == null || closes == null) return Derivation.EMPTY;

        Map<Integer, FundamentalObservationStore.QuarterFact> byPeriod = new LinkedHashMap<>();
        quarters.stream()
                .filter(q -> q != null && q.fiscalYear() > 0
                        && q.fiscalQuarter() >= 1 && q.fiscalQuarter() <= 4
                        && q.sourceAvailableAt() != null)
                .sorted(Comparator.<FundamentalObservationStore.QuarterFact>comparingInt(
                        UsValuationDerivationService::periodIndex).reversed())
                .forEach(q -> byPeriod.putIfAbsent(periodIndex(q), q));
        List<Integer> periodsDesc = List.copyOf(byPeriod.keySet());
        if (periodsDesc.size() < TTM_QUARTERS) return Derivation.EMPTY;

        Map<Integer, Instant> effectiveAvailableAt = effectiveAvailableAt(byPeriod, periodsDesc);
        Map<Integer, BigDecimal> standaloneEps = new HashMap<>();
        Map<Integer, BigDecimal> standaloneIncome = new HashMap<>();
        for (int period : periodsDesc) {
            FundamentalObservationStore.QuarterFact row = byPeriod.get(period);
            // period−1 在同一會計年度內恰為前一季；Q1 不查前季（standaloneValue 直接取累計）。
            FundamentalObservationStore.QuarterFact previous = byPeriod.get(period - 1);
            standaloneEps.put(period, standaloneValue(row.fiscalQuarter(), row.cumulativeEps(),
                    previous == null ? null : previous.cumulativeEps()));
            standaloneIncome.put(period, standaloneValue(row.fiscalQuarter(),
                    decimal(row.cumulativeNetIncomeParent()),
                    previous == null ? null : decimal(previous.cumulativeNetIncomeParent())));
        }

        UsableSegment segment = usableSegment(periodsDesc, standaloneEps, standaloneIncome);
        if (segment.floorPeriod() == null) return new Derivation(List.of(), segment.basisChangeDetected());
        int usableFloor = segment.floorPeriod();

        DividendSeries dividendSeries = resolveDividends(stockCode, dividends);

        // 輸出順序是對外契約（deriveForCode 以 index 0 判斷「可用區段起點」），不倚賴呼叫端的排序。
        List<StockSourceQuery.ClosePoint> ascending = closes.stream()
                .filter(p -> p != null && p.date() != null)
                .sorted(Comparator.comparing(StockSourceQuery.ClosePoint::date))
                .toList();

        List<StockFundamentalFetchClient.Valuation> out = new ArrayList<>();
        for (StockSourceQuery.ClosePoint point : ascending) {
            if (point.close() == null || point.close().signum() <= 0) continue;
            LocalDate day = point.date();
            Instant closeInstant = marketCloseInstant(day);

            List<Integer> visible = new ArrayList<>();
            for (int period : periodsDesc) {
                Instant available = effectiveAvailableAt.get(period);
                if (available != null && !available.isAfter(closeInstant)) visible.add(period);
            }
            if (visible.size() < TTM_QUARTERS) continue;
            List<Integer> window = visible.subList(0, TTM_QUARTERS);
            // periodsDesc 嚴格遞減且相異，故「最大 − 最小 == 3」等價於四季連續。
            if (window.get(0) - window.get(TTM_QUARTERS - 1) != TTM_QUARTERS - 1) continue;
            if (window.get(TTM_QUARTERS - 1) < usableFloor) continue;

            BigDecimal ttmEps = sum(window, standaloneEps);
            if (ttmEps == null) continue;

            out.add(buildRow(stockCode, day, point.close(),
                    ttmEps, sum(window, standaloneIncome),
                    byPeriod.get(window.get(0)).equityParent(),
                    dividendYieldPct(day, point.close(), dividendSeries),
                    effectiveAvailableAt.get(window.get(0)),
                    sourceUrls(byPeriod, window)));
        }
        return new Derivation(List.copyOf(out), segment.basisChangeDetected());
    }

    /**
     * 由一組已決定的 TTM 輸入組出一列落地值。
     *
     * <p>{@code pe_loss_flag} 的語意沿用既有欄位：有正 TTM EPS 時為 {@code FALSE}，TTM EPS ≤ 0 時為
     * {@code TRUE} 且 {@code pe_ratio} 為 {@code NULL} 但<b>該列仍須產出</b>——否則下游無法區分「虧損」
     * 與「未推導出」這兩種行為相反的情形。連 TTM 都算不出來時呼叫端不會走到這裡（該日不落列），
     * 所以不會出現 {@code null} flag 搭配 {@code null} PE 的模稜狀態。</p>
     *
     * <p>{@code source_available_at} 取「所用四季中最新那一季的 <b>effective_available_at</b>（單調化後）」
     * 與「D 當日美股收盤時刻」兩者的較晚者——收盤價本身也要到收盤才可得。<b>絕對不可改用 raw
     * {@code filed}</b>：MSFT 三個 Q4 的 raw filed 都是同一天，用它會讓整段推導列的可見時點塌成同一天，
     * 任何過去決策時點的 walk-forward／holdout 都會整組取不到這些列。</p>
     */
    static StockFundamentalFetchClient.Valuation buildRow(
            String stockCode,
            LocalDate day,
            BigDecimal close,
            BigDecimal ttmEps,
            BigDecimal ttmNetIncome,
            Long equityParent,
            BigDecimal dividendYieldPct,
            Instant newestEffectiveAvailableAt,
            List<String> sourceUrls) {
        boolean loss = ttmEps.signum() <= 0;
        BigDecimal pe = loss ? null : close.divide(ttmEps, OUTPUT_SCALE, RoundingMode.HALF_UP);
        BigDecimal pb = priceToBook(close, ttmEps, ttmNetIncome, equityParent);
        return new StockFundamentalFetchClient.Valuation(
                stockCode, US_MARKET, day,
                fitNumeric(stockCode, day, "pe_ratio", pe),
                fitNumeric(stockCode, day, "pb_ratio", pb),
                fitNumeric(stockCode, day, "dividend_yield_pct", dividendYieldPct),
                loss ? Boolean.TRUE : Boolean.FALSE,
                StockFundamentalFetchClient.SEC_DERIVED,
                sourceUrls,
                later(newestEffectiveAvailableAt, marketCloseInstant(day)),
                AVAILABILITY_BASIS);
    }

    /**
     * 年度累計值轉單季；Q2–Q4 缺前一季時必須是 {@code null}。
     *
     * <p><b>backend {@code FundamentalAnalysisService.standaloneValue(int, BigDecimal, BigDecimal)}
     * 的語意鏡像</b>——兩個 module 無法互相呼叫，只能以相同語意實作並用測試把兩份複本釘在一起。
     * 改這裡就要同步改那裡（反之亦然）。</p>
     */
    static BigDecimal standaloneValue(int quarter, BigDecimal cumulative, BigDecimal previousCumulative) {
        if (cumulative == null) return null;
        if (quarter == 1) return cumulative;
        if (quarter < 1 || quarter > 4 || previousCumulative == null) return null;
        return cumulative.subtract(previousCumulative);
    }

    /**
     * {@code effective_available_at(Q) = min{ source_available_at(Q') | Q' ≥ Q }}（單調化）。
     * 由新到舊掃描並保留 running min，故結果對期別單調不減。
     */
    static Map<Integer, Instant> effectiveAvailableAt(
            Map<Integer, FundamentalObservationStore.QuarterFact> byPeriod, List<Integer> periodsDesc) {
        Map<Integer, Instant> effective = new HashMap<>();
        Instant running = null;
        for (int period : periodsDesc) {
            Instant raw = byPeriod.get(period).sourceAvailableAt();
            running = (running == null || raw.isBefore(running)) ? raw : running;
            effective.put(period, running);
        }
        return effective;
    }

    /**
     * 每股基準閘門的結果。{@code floorPeriod} 為可用區段中最舊的期別 index（{@code null} 代表最新一季
     * 本身就不可驗證、整檔無可用區段）；{@code basisChangeDetected} 為 {@code true} 只在<b>相鄰兩季推導
     * 股數比值 ≥ {@link #BASIS_CHANGE_MIN_RATIO}</b> 時成立（＝真的看到每股基準變動，通常是股票分割），
     * 不含「推導股數不可得」那種中止。兩者刻意分開：前者決定要不要落列，後者決定要不要刪掉舊基準序列。
     */
    record UsableSegment(Integer floorPeriod, boolean basisChangeDetected) {}

    /**
     * 每股基準閘門：回傳<b>可用區段中最舊的期別 index</b>；最新一季本身就不可驗證時 {@code floorPeriod}
     * 為 {@code null}（代表整檔無可用區段）。
     *
     * <p>比值一律只在<b>兩季推導股數皆為正</b>時才計算；任一季不可得即視為變動點並就地中止
     * （fail closed，不是略過後繼續往更舊的季度延伸）。<b>兩個 break 的意義不同</b>：推導股數不可得是
     * 「基準不可驗證」（可能只是上游缺欄），比值超標才是「基準真的變了」，只有後者會設
     * {@code basisChangeDetected}。</p>
     */
    static UsableSegment usableSegment(
            List<Integer> periodsDesc,
            Map<Integer, BigDecimal> standaloneEps,
            Map<Integer, BigDecimal> standaloneIncome) {
        if (periodsDesc == null || periodsDesc.isEmpty()) return new UsableSegment(null, false);
        BigDecimal previousShares = derivedShares(
                standaloneEps.get(periodsDesc.get(0)), standaloneIncome.get(periodsDesc.get(0)));
        if (previousShares == null) return new UsableSegment(null, false);
        int floor = periodsDesc.get(0);
        for (int i = 1; i < periodsDesc.size(); i++) {
            int period = periodsDesc.get(i);
            BigDecimal shares = derivedShares(standaloneEps.get(period), standaloneIncome.get(period));
            if (shares == null) break;
            if (ratio(previousShares, shares).compareTo(BASIS_CHANGE_MIN_RATIO) >= 0) {
                return new UsableSegment(floor, true);
            }
            floor = period;
            previousShares = shares;
        }
        return new UsableSegment(floor, false);
    }

    /**
     * <b>單季</b>推導股數＝單季母公司淨利 ÷ 單季 EPS（同一份申報的分子分母）。
     * 任一者為 null 或不為正一律回 {@code null}（＝基準不可驗證）。
     */
    static BigDecimal derivedShares(BigDecimal standaloneEps, BigDecimal standaloneNetIncome) {
        if (standaloneEps == null || standaloneNetIncome == null) return null;
        if (standaloneEps.signum() <= 0 || standaloneNetIncome.signum() <= 0) return null;
        return standaloneNetIncome.divide(standaloneEps, RATIO_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 同一除息日多值的去重結果。{@code resolved} 為可判別的單一金額，{@code unresolved} 為
     * fail-closed 的除息日（該日落在任何交易日的 365 天視窗內時，該交易日的殖利率一律 {@code null}）。
     */
    record DividendSeries(
            NavigableMap<LocalDate, BigDecimal> resolved,
            NavigableSet<LocalDate> unresolved,
            LocalDate earliestExDate) {}

    /**
     * 現金股利去重（Task 334.3）。<b>直接加總會同時 double count 與混基準</b>，使分割前區間的殖利率
     * 偏高約一個數量級。
     *
     * <p><b>先試確定性判別欄位、再退回比值啟發式（規則 0）</b>：本任務動工時已對運行中資料庫實查
     * {@code event_key} 與 {@code source}——同一除息日的兩列 {@code event_key} 是<b>逐列不同的雜湊</b>
     * （由金額本身參與計算，故必然不同、無法用來配對），{@code source} 則<b>兩列完全相同</b>
     * （美股重複列一律為 {@code NASDAQ+Yahoo Finance}）。兩欄皆無法確定性區分，因此比值規則是實際採用的
     * 路徑而非 fallback。（{@code previous_close} 同樣不可用：實測非 null 的那一列帶的是<b>未還原</b>的
     * 金額配上<b>已還原</b>的價格，列內兩欄基準本身就不一致。）</p>
     *
     * <p>規則：以除息日分組，對同組相異 {@code cash_dividend} 依序判斷</p>
     * <ol>
     *   <li>最大／最小比值 ≤ {@link #DIVIDEND_PRECISION_MAX_RATIO} → 型態一（同一筆、兩個精度），
     *       取有效位數較多者、只計一次，<b>不 fail closed</b>（單純的來源精度差不該讓整日殖利率消失）。</li>
     *   <li>比值 ≥ {@link #BASIS_CHANGE_MIN_RATIO} → 型態二候選（同一筆、兩個每股基準）。取該標的全部
     *       「可解出的還原倍數」比值之<b>眾數 m</b>，{@code |ratio − m| / m ≤}
     *       {@link #SPLIT_RATIO_TOLERANCE} 才判為型態二、取較小值。<b>單看「取最小值」會出錯</b>：
     *       AVGO 僅有的一次分割是 10:1，但 2018-03-21／2018-06-19 兩列比值是 5，取最小值會採 0.35，
     *       是正確值 0.175 的兩倍且不會報錯——這正是要做鄰近一致檢查的原因。</li>
     *   <li>其餘一律 {@code null}（fail closed）並記 DEBUG。<b>不得</b>任選一列、不得直接加總、
     *       不得用 MAX／MIN 之類未說明理由的啟發式。</li>
     * </ol>
     *
     * <p><b>規則 3 會誤殺一種合法情形，這是刻意的取捨</b>：changeset
     * {@code v1.98.0-radar-dividend-same-day-amounts.sql} 註記同一除息日可以承載多筆不同金額的合法
     * 分配；本規則會把它一併判成不可解而寫 {@code null}（少算殖利率、偏保守），而不是冒險相加。</p>
     *
     * <p><b>眾數的取樣母體限定為「≥ {@link #BASIS_CHANGE_MIN_RATIO} 的比值」</b>：≤ 1.01 的比值在規則 1
     * 已被認定為同一金額的精度差、本質不是還原倍數，讓它進入眾數會讓精度雜訊主導「該標的的還原倍數
     * 基準」（例如 QQQ 全部 36 組重複都是精度差）。</p>
     */
    static DividendSeries resolveDividends(
            String stockCode, List<DividendHistoryQuery.CashDividendEvent> events) {
        TreeMap<LocalDate, BigDecimal> resolved = new TreeMap<>();
        TreeSet<LocalDate> unresolved = new TreeSet<>();
        TreeMap<LocalDate, TreeSet<BigDecimal>> byExDate = new TreeMap<>();
        for (DividendHistoryQuery.CashDividendEvent event : events == null ? List.<DividendHistoryQuery.CashDividendEvent>of() : events) {
            if (event == null || event.exDividendDate() == null || event.cashDividend() == null) continue;
            byExDate.computeIfAbsent(event.exDividendDate(), key -> new TreeSet<>()).add(event.cashDividend());
        }
        if (byExDate.isEmpty()) return new DividendSeries(resolved, unresolved, null);

        BigDecimal mode = splitRatioMode(byExDate);
        for (Map.Entry<LocalDate, TreeSet<BigDecimal>> entry : byExDate.entrySet()) {
            LocalDate exDate = entry.getKey();
            TreeSet<BigDecimal> values = entry.getValue();
            if (values.size() == 1) {
                resolved.put(exDate, values.first());
                continue;
            }
            BigDecimal ratio = distinctRatio(values);
            if (ratio == null) {
                unresolved.add(exDate);
                log.debug("股利去重失敗（比值不可解）{} {}：{}", stockCode, exDate, values);
                continue;
            }
            if (ratio.compareTo(DIVIDEND_PRECISION_MAX_RATIO) <= 0) {
                resolved.put(exDate, mostPreciseValue(values));
                continue;
            }
            if (ratio.compareTo(BASIS_CHANGE_MIN_RATIO) >= 0 && mode != null
                    && relativeDistance(ratio, mode).compareTo(SPLIT_RATIO_TOLERANCE) <= 0) {
                resolved.put(exDate, values.first());
                log.debug("股利去重判為每股基準重複 {} {}：比值 {}、眾數 {}、採 {}",
                        stockCode, exDate, ratio, mode, values.first());
                continue;
            }
            unresolved.add(exDate);
            log.debug("股利去重失敗（比值 {} 與眾數 {} 不一致）{} {}：{}",
                    ratio, mode, stockCode, exDate, values);
        }
        return new DividendSeries(resolved, unresolved, byExDate.firstKey());
    }

    /**
     * 近 365 日現金股利加總 ÷ 收盤 × 100。
     *
     * <p>D 當日之前查無任何除息紀錄 → {@code null}（<b>不得寫 0 冒充「不配息」</b>）；有紀錄但近 365 日
     * 無除息 → {@code 0}（代表已停配滿一年）；視窗內有任何無法去重的除息日 → {@code null}（fail closed）。</p>
     */
    static BigDecimal dividendYieldPct(LocalDate day, BigDecimal close, DividendSeries series) {
        if (series.earliestExDate() == null || series.earliestExDate().isAfter(day)) return null;
        LocalDate from = day.minusDays(DIVIDEND_LOOKBACK_DAYS);
        if (!series.unresolved().subSet(from, false, day, true).isEmpty()) return null;
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal amount : series.resolved().subMap(from, false, day, true).values()) {
            total = total.add(amount);
        }
        if (total.signum() < 0) return null;
        return total.multiply(HUNDRED).divide(close, OUTPUT_SCALE, RoundingMode.HALF_UP);
    }

    /** D 當日美股收盤時刻（16:00 America/New_York，夏冬令由 zone 自行處理）對應的 Instant。 */
    static Instant marketCloseInstant(LocalDate day) {
        return day.atTime(US_CLOSE_TIME).atZone(US_EXCHANGE_ZONE).toInstant();
    }

    private static BigDecimal priceToBook(
            BigDecimal close, BigDecimal ttmEps, BigDecimal ttmIncome, Long equityParent) {
        if (equityParent == null || ttmIncome == null || ttmEps.signum() == 0) return null;
        BigDecimal shares = ttmIncome.divide(ttmEps, RATIO_SCALE, RoundingMode.HALF_UP);
        if (shares.signum() <= 0) return null;
        // 美股 net_income_parent／equity_parent 單位是原始 USD，與 EPS 同來源，不做任何千元換算。
        BigDecimal bookValuePerShare = decimal(equityParent).divide(shares, RATIO_SCALE, RoundingMode.HALF_UP);
        if (bookValuePerShare.signum() <= 0) return null;
        return close.divide(bookValuePerShare, OUTPUT_SCALE, RoundingMode.HALF_UP);
    }

    private static List<String> sourceUrls(
            Map<Integer, FundamentalObservationStore.QuarterFact> byPeriod, List<Integer> window) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        for (int period : window) {
            for (String url : byPeriod.get(period).sourceUrls()) {
                if (url != null && !url.isBlank()) urls.add(url);
            }
        }
        urls.add(DERIVED_URL_MARKER);
        return List.copyOf(urls);
    }

    private static BigDecimal sum(List<Integer> window, Map<Integer, BigDecimal> values) {
        BigDecimal total = BigDecimal.ZERO;
        for (int period : window) {
            BigDecimal value = values.get(period);
            if (value == null) return null;
            total = total.add(value);
        }
        return total;
    }

    /**
     * 眾數母體只取 ≥ {@link #BASIS_CHANGE_MIN_RATIO} 的比值；同票數時取較大的比值（決定性）。
     *
     * <p><b>眾數必須由至少 {@value #MIN_SPLIT_RATIO_SUPPORT} 個不同除息日支持，否則回 {@code null}</b>：
     * 只有一票時眾數必然等於那一組自己的比值，{@code |ratio − mode| / mode} 恆為 0、必定通過，變成自我驗證
     * （fail open）——同日兩筆<b>合法但不同金額</b>的分配（例如常配 0.91 ＋ 同日特別配 3.00，比值 3.3）會被
     * 判成「每股基準重複」而只計較小者，殖利率少算特別配的部分且沒有任何告警。這與規則 3 白紙黑字的
     * fail-closed 取捨（「寧可缺值也不要混基準」）直接相反，故此處要求至少兩個獨立除息日佐證；不足時落到
     * 規則 3 寫 {@code null}。</p>
     */
    private static BigDecimal splitRatioMode(Map<LocalDate, TreeSet<BigDecimal>> byExDate) {
        Map<BigDecimal, Integer> counts = new TreeMap<>();
        for (TreeSet<BigDecimal> values : byExDate.values()) {
            BigDecimal ratio = distinctRatio(values);
            if (ratio == null || ratio.compareTo(BASIS_CHANGE_MIN_RATIO) < 0) continue;
            counts.merge(ratio.setScale(2, RoundingMode.HALF_UP), 1, Integer::sum);
        }
        BigDecimal mode = null;
        int best = 0;
        for (Map.Entry<BigDecimal, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > best
                    || (entry.getValue() == best && mode != null && entry.getKey().compareTo(mode) > 0)) {
                mode = entry.getKey();
                best = entry.getValue();
            }
        }
        return best >= MIN_SPLIT_RATIO_SUPPORT ? mode : null;
    }

    /** 同組相異金額的 max/min；不足兩個相異值或最小值非正時回 {@code null}。 */
    private static BigDecimal distinctRatio(TreeSet<BigDecimal> values) {
        if (values.size() < 2) return null;
        BigDecimal min = values.first();
        if (min.signum() <= 0) return null;
        return values.last().divide(min, RATIO_SCALE, RoundingMode.HALF_UP);
    }

    /** 「有效位數較多者」＝去掉尾端零後小數位數最多者；同位數時取較大值（決定性）。 */
    private static BigDecimal mostPreciseValue(TreeSet<BigDecimal> values) {
        BigDecimal best = null;
        for (BigDecimal value : values) {
            if (best == null) {
                best = value;
                continue;
            }
            int bestScale = best.stripTrailingZeros().scale();
            int scale = value.stripTrailingZeros().scale();
            if (scale > bestScale || (scale == bestScale && value.compareTo(best) > 0)) best = value;
        }
        return best;
    }

    private static BigDecimal relativeDistance(BigDecimal ratio, BigDecimal mode) {
        return ratio.subtract(mode).abs().divide(mode, RATIO_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal ratio(BigDecimal left, BigDecimal right) {
        return left.compareTo(right) >= 0
                ? left.divide(right, RATIO_SCALE, RoundingMode.HALF_UP)
                : right.divide(left, RATIO_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 三個比率欄位在 schema 都是 {@code numeric(12,4)}（整數位上限 8 位）。超界時留 {@code null} 並記
     * DEBUG，而不是讓 INSERT 直接擲 {@code DataIntegrityViolationException} 把整檔的推導中斷。
     */
    private static BigDecimal fitNumeric(String stockCode, LocalDate day, String column, BigDecimal value) {
        if (value == null) return null;
        if (value.abs().compareTo(NUMERIC_12_4_LIMIT) >= 0) {
            log.debug("推導值超出 numeric(12,4) 範圍，以 NULL 落地：{} {} {}={}", stockCode, day, column, value);
            return null;
        }
        return value;
    }

    private static Instant later(Instant left, Instant right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isAfter(right) ? left : right;
    }

    private static BigDecimal decimal(Long value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private static int periodIndex(FundamentalObservationStore.QuarterFact quarter) {
        return periodIndex(quarter.fiscalYear(), quarter.fiscalQuarter());
    }

    static int periodIndex(int fiscalYear, int fiscalQuarter) {
        return fiscalYear * 4 + fiscalQuarter;
    }
}
