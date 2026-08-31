package com.steven.assets.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL implementation of the immutable complete-capture lookup port. */
@Repository
public class JdbcRadarTechnicalFactRepository implements RadarTechnicalFactPort {
    private static final String MARKET = "台股";
    private static final String PROVIDER = "FUBON_SDK";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcRadarTechnicalFactRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Map<String, Capture> findFreshCompleteCaptures(List<String> stockCodes, Instant now) {
        if (stockCodes == null || stockCodes.isEmpty() || now == null) return Map.of();
        try {
            String placeholders = String.join(",", Collections.nCopies(stockCodes.size(), "?"));
            String sql = """
                    WITH complete AS (
                      SELECT stock_code,capture_id,min(observed_at) AS oldest
                      FROM fubon_technical_capture_member
                      WHERE stock_code IN (%s) AND market=? AND provider=?
                      GROUP BY stock_code,capture_id
                      HAVING count(*)=17 AND count(DISTINCT profile_id)=17 AND min(observed_at)>=?
                    ), picked AS (
                      SELECT stock_code,capture_id,oldest,
                             row_number() OVER (PARTITION BY stock_code ORDER BY oldest DESC,capture_id DESC) AS row_no
                      FROM complete
                    )
                    SELECT p.stock_code,p.capture_id,p.oldest,
                           m.profile_id,m.source_date,m.content_hash,m.observed_at,
                           f.parameters::text,f.payload::text,
                           m.previous_source_date,m.previous_content_hash,previous_fact.payload::text AS previous_payload
                    FROM picked p
                    JOIN fubon_technical_capture_member m ON m.capture_id=p.capture_id
                    JOIN stock_technical_indicator f ON
                      (f.stock_code,f.market,f.provider,f.timeframe,f.profile_id,f.source_date,f.content_hash)=
                      (m.stock_code,m.market,m.provider,m.timeframe,m.profile_id,m.source_date,m.content_hash)
                    LEFT JOIN stock_technical_indicator previous_fact ON
                      (previous_fact.stock_code,previous_fact.market,previous_fact.provider,previous_fact.timeframe,
                       previous_fact.profile_id,previous_fact.source_date,previous_fact.content_hash)=
                      (m.stock_code,m.market,m.provider,m.timeframe,m.profile_id,m.previous_source_date,m.previous_content_hash)
                    WHERE p.row_no=1
                    ORDER BY p.stock_code,m.profile_id
                    """.formatted(placeholders);
            List<Object> args = new ArrayList<>(stockCodes);
            args.add(MARKET);
            args.add(PROVIDER);
            args.add(Timestamp.from(now.minusSeconds(FubonTechnicalFreshness.SECONDS)));
            Map<String, MutableCapture> grouped = new LinkedHashMap<>();
            jdbc.query(sql, rs -> {
                String code = rs.getString("stock_code");
                UUID captureId = rs.getObject("capture_id", UUID.class);
                Instant oldest = rs.getTimestamp("oldest").toInstant();
                MutableCapture capture = grouped.computeIfAbsent(code,
                        ignored -> new MutableCapture(captureId.toString(), oldest));
                capture.candidates.add(candidate(rs.getString("profile_id"),
                        rs.getObject("source_date", LocalDate.class), rs.getString("content_hash"),
                        rs.getTimestamp("observed_at").toInstant(), rs.getString("parameters"), rs.getString("payload"),
                        rs.getObject("previous_source_date", LocalDate.class), rs.getString("previous_content_hash"),
                        rs.getString("previous_payload")));
            }, args.toArray());
            Map<String, Capture> result = new LinkedHashMap<>();
            grouped.forEach((code, capture) -> {
                if (capture.candidates.size() == 17) {
                    result.put(code, new Capture(capture.captureId, capture.oldest, capture.candidates));
                }
            });
            return Map.copyOf(result);
        } catch (RuntimeException unavailable) {
            return Map.of();
        }
    }

    private Candidate candidate(String id, LocalDate date, String hash, Instant observed,
                                String parameters, String payload, LocalDate previousDate,
                                String previousHash, String previousPayload) {
        try {
            Map<String, Object> parsedParameters = json.readValue(parameters, new TypeReference<>() {});
            Map<String, String> parsedPayload = json.readValue(payload, new TypeReference<>() {});
            Map<String, String> parsedPrevious = previousPayload == null ? null
                    : json.readValue(previousPayload, new TypeReference<>() {});
            return new Candidate(id, date, hash, parsedParameters, parsedPayload, observed,
                    previousDate, previousHash, parsedPrevious);
        } catch (RuntimeException | java.io.IOException invalid) {
            throw new IllegalArgumentException("invalid db technical capture", invalid);
        }
    }

    private static final class MutableCapture {
        private final String captureId;
        private final Instant oldest;
        private final List<Candidate> candidates = new ArrayList<>();

        private MutableCapture(String captureId, Instant oldest) {
            this.captureId = captureId;
            this.oldest = oldest;
        }
    }
}
