package com.steven.assets.integration.fubon;

import com.steven.assets.model.AppUser;
import com.steven.assets.model.AssetTransaction;
import com.steven.assets.model.BrokerEntity;
import com.steven.assets.repository.AssetTransactionRepository;
import com.steven.assets.repository.BrokerRepository;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.service.StockMasterService;
import com.steven.assets.service.UserAdminService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Read-only Fubon filled-trade sync into {@code asset_transaction} (Requirement 120 / Task 385).
 *
 * <p>Sibling of {@link FubonInventorySyncService}: same cron cadence, same tri-state calendar
 * gate, same {@link FubonConfigState} READY gate, same configured-admin owner resolution. The
 * output target and internal call graph are deliberately different — this service returns a bare
 * {@link FubonTradeOutcome} enum wrapped in {@link TradeSyncResult} rather than the existing
 * {@link FubonDtos.SyncResponse}, and {@link #syncManual} delegates to
 * {@link #syncScheduledAfterCalendar} (the existing inventory service's {@code syncManual} does
 * not — it is an independent flow). Do not copy that call graph; only the gate-short-circuit
 * spirit is shared.
 */
@Service
@Slf4j
public class FubonTradeSyncService {
    static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");
    private static final Pattern BATCH_ID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final FubonConfigState configState;
    private final FubonBrokerClient brokerClient;
    private final MarketDataService marketDataService;
    private final UserAdminService userAdminService;
    private final BrokerRepository brokerRepository;
    private final AssetTransactionRepository assetTransactionRepository;
    private final StockMasterService stockMasterService;
    private final FubonTradeOutcomeCounters counters;
    private final Clock clock;
    private final boolean tradeSyncEnabled;
    private final boolean liveQuotesEnabled;

    @Autowired
    public FubonTradeSyncService(
            FubonConfigState configState,
            FubonBrokerClient brokerClient,
            MarketDataService marketDataService,
            UserAdminService userAdminService,
            BrokerRepository brokerRepository,
            AssetTransactionRepository assetTransactionRepository,
            StockMasterService stockMasterService,
            FubonTradeOutcomeCounters counters,
            @org.springframework.beans.factory.annotation.Value("${fubon.trade-sync-enabled:false}") boolean tradeSyncEnabled,
            @org.springframework.beans.factory.annotation.Value("${fubon.tw-live-quotes-enabled:false}") boolean liveQuotesEnabled) {
        this(configState, brokerClient, marketDataService, userAdminService, brokerRepository,
                assetTransactionRepository, stockMasterService, counters, Clock.system(TW_ZONE),
                tradeSyncEnabled, liveQuotesEnabled);
    }

    FubonTradeSyncService(
            FubonConfigState configState,
            FubonBrokerClient brokerClient,
            MarketDataService marketDataService,
            UserAdminService userAdminService,
            BrokerRepository brokerRepository,
            AssetTransactionRepository assetTransactionRepository,
            StockMasterService stockMasterService,
            FubonTradeOutcomeCounters counters,
            Clock clock,
            boolean tradeSyncEnabled,
            boolean liveQuotesEnabled) {
        this.configState = configState;
        this.brokerClient = brokerClient;
        this.marketDataService = marketDataService;
        this.userAdminService = userAdminService;
        this.brokerRepository = brokerRepository;
        this.assetTransactionRepository = assetTransactionRepository;
        this.stockMasterService = stockMasterService;
        this.counters = counters;
        this.clock = clock;
        this.tradeSyncEnabled = tradeSyncEnabled;
        this.liveQuotesEnabled = liveQuotesEnabled;
    }

    /** Scheduler calls this before config/calendar reads so consumer flags have precedence. */
    public FubonTradeOutcome tradeSyncFeatureGate(boolean dryRun) {
        if (!tradeSyncEnabled) return recordOutcome(FubonTradeOutcome.TRADE_SYNC_DISABLED);
        if (liveQuotesEnabled) return recordOutcome(FubonTradeOutcome.TRADE_SYNC_CAPACITY_CONFLICT);
        return null;
    }

    public void localConfigOutcome(boolean dryRun, FubonConfigState.State state) {
        if (state == FubonConfigState.State.DISABLED) {
            recordOutcome(FubonTradeOutcome.DISABLED);
            return;
        }
        if (state == FubonConfigState.State.MISCONFIGURED) {
            recordOutcome(FubonTradeOutcome.MISCONFIGURED);
            return;
        }
        throw new IllegalArgumentException("READY is not a local failure outcome");
    }

    public void calendarUnknown(boolean dryRun) {
        recordOutcome(FubonTradeOutcome.CALENDAR_UNKNOWN);
    }

    private FubonTradeOutcome localConfigGate(boolean dryRun) {
        FubonTradeOutcome featureGate = tradeSyncFeatureGate(dryRun);
        if (featureGate != null) return featureGate;
        FubonConfigState.Snapshot config = configState.snapshot();
        return switch (config.state()) {
            case DISABLED -> recordOutcome(FubonTradeOutcome.DISABLED);
            case MISCONFIGURED -> recordOutcome(FubonTradeOutcome.MISCONFIGURED);
            case READY -> null;
        };
    }

    /** Manual endpoint flow: does not trust the caller to have validated the calendar. */
    public TradeSyncResult syncManual(boolean dryRun) {
        FubonTradeOutcome gate = localConfigGate(dryRun);
        if (gate != null) return toResult(gate, dryRun);

        LocalDate today = LocalDate.now(clock.withZone(TW_ZONE));
        Optional<Boolean> tradingDay;
        try {
            tradingDay = marketDataService.isTwTradingDayKnown(today);
        } catch (RuntimeException exception) {
            tradingDay = Optional.empty();
        }
        if (!tradingDay.orElse(false)) {
            return toResult(recordOutcome(FubonTradeOutcome.CALENDAR_UNKNOWN), dryRun);
        }
        return syncScheduledAfterCalendar(today, dryRun);
    }

    /** Scheduler flow after its own tri-state calendar gate authorized this exact Taipei date. */
    TradeSyncResult syncScheduledAfterCalendar(LocalDate today, boolean dryRun) {
        // Defense-in-depth: re-verify even though the scheduler / syncManual already gated once.
        FubonTradeOutcome gate = localConfigGate(dryRun);
        if (gate != null) return toResult(gate, dryRun);

        Optional<AppUser> configured = userAdminService.configuredAdmin();
        if (configured.isEmpty() || configured.get().getId() == null
                || !configured.get().isActive() || !configured.get().isAdmin()) {
            return toResult(recordOutcome(FubonTradeOutcome.NO_OWNER), dryRun);
        }
        Long ownerId = configured.get().getId();

        Optional<BrokerEntity> broker = brokerRepository.findByCode("fubon");
        if (broker.isEmpty() || !Boolean.TRUE.equals(broker.get().getActive())) {
            return toResult(recordOutcome(FubonTradeOutcome.BROKER_MISSING), dryRun);
        }

        FubonDtos.CallResult<FubonDtos.TradeBatchResponse> call;
        try {
            call = brokerClient.readFilledTrades(today, today);
        } catch (RuntimeException exception) {
            call = FubonDtos.CallResult.failure("TRANSPORT_OR_SCHEMA_FAILURE");
        }
        if (call == null || !call.success() || call.body() == null || call.body().trades() == null) {
            return toResult(recordOutcome(FubonTradeOutcome.TRADE_FAILED), dryRun);
        }
        FubonDtos.TradeBatchResponse body = call.body();
        if (body.emptyConfirmed() && body.trades().isEmpty()) {
            FubonTradeOutcome outcome = recordOutcome(FubonTradeOutcome.NO_NEW_TRADES);
            return buildResult(outcome, dryRun, body.batchId(), 0, 0, 0, 0);
        }

        try {
            return processTrades(ownerId, body, dryRun);
        } catch (RuntimeException unexpected) {
            counters.increment(FubonTradeOutcome.ROLLED_BACK);
            log.info("Fubon trade sync outcome={} reason=UNEXPECTED_EXCEPTION",
                    FubonTradeOutcome.ROLLED_BACK, unexpected);
            throw unexpected;
        }
    }

    /**
     * Validates and (when not dry-run) writes every not-yet-recorded trade in {@code body}.
     *
     * <p><b>Deliberately not one big {@code @Transactional} span.</b> The spec sketch describes a
     * single transactional method for the whole batch, but sharing one PostgreSQL transaction
     * across every row is unsafe here: once any statement inside a PostgreSQL transaction raises
     * a constraint violation, the server aborts that whole transaction and rejects every further
     * statement issued on it ("current transaction is aborted") until it is rolled back — so
     * catching the unique-index race on row N and continuing to row N+1 in the same transaction
     * would silently fail every remaining insert, not just skip the duplicate. Each
     * {@code assetTransactionRepository.save(...)} call below is therefore its own independent
     * transaction (Spring Data's {@code CrudRepository} methods are transactional per call), so a
     * concurrent-race duplicate on one row cannot poison the others. Application-level self-
     * invocation of {@code @Transactional} on a method in this same class would also silently not
     * apply (Spring's proxy-based AOP does not intercept {@code this.}-calls), which is a second,
     * independent reason not to add one here.
     */
    private TradeSyncResult processTrades(Long ownerId, FubonDtos.TradeBatchResponse body, boolean dryRun) {
        List<AssetTransaction> toInsert = new ArrayList<>();
        int skippedExisting = 0;
        int skippedNameUnresolved = 0;
        for (FubonDtos.FilledTrade trade : body.trades()) {
            String stockName = stockMasterService.resolveNameLocalOnly(trade.stockCode(), "台股");
            // resolveNameLocalOnly falls back to returning the code itself when the local stock
            // master has no name for it yet (see its javadoc); that fallback is this method's
            // only signal that the name is not locally resolvable, so treat it the same as null.
            if (stockName == null || stockName.equals(trade.stockCode())) {
                skippedNameUnresolved++;
                continue;
            }
            if (assetTransactionRepository.existsByOwnerUserIdAndBrokerFilledNo(ownerId, trade.filledNo())) {
                skippedExisting++;
                continue;
            }

            String transactionType;
            if ("Buy".equals(trade.side())) {
                transactionType = "買";
            } else if ("Sell".equals(trade.side())) {
                transactionType = "賣";
            } else {
                throw new IllegalStateException("UNEXPECTED_TRADE_SIDE:" + trade.side());
            }

            BigDecimal shares = BigDecimal.valueOf(trade.filledQty());
            BigDecimal price = trade.filledAvgPrice().value();
            BigDecimal amount = price.multiply(shares).setScale(2, RoundingMode.HALF_UP);
            if (amount.precision() > 20) {
                FubonTradeOutcome outcome = recordOutcome(FubonTradeOutcome.TRADE_FAILED);
                return buildResult(outcome, dryRun, body.batchId(), body.trades().size(), 0, 0, 0);
            }

            toInsert.add(AssetTransaction.builder()
                    .ownerUserId(ownerId)
                    .transactionType(transactionType)
                    .assetType("股票")
                    .assetName(stockName)
                    .assetCode(trade.stockCode())
                    .market("台股")
                    .currency("TWD")
                    .channel("富邦證券")
                    .tradeDate(trade.filledDate())
                    .shares(shares)
                    .price(price)
                    .amount(amount)
                    .fee(null)
                    .transactionTax(null)
                    .exchangeRate(null)
                    .notes(null)
                    .source("FUBON_SYNC")
                    .brokerFilledNo(trade.filledNo())
                    .build());
        }

        if (dryRun) {
            FubonTradeOutcome outcome = recordOutcome(FubonTradeOutcome.DRY_RUN);
            return buildResult(outcome, true, body.batchId(), body.trades().size(),
                    0, skippedExisting, skippedNameUnresolved);
        }

        int insertedCount = 0;
        for (AssetTransaction candidate : toInsert) {
            try {
                assetTransactionRepository.save(candidate);
                insertedCount++;
            } catch (DataIntegrityViolationException race) {
                // Unique-index race with a concurrent invocation (manual endpoint vs. scheduler):
                // treat exactly like the already-exists check above, do not fail the batch.
                skippedExisting++;
            }
        }
        FubonTradeOutcome outcome = recordOutcome(FubonTradeOutcome.SUCCESS);
        return buildResult(outcome, false, body.batchId(), body.trades().size(),
                insertedCount, skippedExisting, skippedNameUnresolved);
    }

    private FubonTradeOutcome recordOutcome(FubonTradeOutcome outcome) {
        counters.increment(outcome);
        log.info("Fubon trade sync outcome={} reason={}", outcome, reasonFor(outcome));
        return outcome;
    }

    private TradeSyncResult toResult(FubonTradeOutcome outcome, boolean dryRun) {
        return buildResult(outcome, dryRun, null, 0, 0, 0, 0);
    }

    private TradeSyncResult buildResult(
            FubonTradeOutcome outcome,
            boolean dryRun,
            String batchId,
            int tradeCount,
            int insertedCount,
            int skippedExistingCount,
            int skippedNameUnresolvedCount) {
        return new TradeSyncResult(outcome, dryRun, safeBatchId(batchId), tradeCount, insertedCount,
                skippedExistingCount, skippedNameUnresolvedCount, reasonFor(outcome), counters.snapshot());
    }

    private String reasonFor(FubonTradeOutcome outcome) {
        return switch (outcome) {
            case DISABLED -> "DISABLED";
            case TRADE_SYNC_DISABLED -> "TRADE_SYNC_DISABLED";
            case TRADE_SYNC_CAPACITY_CONFLICT -> "TRADE_SYNC_CAPACITY_CONFLICT";
            case MISCONFIGURED -> "MISCONFIGURED";
            case CALENDAR_UNKNOWN -> "CALENDAR_NOT_AUTHORIZED";
            case TRADE_FAILED -> "TRADE_ADAPTER_FAILURE";
            case NO_OWNER -> "NO_ACTIVE_CONFIGURED_ADMIN";
            case BROKER_MISSING -> "BROKER_MISSING";
            case DRY_RUN -> "DRY_RUN_COMPLETE";
            case SUCCESS -> "SUCCESS";
            case NO_NEW_TRADES -> "NO_NEW_TRADES";
            case ROLLED_BACK -> "TRANSACTION_ROLLED_BACK";
        };
    }

    private static boolean validBatchId(String value) {
        return value != null && BATCH_ID.matcher(value).matches();
    }

    private static String safeBatchId(String value) {
        return validBatchId(value) ? value : null;
    }

    /** Response shape returned to both {@link FubonTradeSyncController} and the scheduler. */
    public record TradeSyncResult(
            FubonTradeOutcome outcome,
            boolean dryRun,
            String batchId,
            int tradeCount,
            int insertedCount,
            int skippedExistingCount,
            int skippedNameUnresolvedCount,
            String reason,
            Map<FubonTradeOutcome, Long> counters) {}
}
