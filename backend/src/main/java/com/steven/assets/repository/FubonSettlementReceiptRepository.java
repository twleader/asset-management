package com.steven.assets.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.service.fubon.FubonSettlementReceiptStore;
import com.steven.assets.srpp.SrppJcs;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.util.Optional;

@Repository
public class FubonSettlementReceiptRepository implements FubonSettlementReceiptStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public FubonSettlementReceiptRepository(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc=jdbc;this.mapper=mapper; }
    public Optional<Receipt> latest(long owner) {
        return jdbc.query("SELECT * FROM fubon_settlement_receipt WHERE owner_user_id=? ORDER BY id DESC LIMIT 1", (r,n)-> {
            ObjectNode payload;
            try { payload=(ObjectNode)mapper.readTree(r.getString("payload_jcs")); }
            catch(Exception e) { throw new IllegalStateException("SETTLEMENT_RECEIPT_INVALID"); }
            return new Receipt(r.getLong("id"),r.getLong("owner_user_id"),r.getDate("query_date").toLocalDate(),
                    r.getTimestamp("observed_at").toInstant(),r.getString("status"),r.getString("account_fingerprint"),
                    r.getString("payload_hash"),payload,r.getString("reason"));
        },owner).stream().findFirst();
    }
    public long append(Receipt r) {
        return jdbc.queryForObject("INSERT INTO fubon_settlement_receipt(owner_user_id,query_date,observed_at,status,account_fingerprint,payload_hash,payload_jcs,reason) VALUES(?,?,?,?,?,?,?,?) RETURNING id",Long.class,
                r.owner(),r.queryDate(),Timestamp.from(r.observedAt()),r.status(),r.accountFingerprint(),r.payloadHash(),SrppJcs.canonicalize(r.payload()),r.reason());
    }
}
