package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.NewsRow;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Method;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 181／Task 481（{@code spec/steering/structure.md} §3.2 第 4 條具名例外之六）：寫入
 * {@code news_headline.dedupe_key} 的 {@code NewsPoller.dedupeKey}（private static）必須與 business-services
 * {@code EventEvidenceCaptureService#dedupeKey} 逐位元一致。以與 backend {@code NewsHeadlineDedupeKeyTest} 相同的
 * 三組黃金向量釘住；以反射呼叫，不為測試改動 production 可見度。純 JUnit，不啟動 Spring、不連資料庫。
 */
class NewsPollerDedupeKeyTest {

    @ParameterizedTest(name = "{0}|{1}|{2}")
    @CsvSource({
            "ltn,https://news.ltn.com.tw/news/business/breakingnews/1,news,3688d331d3b6765b1ee2dc4b273c4b54b049eaa89f1a661aa433aa4b9a9a5405",
            "kr-index,https://finance.yahoo.com/quote/%5EKS11,kr-market,ff0ec0f42ac1183b367be9a4c4b35d1e6203eaddbe88ced98c1e253e5b8f9ff1",
            "twse,https://www.twse.com.tw/zh/trading/foreign/bfi82u.html,twse-institutional,1e76d14cd86b556c9498389229e6eb2b4f626304f3e6aa1edb30b9f550e6a3f7"
    })
    void dedupeKeyMatchesSharedGoldenVectors(String source, String url, String category, String expected) throws Exception {
        NewsRow row = new NewsRow("任意標題", source, url, category, "TW", null, Instant.parse("2026-10-12T00:00:00Z"));
        Method dedupeKey = NewsPoller.class.getDeclaredMethod("dedupeKey", NewsRow.class);
        dedupeKey.setAccessible(true);

        assertThat(dedupeKey.invoke(null, row)).isEqualTo(expected);
    }
}
