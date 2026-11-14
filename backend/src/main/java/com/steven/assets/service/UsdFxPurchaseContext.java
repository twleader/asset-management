package com.steven.assets.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.model.ExchangeRateHistory;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** D-215 FX preference facts. No IO except reading the already embedded, hashed policy authority. */
public record UsdFxPurchaseContext(String stockCode, String market, String quoteCurrency,
        TradingRadarAssetProfileResolver.InstrumentKind instrumentKind, boolean typeVerified,
        MarketData data, boolean evaluated) {
    private static final BigDecimal LIMIT = new BigDecimal("32.8");
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    public static final UsdFxPurchaseContext NOT_EVALUATED = new UsdFxPurchaseContext(
            null, null, null, TradingRadarAssetProfileResolver.InstrumentKind.UNKNOWN, false, MarketData.unavailable("NOT_EVALUATED"), false);
    public enum Preference { NOT_APPLICABLE, NORMAL, HIGH, UNKNOWN }
    public record VerifiedInstrument(String stockCode, String market, String quoteCurrency,
            TradingRadarAssetProfileResolver.InstrumentKind kind, String sourceDocument, String sourceSha256) {}
    public record HistoryRange(boolean available, LocalDate windowStart, LocalDate windowEnd,
            LocalDate completedAsOfDate, BigDecimal low, BigDecimal high, String basis,
            String source, String unavailableReason, int excludedLegacyRows) {
        public HistoryRange(boolean available, LocalDate windowStart, LocalDate windowEnd, LocalDate completedAsOfDate,
                BigDecimal low, BigDecimal high, String basis, String source, String unavailableReason) {
            this(available, windowStart, windowEnd, completedAsOfDate, low, high, basis, source, unavailableReason, 0);
        }
        public static HistoryRange unavailable(LocalDate end, LocalDate completed, String reason) {
            return new HistoryRange(false, end == null ? null : end.minusYears(2), end, completed,
                    null, null, "BANK_SPOT_SELLING", "CANONICAL_HISTORY_PROVIDER_UNRECORDED", reason);
        }
    }
    /** Explicit source proof must bind the exact supplied spot-selling rows and window. */
    public record HistoryProof(String status, String rateBasis, String quoteType, String source,
            String sourceSha256, String rowsSha256, LocalDate windowStart, LocalDate windowEnd,
            LocalDate completedAsOfDate) {}
    public record MarketData(String quality, BigDecimal currentRate, Instant currentAsOf,
            String currentSource, HistoryRange history, String unavailableReason) {
        public static MarketData unavailable(String reason) {
            return new MarketData("UNAVAILABLE", null, null, null, HistoryRange.unavailable(null, null, reason), reason);
        }
    }

    public UsdFxPurchaseContext {
        instrumentKind = instrumentKind == null ? TradingRadarAssetProfileResolver.InstrumentKind.UNKNOWN : instrumentKind;
        data = data == null ? MarketData.unavailable("USD_CURRENT_FX_UNAVAILABLE") : data;
    }
    public static UsdFxPurchaseContext forAsset(String code, String market, String quoteCurrency, MarketData data) {
        return forAsset(code, market, quoteCurrency, data, PolicyInstruments.VALUES.get(market + ":" + code));
    }
    static UsdFxPurchaseContext forAsset(String code, String market, String quoteCurrency, MarketData data,
                                        VerifiedInstrument proof) {
        boolean verified = proof != null && code != null && code.equals(proof.stockCode())
                && market != null && market.equals(proof.market()) && quoteCurrency != null
                && quoteCurrency.equals(proof.quoteCurrency()) && proof.kind() != TradingRadarAssetProfileResolver.InstrumentKind.UNKNOWN
                && proof.sourceDocument() != null && !proof.sourceDocument().isBlank()
                && proof.sourceSha256() != null && proof.sourceSha256().matches("[a-f0-9]{64}");
        return new UsdFxPurchaseContext(code, market, quoteCurrency,
                verified ? proof.kind() : TradingRadarAssetProfileResolver.InstrumentKind.UNKNOWN, verified, data, true);
    }
    public static boolean requiresUsd(String code, String market, String quoteCurrency) {
        var proof = PolicyInstruments.VALUES.get(market + ":" + code);
        return "USD".equals(quoteCurrency) || "TWD".equals(quoteCurrency) && proof != null
                && "TWD".equals(proof.quoteCurrency()) && proof.kind() == TradingRadarAssetProfileResolver.InstrumentKind.BOND_ETF;
    }
    public boolean applies() {
        return evaluated && ("USD".equals(quoteCurrency) || "TWD".equals(quoteCurrency) && typeVerified
                && instrumentKind == TradingRadarAssetProfileResolver.InstrumentKind.BOND_ETF);
    }
    public Preference preference() {
        if (!applies()) return Preference.NOT_APPLICABLE;
        if (!("LIVE".equals(data.quality()) || "HISTORICAL_PROXY".equals(data.quality()))
                || data.currentRate() == null || data.currentRate().signum() <= 0) return Preference.UNKNOWN;
        if (data.currentRate().compareTo(LIMIT) > 0) return Preference.HIGH;
        HistoryRange h = data.history();
        if (h == null || !h.available() || h.low() == null || h.high() == null || h.high().compareTo(h.low()) <= 0)
            return Preference.UNKNOWN;
        return data.currentRate().subtract(h.low()).multiply(new BigDecimal("100"))
                .compareTo(h.high().subtract(h.low()).multiply(new BigDecimal("90"))) >= 0
                ? Preference.HIGH : Preference.NORMAL;
    }
    public boolean priceException(RadarCompletedPriceDrawdown.Context fact, BigDecimal price) {
        if (!typeVerified || !applies() || !("LIVE".equals(data.quality()) || "HISTORICAL_PROXY".equals(data.quality()))
                || data.currentRate() == null || data.currentRate().signum() <= 0
                || preference() == Preference.NORMAL || fact == null
                || !fact.validPriceFact(stockCode, market, price)) return false;
        boolean etf = instrumentKind == TradingRadarAssetProfileResolver.InstrumentKind.EQUITY_ETF
                || instrumentKind == TradingRadarAssetProfileResolver.InstrumentKind.BOND_ETF;
        int comparison = price.multiply(new BigDecimal("100")).compareTo(fact.high().multiply(new BigDecimal(etf ? "90" : "70")));
        return etf ? comparison < 0 : instrumentKind == TradingRadarAssetProfileResolver.InstrumentKind.STOCK && comparison <= 0;
    }
    public String disclosure(RadarCompletedPriceDrawdown.Context fact, BigDecimal price) {
        if (!applies()) return null;
        Preference preference = preference();
        String rate = data.currentRate() == null ? "未核實" : data.currentRate().toPlainString();
        String range = data.history() == null || !data.history().available()
                ? "兩年即期賣出來源或完整跨度未核實，區間條件未評"
                : "兩曆年窗口 " + data.history().windowStart() + "～" + data.history().windowEnd()
                    + "（完成日 " + data.history().completedAsOfDate() + "）即期賣出區間 " + data.history().low().toPlainString() + "～" + data.history().high().toPlainString()
                    + "（另有 " + data.history().excludedLegacyRows() + " 筆舊未核實紀錄未納入）";
        return "D-215 美元資產換匯偏好：即期賣出成本 " + rate
                + (data.currentAsOf() == null ? "" : "（" + data.currentSource() + "，" + data.currentAsOf() + "）")
                + "；" + range + "；"
                + (data.quality().startsWith("HISTORICAL_PROXY") ? "歷史完成日代理；缺當時可驗證來源時不作策略績效採納證據；" : "")
                + (preference == Preference.HIGH ? priceException(fact, price)
                    ? "標的價格回撤符合 FX 例外，可續評其他買進條件，並非已放行交易。 "
                    : "匯率成本偏高，優先等待；型別或價格例外未成立。 "
                    : preference == Preference.NORMAL ? "匯率偏好條件未觸發，其他買進條件仍須成立。 "
                    : priceException(fact, price)
                        ? "兩年區間仍未評；當前即期價已核實，標的回撤符合獨立 FX 例外，可續評其他條件。 "
                        : "當前或兩年匯率證據不足，買方優先等待。 ");
    }

    public static MarketData live(UsdTwdLiveRateService.LiveRate rate, Instant decisionInstant,
                                  LocalDate twCompletedMinimum, List<ExchangeRateHistory> rows, HistoryProof proof) {
        LocalDate completed = actualCompletedDate(rows, decisionInstant, twCompletedMinimum);
        LocalDate end = decisionInstant == null ? null : decisionInstant.atZone(TAIPEI).toLocalDate();
        HistoryProof boundProof = proof != null ? proof : aggregateHistoryProof(rows, end, completed, decisionInstant);
        HistoryRange range = history(rows, end, completed, boundProof);
        if (rate == null || decisionInstant == null || !"LIVE".equals(rate.quoteStatus()) || !"ACTIVE".equals(rate.liveUpdateStatus())
                || !("BANK_OF_TAIWAN".equals(rate.source()) || "MEGA_BANK".equals(rate.source()))
                || !"USD".equals(rate.baseCurrency()) || !"TWD".equals(rate.quoteCurrency())
                || rate.sellRate() == null || rate.buyRate() == null || rate.buyRate().signum() <= 0
                || rate.buyRate().compareTo(rate.sellRate()) >= 0 || rate.polledAt() == null
                || rate.polledAt().isAfter(decisionInstant) || !UsdTwdLiveRateService.isFresh(rate.polledAt(), decisionInstant)
                || rate.sourceUpdatedAt() != null && rate.sourceUpdatedAt().isAfter(decisionInstant)
                || !decisionInstant.atZone(TAIPEI).toLocalDate().equals(rate.date()))
            return new MarketData("UNAVAILABLE", null, null, rate == null ? null : rate.source(), range,
                    "USD_LIVE_BANK_SPOT_SELLING_UNVERIFIED");
        return new MarketData("LIVE", rate.sellRate(), rate.polledAt(), rate.source(), range, null);
    }
    /** Compatibility proxy evaluated at the supplied completed date's 17:00 cutoff. */
    public static MarketData historical(List<ExchangeRateHistory> rows, LocalDate completed) {
        Instant knownAt = completed == null ? null : completed.atTime(17, 0).atZone(TAIPEI).toInstant();
        return historical(rows, knownAt, completed);
    }
    public static MarketData historical(List<ExchangeRateHistory> rows, Instant knownAt, LocalDate twCompletedMinimum) {
        LocalDate completed = actualCompletedDate(rows, knownAt, twCompletedMinimum);
        LocalDate end = knownAt == null ? null : knownAt.atZone(TAIPEI).toLocalDate();
        var proof = aggregateHistoryProof(rows, end, completed, knownAt);
        var range = history(rows, end, completed, proof);
        ExchangeRateHistory current = rows == null || completed == null ? null : rows.stream()
                .filter(r -> r != null && completed.equals(r.getRateDate()) && "USD".equals(r.getCurrency()))
                .findFirst().orElse(null);
        boolean verified = current != null && current.isSpotBasisVerified() && current.getFetchedAt() != null
                && !current.getFetchedAt().isAfter(knownAt);
        return new MarketData(verified ? "HISTORICAL_PROXY" : "HISTORICAL_PROXY_UNAVAILABLE", verified ? current.getSellRate() : null,
                knownAt, verified ? current.getSource() : "CANONICAL_HISTORY_PROVIDER_UNRECORDED", range,
                verified ? null : "HISTORICAL_PROXY_SPOT_BASIS_UNVERIFIED");
    }
    /** Bank observations can exist on TW stock holidays; that calendar is only a freshness floor. */
    static LocalDate actualCompletedDate(List<ExchangeRateHistory> rows, Instant knownAt, LocalDate twCompletedMinimum) {
        LocalDate cutoff = completedClockCutoff(knownAt);
        if (rows == null || cutoff == null || twCompletedMinimum == null || twCompletedMinimum.isAfter(cutoff)) return null;
        LocalDate actual = rows.stream().filter(r -> r != null && "USD".equals(r.getCurrency())
                && r.getRateDate() != null && !r.getRateDate().isAfter(cutoff)
                && r.isSpotBasisVerified() && r.getFetchedAt() != null && !r.getFetchedAt().isAfter(knownAt))
                .map(ExchangeRateHistory::getRateDate).max(Comparator.naturalOrder()).orElse(null);
        return actual != null && !actual.isBefore(twCompletedMinimum) ? actual : null;
    }
    static LocalDate completedClockCutoff(Instant knownAt) {
        if (knownAt == null) return null;
        var local = knownAt.atZone(TAIPEI);
        return local.toLocalTime().isBefore(java.time.LocalTime.of(17, 0))
                ? local.toLocalDate().minusDays(1) : local.toLocalDate();
    }
    /** Only immutable raw-capture-backed spot rows can establish the new range; legacy rows stay unknown. */
    public static HistoryProof aggregateHistoryProof(List<ExchangeRateHistory> rows, LocalDate end,
                                                      LocalDate completed, Instant knownAt) {
        try {
            if (end == null || completed == null || knownAt == null || completed.isAfter(end)) return null;
            if (rows == null || rows.stream().anyMatch(r -> r == null || r.getRateDate() == null)) return null;
            for (var row : rows) if ("USD".equals(row.getCurrency()) && !row.getRateDate().isBefore(end.minusYears(2))
                    && !row.getRateDate().isAfter(completed) && row.getSourceCapture() != null
                    && (!row.isSpotBasisVerified() || row.getFetchedAt() == null || row.getFetchedAt().isAfter(knownAt))) return null;
            List<ExchangeRateHistory> selected = windowRows(rows.stream().filter(ExchangeRateHistory::isSpotBasisVerified).toList(), end, completed);
            if (selected.isEmpty() || !nativeQueryCoverage(selected, end.minusYears(2), completed)) return null;
            java.util.Set<String> sourceHashes = new java.util.TreeSet<>();
            for (var row : selected) {
                if (!row.isSpotBasisVerified() || !"BANK_SPOT_SELLING".equals(row.getRateBasis())
                        || row.getSource() == null || row.getSourceSha256() == null
                        || !row.getSourceSha256().matches("[a-f0-9]{64}") || row.getFetchedAt() == null
                        || row.getFetchedAt().isAfter(knownAt)) return null;
                sourceHashes.add(captureIdentity(row));
            }
            return new HistoryProof("VERIFIED", "BANK_SPOT_SELLING", "SPOT",
                    "IMMUTABLE_NATIVE_SPOT_SOURCE_CAPTURES", sha256(String.join("\n", sourceHashes).getBytes(StandardCharsets.UTF_8)),
                    rowsSha256(selected), end.minusYears(2), end, completed);
        } catch (RuntimeException invalid) { return null; }
    }
    private static String captureIdentity(ExchangeRateHistory row) {
        return row.getSource() + "|" + row.getRateBasis() + "|" + row.getSourceSha256() + "|"
                + row.getSourceCapture().getSourceUrl() + "|" + row.getFetchedAt();
    }
    /** A complete native query may legitimately omit weekend/holiday legacy dates; never price those unknown rows. */
    private static boolean nativeQueryCoverage(List<ExchangeRateHistory> rows, LocalDate start, LocalDate end) {
        java.util.Set<String> seenCaptures = new java.util.HashSet<>();
        java.util.List<LocalDate[]> intervals = new java.util.ArrayList<>();
        for (var row : rows) {
            if (!"FINMIND_TAIWAN_EXCHANGE_RATE".equals(row.getSource()) || row.getSourceCapture() == null) continue;
            String url = row.getSourceCapture().getSourceUrl();
            if (url == null) continue;
            if (!seenCaptures.add(captureIdentity(row))) continue;
            try {
                var uri = java.net.URI.create(url);
                if (!"https".equals(uri.getScheme()) || !"api.finmindtrade.com".equals(uri.getHost())
                        || !"/api/v4/data".equals(uri.getPath()) || uri.getRawQuery() == null) continue;
                Map<String, String> query = new HashMap<>();
                for (String pair : uri.getRawQuery().split("&")) {
                    String[] parts = pair.split("=", 2);
                    String key = java.net.URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
                    String value = parts.length == 2 ? java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
                    if (query.put(key, value) != null) throw new IllegalArgumentException();
                }
                if (!"TaiwanExchangeRate".equals(query.get("dataset")) || !"USD".equals(query.get("data_id"))) continue;
                LocalDate lower = LocalDate.parse(query.get("start_date")), upper = LocalDate.parse(query.get("end_date"));
                if (!lower.toString().equals(query.get("start_date")) || !upper.toString().equals(query.get("end_date"))) continue;
                if (!lower.isAfter(upper)) intervals.add(new LocalDate[]{lower, upper});
            } catch (RuntimeException unavailable) { /* An unparseable URL cannot prove query coverage. */ }
        }
        intervals.sort(Comparator.comparing(pair -> pair[0]));
        LocalDate needed = start;
        for (var interval : intervals) {
            if (interval[1].isBefore(needed)) continue;
            if (interval[0].isAfter(needed)) return false;
            if (!interval[1].isBefore(end)) return true;
            needed = interval[1].plusDays(1);
        }
        return false;
    }
    private static List<ExchangeRateHistory> windowRows(List<ExchangeRateHistory> rows, LocalDate end, LocalDate completed) {
        if (rows == null || rows.stream().anyMatch(r -> r == null || r.getRateDate() == null)) return List.of();
        List<ExchangeRateHistory> ordered = rows.stream().filter(r -> "USD".equals(r.getCurrency())
                && !r.getRateDate().isAfter(completed)).sorted(Comparator.comparing(ExchangeRateHistory::getRateDate)).toList();
        int boundary = -1;
        for (int i = 0; i < ordered.size(); i++) if (!ordered.get(i).getRateDate().isAfter(end.minusYears(2))) boundary = i;
        return boundary < 0 ? List.of() : ordered.subList(boundary, ordered.size());
    }
    public static HistoryRange history(List<ExchangeRateHistory> rows, LocalDate end, LocalDate completed, HistoryProof proof) {
        if (end == null || completed == null || completed.isAfter(end) || proof == null
                || !"VERIFIED".equals(proof.status()) || !"BANK_SPOT_SELLING".equals(proof.rateBasis())
                || !"SPOT".equals(proof.quoteType()) || proof.source() == null || proof.source().isBlank()
                || proof.sourceSha256() == null || !proof.sourceSha256().matches("[a-f0-9]{64}")
                || !end.minusYears(2).equals(proof.windowStart()) || !end.equals(proof.windowEnd())
                || !completed.equals(proof.completedAsOfDate()))
            return HistoryRange.unavailable(end, completed, "LEGACY_HISTORY_SPOT_BASIS_UNVERIFIED");
        try {
            boolean nativeProof = "IMMUTABLE_NATIVE_SPOT_SOURCE_CAPTURES".equals(proof.source());
            List<ExchangeRateHistory> selected = windowRows(nativeProof && rows != null
                    ? rows.stream().filter(r -> r != null && r.isSpotBasisVerified()).toList() : rows, end, completed);
            if (selected.isEmpty() || selected.get(0).getRateDate().isAfter(end.minusYears(2))
                    || !selected.get(selected.size() - 1).getRateDate().equals(completed)
                    || !rowsSha256(selected).equals(proof.rowsSha256())) throw new IllegalArgumentException();
            BigDecimal low = null, high = null; LocalDate previous = null;
            for (ExchangeRateHistory row : selected) {
                if (previous != null && !row.getRateDate().isAfter(previous) || row.getBuyRate() == null
                        || row.getSellRate() == null || row.getBuyRate().signum() <= 0
                        || row.getBuyRate().compareTo(row.getSellRate()) >= 0) throw new IllegalArgumentException();
                previous = row.getRateDate();
                if (!previous.isBefore(end.minusYears(2))) {
                    low = low == null ? row.getSellRate() : low.min(row.getSellRate());
                    high = high == null ? row.getSellRate() : high.max(row.getSellRate());
                }
            }
            if (low == null || high.compareTo(low) <= 0) throw new IllegalArgumentException();
            return new HistoryRange(true, end.minusYears(2), end, completed, low, high,
                    "BANK_SPOT_SELLING", proof.source(), null,
                    nativeProof && rows != null ? (int)rows.stream().filter(r -> r != null && "USD".equals(r.getCurrency())
                            && r.getRateDate() != null && !r.getRateDate().isBefore(end.minusYears(2))
                            && !r.getRateDate().isAfter(completed) && !r.isSpotBasisVerified()).count() : 0);
        } catch (RuntimeException invalid) {
            return HistoryRange.unavailable(end, completed, "USD_TWO_YEAR_SPOT_HISTORY_INVALID");
        }
    }
    public static String rowsSha256(List<ExchangeRateHistory> rows) {
        StringBuilder text = new StringBuilder();
        for (var r : rows) text.append(r.getRateDate()).append('|').append(r.getBuyRate().stripTrailingZeros().toPlainString())
                .append('|').append(r.getSellRate().stripTrailingZeros().toPlainString()).append('\n');
        return sha256(text.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
    }
    private static class PolicyInstruments {
        private static final Map<String, VerifiedInstrument> VALUES = load();
        private static Map<String, VerifiedInstrument> load() {
            try (var modelStream = UsdFxPurchaseContext.class.getResourceAsStream("/srpp/decision/model_inputs.json");
                 var manifestStream = UsdFxPurchaseContext.class.getResourceAsStream("/srpp/decision/policy_manifest.json")) {
                if (modelStream == null || manifestStream == null) return Map.of();
                byte[] bytes = modelStream.readAllBytes(); ObjectMapper mapper = new ObjectMapper();
                JsonNode model = mapper.readTree(bytes), manifest = mapper.readTree(manifestStream);
                if (!sha256(bytes).equals(manifest.path("files").path("assumptions/model_inputs.json").asText())) return Map.of();
                Map<String, VerifiedInstrument> result = new HashMap<>();
                for (JsonNode row : model.path("execution_policy").path("usd_asset_fx_preference_d215").path("verified_instruments")) {
                    String source = row.path("source_document").asText(), hash = row.path("source_sha256").asText();
                    if (!hash.matches("[a-f0-9]{64}") || !hash.equals(manifest.path("files").path(source).asText())) return Map.of();
                    var kind = TradingRadarAssetProfileResolver.InstrumentKind.valueOf(row.path("instrument_kind").asText());
                    var item = new VerifiedInstrument(row.path("stock_code").asText(), row.path("market").asText(),
                            row.path("quote_currency").asText(), kind, source, hash);
                    if (result.put(item.market() + ":" + item.stockCode(), item) != null) return Map.of();
                }
                return Map.copyOf(result);
            } catch (Exception invalid) { return Map.of(); }
        }
    }
}
