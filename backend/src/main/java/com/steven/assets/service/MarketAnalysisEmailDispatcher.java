package com.steven.assets.service;

import com.steven.assets.dto.MarketAnalysisDto;
import com.steven.assets.dto.MarketAnalysisResult;
import com.steven.assets.model.DailyMarketAnalysis;
import com.steven.assets.model.NotificationRecipient;
import com.steven.assets.repository.NotificationRecipientRepository;
import com.steven.assets.util.MarketZones;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 今日股市分析結果每日 Email 寄送（Requirement 31 / Task 151）。
 *
 * <p>{@link MarketAnalysisService#finalizeIfReady}（Batch 收尾 poller，{@link MarketAnalysisService#pollPendingBatches} 呼叫）於批次落 OK 後呼叫；不論排程 / self-heal / 管理者手動觸發，皆於首次落 OK 寄送一次（以 email_sent_at 冪等把關，不重寄）。
 * 收件人沿用既有通知收件人（{@link NotificationRecipient}，Requirement 23），僅寄給
 * {@code active = true 且 receive_market_analysis = true} 者；逐一收件人各寄一封（保護彼此 email 隱私，
 * 比照 {@link AlertNotificationDispatcher}）。
 *
 * <p>失敗策略：{@code EmailService} 未啟用 / 無訂閱收件人 → log 略過；任何階段例外一律 log.warn 不拋，
 * 不中斷排程與落庫（比照本功能整體「不中斷」契約）。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MarketAnalysisEmailDispatcher {

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final NotificationRecipientRepository recipientRepo;
    private final EmailService emailService;

    /**
     * 寄送當日成功分析給訂閱收件人。僅接受 {@code status = OK} 的 DTO（呼叫端已保證，此處再防禦）。
     * 於背景排程執行緒（poller）呼叫 → {@code TenantFilterAspect} 不啟用 ownerFilter → 讀全體訂閱收件人（全域分析）。
     *
     * @return {@code true} 表示實際寄給了至少一位收件人（呼叫端據以戳記 {@code email_sent_at} 冪等記號）；
     *         {@code false} 表示略過（非 OK / EmailService 未設定 / 無訂閱收件人 / 例外），不戳記、留待下次收尾重試。
     */
    public boolean dispatchDaily(MarketAnalysisDto dto) {
        try {
            if (dto == null || !DailyMarketAnalysis.STATUS_OK.equals(dto.status())) {
                return false;
            }
            if (!emailService.isEnabled()) {
                log.info("今日股市分析 Email：EmailService 未設定，略過寄送（{}）", dto.analysisDate());
                return false;
            }
            List<NotificationRecipient> recipients =
                    recipientRepo.findByActiveTrueAndReceiveMarketAnalysisTrueOrderByCreatedAtAsc();
            if (recipients.isEmpty()) {
                log.info("今日股市分析 Email：無訂閱收件人，略過寄送（{}）", dto.analysisDate());
                return false;
            }

            String subject = buildSubject(dto);
            String html = buildHtml(dto);
            // 逐一收件人各寄一封（單一 To，保護彼此 email 隱私）
            for (NotificationRecipient r : recipients) {
                emailService.sendHtml(List.of(r.getEmail()), subject, html, null);
            }
            log.info("今日股市分析 Email：已寄送 {} 位訂閱收件人（{}, bias={}）",
                    recipients.size(), dto.analysisDate(), dto.bias());
            return true;
        } catch (Exception e) {
            log.warn("今日股市分析 Email 寄送失敗（{}）：{}",
                    dto == null ? "?" : dto.analysisDate(), e.getMessage());
            return false;
        }
    }

    private String buildSubject(MarketAnalysisDto dto) {
        StringBuilder sb = new StringBuilder();
        sb.append("[今日股市分析] ").append(dto.analysisDate())
                .append(" 台股").append(biasLabel(dto.bias()));
        if (dto.confidence() != null) {
            sb.append("（信心 ").append(dto.confidence()).append("）");
        }
        return sb.toString();
    }

    private String buildHtml(MarketAnalysisDto dto) {
        String biasColor = biasColor(dto.bias());
        StringBuilder sb = new StringBuilder();
        sb.append("<div style=\"font-family:'Helvetica Neue',Arial,'PingFang TC','Microsoft JhengHei',sans-serif;")
          .append("color:#0f172a;font-size:14px;line-height:1.7;max-width:720px\">");

        // 方向色塊（台股漲紅跌綠：偏多紅、偏空綠、中性灰）
        sb.append("<div style=\"background:").append(biasColor)
          .append(";color:#fff;padding:14px 18px;border-radius:6px;display:flex;")
          .append("justify-content:space-between;align-items:center\">");
        sb.append("<div><span style=\"font-size:24px;font-weight:800;letter-spacing:2px\">")
          .append(biasLabel(dto.bias())).append("</span>")
          .append("<span style=\"font-size:13px;opacity:.9;margin-left:12px\">")
          .append(esc(String.valueOf(dto.analysisDate()))).append("　台股當日走向研判</span></div>");
        if (dto.confidence() != null) {
            sb.append("<div style=\"white-space:nowrap\"><span style=\"font-size:24px;font-weight:800\">")
              .append(dto.confidence()).append("</span><span style=\"font-size:12px;opacity:.9\"> / 100 信心</span></div>");
        }
        sb.append("</div>");

        // 總結
        if (notBlank(dto.summary())) {
            sb.append("<p style=\"font-size:15px;line-height:1.8;color:#334155;margin:16px 0\">")
              .append(esc(dto.summary())).append("</p>");
        }

        // 關鍵因素
        if (dto.keyFactors() != null && !dto.keyFactors().isEmpty()) {
            sb.append(blockTitle("關鍵因素"));
            sb.append("<ul style=\"margin:0 0 12px;padding-left:18px\">");
            for (String f : dto.keyFactors()) {
                if (notBlank(f)) sb.append("<li style=\"line-height:1.9;color:#334155\">").append(esc(f)).append("</li>");
            }
            sb.append("</ul>");
        }

        // 台股 / 美股近期走勢
        if (notBlank(dto.twContext())) {
            sb.append(blockTitle("台股近期走勢"));
            sb.append("<div style=\"color:#475569;margin-bottom:12px\">").append(esc(dto.twContext())).append("</div>");
        }
        if (notBlank(dto.usContext())) {
            sb.append(blockTitle("美股近期走勢"));
            sb.append("<div style=\"color:#475569;margin-bottom:12px\">").append(esc(dto.usContext())).append("</div>");
        }

        // 參考新聞（連結僅接受 http(s)；入庫時已過濾，此處再驗一層縱深）
        if (dto.newsHighlights() != null && !dto.newsHighlights().isEmpty()) {
            sb.append(blockTitle("參考新聞"));
            sb.append("<ul style=\"list-style:none;padding-left:0;margin:0 0 12px\">");
            for (MarketAnalysisResult.NewsHighlight n : dto.newsHighlights()) {
                if (n == null || !notBlank(n.title())) continue;
                sb.append("<li style=\"margin-bottom:8px;line-height:1.6\">");
                String url = safeHttpUrl(n.url());
                if (url != null) {
                    sb.append("<a href=\"").append(esc(url))
                      .append("\" style=\"color:#2563eb;text-decoration:none\">").append(esc(n.title())).append("</a>");
                } else {
                    sb.append(esc(n.title()));
                }
                String meta = joinMeta(n.source(), n.publishedAt());
                if (notBlank(meta)) {
                    sb.append("<span style=\"display:block;font-size:12px;color:#94a3b8\">").append(esc(meta)).append("</span>");
                }
                sb.append("</li>");
            }
            sb.append("</ul>");
        }

        // footer
        sb.append("<div style=\"margin-top:16px;padding-top:12px;border-top:1px solid #f1f5f9;")
          .append("font-size:12px;color:#94a3b8\">由 ").append(esc(dto.model() == null ? "Claude" : dto.model()))
          .append(" 產生");
        if (dto.generatedAt() != null) {
            sb.append("於 ").append(esc(TS_FMT.format(dto.generatedAt().atZone(MarketZones.TW_ZONE))));
        }
        sb.append("　·　本分析由 AI 產生，僅供參考，不構成投資建議</div>");

        sb.append("</div>");
        return sb.toString();
    }

    private static String blockTitle(String title) {
        return "<div style=\"font-weight:700;color:#1e293b;margin:10px 0 6px;"
                + "border-left:3px solid #cbd5e1;padding-left:8px\">" + esc(title) + "</div>";
    }

    private static String joinMeta(String source, String publishedAt) {
        boolean s = notBlank(source), p = notBlank(publishedAt);
        if (s && p) return source + " · " + publishedAt;
        if (s) return source;
        return p ? publishedAt : "";
    }

    private static String biasLabel(String bias) {
        if (bias == null) return "未定";
        return switch (bias) {
            case "BULLISH" -> "偏多";
            case "BEARISH" -> "偏空";
            case "NEUTRAL" -> "中性";
            default -> "未定";
        };
    }

    /** 台股漲紅跌綠：偏多紅、偏空綠、中性 / 未定灰（與前端 biasHex 一致）。 */
    private static String biasColor(String bias) {
        if (bias == null) return "#7f8c8d";
        return switch (bias) {
            case "BULLISH" -> "#c0392b";
            case "BEARISH" -> "#27ae60";
            default -> "#7f8c8d";
        };
    }

    /** 僅接受 http/https，其餘（含 null）回 null（防 javascript: / data: scheme）。 */
    private static String safeHttpUrl(String url) {
        if (url == null) return null;
        String u = url.trim().toLowerCase();
        return (u.startsWith("http://") || u.startsWith("https://")) ? url.trim() : null;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
