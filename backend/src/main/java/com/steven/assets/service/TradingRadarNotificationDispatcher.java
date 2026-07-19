package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.repository.TradingRadarNotificationRecipientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/** 將短時間內的交易雷達狀態轉入通知依收件人合併寄送。 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TradingRadarNotificationDispatcher {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TradingRadarNotificationRecipientRepository recipientRepo;
    private final EmailService emailService;
    private final ConcurrentLinkedQueue<Notice> queue = new ConcurrentLinkedQueue<>();

    public void enqueue(Long settingId, TradingRadarDto.StockDecision decision, List<String> enteredStates) {
        if (settingId == null || decision == null || enteredStates == null || enteredStates.isEmpty()) return;
        queue.offer(new Notice(
                settingId,
                decision.stockCode(),
                decision.stockName(),
                decision.actionLabel(),
                decision.counterTrendLabel(),
                decision.score(),
                decision.price(),
                decision.changePercent(),
                decision.priceUpdatedAt(),
                List.copyOf(enteredStates),
                decision.reasons(),
                decision.risks(),
                decision.counterTrendReasons(),
                decision.counterTrendRisks()));
    }

    /**
     * 由 {@link TradingRadarNotificationService#runCycle()} 在評估交易 commit 後於同一輪呼叫。
     * 刻意不掛 {@code @Scheduled}：獨立計時器會與評估競爭，把同一輪通知拆成多封信。
     */
    public void flush() {
        List<Notice> batch = drain();
        if (batch.isEmpty()) return;
        if (!emailService.isEnabled()) {
            log.warn("EmailService disabled，略過 {} 筆交易雷達狀態通知", batch.size());
            return;
        }

        Map<String, List<Notice>> byRecipient = new LinkedHashMap<>();
        Map<Long, List<String>> emailCache = new LinkedHashMap<>();
        for (Notice notice : batch) {
            List<String> emails = emailCache.computeIfAbsent(
                    notice.settingId(), recipientRepo::findActiveEmails);
            for (String email : emails) {
                byRecipient.computeIfAbsent(email, ignored -> new ArrayList<>()).add(notice);
            }
        }
        if (byRecipient.isEmpty()) {
            log.warn("本輪 {} 筆交易雷達通知沒有啟用中的收件人", batch.size());
            return;
        }

        byRecipient.forEach((email, notices) -> {
            String subject = "[資產管理] 交易雷達狀態通知 " + notices.size() + " 筆";
            emailService.sendHtml(List.of(email), subject, buildHtml(notices), Map.of());
        });
    }

    private List<Notice> drain() {
        List<Notice> batch = new ArrayList<>();
        Notice notice;
        while ((notice = queue.poll()) != null) batch.add(notice);
        return batch;
    }

    private String buildHtml(List<Notice> notices) {
        StringBuilder html = new StringBuilder("""
                <html><body style="font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;color:#1f2937">
                <h2 style="margin-bottom:6px">交易雷達狀態通知</h2>
                <p style="color:#64748b;margin-top:0">以下股票剛進入你選定的通知狀態；主規則與逆勢狀態彼此獨立。</p>
                """);
        for (Notice notice : notices) {
            html.append("<div style=\"border:1px solid #e2e8f0;border-radius:10px;padding:14px;margin:14px 0\">")
                    .append("<h3 style=\"margin:0 0 8px\">")
                    .append(escape(notice.stockCode())).append(" ")
                    .append(escape(notice.stockName())).append("</h3>")
                    .append("<div><strong>觸發狀態：</strong>")
                    .append(escape(String.join("、", notice.enteredStates()))).append("</div>")
                    .append("<div><strong>主規則建議：</strong>").append(escape(notice.actionLabel()))
                    .append("　<strong>逆勢狀態：</strong>").append(escape(notice.counterTrendLabel()))
                    .append("　<strong>分數：</strong>").append(notice.score() == null ? "—" : notice.score())
                    .append("</div>")
                    .append("<div><strong>現價：</strong>").append(number(notice.price()))
                    .append("　<strong>漲跌：</strong>").append(percent(notice.changePercent()))
                    .append("　<strong>行情時間：</strong>").append(escape(formatTime(notice.priceUpdatedAt())))
                    .append("</div>");
            appendList(html, "支持訊號", concat(notice.reasons(), notice.counterTrendReasons()));
            appendList(html, "風險提醒", concat(notice.risks(), notice.counterTrendRisks()));
            html.append("</div>");
        }
        return html.append("<p style=\"color:#64748b;font-size:12px\">此為規則式決策輔助，不是獲利保證；系統不會自動下單。</p></body></html>")
                .toString();
    }

    private void appendList(StringBuilder html, String title, List<String> values) {
        if (values == null || values.isEmpty()) return;
        html.append("<div style=\"margin-top:8px\"><strong>").append(title).append("：</strong><ul>");
        values.forEach(value -> html.append("<li>").append(escape(value)).append("</li>"));
        html.append("</ul></div>");
    }

    private List<String> concat(List<String> first, List<String> second) {
        List<String> result = new ArrayList<>();
        if (first != null) result.addAll(first);
        if (second != null) result.addAll(second);
        return result;
    }

    private String formatTime(String value) {
        if (value == null || value.isBlank()) {
            return OffsetDateTime.now(ZoneId.of("Asia/Taipei")).format(TS);
        }
        try {
            return OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.of("Asia/Taipei")).format(TS);
        } catch (Exception ignored) {
            return value;
        }
    }

    private String number(BigDecimal value) {
        return value == null ? "—" : value.stripTrailingZeros().toPlainString();
    }

    private String percent(BigDecimal value) {
        if (value == null) return "—";
        String prefix = value.signum() > 0 ? "+" : "";
        return prefix + value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private String escape(String value) {
        if (value == null) return "—";
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private record Notice(
            Long settingId,
            String stockCode,
            String stockName,
            String actionLabel,
            String counterTrendLabel,
            Integer score,
            BigDecimal price,
            BigDecimal changePercent,
            String priceUpdatedAt,
            List<String> enteredStates,
            List<String> reasons,
            List<String> risks,
            List<String> counterTrendReasons,
            List<String> counterTrendRisks
    ) {}
}
