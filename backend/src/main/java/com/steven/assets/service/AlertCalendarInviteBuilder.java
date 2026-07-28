package com.steven.assets.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 警示 digest 的 Google 日曆邀請產生器（Requirement 23 / Task 248）。
 *
 * <p>產出 RFC 5545 的 {@code METHOD:REQUEST} VCALENDAR 字串，由 {@code EmailService} 以
 * {@code text/calendar} body part 夾帶。Gmail 收到後（收件人日曆維持預設的「自動將邀請加入我的日曆」時）
 * 會自動建立事件並推播提醒——這是本專案唯一「不需收件人 OAuth 授權就能落進其日曆」的路徑：
 * 收件人是別人的信箱，Calendar API 無從代其建立事件。
 *
 * <p>純字串組裝，不引入任何 iCal 第三方函式庫。
 */
@Component
public class AlertCalendarInviteBuilder {

    private static final String CRLF = "\r\n";
    /** RFC 5545 §3.1 content line 上限，含續行的前導空白在內。 */
    private static final int MAX_OCTETS = 75;
    /** 事件長度；只是日曆上的一個時間塊，真正的作用是觸發提醒。 */
    private static final int EVENT_MINUTES = 15;
    /**
     * 事件開始時間相對寄送當下的偏移。**必須為正**：Google 對「開始時間已過」的事件不發推播；
     * 而事件在其提醒窗內被建立時（收件人日曆預設多為「10 分鐘前」），Google 會於加入當下立即推播。
     * 留 2 分鐘同時涵蓋「本檔的 VALARM 生效」與「被收件人日曆預設提醒覆蓋」兩種情形。
     */
    private static final int START_OFFSET_MINUTES = 2;

    private static final DateTimeFormatter UTC_FMT =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /**
     * @param organizerEmail 寄件人（必須與實際 SMTP 寄件人一致，Google 才會自動接受 REQUEST）
     * @param recipientEmail 收件人 email
     * @param recipientId    收件人 id（寫進 UID，確保每封信是新事件而非既有事件的更新）
     * @param subject        事件標題，＝該封信主旨
     * @param lines          每檔一行的觸發摘要，與信件正文同一份資料
     * @param now            寄送當下（由呼叫端傳入，可測）
     */
    public String build(String organizerEmail, String recipientEmail, Long recipientId,
                        String subject, List<String> lines, Instant now) {
        Instant start = now.truncatedTo(ChronoUnit.MINUTES).plus(START_OFFSET_MINUTES, ChronoUnit.MINUTES);
        Instant end = start.plus(EVENT_MINUTES, ChronoUnit.MINUTES);
        // 每封信都是新 UID：重複 UID 會被 Google 視為既有事件的「更新」而覆蓋前一次觸發，且不再推播。
        String uid = "alert-" + recipientId + "-" + now.toEpochMilli() + "@asset-management";
        String description = lines == null ? "" : String.join("\\n", lines.stream().map(AlertCalendarInviteBuilder::escapeText).toList());

        StringBuilder sb = new StringBuilder();
        line(sb, "BEGIN:VCALENDAR");
        line(sb, "VERSION:2.0");
        line(sb, "PRODID:-//asset-management//alert//ZH-TW");
        line(sb, "CALSCALE:GREGORIAN");
        line(sb, "METHOD:REQUEST");
        line(sb, "BEGIN:VEVENT");
        line(sb, "UID:" + uid);
        line(sb, "DTSTAMP:" + UTC_FMT.format(now));
        line(sb, "DTSTART:" + UTC_FMT.format(start));
        line(sb, "DTEND:" + UTC_FMT.format(end));
        line(sb, "ORGANIZER;CN=資產管理系統:mailto:" + organizerEmail);
        // PARTSTAT=ACCEPTED;RSVP=FALSE：預設已接受、不要求回覆，避免 RSVP 回信灌爆寄件信箱。
        line(sb, "ATTENDEE;CUTYPE=INDIVIDUAL;ROLE=REQ-PARTICIPANT;PARTSTAT=ACCEPTED;RSVP=FALSE;CN="
                + recipientEmail + ":mailto:" + recipientEmail);
        line(sb, "SUMMARY:" + escapeText(subject));
        line(sb, "DESCRIPTION:" + description);
        line(sb, "STATUS:CONFIRMED");
        line(sb, "SEQUENCE:0");
        line(sb, "TRANSP:TRANSPARENT");   // 不佔用忙碌時段，不影響收件人的 free/busy
        line(sb, "BEGIN:VALARM");
        line(sb, "ACTION:DISPLAY");
        line(sb, "DESCRIPTION:股票警示觸發");
        line(sb, "TRIGGER:-PT1M");
        line(sb, "END:VALARM");
        line(sb, "END:VEVENT");
        line(sb, "END:VCALENDAR");
        return sb.toString();
    }

    /** RFC 5545 §3.3.11 TEXT escape；反斜線必須先處理，否則後續插入的反斜線會被重複轉義。 */
    static String escapeText(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n")
                .replace("\r", "\\n");
    }

    /**
     * 寫入一條 content line，超過 75 octets 就折行（RFC 5545 §3.1），續行以單一半形空白起始。
     *
     * <p>長度以 **UTF-8 位元組**計、且**不從多位元組字元中間切斷**：DESCRIPTION 內是中文（一字 3 bytes），
     * 按字元數折會超規，按位元組硬切會產生亂碼。上限含續行的前導空白在內（該空白本身就是續行的第一個 octet）。
     */
    static void line(StringBuilder out, String content) {
        StringBuilder cur = new StringBuilder();
        int curOctets = 0;
        int i = 0;
        while (i < content.length()) {
            int cp = content.codePointAt(i);
            int chars = Character.charCount(cp);
            String piece = content.substring(i, i + chars);
            int octets = piece.getBytes(StandardCharsets.UTF_8).length;
            if (curOctets + octets > MAX_OCTETS) {
                out.append(cur).append(CRLF);
                cur.setLength(0);
                cur.append(' ');       // 續行前導空白，佔 1 octet
                curOctets = 1;
            }
            cur.append(piece);
            curOctets += octets;
            i += chars;
        }
        out.append(cur).append(CRLF);
    }
}
