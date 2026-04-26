package com.steven.assets.service;

import com.steven.assets.dto.StockAlertDto;
import com.steven.assets.model.StockAlert;
import com.steven.assets.model.StockPrice;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.repository.StockAlertRepository;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.StockPriceRepository;
import com.steven.assets.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockAlertService {

    private final StockAlertRepository alertRepo;
    private final StockPriceRepository priceRepo;
    private final StockPriceHistoryRepository historyRepo;
    private final StockRepository stockMasterRepo;

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
                .threshold(req.getThreshold())
                .active(req.getActive() != null ? req.getActive() : true)
                .displayOrder(maxOrder + 1)
                .build();
        if (req.getStockName() != null && !req.getStockName().isBlank()) {
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
        if (req.getStockName() != null && !req.getStockName().isBlank()) {
            stockMasterRepo.upsert(code, req.getMarket(), req.getStockName().trim());
        }
        alert.setAlertType(req.getAlertType());
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

    private void evaluate(StockAlert alert) {
        try {
            Optional<StockPrice> priceOpt = priceRepo.findByStockCodeAndMarket(alert.getStockCode(), alert.getMarket());
            if (priceOpt.isEmpty()) return;
            double currentPrice = priceOpt.get().getPrice().doubleValue();

            // 24-hour cooldown
            if (alert.getLastTriggeredAt() != null &&
                    alert.getLastTriggeredAt().isAfter(LocalDateTime.now().minusHours(24))) {
                return;
            }

            boolean triggered = switch (alert.getAlertType()) {
                case "QUARTERLY_MA_ABOVE_PCT" -> checkMaDeviation(alert, currentPrice, 60, true);
                case "QUARTERLY_MA_BELOW_PCT" -> checkMaDeviation(alert, currentPrice, 60, false);
                case "ANNUAL_MA_ABOVE_PCT"    -> checkMaDeviation(alert, currentPrice, 240, true);
                case "ANNUAL_MA_BELOW_PCT"    -> checkMaDeviation(alert, currentPrice, 240, false);
                case "KD_ABOVE"              -> checkKdValue(alert, false, true);
                case "KD_BELOW"              -> checkKdValue(alert, false, false);
                case "KD_D_ABOVE"            -> checkKdValue(alert, true, true);
                case "KD_D_BELOW"            -> checkKdValue(alert, true, false);
                case "PRICE_ABOVE"           -> currentPrice >= alert.getThreshold().doubleValue();
                case "PRICE_BELOW"           -> currentPrice <= alert.getThreshold().doubleValue();
                default -> false;
            };

            if (triggered) {
                LocalDate tradingDate = historyRepo.findMaxTradingDate(
                        alert.getStockCode(), alert.getMarket()).orElse(LocalDate.now());
                java.time.LocalTime closeTime = "美股".equals(alert.getMarket())
                        ? java.time.LocalTime.of(16, 0) : java.time.LocalTime.of(13, 30);
                alert.setLastTriggeredAt(tradingDate.atTime(closeTime));
                alert.setLastTriggeredPrice(BigDecimal.valueOf(currentPrice));
                alertRepo.save(alert);
                return;
            }

            // 補抓：今天 close 沒觸發、但最近 3 個交易日內可能在「盤中」跨過門檻
            // （cron 5 分鐘採樣會漏掉短暫尖峰）。用每天的日內 HIGH/LOW 重算指標。
            if (alert.getLastTriggeredAt() == null) {
                findRecentIntradayTrigger(alert, 3).ifPresent(m -> {
                    java.time.LocalTime closeTime = "美股".equals(alert.getMarket())
                            ? java.time.LocalTime.of(16, 0) : java.time.LocalTime.of(13, 30);
                    alert.setLastTriggeredAt(m.date.atTime(closeTime));
                    alert.setLastTriggeredPrice(m.price);
                    alert.setLastTriggeredMaValue(m.ma);
                    alert.setLastTriggeredKdValue(m.k);
                    alert.setLastTriggeredDValue(m.d);
                    alertRepo.save(alert);
                });
            }
        } catch (Exception e) {
            log.warn("Error evaluating alert {}: {}", alert.getId(), e.getMessage());
        }
    }

    private record IntradayMatch(LocalDate date, BigDecimal price,
                                  BigDecimal ma, BigDecimal k, BigDecimal d) {}

    /** 在最近 N 個交易日內，用日內 HIGH/LOW 補抓盤中可能觸發的時點。 */
    private Optional<IntradayMatch> findRecentIntradayTrigger(StockAlert alert, int recentDays) {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusYears(2);
        List<StockPriceHistory> asc = historyRepo
                .findByStockCodeAndMarketAndTradingDateBetweenOrderByTradingDateAsc(
                        alert.getStockCode(), alert.getMarket(), start, end);
        if (asc.isEmpty()) return Optional.empty();

        // cutoff = 第 N 個最近交易日
        if (asc.size() < recentDays) return Optional.empty();
        LocalDate cutoff = asc.get(asc.size() - recentDays).getTradingDate();

        String type = alert.getAlertType();
        double threshold = alert.getThreshold().doubleValue();
        IntradayMatch last = null;

        if ("PRICE_ABOVE".equals(type) || "PRICE_BELOW".equals(type)) {
            boolean above = "PRICE_ABOVE".equals(type);
            for (StockPriceHistory h : asc) {
                if (h.getTradingDate().isBefore(cutoff)) continue;
                BigDecimal probe = above
                        ? (h.getHighPrice() != null ? h.getHighPrice() : h.getClosePrice())
                        : (h.getLowPrice() != null ? h.getLowPrice() : h.getClosePrice());
                double p = probe.doubleValue();
                if ((above && p >= threshold) || (!above && p <= threshold)) {
                    last = new IntradayMatch(h.getTradingDate(), probe, null, null, null);
                }
            }
            return Optional.ofNullable(last);
        }

        if (type.startsWith("KD_")) {
            boolean useD = type.startsWith("KD_D_");
            boolean above = type.endsWith("_ABOVE");
            int period = 9;
            if (asc.size() < period) return Optional.empty();
            double k = 50, d = 50;
            for (int i = period - 1; i < asc.size(); i++) {
                StockPriceHistory today = asc.get(i);
                List<StockPriceHistory> window = asc.subList(i - period + 1, i + 1);
                double highest = window.stream().mapToDouble(h -> h.getHighPrice() != null
                        ? h.getHighPrice().doubleValue() : h.getClosePrice().doubleValue())
                        .max().orElse(0);
                double lowest = window.stream().mapToDouble(h -> h.getLowPrice() != null
                        ? h.getLowPrice().doubleValue() : h.getClosePrice().doubleValue())
                        .min().orElse(0);

                double prevK = k, prevD = d;
                // close-based K：propagate state
                double rsvClose = (highest == lowest) ? 50
                        : (today.getClosePrice().doubleValue() - lowest) / (highest - lowest) * 100;
                k = prevK * 2.0 / 3 + rsvClose / 3.0;
                d = prevD * 2.0 / 3 + k / 3.0;

                if (today.getTradingDate().isBefore(cutoff)) continue;

                // 盤中 K_peak：用該日 HIGH（above）或 LOW（below）當 close
                double probe = above
                        ? (today.getHighPrice() != null ? today.getHighPrice().doubleValue() : today.getClosePrice().doubleValue())
                        : (today.getLowPrice() != null ? today.getLowPrice().doubleValue() : today.getClosePrice().doubleValue());
                double rsvProbe = (highest == lowest) ? 50
                        : (probe - lowest) / (highest - lowest) * 100;
                double kProbe = prevK * 2.0 / 3 + rsvProbe / 3.0;
                double dProbe = prevD * 2.0 / 3 + kProbe / 3.0;
                double check = useD ? Math.max(d, dProbe) : Math.max(k, kProbe);
                double checkLow = useD ? Math.min(d, dProbe) : Math.min(k, kProbe);
                boolean hit = above ? check >= threshold : checkLow <= threshold;
                if (hit) {
                    BigDecimal triggerPrice = above
                            ? (today.getHighPrice() != null ? today.getHighPrice() : today.getClosePrice())
                            : (today.getLowPrice()  != null ? today.getLowPrice()  : today.getClosePrice());
                    BigDecimal kSnap = BigDecimal.valueOf(useD ? Math.max(d, dProbe) : Math.max(k, kProbe))
                            .setScale(2, java.math.RoundingMode.HALF_UP);
                    BigDecimal kKVal = BigDecimal.valueOf(Math.max(k, kProbe)).setScale(2, java.math.RoundingMode.HALF_UP);
                    BigDecimal dDVal = BigDecimal.valueOf(Math.max(d, dProbe)).setScale(2, java.math.RoundingMode.HALF_UP);
                    last = new IntradayMatch(today.getTradingDate(), triggerPrice, null, kKVal, dDVal);
                }
            }
            return Optional.ofNullable(last);
        }

        if (type.startsWith("QUARTERLY_MA_") || type.startsWith("ANNUAL_MA_")) {
            int days = type.startsWith("QUARTERLY") ? 60 : 240;
            boolean above = type.endsWith("_ABOVE_PCT");
            if (asc.size() < days) return Optional.empty();
            double sum = 0;
            for (int i = 0; i < days; i++) sum += asc.get(i).getClosePrice().doubleValue();
            for (int i = days - 1; i < asc.size(); i++) {
                StockPriceHistory today = asc.get(i);
                double ma = sum / days;
                if (!today.getTradingDate().isBefore(cutoff)) {
                    // 用該日 HIGH（above）或 LOW（below）當盤中極端值來判定
                    BigDecimal probe = above
                            ? (today.getHighPrice() != null ? today.getHighPrice() : today.getClosePrice())
                            : (today.getLowPrice()  != null ? today.getLowPrice()  : today.getClosePrice());
                    double p = probe.doubleValue();
                    double thresholdPrice = ma * (1 + (above ? threshold : -threshold) / 100.0);
                    if ((above && p >= thresholdPrice) || (!above && p <= thresholdPrice)) {
                        last = new IntradayMatch(today.getTradingDate(), probe,
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

    private List<StockPriceHistory> withTodayIfMissing(List<StockPriceHistory> desc, String code, String market) {
        LocalDate today = java.time.LocalDate.now();
        if (!desc.isEmpty() && desc.get(0).getTradingDate().equals(today)) return desc;
        return priceRepo.findByStockCodeAndMarket(code, market)
                .filter(sp -> sp.getTradingDate() != null && sp.getTradingDate().equals(today))
                .map(sp -> {
                    StockPriceHistory t = StockPriceHistory.builder()
                            .stockCode(code).market(market).tradingDate(today)
                            .closePrice(sp.getPrice()).highPrice(sp.getPrice()).lowPrice(sp.getPrice())
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
        String name = stockMasterRepo.findByCodeAndMarket(a.getStockCode(), a.getMarket())
                .map(s -> s.getName()).orElse(a.getStockCode());
        r.setStockName(name);
        r.setMarket(a.getMarket());
        r.setAlertType(a.getAlertType());
        r.setThreshold(a.getThreshold());
        r.setActive(a.getActive());
        // 觸發欄位只顯示最近 3 個交易日內的；超過就視為過期不傳
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

    private String buildLabel(StockAlert a) {
        double thr = a.getThreshold().doubleValue();
        return switch (a.getAlertType()) {
            case "QUARTERLY_MA_ABOVE_PCT" -> String.format("高於季線 %.0f%%", thr);
            case "QUARTERLY_MA_BELOW_PCT" -> String.format("低於季線 %.0f%%", thr);
            case "ANNUAL_MA_ABOVE_PCT"    -> thr == 0 ? "高於年線" : String.format("高於年線 %.0f%%", thr);
            case "ANNUAL_MA_BELOW_PCT"    -> thr == 0 ? "低於年線" : String.format("低於年線 %.0f%%", thr);
            case "KD_ABOVE"              -> String.format("K 值高於 %.0f", thr);
            case "KD_BELOW"              -> String.format("K 值低於 %.0f", thr);
            case "KD_D_ABOVE"            -> String.format("D 值高於 %.0f", thr);
            case "KD_D_BELOW"            -> String.format("D 值低於 %.0f", thr);
            case "PRICE_ABOVE"           -> String.format("股價高於 %s", a.getThreshold().stripTrailingZeros().toPlainString());
            case "PRICE_BELOW"           -> String.format("股價低於 %s", a.getThreshold().stripTrailingZeros().toPlainString());
            default -> a.getAlertType();
        };
    }
}
