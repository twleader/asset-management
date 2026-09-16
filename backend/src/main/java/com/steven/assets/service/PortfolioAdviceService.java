package com.steven.assets.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ToolUnion;
import com.anthropic.models.messages.WebSearchTool20260209;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.dto.CurrentAllocationDto;
import com.steven.assets.dto.InvestmentProfileDto;
import com.steven.assets.dto.InvestmentProfileInput;
import com.steven.assets.dto.PortfolioAdviceResult;
import com.steven.assets.dto.PortfolioAdviceSettingsDto;
import com.steven.assets.dto.RetirementProjectionDto;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.BankDeposit;
import com.steven.assets.model.DepositTypeEntity;
import com.steven.assets.model.FundHolding;
import com.steven.assets.model.InvestmentPlannedExpense;
import com.steven.assets.model.InvestmentProfile;
import com.steven.assets.model.FundClassOverride;
import com.steven.assets.model.PortfolioAdvice;
import com.steven.assets.model.PortfolioAdviceSetting;
import com.steven.assets.model.Stock;
import com.steven.assets.model.StockHolding;
import com.steven.assets.model.StockStyle;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.BankDepositRepository;
import com.steven.assets.repository.DepositTypeRepository;
import com.steven.assets.repository.FundClassOverrideRepository;
import com.steven.assets.repository.FundHoldingRepository;
import com.steven.assets.repository.InvestmentPlannedExpenseRepository;
import com.steven.assets.repository.InvestmentProfileRepository;
import com.steven.assets.repository.PortfolioAdviceRepository;
import com.steven.assets.repository.PortfolioAdviceSettingRepository;
import com.steven.assets.repository.StockHoldingRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.repository.StockStyleRepository;
import com.steven.assets.security.TenantGuard;
import com.steven.assets.security.UnauthenticatedException;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 資產配置建議（Requirement 32）。
 *
 * <p>使用者先在頁面填理財條件（生日／退休前年薪與年支出／退休日期／理財目標／風險承受度／獲利預期／退休後現金流與試算假設，存於
 * {@code investment_profile}），再按「產生建議」：本服務讀該使用者最新 {@code asset_snapshot} 的現況配置與持有明細，
 * 結合理財條件產出結構化建議並保存歷次結果。LOCAL 在 request 內同步落終態；HYBRID 與 LLM
 * 先保存 {@code PROCESSING}，再將工作交給背景執行緒池，前端輪詢結果。
 *
 * <p>request 執行緒先依 owner context 讀取資料並組好 prompt；背景只執行 Claude 呼叫並以
 * 捕捉的 {@code adviceId} 更新結果，不查 owner-scoped 資料，也不自動繼承 request tenant context。HYBRID 與 LLM 的 Messages API 呼叫
 * 在背景執行，金鑰未設定或呼叫／解析失敗落 {@code NOT_CONFIGURED}／{@code FAILED}。
 *
 * <p><b>Requirement 80 / Task 339 起有三條路徑</b>，由 {@code portfolio_advice_setting.engine} 決定：
 * <ul>
 *   <li>{@link #ENGINE_LOCAL}（預設）：全部欄位由 {@link LocalPortfolioAllocationEngine} 的配置模板產生，
 *       <b>不建 {@code AnthropicClient}、不發任何外部請求、不檢查金鑰</b>，同步落終態（不經 {@code PROCESSING}）。</li>
 *   <li>{@link #ENGINE_HYBRID}：所有數字仍由本機決定，LLM 只把結果改寫成 {@code summary} 與
 *       {@code riskAssessment} 兩段文字；<b>強制停用 {@code web_search}</b>、{@code references} 固定空陣列，
 *       立即回 {@code PROCESSING}，由背景工作完成。</li>
 *   <li>{@link #ENGINE_LLM}：立即回 {@code PROCESSING}，背景執行完整路徑（adaptive thinking ＋ effort ＋可選 {@code web_search} ＋ references 淨化）。</li>
 * </ul>
 * 三檔位共用同一組狀態常數與同一個 {@link #latest()} 自癒邏輯，也共用同一支
 * {@link #enrich(PortfolioAdviceResult, BigDecimal)} 做金額算術——不得為本機檔位另寫一份。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortfolioAdviceService {

    private static final int MAX_TOKENS = 16000;
    private static final int HISTORY_MAX = 100;
    /** PROCESSING 逾時：送出超過此時間仍未完成（背景執行緒異常／服務重啟中斷）→ 讀取時自癒判 FAILED，避免卡住轉圈。 */
    private static final Duration PROCESSING_STALE = Duration.ofMinutes(10);
    /** 提示詞內每類持有明細最多列出的檔數（個人資產通常遠少於此，僅防極端過長）。 */
    private static final int MAX_HOLDINGS_PER_CLASS = 40;

    // ===== 成本控管白名單（技術白名單，非業務分類，不入 /api/settings；比照 MarketAnalysisService）=====

    private static final List<PortfolioAdviceSettingsDto.ModelOption> AVAILABLE_MODELS = List.of(
            new PortfolioAdviceSettingsDto.ModelOption("claude-fable-5", "Fable 5（最佳品質）"),
            new PortfolioAdviceSettingsDto.ModelOption("claude-opus-5", "Opus 5（居中）"),
            new PortfolioAdviceSettingsDto.ModelOption("claude-sonnet-5", "Sonnet 5（最省）")
    );

    private static final List<PortfolioAdviceSettingsDto.EffortOption> AVAILABLE_EFFORTS = List.of(
            new PortfolioAdviceSettingsDto.EffortOption("low", "Low（最省、較快）"),
            new PortfolioAdviceSettingsDto.EffortOption("medium", "Medium（平衡，建議）"),
            new PortfolioAdviceSettingsDto.EffortOption("high", "High（最深入、最貴）")
    );

    private static final String DEFAULT_EFFORT = "medium";

    private static final List<PortfolioAdviceSettingsDto.WebSearchOption> AVAILABLE_WEB_SEARCHES = List.of(
            new PortfolioAdviceSettingsDto.WebSearchOption(0, "關閉（僅依個人資產與條件，最省）"),
            new PortfolioAdviceSettingsDto.WebSearchOption(3, "3 次（精簡）"),
            new PortfolioAdviceSettingsDto.WebSearchOption(4, "4 次（平衡，預設）"),
            new PortfolioAdviceSettingsDto.WebSearchOption(6, "6 次（完整）")
    );

    private static final int DEFAULT_WEB_SEARCH = 4;

    // ===== 分析引擎（Requirement 80 / Task 339）=====

    /** 完全本機：零 API 呼叫、同步落終態。 */
    public static final String ENGINE_LOCAL = "local";
    /** 部分打 API：數字全本機，LLM 只寫 summary／riskAssessment 兩段文字，強制不搜尋。 */
    public static final String ENGINE_HYBRID = "hybrid";
    /** 現行完整版：既有 {@link #runGeneration} 路徑（含 web_search 與 references 淨化）。 */
    public static final String ENGINE_LLM = "llm";

    /**
     * 可選分析引擎白名單——技術白名單（非使用者可自訂之業務分類），比照 {@link #AVAILABLE_MODELS}／
     * {@link #AVAILABLE_EFFORTS}／{@link #AVAILABLE_WEB_SEARCHES}，不套用「Enum 必須入庫由
     * {@code /api/settings} 管理」規範。
     */
    private static final List<PortfolioAdviceSettingsDto.EngineOption> AVAILABLE_ENGINES = List.of(
            new PortfolioAdviceSettingsDto.EngineOption(ENGINE_LOCAL, "完全本機（免費）"),
            new PortfolioAdviceSettingsDto.EngineOption(ENGINE_HYBRID, "本機計算 ＋ AI 撰寫敘述（省錢）"),
            new PortfolioAdviceSettingsDto.EngineOption(ENGINE_LLM, "完整 AI 分析（含網路搜尋，最貴）")
    );

    /** 設定表未設或後備時使用的分析引擎。local＝零 API 成本（Task 339 起的預設）。 */
    private static final String DEFAULT_ENGINE = ENGINE_LOCAL;

    /**
     * hybrid 檔位寫入 {@code portfolio_advice.model} 的前綴，後接實際 Claude model id
     * （例：{@code hybrid-allocation:v1+claude-fable-5}）。欄位長度 varchar(64)，仍過既有 truncate。
     * <b>配置模板調整時須連同 {@link LocalPortfolioAllocationEngine#ENGINE_VERSION} 一起提升版本號。</b>
     */
    public static final String HYBRID_MODEL_PREFIX = "hybrid-allocation:v1+";

    /**
     * hybrid 檔位的 maxTokens：本檔位只產 {@code summary} 與 {@code riskAssessment} 兩段文字
     * （數字與清單都已由本機算好、不由模型輸出），故顯著低於完整版的 {@link #MAX_TOKENS}。
     */
    private static final int HYBRID_MAX_TOKENS = 4000;

    /** 假設年通膨率預設值（%）：使用者未填時，大筆花費「今日幣值 → 未來名目值」以此換算（台灣長期 CPI 目標約 2%）。 */
    private static final BigDecimal DEFAULT_INFLATION_RATE = new BigDecimal("2");

    // ===== 表單詞彙白名單（理財目標／風險／獲利預期；本頁表單選項，非跨域分類）=====

    private static final List<InvestmentProfileDto.Option> GOAL_OPTIONS = List.of(
            new InvestmentProfileDto.Option("RETIREMENT", "退休準備"),
            new InvestmentProfileDto.Option("WEALTH_GROWTH", "資產增值"),
            new InvestmentProfileDto.Option("PASSIVE_INCOME", "被動收入（存股／領息）"),
            new InvestmentProfileDto.Option("CHILD_EDUCATION", "子女教育金"),
            new InvestmentProfileDto.Option("HOME_PURCHASE", "購屋置產"),
            new InvestmentProfileDto.Option("SHORT_TERM", "短期資金週轉"),
            new InvestmentProfileDto.Option("WEALTH_PRESERVATION", "財富保值／傳承")
    );

    private static final List<InvestmentProfileDto.Option> RISK_OPTIONS = List.of(
            new InvestmentProfileDto.Option("CONSERVATIVE", "保守（不能忍受本金明顯虧損）"),
            new InvestmentProfileDto.Option("BALANCED", "穩健（可承受中度波動）"),
            new InvestmentProfileDto.Option("AGGRESSIVE", "積極（可承受較大波動追求高報酬）")
    );

    private static final List<InvestmentProfileDto.Option> RETURN_OPTIONS = List.of(
            new InvestmentProfileDto.Option("LT3", "保守：年化報酬 < 3%"),
            new InvestmentProfileDto.Option("R3_6", "中低：年化報酬 3–6%"),
            new InvestmentProfileDto.Option("R6_10", "中高：年化報酬 6–10%"),
            new InvestmentProfileDto.Option("GT10", "積極：年化報酬 > 10%")
    );

    private final InvestmentProfileRepository profileRepo;
    private final InvestmentPlannedExpenseRepository expenseRepo;
    private final PortfolioAdviceRepository adviceRepo;
    private final PortfolioAdviceSettingRepository settingRepo;
    private final AssetSnapshotRepository snapshotRepo;
    private final BankDepositRepository depositRepo;
    /**
     * 提領優先序（{@code deposit_type.withdrawal_order}）的來源，供存款減碼 waterfall 用（Task 344.4）。
     * <b>禁止 Enum 寫死</b>：存款類型是使用者可自行新增的業務分類，判準必須入庫。
     */
    private final DepositTypeRepository depositTypeRepo;
    private final FundHoldingRepository fundRepo;
    private final StockHoldingRepository stockRepo;
    private final RetirementProjectionService projectionService;
    private final ObjectMapper objectMapper;
    private final TenantGuard tenantGuard;
    /**
     * 現況子分類（Requirement 82 / Task 341）——與 {@code AssetService.getHoldingsClassified()} 共用同一組
     * {@link AssetClassifier} 呼叫，不另寫一套分類邏輯。皆為既有 Spring bean，非新建。
     */
    private final AssetClassifier assetClassifier;
    private final StockRepository stockMasterRepo;
    private final FundClassOverrideRepository fundClassOverrideRepo;
    private final StockStyleRepository stockStyleRepo;
    /** 本機配置模板引擎（Task 339）：{@code local} 與 {@code hybrid} 兩檔位的目標比例來源。 */
    private final LocalPortfolioAllocationEngine localEngine;

    @Value("${anthropic.api-key:}")
    private String apiKey;

    @Value("${anthropic.model:claude-opus-5}")
    private String defaultModel;

    private volatile AnthropicClient anthropicClient;

    /**
     * 產生建議的背景執行緒池（非同步）：/generate 送出後立即回 PROCESSING、由此池跑 Claude 呼叫（數十秒），
     * 前端輪詢至 OK/FAILED。避免同步長連線撞 nginx/proxy 逾時（見 Requirement 32 設計）。daemon、小型固定池。
     */
    private final ExecutorService generationExecutor = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "portfolio-advice-gen");
        t.setDaemon(true);
        return t;
    });

    // ===== Profile（理財條件）=====

    /** 目前使用者的理財條件（含各欄位可選清單與大筆花費清單供表單 render）；無則回空 profile（僅帶清單）。 */
    public InvestmentProfileDto getProfile() {
        Long ownerId = tenantGuard.requireCurrentUserId();
        InvestmentProfile p = ownerId == null ? null : profileRepo.findByOwnerUserId(ownerId).orElse(null);
        List<InvestmentPlannedExpense> expenses = ownerId == null
                ? List.of() : expenseRepo.findByOwnerUserIdOrderByExpenseDate(ownerId);
        return InvestmentProfileDto.from(p, expenses, GOAL_OPTIONS, RISK_OPTIONS, RETURN_OPTIONS);
    }

    /**
     * upsert 目前使用者的理財條件（白名單驗證；無效 goals 略過），回更新後 DTO。
     * 大筆花費清單以「先刪 owner 全部再插入提交清單」replace（清單小、簡單穩健）；整段以 @Transactional 保原子性。
     */
    @Transactional
    public InvestmentProfileDto saveProfile(InvestmentProfileInput in) {
        Long ownerId = tenantGuard.requireCurrentUserId();
        if (ownerId == null) {
            throw new UnauthenticatedException("無使用者情境，無法儲存理財條件");
        }
        validateRisk(in.riskTolerance());
        validateReturn(in.expectedAnnualReturn());
        InvestmentProfile p = profileRepo.findByOwnerUserId(ownerId).orElseGet(InvestmentProfile::new);
        p.setOwnerUserId(ownerId);
        p.setBirthDate(in.birthDate());
        p.setPreRetirementAnnualSalary(in.preRetirementAnnualSalary());
        p.setPreRetirementAnnualExpense(in.preRetirementAnnualExpense());
        p.setRetirementDate(in.retirementDate());
        p.setLaborInsuranceMonthly(in.laborInsuranceMonthly());
        p.setLaborInsuranceStartDate(in.laborInsuranceStartDate());
        p.setLaborPensionLumpSum(in.laborPensionLumpSum());
        p.setLaborPensionClaimDate(in.laborPensionClaimDate());
        p.setAssumedAnnualInflationRate(in.assumedAnnualInflationRate());
        p.setRetirementAnnualExpense(in.retirementAnnualExpense());
        p.setLongTermCareAnnualExpense(in.longTermCareAnnualExpense());
        p.setLongTermCareStartAge(in.longTermCareStartAge());
        p.setAccumulationAnnualReturnRate(in.accumulationAnnualReturnRate());
        p.setRetirementAnnualReturnRate(in.retirementAnnualReturnRate());
        p.setGoals(joinGoals(in.goals()));
        p.setRiskTolerance(blankToNull(in.riskTolerance()));
        p.setExpectedAnnualReturn(blankToNull(in.expectedAnnualReturn()));
        p.setUpdatedAt(Instant.now());
        InvestmentProfile saved = profileRepo.save(p);

        List<InvestmentPlannedExpense> expenses = replaceExpenses(ownerId, in.plannedExpenses());
        return InvestmentProfileDto.from(saved, expenses, GOAL_OPTIONS, RISK_OPTIONS, RETURN_OPTIONS);
    }

    /** 以提交清單覆寫某使用者的大筆花費（先刪全部再插入有效列：需有日期與金額）。回排序後結果。 */
    private List<InvestmentPlannedExpense> replaceExpenses(Long ownerId, List<InvestmentProfileInput.PlannedExpenseInput> items) {
        expenseRepo.deleteByOwnerUserId(ownerId);
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        Instant now = Instant.now();
        for (InvestmentProfileInput.PlannedExpenseInput it : items) {
            if (it == null || it.expenseDate() == null || it.amount() == null) {
                continue; // 略過不完整列（缺日期或金額）
            }
            InvestmentPlannedExpense e = new InvestmentPlannedExpense();
            e.setOwnerUserId(ownerId);
            e.setExpenseDate(it.expenseDate());
            e.setName(blankToNull(it.name()));
            e.setAmount(it.amount());
            e.setUpdatedAt(now);
            expenseRepo.save(e);
        }
        return expenseRepo.findByOwnerUserIdOrderByExpenseDate(ownerId);
    }

    // ===== 現況配置 =====

    /**
     * 目前使用者最新快照的資產配置概覽（存款／基金／股票占比，Requirement 82 起「信託基金」「股票」
     * 兩桶另附成長型／收益型（高股息）／短期債／中期債／長期債子分類）；無快照回 empty。
     *
     * <p>子分類邏輯完全比照既有 {@code AssetService.getHoldingsClassified()}（同一組 {@link AssetClassifier}
     * 呼叫、同一套 override 查表模式，不另開一套分類邏輯分岔）。頂層三類的金額／占比語意不變
     * （Requirement 80 既有保證）。</p>
     */
    @Transactional(readOnly = true)
    public CurrentAllocationDto getCurrentAllocation() {
        return allocationContext().dto();
    }

    /**
     * {@link #getCurrentAllocation()} 的計算素材（Task 344.20）：除了對外的 {@link CurrentAllocationDto}，
     * 另帶出建 DTO 時<b>順手查好的</b> {@code stockNameMap}、四份 override Map 與 {@code incomeThreshold}。
     *
     * <p>存在的理由：{@code buildLocalResult} 要把差額攤到逐筆持有時，需要對同一批股票／基金做<b>完全相同的</b>
     * 子類別分類與顯示名稱查表。這些原本全是 {@code getCurrentAllocation()} 的區域變數、
     * 而 {@code CurrentAllocationDto} 不含其中任何一份；在「不得改 public 簽章／DTO 形狀」的前提下，
     * 不抽這一層就只剩「再 {@code findAll()} 一次」或「複製那 15 行」兩條路——後者正是
     * 「兩套分類會漂移」要避免的情形（「② 我目前的資產配置」與「再平衡明細」對同一檔股票歸到不同子類別）。</p>
     */
    private record AllocationContext(
            CurrentAllocationDto dto,
            Map<String, String> stockNameMap,
            Map<String, String> stockClassOverride,
            Map<String, String> stockStyleOverride,
            Map<String, String> bondTermOverride,
            Map<String, FundClassOverride> fundOverride,
            BigDecimal incomeThreshold) {}

    /** {@link #getCurrentAllocation()} 的本體；public 方法只是委派並回傳其中的 DTO（對外契約完全不變）。 */
    private AllocationContext allocationContext() {
        AssetSnapshot s = snapshotRepo.findLatest().orElse(null);
        if (s == null) {
            return new AllocationContext(CurrentAllocationDto.empty(),
                    Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), null);
        }
        BigDecimal deposit = nz(s.getTotalDeposit());
        BigDecimal fund = nz(s.getTotalFundValue());
        BigDecimal stock = nz(s.getTotalStockValue());
        BigDecimal total = s.getTotalAssets() != null ? s.getTotalAssets() : deposit.add(fund).add(stock);

        // 一次查表建 Map（比照 AssetService.getHoldingsClassified 既有模式，不逐筆查 DB）
        Map<String, String> stockClassOverride = new HashMap<>();
        Map<String, String> stockStyleOverride = new HashMap<>();
        Map<String, String> bondTermOverride = new HashMap<>();
        Map<String, String> stockNameMap = new HashMap<>();
        for (Stock sm : stockMasterRepo.findAll()) {
            String key = sm.getMarket() + "|" + sm.getCode();
            stockNameMap.put(key, sm.getName());
            if (sm.getAssetClass() != null && !sm.getAssetClass().isBlank()) stockClassOverride.put(key, sm.getAssetClass());
            if (sm.getStockStyle() != null && !sm.getStockStyle().isBlank()) stockStyleOverride.put(key, sm.getStockStyle());
            if (sm.getBondTerm() != null && !sm.getBondTerm().isBlank()) bondTermOverride.put(key, sm.getBondTerm());
        }
        Map<String, FundClassOverride> fundOverride = new HashMap<>();
        for (FundClassOverride fo : fundClassOverrideRepo.findAll()) {
            fundOverride.put(fo.getFundName(), fo);
        }
        BigDecimal incomeThreshold = stockStyleRepo.findByCode(AssetClassifier.INCOME)
                .map(StockStyle::getDividendThreshold).orElse(null);

        // 股票桶：逐筆分類累加
        Map<String, BigDecimal> stockSub = newSubMap();
        for (StockHolding st : s.getStocks()) {
            BigDecimal val = st.getCurrentValue() != null ? st.getCurrentValue() : BigDecimal.ZERO;
            String key = st.getMarket() + "|" + st.getStockCode();
            String cls = assetClassifier.classifyStock(st.getStockCode(), st.getMarket(), stockClassOverride.get(key));
            if (AssetClassifier.BOND.equals(cls)) {
                String term = assetClassifier.classifyBondTerm(st.getStockCode(), st.getMarket(),
                        stockNameMap.get(key), bondTermOverride.get(key));
                addToSub(stockSub, term, val);
            } else {
                String style = assetClassifier.classifyStockStyle(st.getStockCode(), st.getMarket(),
                        stockStyleOverride.get(key), st.getDividendRate(), incomeThreshold);
                addToSub(stockSub, style, val);
            }
        }
        // 信託基金桶：逐筆分類累加
        Map<String, BigDecimal> fundSub = newSubMap();
        for (FundHolding fh : s.getFunds()) {
            BigDecimal val = fh.getCurrentValue() != null ? fh.getCurrentValue() : BigDecimal.ZERO;
            String fname = fh.getFundName();
            FundClassOverride ov = fundOverride.get(fname);
            String cls = assetClassifier.classifyFund(fname, ov != null ? ov.getAssetClass() : null);
            if (AssetClassifier.BOND.equals(cls)) {
                String term = assetClassifier.classifyBondTerm(null, null, fname, ov != null ? ov.getBondTerm() : null);
                addToSub(fundSub, term, val);
            } else {
                String style = assetClassifier.classifyStockStyle(null, null,
                        ov != null ? ov.getStockStyle() : null, null, incomeThreshold);
                addToSub(fundSub, style, val);
            }
        }

        List<CurrentAllocationDto.Item> items = new ArrayList<>();
        items.add(new CurrentAllocationDto.Item("存款（現金）", deposit, pct(deposit, total), List.of()));
        items.add(new CurrentAllocationDto.Item("信託基金", fund, pct(fund, total), toSubItems(fundSub, fund)));
        items.add(new CurrentAllocationDto.Item("股票", stock, pct(stock, total), toSubItems(stockSub, stock)));
        return new AllocationContext(
                new CurrentAllocationDto(s.getId(), s.getSnapshotDate(), total, items),
                stockNameMap, stockClassOverride, stockStyleOverride, bondTermOverride, fundOverride, incomeThreshold);
    }

    /** 五個子類別的累加容器，鍵一律 {@link LocalPortfolioAllocationEngine} 的 {@code SUBCLASS_*} 常數。 */
    private static Map<String, BigDecimal> newSubMap() {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        m.put(LocalPortfolioAllocationEngine.SUBCLASS_GROWTH, BigDecimal.ZERO);
        m.put(LocalPortfolioAllocationEngine.SUBCLASS_INCOME, BigDecimal.ZERO);
        m.put(LocalPortfolioAllocationEngine.SUBCLASS_BOND_SHORT, BigDecimal.ZERO);
        m.put(LocalPortfolioAllocationEngine.SUBCLASS_BOND_MID, BigDecimal.ZERO);
        m.put(LocalPortfolioAllocationEngine.SUBCLASS_BOND_LONG, BigDecimal.ZERO);
        return m;
    }

    /** {@link AssetClassifier} 的分類代碼（GROWTH/INCOME/SHORT/MID/LONG）→ 中文子類別名稱後累加金額。 */
    private static void addToSub(Map<String, BigDecimal> m, String classifierCode, BigDecimal val) {
        m.merge(subClassLabel(classifierCode), val, BigDecimal::add);
    }

    /**
     * {@link AssetClassifier} 的分類代碼 → 中文子類別名稱。
     * Task 344 的逐筆持有明細與「② 我目前的資產配置」共用這一支，<b>不得寫第二套對照</b>
     * ——兩套一定會漂移，同一檔股票會在兩個區塊被歸到不同子類別。
     */
    private static String subClassLabel(String classifierCode) {
        return switch (classifierCode) {
            case AssetClassifier.GROWTH -> LocalPortfolioAllocationEngine.SUBCLASS_GROWTH;
            case AssetClassifier.INCOME -> LocalPortfolioAllocationEngine.SUBCLASS_INCOME;
            case AssetClassifier.SHORT -> LocalPortfolioAllocationEngine.SUBCLASS_BOND_SHORT;
            case AssetClassifier.MID -> LocalPortfolioAllocationEngine.SUBCLASS_BOND_MID;
            case AssetClassifier.LONG -> LocalPortfolioAllocationEngine.SUBCLASS_BOND_LONG;
            default -> LocalPortfolioAllocationEngine.SUBCLASS_GROWTH; // 理論不可達，classifyStockStyle/classifyBondTerm 值域固定
        };
    }

    /** 累加結果 → 只保留金額 &gt; 0 的子類別 SubItem 清單；{@code pct} 為占「所屬頂層桶」的占比，非占資產總額。 */
    private static List<CurrentAllocationDto.SubItem> toSubItems(Map<String, BigDecimal> sub, BigDecimal bucketTotal) {
        List<CurrentAllocationDto.SubItem> result = new ArrayList<>();
        for (Map.Entry<String, BigDecimal> e : sub.entrySet()) {
            if (e.getValue().signum() > 0) {
                result.add(new CurrentAllocationDto.SubItem(e.getKey(), e.getValue(), pct(e.getValue(), bucketTotal)));
            }
        }
        return result;
    }

    // ===== 退休現金流試算 =====

    /**
     * 目前使用者的退休現金流逐年試算（Requirement 32 / Task 165）。
     * 讀該使用者最新快照的資產總額為起點，結合其理財條件與大筆花費，交 {@link RetirementProjectionService} 決定性試算。
     * owner context 由 request 執行緒綁定（{@code @Filter} 生效），僅取到自己的 profile／快照／花費。
     */
    public RetirementProjectionDto getProjection() {
        Long ownerId = tenantGuard.requireCurrentUserId();
        if (ownerId == null) {
            return RetirementProjectionDto.unavailable("無使用者情境。", null, null);
        }
        InvestmentProfile p = profileRepo.findByOwnerUserId(ownerId).orElse(null);
        List<InvestmentPlannedExpense> expenses = expenseRepo.findByOwnerUserIdOrderByExpenseDate(ownerId);
        AssetSnapshot snapshot = snapshotRepo.findLatest().orElse(null);
        BigDecimal startAssets = snapshot == null ? null
                : (snapshot.getTotalAssets() != null ? snapshot.getTotalAssets()
                : nz(snapshot.getTotalDeposit()).add(nz(snapshot.getTotalFundValue())).add(nz(snapshot.getTotalStockValue())));
        if (p == null) {
            return RetirementProjectionDto.unavailable("請先填理財條件（至少生日）。", null, startAssets);
        }
        return projectionService.project(p, startAssets, expenses);
    }

    // ===== 建議查詢 =====

    public PortfolioAdvice latest() {
        Long ownerId = tenantGuard.requireCurrentUserId();
        if (ownerId == null) {
            return null;
        }
        PortfolioAdvice row = adviceRepo.findFirstByOwnerUserIdOrderByCreatedAtDesc(ownerId).orElse(null);
        // 自癒：PROCESSING 卡超過上限（背景執行緒中斷／服務重啟）→ 判 FAILED，避免前端無限輪詢
        if (row != null && PortfolioAdvice.STATUS_PROCESSING.equals(row.getStatus())
                && row.getCreatedAt() != null && row.getCreatedAt().isBefore(Instant.now().minus(PROCESSING_STALE))) {
            row.setStatus(PortfolioAdvice.STATUS_FAILED);
            row.setCompletedAt(Instant.now());
            row.setErrorMessage("產生逾時（可能因服務重啟中斷），請重新產生");
            row = save(row);
        }
        return row;
    }

    public List<PortfolioAdvice> history(int limit) {
        Long ownerId = tenantGuard.requireCurrentUserId();
        if (ownerId == null) {
            return List.of();
        }
        int n = Math.max(1, Math.min(limit, HISTORY_MAX));
        return adviceRepo.findRecentByOwner(ownerId, PageRequest.of(0, n));
    }

    // ===== 設定（模型／思考深度／web 搜尋）=====

    public String resolveModel() {
        return settingRepo.findById(PortfolioAdviceSetting.SINGLETON_ID)
                .map(PortfolioAdviceSetting::getModel)
                .filter(m -> m != null && !m.isBlank())
                .orElse(defaultModel);
    }

    public String resolveEffort() {
        return settingRepo.findById(PortfolioAdviceSetting.SINGLETON_ID)
                .map(PortfolioAdviceSetting::getEffort)
                .filter(e -> e != null && AVAILABLE_EFFORTS.stream().anyMatch(o -> o.id().equals(e)))
                .orElse(DEFAULT_EFFORT);
    }

    /**
     * 解析要用的分析引擎：設定表 engine（白名單內）→ 否則 {@link #DEFAULT_ENGINE}（{@code local}）。
     * 三段式寫法比照 {@link #resolveEffort()}（讀單列設定 → 白名單過濾 → 後備常數），
     * <b>不比照 {@link #resolveModel()}</b>——後者只驗非空、沒有白名單。
     */
    public String resolveEngine() {
        return settingRepo.findById(PortfolioAdviceSetting.SINGLETON_ID)
                .map(PortfolioAdviceSetting::getEngine)
                .filter(e -> e != null && AVAILABLE_ENGINES.stream().anyMatch(o -> o.id().equals(e)))
                .orElse(DEFAULT_ENGINE);
    }

    public int resolveWebSearchMaxUses() {
        return settingRepo.findById(PortfolioAdviceSetting.SINGLETON_ID)
                .map(PortfolioAdviceSetting::getWebSearchMaxUses)
                .filter(v -> v != null && AVAILABLE_WEB_SEARCHES.stream().anyMatch(o -> o.value().equals(v)))
                .orElse(DEFAULT_WEB_SEARCH);
    }

    public PortfolioAdviceSettingsDto getSettings() {
        String currentEngine = resolveEngine();
        String currentModel = resolveModel();
        String currentEffort = resolveEffort();
        int currentWebSearch = resolveWebSearchMaxUses();

        List<PortfolioAdviceSettingsDto.ModelOption> models = new ArrayList<>(AVAILABLE_MODELS);
        if (models.stream().noneMatch(o -> o.id().equals(currentModel))) {
            models.add(0, new PortfolioAdviceSettingsDto.ModelOption(currentModel, currentModel));
        }
        List<PortfolioAdviceSettingsDto.EffortOption> efforts = new ArrayList<>(AVAILABLE_EFFORTS);
        if (efforts.stream().noneMatch(o -> o.id().equals(currentEffort))) {
            efforts.add(0, new PortfolioAdviceSettingsDto.EffortOption(currentEffort, currentEffort));
        }
        List<PortfolioAdviceSettingsDto.WebSearchOption> webSearches = new ArrayList<>(AVAILABLE_WEB_SEARCHES);
        if (webSearches.stream().noneMatch(o -> o.value().equals(currentWebSearch))) {
            webSearches.add(0, new PortfolioAdviceSettingsDto.WebSearchOption(currentWebSearch, currentWebSearch + " 次"));
        }
        List<PortfolioAdviceSettingsDto.EngineOption> engines = new ArrayList<>(AVAILABLE_ENGINES);
        if (engines.stream().noneMatch(o -> o.id().equals(currentEngine))) {
            engines.add(0, new PortfolioAdviceSettingsDto.EngineOption(currentEngine, currentEngine));
        }
        return new PortfolioAdviceSettingsDto(currentEngine, currentModel, currentEffort, currentWebSearch,
                models, efforts, webSearches, engines);
    }

    /**
     * 更新成本控管設定（引擎／模型／思考深度／web 搜尋次數限白名單）：null 表示該欄不變；至少須提供一項。
     */
    public PortfolioAdviceSettingsDto updateSettings(String model, String effort, Integer webSearchMaxUses,
                                                    String engine) {
        if (model == null && effort == null && webSearchMaxUses == null && engine == null) {
            throw new IllegalArgumentException("未提供任何可更新的設定（model / effort / webSearchMaxUses / engine）");
        }
        if (model != null && AVAILABLE_MODELS.stream().noneMatch(o -> o.id().equals(model))) {
            throw new IllegalArgumentException("不支援的分析模型：" + model);
        }
        if (effort != null && AVAILABLE_EFFORTS.stream().noneMatch(o -> o.id().equals(effort))) {
            throw new IllegalArgumentException("不支援的思考深度：" + effort);
        }
        if (webSearchMaxUses != null && AVAILABLE_WEB_SEARCHES.stream().noneMatch(o -> o.value().equals(webSearchMaxUses))) {
            throw new IllegalArgumentException("不支援的 web 搜尋次數：" + webSearchMaxUses);
        }
        if (engine != null && AVAILABLE_ENGINES.stream().noneMatch(o -> o.id().equals(engine))) {
            throw new IllegalArgumentException("不支援的分析引擎：" + engine);
        }
        PortfolioAdviceSetting s = settingRepo.findById(PortfolioAdviceSetting.SINGLETON_ID)
                .orElseGet(PortfolioAdviceSetting::new);
        s.setId(PortfolioAdviceSetting.SINGLETON_ID);
        if (s.getEngine() == null || s.getEngine().isBlank()) {
            s.setEngine(resolveEngine());
        }
        if (s.getModel() == null || s.getModel().isBlank()) {
            s.setModel(resolveModel());
        }
        if (s.getEffort() == null || s.getEffort().isBlank()) {
            s.setEffort(resolveEffort());
        }
        if (s.getWebSearchMaxUses() == null) {
            s.setWebSearchMaxUses(resolveWebSearchMaxUses());
        }
        if (model != null) {
            s.setModel(model);
        }
        if (effort != null) {
            s.setEffort(effort);
        }
        if (webSearchMaxUses != null) {
            s.setWebSearchMaxUses(webSearchMaxUses);
        }
        if (engine != null) {
            s.setEngine(engine);
        }
        s.setUpdatedAt(Instant.now());
        settingRepo.save(s);
        log.info("資產配置建議：設定更新（engine={}, model={}, effort={}, webSearchMaxUses={}）",
                s.getEngine(), s.getModel(), s.getEffort(), s.getWebSearchMaxUses());
        return getSettings();
    }

    // ===== 產生建議 =====

    /**
     * upsert 條件後，LOCAL 同步落終態；HYBRID／LLM 落 {@code PROCESSING} 並立即回傳。Claude 呼叫由背景執行緒
     * {@link #runGeneration} 進行，完成後把該列更新為 {@code OK}/{@code FAILED}。前端輪詢至非 PROCESSING。
     *
     * <p>非同步（非同步呼叫）是為避免同步長連線撞 nginx/proxy 60s 逾時；且送出即有 PROCESSING 回饋。
     * 多租戶：**在本（request）執行緒內**組好 system/user prompt（此時 owner filter 生效，取到正確的自己快照與明細），
     * 背景執行緒只做 Claude 呼叫並以 {@code adviceId} by-id 更新（不觸及 owner-scoped 查詢），
     * 避開背景執行緒 {@code TenantFilterAspect} 不啟用的坑。不拋出。
     *
     * <p><b>Task 339</b>：{@code engine} 分岔<b>置於 {@code ANTHROPIC_API_KEY} 檢查之前</b>——
     * {@link #ENGINE_LOCAL} 零 API 呼叫，拔掉金鑰仍須正常產出；金鑰檢查只保留在
     * {@link #ENGINE_HYBRID}／{@link #ENGINE_LLM} 兩個分支內。
     */
    public PortfolioAdvice generate(InvestmentProfileInput in) {
        Long ownerId = tenantGuard.requireCurrentUserId();
        if (ownerId == null) {
            throw new UnauthenticatedException("無使用者情境，無法產生資產配置建議");
        }
        // 先儲存條件（記住免重填、且作為本次建議的條件快照來源）
        saveProfile(in);
        InvestmentProfile profile = profileRepo.findByOwnerUserId(ownerId).orElseThrow();
        List<InvestmentPlannedExpense> expenses = expenseRepo.findByOwnerUserIdOrderByExpenseDate(ownerId);

        String engine = resolveEngine();
        String model = resolveModel();
        int webSearchMaxUses = resolveWebSearchMaxUses();
        boolean webSearchOn = webSearchMaxUses > 0;
        String effort = resolveEffort();

        AssetSnapshot snapshot = snapshotRepo.findLatest().orElse(null);

        PortfolioAdvice row = new PortfolioAdvice();
        row.setOwnerUserId(ownerId);
        row.setCreatedAt(Instant.now());
        row.setModel(truncate(model, 64));
        // 條件快照（age 由生日衍生後凍結為歷史值）
        row.setAge(deriveAge(profile.getBirthDate()));
        row.setGoals(profile.getGoals());
        row.setRiskTolerance(profile.getRiskTolerance());
        row.setExpectedAnnualReturn(profile.getExpectedAnnualReturn());
        // 資產依據
        BigDecimal totalAssets = null;
        if (snapshot != null) {
            totalAssets = snapshot.getTotalAssets() != null ? snapshot.getTotalAssets()
                    : nz(snapshot.getTotalDeposit()).add(nz(snapshot.getTotalFundValue())).add(nz(snapshot.getTotalStockValue()));
            row.setBasedOnSnapshotId(snapshot.getId());
            row.setBasedOnSnapshotDate(snapshot.getSnapshotDate());
            row.setBasedOnTotalAssets(totalAssets);
        }

        // Task 339 的引擎分岔——**務必在下方金鑰檢查之前**（local 檔位無金鑰時仍須成功）
        if (!ENGINE_LLM.equals(engine)) {
            PortfolioAdviceResult localResult = buildLocalResult(profile, totalAssets, snapshot);
            return ENGINE_LOCAL.equals(engine)
                    ? completeLocal(row, localResult, ownerId)
                    : startHybrid(row, localResult, ownerId, model, effort);
        }

        if (apiKey == null || apiKey.isBlank()) {
            log.warn("資產配置建議：未設定 ANTHROPIC_API_KEY，跳過（NOT_CONFIGURED）");
            row.setStatus(PortfolioAdvice.STATUS_NOT_CONFIGURED);
            row.setCompletedAt(Instant.now());
            row.setErrorMessage("尚未設定 Anthropic API 金鑰（ANTHROPIC_API_KEY）");
            return save(row);
        }

        // 在 request 執行緒組 prompt（owner filter 生效 → 取到正確的自己快照與持有明細）
        String systemPrompt = buildSystemPrompt(webSearchOn);
        String userPrompt = buildUserPrompt(profile, expenses, snapshot, webSearchOn);

        row.setStatus(PortfolioAdvice.STATUS_PROCESSING);
        PortfolioAdvice saved = save(row);   // 立即落 PROCESSING，前端據以顯示「產生中」並輪詢
        final Long adviceId = saved.getId();

        log.info("資產配置建議：送出（owner={}, adviceId={}, model={}, effort={}, webSearchMaxUses={}, snapshot={}）",
                ownerId, adviceId, model, effort, webSearchMaxUses, snapshot == null ? "none" : snapshot.getId());
        final BigDecimal totalForEnrich = totalAssets;
        try {
            generationExecutor.submit(() ->
                    runGeneration(adviceId, ownerId, model, effort, webSearchMaxUses, webSearchOn, systemPrompt, userPrompt, totalForEnrich));
        } catch (Exception e) {
            // 提交失敗（如關機中）→ 直接落 FAILED，避免卡 PROCESSING
            log.warn("資產配置建議：背景任務提交失敗（adviceId={}）: {}", adviceId, e.getMessage());
            saved.setStatus(PortfolioAdvice.STATUS_FAILED);
            saved.setCompletedAt(Instant.now());
            saved.setErrorMessage("背景任務提交失敗：" + truncate(e.getMessage(), 900));
            return save(saved);
        }
        return saved;
    }

    // ===== 本機配置模板路徑（Task 339：local 與 hybrid 共用的計算階段）=====

    /**
     * 以本機配置模板算出一份完整建議（{@code local} 與 {@code hybrid} <b>共用同一段計算</b>）。
     *
     * <p>三處刻意複用既有唯一事實來源，不另寫一份：
     * <ul>
     *   <li>現況金額：既有 {@link #getCurrentAllocation()}（本機引擎<b>不重查</b> {@code asset_snapshot}）</li>
     *   <li>退休試算：既有 {@link #getProjection()} →
     *       {@link RetirementProjectionService#project}（Requirement 32 / Task 165 的唯一事實來源）</li>
     *   <li>金額算術：既有 {@link #enrich(PortfolioAdviceResult, BigDecimal)}（三檔位同一支）</li>
     * </ul>
     * {@code rebalancePlan} 在 enrich 之後才由
     * {@link LocalPortfolioAllocationEngine#withRebalancePlan} 依回填好的 {@code deltaAmount} 產生，
     * 最後再由 {@link LocalPortfolioAllocationEngine#withHoldingLevelRebalance} 把各子類別的差額
     * 攤到逐筆持有標的（Requirement 84 / Task 344）。
     *
     * @param snapshot {@code generate()} 已取得的最新快照（逐筆持有明細的來源；
     *                 {@code getCurrentAllocation()} 回傳的 DTO <b>不含任何逐筆持有列</b>，故必須另外傳入。
     *                 為 null（無快照）時 {@code deltaAmount} 亦全為 null，標的層級整組略過）
     */
    private PortfolioAdviceResult buildLocalResult(InvestmentProfile profile, BigDecimal totalAssets,
                                                   AssetSnapshot snapshot) {
        AllocationContext ctx = allocationContext();
        CurrentAllocationDto current = ctx.dto();
        RetirementProjectionDto projection = getProjection();
        Integer years = LocalPortfolioAllocationEngine.yearsToRetirement(
                LocalDate.now(), profile.getRetirementDate(), profile.getBirthDate());
        PortfolioAdviceResult base = localEngine.evaluate(
                profile.getRiskTolerance(), years, current, projection);
        PortfolioAdviceResult withPlan = localEngine.withRebalancePlan(enrich(base, totalAssets));
        PortfolioAdviceResult withSub = localEngine.withSubAllocationAmounts(
                withPlan, current, profile.getRiskTolerance(), years);
        return localEngine.withHoldingLevelRebalance(withSub, holdingBreakdown(snapshot, ctx));
    }

    /**
     * 組出交給 {@link LocalPortfolioAllocationEngine#withHoldingLevelRebalance} 的逐筆持有明細
     * （Task 344.5／344.15／344.20）。
     *
     * <p>三類的來源：股票／基金取自 {@code snapshot.getStocks()}／{@code getFunds()}（{@code generate()} 已取得的
     * 同一個快照，不重查）；存款走既有 {@link BankDepositRepository#findWithBankBySnapshotId}
     * （已 {@code LEFT JOIN FETCH d.bank}，否則取 {@code bank.getDisplayName()} 會觸發 N 次 lazy load）。</p>
     *
     * <p>子類別判定<b>複用 {@link #allocationContext()} 的同一組 {@link AssetClassifier} 呼叫與同一份 override Map</b>，
     * 不寫第二套分類邏輯。提領優先序取自 {@code deposit_type.withdrawal_order}（CLAUDE.md「禁止 Enum 寫死」）
     * ——判準<b>不得</b>改用「名稱含定存」或「利率 &gt; 0」：實測「美元定存」利率為 NULL、「優利活存 1.5%」為 1.5%，
     * 兩種判法都判錯。</p>
     */
    private LocalPortfolioAllocationEngine.HoldingBreakdown holdingBreakdown(
            AssetSnapshot snapshot, AllocationContext ctx) {
        if (snapshot == null) {
            return new LocalPortfolioAllocationEngine.HoldingBreakdown(List.of());
        }
        List<LocalPortfolioAllocationEngine.Holding> holdings = new ArrayList<>();

        for (StockHolding st : snapshot.getStocks()) {
            String key = st.getMarket() + "|" + st.getStockCode();
            String cls = assetClassifier.classifyStock(st.getStockCode(), st.getMarket(),
                    ctx.stockClassOverride().get(key));
            String subClass = AssetClassifier.BOND.equals(cls)
                    ? subClassLabel(assetClassifier.classifyBondTerm(st.getStockCode(), st.getMarket(),
                            ctx.stockNameMap().get(key), ctx.bondTermOverride().get(key)))
                    : subClassLabel(assetClassifier.classifyStockStyle(st.getStockCode(), st.getMarket(),
                            ctx.stockStyleOverride().get(key), st.getDividendRate(), ctx.incomeThreshold()));
            holdings.add(new LocalPortfolioAllocationEngine.Holding(
                    LocalPortfolioAllocationEngine.CLASS_STOCK, subClass,
                    stockDisplayName(ctx.stockNameMap().get(key), st.getStockCode()),
                    nz(st.getCurrentValue()), null, null, null, key));
        }

        for (FundHolding fh : snapshot.getFunds()) {
            String fname = fh.getFundName();
            FundClassOverride ov = ctx.fundOverride().get(fname);
            String cls = assetClassifier.classifyFund(fname, ov != null ? ov.getAssetClass() : null);
            String subClass = AssetClassifier.BOND.equals(cls)
                    ? subClassLabel(assetClassifier.classifyBondTerm(null, null, fname,
                            ov != null ? ov.getBondTerm() : null))
                    : subClassLabel(assetClassifier.classifyStockStyle(null, null,
                            ov != null ? ov.getStockStyle() : null, null, ctx.incomeThreshold()));
            holdings.add(new LocalPortfolioAllocationEngine.Holding(
                    LocalPortfolioAllocationEngine.CLASS_FUND, subClass, fname,
                    nz(fh.getCurrentValue()), null, null, null, fname));
        }

        Map<String, Integer> withdrawalOrders = new HashMap<>();
        for (DepositTypeEntity dt : depositTypeRepo.findAll()) {
            withdrawalOrders.put(dt.getCode(), dt.getWithdrawalOrder());
        }
        for (BankDeposit d : depositRepo.findWithBankBySnapshotId(snapshot.getId())) {
            String bankName = d.getBank() == null ? null : d.getBank().getDisplayName();
            String displayName = (bankName == null || bankName.isBlank())
                    ? String.valueOf(d.getDepositType()) : bankName + " " + d.getDepositType();
            // 存款不合併（一列 bank_deposit 即一個標的）→ stableKey 含列 id
            String stableKey = (d.getBank() == null ? "-" : String.valueOf(d.getBank().getId()))
                    + "|" + d.getDepositType() + "|" + d.getCurrency() + "|" + d.getId();
            holdings.add(new LocalPortfolioAllocationEngine.Holding(
                    LocalPortfolioAllocationEngine.CLASS_CASH, null, displayName,
                    nz(d.getAmount()), withdrawalOrders.get(d.getDepositType()),
                    d.getAnnualInterestRate(), d.getCurrency(), stableKey));
        }
        return new LocalPortfolioAllocationEngine.HoldingBreakdown(List.copyOf(holdings));
    }

    /**
     * 標的顯示名稱（344.15）：{@code {stock 主檔 name}（{stockCode}）}；查不到 {@code name} 時<b>只顯示代號</b>
     * ——不得輸出 {@code null（0050）}。使用者下單要打代號故代號不能拿掉，但清單會出現 00865B／00697B
     * 這類他不見得記得住的代號，只給代號等於要他自己再去查。
     */
    static String stockDisplayName(String name, String stockCode) {
        return (name == null || name.isBlank()) ? String.valueOf(stockCode) : name + "（" + stockCode + "）";
    }

    /**
     * {@code local} 檔位：純計算（毫秒級），故<b>同步直接落終態、不經 {@code PROCESSING}</b>
     * ——但仍用同一組 {@code PortfolioAdvice.STATUS_*} 常數，不新增第二套狀態機（Task 339.10）。
     */
    private PortfolioAdvice completeLocal(PortfolioAdvice row, PortfolioAdviceResult result, Long ownerId) {
        row.setModel(truncate(LocalPortfolioAllocationEngine.ENGINE_VERSION, 64));
        row.setCompletedAt(Instant.now());
        try {
            row.setResultJson(objectMapper.writeValueAsString(result));
            row.setStatus(PortfolioAdvice.STATUS_OK);
            log.info("資產配置建議：本機配置模板完成（owner={}, engine={}, allocations={}, rebalance={}）",
                    ownerId, ENGINE_LOCAL,
                    result.targetAllocation() == null ? 0 : result.targetAllocation().size(),
                    result.rebalancePlan() == null ? 0 : result.rebalancePlan().size());
        } catch (Exception e) {
            log.warn("資產配置建議：本機結果序列化失敗（owner={}）: {}", ownerId, e.getMessage(), e);
            row.setStatus(PortfolioAdvice.STATUS_FAILED);
            row.setErrorMessage(truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 1000));
        }
        return save(row);
    }

    /**
     * {@code hybrid} 檔位：數字全部已由本機算好，只把它交給 LLM 改寫成兩段文字。
     * 維持既有非同步形狀（{@code PROCESSING} → 背景執行緒 → 前端輪詢），金鑰檢查保留於本分支。
     */
    private PortfolioAdvice startHybrid(PortfolioAdvice row, PortfolioAdviceResult localResult,
                                        Long ownerId, String model, String effort) {
        row.setModel(truncate(HYBRID_MODEL_PREFIX + model, 64));
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("資產配置建議：hybrid 檔位未設定 ANTHROPIC_API_KEY，跳過（NOT_CONFIGURED）");
            row.setStatus(PortfolioAdvice.STATUS_NOT_CONFIGURED);
            row.setCompletedAt(Instant.now());
            row.setErrorMessage("尚未設定 Anthropic API 金鑰（ANTHROPIC_API_KEY）；改用「完全本機」檔位可免金鑰產生建議");
            return save(row);
        }
        String systemPrompt = buildHybridSystemPrompt();
        String userPrompt = buildHybridUserPrompt(localResult);

        row.setStatus(PortfolioAdvice.STATUS_PROCESSING);
        PortfolioAdvice saved = save(row);
        final Long adviceId = saved.getId();
        log.info("資產配置建議：hybrid 送出（owner={}, adviceId={}, model={}, effort={}, webSearch=停用）",
                ownerId, adviceId, model, effort);
        try {
            generationExecutor.submit(() ->
                    runHybridGeneration(adviceId, ownerId, model, effort, systemPrompt, userPrompt, localResult));
        } catch (Exception e) {
            log.warn("資產配置建議：hybrid 背景任務提交失敗（adviceId={}）: {}", adviceId, e.getMessage());
            saved.setStatus(PortfolioAdvice.STATUS_FAILED);
            saved.setCompletedAt(Instant.now());
            saved.setErrorMessage("背景任務提交失敗：" + truncate(e.getMessage(), 900));
            return save(saved);
        }
        return saved;
    }

    /**
     * {@code hybrid} 的背景執行緒：呼叫 Claude 只取 {@code summary}／{@code riskAssessment} 兩段文字。
     *
     * <p><b>不重跑 {@link #enrich}</b>——金額已在 {@link #buildLocalResult} 階段由那一支既有方法填好，
     * 再跑一次會對已正確的值重複覆寫。<b>也不走既有 {@link #parseResult}／{@link #sanitize}</b>：
     * 本檔位的請求／回應契約自成一套，且 {@code references} 固定空陣列（無搜尋來源可淨化）。</p>
     *
     * <p>呼叫或解析失敗時<b>退回本機模板文字並落 {@code OK}</b>（另在 {@code warnings} 附記退化原因）——
     * 建議本體已完整算出，不因文字潤飾失敗而讓整筆失敗。</p>
     */
    private void runHybridGeneration(Long adviceId, Long ownerId, String model, String effort,
                                     String systemPrompt, String userPrompt, PortfolioAdviceResult localResult) {
        String rawText = null;
        PortfolioAdviceResult result;
        try {
            Message resp = client().messages().create(buildHybridParams(model, effort, systemPrompt, userPrompt));
            rawText = extractText(resp);
            result = mergeRefinement(localResult, rawText);
            log.info("資產配置建議：hybrid 完成（owner={}, adviceId={}）", ownerId, adviceId);
        } catch (Exception e) {
            log.warn("資產配置建議：hybrid 文字潤飾失敗，改用本機模板文字（owner={}, adviceId={}）: {}",
                    ownerId, adviceId, e.getMessage(), e);
            result = withExtraWarning(localResult, hybridDegradedWarning(e.getMessage()));
        }

        String status = PortfolioAdvice.STATUS_OK;
        String error = null;
        String resultJson = null;
        try {
            resultJson = objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            log.warn("資產配置建議：hybrid 結果序列化失敗（adviceId={}）: {}", adviceId, e.getMessage(), e);
            status = PortfolioAdvice.STATUS_FAILED;
            error = truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 1000);
        }
        try {
            PortfolioAdvice row = adviceRepo.findById(adviceId).orElse(null);
            if (row == null) {
                log.warn("資產配置建議：找不到待更新列（adviceId={}）", adviceId);
                return;
            }
            row.setStatus(status);
            row.setCompletedAt(Instant.now());
            row.setRawResponse(truncate(rawText, 20000));
            row.setResultJson(resultJson);
            row.setErrorMessage(error);
            adviceRepo.save(row);
        } catch (Exception e) {
            log.error("資產配置建議：更新結果落庫失敗（adviceId={}）: {}", adviceId, e.getMessage(), e);
        }
    }

    /**
     * {@code hybrid} 的請求參數：<b>刻意不加 {@code WebSearchTool20260209}</b>（該檔位強制不搜尋，
     * 故也沒有 {@code references} 可帶），且 {@code maxTokens} 為顯著較低的 {@value #HYBRID_MAX_TOKENS}
     * ——只產兩段文字。thinking 與 effort 沿用既有慣例（前端該檔位的「思考深度」下拉仍可用）。
     */
    MessageCreateParams buildHybridParams(String model, String effort, String systemPrompt, String userPrompt) {
        return MessageCreateParams.builder()
                .model(model)
                .maxTokens((long) HYBRID_MAX_TOKENS)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .outputConfig(OutputConfig.builder().effort(mapEffort(effort)).build())
                .system(systemPrompt)
                .addUserMessage(userPrompt)
                .build();
    }

    private String buildHybridSystemPrompt() {
        return """
            你是一位專業的個人理財顧問的「文字編輯」。系統已用固定規則算好一份資產配置建議，
            你的唯一任務是把它改寫成兩段通順、好讀的繁體中文敘述。

            嚴格規則：
            1. 以下數字（各類目標比例、目前金額、目標金額、調整金額、退休試算年齡與年份）**已由系統算好，一律不得更動、不得重算、不得四捨五入成別的值**。
            2. **不得新增任何系統沒給的數字**（含報酬率、勝率、目標價、個股代號與買賣時點）。沒有的資訊就不要寫。
            3. 不得推薦個股買賣時點、不得保證報酬。本建議只到「存款（現金）／信託基金／股票」三個類別層級。
            4. 本次沒有網路搜尋，**不得引用任何新聞、行情或總經數據**，也不要提供任何連結。
            5. 全程使用台灣繁體中文；提到金額時單位為新台幣元。
            6. 必須明確讓讀者知道：目標比例來自固定的經驗法則對照表，未經回測，不是個人化投資建議。

            你的輸出「只包含一個 JSON 物件」，不要有任何多餘文字、不要用 markdown 反引號包裹，且**只有以下兩個字串欄位**：
            {
              "summary": "整體評析與核心建議方向（繁體中文，一段）",
              "riskAssessment": "現況配置的風險評估，含退休現金流試算的結論（繁體中文，一段）"
            }
            """;
    }

    /** hybrid 的 user prompt：把本機算好的配置、調整金額與退休試算摘要原封餵給模型，要求只改寫文字。 */
    private String buildHybridUserPrompt(PortfolioAdviceResult local) {
        StringBuilder sb = new StringBuilder();
        sb.append("== 系統已算好的目標配置（比例與金額皆為定案，不得更動）==\n");
        if (local.targetAllocation() != null) {
            for (PortfolioAdviceResult.TargetAllocation t : local.targetAllocation()) {
                if (t == null) continue;
                sb.append("  - ").append(safe(t.assetClass()))
                        .append("：目標 ").append(t.targetPct() == null ? "—" : t.targetPct().toPlainString())
                        .append("%、目前 ").append(money(t.currentValue()))
                        .append(" 元、目標金額 ").append(money(t.targetAmount()))
                        .append(" 元、差額 ").append(signedMoney(t.deltaAmount()))
                        .append(" 元（理由：").append(safe(t.rationale())).append("）\n");
            }
        }
        sb.append("\n== 系統已算好的調整動作（類別層級，金額為定案）==\n");
        if (local.rebalancePlan() == null || local.rebalancePlan().isEmpty()) {
            sb.append("  （無資產快照，本次沒有金額層級的調整動作）\n");
        } else {
            for (PortfolioAdviceResult.Rebalance r : local.rebalancePlan()) {
                if (r == null) continue;
                sb.append("  - ").append(safe(r.assetClass())).append("（").append(safe(r.holding()))
                        .append("）：").append(safe(r.action())).append(" 約 ")
                        .append(money(r.estimatedAmount())).append(" 元\n");
            }
        }
        sb.append("\n== 系統的本機版敘述（可作為改寫素材，事實以此為準）==\n");
        sb.append("整體評析：").append(safe(local.summary())).append("\n");
        sb.append("風險評估：").append(safe(local.riskAssessment())).append("\n");
        sb.append("\n== 必須保留的提醒（可換句話說，但語意不得減弱）==\n");
        if (local.warnings() != null) {
            for (String w : local.warnings()) {
                sb.append("  - ").append(safe(w)).append("\n");
            }
        }
        sb.append("\n請只輸出含 summary 與 riskAssessment 兩個字串欄位的 JSON 物件。");
        return sb.toString();
    }

    /**
     * 合併 LLM 潤飾結果：<b>只取 {@code summary} 與 {@code riskAssessment} 兩個字串欄位</b>，
     * 其餘（目標比例、各項金額、調整動作、提醒）一律沿用本機值——模型若回了數字欄位，
     * 在此連讀都不讀，等同以本機值覆蓋、不採信。{@code references} 固定空陣列。
     *
     * <p>兩欄都取不到（回覆非 JSON／欄位缺漏）時退回本機模板文字，並在 {@code warnings} 附記退化原因。</p>
     */
    PortfolioAdviceResult mergeRefinement(PortfolioAdviceResult local, String rawText) {
        String summary = null;
        String riskAssessment = null;
        try {
            com.fasterxml.jackson.databind.JsonNode node = parseJsonObject(rawText);
            summary = textField(node, "summary");
            riskAssessment = textField(node, "riskAssessment");
        } catch (Exception e) {
            log.warn("資產配置建議：hybrid 回覆解析失敗，改用本機模板文字: {}", e.getMessage());
        }
        if (summary == null && riskAssessment == null) {
            return withExtraWarning(local, hybridDegradedWarning("模型回覆未含可用的 summary／riskAssessment 欄位"));
        }
        return new PortfolioAdviceResult(
                summary != null ? summary : local.summary(),
                riskAssessment != null ? riskAssessment : local.riskAssessment(),
                local.targetAllocation(),   // 以下一律本機值，不採信模型回傳的任何數字
                local.rebalancePlan(),
                local.actions(),
                local.warnings(),
                List.of());                 // hybrid 不搜尋 → references 固定空陣列
    }

    /** 取首個 {@code &#123;} 至末個 {@code &#125;} 再以 Jackson 解析（沿用既有 {@link #parseResult} 的容錯手法）。 */
    private com.fasterxml.jackson.databind.JsonNode parseJsonObject(String text) throws Exception {
        if (text == null) {
            throw new IllegalStateException("模型回覆為空");
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < 0 || end <= start) {
            throw new IllegalStateException("模型回覆不含 JSON 物件");
        }
        return objectMapper.readTree(text.substring(start, end + 1));
    }

    /** 只接受非空白的字串欄位（數字／物件／陣列一律視為缺欄，避免把模型亂帶的型別當文字用）。 */
    private static String textField(com.fasterxml.jackson.databind.JsonNode node, String name) {
        if (node == null) {
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode v = node.get(name);
        return (v != null && v.isTextual() && !v.asText().isBlank()) ? v.asText().trim() : null;
    }

    private static String hybridDegradedWarning(String reason) {
        return "本次的文字敘述由本機模板產生（AI 潤飾未成功："
                + (reason == null || reason.isBlank() ? "原因未提供" : truncate(reason, 200))
                + "）；所有比例與金額不受影響，皆為本機計算結果。";
    }

    /** 在既有 {@code warnings} 後追加一條（首條的模板聲明維持在最前）。 */
    private static PortfolioAdviceResult withExtraWarning(PortfolioAdviceResult r, String warning) {
        List<String> warnings = new ArrayList<>();
        if (r.warnings() != null) {
            warnings.addAll(r.warnings());
        }
        warnings.add(warning);
        return new PortfolioAdviceResult(r.summary(), r.riskAssessment(), r.targetAllocation(),
                r.rebalancePlan(), r.actions(), List.copyOf(warnings), List.of());
    }

    /**
     * 背景執行緒：實際呼叫 Claude、解析，並以 {@code adviceId} by-id 更新該列為 OK/FAILED。
     * 不觸及 owner-scoped 查詢（prompt 已於 request 執行緒組好）；{@code findById} 不受 {@code @Filter} 影響。不拋出。
     */
    private void runGeneration(Long adviceId, Long ownerId, String model, String effort, int webSearchMaxUses,
                               boolean webSearchOn, String systemPrompt, String userPrompt, BigDecimal totalAssets) {
        String rawText = null;
        String resultJson = null;
        String status;
        String error = null;
        try {
            MessageCreateParams.Builder pb = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens((long) MAX_TOKENS)
                    .thinking(ThinkingConfigAdaptive.builder().build())
                    .outputConfig(OutputConfig.builder().effort(mapEffort(effort)).build());
            if (webSearchOn) {
                pb.addTool(ToolUnion.ofWebSearchTool20260209(
                        WebSearchTool20260209.builder().maxUses((long) webSearchMaxUses).build()));
            }
            MessageCreateParams params = pb.system(systemPrompt).addUserMessage(userPrompt).build();

            Message resp = client().messages().create(params);
            rawText = extractText(resp);
            PortfolioAdviceResult result = parseResult(rawText);
            resultJson = objectMapper.writeValueAsString(enrich(sanitize(result), totalAssets));
            status = PortfolioAdvice.STATUS_OK;
            log.info("資產配置建議：完成（owner={}, adviceId={}, actions={}, rebalance={}）",
                    ownerId, adviceId,
                    result.actions() == null ? 0 : result.actions().size(),
                    result.rebalancePlan() == null ? 0 : result.rebalancePlan().size());
        } catch (Exception e) {
            log.warn("資產配置建議：產生失敗（owner={}, adviceId={}）: {}", ownerId, adviceId, e.getMessage(), e);
            status = PortfolioAdvice.STATUS_FAILED;
            error = truncate(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), 1000);
        }
        // 更新結果（背景執行緒無 request context → 不啟用 ownerFilter；findById 本就不受 @Filter 影響）
        try {
            PortfolioAdvice row = adviceRepo.findById(adviceId).orElse(null);
            if (row == null) {
                log.warn("資產配置建議：找不到待更新列（adviceId={}）", adviceId);
                return;
            }
            row.setStatus(status);
            row.setCompletedAt(Instant.now());
            row.setRawResponse(truncate(rawText, 20000));
            row.setResultJson(resultJson);
            row.setErrorMessage(error);
            adviceRepo.save(row);
        } catch (Exception e) {
            log.error("資產配置建議：更新結果落庫失敗（adviceId={}）: {}", adviceId, e.getMessage(), e);
        }
    }

    private PortfolioAdvice save(PortfolioAdvice row) {
        try {
            return adviceRepo.save(row);
        } catch (Exception e) {
            log.error("資產配置建議：落庫失敗（owner={}）: {}", row.getOwnerUserId(), e.getMessage(), e);
            return row;
        }
    }

    // ===== Prompt =====

    private String buildSystemPrompt(boolean webSearchEnabled) {
        String marketPrinciple = webSearchEnabled
                ? "3. 可使用 web_search 參考「當前」總體經濟與市場狀況（利率／通膨走向、股債評價、匯率、重大風險事件等），把當前環境納入配置考量；references 附上你實際參考的來源（http/https 連結）。"
                : "3. 本次不提供網路搜尋（web_search 已停用）：請勿杜撰新聞或即時行情，僅依使用者條件與現有資產給一般性配置建議，references 一律回空陣列 []。";
        return """
            你是一位專業的個人理財與資產配置顧問。你的任務：根據「使用者的理財條件」與「使用者目前實際持有的資產」，
            給出個人化、可執行的資產配置建議。

            重要原則：
            1. 建議必須同時貼合使用者的年齡、距退休年數、退休前每年淨投入（年薪 − 退休前年生活費）、理財目標、風險承受度與獲利預期——風險承受度與距退休年數是決定股債／現金比重的關鍵。
            1a. 退休兩階段：退休前（累積期）每年淨投入＝年薪 − 退休前年生活費；退休後（退休日期之後）薪水停止、淨投入為 0，只能靠既有資產與其報酬、勞保勞退支應。估算未來可累積金額時，淨投入只計算累積期的年數，切勿假設退休後仍持續投入。越接近或進入退休，配置應越保守（提高現金／固定收益、降低高波動部位）；退休後不可用「之後還會定期投入」來合理化承受更高風險或攤平短期虧損。
            1b. 退休後現金流與未來大筆支出：若使用者提供「勞保年金月領／勞退一次領」，視為退休後的固定收入來源，可部分抵減退休後的資產提領壓力、據此評估既有資產能否支應退休生活（勞保勞退金額為未來實際給付，勿再做通膨調整）。若使用者提供「特定日期大筆花費」（如購車、購屋），金額已換算為該日期的未來名目值——這是未來一次性現金流出，配置上需為其預留足夠流動性、並在越接近該支出日時越保守（避免屆時被迫在低點變現），於 warnings 明確提醒。
            2. 先評估使用者「目前」的配置與風險（過度集中、現金過多／過少、與其風險屬性是否相稱），再提出「目標配置比例」與具體「調整動作」。targetAllocation 各類別的 targetPct 加總應約等於 100。
            2a. targetAllocation 每一類請一併估算 currentValue＝「使用者目前持有的資產中，歸屬於該類別的金額合計」（把每一檔股票／基金／存款分類到最貼近的類別後加總，單位為新台幣元的純數字，不要逗號或文字）。系統會用「資產總額 × targetPct」自動算出各類目標金額與差額，你不需輸出 targetAmount／deltaAmount。
            2b. rebalancePlan：把「怎麼從現況調到目標」落到**逐標的的具體新台幣金額**。針對使用者實際持有的標的（用其代號／名稱）與需要新增的類別，列出要 BUY（增碼/買進）、SELL（減碼/賣出）或 HOLD（維持）約多少錢（estimatedAmount 為正數純數字，單位元）。各項增減碼金額應與各類別的目標差額大致相符（賣出總額 ≈ 買進總額，除非有淨增／淨提領）。這是使用者最想要的「可執行操作清單」，務必具體、可對照其持股。
            2c. 系統已附「退休現金流試算」（依使用者自訂假設做的決定性逐年試算）。請據此在 riskAssessment／warnings 具體點出「退休後資產是否足以支應提領、能撐到幾歲或哪一年可能出現缺口」，並讓 targetAllocation 與提領策略與該試算相容（例如若試算顯示會提早耗盡，就要更強調固定收益／降低提領或延後大額支出）。
            %s
            4. 建議要具體且務實（可含定期定額、再平衡、緊急預備金、分散標的等），但**不得**推薦個股買賣時點或保證報酬。actions 放「非金額類的做法步驟」（如再平衡節奏、緊急預備金、定期檢視），金額類的加減碼放 rebalancePlan。
            5. 全程使用台灣繁體中文；金額以新台幣（TWD）為單位。JSON 內所有金額欄位一律為純數字（不含千分位逗號、貨幣符號或文字）。

            完成後，你的最後輸出「只包含一個 JSON 物件」，不要有任何多餘文字、不要用 markdown 反引號包裹，格式如下：
            {
              "summary": "一段話總結整體評析與核心建議方向（繁體中文）",
              "riskAssessment": "對使用者目前配置與風險的評估，含退休現金流是否足夠（繁體中文）",
              "targetAllocation": [
                {"assetClass": "現金/存款", "targetPct": 20, "currentValue": 8170000, "rationale": "理由"},
                {"assetClass": "債券/固定收益", "targetPct": 15, "currentValue": 0, "rationale": "理由"},
                {"assetClass": "台股", "targetPct": 30, "currentValue": 6000000, "rationale": "理由"},
                {"assetClass": "海外股票", "targetPct": 25, "currentValue": 5000000, "rationale": "理由"},
                {"assetClass": "其他（基金/REITs等）", "targetPct": 10, "currentValue": 60000, "rationale": "理由"}
              ],
              "rebalancePlan": [
                {"assetClass": "現金/存款", "holding": "定存", "action": "SELL", "estimatedAmount": 5000000, "rationale": "現金過多，轉入固定收益"},
                {"assetClass": "債券/固定收益", "holding": "00751B", "action": "BUY", "estimatedAmount": 3000000, "rationale": "建立債券部位作為退休提領緩衝"}
              ],
              "actions": [
                {"title": "調整動作標題", "detail": "非金額類的做法步驟（繁體中文）", "priority": "HIGH | MEDIUM | LOW"}
              ],
              "warnings": ["需提醒使用者注意的風險或前提（繁體中文）"],
              "references": [
                {"title": "參考來源標題", "url": "https://..."}
              ]
            }
            """.formatted(marketPrinciple);
    }

    private String buildUserPrompt(InvestmentProfile p, List<InvestmentPlannedExpense> expenses,
                                   AssetSnapshot snapshot, boolean webSearchEnabled) {
        StringBuilder sb = new StringBuilder();
        sb.append("== 使用者理財條件 ==\n");
        Integer age = deriveAge(p.getBirthDate());
        sb.append("目前年齡：").append(age != null ? age + " 歲（生日 " + p.getBirthDate() + "）" : "未提供").append("\n");
        RetirementSpan span = retirementSpan(p.getBirthDate(), p.getRetirementDate());
        sb.append("預計退休日期：").append(p.getRetirementDate() != null ? p.getRetirementDate().toString() : "未提供").append("\n");
        if (p.getRetirementDate() != null && span.accumulationYears() != null) {
            String salary = p.getPreRetirementAnnualSalary() != null ? money(p.getPreRetirementAnnualSalary()) + " 元" : "未提供";
            String preExp = p.getPreRetirementAnnualExpense() != null ? money(p.getPreRetirementAnnualExpense()) + " 元" : "0 元";
            sb.append("累積期（今天→退休日）：").append(span.accumulationYears())
                    .append(" 年（退休前每年淨投入＝年薪 ").append(salary).append(" − 退休前年生活費 ").append(preExp)
                    .append("，皆今日幣值、依通膨逐年膨脹）\n");
            if (span.retirementYears() != null) {
                sb.append("退休後守成／提領期（退休→100 歲）：約 ").append(span.retirementYears())
                        .append(" 年（退休後薪水停止，退休前淨投入為 0，僅靠既有資產、其報酬與勞保勞退）\n");
            }
            BigDecimal projected = projectedContribution(p.getPreRetirementAnnualSalary(), p.getPreRetirementAnnualExpense(), span.accumulationYears());
            sb.append("退休前預估淨投入合計（僅累積期，退休後不計；未計通膨與報酬）：約 ").append(money(projected)).append(" 元\n");
        } else {
            sb.append("退休前年薪：").append(p.getPreRetirementAnnualSalary() != null ? money(p.getPreRetirementAnnualSalary()) + " 元" : "未提供")
                    .append("、退休前年生活費：").append(p.getPreRetirementAnnualExpense() != null ? money(p.getPreRetirementAnnualExpense()) + " 元" : "未提供")
                    .append("（未提供退休日期）\n");
        }
        sb.append("理財目標：").append(goalLabels(p.getGoals())).append("\n");
        sb.append("風險承受度：").append(labelOf(RISK_OPTIONS, p.getRiskTolerance())).append("\n");
        sb.append("獲利預期：").append(labelOf(RETURN_OPTIONS, p.getExpectedAnnualReturn())).append("\n");
        appendRetirementCashFlow(sb, p, expenses);
        sb.append("\n");

        if (snapshot == null) {
            sb.append("== 使用者目前資產 ==\n（尚無任何資產快照）——請針對其條件，給一般性的「起始配置」建議。\n\n");
        } else {
            BigDecimal deposit = nz(snapshot.getTotalDeposit());
            BigDecimal fund = nz(snapshot.getTotalFundValue());
            BigDecimal stock = nz(snapshot.getTotalStockValue());
            BigDecimal total = snapshot.getTotalAssets() != null ? snapshot.getTotalAssets() : deposit.add(fund).add(stock);
            sb.append("== 使用者目前資產（快照日 ").append(snapshot.getSnapshotDate()).append("，單位：新台幣）==\n");
            sb.append("資產總額：").append(money(total)).append(" 元\n");
            sb.append(String.format("現況配置：存款（現金）%s 元（%s%%）、信託基金 %s 元（%s%%）、股票 %s 元（%s%%）%n",
                    money(deposit), pct(deposit, total).toPlainString(),
                    money(fund), pct(fund, total).toPlainString(),
                    money(stock), pct(stock, total).toPlainString()));
            if (snapshot.getEstimatedAnnualDividend() != null) {
                sb.append("預估年配息：").append(money(snapshot.getEstimatedAnnualDividend())).append(" 元\n");
            }
            sb.append("\n");
            appendHoldings(sb, snapshot.getId());
            appendProjection(sb, p, expenses, total);
        }

        if (webSearchEnabled) {
            sb.append("請結合當前總經／市場狀況（可 web_search）與上述條件、現有資產，給出個人化配置建議並輸出指定 JSON。");
        } else {
            sb.append("請依上述條件與現有資產，給出個人化配置建議並輸出指定 JSON（本次不搜尋網路，references 回空陣列）。");
        }
        return sb.toString();
    }

    /** 追加各類持有明細（存款／基金／股票），每類至多 {@link #MAX_HOLDINGS_PER_CLASS} 檔。含股數／成本／損益／銀行名稱，供 AI 判斷套牢 vs 獲利可調節部位。 */
    private void appendHoldings(StringBuilder sb, Long snapshotId) {
        List<BankDeposit> deposits = depositRepo.findWithBankBySnapshotId(snapshotId);
        if (!deposits.isEmpty()) {
            sb.append("存款明細：\n");
            int n = 0;
            for (BankDeposit d : deposits) {
                if (n++ >= MAX_HOLDINGS_PER_CLASS) { sb.append("  …（其餘略）\n"); break; }
                sb.append("  - ");
                if (d.getBank() != null && d.getBank().getDisplayName() != null) {
                    sb.append(d.getBank().getDisplayName()).append(" ");
                }
                sb.append(safe(d.getDepositType())).append("：").append(money(d.getAmount())).append(" 元");
                if (d.getAnnualInterestRate() != null) {
                    sb.append("（年利率 ").append(d.getAnnualInterestRate().toPlainString()).append("%）");
                }
                if (d.getCurrency() != null && !"TWD".equals(d.getCurrency())) {
                    sb.append("（原幣 ").append(d.getCurrency()).append("）");
                }
                sb.append("\n");
            }
        }
        List<FundHolding> funds = fundRepo.findBySnapshotId(snapshotId);
        if (!funds.isEmpty()) {
            sb.append("信託基金明細：\n");
            int n = 0;
            for (FundHolding f : funds) {
                if (n++ >= MAX_HOLDINGS_PER_CLASS) { sb.append("  …（其餘略）\n"); break; }
                sb.append("  - ").append(safe(f.getFundName()));
                if (f.getFundCode() != null && !f.getFundCode().isBlank()) {
                    sb.append("（").append(f.getFundCode()).append("）");
                }
                sb.append("：現值 ").append(money(f.getCurrentValue())).append(" 元");
                if (f.getInvestmentAmount() != null) {
                    sb.append("、成本 ").append(money(f.getInvestmentAmount())).append(" 元、損益 ")
                            .append(signedMoney(f.getProfit())).append(" 元");
                }
                if (f.getEstimatedDividend() != null && f.getEstimatedDividend().signum() > 0) {
                    sb.append("（預估年配息 ").append(money(f.getEstimatedDividend())).append(" 元）");
                }
                sb.append("\n");
            }
        }
        List<StockHolding> stocks = stockRepo.findBySnapshotId(snapshotId);
        if (!stocks.isEmpty()) {
            sb.append("股票明細：\n");
            int n = 0;
            for (StockHolding s : stocks) {
                if (n++ >= MAX_HOLDINGS_PER_CLASS) { sb.append("  …（其餘略）\n"); break; }
                sb.append("  - ").append(safe(s.getStockCode()));
                if (s.getMarket() != null) sb.append("（").append(s.getMarket()).append("）");
                sb.append("：現值 ").append(money(s.getCurrentValue())).append(" 元");
                if (s.getShares() != null) {
                    sb.append("、股數 ").append(s.getShares().stripTrailingZeros().toPlainString());
                }
                if (s.getInvestmentCost() != null) {
                    sb.append("、成本 ").append(money(s.getInvestmentCost())).append(" 元、損益 ")
                            .append(signedMoney(s.getProfit())).append(" 元");
                }
                if (s.getDividendRate() != null && s.getDividendRate().signum() > 0) {
                    sb.append("（配息率 ").append(s.getDividendRate().multiply(BigDecimal.valueOf(100))
                            .setScale(2, RoundingMode.HALF_UP).toPlainString()).append("%）");
                }
                sb.append("\n");
            }
        }
        sb.append("\n");
    }

    /**
     * 追加「退休現金流試算」段：把 {@link RetirementProjectionService} 的決定性逐年試算摘要餵給 AI，
     * 讓文字建議建立在同一組數字上（單一真實來源，與前端折線圖一致）。試算 unavailable 時附提示、要求 AI 提醒補資料。
     */
    private void appendProjection(StringBuilder sb, InvestmentProfile p, List<InvestmentPlannedExpense> expenses, BigDecimal total) {
        RetirementProjectionDto proj = projectionService.project(p, total, expenses);
        sb.append("== 退休現金流試算（系統依你的假設做的決定性逐年試算，非預測）==\n");
        if (!proj.available()) {
            sb.append("（無法試算：").append(safe(proj.unavailableReason()))
                    .append("）請在 warnings 提醒使用者補齊該資料以取得退休現金流試算。\n\n");
            return;
        }
        RetirementProjectionDto.Assumptions a = proj.assumptions();
        sb.append("起始資產：").append(money(total)).append(" 元\n");
        sb.append("假設：累積期年報酬 ").append(a.accumulationReturnPct().toPlainString()).append("%")
                .append(a.accumReturnFromBand() ? "（依獲利預期帶入）" : "（自訂）")
                .append("、退休後年報酬 ").append(a.retirementReturnPct().toPlainString()).append("%")
                .append(a.retireReturnFromBand() ? "（依獲利預期帶入）" : "（自訂）")
                .append("、年通膨 ").append(a.inflationPct().toPlainString()).append("%");
        if (a.retirementAnnualExpense() != null) {
            sb.append("、長照前年生活費（今日幣值）").append(money(a.retirementAnnualExpense())).append(" 元");
        }
        if (a.longTermCareAnnualExpense() != null) {
            sb.append("、長照後年生活費（今日幣值）").append(money(a.longTermCareAnnualExpense())).append(" 元")
                    .append("（自 ").append(a.longTermCareStartAge()).append(" 歲起）");
        }
        sb.append("\n");
        sb.append("退休後分兩階段：長照前（一般退休生活）與長照後（照護期，年支出通常較高）；每年提領依通膨逐年膨脹。\n");
        if (proj.retirementAge() != null) {
            sb.append("預計退休年齡：約 ").append(proj.retirementAge()).append(" 歲");
            if (proj.retirementStartBalance() != null) {
                sb.append("，退休首年年末預估結餘約 ").append(money(proj.retirementStartBalance())).append(" 元");
            }
            sb.append("\n");
        }
        if (proj.lastsToEndAge()) {
            sb.append("結論：依此假設，資產可支應至 ").append(proj.endAge()).append(" 歲，屆時約剩 ")
                    .append(money(proj.endBalance())).append(" 元（未見缺口）。\n");
        } else {
            sb.append("結論：依此假設，資產預計在約 ").append(proj.depletionAge()).append(" 歲（")
                    .append(proj.depletionYear()).append(" 年）出現資金缺口（提前耗盡）。請據此在建議中強化提領安全性。\n");
        }
        sb.append("\n");
    }

    // ===== 退休兩階段（衍生，不入庫）=====

    /**
     * 依生日與退休日推導兩階段年數（衍生值，不入庫）——不再需要「投資年限」。
     * accumulationYears：今天 → 退休日的整月數 / 12「無條件捨去」（滿一年才算一年，避免高估退休前可投入期間）。
     * retirementYears：退休 → {@link RetirementProjectionService#END_AGE} 歲的守成年數（＝100 − 退休年齡；生日缺時回 null）。
     * 退休已過（≤今天）：accumulationYears = 0（累積期已結束）。
     */
    private RetirementSpan retirementSpan(LocalDate birthDate, LocalDate retirementDate) {
        if (retirementDate == null) {
            return new RetirementSpan(null, null);
        }
        LocalDate now = LocalDate.now();
        long monthsToRetire = ChronoUnit.MONTHS.between(now, retirementDate);
        int accumulationYears = (int) Math.max(0, monthsToRetire / 12); // 整數除法即無條件捨去
        Integer retirementYears = null;
        if (birthDate != null) {
            int retirementAge = Math.max(0, Period.between(birthDate, retirementDate).getYears());
            retirementYears = Math.max(0, RetirementProjectionService.END_AGE - retirementAge);
        }
        return new RetirementSpan(accumulationYears, retirementYears);
    }

    /** 退休前累積年數 / 退休後守成年數（衍生，不入庫）。 */
    private record RetirementSpan(Integer accumulationYears, Integer retirementYears) {}

    /**
     * 估算「退休前預估淨投入合計」＝（年薪 − 退休前年生活費）× 累積年數（今日幣值、未計通膨與報酬，僅供 prompt 概覽）。
     * 退休後每年淨投入視為 0。淨額可為負（退休前即淨提領）。
     */
    private BigDecimal projectedContribution(BigDecimal annualSalary, BigDecimal preRetireExpense, Integer accumulationYears) {
        if (accumulationYears == null || accumulationYears <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal net = nz(annualSalary).subtract(nz(preRetireExpense));
        return net.multiply(BigDecimal.valueOf(accumulationYears));
    }

    // ===== 退休後現金流／未來大筆支出（衍生，不入庫）=====

    /** 由生日推導目前年齡（整年數）；null 生日回 null，未來生日夾為 0。 */
    private Integer deriveAge(LocalDate birthDate) {
        if (birthDate == null) {
            return null;
        }
        return Math.max(0, Period.between(birthDate, LocalDate.now()).getYears());
    }

    /** 假設年通膨率（%）；未填或負值回預設 2%（0% 表示使用者明示不計通膨，予以尊重）。 */
    private BigDecimal resolveInflationRate(BigDecimal rate) {
        return (rate == null || rate.signum() < 0) ? DEFAULT_INFLATION_RATE : rate;
    }

    /**
     * 今日幣值 → 指定日期的未來名目值：{@code amount × (1 + rate%)^年數}，年數＝今天到該日期的天數 ÷ 365.25（可為小數）。
     * amount／date 為 null 時原值回傳；過去日期（年數為負）factor &lt; 1（合理退化）。四捨五入到元。
     */
    private BigDecimal inflate(BigDecimal amount, BigDecimal ratePct, LocalDate date) {
        if (amount == null || date == null) {
            return amount;
        }
        double r = ratePct.doubleValue() / 100.0;
        double years = ChronoUnit.DAYS.between(LocalDate.now(), date) / 365.25;
        double factor = Math.pow(1.0 + r, years);
        return amount.multiply(BigDecimal.valueOf(factor), MathContext.DECIMAL64).setScale(0, RoundingMode.HALF_UP);
    }

    /** 追加「退休後現金流／未來大筆支出」段：勞保月領／勞退一次領（照填、不調通膨）、大筆花費（今日幣值→未來名目值）。 */
    private void appendRetirementCashFlow(StringBuilder sb, InvestmentProfile p, List<InvestmentPlannedExpense> expenses) {
        boolean hasLaborInsurance = p.getLaborInsuranceMonthly() != null && p.getLaborInsuranceMonthly().signum() > 0;
        boolean hasLaborPension = p.getLaborPensionLumpSum() != null && p.getLaborPensionLumpSum().signum() > 0;
        boolean hasExpenses = expenses != null && !expenses.isEmpty();
        if (!hasLaborInsurance && !hasLaborPension && !hasExpenses) {
            return;
        }
        sb.append("退休後現金流與未來大筆支出：\n");
        if (hasLaborInsurance) {
            sb.append("  - 勞保年金：月領 ").append(money(p.getLaborInsuranceMonthly())).append(" 元");
            if (p.getLaborInsuranceStartDate() != null) {
                sb.append("（自 ").append(p.getLaborInsuranceStartDate()).append(" 起領）");
            }
            sb.append("（使用者填入之未來實際給付，未做通膨調整）\n");
        }
        if (hasLaborPension) {
            sb.append("  - 勞退：一次領 ").append(money(p.getLaborPensionLumpSum())).append(" 元");
            if (p.getLaborPensionClaimDate() != null) {
                sb.append("（於 ").append(p.getLaborPensionClaimDate()).append(" 領取）");
            }
            sb.append("（使用者填入之未來實際給付，未做通膨調整）\n");
        }
        if (hasExpenses) {
            BigDecimal rate = resolveInflationRate(p.getAssumedAnnualInflationRate());
            sb.append("  特定日期大筆花費（金額已由今日幣值依假設年通膨率 ").append(rate.toPlainString())
                    .append("% 換算為該日期之未來名目值）：\n");
            for (InvestmentPlannedExpense e : expenses) {
                BigDecimal future = inflate(e.getAmount(), rate, e.getExpenseDate());
                sb.append("    - ").append(e.getExpenseDate());
                if (e.getName() != null && !e.getName().isBlank()) {
                    sb.append(" ").append(e.getName());
                }
                sb.append("：今日幣值 ").append(money(e.getAmount())).append(" 元 → 未來約 ")
                        .append(money(future)).append(" 元\n");
            }
        }
    }

    // ===== 回覆解析 =====

    private String extractText(Message resp) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : resp.content()) {
            block.text().ifPresent(t -> sb.append(t.text()).append("\n"));
        }
        return sb.toString().trim();
    }

    private PortfolioAdviceResult parseResult(String text) throws Exception {
        if (text == null) throw new IllegalStateException("模型回覆為空");
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < 0 || end <= start) {
            throw new IllegalStateException("模型回覆不含 JSON 物件");
        }
        return objectMapper.readValue(text.substring(start, end + 1), PortfolioAdviceResult.class);
    }

    /** 淨化解析結果：references 僅保留 http(s)（web_search 為不可信來源，防前端 href XSS，前端另有一層）。 */
    private PortfolioAdviceResult sanitize(PortfolioAdviceResult r) {
        List<PortfolioAdviceResult.Reference> refs = new ArrayList<>();
        if (r.references() != null) {
            for (PortfolioAdviceResult.Reference ref : r.references()) {
                if (ref == null) continue;
                String url = safeHttpUrl(ref.url());
                if (ref.title() == null && url == null) continue;
                refs.add(new PortfolioAdviceResult.Reference(ref.title(), url));
            }
        }
        return new PortfolioAdviceResult(
                r.summary(), r.riskAssessment(), r.targetAllocation(), r.rebalancePlan(), r.actions(), r.warnings(), refs);
    }

    /**
     * 回填 targetAllocation 的「目標金額／差額」——決定性算術由後端做，不交給 LLM：
     * targetAmount = 資產總額 × targetPct%（四捨五入到元）；deltaAmount = targetAmount − currentValue（LLM 分類的目前金額，可 null）。
     * 總額未知（無快照）時只保留 LLM 的比例與分類，不回填金額。
     */
    private PortfolioAdviceResult enrich(PortfolioAdviceResult r, BigDecimal totalAssets) {
        if (r.targetAllocation() == null || totalAssets == null || totalAssets.signum() <= 0) {
            return r;
        }
        List<PortfolioAdviceResult.TargetAllocation> out = new ArrayList<>();
        for (PortfolioAdviceResult.TargetAllocation t : r.targetAllocation()) {
            if (t == null) continue;
            BigDecimal targetAmount = t.targetPct() == null ? null
                    : totalAssets.multiply(t.targetPct()).divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
            BigDecimal delta = (targetAmount != null && t.currentValue() != null)
                    ? targetAmount.subtract(t.currentValue()).setScale(0, RoundingMode.HALF_UP)
                    : null;
            out.add(new PortfolioAdviceResult.TargetAllocation(
                    t.assetClass(), t.targetPct(), t.currentValue(), targetAmount, delta, t.rationale(),
                    t.subAllocations()));
        }
        return new PortfolioAdviceResult(
                r.summary(), r.riskAssessment(), out, r.rebalancePlan(), r.actions(), r.warnings(), r.references());
    }

    private String safeHttpUrl(String url) {
        if (url == null) return null;
        String u = url.trim().toLowerCase();
        return (u.startsWith("http://") || u.startsWith("https://")) ? url.trim() : null;
    }

    // ===== Anthropic client =====

    private AnthropicClient client() {
        AnthropicClient c = anthropicClient;
        if (c == null) {
            synchronized (this) {
                c = anthropicClient;
                if (c == null) {
                    c = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
                    anthropicClient = c;
                }
            }
        }
        return c;
    }

    @PreDestroy
    void shutdown() {
        generationExecutor.shutdown();
        try {
            if (!generationExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                generationExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            generationExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        AnthropicClient c = anthropicClient;
        if (c instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.debug("關閉 Anthropic client: {}", e.getMessage());
            }
        }
    }

    private OutputConfig.Effort mapEffort(String effort) {
        return switch (effort == null ? "" : effort) {
            case "low" -> OutputConfig.Effort.LOW;
            case "high" -> OutputConfig.Effort.HIGH;
            default -> OutputConfig.Effort.MEDIUM;
        };
    }

    // ===== 小工具 =====

    private void validateRisk(String risk) {
        if (risk != null && !risk.isBlank() && RISK_OPTIONS.stream().noneMatch(o -> o.id().equals(risk))) {
            throw new IllegalArgumentException("不支援的風險承受度：" + risk);
        }
    }

    private void validateReturn(String ret) {
        if (ret != null && !ret.isBlank() && RETURN_OPTIONS.stream().noneMatch(o -> o.id().equals(ret))) {
            throw new IllegalArgumentException("不支援的獲利預期：" + ret);
        }
    }

    /** 有效 goals code 以逗號串接（白名單過濾、去重、保序）；無則 null。 */
    private String joinGoals(List<String> goals) {
        if (goals == null || goals.isEmpty()) return null;
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (String g : goals) {
            if (g == null) continue;
            String code = g.trim();
            if (GOAL_OPTIONS.stream().anyMatch(o -> o.id().equals(code))) {
                seen.put(code, Boolean.TRUE);
            }
        }
        return seen.isEmpty() ? null : String.join(",", seen.keySet());
    }

    /** 逗號分隔 goals code → 「退休準備、資產增值」中文標籤串。 */
    private String goalLabels(String goalsCsv) {
        if (goalsCsv == null || goalsCsv.isBlank()) return "未提供";
        List<String> labels = new ArrayList<>();
        for (String code : goalsCsv.split(",")) {
            labels.add(labelOf(GOAL_OPTIONS, code.trim()));
        }
        return String.join("、", labels);
    }

    private String labelOf(List<InvestmentProfileDto.Option> options, String id) {
        if (id == null || id.isBlank()) return "未提供";
        return options.stream().filter(o -> o.id().equals(id)).map(InvestmentProfileDto.Option::label)
                .findFirst().orElse(id);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /** 占比%（保留 1 位小數）；total<=0 回 0。 */
    private static BigDecimal pct(BigDecimal value, BigDecimal total) {
        if (total == null || total.signum() <= 0) return BigDecimal.ZERO;
        return value.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP);
    }

    private static String money(BigDecimal v) {
        if (v == null) return "0";
        return String.format("%,.0f", v.setScale(0, RoundingMode.HALF_UP));
    }

    /** 帶正負號的金額（損益用）：正數前綴 +，負數自帶 −。 */
    private static String signedMoney(BigDecimal v) {
        if (v == null) return "0";
        BigDecimal r = v.setScale(0, RoundingMode.HALF_UP);
        return (r.signum() > 0 ? "+" : "") + String.format("%,.0f", r);
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
