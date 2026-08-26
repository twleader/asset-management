package com.steven.assets.bff.publiccalendar;

import com.steven.assets.bff.publicapi.StrictPublicJsonResponse;
import com.steven.assets.bff.security.AuthConstants;
import com.steven.assets.bff.tradingcalendar.TradingCalendarYearWindow;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/** Global calendar bridge: explicit no-tenant WebClient, local year gate, and no configured-admin lookup. */
@Service
@RequiredArgsConstructor
public class PublicTradingCalendarService {

    private static final Pattern YEAR = Pattern.compile("^[0-9]{4}$");
    private static final Duration DOWNSTREAM_TIMEOUT = Duration.ofSeconds(5);

    @Qualifier("publicMarketDataBusinessClient")
    private final WebClient marketDataClient;
    private final TradingCalendarYearWindow yearWindow;

    public Mono<PublicTradingCalendarRelay> current(List<String> rawYears) {
        int year = validatedYear(rawYears);
        return marketDataClient.get()
                .uri(uri -> uri.path("/internal/public-market-data/trading-calendar")
                        .queryParam("year", year).build())
                .accept(MediaType.APPLICATION_JSON)
                .exchangeToMono(this::relayResponse)
                .timeout(DOWNSTREAM_TIMEOUT)
                .onErrorMap(java.util.concurrent.TimeoutException.class,
                        ignored -> new PublicTradingCalendarTimeoutException())
                .contextWrite(ctx -> ctx.delete(AuthConstants.CTX_IDENTITY));
    }

    private int validatedYear(List<String> rawYears) {
        if (rawYears == null || rawYears.size() != 1 || rawYears.getFirst() == null) {
            throw new PublicTradingCalendarRequestException();
        }
        String rawYear = rawYears.getFirst();
        if (!YEAR.matcher(rawYear).matches()) {
            throw new PublicTradingCalendarRequestException();
        }
        int year;
        try {
            year = Integer.parseInt(rawYear);
        } catch (NumberFormatException invalid) {
            throw new PublicTradingCalendarRequestException();
        }
        if (!yearWindow.snapshot().accepts(year)) {
            throw new PublicTradingCalendarRequestException();
        }
        return year;
    }

    private Mono<PublicTradingCalendarRelay> relayResponse(ClientResponse response) {
        if (!response.statusCode().is2xxSuccessful()) {
            return response.createException().flatMap(Mono::error);
        }
        return StrictPublicJsonResponse.decode(response, StrictPublicJsonResponse.Contract.TRADING_CALENDAR,
                        PublicTradingCalendarPayloadException::new)
                .map(PublicTradingCalendarRelay::new);
    }
}
