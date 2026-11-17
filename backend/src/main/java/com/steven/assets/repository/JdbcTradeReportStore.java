package com.steven.assets.repository;
import com.steven.assets.service.tradereport.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Optional;
@Repository @RequiredArgsConstructor
public class JdbcTradeReportStore implements TradeReportStore {
 private final JdbcTemplate jdbc;
 public Optional<Receipt> receipt(Long owner,String message){return jdbc.query("SELECT id,raw_sha256 FROM broker_trade_report_receipt WHERE owner_user_id=? AND source_message_id=?",(r,n)->new Receipt(r.getLong(1),r.getString(2)),owner,message).stream().findFirst();}
 public Optional<Evidence> evidence(Long owner,String hash){return jdbc.query("SELECT id,facts_hash,canonical_facts,transaction_id FROM broker_trade_report_import WHERE owner_user_id=? AND identity_hash=?",(r,n)->new Evidence(r.getLong(1),r.getString(2),r.getString(3),r.getObject(4,Long.class)),owner,hash).stream().findFirst();}
 public long addReceipt(Long owner,String message,String raw){return jdbc.queryForObject("INSERT INTO broker_trade_report_receipt(owner_user_id,source_message_id,raw_sha256,parser_version) VALUES(?,?,?,?) RETURNING id",Long.class,owner,message,raw,TradeReportParser.VERSION);}
 public long addEvidence(Long owner,String identity,String facts,String canonical,Long tx){return jdbc.queryForObject("INSERT INTO broker_trade_report_import(owner_user_id,parser_version,identity_hash,facts_hash,canonical_facts,transaction_id) VALUES(?,?,?,?,?,?) RETURNING id",Long.class,owner,TradeReportParser.VERSION,identity,facts,canonical,tx);}
 public void link(long receipt,long evidence){jdbc.update("INSERT INTO broker_trade_report_receipt_line(receipt_id,evidence_id) VALUES(?,?) ON CONFLICT DO NOTHING",receipt,evidence);}
}
