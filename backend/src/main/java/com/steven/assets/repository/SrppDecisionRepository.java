package com.steven.assets.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;

/** PostgreSQL-only immutable run and bounded capture-lease persistence. */
@Repository
public class SrppDecisionRepository {
    public record Run(UUID id,long owner,LocalDate date,String slot,String policy,String swagger,String input,String inputHash,String content,String contentHash,Instant createdAt,Instant finalizedAt) {}
    public record Claim(UUID id,long owner,LocalDate date,String slot,String policy,String swagger,UUID generation,Instant claimedAt,Instant leaseUntil) {}
    private final JdbcTemplate jdbc;
    public SrppDecisionRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public Optional<Run> find(long owner,LocalDate date,String slot){return jdbc.query("SELECT * FROM srpp_decision_run WHERE owner_user_id=? AND trading_date=? AND slot=?",(r,n)->new Run(r.getObject("id",UUID.class),r.getLong("owner_user_id"),r.getDate("trading_date").toLocalDate(),r.getString("slot"),r.getString("policy_bundle_sha256"),r.getString("swagger_sha256"),r.getString("input_jcs"),r.getString("input_snapshot_sha256"),r.getString("content_jcs"),r.getString("decision_content_sha256"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("finalized_at").toInstant()),owner,date,slot).stream().findFirst();}
    public Optional<Claim> claim(long owner,LocalDate date,String slot,boolean lock){return jdbc.query("SELECT * FROM srpp_decision_capture_claim WHERE owner_user_id=? AND trading_date=? AND slot=?"+(lock?" FOR UPDATE":""),(r,n)->new Claim(r.getObject("id",UUID.class),r.getLong("owner_user_id"),r.getDate("trading_date").toLocalDate(),r.getString("slot"),r.getString("policy_bundle_sha256"),r.getString("swagger_sha256"),r.getObject("generation",UUID.class),r.getTimestamp("claimed_at").toInstant(),r.getTimestamp("lease_until").toInstant()),owner,date,slot).stream().findFirst();}
    public void insertClaim(Claim c){jdbc.update("INSERT INTO srpp_decision_capture_claim VALUES (?,?,?,?,?,?,?,?,?)",c.id(),c.owner(),c.date(),c.slot(),c.policy(),c.swagger(),c.generation(),Timestamp.from(c.claimedAt()),Timestamp.from(c.leaseUntil()));}
    public Claim renew(Claim c,Instant now){UUID generation=UUID.randomUUID();jdbc.update("UPDATE srpp_decision_capture_claim SET generation=?,claimed_at=?,lease_until=? WHERE id=? AND generation=?",generation,Timestamp.from(now),Timestamp.from(now.plusSeconds(30)),c.id(),c.generation());return new Claim(c.id(),c.owner(),c.date(),c.slot(),c.policy(),c.swagger(),generation,now,now.plusSeconds(30));}
    public void release(Claim c){jdbc.update("DELETE FROM srpp_decision_capture_claim WHERE id=? AND generation=?",c.id(),c.generation());}
    public boolean scopeLock(long owner,LocalDate date){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?,?)",Boolean.class,Math.toIntExact(owner),Math.toIntExact(date.toEpochDay())));}
    public boolean otherActive(Claim c,Instant now){Integer n=jdbc.queryForObject("SELECT count(*) FROM srpp_decision_capture_claim WHERE owner_user_id=? AND trading_date=? AND id<>? AND lease_until>? AND (claimed_at<? OR (claimed_at=? AND id::text<?))",Integer.class,c.owner(),c.date(),c.id(),Timestamp.from(now),Timestamp.from(c.claimedAt()),Timestamp.from(c.claimedAt()),c.id().toString());return n!=null&&n>0;}
    public List<Run> reservations(long owner,LocalDate from,LocalDate through){return jdbc.query("SELECT owner_user_id,trading_date,slot FROM srpp_decision_run WHERE owner_user_id=? AND trading_date BETWEEN ? AND ? ORDER BY trading_date,slot",(r,n)->find(r.getLong(1),r.getDate(2).toLocalDate(),r.getString(3)).orElseThrow(),owner,from,through);}
    public void insert(Run r){jdbc.update("INSERT INTO srpp_decision_run(id,owner_user_id,trading_date,slot,policy_bundle_sha256,swagger_sha256,status,input_jcs,input_snapshot_sha256,content_jcs,decision_content_sha256,created_at,finalized_at) VALUES(?,?,?,?,?,?,'FINAL',?,?,?,?,?,?)",r.id(),r.owner(),r.date(),r.slot(),r.policy(),r.swagger(),r.input(),r.inputHash(),r.content(),r.contentHash(),Timestamp.from(r.createdAt()),Timestamp.from(r.finalizedAt()));}
}
