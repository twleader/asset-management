package com.steven.assets.service.srpp;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 181／Task 481.4／481.13：backend 自行重製的 {@code news_headline.dedupe_key} 公式必須與
 * external-materials-service {@code NewsPoller.dedupeKey}（private static）逐位元一致；以三組黃金向量鎖住。
 * ext 端以同一組向量由 {@code NewsPollerDedupeKeyTest} 釘住（{@code spec/steering/structure.md} §3.2 第 4 條具名例外之六）。
 */
class NewsHeadlineDedupeKeyTest {

    @ParameterizedTest(name = "{0}|{1}|{2}")
    @CsvSource({
            "ltn,https://news.ltn.com.tw/news/business/breakingnews/1,news,3688d331d3b6765b1ee2dc4b273c4b54b049eaa89f1a661aa433aa4b9a9a5405",
            "kr-index,https://finance.yahoo.com/quote/%5EKS11,kr-market,ff0ec0f42ac1183b367be9a4c4b35d1e6203eaddbe88ced98c1e253e5b8f9ff1",
            "twse,https://www.twse.com.tw/zh/trading/foreign/bfi82u.html,twse-institutional,1e76d14cd86b556c9498389229e6eb2b4f626304f3e6aa1edb30b9f550e6a3f7"
    })
    void dedupeKeyMatchesNewsPollerGoldenVectors(String source, String url, String category, String expected) {
        assertThat(EventEvidenceCaptureService.dedupeKey(source, url, category)).isEqualTo(expected);
    }
}
