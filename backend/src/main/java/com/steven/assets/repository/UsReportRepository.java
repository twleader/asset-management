package com.steven.assets.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.service.srpp.us.*;
import com.steven.assets.service.srpp.us.UsReportReadPort.*;
import com.steven.assets.srpp.SrppJcs;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Explicit tenant and consumer predicates. Mutations require the publisher's existing transaction. */
@Repository
public class UsReportRepository implements UsReportReadPort {
    private final JdbcTemplate jdbc;
    public UsReportRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
    public void lockOwner(long owner){transaction();jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,542))",Object.class,"US/"+owner);}
    @Override public Optional<Saved> latest(Scope s){return jdbc.query("SELECT * FROM srpp_us_report_package WHERE owner_user_id=? AND consumer=? AND market=? AND session_date=? AND report_slot=? AND policy_bundle_sha256=? AND swagger_sha256=? AND calculator_version=? ORDER BY published_at DESC,id DESC LIMIT 1",(r,n)->saved(r),s.owner(),s.consumer(),s.market(),s.date(),s.slot(),s.policy(),s.swagger(),s.calculator()).stream().findFirst();}
    @Override public Optional<Saved> byId(long owner,UUID id){return jdbc.query("SELECT * FROM srpp_us_report_package WHERE owner_user_id=? AND id=?",(r,n)->saved(r),owner,id).stream().findFirst();}
    public Optional<Saved> forSource(Scope s,String source){return jdbc.query("SELECT * FROM srpp_us_report_package WHERE owner_user_id=? AND consumer=? AND market=? AND session_date=? AND report_slot=? AND policy_bundle_sha256=? AND swagger_sha256=? AND calculator_version=? AND source_fingerprint_sha256=?",(r,n)->saved(r),s.owner(),s.consumer(),s.market(),s.date(),s.slot(),s.policy(),s.swagger(),s.calculator(),source).stream().findFirst();}
    public record State(ObjectNode wrapper,Map<String,Long> versions){}
    public State state(long owner,String consumer){
        ObjectNode out=JsonNodeFactory.instance.objectNode().put("ownerUserId",owner).put("consumer",consumer).put("market","US");
        List<Map<String,Object>> imports=jdbc.queryForList("SELECT ledger_sha256,watch_policy_sha256,evidence_jcs FROM srpp_us_watch_import WHERE owner_user_id=? AND consumer=? AND market='US'",owner,consumer);
        if(imports.isEmpty()){out.put("status","UNAVAILABLE").put("reason","WATCH_STATE_SOURCE_UNAVAILABLE").putNull("importLedgerSha256").putNull("watchPolicySha256").putNull("ledger");out.putObject("versionVector");return new State(out,Map.of());}
        var imported=imports.getFirst();String ledgerHash=imported.get("ledger_sha256").toString(),policyHash=imported.get("watch_policy_sha256").toString();
        // A syntactically valid marker must still retain the independently validated migration evidence.
        JsonNode evidence=SrppJcs.parseStrict(imported.get("evidence_jcs").toString());
        if(!ledgerHash.matches("[a-f0-9]{64}")||!policyHash.matches("[a-f0-9]{64}")||!evidence.isObject()||!evidence.path("ownerUserId").isIntegralNumber()||evidence.path("ownerUserId").asLong()!=owner||!consumer.equals(evidence.path("consumer").asText())||!ledgerHash.equals(evidence.path("ledgerSha256").asText()))throw new UsReportProblem("UPSTREAM_INVALID");
        out.put("status","VERIFIED").putNull("reason").put("importLedgerSha256",ledgerHash).put("watchPolicySha256",policyHash);
        ObjectNode ledger=out.putObject("ledger");ledger.put("decision","D-174").put("consumer",consumer);ObjectNode states=ledger.putObject("states"),vector=out.putObject("versionVector");Map<String,Long> versions=new TreeMap<>();
        jdbc.query("SELECT symbol,version,state_jcs,state_sha256 FROM srpp_us_watch_state WHERE owner_user_id=? AND consumer=? AND market='US' ORDER BY symbol",r->{String symbol=r.getString("symbol"),raw=r.getString("state_jcs");JsonNode state=SrppJcs.parseStrict(raw);if(!SrppJcs.hash(state).equals(r.getString("state_sha256"))||!symbol.equals(state.path("stock_code").asText())||!"美股".equals(state.path("market").asText())||!policyHash.equals(state.path("policy_sha256").asText()))throw new UsReportProblem("UPSTREAM_INVALID");long version=r.getLong("version");states.set(symbol,state);vector.put(symbol,version);versions.put(symbol,version);},owner,consumer);
        return new State(out,Collections.unmodifiableMap(versions));
    }
    public void saveStates(Scope s,State before,JsonNode after,String source,UUID packageId,Instant at){
        transaction();if(after==null||after.isNull())return;
        if(!"VERIFIED".equals(before.wrapper().path("status").asText())||!after.isObject())throw new UsReportProblem("UPSTREAM_INVALID");
        JsonNode old=before.wrapper().path("ledger").path("states");if(!after.properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()).containsAll(old.properties().stream().map(Map.Entry::getKey).toList()))throw new UsReportProblem("UPSTREAM_INVALID");
        after.properties().forEach(e->{String symbol=e.getKey();JsonNode prior=old.has(symbol)?old.get(symbol):NullNode.instance,value=e.getValue();String priorHash=SrppJcs.hash(prior),nextHash=SrppJcs.hash(value);long version=before.versions().getOrDefault(symbol,0L);boolean changed=!prior.equals(value);long next=changed?version+1:version;
            if(changed){int updated;if(version==0)updated=jdbc.update("INSERT INTO srpp_us_watch_state(owner_user_id,consumer,market,symbol,version,state_jcs,state_sha256,updated_at) VALUES(?,?,'US',?,?,?,?,?) ON CONFLICT DO NOTHING",s.owner(),s.consumer(),symbol,next,SrppJcs.canonicalize(value),nextHash,Timestamp.from(at));else updated=jdbc.update("UPDATE srpp_us_watch_state SET version=?,state_jcs=?,state_sha256=?,updated_at=? WHERE owner_user_id=? AND consumer=? AND market='US' AND symbol=? AND version=?",next,SrppJcs.canonicalize(value),nextHash,Timestamp.from(at),s.owner(),s.consumer(),symbol,version);if(updated!=1)throw new UsReportProblem("CONTEXT_NOT_READY");}
            jdbc.update("INSERT INTO srpp_us_watch_transition(id,package_id,owner_user_id,consumer,market,symbol,version_before,version_after,state_before_jcs,state_after_jcs,state_before_sha256,state_after_sha256,source_fingerprint_sha256,calculator_version,created_at) VALUES(?,?,?,?,'US',?,?,?,?,?,?,?,?,?,?)",UUID.randomUUID(),packageId,s.owner(),s.consumer(),symbol,version,next,SrppJcs.canonicalize(prior),SrppJcs.canonicalize(value),priorHash,nextHash,source,s.calculator(),Timestamp.from(at));
        });
    }
    public void insert(Saved p){transaction();Scope s=p.scope();jdbc.update("INSERT INTO srpp_us_report_package(id,owner_user_id,consumer,market,session_date,report_slot,policy_bundle_sha256,swagger_sha256,calculator_version,source_fingerprint_sha256,input_snapshot_sha256,content_sha256,content_jcs,published_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",p.id(),s.owner(),s.consumer(),s.market(),s.date(),s.slot(),s.policy(),s.swagger(),s.calculator(),p.sourceHash(),p.inputHash(),p.contentHash(),p.content(),Timestamp.from(p.publishedAt()));}
    private static void transaction(){if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("US_PUBLICATION_TRANSACTION_REQUIRED");}
    private static Saved saved(ResultSet r)throws SQLException{Scope s=new Scope(r.getLong("owner_user_id"),r.getString("consumer"),r.getString("market"),r.getDate("session_date").toLocalDate(),r.getString("report_slot"),r.getString("policy_bundle_sha256"),r.getString("swagger_sha256"),r.getString("calculator_version"));return new Saved(r.getObject("id",UUID.class),s,r.getString("source_fingerprint_sha256"),r.getString("input_snapshot_sha256"),r.getString("content_sha256"),r.getString("content_jcs"),r.getTimestamp("published_at").toInstant());}
}
