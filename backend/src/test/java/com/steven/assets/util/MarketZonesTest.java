package com.steven.assets.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 市場時區入口（Requirement 53 / Task 252）。
 *
 * <p>斷言一律寫成「不依賴執行當下時刻」的形式——不能寫死某個日期，否則明天就紅；
 * 也不能直接比對兩次 {@code now()}，那在跨日瞬間會偽紅。
 */
class MarketZonesTest {

    @Nested
    @DisplayName("today(market)：交易日判定的唯一入口")
    class Today {

        @Test
        @DisplayName("等於該市場時區的今日（以前後兩次取樣夾擠，避開跨日瞬間的競態）")
        void matchesZoneToday() {
            for (String market : new String[]{"台股", "美股", "英股"}) {
                LocalDate before = LocalDate.now(MarketZones.resolve(market));
                LocalDate actual = MarketZones.today(market);
                LocalDate after = LocalDate.now(MarketZones.resolve(market));
                assertTrue(actual.equals(before) || actual.equals(after),
                        market + " 的 today() 應等於該市場時區的今日");
            }
        }

        @Test
        @DisplayName("同一瞬間，美股日期不會晚於台北日期，且最多差一天")
        void usNeverAheadOfTaipei() {
            LocalDate tw = MarketZones.today("台股");
            LocalDate us = MarketZones.today("美股");
            assertTrue(!us.isAfter(tw), "紐約永遠不會比台北早進入新的一天");
            assertTrue(ChronoUnit.DAYS.between(us, tw) <= 1, "兩地日期差不應超過一天");
        }

        @Test
        @DisplayName("null 與未知市場 fallback 到台股（與 resolve 的既有行為一致，不拋例外）")
        void fallsBackToTaipei() {
            assertSame(MarketZones.TW_ZONE, MarketZones.resolve(null));
            assertSame(MarketZones.TW_ZONE, MarketZones.resolve("不存在的市場"));
            LocalDate before = LocalDate.now(MarketZones.TW_ZONE);
            LocalDate actual = MarketZones.today(null);
            LocalDate after = LocalDate.now(MarketZones.TW_ZONE);
            assertTrue(actual.equals(before) || actual.equals(after));
        }
    }

    @Nested
    @DisplayName("本任務的核心語意：台北凌晨時，美股還在前一天")
    class TaipeiEarlyMorning {

        /**
         * 這是 Task 252 要防的那個 bug 的確定性版本：切換 JVM 時區為 Asia/Taipei 後，
         * 若用裸 {@code LocalDate.now()} 取「今日」拿去比對美股的 tradingDate，
         * 台北 00:00–04:00 會取到 D+1 而資料端是 D，今日即時價將完全不併入序列。
         */
        @Test
        @DisplayName("台北 7/29 02:00 = 紐約 7/28 14:00（美股盤中），日期是 7/28 不是 7/29")
        void usStillPreviousDay() {
            ZonedDateTime taipeiEarly =
                    ZonedDateTime.of(2026, 7, 29, 2, 0, 0, 0, MarketZones.TW_ZONE);

            ZonedDateTime sameInstantInNy = taipeiEarly.withZoneSameInstant(MarketZones.US_ZONE);

            assertEquals(LocalDate.of(2026, 7, 28), sameInstantInNy.toLocalDate(),
                    "台北凌晨兩點時，紐約還是前一天");
            assertEquals(14, sameInstantInNy.getHour(), "且正在美股盤中（09:30–16:00 ET）");
            // 對照：同一瞬間用台北時區看是 7/29 —— 這正是裸 LocalDate.now() 會取到的錯誤值
            assertEquals(LocalDate.of(2026, 7, 29), taipeiEarly.toLocalDate());
        }

        @Test
        @DisplayName("英股同理：台北 7/29 02:00 = 倫敦 7/28 19:00（已收盤，仍是前一天）")
        void ukStillPreviousDay() {
            ZonedDateTime taipeiEarly =
                    ZonedDateTime.of(2026, 7, 29, 2, 0, 0, 0, MarketZones.TW_ZONE);
            ZonedDateTime sameInstantInLondon = taipeiEarly.withZoneSameInstant(MarketZones.LON_ZONE);
            assertEquals(LocalDate.of(2026, 7, 28), sameInstantInLondon.toLocalDate());
        }
    }

    @Nested
    @DisplayName("nowLocal(market)：與市場牆鐘欄位比較的唯一入口")
    class NowLocal {

        @Test
        @DisplayName("等於該市場時區的現在（容差 5 秒）")
        void matchesZoneNow() {
            for (String market : new String[]{"台股", "美股", "英股"}) {
                LocalDateTime expected = LocalDateTime.now(MarketZones.resolve(market));
                LocalDateTime actual = MarketZones.nowLocal(market);
                long deltaSec = Math.abs(Duration.between(expected, actual).getSeconds());
                assertTrue(deltaSec <= 5, market + " 的 nowLocal() 偏差過大：" + deltaSec + "s");
            }
        }

        @Test
        @DisplayName("台股與美股的牆鐘差 12 或 13 小時（夏令時間）")
        void taipeiVsNewYorkOffset() {
            long hours = Math.abs(Duration.between(
                    MarketZones.nowLocal("美股"), MarketZones.nowLocal("台股")).toHours());
            assertTrue(hours == 12 || hours == 13, "台北與紐約應差 12（夏令）或 13 小時，實際 " + hours);
        }
    }
}
