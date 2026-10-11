package com.steven.assets.repository.aiflow;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAiFlowRepository implements AiFlowRepository {
    private final JdbcTemplate jdbc;
    private static final String COLUMNS = "owner_user_id,run_id,consumer,report_date,report_slot,source_kind,source_run_id,metadata_json,metadata_sha256";
    public JdbcAiFlowRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @Override public void writeDeadline() {
        jdbc.execute("SET LOCAL statement_timeout='5s'");
        jdbc.execute("SET LOCAL lock_timeout='5s'");
    }
    @Override public void insertRun(StoredRun r) {
        jdbc.update("INSERT INTO ai_flow_run(" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                r.owner(), r.runId(), r.consumer(), r.date(), r.slot(), r.sourceKind(), r.sourceRunId(), r.metadata(), r.hash());
    }
    @Override public Optional<StoredRun> lockRun(long owner, UUID runId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM ai_flow_run WHERE owner_user_id=? AND run_id=? FOR UPDATE",
                JdbcAiFlowRepository::run, owner, runId).stream().findFirst();
    }
    @Override public Optional<StoredRun> sourceRun(long owner, String consumer, String sourceKind, String sourceRunId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM ai_flow_run WHERE owner_user_id=? AND consumer=? AND source_kind=? AND source_run_id=? FOR UPDATE",
                JdbcAiFlowRepository::run, owner, consumer, sourceKind, sourceRunId).stream().findFirst();
    }
    @Override public List<StoredRun> list(long owner, LocalDate date, String consumer, String period) {
        return jdbc.query("SELECT " + COLUMNS + " FROM ai_flow_run WHERE owner_user_id=? AND report_date=? AND consumer=? AND report_date >= (transaction_timestamp() AT TIME ZONE 'Asia/Taipei')::date - 89 AND report_date <= (transaction_timestamp() AT TIME ZONE 'Asia/Taipei')::date AND (?='ALL' OR (?='MORNING' AND report_slot='09:05') OR (?='NOON' AND report_slot IN ('12:00','11:40'))) ORDER BY report_slot,run_id",
                JdbcAiFlowRepository::run, owner, date, consumer, period, period, period);
    }
    @Override public LocalDate databaseDate() {
        return jdbc.queryForObject("SELECT (transaction_timestamp() AT TIME ZONE 'Asia/Taipei')::date", LocalDate.class);
    }
    @Override public Optional<StoredRun> detail(long owner, UUID runId, LocalDate date, String consumer) {
        return jdbc.query("SELECT " + COLUMNS + " FROM ai_flow_run WHERE owner_user_id=? AND run_id=? AND report_date=? AND consumer=? AND report_date >= (transaction_timestamp() AT TIME ZONE 'Asia/Taipei')::date - 89 AND report_date <= (transaction_timestamp() AT TIME ZONE 'Asia/Taipei')::date",
                JdbcAiFlowRepository::run, owner, runId, date, consumer).stream().findFirst();
    }
    @Override public List<StoredEvent> events(long owner, UUID runId) {
        return jdbc.query("SELECT e.event_id,e.sequence,e.payload_json,e.payload_sha256 FROM ai_flow_event e JOIN ai_flow_run r ON r.owner_user_id=e.owner_user_id AND r.run_id=e.run_id WHERE e.owner_user_id=? AND e.run_id=? AND r.report_date >= (transaction_timestamp() AT TIME ZONE 'Asia/Taipei')::date - 89 ORDER BY e.sequence,e.event_id",
                (rs, row) -> new StoredEvent(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getString(4)), owner, runId);
    }
    @Override public void insertEvent(long owner, UUID runId, StoredEvent e) {
        jdbc.update("INSERT INTO ai_flow_event(owner_user_id,run_id,event_id,sequence,payload_json,payload_sha256) VALUES (?,?,?,?,?,?)",
                owner, runId, e.eventId(), e.sequence(), e.payload(), e.hash());
    }
    @Override public int purgeExpired(int limit) {
        return jdbc.update("WITH expired AS (SELECT owner_user_id,run_id FROM ai_flow_run WHERE report_date < (transaction_timestamp() AT TIME ZONE 'Asia/Taipei')::date - 89 ORDER BY report_date,owner_user_id,run_id LIMIT ? FOR UPDATE SKIP LOCKED) DELETE FROM ai_flow_run r USING expired x WHERE r.owner_user_id=x.owner_user_id AND r.run_id=x.run_id", limit);
    }
    @Override public boolean expiredRemain() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ai_flow_run WHERE report_date < (transaction_timestamp() AT TIME ZONE 'Asia/Taipei')::date - 89)", Boolean.class));
    }
    private static StoredRun run(ResultSet rs, int row) throws SQLException {
        return new StoredRun(rs.getLong(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getObject(4, LocalDate.class),
                rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9));
    }
}
