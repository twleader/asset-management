package com.steven.assets.repository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.service.fubon.FubonSrppSourceStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
@Repository
public class FubonSrppSourceRepository implements FubonSrppSourceStore {
    private final JdbcTemplate jdbc;private final ObjectMapper mapper;
    public FubonSrppSourceRepository(JdbcTemplate jdbc,ObjectMapper mapper){this.jdbc=jdbc;this.mapper=mapper;}
    public Optional<Observation> latest(long owner,LocalDate date){
        return jdbc.query("SELECT * FROM fubon_srpp_source_snapshot WHERE owner_user_id=? AND trading_date=? ORDER BY sequence_id DESC LIMIT 1",(r,n)->{
            ObjectNode payload;try{payload=(ObjectNode)mapper.readTree(r.getString("payload_jcs"));}catch(Exception invalid){throw new IllegalStateException("FUBON_SNAPSHOT_INVALID");}
            return new Observation(r.getObject("id",UUID.class),r.getLong("owner_user_id"),r.getDate("trading_date").toLocalDate(),r.getString("account_fingerprint"),r.getTimestamp("captured_at").toInstant(),r.getString("status"),r.getString("revision"),payload);
        },owner,date).stream().findFirst();
    }
    public void append(Observation o){jdbc.update("INSERT INTO fubon_srpp_source_snapshot(id,owner_user_id,trading_date,account_fingerprint,captured_at,status,revision,payload_jcs) VALUES(?,?,?,?,?,?,?,?)",o.id(),o.owner(),o.date(),o.accountFingerprint(),Timestamp.from(o.capturedAt()),o.status(),o.revision(),com.steven.assets.srpp.SrppJcs.canonicalize(o.payload()));}
}
