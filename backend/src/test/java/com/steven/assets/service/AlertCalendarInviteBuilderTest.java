package com.steven.assets.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 警示日曆邀請 ics 產生（Requirement 23 / Task 248）。純字串斷言，不需 Spring context 或 Mockito。
 */
class AlertCalendarInviteBuilderTest {

    private final AlertCalendarInviteBuilder builder = new AlertCalendarInviteBuilder();

    private static final Instant NOW = Instant.parse("2026-07-29T03:15:40Z");
    private static final String FROM = "sender@gmail.com";
    private static final String TO = "someone@gmail.com";

    private String build(String subject, List<String> lines) {
        return builder.build(FROM, TO, 42L, subject, lines, NOW);
    }

    private String buildDefault() {
        return build("[資產管理] 股票警示觸發 1 筆", List.of("台積電 (2330 台股) — 跌破季線 觸發價 1085"));
    }

    @Nested
    @DisplayName("VCALENDAR 骨架")
    class Skeleton {

        @Test
        @DisplayName("含 REQUEST method、VEVENT、TRANSPARENT 與 VALARM 提醒")
        void containsRequiredProperties() {
            String ics = buildDefault();
            assertTrue(ics.contains("METHOD:REQUEST"));
            assertTrue(ics.contains("BEGIN:VEVENT"));
            assertTrue(ics.contains("TRANSP:TRANSPARENT"));
            assertTrue(ics.contains("STATUS:CONFIRMED"));
            assertTrue(ics.contains("SEQUENCE:0"));
            assertTrue(ics.contains("BEGIN:VALARM"));
            assertTrue(ics.contains("ACTION:DISPLAY"));
            assertTrue(ics.contains("TRIGGER:-PT1M"));
        }

        @Test
        @DisplayName("ORGANIZER / ATTENDEE：unfold 後參數完整（ATTENDEE 行過長必被折行，解析端須先 unfold）")
        void participantsSurviveFolding() {
            String unfolded = unfold(buildDefault());
            // 預設已接受、不要求回覆（避免 RSVP 回信灌爆寄件信箱）
            assertTrue(unfolded.contains("PARTSTAT=ACCEPTED;RSVP=FALSE"), unfolded);
            assertTrue(unfolded.contains("ATTENDEE;CUTYPE=INDIVIDUAL;ROLE=REQ-PARTICIPANT"), unfolded);
            assertTrue(unfolded.contains("CN=" + TO + ":mailto:" + TO), unfolded);
            // ORGANIZER 必須與實際 SMTP 寄件人一致，Google 才會自動接受 REQUEST
            assertTrue(unfolded.contains("ORGANIZER;CN=資產管理系統:mailto:" + FROM), unfolded);
        }

        @Test
        @DisplayName("每一行都以 CRLF 結尾，且沒有裸 LF")
        void everyLineEndsWithCrlf() {
            String ics = buildDefault();
            assertTrue(ics.endsWith("END:VCALENDAR\r\n"));
            assertEquals(0, ics.replace("\r\n", "").chars().filter(c -> c == '\n' || c == '\r').count(),
                    "不應有落單的 CR 或 LF");
        }
    }

    @Nested
    @DisplayName("時間錨點")
    class Timing {

        @Test
        @DisplayName("DTSTART = 寄送當下秒歸零 +2 分，DTEND = 再 +15 分")
        void startsTwoMinutesInTheFuture() {
            String ics = buildDefault();
            // 03:15:40 → 秒歸零 03:15:00 → +2 分 = 03:17:00；事件長 15 分 → 03:32:00
            assertTrue(ics.contains("DTSTART:20260729T031700Z"), ics);
            assertTrue(ics.contains("DTEND:20260729T033200Z"), ics);
            assertTrue(ics.contains("DTSTAMP:20260729T031540Z"), ics);
        }
    }

    @Nested
    @DisplayName("UID")
    class Uid {

        @Test
        @DisplayName("含收件人 id 與寄送毫秒")
        void carriesRecipientAndTimestamp() {
            assertTrue(buildDefault().contains("UID:alert-42-" + NOW.toEpochMilli() + "@asset-management"));
        }

        @Test
        @DisplayName("不同寄送時點產生不同 UID —— 重複 UID 會被 Google 當成既有事件的更新而不再推播")
        void differsPerSend() {
            String first = builder.build(FROM, TO, 42L, "s", List.of("l"), NOW);
            String second = builder.build(FROM, TO, 42L, "s", List.of("l"), NOW.plusSeconds(90));
            assertNotEquals(uidOf(first), uidOf(second));
        }

        private String uidOf(String ics) {
            return ics.lines().filter(l -> l.startsWith("UID:")).findFirst().orElseThrow();
        }
    }

    @Nested
    @DisplayName("RFC 5545 TEXT escape")
    class Escaping {

        @Test
        @DisplayName("反斜線、分號、逗號被轉義（反斜線先處理，不重複轉義）")
        void escapesSpecialChars() {
            String ics = build("a\\b;c,d", List.of("x;y"));
            assertTrue(ics.contains("SUMMARY:a\\\\b\\;c\\,d"), ics);
            assertTrue(ics.contains("x\\;y"), ics);
        }

        @Test
        @DisplayName("多行摘要以 literal \\n 串接，不產生真換行")
        void joinsLinesWithLiteralBackslashN() {
            String ics = build("s", List.of("第一檔", "第二檔"));
            String unfolded = unfold(ics);
            assertTrue(unfolded.contains("DESCRIPTION:第一檔\\n第二檔"), unfolded);
        }

        @Test
        @DisplayName("摘要內的真換行也被轉成 literal \\n")
        void escapesRealNewlines() {
            String unfolded = unfold(build("s", List.of("上\n下")));
            assertTrue(unfolded.contains("DESCRIPTION:上\\n下"), unfolded);
            assertFalse(unfolded.contains("DESCRIPTION:上\n"), "真換行會破壞 content line 結構");
        }
    }

    @Nested
    @DisplayName("75 octet folding")
    class Folding {

        /** 一行中文摘要（每字 3 bytes），長到必然需要折行。 */
        private static final String LONG_LINE =
                "台灣積體電路製造股份有限公司 (2330 台股) — 跌破季線、K 值低於 20、股價低於年線 5% 觸發價 1085.5";

        @Test
        @DisplayName("折行後每行 UTF-8 位元組不超過 75（含續行前導空白）")
        void everyLineWithinSeventyFiveOctets() {
            String ics = build("[資產管理] 股票警示觸發 1 筆", List.of(LONG_LINE));
            for (String line : ics.split("\r\n", -1)) {
                if (line.isEmpty()) continue;
                assertTrue(line.getBytes(StandardCharsets.UTF_8).length <= 75,
                        "超過 75 octets：" + line);
            }
        }

        @Test
        @DisplayName("續行以單一半形空白起始")
        void continuationLinesStartWithSingleSpace() {
            String ics = build("s", List.of(LONG_LINE));
            String[] lines = ics.split("\r\n", -1);
            boolean sawContinuation = false;
            for (String line : lines) {
                if (line.startsWith(" ")) {
                    sawContinuation = true;
                    assertFalse(line.startsWith("  "), "續行只能有一個前導空白：" + line);
                }
            }
            assertTrue(sawContinuation, "這段長中文應該有被折行");
        }

        @Test
        @DisplayName("unfold 後內容完整還原 —— 沒有從多位元組字元中間切斷")
        void unfoldRestoresOriginalContent() {
            String ics = build("s", List.of(LONG_LINE));
            String unfolded = unfold(ics);
            assertTrue(unfolded.contains("DESCRIPTION:" + LONG_LINE), unfolded);
            // 切壞多位元組字元會產生替代字元
            assertFalse(unfolded.contains("�"), "出現替代字元代表 UTF-8 被切壞");
        }
    }

    /** RFC 5545 unfold：移除「CRLF + 單一空白」。 */
    private static String unfold(String ics) {
        return ics.replace("\r\n ", "");
    }
}
