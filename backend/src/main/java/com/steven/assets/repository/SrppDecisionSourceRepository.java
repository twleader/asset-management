package com.steven.assets.repository;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;

/** Explicit owner predicates and fixed columns; pure read of the already-confirmed business ledger. */
@Repository
public class SrppDecisionSourceRepository {
    private final JdbcTemplate jdbc;
    public SrppDecisionSourceRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public ArrayNode ledger(long ownerId,LocalDate from,LocalDate through){
        ArrayNode out=JsonNodeFactory.instance.arrayNode();
        jdbc.query("SELECT id,asset_code,market,channel,trade_date,shares,transaction_type,source,broker_filled_no FROM asset_transaction WHERE owner_user_id=? AND trade_date BETWEEN ? AND ? ORDER BY trade_date,id",rs->{
            ObjectNode n=out.addObject();n.put("id",rs.getLong("id"));n.put("symbol",rs.getString("asset_code"));n.put("market",rs.getString("market"));n.put("channel",rs.getString("channel"));n.put("date",rs.getDate("trade_date").toLocalDate().toString());
            if(rs.getBigDecimal("shares")==null)n.putNull("shares");else n.put("shares",rs.getBigDecimal("shares").stripTrailingZeros().toPlainString());
            n.put("direction",rs.getString("transaction_type"));n.put("source",rs.getString("source"));n.put("fillId",rs.getString("broker_filled_no"));
        },ownerId,from,through);return out;
    }
}
