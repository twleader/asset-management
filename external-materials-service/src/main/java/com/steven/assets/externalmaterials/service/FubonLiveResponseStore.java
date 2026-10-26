package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.model.marketdata.FubonResponseData.CanonicalResponse;
import com.steven.assets.externalmaterials.model.marketdata.FubonResponseData.PersistResult;
import com.steven.assets.externalmaterials.model.marketdata.FubonResponseData.ReadResult;
import com.steven.assets.externalmaterials.model.marketdata.NormalizedQuoteData;
import com.steven.assets.externalmaterials.service.port.FubonResponseRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.steven.assets.externalmaterials.model.marketdata.FubonResponseRules.*;

/**
 * PostgreSQL-canonical latest normalized Fubon LIVE response state.
 *
 * <p>This is deliberately separate from canonical price and complete-book tables.  It preserves a
 * validated normalized adapter response, including FAILURE and otherwise non-price-eligible rows,
 * without making that response another price authority.</p>
 */
@Slf4j
@Component
public class FubonLiveResponseStore implements FubonResponseRepository {

    private static final String TAIWAN = "台股";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate writeTransaction;
    private final TransactionTemplate readTransaction;

    public FubonLiveResponseStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.writeTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    /**
     * Writes an entire validated batch in one transaction with one microsecond receipt instant.
     * A stale row is re-read inside the transaction and is still returned as the DB canonical value.
     */
    public PersistResult persist(NormalizedQuoteData.ValidatedEnvelope envelope, Instant receiptAt) {
        if (!validEnvelope(envelope) || receiptAt == null) return PersistResult.failed();
        Instant receivedAt = receiptAt.truncatedTo(ChronoUnit.MICROS);
        try {
            PersistResult result = writeTransaction.execute(ignored -> persistWithinTransaction(envelope, receivedAt));
            return result == null ? PersistResult.failed() : result;
        } catch (Exception failure) {
            log.warn("富邦 LIVE 完整回應 DB 寫入失敗 batchId={}", envelope.batchId());
            return PersistResult.failed();
        }
    }

    /** Pure DB read; it never repairs Redis or talks to an adapter/provider. */
    public ReadResult find(String code, String market) {
        if (!supportedIdentity(code, market)) return ReadResult.missing();
        try {
            ReadResult result = readTransaction.execute(ignored -> read(code, market)
                    .map(ReadResult::found).orElseGet(ReadResult::missing));
            return result == null ? ReadResult.failed() : result;
        } catch (Exception failure) {
            log.warn("富邦 LIVE 完整回應 DB 讀取失敗 market={} code={}", market, code);
            return ReadResult.failed();
        }
    }

    private PersistResult persistWithinTransaction(NormalizedQuoteData.ValidatedEnvelope envelope,
                                                   Instant receivedAt) {
        List<CanonicalResponse> rows = new ArrayList<>();
        for (Map.Entry<String, String> entry : envelope.responseRows().entrySet()) {
            String code = entry.getKey();
            if (!supportedIdentity(code, TAIWAN) || entry.getValue() == null || entry.getValue().isBlank()) {
                throw new IllegalArgumentException("invalid validated response row");
            }
            List<CanonicalResponse> applied = jdbc.query("""
                    INSERT INTO fubon_tw_live_quote_response
                      (stock_code, market, received_at, batch_id, counters, response_row)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb)
                    ON CONFLICT (stock_code, market) DO UPDATE SET
                      received_at=EXCLUDED.received_at, batch_id=EXCLUDED.batch_id,
                      counters=EXCLUDED.counters, response_row=EXCLUDED.response_row
                    WHERE EXCLUDED.received_at > fubon_tw_live_quote_response.received_at
                    RETURNING stock_code, market, received_at, batch_id, counters::text, response_row::text
                    """, (rs, rowNum) -> toCanonical(rs),
                    code, TAIWAN, Timestamp.from(receivedAt), envelope.batchId(), envelope.countersJson(), entry.getValue());
            if (applied.isEmpty()) {
                CanonicalResponse canonical = read(code, TAIWAN)
                        .orElseThrow(() -> new IllegalStateException("canonical fubon response missing after stale conflict"));
                rows.add(canonical);
            } else {
                rows.add(applied.getFirst());
            }
        }
        if (rows.size() != envelope.responseRows().size()) {
            throw new IllegalStateException("incomplete Fubon response batch");
        }
        return PersistResult.stored(rows);
    }

    private Optional<CanonicalResponse> read(String code, String market) {
        List<CanonicalResponse> rows = jdbc.query("""
                SELECT stock_code, market, received_at, batch_id, counters::text, response_row::text
                FROM fubon_tw_live_quote_response WHERE stock_code=? AND market=?
                """, (rs, rowNum) -> toCanonical(rs), code, market);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    private static CanonicalResponse toCanonical(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new CanonicalResponse(rs.getString("stock_code"), rs.getString("market"),
                rs.getTimestamp("received_at").toInstant(), rs.getString("batch_id"),
                rs.getString("counters"), rs.getString("response_row"));
    }

    private static boolean validEnvelope(NormalizedQuoteData.ValidatedEnvelope envelope) {
        return envelope != null && envelope.batchId() != null && !envelope.batchId().isBlank()
                && envelope.countersJson() != null && !envelope.countersJson().isBlank()
                && envelope.responseRows() != null && !envelope.responseRows().isEmpty();
    }

}
