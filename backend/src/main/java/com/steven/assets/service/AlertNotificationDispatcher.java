package com.steven.assets.service;

import com.steven.assets.model.NotificationRecipient;
import com.steven.assets.model.StockAlert;
import com.steven.assets.repository.NotificationRecipientRepository;
import com.steven.assets.repository.StockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
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

    private final NotificationRecipientRepository recipientRepo;
    private final EmailService emailService;
    private final StockRepository stockMasterRepo;

    private final ConcurrentLinkedQueue<PendingTrigger> queue = new ConcurrentLinkedQueue<>();

    public void enqueue(StockAlert alert, LocalDateTime triggeredAt, BigDecimal price,
                        BigDecimal maValue, BigDecimal kValue, BigDecimal dValue) {
        try {
            String stockName = resolveStockName(alert);
            queue.offer(new PendingTrigger(
                    alert.getStockCode(),
                    alert.getMarket(),
                    stockName,
                    StockAlertService.buildLabel(alert),
                    triggeredAt,
                    price,
                    maValue,
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

        if (!emailService.isEnabled()) {
            log.warn("EmailService disabled，丟棄 {} 筆警示通知", batch.size());
            return;
        }

        List<String> recipients = recipientRepo.findByActiveTrueOrderByCreatedAtAsc()
                .stream().map(NotificationRecipient::getEmail).toList();
        if (recipients.isEmpty()) {
            log.warn("無啟用中的通知收件人，丟棄 {} 筆警示通知", batch.size());
            return;
        }

        String subject = String.format("[資產管理] 股票警示觸發 %d 筆", batch.size());
        String body = buildDigestBody(batch);
        emailService.send(recipients, subject, body);
    }

    private List<PendingTrigger> drain() {
        List<PendingTrigger> out = new ArrayList<>();
        PendingTrigger t;
        while ((t = queue.poll()) != null) out.add(t);
        return out;
    }

    private String buildDigestBody(List<PendingTrigger> batch) {
        StringBuilder sb = new StringBuilder();
        sb.append("您訂閱的股票觀察清單於以下時點觸發警示條件：\n\n");
        for (int i = 0; i < batch.size(); i++) {
            PendingTrigger t = batch.get(i);
            sb.append(String.format("%d. %s (%s %s) — %s%n",
                    i + 1, t.stockName, t.stockCode, t.market, t.conditionLabel));
            sb.append("   觸發時間：").append(t.triggeredAt == null ? "-" : TS_FMT.format(t.triggeredAt)).append('\n');
            sb.append("   股價：").append(formatNumber(t.price)).append('\n');
            if (t.maValue != null) sb.append("   均線：").append(formatNumber(t.maValue)).append('\n');
            if (t.kValue != null || t.dValue != null) {
                sb.append("   KD：K ").append(formatNumber(t.kValue))
                  .append(" / D ").append(formatNumber(t.dValue)).append('\n');
            }
            sb.append('\n');
        }
        sb.append("— 資產管理系統");
        return sb.toString();
    }

    private static String formatNumber(BigDecimal n) {
        if (n == null) return "-";
        return n.stripTrailingZeros().toPlainString();
    }

    private String resolveStockName(StockAlert alert) {
        if ("0000".equals(alert.getStockCode()) && "台股".equals(alert.getMarket())) {
            return "台股大盤";
        }
        return stockMasterRepo.findByCodeAndMarket(alert.getStockCode(), alert.getMarket())
                .map(s -> s.getName())
                .orElse(alert.getStockCode());
    }

    private record PendingTrigger(
            String stockCode,
            String market,
            String stockName,
            String conditionLabel,
            LocalDateTime triggeredAt,
            BigDecimal price,
            BigDecimal maValue,
            BigDecimal kValue,
            BigDecimal dValue) {}
}
