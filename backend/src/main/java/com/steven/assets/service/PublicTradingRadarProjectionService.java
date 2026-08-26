package com.steven.assets.service;

import com.steven.assets.dto.PublicTradingRadarDto;
import com.steven.assets.dto.TradingRadarDto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;

/** Requirement 111 的純 current-read 雷達投影；不寫 snapshot、不 refresh、不匯出。 */
@Service
@RequiredArgsConstructor
public class PublicTradingRadarProjectionService {

    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9.\\-]{1,12}$");
    private static final Pattern MARKET = Pattern.compile("^[\\p{L}0-9]{1,10}$");

    private final TradingRadarService tradingRadarService;

    /** 每一個 list request 只呼叫一次既有 pure current reader。 */
    public PublicTradingRadarDto.TradingRadarListResponse list() {
        TradingRadarDto.Response current = tradingRadarService.getCurrent();
        return new PublicTradingRadarDto.TradingRadarListResponse(
                current.ruleVersion(), current.actionPolicyVersion(), current.generatedAt(), current.market(),
                current.usMarket(), current.stocks().stream().map(this::toListStock).toList(),
                current.skippedNonTwStocks(), current.publicInformation());
    }

    /** 每一個 detail request 先做 local selector validation，通過後只 current-read 一次。 */
    public PublicTradingRadarDto.TradingRadarStockDetailResponse stock(String rawStockCode, String rawMarket) {
        String stockCode = normalizedSelector(rawStockCode, CODE);
        String market = normalizedSelector(rawMarket, MARKET);
        TradingRadarDto.Response current = tradingRadarService.getCurrent();
        TradingRadarDto.StockDecision stock = current.stocks().stream()
                .filter(candidate -> stockCode.equals(candidate.stockCode()) && market.equals(candidate.market()))
                .findFirst()
                .orElseThrow(() -> new PublicTradingRadarProjectionException(HttpStatus.NOT_FOUND));
        return new PublicTradingRadarDto.TradingRadarStockDetailResponse(
                current.ruleVersion(), current.actionPolicyVersion(), current.generatedAt(), current.market(),
                current.usMarket(), stock);
    }

    private String normalizedSelector(String value, Pattern pattern) {
        if (value == null) {
            throw new PublicTradingRadarProjectionException(HttpStatus.BAD_REQUEST);
        }
        String normalized = value.trim();
        if (normalized.isEmpty() || !pattern.matcher(normalized).matches()) {
            throw new PublicTradingRadarProjectionException(HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    private PublicTradingRadarDto.TradingRadarListStock toListStock(TradingRadarDto.StockDecision stock) {
        TradingRadarDto.FundamentalSnapshot fundamental = stock.fundamental();
        PublicTradingRadarDto.TradingRadarListFundamental listFundamental = fundamental == null ? null
                : new PublicTradingRadarDto.TradingRadarListFundamental(
                        fundamental.applicable(), fundamental.coverage(), fundamental.industryName(),
                        fundamental.industryRevenueYoyPct());
        return new PublicTradingRadarDto.TradingRadarListStock(
                stock.stockCode(), stock.stockName(), stock.market(), stock.assetClass(),
                stock.distributionAdjusted(), stock.held(), stock.fxPercentile(), stock.underlyingCurrency(),
                listFundamental, stock.shortAction(), stock.shortActionLabel(), stock.shortScore(),
                stock.swingAction(), stock.swingActionLabel(), stock.swingScore(), stock.action(),
                stock.actionLabel(), stock.score(), stock.horizonConflict(), stock.timingState(),
                stock.timingLabel(), stock.counterTrendState(), stock.counterTrendLabel(), stock.price(),
                stock.changePercent(), stock.quoteStatus(), stock.etfPremiumLivePct(),
                stock.etfPremiumLiveNavAsOf(), stock.weeklyMa(), stock.monthlyMa(), stock.quarterlyMa(),
                stock.annualMa(), stock.kValue(), stock.dValue(), stock.kdHeat(), stock.weeklyIndicators(),
                stock.asOfDate());
    }

    /** 內部 endpoint 只需 status；public BFF 一律將 detail/content 消毒後再回外部。 */
    public static final class PublicTradingRadarProjectionException extends RuntimeException {
        private final HttpStatus status;

        PublicTradingRadarProjectionException(HttpStatus status) {
            this.status = status;
        }

        public HttpStatus status() {
            return status;
        }
    }
}
