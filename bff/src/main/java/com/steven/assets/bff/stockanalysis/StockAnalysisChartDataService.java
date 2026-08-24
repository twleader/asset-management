package com.steven.assets.bff.stockanalysis;

import com.steven.assets.bff.stockanalysis.dto.ChartSeriesDto;
import com.steven.assets.bff.stockanalysis.dto.IndicatorPointDto;
import com.steven.assets.bff.stockanalysis.dto.PricePointDto;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;

/**
 * 股票分析「history + indicator」共用 aggregation。
 *
 * <p>popup 與 Requirement 108 的公開市場投影都經此 service 取得同一份日期聯集與
 * {@link ChartSeriesAligner} 對齊結果；controller 不互相呼叫，也不在第二處重算技術指標。</p>
 */
@Service
public class StockAnalysisChartDataService {

    private static final ParameterizedTypeReference<List<PricePointDto>> PRICE_LIST =
            new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<IndicatorPointDto>> INDICATOR_LIST =
            new ParameterizedTypeReference<>() {};

    /**
     * 兩條下游獨立啟動；失敗狀態保留給公開 API 做 typed fallback，popup 仍可直接取 series。
     */
    public Mono<ChartFetchResult> fetch(
            WebClient businessClient, String code, String market, LocalDate start, LocalDate end) {
        Mono<FetchResult<PricePointDto>> prices = businessClient.get()
                .uri(builder -> builder.path("/api/market-data/history/stock")
                        .queryParam("code", code)
                        .queryParam("market", market)
                        .queryParam("start", start)
                        .queryParam("end", end)
                        .build())
                .retrieve()
                .bodyToMono(PRICE_LIST)
                .defaultIfEmpty(List.of())
                .map(FetchResult::success)
                .onErrorReturn(FetchResult.failure());

        Mono<FetchResult<IndicatorPointDto>> indicators = businessClient.get()
                .uri(builder -> builder.path("/api/market-data/indicators/series")
                        .queryParam("code", code)
                        .queryParam("market", market)
                        .queryParam("start", start)
                        .queryParam("end", end)
                        .build())
                .retrieve()
                .bodyToMono(INDICATOR_LIST)
                .defaultIfEmpty(List.of())
                .map(FetchResult::success)
                .onErrorReturn(FetchResult.failure());

        return Mono.zip(prices, indicators)
                .map(tuple -> {
                    FetchResult<PricePointDto> priceResult = tuple.getT1();
                    FetchResult<IndicatorPointDto> indicatorResult = tuple.getT2();
                    ChartSeriesDto series = ChartSeriesAligner.align(
                            priceResult.rows(), indicatorResult.rows(), start);
                    return new ChartFetchResult(
                            priceResult.available(), indicatorResult.available(),
                            priceResult.rows(), indicatorResult.rows(), series);
                });
    }

    /** 公開 API 的狀態判定需要知道每一側是否真的可用，而非把 error 偽裝成 []。 */
    public record ChartFetchResult(
            boolean historyAvailable,
            boolean indicatorsAvailable,
            List<PricePointDto> prices,
            List<IndicatorPointDto> indicators,
            ChartSeriesDto series) {
        public boolean bothEmpty() {
            return prices.isEmpty() && indicators.isEmpty();
        }
    }

    private record FetchResult<T>(boolean available, List<T> rows) {
        private static <T> FetchResult<T> success(List<T> rows) {
            return new FetchResult<>(true, rows == null ? List.of() : List.copyOf(rows));
        }

        private static <T> FetchResult<T> failure() {
            return new FetchResult<>(false, List.of());
        }
    }
}
