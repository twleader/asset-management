package com.steven.assets.service;

import com.steven.assets.dto.StockAlertDto;
import com.steven.assets.model.StockAlert;
import com.steven.assets.model.StockAlertTrigger;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.repository.StockAlertRepository;
import com.steven.assets.repository.StockAlertTriggerRepository;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockAlertService {

    private final StockAlertRepository alertRepo;
    private final StockAlertTriggerRepository triggerRepo;
    private final PriceQueryService priceQuery;
    private final StockPriceHistoryRepository historyRepo;
    private final StockRepository stockMasterRepo;
    private final HistoricalDataService historicalDataService;
    private final TechnicalIndicatorService indicatorService;

    // ===== CRUD =====

    public List<StockAlertDto.Response> findAll() {
        return alertRepo.findAllByOrderByDisplayOrderAsc().stream().map(this::toResponse).toList();
    }

    @Transactional
    public StockAlertDto.Response create(StockAlertDto.Request req) {
        // 新增排在最後
        int maxOrder = alertRepo.findAllByOrderByDisplayOrderAsc().stream()
                .mapToInt(a -> a.getDisplayOrder() != null ? a.getDisplayOrder() : 0)
                .max().orElse(0);
        String code = req.getStockCode().trim().toUpperCase();
        StockAlert alert = StockAlert.builder()
                .stockCode(code)
                .market(req.getMarket())
                .alertType(req.getAlertType())
                .maPeriod(req.getMaPeriod())
                .threshold(req.getThreshold())
                .active(req.getActive() != null ? req.getActive() : true)
                .displayOrder(maxOrder + 1)
                .build();
        // 0000 = 台股大盤：不寫入 stock 主檔（避免被排程當真股票抓價，價格走 twse_index_daily_history）
        boolean isTaiex = "0000".equals(code) && "台股".equals(req.getMarket());
        if (!isTaiex && req.getStockName() != null && !req.getStockName().isBlank()) {
            stockMasterRepo.upsert(code, req.getMarket(), req.getStockName().trim());
        }
        return toResponse(alertRepo.save(alert));
    }

    @Transactional
    public void reorder(List<Long> orderedIds) {
        for (int i = 0; i < orderedIds.size(); i++) {
            Long id = orderedIds.get(i);
            alertRepo.findById(id).ifPresent(a -> {
                a.setDisplayOrder(orderedIds.indexOf(id));
                alertRepo.save(a);
            });
        }
    }

    @Transactional
    public StockAlertDto.Response update(Long id, StockAlertDto.Request req) {
        StockAlert alert = alertRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Alert not found: " + id));
        String code = req.getStockCode().trim().toUpperCase();
        alert.setStockCode(code);
        alert.setMarket(req.getMarket());
        boolean isTaiex = "0000".equals(code) && "台股".equals(req.getMarket());
        if (!isTaiex && req.getStockName() != null && !req.getStockName().isBlank()) {
            stockMasterRepo.upsert(code, req.getMarket(), req.getStockName().trim());
        }
        alert.setAlertType(req.getAlertType());
        alert.setMaPeriod(req.getMaPeriod());
        alert.setThreshold(req.getThreshold());
        if (req.getActive() != null) alert.setActive(req.getActive());
        return toResponse(alertRepo.save(alert));
    }

    @Transactional
    public void delete(Long id) {
        alertRepo.deleteById(id);
    }

    @Transactional
    public StockAlertDto.Response toggleActive(Long id) {
        StockAlert alert = alertRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Alert not found: " + id));
        alert.setActive(!alert.getActive());
        return toResponse(alertRepo.save(alert));
    }

    // ===== Alert Check（由 StockPriceService 於每次股價更新後呼叫）=====

    public void checkAlerts() {
        List<StockAlert> actives = alertRepo.findByActiveTrue();
        if (actives.isEmpty()) return;
        log.info("檢查 {} 個到價警示", actives.size());
        actives.forEach(this::evaluate);
    }

    /**
     * 只比對單一股票的警示（由 Redis pub/sub price-update 訊息觸發，
     * 每次 price-service 寫一筆 Redis 都會 fire 一次，避免每次都 scan 所有 active alerts）。
     */
    public void checkAlertsFor(String stockCode, String market) {
        if (stockCode == null || market == null) return;
        List<StockAlert> matching = alertRepo.findByActiveTrue().stream()
                .filter(a -> stockCode.equals(a.getStockCode()) && market.equals(a.getMarket()))
                .toList();
        if (matching.isEmpty()) return;
        matching.forEach(this::evaluate);
    }

    private void evaluate(StockAlert alert) {
        try {
            Optional<PriceQueryService.LivePrice> priceOpt = priceQuery.getLive(alert.getStockCode(), alert.getMarket());
            if (priceOpt.isEmpty() || priceOpt.get().price() == null) return;
            double currentPrice = priceOpt.get().price().doubleValue();

            // 24-hour cooldown
            if (alert.getLastTriggeredAt() != null &&
                    alert.getLastTriggeredAt().isAfter(LocalDateTime.now().minusHours(24))) {
                return;
            }

            boolean triggered = switch (alert.getAlertType()) {
                case "MA_ABOVE_PCT" -> alert.getMaPeriod() != null
                        && checkMaDeviation(alert, currentPrice, alert.getMaPeriod(), true);
                case "MA_BELOW_PCT" -> alert.getMaPeriod() != null
                        && checkMaDeviation(alert, currentPrice, alert.getMaPeriod(), false);
                case "KD_ABOVE"              -> checkKdValue(alert, false, true);
                case "KD_BELOW"              -> checkKdValue(alert, false, false);
                case "KD_D_ABOVE"            -> checkKdValue(alert, true, true);
                case "KD_D_BELOW"            -> checkKdValue(alert, true, false);
                case "PRICE_ABOVE"           -> currentPrice >= alert.getThreshold().doubleValue();
                case "PRICE_BELOW"           -> currentPrice <= alert.getThreshold().doubleValue();
                default -> false;
            };

            if (triggered) {
                LocalDateTime triggeredAt = computeTriggeredAt(alert);
                alert.setLastTriggeredAt(triggeredAt);
                alert.setLastTriggeredPrice(BigDecimal.valueOf(currentPrice));
                // 不論觸發原因為何，把當下 MA / KD / D 一併凍結進 alert，UI 才能完整顯示
                try {
                    TechnicalIndicatorService.FullIndicators ind = indicatorService.computeAll(
                            alert.getStockCode(), alert.getMarket());
                    BigDecimal ma = pickMaForAlert(alert.getAlertType(), alert.getMaPeriod(), ind);
                    if (ma != null) alert.setLastTriggeredMaValue(ma);
                    if (ind.k() != null) alert.setLastTriggeredKdValue(ind.k());
                    if (ind.d() != null) alert.setLastTriggeredDValue(ind.d());
                } catch (Exception e) {
                    log.warn("快照觸發指標失敗 alert {}: {}", alert.getId(), e.getMessage());
                }
                alertRepo.save(alert);
                recordTrigger(alert, triggeredAt, BigDecimal.valueOf(currentPrice));
                return;
            }

            // 補抓：最近 3 個交易日內可能在盤中跨過門檻（cron 5 分鐘採樣會漏短暫尖峰）。
            // 從 Yahoo Finance 抓 5 分鐘 K 線，找到條件第一次成立的精確時點。
            if (alert.getLastTriggeredAt() == null) {
                findRecentIntradayTrigger(alert, 3).ifPresent(m -> {
                    alert.setLastTriggeredAt(m.time);
                    alert.setLastTriggeredPrice(m.price);
                    alert.setLastTriggeredMaValue(m.ma);
                    alert.setLastTriggeredKdValue(m.k);
                    alert.setLastTriggeredDValue(m.d);
                    alertRepo.save(alert);
                    recordTrigger(alert, m.time, m.price);
                });
            }
        } catch (Exception e) {
            log.warn("Error evaluating alert {}: {}", alert.getId(), e.getMessage());
        }
    }

    /** 依 maPeriod 挑出對應的 MA（20=月線、60=季線、240=年線）；非 MA 類型或無 maPeriod 時回 quarterly 當預設。 */
    private static BigDecimal pickMaForAlert(String type, Integer maPeriod,
                                              TechnicalIndicatorService.FullIndicators ind) {
        if (maPeriod == null) return ind.quarterlyMa();
        return switch (maPeriod) {
            case 20 -> ind.monthlyMa();
            case 60 -> ind.quarterlyMa();
            case 240 -> ind.annualMa();
            default -> ind.quarterlyMa();
        };
    }

    private record IntradayMatch(LocalDateTime time, BigDecimal price,
                                  BigDecimal ma, BigDecimal k, BigDecimal d) {}

    /**
     * 在最近 N 個交易日內，用 Yahoo Finance 5 分鐘 K 線資料找到條件第一次成立的精確時點。
     * 抓不到 5m 資料時 fallback 用日內 HIGH/LOW，時間用該日 13:30/16:00 收盤時間（粗估）。
     */
    private Optional<IntradayMatch> findRecentIntradayTrigger(StockAlert alert, int recentDays) {
        List<StockPriceHistory> asc = historyRepo
                .findByStockCodeAndMarketAndTradingDateBetweenOrderByTradingDateAsc(
                        alert.getStockCode(), alert.getMarket(),
                        LocalDate.now().minusYears(2), LocalDate.now());
        if (asc.size() < 9) return Optional.empty();

        int N = Math.min(recentDays, asc.size());
        LocalDate cutoff = asc.get(asc.size() - N).getTradingDate();
        String type = alert.getAlertType();
        Integer maPeriod = alert.getMaPeriod();
        double threshold = alert.getThreshold().doubleValue();

        // 抓 5 分鐘 K 線（多抓一天保險）
        List<HistoricalDataService.IntradayBar> bars = historicalDataService
                .fetchIntraday5m(alert.getStockCode(), alert.getMarket(), N + 1)
                .stream()
                .filter(b -> !b.time().toLocalDate().isBefore(cutoff))
                .toList();

        Optional<IntradayMatch> precise = bars.isEmpty()
                ? Optional.empty()
                : matchInIntradayBars(asc, bars, cutoff, type, maPeriod, threshold);
        if (precise.isPresent()) return precise;

        // Fallback: 日內 HIGH/LOW 粗估，時間錨在該日收盤
        return matchInDailyOhlc(asc, cutoff, alert.getMarket(), type, maPeriod, threshold);
    }

    /** 用 5 分鐘 K 線精確定位觸發時點。 */
    private Optional<IntradayMatch> matchInIntradayBars(
            List<StockPriceHistory> asc,
            List<HistoricalDataService.IntradayBar> bars,
            LocalDate cutoff, String type, Integer maPeriod, double threshold) {

        if ("PRICE_ABOVE".equals(type) || "PRICE_BELOW".equals(type)) {
            boolean above = "PRICE_ABOVE".equals(type);
            for (var bar : bars) {
                BigDecimal probe = above ? bar.high() : bar.low();
                if (probe == null) continue;
                double p = probe.doubleValue();
                if ((above && p >= threshold) || (!above && p <= threshold)) {
                    return Optional.of(new IntradayMatch(bar.time(), probe, null, null, null));
                }
            }
            return Optional.empty();
        }

        if (type.startsWith("KD_")) {
            boolean useD = type.startsWith("KD_D_");
            boolean above = type.endsWith("_ABOVE");
            // 走訪每日歷史，計算每天「收盤後」的 K/D 狀態，存到 map：date → {K, D}
            Map<LocalDate, double[]> endOfDayKD = new HashMap<>();
            double k = 50, d = 50;
            for (int i = 8; i < asc.size(); i++) {
                List<StockPriceHistory> w = asc.subList(i - 8, i + 1);
                double hi = w.stream().mapToDouble(h -> h.getHighPrice() != null
                        ? h.getHighPrice().doubleValue() : h.getClosePrice().doubleValue()).max().orElse(0);
                double lo = w.stream().mapToDouble(h -> h.getLowPrice() != null
                        ? h.getLowPrice().doubleValue() : h.getClosePrice().doubleValue()).min().orElse(0);
                double rsv = (hi == lo) ? 50
                        : (asc.get(i).getClosePrice().doubleValue() - lo) / (hi - lo) * 100;
                k = k * 2.0 / 3 + rsv / 3.0;
                d = d * 2.0 / 3 + k / 3.0;
                endOfDayKD.put(asc.get(i).getTradingDate(), new double[]{k, d});
            }
            // 走訪 5m bars，按日切組
            Map<LocalDate, List<HistoricalDataService.IntradayBar>> grouped = new TreeMap<>();
            for (var b : bars) grouped.computeIfAbsent(b.time().toLocalDate(), x -> new ArrayList<>()).add(b);
            for (var entry : grouped.entrySet()) {
                LocalDate day = entry.getKey();
                LocalDate prev = previousTradingDate(asc, day);
                if (prev == null || !endOfDayKD.containsKey(prev)) continue;
                double prevK = endOfDayKD.get(prev)[0];
                double prevD = endOfDayKD.get(prev)[1];
                // 8 天前期 high/low（從 prev 往前 8 天，含 prev）
                double[] prevHL = priorEightDayHL(asc, prev);
                if (prevHL == null) continue;
                double dayHi = Double.NEGATIVE_INFINITY, dayLo = Double.POSITIVE_INFINITY;
                for (var bar : entry.getValue()) {
                    if (bar.high() != null) dayHi = Math.max(dayHi, bar.high().doubleValue());
                    if (bar.low()  != null) dayLo = Math.min(dayLo, bar.low().doubleValue());
                    if (bar.close() == null) continue;
                    double winHi = Math.max(prevHL[0], dayHi == Double.NEGATIVE_INFINITY ? bar.close().doubleValue() : dayHi);
                    double winLo = Math.min(prevHL[1], dayLo == Double.POSITIVE_INFINITY ? bar.close().doubleValue() : dayLo);
                    double rsv = (winHi == winLo) ? 50
                            : (bar.close().doubleValue() - winLo) / (winHi - winLo) * 100;
                    double kBar = prevK * 2.0 / 3 + rsv / 3.0;
                    double dBar = prevD * 2.0 / 3 + kBar / 3.0;
                    double check = useD ? dBar : kBar;
                    boolean hit = above ? check >= threshold : check <= threshold;
                    if (hit) {
                        return Optional.of(new IntradayMatch(bar.time(), bar.close(), null,
                                BigDecimal.valueOf(kBar).setScale(2, java.math.RoundingMode.HALF_UP),
                                BigDecimal.valueOf(dBar).setScale(2, java.math.RoundingMode.HALF_UP)));
                    }
                }
            }
            return Optional.empty();
        }

        if (type.startsWith("MA_") && maPeriod != null) {
            int days = maPeriod;
            boolean above = type.endsWith("_ABOVE_PCT");
            // 預先算每天的 MA{days}
            Map<LocalDate, Double> maByDay = new HashMap<>();
            if (asc.size() < days) return Optional.empty();
            double sum = 0;
            for (int i = 0; i < days; i++) sum += asc.get(i).getClosePrice().doubleValue();
            for (int i = days - 1; i < asc.size(); i++) {
                maByDay.put(asc.get(i).getTradingDate(), sum / days);
                if (i + 1 < asc.size()) {
                    sum -= asc.get(i - days + 1).getClosePrice().doubleValue();
                    sum += asc.get(i + 1).getClosePrice().doubleValue();
                }
            }
            for (var bar : bars) {
                LocalDate day = bar.time().toLocalDate();
                Double ma = maByDay.get(day);
                if (ma == null) {
                    // 今天 MA 還沒寫入歷史 → 用前一交易日的 MA 近似
                    LocalDate prev = previousTradingDate(asc, day);
                    if (prev != null) ma = maByDay.get(prev);
                }
                if (ma == null) continue;
                BigDecimal probe = above ? bar.high() : bar.low();
                if (probe == null) continue;
                double p = probe.doubleValue();
                double thresholdPrice = ma * (1 + (above ? threshold : -threshold) / 100.0);
                if ((above && p >= thresholdPrice) || (!above && p <= thresholdPrice)) {
                    return Optional.of(new IntradayMatch(bar.time(), probe,
                            BigDecimal.valueOf(ma).setScale(2, java.math.RoundingMode.HALF_UP),
                            null, null));
                }
            }
            return Optional.empty();
        }

        return Optional.empty();
    }

    /** Fallback：5m 抓不到時用日內 HIGH/LOW 粗估，時間錨在該日收盤。 */
    private Optional<IntradayMatch> matchInDailyOhlc(
            List<StockPriceHistory> asc, LocalDate cutoff,
            String market, String type, Integer maPeriod, double threshold) {
        java.time.LocalTime closeTime = "美股".equals(market)
                ? java.time.LocalTime.of(16, 0) : java.time.LocalTime.of(13, 30);
        IntradayMatch last = null;

        if ("PRICE_ABOVE".equals(type) || "PRICE_BELOW".equals(type)) {
            boolean above = "PRICE_ABOVE".equals(type);
            for (StockPriceHistory h : asc) {
                if (h.getTradingDate().isBefore(cutoff)) continue;
                BigDecimal probe = above
                        ? (h.getHighPrice() != null ? h.getHighPrice() : h.getClosePrice())
                        : (h.getLowPrice()  != null ? h.getLowPrice()  : h.getClosePrice());
                double p = probe.doubleValue();
                if ((above && p >= threshold) || (!above && p <= threshold)) {
                    last = new IntradayMatch(h.getTradingDate().atTime(closeTime), probe, null, null, null);
                }
            }
            return Optional.ofNullable(last);
        }

        if (type.startsWith("KD_")) {
            boolean useD = type.startsWith("KD_D_");
            boolean above = type.endsWith("_ABOVE");
            double k = 50, d = 50;
            for (int i = 8; i < asc.size(); i++) {
                StockPriceHistory today = asc.get(i);
                List<StockPriceHistory> w = asc.subList(i - 8, i + 1);
                double hi = w.stream().mapToDouble(h -> h.getHighPrice() != null
                        ? h.getHighPrice().doubleValue() : h.getClosePrice().doubleValue()).max().orElse(0);
                double lo = w.stream().mapToDouble(h -> h.getLowPrice() != null
                        ? h.getLowPrice().doubleValue() : h.getClosePrice().doubleValue()).min().orElse(0);
                double prevK = k, prevD = d;
                double rsvClose = (hi == lo) ? 50
                        : (today.getClosePrice().doubleValue() - lo) / (hi - lo) * 100;
                k = prevK * 2.0 / 3 + rsvClose / 3.0;
                d = prevD * 2.0 / 3 + k / 3.0;
                if (today.getTradingDate().isBefore(cutoff)) continue;
                double probe = above
                        ? (today.getHighPrice() != null ? today.getHighPrice().doubleValue() : today.getClosePrice().doubleValue())
                        : (today.getLowPrice()  != null ? today.getLowPrice().doubleValue()  : today.getClosePrice().doubleValue());
                double rsvProbe = (hi == lo) ? 50 : (probe - lo) / (hi - lo) * 100;
                double kProbe = prevK * 2.0 / 3 + rsvProbe / 3.0;
                double dProbe = prevD * 2.0 / 3 + kProbe / 3.0;
                double check = useD ? Math.max(d, dProbe) : Math.max(k, kProbe);
                double checkLow = useD ? Math.min(d, dProbe) : Math.min(k, kProbe);
                boolean hit = above ? check >= threshold : checkLow <= threshold;
                if (hit) {
                    BigDecimal triggerPrice = above
                            ? (today.getHighPrice() != null ? today.getHighPrice() : today.getClosePrice())
                            : (today.getLowPrice()  != null ? today.getLowPrice()  : today.getClosePrice());
                    last = new IntradayMatch(today.getTradingDate().atTime(closeTime), triggerPrice, null,
                            BigDecimal.valueOf(Math.max(k, kProbe)).setScale(2, java.math.RoundingMode.HALF_UP),
                            BigDecimal.valueOf(Math.max(d, dProbe)).setScale(2, java.math.RoundingMode.HALF_UP));
                }
            }
            return Optional.ofNullable(last);
        }

        if (type.startsWith("MA_") && maPeriod != null) {
            int days = maPeriod;
            boolean above = type.endsWith("_ABOVE_PCT");
            if (asc.size() < days) return Optional.empty();
            double sum = 0;
            for (int i = 0; i < days; i++) sum += asc.get(i).getClosePrice().doubleValue();
            for (int i = days - 1; i < asc.size(); i++) {
                StockPriceHistory today = asc.get(i);
                double ma = sum / days;
                if (!today.getTradingDate().isBefore(cutoff)) {
                    BigDecimal probe = above
                            ? (today.getHighPrice() != null ? today.getHighPrice() : today.getClosePrice())
                            : (today.getLowPrice()  != null ? today.getLowPrice()  : today.getClosePrice());
                    double p = probe.doubleValue();
                    double thresholdPrice = ma * (1 + (above ? threshold : -threshold) / 100.0);
                    if ((above && p >= thresholdPrice) || (!above && p <= thresholdPrice)) {
                        last = new IntradayMatch(today.getTradingDate().atTime(closeTime), probe,
                                BigDecimal.valueOf(ma).setScale(2, java.math.RoundingMode.HALF_UP),
                                null, null);
                    }
                }
                if (i + 1 < asc.size()) {
                    sum -= asc.get(i - days + 1).getClosePrice().doubleValue();
                    sum += asc.get(i + 1).getClosePrice().doubleValue();
                }
            }
            return Optional.ofNullable(last);
        }
        return Optional.empty();
    }

    private LocalDate previousTradingDate(List<StockPriceHistory> asc, LocalDate day) {
        for (int i = asc.size() - 1; i >= 0; i--) {
            if (asc.get(i).getTradingDate().isBefore(day)) return asc.get(i).getTradingDate();
        }
        return null;
    }

    /** 取出前 8 個交易日（含指定日）的 HIGH 最大值 / LOW 最小值。 */
    private double[] priorEightDayHL(List<StockPriceHistory> asc, LocalDate inclusiveDay) {
        int idx = -1;
        for (int i = asc.size() - 1; i >= 0; i--) {
            if (asc.get(i).getTradingDate().equals(inclusiveDay)) { idx = i; break; }
        }
        if (idx < 7) return null;
        double hi = Double.NEGATIVE_INFINITY, lo = Double.POSITIVE_INFINITY;
        for (int i = idx - 7; i <= idx; i++) {
            StockPriceHistory h = asc.get(i);
            double H = h.getHighPrice() != null ? h.getHighPrice().doubleValue() : h.getClosePrice().doubleValue();
            double L = h.getLowPrice()  != null ? h.getLowPrice().doubleValue()  : h.getClosePrice().doubleValue();
            if (H > hi) hi = H;
            if (L < lo) lo = L;
        }
        return new double[]{hi, lo};
    }

    private List<StockPriceHistory> withTodayIfMissing(List<StockPriceHistory> desc, String code, String market) {
        LocalDate today = java.time.LocalDate.now();
        if (!desc.isEmpty() && desc.get(0).getTradingDate().equals(today)) return desc;
        return priceQuery.getLive(code, market)
                .filter(lp -> lp.tradingDate() != null && today.toString().equals(lp.tradingDate()))
                .map(lp -> {
                    StockPriceHistory t = StockPriceHistory.builder()
                            .stockCode(code).market(market).tradingDate(today)
                            .closePrice(lp.price()).highPrice(lp.price()).lowPrice(lp.price())
                            .build();
                    List<StockPriceHistory> r = new java.util.ArrayList<>();
                    r.add(t);
                    r.addAll(desc);
                    return r;
                })
                .orElse(desc);
    }

    private boolean checkMaDeviation(StockAlert alert, double currentPrice, int days, boolean above) {
        List<StockPriceHistory> history = withTodayIfMissing(
                historyRepo.findRecentN(alert.getStockCode(), alert.getMarket(), days),
                alert.getStockCode(), alert.getMarket());
        if (history.size() < days / 2) return false; // 資料不足

        double ma = history.stream()
                .mapToDouble(h -> h.getClosePrice().doubleValue())
                .average().orElse(0);
        if (ma == 0) return false;

        double pct = alert.getThreshold().doubleValue();
        double threshold = ma * (1 + (above ? pct : -pct) / 100.0);

        boolean triggered = above ? currentPrice >= threshold : currentPrice <= threshold;
        if (triggered) {
            alert.setLastTriggeredMaValue(BigDecimal.valueOf(ma).setScale(2, java.math.RoundingMode.HALF_UP));
        }
        return triggered;
    }

    /**
     * 計算並檢查 KD 值，觸發時將 K/D 值回存到 alert
     * @param useD  true = 檢查 D 值；false = 檢查 K 值
     * @param above true = 高於門檻；false = 低於門檻
     */
    private boolean checkKdValue(StockAlert alert, boolean useD, boolean above) {
        // 需至少 40 天資料計算穩定 KD
        List<StockPriceHistory> history = withTodayIfMissing(
                historyRepo.findRecentN(alert.getStockCode(), alert.getMarket(), 60),
                alert.getStockCode(), alert.getMarket());
        if (history.size() < 9) return false;

        // history 是降序，反轉成升序計算
        List<StockPriceHistory> asc = history.reversed();
        double[] kd = calculateKD(asc);
        double value = useD ? kd[1] : kd[0];

        double thr = alert.getThreshold().doubleValue();
        boolean triggered = above ? value >= thr : value <= thr;
        if (triggered) {
            // 無論觸發的是 K 還是 D，都同時記錄 K 和 D 值
            alert.setLastTriggeredKdValue(BigDecimal.valueOf(kd[0]).setScale(2, java.math.RoundingMode.HALF_UP));
            alert.setLastTriggeredDValue(BigDecimal.valueOf(kd[1]).setScale(2, java.math.RoundingMode.HALF_UP));
        }
        return triggered;
    }

    /** 計算 KD 值，回傳 [K, D] */
    private double[] calculateKD(List<StockPriceHistory> asc) {
        double k = 50;
        double d = 50;
        int period = 9;
        for (int i = period - 1; i < asc.size(); i++) {
            List<StockPriceHistory> window = asc.subList(i - period + 1, i + 1);
            double highest = window.stream().mapToDouble(h -> h.getHighPrice() != null
                    ? h.getHighPrice().doubleValue() : h.getClosePrice().doubleValue()).max().orElse(0);
            double lowest  = window.stream().mapToDouble(h -> h.getLowPrice() != null
                    ? h.getLowPrice().doubleValue() : h.getClosePrice().doubleValue()).min().orElse(0);
            double rsv = (highest == lowest) ? 50
                    : (asc.get(i).getClosePrice().doubleValue() - lowest) / (highest - lowest) * 100;
            k = k * 2.0 / 3 + rsv / 3.0;
            d = d * 2.0 / 3 + k  / 3.0;
        }
        return new double[]{k, d};
    }

    // ===== Mapping =====

    private StockAlertDto.Response toResponse(StockAlert a) {
        StockAlertDto.Response r = new StockAlertDto.Response();
        r.setId(a.getId());
        r.setStockCode(a.getStockCode());
        String name;
        if ("0000".equals(a.getStockCode()) && "台股".equals(a.getMarket())) {
            name = "台股大盤";
        } else {
            name = stockMasterRepo.findByCodeAndMarket(a.getStockCode(), a.getMarket())
                    .map(s -> s.getName()).orElse(a.getStockCode());
        }
        r.setStockName(name);
        r.setMarket(a.getMarket());
        r.setAlertType(a.getAlertType());
        r.setMaPeriod(a.getMaPeriod());
        r.setThreshold(a.getThreshold());
        r.setActive(a.getActive());
        // 觸發時間 / 股價 / MA / K / D：全部用觸發時凍結值，最近 3 個交易日內的才傳；超過就視為過期不傳
        java.time.LocalDateTime cutoff = recentTradingDayCutoff(a.getMarket(), 3);
        if (a.getLastTriggeredAt() != null
                && (cutoff == null || !a.getLastTriggeredAt().isBefore(cutoff))) {
            r.setLastTriggeredAt(a.getLastTriggeredAt());
            r.setLastTriggeredPrice(a.getLastTriggeredPrice());
            r.setLastTriggeredMaValue(a.getLastTriggeredMaValue());
            r.setLastTriggeredKdValue(a.getLastTriggeredKdValue());
            r.setLastTriggeredDValue(a.getLastTriggeredDValue());
        }
        r.setCreatedAt(a.getCreatedAt());
        r.setConditionLabel(buildLabel(a));
        return r;
    }

    private java.time.LocalDateTime recentTradingDayCutoff(String market, int n) {
        List<java.time.LocalDate> dates = historyRepo.findDistinctTradingDatesByMarket(
                market, org.springframework.data.domain.PageRequest.of(0, n));
        if (dates.size() < n) return null;
        return dates.get(dates.size() - 1).atStartOfDay();
    }

    /**
     * 觸發時間一律落在「該市場交易時段內」：
     *  - 若 cron 偵測時剛好在交易時段內（市場時區） → 用 LocalDateTime.now()
     *  - 否則（盤後 / 假日） → 退回最近一筆交易日的收盤時間（max(history.tradingDate)@close）
     * 設計目的：cron 5 分鐘採樣常在收盤後幾分鐘才偵測到當日收盤觸發，
     * 直接用 now() 會顯示 13:35 / 16:05 等盤外時間，與「到價」語意不符。
     */
    private LocalDateTime computeTriggeredAt(StockAlert alert) {
        boolean isUs = "美股".equals(alert.getMarket());
        ZoneId zone = isUs ? ZoneId.of("America/New_York") : ZoneId.of("Asia/Taipei");
        LocalTime open = isUs ? LocalTime.of(9, 30) : LocalTime.of(9, 0);
        LocalTime close = isUs ? LocalTime.of(16, 0) : LocalTime.of(13, 30);

        ZonedDateTime nowZ = ZonedDateTime.now(zone);
        DayOfWeek dow = nowZ.getDayOfWeek();
        boolean isWeekday = dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
        LocalTime nowT = nowZ.toLocalTime();
        if (isWeekday && !nowT.isBefore(open) && !nowT.isAfter(close)) {
            // 用市場時區的 wall time，與其他路徑（Yahoo intraday bar, tradingDate.atTime）一致
            return nowZ.toLocalDateTime();
        }

        return historyRepo.findMaxTradingDate(alert.getStockCode(), alert.getMarket())
                .map(d -> d.atTime(close))
                .orElseGet(() -> nowZ.toLocalDateTime());
    }

    /**
     * 寫入觸發歷史紀錄。5 個技術指標欄位皆無條件計算填寫（不論觸發類型），
     * 便於事後追蹤觸發當下的完整技術面狀態。保留 30 天。
     */
    private void recordTrigger(StockAlert alert, LocalDateTime triggeredAt, BigDecimal price) {
        try {
            TechnicalIndicatorService.FullIndicators ind = indicatorService.computeAll(
                    alert.getStockCode(), alert.getMarket());
            triggerRepo.save(StockAlertTrigger.builder()
                    .alertId(alert.getId())
                    .stockCode(alert.getStockCode())
                    .market(alert.getMarket())
                    .triggeredAt(triggeredAt)
                    .price(price)
                    .monthlyMa(ind.monthlyMa())
                    .quarterlyMa(ind.quarterlyMa())
                    .annualMa(ind.annualMa())
                    .kValue(ind.k())
                    .dValue(ind.d())
                    .build());
        } catch (Exception e) {
            log.warn("recordTrigger failed for alert {}: {}", alert.getId(), e.getMessage());
        }
    }

    /**
     * 每日 04:00 (Asia/Taipei) 清理 30 天前的觸發紀錄。
     */
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Taipei")
    @Transactional
    public void cleanupOldTriggers() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        int deleted = triggerRepo.deleteByCreatedAtBefore(cutoff);
        if (deleted > 0) log.info("清理 {} 筆 30 天前的警示觸發紀錄", deleted);
    }

    /** 公開為 static：WatchStockService 在組裝「警示條件」欄時共用同一份文案。 */
    public static String buildLabel(StockAlert a) {
        double thr = a.getThreshold().doubleValue();
        return switch (a.getAlertType()) {
            case "MA_ABOVE_PCT" -> thr == 0
                    ? String.format("高於%s", maPeriodName(a.getMaPeriod()))
                    : String.format("高於%s %.0f%%", maPeriodName(a.getMaPeriod()), thr);
            case "MA_BELOW_PCT" -> thr == 0
                    ? String.format("低於%s", maPeriodName(a.getMaPeriod()))
                    : String.format("低於%s %.0f%%", maPeriodName(a.getMaPeriod()), thr);
            case "KD_ABOVE"              -> String.format("K 值高於 %.0f", thr);
            case "KD_BELOW"              -> String.format("K 值低於 %.0f", thr);
            case "KD_D_ABOVE"            -> String.format("D 值高於 %.0f", thr);
            case "KD_D_BELOW"            -> String.format("D 值低於 %.0f", thr);
            case "PRICE_ABOVE"           -> String.format("股價高於 %s", a.getThreshold().stripTrailingZeros().toPlainString());
            case "PRICE_BELOW"           -> String.format("股價低於 %s", a.getThreshold().stripTrailingZeros().toPlainString());
            default -> a.getAlertType();
        };
    }

    /** maPeriod → 顯示名稱（20=月線、60=季線、240=年線，其他則回「MA{n}」）。 */
    private static String maPeriodName(Integer period) {
        if (period == null) return "均線";
        return switch (period) {
            case 20 -> "月線";
            case 60 -> "季線";
            case 240 -> "年線";
            default -> "MA" + period;
        };
    }
}
