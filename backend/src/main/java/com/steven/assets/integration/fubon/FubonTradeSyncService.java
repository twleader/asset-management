package com.steven.assets.integration.fubon;

import com.steven.assets.model.AppUser;
import com.steven.assets.model.BrokerEntity;
import com.steven.assets.repository.AssetTransactionRepository;
import com.steven.assets.repository.BrokerRepository;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.service.StockMasterService;
import com.steven.assets.service.UserAdminService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
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
@Transactional(propagation = Propagation.NOT_SUPPORTED)
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
    private final FubonTradeWriter writer;
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
            FubonTradeWriter writer,
            FubonTradeOutcomeCounters counters,
            @org.springframework.beans.factory.annotation.Value("${fubon.trade-sync-enabled:false}") boolean tradeSyncEnabled,
            @org.springframework.beans.factory.annotation.Value("${fubon.tw-live-quotes-enabled:false}") boolean liveQuotesEnabled) {
        this(configState, brokerClient, marketDataService, userAdminService, brokerRepository,
                assetTransactionRepository, stockMasterService, writer, counters, Clock.system(TW_ZONE),
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
            FubonTradeWriter writer,
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
        this.writer = writer;
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
        if (broker.isEmpty() || broker.get().getId() == null || !"fubon".equals(broker.get().getCode())
                || !Boolean.TRUE.equals(broker.get().getActive())) {
            return toResult(recordOutcome(FubonTradeOutcome.BROKER_MISSING), dryRun);
        }

        FubonDtos.CallResult<FubonDtos.TradeBatchResponse> call;
        try {
            call = brokerClient.readFilledTrades(today, today);
        } catch (RuntimeException exception) {
            call = FubonDtos.CallResult.failure("TRANSPORT_OR_SCHEMA_FAILURE");
        }
        if (call == null || !call.success() || !FubonTradeContract.validBatch(call.body(), today, today)) {
            return toResult(recordOutcome(FubonTradeOutcome.TRADE_FAILED), dryRun);
        }
        FubonDtos.TradeBatchResponse body = call.body();
        if (body.emptyConfirmed() && body.trades().isEmpty()) {
            FubonTradeOutcome outcome = recordOutcome(FubonTradeOutcome.NO_NEW_TRADES);
            return buildResult(outcome, dryRun, body.batchId(), 0, 0, 0, 0);
        }

        try {
            return processTrades(ownerId, broker.get().getId(), body, dryRun);
        } catch (FubonTradeWriter.WriteRejected rejected) {
            FubonTradeOutcome outcome = recordOutcome(rejected.outcome());
            return buildResult(outcome, dryRun, body.batchId(), body.trades().size(), 0, 0, 0);
        } catch (RuntimeException unexpected) {
            recordOutcome(FubonTradeOutcome.ROLLED_BACK);
            // Neither the API error handler nor scheduler may receive raw SQL/row values.
            // The independently proxied writer has already rolled back before we get here.
            throw new IllegalStateException("TRANSACTION_ROLLED_BACK");
        }
    }

    /**
     * Prepare outside the write transaction, then wait for the separate writer's commit before
     * recording SUCCESS. The explicit ON CONFLICT target handles only actual duplicate fills.
     */
    private TradeSyncResult processTrades(Long ownerId, Long brokerId, FubonDtos.TradeBatchResponse body, boolean dryRun) {
        List<FubonTradeWriter.PreparedTrade> toInsert = new ArrayList<>();
        int skippedExisting = 0;
        int skippedNameUnresolved = 0;
        for (FubonDtos.FilledTrade trade : body.trades()) {
            String stockName = stockMasterService.resolveNameLocalOnly(trade.stockCode(), "台股");
            // resolveNameLocalOnly falls back to returning the code itself when the local stock
            // master has no name for it yet (see its javadoc); that fallback is this method's
            // only signal that the name is not locally resolvable, so treat it the same as null.
            if (stockName == null || stockName.isBlank() || stockName.equals(trade.stockCode())) {
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
                throw new IllegalStateException("UNEXPECTED_TRADE_SIDE");
            }

            BigDecimal shares = BigDecimal.valueOf(trade.filledQty());
            BigDecimal price = trade.filledAvgPrice().value();
            BigDecimal amount = FubonTradeContract.amount(price, shares);
            toInsert.add(new FubonTradeWriter.PreparedTrade(trade.filledNo(), transactionType, stockName,
                    trade.stockCode(), trade.filledDate(), shares, price, amount));
        }

        if (dryRun) {
            FubonTradeOutcome outcome = recordOutcome(FubonTradeOutcome.DRY_RUN);
            return buildResult(outcome, true, body.batchId(), body.trades().size(),
                    0, skippedExisting, skippedNameUnresolved);
        }

        int insertedCount = 0;
        if (!toInsert.isEmpty()) {
            FubonTradeWriter.CommitResult committed = writer.insert(ownerId, brokerId, List.copyOf(toInsert));
            insertedCount = committed.insertedCount();
            skippedExisting += committed.skippedExistingCount();
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
