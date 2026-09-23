package com.steven.assets.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.model.News;
import com.steven.assets.repository.FundamentalAnalysisBatchRepository;
import com.steven.assets.util.MarketZones;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * 個股基本面與產業因子的唯一 as-of resolver（Task 292）。
 *
 * <p>production 與回測都必須傳入顯式 {@code decisionInstant}。production 單次查詢直接限制兩個
 * as-of 時點；回測則只查到該檔最晚決策日，再於記憶體對每個歷史日套用
 * {@code source_available_at <= decisionInstant AND observed_at <= decisionInstant}、選來源順位與
 * 當時最新 revision。多期因子全部來自同一 provider family，禁止把交易所 EPS 與 FinMind 權益
 * 拼成 ROE，也不會讓事後修正版倒灌過去回測。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FundamentalAnalysisService {

    private static final String TW_MARKET = "台股";
    private static final String US_MARKET = "美股";
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final int VALUATION_MAX_AGE_DAYS = 10;
    private static final int PE_MIN_SAMPLES = 250;
    // SEC_EDGAR 插在 EXCHANGE 之後、YAHOO 之前：與 EXCHANGE 同屬「官方一手資料」優先序，只是分屬不同市場
    // （EXCHANGE 只會出現在台股列、SEC_EDGAR 只會出現在美股列，兩者不會同時對同一標的出現，插入順序
    // 不影響台股既有行為）。firstProviderValue() 是通用方法，不需要為市場另建第二份清單。
    // SEC_DERIVED（Requirement 74 / Task 334.5）一律排在**最末**：它是由已入庫官方季報推導出來的估值序列，
    // 不是任何來源觀測到的公告值，只有在所有實際觀測來源都湊不到 PE_MIN_SAMPLES 筆時才輪得到它。
    private static final String DERIVED_PROVIDER = "SEC_DERIVED";
    private static final List<String> PROVIDERS =
            List.of("EXCHANGE", "SEC_EDGAR", "YAHOO", "WANTGOO", "FINMIND", DERIVED_PROVIDER);

    private final FundamentalAnalysisBatchRepository fundamentalBatchRepository;
    private final ObjectMapper mapper;
    private final PublicInfoEvidenceResolver publicInfo;

    /**
     * 邊界單位轉接器：資料庫欄位固定保存百分點（4.00 代表 4%），只有明確標記為 ratio
     * 的外部 adapter 輸入才乘 100；避免同一數值在 DB→profile→rule 路徑被重複轉換。
     */
    static BigDecimal normalizeDividendYieldPct(BigDecimal raw, boolean ratioInput) {
        if (raw == null) return null;
        return ratioInput ? raw.multiply(BigDecimal.valueOf(100)).setScale(4, RoundingMode.HALF_UP)
                : raw;
    }

    public record Resolved(
            TradingRadarRuleEngine.FundamentalInput input,
            TradingRadarDto.FundamentalSnapshot snapshot) {
        public static Resolved unavailable(boolean applicable) {
            return unavailable(applicable, PublicInfoEvidenceResolver.Evidence.EMPTY);
        }

        public static Resolved unavailable(
                boolean applicable, PublicInfoEvidenceResolver.Evidence evidence) {
        TradingRadarRuleEngine.FundamentalInput input = new TradingRadarRuleEngine.FundamentalInput(
                applicable, null, null, null, null, null, false, false);
            TradingRadarDto.FundamentalSnapshot snapshot = new TradingRadarDto.FundamentalSnapshot(
                    applicable, 0, null, null, null, null, null,
                    null, List.of(), null, null, List.of(), null,
                    null, List.of(), null, null, List.of(), null,
                    null, null, null, null, null, List.of(), null,
                    evidence == null ? List.of() : evidence.company(),
                    evidence == null ? List.of() : evidence.industry());
            return new Resolved(input, snapshot);
        }
    }

    /** Exact natural-key request for the Radar list's one immutable fundamental snapshot. */
    public record BatchQuery(
            String stockCode,
            String stockName,
            String market,
            TradingRadarAssetProfileResolver.AssetProfile profile) {}

    private record BatchKey(String stockCode, String market) {}

    public Resolved resolve(String stockCode, String stockName, String market, Instant decisionInstant) {
        TradingRadarAssetProfileResolver.AssetProfile strictProfile =
                TradingRadarAssetProfileResolver.resolve(stockCode, market, stockName,
                        null, null, null, null, null, null);
        return resolve(stockCode, stockName, market, decisionInstant, strictProfile);
    }

    /** Production overload carrying the same strict profile used by the radar decision. */
    public Resolved resolve(
            String stockCode, String stockName, String market, Instant decisionInstant,
            TradingRadarAssetProfileResolver.AssetProfile strictProfile) {
        if (stockCode == null || decisionInstant == null
                || !Set.of(TW_MARKET, US_MARKET).contains(market)) {
            return Resolved.unavailable(false);
        }
        if (strictProfile == null) {
            strictProfile = TradingRadarAssetProfileResolver.resolve(stockCode, market, stockName,
                    null, null, null, null, null, null);
        }
        if (!strictProfile.equity()
                || strictProfile.instrumentKind() != TradingRadarAssetProfileResolver.InstrumentKind.STOCK) {
            return Resolved.unavailable(false);
        }

        // public_info_* 是原文證據第一順位；先載入一次，個股與產業比對共用同一快照。
        List<News> evidenceRows;
        PublicInfoEvidenceResolver.Evidence initialEvidence;
        try {
            evidenceRows = publicInfo.loadForEarliestDecision(decisionInstant);
            initialEvidence = publicInfo.resolveFromRows(
                    stockCode, stockName, null, market, decisionInstant, evidenceRows);
        } catch (Exception e) {
            log.warn("public_info 個股證據讀取失敗（{}）：{}", stockCode, e.getMessage());
            evidenceRows = List.of();
            initialEvidence = PublicInfoEvidenceResolver.Evidence.EMPTY;
        }

        try {
            PreparedData data = loadPreparedData(stockCode, market, decisionInstant);
            // Decision dates are market-local: US/UK/TW observations must not cross a UTC
            // midnight merely because the old resolver used a Taipei-only zone.
            return resolvePrepared(stockCode, stockName, market, decisionInstant, data, evidenceRows,
                    MarketZones.resolve(market));
        } catch (Exception e) {
            // 個別基本面資料失敗不得拖垮技術面；optional 權重會在規則引擎中重分配。
            log.warn("基本面 as-of 解析失敗（{}）：{}", stockCode, e.getMessage());
            return Resolved.unavailable(true, initialEvidence);
        }
    }

    /**
     * List-path batch entry.  Public information is loaded once and each result is keyed by the
     * full (code, market) pair; callers must never key this map by code alone.
     */
    public Map<BatchQuery, Resolved> resolveBatch(List<BatchQuery> rawQueries, Instant decisionInstant) {
        if (rawQueries == null || rawQueries.isEmpty() || decisionInstant == null) return Map.of();
        Map<BatchKey, BatchQuery> canonical = new LinkedHashMap<>();
        rawQueries.stream().filter(Objects::nonNull)
                .filter(query -> query.stockCode() != null && query.market() != null)
                .sorted(Comparator.comparing(BatchQuery::market).thenComparing(BatchQuery::stockCode))
                .forEach(query -> canonical.putIfAbsent(new BatchKey(query.stockCode(), query.market()), query));
        List<BatchQuery> queries = List.copyOf(canonical.values());
        if (queries.isEmpty()) return Map.of();
        Map<BatchQuery, Resolved> out = new LinkedHashMap<>();
        List<BatchKey> applicableKeys = new ArrayList<>();
        for (BatchQuery query : queries) {
            TradingRadarAssetProfileResolver.AssetProfile profile = query.profile();
            if (profile != null && profile.equity()
                    && profile.instrumentKind() == TradingRadarAssetProfileResolver.InstrumentKind.STOCK) {
                applicableKeys.add(new BatchKey(query.stockCode(), query.market()));
            } else {
                out.put(query, Resolved.unavailable(false));
            }
        }
        if (applicableKeys.isEmpty()) return Map.copyOf(out);
        List<News> evidenceRows;
        try {
            evidenceRows = publicInfo.loadForEarliestDecision(decisionInstant);
        } catch (Exception unavailable) {
            log.warn("public_info 清單批次讀取失敗：{}", unavailable.getMessage());
            evidenceRows = List.of();
        }
        Map<BatchKey, PreparedData> preparedByKey;
        try {
            preparedByKey = loadPreparedDataBatch(applicableKeys, decisionInstant);
        } catch (Exception unavailable) {
            log.warn("基本面清單批次 observation 讀取失敗：{}", unavailable.getMessage());
            preparedByKey = Map.of();
        }
        for (BatchQuery query : queries) {
            if (out.containsKey(query)) continue;
            try {
                PreparedData prepared = preparedByKey.getOrDefault(
                        new BatchKey(query.stockCode(), query.market()), PreparedData.EMPTY);
                out.put(query, resolvePrepared(query.stockCode(), query.stockName(), query.market(), decisionInstant,
                        prepared, evidenceRows, MarketZones.resolve(query.market())));
            } catch (Exception unavailable) {
                PublicInfoEvidenceResolver.Evidence evidence = PublicInfoEvidenceResolver.Evidence.EMPTY;
                try {
                    evidence = publicInfo.resolveFromRows(query.stockCode(), query.stockName(), null,
                            query.market(), decisionInstant, evidenceRows);
                } catch (RuntimeException ignored) { }
                out.put(query, Resolved.unavailable(true, evidence));
            }
        }
        return Map.copyOf(out);
    }

    /**
     * 回測專用批次入口：每檔股票只載入四張 observation 表一次，再於記憶體依每個決策日套用
     * {@code source_available_at}/{@code observed_at} 與 revision 選擇。公開資訊只影響揭露、不影響分數，
     * 回測不建立畫面 snapshot，因而不掃多年新聞。這避免原本每股票×每日 4 次 SQL 加新聞查詢的 N+1。
     */
    public Map<Instant, TradingRadarRuleEngine.FundamentalInput> resolveInputsForBacktest(
            String stockCode, String market, List<Instant> decisionInstants) {
        return resolveResolvedInputsForBacktest(stockCode, market, decisionInstants).entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> entry.getValue().input()));
    }

    /**
     * 回測 evidence gate 需要與 rule input 同一個 as-of snapshot；保留 Resolved
     * （而非只回傳 FundamentalInput）避免 V13 用「有分數但無來源／日期」的合成快照冒充完整證據。
     */
    public Map<Instant, Resolved> resolveResolvedInputsForBacktest(
            String stockCode, String market, List<Instant> decisionInstants) {
        return resolveResolvedInputsForBacktest(stockCode, market, decisionInstants, null);
    }

    /** Strict-profile batch overload; ETF/bond callers fail closed even when NAV history is empty. */
    public Map<Instant, Resolved> resolveResolvedInputsForBacktest(
            String stockCode, String market, List<Instant> decisionInstants,
            TradingRadarAssetProfileResolver.AssetProfile strictProfile) {
        List<Instant> instants = decisionInstants == null ? List.of() : decisionInstants.stream()
                .filter(Objects::nonNull).distinct().sorted().toList();
        if (instants.isEmpty()) return Map.of();
        boolean profileKnown = strictProfile != null;
        boolean applicable = stockCode != null && Set.of(TW_MARKET, US_MARKET).contains(market)
                && (profileKnown
                ? strictProfile.equity()
                        && strictProfile.instrumentKind() == TradingRadarAssetProfileResolver.InstrumentKind.STOCK
                : !isEtf(stockCode, market));
        if (!applicable) return unavailableResolvedInputs(instants, false);
        try {
            PreparedData data = loadPreparedData(stockCode, market, instants.get(instants.size() - 1));
            Map<Instant, Resolved> result = new LinkedHashMap<>();
            for (Instant instant : instants) {
                result.put(instant, resolvePrepared(
                        stockCode, stockCode, market, instant, data, List.of(),
                        MarketZones.resolve(market)));
            }
            return Map.copyOf(result);
        } catch (Exception e) {
            log.warn("基本面回測批次解析失敗（{}）：{}", stockCode, e.getMessage());
            return unavailableResolvedInputs(instants, true);
        }
    }

    public Map<Instant, TradingRadarRuleEngine.FundamentalInput> resolveInputsForBacktest(
            String stockCode, String market, List<Instant> decisionInstants,
            TradingRadarAssetProfileResolver.AssetProfile strictProfile) {
        return resolveResolvedInputsForBacktest(stockCode, market, decisionInstants, strictProfile)
                .entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        Map.Entry::getKey, entry -> entry.getValue().input()));
    }

    private Map<Instant, TradingRadarRuleEngine.FundamentalInput> unavailableInputs(
            List<Instant> instants, boolean applicable) {
        Map<Instant, TradingRadarRuleEngine.FundamentalInput> result = new LinkedHashMap<>();
        TradingRadarRuleEngine.FundamentalInput input = Resolved.unavailable(applicable).input();
        instants.forEach(instant -> result.put(instant, input));
        return Map.copyOf(result);
    }

    private Map<Instant, Resolved> unavailableResolvedInputs(
            List<Instant> instants, boolean applicable) {
        Map<Instant, Resolved> result = new LinkedHashMap<>();
        Resolved resolved = Resolved.unavailable(applicable);
        instants.forEach(instant -> result.put(instant, resolved));
        return Map.copyOf(result);
    }

    private Resolved resolvePrepared(
            String stockCode,
            String stockName,
            String market,
            Instant decisionInstant,
            PreparedData data,
            List<News> evidenceRows,
            ZoneId decisionZone) {
        LocalDate decisionDate = decisionInstant.atZone(
                decisionZone == null ? TAIPEI : decisionZone).toLocalDate();
        List<FinancialRow> financialRows = latestAsOf(
                data.financials(), decisionInstant,
                row -> row.provider() + '|' + row.year() + '|' + row.quarter());
        Factor eps = firstProviderValue(financialRows, FinancialRow::provider,
                rows -> epsFactor(rows.stream().sorted(FinancialRow.DESC).toList(), decisionDate));
        Factor roe = firstProviderValue(financialRows, FinancialRow::provider,
                rows -> roeFactor(rows.stream().sorted(FinancialRow.DESC).toList(), decisionDate));

        List<RevenueRow> revenueRows = latestAsOf(
                data.revenues(), decisionInstant,
                row -> row.provider() + '|' + row.year() + '|' + row.month());
        Factor revenue = firstProviderValue(revenueRows, RevenueRow::provider,
                rows -> revenueFactor(rows.stream().sorted(RevenueRow.DESC).toList(), decisionDate));
        RevenueRow industryBasis = latestRevenueBasis(revenueRows, decisionDate);

        List<ValuationRow> valuationRows = latestAsOf(
                data.valuations(), decisionInstant,
                row -> row.provider() + '|' + row.date());
        boolean severeFinancial = (eps != null && eps.contribution() != null && eps.contribution() <= -0.8)
                || (roe != null && roe.contribution() != null && roe.contribution() <= -0.8);
        ValuationComposite valuation = valuationComposite(valuationRows, decisionDate, severeFinancial);
        Factor pe = valuation == null ? null : valuation.asFactor();

        List<IndustryRow> industryRows = latestAsOf(
                data.industries(), decisionInstant,
                row -> row.provider() + '|' + row.industryName() + '|' + row.year() + '|' + row.month());
        Factor industry = industryBasis == null ? null : industryFactor(industryBasis, industryRows);
        String industryName = industryBasis == null ? null : industryBasis.industryName();
        PublicInfoEvidenceResolver.Evidence evidence = evidenceRows == null || evidenceRows.isEmpty()
                ? PublicInfoEvidenceResolver.Evidence.EMPTY
                : publicInfo.resolveFromRows(
                        stockCode, stockName, industryName, market, decisionInstant, evidenceRows);

        int coverage = count(eps, roe, revenue, pe);
        boolean peLoss = pe != null && pe.loss();
        TradingRadarRuleEngine.FundamentalInput input = new TradingRadarRuleEngine.FundamentalInput(
                true, contribution(eps), contribution(roe), contribution(revenue), contribution(pe),
                contribution(industry), peLoss, roe != null && roe.fallback());
        TradingRadarDto.FundamentalSnapshot snapshot = new TradingRadarDto.FundamentalSnapshot(
                true, coverage,
                value(eps), value(roe), value(revenue), valuation == null ? null : valuation.pePercentile(),
                pe == null ? null : pe.loss(),
                provider(eps), urls(eps), asOf(eps),
                provider(roe), urls(roe), asOf(roe),
                provider(revenue), urls(revenue), asOf(revenue),
                provider(pe), urls(pe), asOf(pe),
                industryName, value(industry), industry == null ? null : industry.companyCount(),
                industry == null ? null : industry.period(), provider(industry), urls(industry), asOf(industry),
                evidence.company(), evidence.industry(),
                valuation == null ? null : valuation.peValue(),
                valuation == null ? null : valuation.pbValue(),
                valuation == null ? null : valuation.dividendYieldPct(),
                valuation == null ? null : valuation.pbPercentile(),
                valuation == null ? null : valuation.dividendYieldPercentile(),
                valuation == null ? null : valuation.contribution(),
                valuation == null ? 0 : valuation.coverage(),
                eps == null ? null : eps.trendType(),
                roe == null ? false : roe.fallback(),
                valuation == null ? null : componentEvidence(valuation.peComponent()),
                valuation == null ? null : componentEvidence(valuation.pbComponent()),
                valuation == null ? null : componentEvidence(valuation.dividendYieldComponent()));
        return new Resolved(input, snapshot);
    }

    private boolean isEtf(String stockCode, String market) {
        if (stockCode.startsWith("00")) return true;
        try {
            return fundamentalBatchRepository != null
                    && fundamentalBatchRepository.hasEtfNav(stockCode, market);
        } catch (Exception e) {
            return false;
        }
    }

    private PreparedData loadPreparedData(String code, String market, Instant latestInstant) {
        if (fundamentalBatchRepository == null) return PreparedData.EMPTY;
        return toPreparedData(fundamentalBatchRepository.findSnapshot(
                new FundamentalAnalysisBatchRepository.Key(code, market), latestInstant));
    }

    /**
     * One immutable exact-pair snapshot for the list path.  The four source tables are still
     * intentionally separate evidence families, but each family is read once for every requested
     * natural key rather than once per row. A single-decision batch applies the same as-of
     * selector before materializing PreparedData; repository snapshots retain every revision.
     */
    private Map<BatchKey, PreparedData> loadPreparedDataBatch(
            Collection<BatchKey> rawKeys, Instant latestInstant) {
        if (rawKeys == null || rawKeys.isEmpty() || latestInstant == null
                || fundamentalBatchRepository == null) return Map.of();
        List<FundamentalAnalysisBatchRepository.Key> keys = rawKeys.stream().filter(Objects::nonNull)
                .filter(key -> key.stockCode() != null && key.market() != null)
                .map(key -> new FundamentalAnalysisBatchRepository.Key(key.stockCode(), key.market()))
                .distinct().sorted(Comparator.comparing(FundamentalAnalysisBatchRepository.Key::market)
                        .thenComparing(FundamentalAnalysisBatchRepository.Key::stockCode)).toList();
        Map<BatchKey, PreparedData> out = new LinkedHashMap<>();
        // The same source URL JSON accompanies many historical revisions. Parse each exact
        // value once for this batch; no observations or provider/as-of choices are discarded.
        Map<String, List<String>> parsedUrls = new LinkedHashMap<>();
        fundamentalBatchRepository.findSnapshots(keys, latestInstant).forEach((key, snapshot) ->
                out.put(new BatchKey(key.stockCode(), key.market()),
                        toPreparedData(selectSnapshotAsOf(snapshot, latestInstant), parsedUrls)));
        return Map.copyOf(out);
    }

    /** The raw snapshot remains intact for other decisions; the selected rows keep first-seen order. */
    static FundamentalAnalysisBatchRepository.Snapshot selectSnapshotAsOf(
            FundamentalAnalysisBatchRepository.Snapshot snapshot, Instant decisionInstant) {
        if (snapshot == null) return FundamentalAnalysisBatchRepository.Snapshot.empty();
        return new FundamentalAnalysisBatchRepository.Snapshot(
                latestAsOf(snapshot.financials(), decisionInstant,
                        row -> row.provider() + '|' + row.year() + '|' + row.quarter(),
                        FundamentalAnalysisBatchRepository.FinancialObservation::availableAt,
                        FundamentalAnalysisBatchRepository.FinancialObservation::observedAt),
                latestAsOf(snapshot.revenues(), decisionInstant,
                        row -> row.provider() + '|' + row.year() + '|' + row.month(),
                        FundamentalAnalysisBatchRepository.RevenueObservation::availableAt,
                        FundamentalAnalysisBatchRepository.RevenueObservation::observedAt),
                latestAsOf(snapshot.valuations(), decisionInstant,
                        row -> row.provider() + '|' + row.date(),
                        FundamentalAnalysisBatchRepository.ValuationObservation::availableAt,
                        FundamentalAnalysisBatchRepository.ValuationObservation::observedAt),
                latestAsOf(snapshot.industries(), decisionInstant,
                        row -> row.provider() + '|' + row.industryName() + '|' + row.year() + '|' + row.month(),
                        FundamentalAnalysisBatchRepository.IndustryObservation::availableAt,
                        FundamentalAnalysisBatchRepository.IndustryObservation::observedAt));
    }

    private PreparedData toPreparedData(FundamentalAnalysisBatchRepository.Snapshot snapshot) {
        return toPreparedData(snapshot, new LinkedHashMap<>());
    }

    private PreparedData toPreparedData(FundamentalAnalysisBatchRepository.Snapshot snapshot,
                                       Map<String, List<String>> parsedUrls) {
        if (snapshot == null) return PreparedData.EMPTY;
        return new PreparedData(
                snapshot.financials().stream().map(row -> new FinancialRow(
                        row.year(), row.quarter(), row.eps(), row.income(), row.equity(), row.provider(),
                        parsedUrls.computeIfAbsent(row.sourceUrls(), this::urls), row.availableAt(), row.observedAt())).toList(),
                snapshot.revenues().stream().map(row -> new RevenueRow(
                        row.year(), row.month(), row.industryName(), row.yoy(), row.provider(),
                        parsedUrls.computeIfAbsent(row.sourceUrls(), this::urls), row.availableAt(), row.observedAt())).toList(),
                snapshot.valuations().stream().map(row -> new ValuationRow(
                        row.date(), row.pe(), row.pb(), normalizeDividendYieldPct(row.dividendYieldPct(), false),
                        row.loss(), row.provider(), parsedUrls.computeIfAbsent(row.sourceUrls(), this::urls),
                        row.availableAt(), row.observedAt())).toList(),
                snapshot.industries().stream().map(row -> new IndustryRow(
                        row.industryName(), row.year(), row.month(), row.yoy(), row.companyCount(), row.provider(),
                        parsedUrls.computeIfAbsent(row.sourceUrls(), this::urls), row.availableAt(), row.observedAt())).toList());
    }

    static <T extends SourcedRow> List<T> latestAsOf(
            List<T> rows, Instant decisionInstant, Function<T, String> revisionKey) {
        return latestAsOf(rows, decisionInstant, revisionKey, SourcedRow::availableAt, SourcedRow::observedAt);
    }

    private static <T> List<T> latestAsOf(
            List<T> rows, Instant decisionInstant, Function<T, String> revisionKey,
            Function<T, Instant> availableAt, Function<T, Instant> observedAt) {
        if (rows == null || decisionInstant == null) return List.of();
        Map<String, T> latest = new LinkedHashMap<>();
        for (T row : rows) {
            if (row == null || availableAt.apply(row) == null || observedAt.apply(row) == null
                    || availableAt.apply(row).isAfter(decisionInstant)
                    || observedAt.apply(row).isAfter(decisionInstant)) continue;
            String key = revisionKey.apply(row);
            if (key == null) continue;
            latest.merge(key, row, (left, right) ->
                    observedAt.apply(left).compareTo(observedAt.apply(right)) >= 0 ? left : right);
        }
        return List.copyOf(latest.values());
    }

    private Factor industryFactor(RevenueRow basis, List<IndustryRow> rows) {
        if (basis.industryName() == null || basis.industryName().isBlank()) return null;
        for (String provider : PROVIDERS) {
            IndustryRow row = rows.stream()
                    .filter(v -> provider.equals(v.provider()))
                    .filter(v -> basis.industryName().equals(v.industryName())
                            && basis.year() == v.year() && basis.month() == v.month())
                    .findFirst().orElse(null);
            if (row == null || row.yoy() == null || row.companyCount() < 3) continue;
            return new Factor(row.yoy(), clampUnit(row.yoy().doubleValue() / 15.0), provider,
                    row.urls(), row.availableAt(), false, row.companyCount(), basis.period());
        }
        return null;
    }

    private record PreparedData(
            List<FinancialRow> financials,
            List<RevenueRow> revenues,
            List<ValuationRow> valuations,
            List<IndustryRow> industries) {
        private static final PreparedData EMPTY = new PreparedData(List.of(), List.of(), List.of(), List.of());
    }

    /*
     * 下面的 factor 計算只接收已依決策日 collapse 過的 observation；不得在此再查 DB，
     * 才能讓 production 單次解析與回測批次解析共用同一套來源順位及公式。
     */
    Factor epsFactor(List<FinancialRow> rows, LocalDate decisionDate) {
        List<FinancialRow> recent = consecutive(rows, 8, FinancialRow::periodIndex);
        if (recent.size() < 8 || !financialFresh(recent.get(0), decisionDate)) return null;
        List<BigDecimal> standalone = recent.stream().map(r -> standalone(r, rows, FinancialRow::eps)).toList();
        EpsTrend trend = epsTrend(standalone);
        if (trend == null) return null;
        return new Factor(trend.yoyPct(), trend.contribution(), recent.get(0).provider(),
                mergedUrls(recent), latestAvailable(recent), trend.loss(), null,
                recent.get(0).period(), trend.type(), false);
    }

    /**
     * 斜率 10（Task 300）：±1 對應 ROE 0%／20%，−0.8（deteriorating severe 線）對應 ROE 2%；
     * 斜率為判斷性取值、無回測依據。中心維持 10%。
     */
    Factor roeFactor(List<FinancialRow> rows, LocalDate decisionDate) {
        List<FinancialRow> recent = consecutive(rows, 4, FinancialRow::periodIndex);
        if (recent.size() < 4 || !financialFresh(recent.get(0), decisionDate)
                || recent.get(0).equity() == null || recent.get(0).equity().signum() <= 0) return null;
        List<BigDecimal> standalone = recent.stream()
                .map(r -> standalone(r, rows, FinancialRow::income)).toList();
        FinancialRow endRow = recent.get(0);
        FinancialRow beginningRow = rows.stream()
                .filter(r -> r.periodIndex() == endRow.periodIndex() - 4)
                .findFirst().orElse(null);
        boolean fallback = beginningRow == null || beginningRow.equity() == null;
        if (beginningRow != null && !Objects.equals(beginningRow.provider(), endRow.provider())) return null;
        if (beginningRow != null && beginningRow.equity() != null && beginningRow.equity().signum() <= 0) {
            return null;
        }
        BigDecimal beginningEquity = fallback ? endRow.equity() : beginningRow.equity();
        BigDecimal roe = approximateRoePct(standalone, beginningEquity, endRow.equity());
        if (roe == null) return null;
        double contribution = clampUnit((roe.doubleValue() - 10.0) / 10.0);
        return new Factor(roe, contribution, recent.get(0).provider(), mergedUrls(recent),
                latestAvailable(recent), false, null, recent.get(0).period(), null, fallback);
    }

    Factor revenueFactor(List<RevenueRow> rows, LocalDate decisionDate) {
        List<RevenueRow> recent = consecutive(rows, 3, RevenueRow::periodIndex);
        if (recent.size() < 3 || !revenueFresh(recent.get(0), decisionDate)
                || recent.stream().anyMatch(r -> r.yoy() == null)) return null;
        BigDecimal average = threeMonthAverage(recent.stream().map(RevenueRow::yoy).toList());
        return new Factor(average, clampUnit(average.doubleValue() / 15.0), recent.get(0).provider(),
                mergedUrls(recent), latestAvailable(recent), false, null, recent.get(0).period());
    }

    Factor peFactor(List<ValuationRow> rows, LocalDate decisionDate) {
        for (String provider : PROVIDERS) {
            List<ValuationRow> providerRows = rows.stream()
                    .filter(r -> provider.equals(r.provider())).sorted(ValuationRow.DESC).toList();
            if (providerRows.isEmpty()) continue;
            ValuationRow latest = providerRows.get(0);
            if (!fresh(latest.date(), decisionDate, VALUATION_MAX_AGE_DAYS)) continue;
            // A fresh explicit flag (true or false) is authoritative for this
            // provider.  Do not let a lower-priority provider replace an explicit
            // non-loss row merely because this provider lacks enough PE history.
            if (latest.loss() == null) continue;
            if (Boolean.TRUE.equals(latest.loss())) {
                return new Factor(null, -1.0, provider, latest.urls(), latest.availableAt(), true, null,
                        latest.date().toString());
            }
            if (latest.pe() == null || latest.pe().signum() <= 0) return null;
            List<BigDecimal> history = providerRows.stream().map(ValuationRow::pe)
                    .filter(v -> v != null && v.signum() > 0).toList();
            if (history.size() < PE_MIN_SAMPLES) return null;
            long atOrBelow = history.stream().filter(v -> v.compareTo(latest.pe()) <= 0).count();
            BigDecimal percentile = BigDecimal.valueOf(100.0 * atOrBelow / history.size())
                    .setScale(1, RoundingMode.HALF_UP);
            double contribution = clampUnit(-(percentile.doubleValue() - 50.0) / 30.0);
            return new Factor(percentile, contribution, provider, latest.urls(), latest.availableAt(),
                    false, null, latest.date().toString());
        }
        return null;
    }

    /**
     * t307.1：PE／PB／殖利率各自在自己的 provider 歷史中選取 component，
     * 因此可來自不同 provider／date；每項都有自己的最新 freshness 與至少
     * 250 筆有效歷史。缺一項只降低 coverage，不以 0 補值。
     */
    ValuationComposite valuationComposite(
            List<ValuationRow> rows, LocalDate decisionDate, boolean severeFinancial) {
        if (rows == null || decisionDate == null) return null;
        // Each component selects its own latest valid provider observation.  A late
        // PE revision must not force PB/yield to inherit its date or provenance.
        Component pe = valuationComponent(rows, ValuationRow::pe, decisionDate, false);
        Component pb = valuationComponent(rows, ValuationRow::pb, decisionDate, false);
        Component rawYield = valuationComponent(rows, ValuationRow::dividendYieldPct,
                decisionDate, true);

        // A provider's explicit PE-loss flag is a hard financial warning.  Keep any
        // raw yield observation for profile/API disclosure, but never let it rescue
        // the valuation score (the severe path excludes it from available below).
        LossDecision loss = latestLoss(rows, decisionDate);
        if (loss != null && loss.loss()) {
            // The explicit PE-loss row replaces the PE component; historical
            // positive PE observations must not leak beside the loss flag.
            return ValuationComposite.loss(lossComponent(loss.row()), pb, rawYield);
        }
        Component yield = severeFinancial ? null : rawYield;
        List<Component> available = java.util.stream.Stream.of(pe, pb, yield)
                .filter(java.util.Objects::nonNull).toList();
        if (available.isEmpty() && rawYield == null) return null;
        // A severe-financial snapshot may retain a raw dividend-yield observation for
        // disclosure while PE/PB are both unavailable.  Do not turn that empty score
        // into a synthetic -1 loss; null must remain an unavailable valuation factor.
        Double contribution = available.isEmpty()
                ? null
                : available.stream().mapToDouble(Component::contribution).average().orElse(0.0);
        List<Component> all = java.util.stream.Stream.of(pe, pb, rawYield)
                .filter(java.util.Objects::nonNull).toList();
        List<String> urls = mergedComponentUrls(all);
        Instant availableAt = latestComponentAvailable(all);
        String provider = componentProvider(all);
        String asOf = latestComponentDate(all);
        return new ValuationComposite(
                pe == null ? null : pe.value(), pe == null ? null : pe.percentile(),
                pb == null ? null : pb.value(), pb == null ? null : pb.percentile(),
                rawYield == null ? null : rawYield.value(), rawYield == null ? null : rawYield.percentile(),
                contribution, available.size(), provider, urls, availableAt, false, asOf,
                pe, pb, rawYield);
    }

    private Component valuationComponent(
            List<ValuationRow> rows,
            Function<ValuationRow, BigDecimal> valueOf,
            LocalDate decisionDate,
            boolean yieldComponent) {
        for (String provider : PROVIDERS) {
            List<ValuationRow> providerRows = rows.stream()
                    .filter(r -> provider.equals(r.provider()))
                    .sorted(ValuationRow.DESC).toList();
            if (providerRows.isEmpty()) continue;
            ValuationRow latestRow = providerRows.stream()
                    .filter(r -> valueOf.apply(r) != null)
                    .findFirst().orElse(null);
            if (latestRow == null || !fresh(latestRow.date(), decisionDate, VALUATION_MAX_AGE_DAYS)) continue;
            List<BigDecimal> history = providerRows.stream().map(valueOf)
                    .filter(v -> v != null && v.signum() > 0).toList();
            if (history.size() < PE_MIN_SAMPLES) continue;
            BigDecimal value = valueOf.apply(latestRow);
            if (value == null || value.signum() <= 0) continue;
            BigDecimal percentile = percentile(value, history);
            double contribution = -(percentile.doubleValue() - 50.0) / 30.0;
            if (yieldComponent) contribution = Math.max(0.0, contribution * -1.0);
            return new Component(value, percentile, clampUnit(contribution), latestRow.provider(),
                    latestRow.urls(), latestRow.availableAt(), latestRow.date(), false);
        }
        return null;
    }

    private static Component lossComponent(ValuationRow row) {
        return row == null ? null : new Component(null, null, -1.0, row.provider(),
                row.urls(), row.availableAt(), row.date(), true);
    }

    /**
     * Select the first provider with a fresh, explicit PE-loss flag. An explicit
     * false is authoritative for that provider; only a missing, stale, or null
     * flag may fall through to the next provider.
     *
     * <p><b>{@link #DERIVED_PROVIDER} 一律排除（Task 334.5）</b>：虧損與否是一手觀測事實，不接受由自家
     * 推導值認定。這條路徑<b>沒有任何筆數門檻</b>——只要該 provider 的最新一列 fresh 且 loss 非 null 就
     * 命中，而 {@code valuationComposite} 拿到 loss 決策就直接回 {@link ValuationComposite#loss}，把 PE
     * component 換成 contribution 寫死 {@code -1.0} 的 {@link #lossComponent}。SEC_DERIVED 的每一列都會
     * 寫 {@code pe_loss_flag}（TRUE／FALSE 二選一），只要 YAHOO 當期列的旗標為 null 或已過期，推導出的
     * 虧損旗標就會繞過 {@link #PE_MIN_SAMPLES} 門檻把整組 VALUATION 打成 -1.0，與「推導值只在所有實際
     * 觀測來源都湊不到 250 筆時才被採用」直接抵觸。</p>
     */
    private LossDecision latestLoss(List<ValuationRow> rows, LocalDate decisionDate) {
        for (String provider : PROVIDERS) {
            if (DERIVED_PROVIDER.equals(provider)) continue;
            ValuationRow latest = rows.stream().filter(r -> provider.equals(r.provider()))
                    .sorted(ValuationRow.DESC).findFirst().orElse(null);
            if (latest == null || !fresh(latest.date(), decisionDate, VALUATION_MAX_AGE_DAYS)
                    || latest.loss() == null) continue;
            return new LossDecision(latest, latest.loss());
        }
        return null;
    }

    private static BigDecimal percentile(BigDecimal value, List<BigDecimal> history) {
        long atOrBelow = history.stream().filter(v -> v.compareTo(value) <= 0).count();
        return BigDecimal.valueOf(100.0 * atOrBelow / history.size())
                .setScale(1, RoundingMode.HALF_UP);
    }

    private RevenueRow latestRevenueBasis(List<RevenueRow> rows, LocalDate decisionDate) {
        for (String provider : PROVIDERS) {
            RevenueRow row = rows.stream().filter(v -> provider.equals(v.provider()))
                    .filter(v -> v.industryName() != null && !v.industryName().isBlank())
                    .max(Comparator.comparingInt(RevenueRow::periodIndex)).orElse(null);
            if (row != null && revenueFresh(row, decisionDate)) return row;
        }
        return null;
    }

    private static boolean financialFresh(FinancialRow row, LocalDate decisionDate) {
        return row != null && row.periodIndex() >= expectedFinancialPeriodIndex(decisionDate);
    }

    private static boolean revenueFresh(RevenueRow row, LocalDate decisionDate) {
        return row != null && row.periodIndex() >= expectedRevenuePeriodIndex(decisionDate);
    }

    /** 必須與 external-materials-service 的 fallback deadline 契約同步。 */
    static int expectedFinancialPeriodIndex(LocalDate decisionDate) {
        int year = decisionDate.getYear();
        if (!decisionDate.isBefore(LocalDate.of(year, 11, 14))) return year * 4 + 3;
        if (!decisionDate.isBefore(LocalDate.of(year, 8, 14))) return year * 4 + 2;
        if (!decisionDate.isBefore(LocalDate.of(year, 5, 15))) return year * 4 + 1;
        if (!decisionDate.isBefore(LocalDate.of(year, 3, 31))) return (year - 1) * 4 + 4;
        return (year - 1) * 4 + 3;
    }

    /** 必須與 external-materials-service 的月營收 fallback deadline 契約同步。 */
    static int expectedRevenuePeriodIndex(LocalDate decisionDate) {
        YearMonth expected = YearMonth.from(decisionDate).minusMonths(
                decisionDate.getDayOfMonth() >= 11 ? 1 : 2);
        return expected.getYear() * 12 + expected.getMonthValue();
    }

    private static boolean fresh(LocalDate period, LocalDate decisionDate, int maxAgeDays) {
        if (period == null || decisionDate == null || period.isAfter(decisionDate)) return false;
        return ChronoUnit.DAYS.between(period, decisionDate) <= maxAgeDays;
    }

    static <T, R> R firstProviderValue(
            List<T> rows, Function<T, String> providerOf, Function<List<T>, R> calculator) {
        for (String provider : PROVIDERS) {
            List<T> sameProvider = rows.stream().filter(r -> provider.equals(providerOf.apply(r))).toList();
            if (sameProvider.isEmpty()) continue;
            R result = calculator.apply(sameProvider);
            if (result != null) return result;
        }
        return null;
    }

    private static <T> List<T> consecutive(List<T> rows, int count, Function<T, Integer> index) {
        if (rows.size() < count) return List.of();
        List<T> recent = rows.subList(0, count);
        for (int i = 1; i < recent.size(); i++) {
            if (index.apply(recent.get(i - 1)) - index.apply(recent.get(i)) != 1) return List.of();
        }
        return recent;
    }

    /** 年度累計值轉單季；Q2–Q4 缺前一季時必須是 null。 */
    private static BigDecimal standalone(
            FinancialRow row, List<FinancialRow> sameProvider, Function<FinancialRow, BigDecimal> value) {
        BigDecimal cumulative = value.apply(row);
        if (cumulative == null) return null;
        if (row.quarter() == 1) return cumulative;
        FinancialRow previous = sameProvider.stream()
                .filter(r -> r.year() == row.year() && r.quarter() == row.quarter() - 1)
                .findFirst().orElse(null);
        BigDecimal previousCumulative = previous == null ? null : value.apply(previous);
        return standaloneValue(row.quarter(), cumulative, previousCumulative);
    }

    static BigDecimal standaloneValue(int quarter, BigDecimal cumulative, BigDecimal previousCumulative) {
        if (cumulative == null) return null;
        if (quarter == 1) return cumulative;
        if (quarter < 1 || quarter > 4 || previousCumulative == null) return null;
        return cumulative.subtract(previousCumulative);
    }

    static BigDecimal epsYoyPct(List<BigDecimal> standaloneDesc) {
        if (standaloneDesc == null || standaloneDesc.size() < 8
                || standaloneDesc.subList(0, 8).stream().anyMatch(Objects::isNull)) return null;
        BigDecimal recentFour = sum(standaloneDesc.subList(0, 4));
        BigDecimal priorFour = sum(standaloneDesc.subList(4, 8));
        if (priorFour.signum() <= 0) return null;
        return recentFour.subtract(priorFour).multiply(BigDecimal.valueOf(100))
                .divide(priorFour, 4, RoundingMode.HALF_UP);
    }

    /** EPS 四季對四季趨勢；負基期不再被錯誤地當成「無資料」。 */
    static EpsTrend epsTrend(List<BigDecimal> standaloneDesc) {
        if (standaloneDesc == null || standaloneDesc.size() < 8
                || standaloneDesc.subList(0, 8).stream().anyMatch(Objects::isNull)) return null;
        BigDecimal recent = sum(standaloneDesc.subList(0, 4));
        BigDecimal prior = sum(standaloneDesc.subList(4, 8));
        if (recent.signum() > 0 && prior.signum() > 0) {
            BigDecimal yoy = recent.subtract(prior).multiply(BigDecimal.valueOf(100))
                    .divide(prior, 4, RoundingMode.HALF_UP);
            return new EpsTrend(yoy, clampUnit(yoy.doubleValue() / 20.0),
                    yoy.signum() >= 0 ? "POSITIVE_BASE_IMPROVING" : "POSITIVE_BASE_DETERIORATING", false);
        }
        if (recent.signum() > 0) return new EpsTrend(null, 1.0, "TURNAROUND", false);
        if (prior.signum() > 0) return new EpsTrend(null, -1.0, "TURNED_LOSS", true);
        return new EpsTrend(null, -1.0, "PERSISTENT_LOSS", true);
    }

    static BigDecimal approximateRoePct(List<BigDecimal> standaloneDesc, BigDecimal latestEquity) {
        if (standaloneDesc == null || standaloneDesc.size() < 4 || latestEquity == null
                || latestEquity.signum() <= 0
                || standaloneDesc.subList(0, 4).stream().anyMatch(Objects::isNull)) return null;
        return sum(standaloneDesc.subList(0, 4)).multiply(BigDecimal.valueOf(100))
                .divide(latestEquity, 4, RoundingMode.HALF_UP);
    }

    /** 平均期初／期末權益；兩者皆正才可用。 */
    static BigDecimal approximateRoePct(
            List<BigDecimal> standaloneDesc, BigDecimal beginningEquity, BigDecimal endingEquity) {
        if (standaloneDesc == null || standaloneDesc.size() < 4
                || beginningEquity == null || endingEquity == null
                || beginningEquity.signum() <= 0 || endingEquity.signum() <= 0
                || standaloneDesc.subList(0, 4).stream().anyMatch(Objects::isNull)) return null;
        BigDecimal denominator = beginningEquity.add(endingEquity)
                .divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
        return sum(standaloneDesc.subList(0, 4)).multiply(BigDecimal.valueOf(100))
                .divide(denominator, 4, RoundingMode.HALF_UP);
    }

    static BigDecimal threeMonthAverage(List<BigDecimal> yoyDesc) {
        if (yoyDesc == null || yoyDesc.size() < 3
                || yoyDesc.subList(0, 3).stream().anyMatch(Objects::isNull)) return null;
        return sum(yoyDesc.subList(0, 3)).divide(BigDecimal.valueOf(3), 4, RoundingMode.HALF_UP);
    }

    private Factor factor(BigDecimal value, double contribution, List<FinancialRow> rows) {
        return new Factor(value, contribution, rows.get(0).provider(), mergedUrls(rows),
                latestAvailable(rows), false, null, rows.get(0).period());
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static double clampUnit(double value) {
        return Math.max(-1.0, Math.min(1.0, value));
    }

    private List<String> urls(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return List.copyOf(mapper.readValue(json, new TypeReference<List<String>>() {}));
        } catch (Exception e) {
            log.warn("基本面 source_urls 解析失敗：{}", e.getMessage());
            return List.of();
        }
    }

    private static int count(Factor... factors) {
        int count = 0;
        for (Factor factor : factors) {
            // A factor carrying disclosure values but no usable contribution (for
            // example severe-financial raw yield only) is not score coverage.
            if (factor != null && factor.contribution() != null) count++;
        }
        return count;
    }

    private static BigDecimal value(Factor factor) { return factor == null ? null : factor.value(); }
    private static Double contribution(Factor factor) { return factor == null ? null : factor.contribution(); }
    private static String provider(Factor factor) { return factor == null ? null : factor.provider(); }
    private static List<String> urls(Factor factor) { return factor == null ? List.of() : factor.urls(); }
    private static String asOf(Factor factor) {
        return factor == null || factor.availableAt() == null ? null : factor.availableAt().toString();
    }

    private static <T extends SourcedRow> List<String> mergedUrls(List<T> rows) {
        Set<String> result = new LinkedHashSet<>();
        rows.forEach(row -> result.addAll(row.urls()));
        return List.copyOf(result);
    }

    private static <T extends SourcedRow> Instant latestAvailable(List<T> rows) {
        return rows.stream().map(SourcedRow::availableAt).filter(Objects::nonNull)
                .max(Instant::compareTo).orElse(null);
    }

    private static TradingRadarDto.ValuationComponentEvidence componentEvidence(Component component) {
        if (component == null) return null;
        return new TradingRadarDto.ValuationComponentEvidence(
                component.value(), component.percentile(), component.provider(), component.urls(),
                component.availableAt() == null ? null : component.availableAt().toString(),
                component.asOf() == null ? null : component.asOf().toString(), component.loss());
    }

    private static List<String> mergedComponentUrls(List<Component> components) {
        Set<String> urls = new LinkedHashSet<>();
        components.forEach(component -> urls.addAll(component.urls()));
        return List.copyOf(urls);
    }

    private static Instant latestComponentAvailable(List<Component> components) {
        return components.stream().map(Component::availableAt).filter(Objects::nonNull)
                .max(Instant::compareTo).orElse(null);
    }

    private static String latestComponentDate(List<Component> components) {
        return components.stream().map(Component::asOf).filter(Objects::nonNull)
                .max(LocalDate::compareTo).map(LocalDate::toString).orElse(null);
    }

    private static String componentProvider(List<Component> components) {
        Set<String> providers = components.stream().map(Component::provider)
                .filter(Objects::nonNull).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (providers.isEmpty()) return null;
        return providers.size() == 1 ? providers.iterator().next() : "MULTI";
    }

    interface SourcedRow {
        List<String> urls();
        Instant availableAt();
        Instant observedAt();
    }

    record Factor(
            BigDecimal value,
            Double contribution,
            String provider,
            List<String> urls,
            Instant availableAt,
            boolean loss,
            Integer companyCount,
            String period,
            String trendType,
            boolean fallback) {
        Factor(BigDecimal value, Double contribution, String provider, List<String> urls,
               Instant availableAt, boolean loss, Integer companyCount, String period) {
            this(value, contribution, provider, urls, availableAt, loss, companyCount, period, null, false);
        }

        Factor withContribution(Double next) {
            return new Factor(value, next, provider, urls, availableAt, loss, companyCount, period,
                    trendType, fallback);
        }
    }

    record Component(
            BigDecimal value,
            BigDecimal percentile,
            Double contribution,
            String provider,
            List<String> urls,
            Instant availableAt,
            LocalDate asOf,
            boolean loss) {
        Component(BigDecimal value, BigDecimal percentile, Double contribution,
                  String provider, List<String> urls, Instant availableAt, LocalDate asOf) {
            this(value, percentile, contribution, provider, urls, availableAt, asOf, false);
        }
    }

    record LossDecision(ValuationRow row, boolean loss) {}

    record EpsTrend(BigDecimal yoyPct, double contribution, String type, boolean loss) {}

    record ValuationComposite(
            BigDecimal peValue,
            BigDecimal pePercentile,
            BigDecimal pbValue,
            BigDecimal pbPercentile,
            BigDecimal dividendYieldPct,
            BigDecimal dividendYieldPercentile,
            Double contribution,
            int coverage,
            String provider,
            List<String> urls,
            Instant availableAt,
            boolean loss,
            String asOf,
            Component peComponent,
            Component pbComponent,
            Component dividendYieldComponent) {
        static ValuationComposite loss(Component peLoss, Component pb, Component rawYield) {
            // Keep every independently resolved component for disclosure even when
            // an explicit PE-loss flag fixes the valuation score at -1.
            List<Component> all = java.util.stream.Stream.of(peLoss, pb, rawYield)
                    .filter(java.util.Objects::nonNull).toList();
            return new ValuationComposite(
                    null, null,
                    pb == null ? null : pb.value(), pb == null ? null : pb.percentile(),
                    rawYield == null ? null : rawYield.value(),
                    rawYield == null ? null : rawYield.percentile(),
                    -1.0, all.size(), componentProvider(all), mergedComponentUrls(all),
                    latestComponentAvailable(all), true, latestComponentDate(all), peLoss, pb, rawYield);
        }

        Factor asFactor() {
            return new Factor(pePercentile, contribution, provider, urls, availableAt,
                    loss, null, asOf, null, false);
        }
    }

    record FinancialRow(
            int year, int quarter, BigDecimal eps, BigDecimal income, BigDecimal equity,
            String provider, List<String> urls, Instant availableAt, Instant observedAt) implements SourcedRow {
        static final Comparator<FinancialRow> DESC = Comparator.comparingInt(FinancialRow::periodIndex).reversed();
        int periodIndex() { return year * 4 + quarter; }
        String period() { return year + "Q" + quarter; }
    }

    record RevenueRow(
            int year, int month, String industryName, BigDecimal yoy,
            String provider, List<String> urls, Instant availableAt, Instant observedAt) implements SourcedRow {
        static final Comparator<RevenueRow> DESC = Comparator.comparingInt(RevenueRow::periodIndex).reversed();
        int periodIndex() { return year * 12 + month; }
        String period() { return "%04d-%02d".formatted(year, month); }
    }

    record ValuationRow(
            java.time.LocalDate date, BigDecimal pe, BigDecimal pb, BigDecimal dividendYieldPct, Boolean loss,
            String provider, List<String> urls, Instant availableAt, Instant observedAt) implements SourcedRow {
        static final Comparator<ValuationRow> DESC = Comparator.comparing(ValuationRow::date).reversed();

        ValuationRow(java.time.LocalDate date, BigDecimal pe, Boolean loss,
                     String provider, List<String> urls, Instant availableAt, Instant observedAt) {
            this(date, pe, null, null, loss, provider, urls, availableAt, observedAt);
        }
    }

    record IndustryRow(
            String industryName, int year, int month, BigDecimal yoy, int companyCount,
            String provider, List<String> urls, Instant availableAt, Instant observedAt) implements SourcedRow {}
}
