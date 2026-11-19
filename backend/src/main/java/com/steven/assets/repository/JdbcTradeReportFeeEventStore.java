package com.steven.assets.repository;

import com.steven.assets.service.tradereport.TradeReportFeeEventStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

@Repository
public class JdbcTradeReportFeeEventStore implements TradeReportFeeEventStore {
    private final JdbcTemplate jdbc;

    public JdbcTradeReportFeeEventStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<Event> enrichment(Long owner, long evidenceId) {
        return jdbc.query("""
                SELECT id, owner_user_id, import_evidence_id, kind, payload_jcs
                FROM broker_trade_report_fee_event
                WHERE owner_user_id=? AND import_evidence_id=? AND kind='FEE_ENRICHMENT'
                """, (rs, n) -> new Event(rs.getLong("id"), rs.getLong("owner_user_id"),
                        rs.getLong("import_evidence_id"), rs.getString("kind"), rs.getString("payload_jcs")),
                owner, evidenceId).stream().findFirst();
    }

    @Override
    public boolean manuallyEdited(Long owner, long evidenceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM broker_trade_report_fee_event
                    WHERE owner_user_id=? AND import_evidence_id=? AND kind='MANUAL_EDIT')
                """, Boolean.class, owner, evidenceId));
    }

    @Override
    public void append(Long owner, long evidenceId, String kind, String payload) {
        jdbc.update("""
                INSERT INTO broker_trade_report_fee_event(owner_user_id,import_evidence_id,kind,payload_jcs)
                VALUES(?,?,?,?)
                """, owner, evidenceId, kind, payload);
    }

    @Override
    public Optional<Long> importEvidenceId(Long owner, long transactionId) {
        var matches = jdbc.queryForList("""
                SELECT id FROM broker_trade_report_import WHERE owner_user_id=? AND transaction_id=?
                """, Long.class, owner, transactionId);
        if (matches.size() > 1) throw new IllegalStateException("TRADE_REPORT_EVIDENCE_AMBIGUOUS");
        return matches.stream().findFirst();
    }

    @Override
    public int updateNullFees(Long owner, long transactionId, BigDecimal fee, BigDecimal tax) {
        Objects.requireNonNull(fee);
        Objects.requireNonNull(tax);
        return jdbc.update("""
                UPDATE asset_transaction SET fee=?, transaction_tax=?
                WHERE owner_user_id=? AND id=? AND source='FUBON_EMAIL'
                    AND fee IS NULL AND transaction_tax IS NULL
                """, fee, tax, owner, transactionId);
    }
}
