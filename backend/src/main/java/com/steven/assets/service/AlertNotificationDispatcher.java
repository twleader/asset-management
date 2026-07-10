package com.steven.assets.service;

import com.steven.assets.model.StockAlert;
import com.steven.assets.model.StockAlertTrigger;
import com.steven.assets.repository.StockAlertRecipientRepository;
import com.steven.assets.repository.StockAlertRepository;
import com.steven.assets.repository.StockAlertTriggerRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.util.MarketZones;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 警示觸發通知 dispatcher（Requirement 23）。
 * StockAlertService.recordTrigger() 把觸發塞進 queue，本服務每 60 秒 flush 一次，
 * 同一輪內多筆觸發合併成單一 digest email。
 *
 * 失敗策略：任何階段（讀收件人 / 寄信）失敗一律 log.warn 不拋；queue 一律清空，
 * 即使無收件人或 email service disabled，避免 queue 無限長大。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AlertNotificationDispatcher {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /** 單封 digest 內嵌圖片張數上限（每檔最多 2 張：年圖＋當日分時圖）；達上限後其餘檔僅文字，避免信件過大。 */
    private static final int MAX_CHARTS = 20;
    /** 收盤後仍允許寄送警示 email 的寬限分鐘數（容納 60s flush 延遲與 cron 採樣落後）。 */
    private static final int SEND_GRACE_MINUTES = 10;

    private final StockAlertRecipientRepository recipientLinkRepo;
    private final EmailService emailService;
    private final StockRepository stockMasterRepo;
    private final StockAlertTriggerRepository triggerRepo;
    private final StockAlertRepository alertRepo;
    private final AlertChartRenderer chartRenderer;
    private final MarketDataService marketDataService;

    private final ConcurrentLinkedQueue<PendingTrigger> queue = new ConcurrentLinkedQueue<>();

    public void enqueue(StockAlert alert, LocalDateTime triggeredAt, BigDecimal price,
                        BigDecimal monthlyMa, BigDecimal quarterlyMa, BigDecimal annualMa,
                        BigDecimal kValue, BigDecimal dValue) {
        try {
            String stockName = resolveStockName(alert.getStockCode(), alert.getMarket());
            queue.offer(new PendingTrigger(
                    alert.getId(),
                    alert.getStockCode(),
                    alert.getMarket(),
                    stockName,
                    StockAlertService.buildLabel(alert),
                    triggeredAt,
                    price,
                    monthlyMa,
                    quarterlyMa,
                    annualMa,
                    kValue,
                    dValue));
        } catch (Exception e) {
            log.warn("enqueue 警示通知失敗 alert {}: {}", alert.getId(), e.getMessage());
        }
    }

    /** 每 60 秒 flush 一次，合併同期觸發成單封 digest。 */
    @Scheduled(fixedDelay = 60_000L, initialDelay = 60_000L)
    public void flush() {
        List<PendingTrigger> batch = drain();
        if (batch.isEmpty()) return;

        // 寄送時段閘門：警示 email 僅於各市場「開盤 ~ 收盤後 10 分鐘」寄出。
        // 逐筆依其市場時區判定，盤外市場的觸發本輪丟棄（queue 已 drain 不回填；StockAlertTrigger
        // 歷史已落地，使用者可按「補發」重寄）。多市場混批時只寄仍在盤中的市場 —— 例：台股已收盤
        // 9 小時、美股 / 英股仍盤中時，本輪只寄美股 / 英股，台股丟棄。
        List<PendingTrigger> sendable = batch.stream()
                .filter(t -> withinSendWindow(t.market))
                .toList();
        if (sendable.size() < batch.size()) {
            log.info("盤外時段略過 {} 筆警示 email，本輪寄出 {} 筆", batch.size() - sendable.size(), sendable.size());
        }
        if (sendable.isEmpty()) return;

        if (!emailService.isEnabled()) {
            log.warn("EmailService disabled，丟棄 {} 筆警示通知", sendable.size());
            return;
        }

        // Task 125：以收件人為單位分組——每筆觸發只進「該警示選定且 active」的收件人，
        // 每位收件人各組一封僅含其訂閱觸發的 digest（單一收件人寄出，順帶保護彼此 email 隱私）。
        Map<String, List<PendingTrigger>> byRecipient = groupByRecipient(sendable);
        if (byRecipient.isEmpty()) {
            log.warn("本輪 {} 筆觸發無任何啟用收件人訂閱，丟棄", sendable.size());
            return;
        }

        Map<String, Optional<byte[]>> chartCache = new LinkedHashMap<>();   // "price|intraday "+stockKey → PNG，跨收件人共用避免重複 render
        for (Map.Entry<String, List<PendingTrigger>> e : byRecipient.entrySet()) {
            DigestMail mail = buildDigest(e.getValue(), chartCache);   // 同股票多條件合併成一筆，內嵌年圖＋當日分時圖
            String subject = String.format("[資產管理] 股票警示觸發 %d 筆", mail.stockCount());
            emailService.sendHtml(List.of(e.getKey()), subject, mail.html(), mail.inlineImages());
        }
    }

    /**
     * 把觸發清單反轉成 {@code Map<收件人 email, 該收件人訂閱的觸發清單>}（保留首次出現順序）。
     * 每筆觸發以其 {@code alertId} 查「選定 ∩ active」收件人 email；同 alert 多筆觸發在本輪以 cache 不重查。
     */
    private Map<String, List<PendingTrigger>> groupByRecipient(List<PendingTrigger> triggers) {
        Map<Long, List<String>> emailCache = new HashMap<>();
        LinkedHashMap<String, List<PendingTrigger>> byRecipient = new LinkedHashMap<>();
        for (PendingTrigger t : triggers) {
            List<String> emails = emailCache.computeIfAbsent(t.alertId, this::recipientsFor);
            for (String email : emails) {
                byRecipient.computeIfAbsent(email, k -> new ArrayList<>()).add(t);
            }
        }
        return byRecipient;
    }

    /** 某警示「選定 ∩ active=true」的收件人 email；查詢失敗 / alertId 為 null 時回空清單（不阻斷其他收件人）。 */
    private List<String> recipientsFor(Long alertId) {
        if (alertId == null) return List.of();
        try {
            return recipientLinkRepo.findActiveEmailsByAlertId(alertId);
        } catch (Exception e) {
            log.warn("查警示 {} 收件人失敗：{}", alertId, e.getMessage());
            return List.of();
        }
    }

    /**
     * 手動補發（觀察清單「補發」按鈕）：把指定市場「最後交易日」當天觸發的事件彙整成單封 digest 重寄（Task 128）。
     * 與自動 flush 共用 buildDigest（同格式），但不入 queue、不寫 cooldown —— 純手動全量重寄。
     *
     * @param market 限定補發的單一市場（台股 / 美股 / 英股，由觀察頁當前 tab 帶入）；null / 空白則 fallback
     *               全市場（`triggerRepo.findDistinctMarkets()`），向後相容直接打 API 全補的用法。
     */
    public ResendResult resendLastTradingDay(String market) {
        if (!emailService.isEnabled()) return new ResendResult(ResendStatus.EMAIL_DISABLED, 0, 0);

        List<String> markets = (market == null || market.isBlank())
                ? triggerRepo.findDistinctMarkets()
                : List.of(market);
        List<PendingTrigger> batch = new ArrayList<>();
        for (String m : markets) {
            LocalDate day = lastTradingDate(m);
            List<StockAlertTrigger> rows = triggerRepo.findByMarketAndTriggeredAtInDay(
                    m, day.atStartOfDay(), day.plusDays(1).atStartOfDay());
            for (StockAlertTrigger t : rows) batch.add(toPending(t));
        }
        if (batch.isEmpty()) return new ResendResult(ResendStatus.NO_EVENTS, 0, 0);

        // Task 125：以收件人為單位各寄一封（每位只含其訂閱警示的觸發）。
        Map<String, List<PendingTrigger>> byRecipient = groupByRecipient(batch);
        if (byRecipient.isEmpty()) return new ResendResult(ResendStatus.NO_RECIPIENTS, 0, 0);

        Map<String, Optional<byte[]>> chartCache = new LinkedHashMap<>();
        for (Map.Entry<String, List<PendingTrigger>> e : byRecipient.entrySet()) {
            DigestMail mail = buildDigest(e.getValue(), chartCache);
            String subject = String.format("[資產管理] 股票警示補發 %d 筆", mail.stockCount());
            emailService.sendHtml(List.of(e.getKey()), subject, mail.html(), mail.inlineImages());
        }
        // 回傳「補發涵蓋的去重股票檔數」＋「實際寄達的收件人數」（per-recipient 後兩者皆有意義，前端提示用）
        int distinctStocks = (int) batch.stream()
                .map(t -> t.stockCode + " " + t.market).distinct().count();
        return new ResendResult(ResendStatus.SENT, distinctStocks, byRecipient.size());
    }

    /** trigger 列 → PendingTrigger：回查 alert 還原條件文案（孤兒觸發 fallback「警示觸發」）。 */
    private PendingTrigger toPending(StockAlertTrigger t) {
        String label = alertRepo.findById(t.getAlertId())
                .map(StockAlertService::buildLabel)
                .orElse("警示觸發");
        return new PendingTrigger(
                t.getAlertId(),
                t.getStockCode(), t.getMarket(),
                resolveStockName(t.getStockCode(), t.getMarket()), label,
                t.getTriggeredAt(), t.getPrice(),
                t.getMonthlyMa(), t.getQuarterlyMa(), t.getAnnualMa(),
                t.getKValue(), t.getDValue());
    }

    /**
     * 各市場「最後交易日」：交易日且已過開盤＝當日；盤前 / 週末 / 國定假日則回溯至最近交易日。
     * 交易日判定委派 {@link MarketDataService#isTradingDay}（與抓價 / 觸發共用同一假日表；
     * 開盤時刻 台 09:00 / 美 09:30 / 英 08:00）。
     */
    private LocalDate lastTradingDate(String market) {
        ZoneId zone = MarketZones.resolve(market);
        LocalTime open = MarketZones.openTime(market);
        ZonedDateTime nowZ = ZonedDateTime.now(zone);
        LocalDate day = nowZ.toLocalDate();
        if (nowZ.toLocalTime().isBefore(open)) day = day.minusDays(1); // 盤前 → 前一交易日
        while (!marketDataService.isTradingDay(market, day)) {          // 跳過週末與國定假日
            day = day.minusDays(1);
        }
        return day;
    }

    /**
     * 該市場此刻是否在「可寄送警示 email」時段：市場時區交易日，且 open ≤ now ≤ close + {@link #SEND_GRACE_MINUTES} 分。
     * 開收盤時刻取自 {@link MarketZones}（單一來源）；交易日（含國定假日判定）委派
     * {@link MarketDataService#isTradingDay}，與 computeTriggeredAt / lastTradingDate / 抓價排程共用同一假日表。
     */
    private boolean withinSendWindow(String market) {
        ZoneId zone = MarketZones.resolve(market);
        ZonedDateTime nowZ = ZonedDateTime.now(zone);
        if (!marketDataService.isTradingDay(market, nowZ.toLocalDate())) return false; // 週末 / 國定假日不寄
        LocalTime nowT = nowZ.toLocalTime();
        LocalTime open = MarketZones.openTime(market);
        LocalTime close = MarketZones.closeTime(market).plusMinutes(SEND_GRACE_MINUTES);
        return !nowT.isBefore(open) && !nowT.isAfter(close);
    }

    public enum ResendStatus { SENT, NO_EVENTS, NO_RECIPIENTS, EMAIL_DISABLED }

    /** @param count 去重後股票檔數；@param recipientCount 實際寄達的收件人數（per-recipient；Task 125） */
    public record ResendResult(ResendStatus status, int count, int recipientCount) {}

    private List<PendingTrigger> drain() {
        List<PendingTrigger> out = new ArrayList<>();
        PendingTrigger t;
        while ((t = queue.poll()) != null) out.add(t);
        return out;
    }

    /** HTML digest 本文 + 內嵌走勢圖（cid → PNG bytes）+ 去重後股票檔數。 */
    private record DigestMail(String html, Map<String, byte[]> inlineImages, int stockCount) {}

    /**
     * 組單封 digest（給單一收件人）。{@code chartCache}（前綴命名空間 + stockKey → PNG）跨收件人共用，
     * 同一檔股票的年圖與當日分時圖各只 render 一次；empty 代表已試過但渲染失敗 / 無資料，不重試。
     */
    private DigestMail buildDigest(List<PendingTrigger> batch, Map<String, Optional<byte[]>> chartCache) {
        LinkedHashMap<String, List<PendingTrigger>> grouped = groupByStock(batch);
        Map<String, byte[]> images = new LinkedHashMap<>();
        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"font-family:'Helvetica Neue',Arial,'PingFang TC','Microsoft JhengHei',sans-serif;")
          .append("color:#0f172a;font-size:14px;line-height:1.7\">");
        sb.append("<p>您訂閱的股票觀察清單於以下時點觸發警示條件：</p>");
        int i = 0;
        for (List<PendingTrigger> group : grouped.values()) {
            i++;
            // 同一股票多條件 → 合併成一筆：條件 label 去重串接，技術快照（時間/股價/MA/KD）取最近一筆觸發
            PendingTrigger latest = group.get(0);
            LinkedHashSet<String> labels = new LinkedHashSet<>();
            for (PendingTrigger t : group) {
                if (t.triggeredAt != null
                        && (latest.triggeredAt == null || t.triggeredAt.isAfter(latest.triggeredAt))) {
                    latest = t;
                }
                labels.add(t.conditionLabel);
            }
            sb.append("<div style=\"margin:0 0 22px;padding:12px 14px;border:1px solid #e2e8f0;border-radius:8px\">");
            sb.append("<div style=\"font-weight:700;font-size:15px;margin-bottom:6px\">")
              .append(i).append(". ").append(esc(latest.stockName))
              .append(" (").append(esc(latest.stockCode)).append(' ').append(esc(latest.market)).append(") — ")
              .append(esc(String.join("、", labels))).append("</div>");
            sb.append(row("觸發時間", latest.triggeredAt == null ? "-" : TS_FMT.format(latest.triggeredAt)));
            // 觸發股價＝觸發當下價（與圖內 legend 的「最新收盤」語意不同，故保留為文字）。
            // 月線 / 季線 / 年線 / KD 數值已全部入圖（PNG legend），文字不再重複列出。
            sb.append(row("觸發股價", formatNumber(latest.price)));
            // 內嵌走勢圖（失敗則略過圖、文字照寄）；每封信內嵌圖數設上限，超過僅文字避免信過大。
            // 每檔最多兩張：年圖（近一年 股價+MA+KD）＋當日分時圖（分時價格線+昨收基準線+漲跌色）。
            // 以 stockKey（各自前綴命名空間）快取 PNG，跨收件人 / 同封內同股不重複 render。
            if (images.size() < MAX_CHARTS) {
                String code = latest.stockCode;
                String market = latest.market;
                // 年圖
                Optional<byte[]> png = chartCache.computeIfAbsent(
                        "price " + code + " " + market, k -> chartRenderer.renderPriceMaPng(code, market));
                if (png.isPresent()) {
                    String cid = "chart" + i;
                    images.put(cid, png.get());
                    sb.append("<img src=\"cid:").append(cid).append("\" alt=\"走勢圖\" ")
                      .append("style=\"display:block;margin-top:10px;max-width:100%;width:900px;height:auto;")
                      .append("border:1px solid #eee;border-radius:6px\"/>");
                }
                // 當日分時圖（無當日 tick / 繪圖失敗則略過此圖，年圖與文字照寄）
                Optional<byte[]> intraday = chartCache.computeIfAbsent(
                        "intraday " + code + " " + market, k -> chartRenderer.renderIntradayPng(code, market));
                if (intraday.isPresent()) {
                    String cid = "intraday" + i;
                    images.put(cid, intraday.get());
                    sb.append("<img src=\"cid:").append(cid).append("\" alt=\"當日分時走勢圖\" ")
                      .append("style=\"display:block;margin-top:10px;max-width:100%;width:900px;height:auto;")
                      .append("border:1px solid #eee;border-radius:6px\"/>");
                }
            }
            sb.append("</div>");
        }
        if (grouped.size() > MAX_CHARTS) {
            log.info("digest 內嵌走勢圖達上限 {}，其餘 {} 檔僅文字", MAX_CHARTS, grouped.size() - MAX_CHARTS);
        }
        sb.append("<p style=\"color:#64748b\">— 資產管理系統</p></div>");
        return new DigestMail(sb.toString(), images, grouped.size());
    }

    private static String row(String label, String value) {
        return "<div><span style=\"color:#64748b\">" + label + "：</span>" + esc(value) + "</div>";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** 同一股票（stockCode+market）的觸發合併成一組，保留首次出現順序（= digest 顯示順序）。 */
    private static LinkedHashMap<String, List<PendingTrigger>> groupByStock(List<PendingTrigger> batch) {
        LinkedHashMap<String, List<PendingTrigger>> grouped = new LinkedHashMap<>();
        for (PendingTrigger t : batch) {
            grouped.computeIfAbsent(t.stockCode + " " + t.market, k -> new ArrayList<>()).add(t);
        }
        return grouped;
    }

    private static String formatNumber(BigDecimal n) {
        if (n == null) return "-";
        return n.stripTrailingZeros().toPlainString();
    }

    private String resolveStockName(String code, String market) {
        if ("0000".equals(code) && "台股".equals(market)) {
            return "台股大盤";
        }
        return stockMasterRepo.findByCodeAndMarket(code, market)
                .map(s -> s.getName())
                .orElse(code);
    }

    private record PendingTrigger(
            Long alertId,
            String stockCode,
            String market,
            String stockName,
            String conditionLabel,
            LocalDateTime triggeredAt,
            BigDecimal price,
            BigDecimal monthlyMa,
            BigDecimal quarterlyMa,
            BigDecimal annualMa,
            BigDecimal kValue,
            BigDecimal dValue) {}
}
