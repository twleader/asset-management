package com.steven.assets.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Persisted public market facts and deterministic reconciliation values. */
public final class CanonicalDividend {
    private CanonicalDividend() {}
    public record Key(String code, String market) {}
    public record Component(String id, Integer year, LocalDate cashDate, LocalDate stockDate,
                            BigDecimal cash, BigDecimal stock, LocalDate cashPayment,
                            LocalDate stockPayment, Instant availableAt, boolean withdrawn) {
        public Component(String id, Integer year, LocalDate cashDate, LocalDate stockDate,
                         BigDecimal cash, BigDecimal stock, LocalDate cashPayment,
                         LocalDate stockPayment, Instant availableAt) {
            this(id, year, cashDate, stockDate, cash, stock, cashPayment, stockPayment, availableAt, false);
        }
    }
    public record Evidence(long snapshotId, long observationId, String provider, int version,
                           String basis, boolean groupsComplete, String status, boolean complete,
                           LocalDate scopeFrom, LocalDate scopeTo, Instant observedAt,
                           Instant availableAt, String contentHash, List<Component> components) {
        public Evidence { components = List.copyOf(components); }
    }
    public record Current(long id, Integer year, LocalDate cashDate, LocalDate stockDate,
                          BigDecimal cash, BigDecimal stock, LocalDate cashPayment,
                          LocalDate stockPayment, String source, String eventKey, String status,
                          LocalDate canonicalDate, Long snapshotId, String resolution,
                          String reason, BigDecimal yieldPct, BigDecimal previousClose,
                          Integer fillDays, String updatedAt) {}
    public record Facts(Key key, List<Evidence> evidence, List<Current> current) {
        public Facts { evidence = List.copyOf(evidence); current = List.copyOf(current); }
    }
    public record Day(LocalDate date, BigDecimal cash, BigDecimal stock, LocalDate cashPayment,
                      LocalDate stockPayment, String source, Long snapshotId, Long observationId,
                      Instant observedAt, String resolution, String reason, String eventKey,
                      boolean financial, boolean withdrawn, BigDecimal yieldPct, BigDecimal previousClose, Integer fillDays) {
        public Day(LocalDate date,BigDecimal cash,BigDecimal stock,LocalDate cashPayment,LocalDate stockPayment,
                   String source,Long snapshotId,Long observationId,Instant observedAt,String resolution,String reason,
                   String eventKey,boolean financial,boolean withdrawn){
            this(date,cash,stock,cashPayment,stockPayment,source,snapshotId,observationId,observedAt,resolution,reason,eventKey,financial,withdrawn,null,null,null);
        }
    }
    public record Resolution(List<Day> days) { public Resolution { days = List.copyOf(days); } }
    public record ObservationBatch(long throughId, List<Key> keys) {
        public ObservationBatch { keys = List.copyOf(keys); }
    }
    public record Undated(Integer year,String source,String reason) {}
    public record Display(String code, String market, List<Day> days,List<Undated> undated) {
        public Display { days = List.copyOf(days);undated=List.copyOf(undated); }
        public Display(String code,String market,List<Day> days){this(code,market,days,List.of());}
    }
}
