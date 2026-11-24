package com.steven.assets.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.function.Consumer;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.*;
import java.util.*;

/** PostgreSQL immutable publication. Every read predicate contains explicit owner identity. */
@Repository
public class SrppReportRepository {
    public record Scope(long owner, LocalDate date, String slot, String policy, String swagger, String calculator) {}
    public record Base(UUID id, Scope scope, String fingerprint, Instant capturedAt, Instant assetGeneratedAt,
                       long snapshotId, UUID valuationId, String input, String vector, String modules, String hash, Instant createdAt) {}
    public record Package(UUID id, UUID sharedId, Scope scope, String consumer, String stateHash, String inputHash,
                          String content, String contentHash, Instant publishedAt) {}
    /** Immutable selectors only: traversing reservations must not transfer every historical body. */
    public record ReservationRef(UUID id, UUID sharedId, Scope scope, String consumer, String stateHash,
                                 String inputHash, String contentHash, String status, Instant publishedAt) {}
    private static final String RESERVATION_METADATA = "id,shared_calculation_id,owner_user_id,consumer,trading_date,report_slot,policy_bundle_sha256,swagger_sha256,calculator_version,consumer_state_sha256,input_snapshot_sha256,content_sha256,status,published_at";
    private final JdbcTemplate jdbc;
    public SrppReportRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    /** Blocking transaction lock; caller acquires it before checking/calculating common modules. */
    public void lock(String identity) { jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,489))", Object.class, identity); }
    /** Same two-key fence used by the existing decision POST; scope capture/reservations cannot race it. */
    public void lockOwnerDate(long owner, LocalDate date) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?,?)", Object.class, (int)owner, Math.toIntExact(date.toEpochDay()));
    }
    public Optional<Base> base(Scope s, String fingerprint) {
        return jdbc.query("SELECT * FROM srpp_report_calculation_base WHERE owner_user_id=? AND trading_date=? AND report_slot=? AND policy_bundle_sha256=? AND swagger_sha256=? AND calculator_version=? AND source_fingerprint_sha256=?", (r,n)->base(r), s.owner(), s.date(), s.slot(), s.policy(), s.swagger(), s.calculator(), fingerprint).stream().findFirst();
    }
    public Optional<Package> packageForState(Base base, String consumer, String stateHash) {
        return jdbc.query("SELECT * FROM srpp_report_calculation_package WHERE owner_user_id=? AND shared_calculation_id=? AND consumer=? AND consumer_state_sha256=?", (r,n)->pkg(r), base.scope().owner(), base.id(), consumer, stateHash).stream().findFirst();
    }
    public Optional<Package> byId(long owner, UUID id) {
        return jdbc.query("SELECT * FROM srpp_report_calculation_package WHERE owner_user_id=? AND id=?", (r,n)->pkg(r), owner, id).stream().findFirst();
    }
    public Optional<Package> latest(Scope s, String consumer) {
        return jdbc.query("SELECT * FROM srpp_report_calculation_package WHERE owner_user_id=? AND consumer=? AND trading_date=? AND report_slot=? AND policy_bundle_sha256=? AND swagger_sha256=? AND calculator_version=? AND status='FINAL' ORDER BY published_at DESC,id DESC LIMIT 1", (r,n)->pkg(r), s.owner(), consumer, s.date(), s.slot(), s.policy(), s.swagger(), s.calculator()).stream().findFirst();
    }
    @Transactional(readOnly=true)
    public void visitPriorReportReservations(long owner, LocalDate from, LocalDate date, String slot, Consumer<String> visitor) {
        // One consumer copy per shared base. Never discard an earlier actionable revision without an exact fill link.
        visit("SELECT DISTINCT ON (shared_calculation_id) content_jcs FROM srpp_report_calculation_package WHERE owner_user_id=? AND trading_date BETWEEN ? AND ? AND (trading_date<? OR report_slot<?) ORDER BY shared_calculation_id,published_at,id", visitor, owner, from, date, date, slot);
    }
    @Transactional(readOnly=true)
    public void visitUnlinkedReportReservations(long owner, LocalDate date, String slot, Consumer<String> visitor) {
        // Policy or week changes do not prove that a prior recommendation was consumed.
        visit("SELECT DISTINCT ON (shared_calculation_id) content_jcs FROM srpp_report_calculation_package WHERE owner_user_id=? AND (trading_date<? OR (trading_date=? AND report_slot<?)) ORDER BY shared_calculation_id,published_at,id", visitor, owner, date, date, slot);
    }
    @Transactional(readOnly=true)
    public void visitPriorReportReservationMetadata(long owner, LocalDate from, LocalDate date, String slot, Consumer<ReservationRef> visitor) {
        visitRows("SELECT DISTINCT ON (shared_calculation_id) " + RESERVATION_METADATA + " FROM srpp_report_calculation_package WHERE owner_user_id=? AND trading_date BETWEEN ? AND ? AND (trading_date<? OR report_slot<?) ORDER BY shared_calculation_id,published_at,id",
                visitor, SrppReportRepository::reservationRef, owner, from, date, date, slot);
    }
    @Transactional(readOnly=true)
    public void visitUnlinkedReportReservationMetadata(long owner, LocalDate date, String slot, Consumer<ReservationRef> visitor) {
        // Same predicate and tie breaker as the historical body cursor; no policy/week/age truncation.
        visitRows("SELECT DISTINCT ON (shared_calculation_id) " + RESERVATION_METADATA + " FROM srpp_report_calculation_package WHERE owner_user_id=? AND (trading_date<? OR (trading_date=? AND report_slot<?)) ORDER BY shared_calculation_id,published_at,id",
                visitor, SrppReportRepository::reservationRef, owner, date, date, slot);
    }
    public Optional<String> reservationBody(ReservationRef ref) {
        Scope s=ref.scope();
        return jdbc.query("SELECT content_jcs FROM srpp_report_calculation_package WHERE owner_user_id=? AND id=? AND shared_calculation_id=? AND consumer=? AND trading_date=? AND report_slot=? AND policy_bundle_sha256=? AND swagger_sha256=? AND calculator_version=? AND consumer_state_sha256=? AND input_snapshot_sha256=? AND content_sha256=? AND status=? AND published_at=?",
                (row,n)->row.getString(1), s.owner(), ref.id(), ref.sharedId(), ref.consumer(), s.date(), s.slot(), s.policy(), s.swagger(), s.calculator(), ref.stateHash(), ref.inputHash(), ref.contentHash(), ref.status(), Timestamp.from(ref.publishedAt())).stream().findFirst();
    }
    private void visit(String sql, Consumer<String> visitor, Object... parameters) {
        visitRows(sql,visitor,row->row.getString(1),parameters);
    }
    private interface RowValue<T> { T read(ResultSet row) throws SQLException; }
    private <T> void visitRows(String sql, Consumer<T> visitor, RowValue<T> value, Object... parameters) {
        Objects.requireNonNull(visitor, "visitor");
        if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("REPORT_RESERVATION_TRANSACTION_REQUIRED");
        jdbc.query(connection -> {
            if (connection.getAutoCommit()) throw new SQLException("REPORT_RESERVATION_CURSOR_REQUIRES_TRANSACTION");
            PreparedStatement statement=connection.prepareStatement(sql);
            try {
                statement.setFetchSize(1);
                for(int i=0;i<parameters.length;i++)statement.setObject(i+1,parameters[i]);
                return statement;
            } catch (SQLException|RuntimeException failure) { statement.close();throw failure; }
        }, (RowCallbackHandler)row -> visitor.accept(value.read(row)));
    }
    public void insert(Base b) {
        Scope s=b.scope(); jdbc.update("INSERT INTO srpp_report_calculation_base(id,owner_user_id,trading_date,report_slot,policy_bundle_sha256,swagger_sha256,calculator_version,source_fingerprint_sha256,captured_at,asset_generated_at,asset_snapshot_id,valuation_capture_id,input_jcs,source_vector_jcs,shared_modules_jcs,shared_content_sha256,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", b.id(),s.owner(),s.date(),s.slot(),s.policy(),s.swagger(),s.calculator(),b.fingerprint(),Timestamp.from(b.capturedAt()),Timestamp.from(b.assetGeneratedAt()),b.snapshotId(),b.valuationId(),b.input(),b.vector(),b.modules(),b.hash(),Timestamp.from(b.createdAt()));
    }
    public void insert(Package p) {
        Scope s=p.scope(); jdbc.update("INSERT INTO srpp_report_calculation_package(id,shared_calculation_id,owner_user_id,consumer,trading_date,report_slot,policy_bundle_sha256,swagger_sha256,calculator_version,consumer_state_sha256,status,input_snapshot_sha256,content_jcs,content_sha256,published_at) VALUES(?,?,?,?,?,?,?,?,?,?,'FINAL',?,?,?,?)", p.id(),p.sharedId(),s.owner(),p.consumer(),s.date(),s.slot(),s.policy(),s.swagger(),s.calculator(),p.stateHash(),p.inputHash(),p.content(),p.contentHash(),Timestamp.from(p.publishedAt()));
    }
    private static Scope scope(ResultSet r)throws SQLException { return new Scope(r.getLong("owner_user_id"),r.getDate("trading_date").toLocalDate(),r.getString("report_slot"),r.getString("policy_bundle_sha256"),r.getString("swagger_sha256"),r.getString("calculator_version")); }
    private static Base base(ResultSet r)throws SQLException { return new Base(r.getObject("id",UUID.class),scope(r),r.getString("source_fingerprint_sha256"),r.getTimestamp("captured_at").toInstant(),r.getTimestamp("asset_generated_at").toInstant(),r.getLong("asset_snapshot_id"),r.getObject("valuation_capture_id",UUID.class),r.getString("input_jcs"),r.getString("source_vector_jcs"),r.getString("shared_modules_jcs"),r.getString("shared_content_sha256"),r.getTimestamp("created_at").toInstant()); }
    private static Package pkg(ResultSet r)throws SQLException { return new Package(r.getObject("id",UUID.class),r.getObject("shared_calculation_id",UUID.class),scope(r),r.getString("consumer"),r.getString("consumer_state_sha256"),r.getString("input_snapshot_sha256"),r.getString("content_jcs"),r.getString("content_sha256"),r.getTimestamp("published_at").toInstant()); }
    private static ReservationRef reservationRef(ResultSet r)throws SQLException { return new ReservationRef(r.getObject("id",UUID.class),r.getObject("shared_calculation_id",UUID.class),scope(r),r.getString("consumer"),r.getString("consumer_state_sha256"),r.getString("input_snapshot_sha256"),r.getString("content_sha256"),r.getString("status"),r.getTimestamp("published_at").toInstant()); }
}
