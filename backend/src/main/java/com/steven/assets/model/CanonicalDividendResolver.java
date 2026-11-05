package com.steven.assets.model;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import static com.steven.assets.model.CanonicalDividend.*;

/** Chooses a complete same-provider date set; amounts never identify an event. */
public final class CanonicalDividendResolver {
    private CanonicalDividendResolver() {}
    private record Part(Component component, BigDecimal cash, BigDecimal stock) {}
    private record Candidate(Evidence evidence, LocalDate date, List<Part> parts, String problem) {}
    private static final String LEGACY_CASH_CONFIRMATION = "保留既有ACTIVE現金，依完整主表確認股票股利為零";

    public static String key(Key key, LocalDate date) {
        return sha256("DIVIDEND_CANONICAL_V2|" + key.market() + "|" + key.code() + "|" + date);
    }
    public static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    public static boolean exact(BigDecimal value) {
        return value == null || (value.signum() >= 0 && value.stripTrailingZeros().scale() <= 18
                && value.precision() - value.scale() <= 12);
    }
    public static Resolution resolve(Facts facts, Instant instant, LocalDate today, boolean display) {
        Map<LocalDate, List<Candidate>> candidates = new TreeMap<>();
        List<Evidence> admissible = facts.evidence().stream().filter(e -> usable(e, facts.key().market(), instant)).toList();
        // A stable component id may move between dates only within that exact provider lineage.
        Map<String, Evidence> latestComponent = new HashMap<>();
        for (Evidence e : admissible) for (Component c : e.components()) {
            if (stableIdentity(e.provider(), c) && available(c.availableAt(), instant)) {
                String id = e.provider() + "|" + c.id();
                Evidence old = latestComponent.get(id);
                if (old == null || newer(e, old)) latestComponent.put(id, e);
            }
        }
        Set<LocalDate> movedFrom = new HashSet<>();
        Set<LocalDate> incompleteDates = new HashSet<>();
        Map<LocalDate, Map<String, Evidence>> uncertainty = new HashMap<>();
        for (Evidence e : facts.evidence()) if (knownSuccessful(e, facts.key().market(), instant) && !e.groupsComplete())
            for (LocalDate date : componentDates(e, instant)) {
                Map<String, Evidence> providers = uncertainty.computeIfAbsent(date, ignored -> new HashMap<>());
                Evidence prior = providers.get(e.provider());
                if (prior == null || newer(e, prior)) providers.put(e.provider(), e);
            }
        for (Evidence e : facts.evidence()) if(e.version()==2 && available(e.observedAt(),instant) && available(e.availableAt(),instant))
            for(Component c:e.components()) if(available(c.availableAt(),instant)) {
                if(c.cashDate()!=null&&inScope(c.cashDate(),e))incompleteDates.add(c.cashDate());
                if(c.stockDate()!=null&&inScope(c.stockDate(),e))incompleteDates.add(c.stockDate());
            }
        for (Evidence e : admissible) {
            Map<LocalDate, List<Part>> groups = new TreeMap<>();
            for (Component c : e.components()) {
                if (!available(c.availableAt(), instant)) continue;
                Evidence last = stableIdentity(e.provider(), c) ? latestComponent.get(e.provider() + "|" + c.id()) : null;
                if (last != null && newer(last, e)) {
                    Component latest = last.components().stream().filter(x -> Objects.equals(c.id(), x.id())).findFirst().orElse(null);
                    if (latest != null && (!Objects.equals(c.cashDate(), latest.cashDate()) || !Objects.equals(c.stockDate(), latest.stockDate()))) {
                        if (c.cashDate() != null) movedFrom.add(c.cashDate());
                        if (c.stockDate() != null) movedFrom.add(c.stockDate());
                    }
                    continue;
                }
                if (c.cashDate() != null && inScope(c.cashDate(), e))
                    groups.computeIfAbsent(c.cashDate(), x -> new ArrayList<>()).add(new Part(c, c.cash(), BigDecimal.ZERO));
                if (c.stockDate() != null && inScope(c.stockDate(), e))
                    groups.computeIfAbsent(c.stockDate(), x -> new ArrayList<>()).add(new Part(c, BigDecimal.ZERO, c.stock()));
            }
            for (var entry : groups.entrySet()) {
                String problem = null;
                Map<String, Component> ids = new HashMap<>();
                for (Part part : entry.getValue()) {
                    Component c = part.component();
                    if (!exact(c.cash()) || !exact(c.stock())) problem = "配息數值超出可精確表示範圍";
                    if (c.cash() == null || c.stock() == null) problem = "配息日期組缺少可確認的現金或配股分項";
                    if (c.id() == null || c.id().isBlank()) {
                        if (entry.getValue().stream().map(Part::component).distinct().count() > 1)
                            problem = "同日多筆分配缺少可驗元件身分";
                    } else {
                        Component prior = ids.putIfAbsent(c.id(), c);
                        if (prior != null && !prior.equals(c)) problem = "同一上游元件身分出現不同內容";
                    }
                }
                candidates.computeIfAbsent(entry.getKey(), x -> new ArrayList<>())
                        .add(new Candidate(e, entry.getKey(), entry.getValue(), problem));
            }
        }
        SortedSet<LocalDate> dates = new TreeSet<>(candidates.keySet());
        for (Current row : facts.current()) if ("ACTIVE".equals(row.status()) || "CANCELLED".equals(row.status()) && row.canonicalDate()!=null) {
            if (row.cashDate() != null) dates.add(row.cashDate());
            if (row.stockDate() != null) dates.add(row.stockDate());
            if (row.canonicalDate() != null) dates.add(row.canonicalDate());
        }
        dates.addAll(movedFrom);
        dates.addAll(incompleteDates);
        Set<LocalDate> unprovenMoves=new HashSet<>();
        for(Evidence old:admissible) for(Evidence newer:admissible) if(old.provider().equals(newer.provider())&&newer(newer,old)) {
            Set<LocalDate> oldDates=new HashSet<>(),newDates=new HashSet<>();
            for(Component c:old.components()) { if(c.cashDate()!=null&&inScope(c.cashDate(),newer))oldDates.add(c.cashDate());if(c.stockDate()!=null&&inScope(c.stockDate(),newer))oldDates.add(c.stockDate()); }
            for(Component c:newer.components()) { if(c.cashDate()!=null&&inScope(c.cashDate(),old))newDates.add(c.cashDate());if(c.stockDate()!=null&&inScope(c.stockDate(),old))newDates.add(c.stockDate()); }
            Set<LocalDate> removed=new HashSet<>(oldDates);removed.removeAll(newDates);Set<LocalDate> added=new HashSet<>(newDates);added.removeAll(oldDates);
            // Only upstream identities independent of action dates can prove a move.
            for (Component c : old.components()) if (stableIdentity(old.provider(), c))
                for (Component n : newer.components()) if (stableIdentity(newer.provider(), n) && c.id().equals(n.id())) {
                    if (!Objects.equals(c.cashDate(), n.cashDate())) { removed.remove(c.cashDate()); added.remove(n.cashDate()); }
                    if (!Objects.equals(c.stockDate(), n.stockDate())) { removed.remove(c.stockDate()); added.remove(n.stockDate()); }
                }
            boolean dateBased = old.components().stream().anyMatch(c -> !stableIdentity(old.provider(), c)
                    && (removed.contains(c.cashDate()) || removed.contains(c.stockDate())))
                    || newer.components().stream().anyMatch(c -> !stableIdentity(newer.provider(), c)
                    && (added.contains(c.cashDate()) || added.contains(c.stockDate())));
            if(dateBased&&!removed.isEmpty()&&!added.isEmpty()){unprovenMoves.addAll(removed);unprovenMoves.addAll(added);}
        }
        List<Day> result = new ArrayList<>();
        for (LocalDate date : dates) {
            List<Candidate> choices = candidates.getOrDefault(date, List.of());
            if(unprovenMoves.contains(date)){result.add(unresolved(facts.key(),date,"來源日期組疑似跨日修訂，但缺少可驗元件關聯，未同時發布兩日正事件"));continue;}
            boolean hasCurrent=facts.current().stream().anyMatch(row->("ACTIVE".equals(row.status())||"CANCELLED".equals(row.status()))
                    &&(date.equals(row.cashDate())||date.equals(row.stockDate())||date.equals(row.canonicalDate())));
            if(!display&&date.isAfter(today)&&!hasCurrent&&admissible.stream().noneMatch(e->futureComplete(e,today)&&inScope(date,e)))continue;
            // Keep the newest whole date set per provider; never concatenate revisions.
            Map<String, Candidate> perProvider = new HashMap<>();
            for (Candidate candidate : choices) {
                if (!display && date.isAfter(today) && !futureComplete(candidate.evidence(), today)) continue;
                Evidence uncertain = uncertainty.getOrDefault(date, Map.of()).get(candidate.evidence().provider());
                if (uncertain != null && newer(uncertain, candidate.evidence())) continue;
                Candidate old = perProvider.get(candidate.evidence().provider());
                if (old == null || newer(candidate.evidence(), old.evidence())) perProvider.put(candidate.evidence().provider(), candidate);
            }
            List<Candidate> ranked = perProvider.values().stream().sorted(Comparator
                    .comparingInt((Candidate c) -> rank(facts.key().market(), c.evidence().provider()))
                    .thenComparing(c -> c.evidence().observedAt(), Comparator.reverseOrder())
                    .thenComparing(c -> c.evidence().observationId(), Comparator.reverseOrder())).toList();
            Evidence withdrawal=completeAbsence(facts,admissible,choices,date,today,uncertainty.getOrDefault(date,Map.of()));
            if(withdrawal!=null){
                result.add(new Day(date,BigDecimal.ZERO,BigDecimal.ZERO,null,null,withdrawal.provider(),withdrawal.snapshotId(),withdrawal.observationId(),withdrawal.observedAt(),
                        "CANCELLED","已驗證完整未來範圍明示該日期無事件",key(facts.key(),date),true,true));
                continue;
            }
            if (ranked.isEmpty()) {
                Current existing = facts.current().stream().filter(r -> "ACTIVE".equals(r.status()) && date.equals(r.canonicalDate())
                        && "RESOLVED".equals(r.resolution())).findFirst().orElse(null);
                Evidence blocker = existing == null ? null : uncertainty.getOrDefault(date, Map.of()).get(existing.source());
                Evidence existingEvidence = existing == null ? null : facts.evidence().stream()
                        .filter(e -> Objects.equals(e.snapshotId(), existing.snapshotId()))
                        .max((a,b) -> newer(a,b) ? 1 : newer(b,a) ? -1 : 0).orElse(null);
                boolean shadowed = blocker != null && (existingEvidence == null || newer(blocker, existingEvidence));
                if (existing != null && !shadowed && !movedFrom.contains(date)) {
                    result.add(new Day(date, existing.cash(), existing.stock(), existing.cashPayment(), existing.stockPayment(),
                            existing.source(), existing.snapshotId(), null, null, "RESOLVED", existing.reason(), key(facts.key(), date), true, false));
                } else if (movedFrom.contains(date)) {
                    result.add(new Day(date, BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, null, null, null,
                            "RESOLVED", "具穩定元件身分的新修訂已移至其他作用日", key(facts.key(), date), true, true));
                } else result.add(unresolved(facts.key(), date, uncertainty.containsKey(date)
                        ? "同來源較新公告明示日期組不完整，舊金額尚不可繼續採用"
                        : "僅有未驗證舊資料，尚無可採用的完整原始日期組"));
                continue;
            }
            Candidate selected = ranked.getFirst();
            Day resolved = aggregate(facts.key(), selected, today);
            for (Candidate alternative : ranked) {
                if (rank(facts.key().market(), alternative.evidence().provider()) != rank(facts.key().market(), selected.evidence().provider())) break;
                Day other = aggregate(facts.key(), alternative, today);
                if (!sameAmount(resolved.cash(), other.cash()) || !sameAmount(resolved.stock(), other.stock())) {
                    resolved = unresolved(facts.key(), date, "同等權威來源的日期組相互矛盾"); break;
                }
            }
            if (cancelled(facts.current(), date, resolved)) {
                if (legacyCashContinuation(facts, selected.evidence(), resolved, today)) resolved = confirmationReason(resolved);
                else resolved = unresolved(facts.key(), date, "既有撤回紀錄未獲足夠證據解除，未由歷史部分資料復活");
            } else if (retainsLegacyConfirmation(facts, resolved)) resolved = confirmationReason(resolved);
            result.add(resolved);
        }
        return new Resolution(result);
    }
    private static Evidence completeAbsence(Facts facts,List<Evidence> all,List<Candidate> choices,LocalDate date,LocalDate today,Map<String,Evidence> uncertainty){
        if(!date.isAfter(today)){
            // Preserve a previously established canonical cancellation after its event date.
            Current cancelled=facts.current().stream().filter(r->"CANCELLED".equals(r.status())&&date.equals(r.canonicalDate())).findFirst().orElse(null);
            return cancelled==null?null:all.stream().filter(e->Objects.equals(e.snapshotId(),cancelled.snapshotId())).findFirst().orElse(null);
        }
        List<Evidence> absence=all.stream().filter(e->futureComplete(e,today)&&inScope(date,e)
                &&(uncertainty.get(e.provider())==null||!newer(uncertainty.get(e.provider()),e))
                &&e.components().stream().noneMatch(c->date.equals(c.cashDate())||date.equals(c.stockDate())))
                .sorted(Comparator.comparingInt((Evidence e)->rank(facts.key().market(),e.provider()))
                        .thenComparing(Evidence::observedAt,Comparator.reverseOrder()).thenComparing(Evidence::observationId,Comparator.reverseOrder())).toList();
        for(Evidence e:absence){
            boolean blocks=choices.stream().anyMatch(c->rank(facts.key().market(),c.evidence().provider())<rank(facts.key().market(),e.provider())
                    ||rank(facts.key().market(),c.evidence().provider())==rank(facts.key().market(),e.provider())&&!newer(e,c.evidence()));
            if(blocks)continue;
            boolean protects=facts.current().stream().filter(r->"ACTIVE".equals(r.status())&&date.equals(r.canonicalDate())&&"RESOLVED".equals(r.resolution()))
                    .anyMatch(r->rank(facts.key().market(),r.source())<rank(facts.key().market(),e.provider()));
            if(!protects)return e;
        }
        return null;
    }
    private static boolean cancelled(List<Current> rows, LocalDate date, Day selected) {
        boolean tombstone = rows.stream().anyMatch(r -> "CANCELLED".equals(r.status())
                && (date.equals(r.cashDate()) || date.equals(r.stockDate()) || date.equals(r.canonicalDate())));
        boolean twin = rows.stream().anyMatch(r -> "ACTIVE".equals(r.status()) && (date.equals(r.cashDate()) || date.equals(r.stockDate()))
                && sameAmount(r.cash(), selected.cash()) && sameAmount(r.stock(), selected.stock()));
        return tombstone && !twin;
    }
    /** Preserve an already ACTIVE cash fact; only the complete primary set supplies its missing stock zero. */
    private static boolean legacyCashContinuation(Facts facts, Evidence evidence, Day selected, LocalDate today) {
        LocalDate date = selected.date();
        if (!date.isBefore(today) || !"台股".equals(facts.key().market()) || !"RESOLVED".equals(selected.resolution())
                || selected.withdrawn() || selected.cash() == null || selected.cash().signum() <= 0
                || selected.stock() == null || selected.stock().signum() != 0
                || evidence.version() != 2 || !"NOMINAL_PER_EX_DATE".equals(evidence.basis())
                || !evidence.groupsComplete() || !"FinMind[TaiwanStockDividend]".equals(evidence.provider())) return false;
        List<Current> related = facts.current().stream().filter(r -> date.equals(r.cashDate())
                || date.equals(r.stockDate()) || date.equals(r.canonicalDate())).toList();
        if (related.stream().anyMatch(r -> "CANCELLED".equals(r.status()) && !legacy(r))) return false;
        List<Current> legacy = related.stream().filter(CanonicalDividendResolver::legacy).toList();
        // Do not overlook a known contradictory legacy component or borrow a different date's payment.
        if (legacy.stream().anyMatch(r -> r.cash() != null && !sameAmount(r.cash(), selected.cash())
                || r.stock() != null && !sameAmount(r.stock(), selected.stock())
                || r.cashDate() != null && !date.equals(r.cashDate())
                || r.stockDate() != null && !date.equals(r.stockDate())
                || r.cashPayment() != null && !r.cashPayment().equals(selected.cashPayment())
                || r.stockPayment() != null && !r.stockPayment().equals(selected.stockPayment()))) return false;
        boolean active = legacy.stream().anyMatch(r -> "ACTIVE".equals(r.status()) && date.equals(r.cashDate())
                && sameAmount(r.cash(), selected.cash()) && r.stock() == null && r.stockDate() == null && r.stockPayment() == null);
        boolean cancelled = legacy.stream().anyMatch(r -> "CANCELLED".equals(r.status()) && date.equals(r.cashDate())
                && sameAmount(r.cash(), selected.cash()) && r.stock() != null && r.stock().signum() == 0);
        return active && cancelled;
    }
    private static boolean legacy(Current row) {
        return row.canonicalDate() == null && row.snapshotId() == null && "LEGACY".equals(row.resolution());
    }
    /** A saved explanation remains metadata only: the existing exact twin already passed the guard. */
    private static boolean retainsLegacyConfirmation(Facts facts, Day day) {
        return facts.current().stream().anyMatch(r -> "ACTIVE".equals(r.status()) && "RESOLVED".equals(r.resolution())
                && day.date().equals(r.canonicalDate()) && Objects.equals(r.snapshotId(), day.snapshotId())
                && sameAmount(r.cash(), day.cash()) && sameAmount(r.stock(), day.stock())
                && Objects.equals(r.cashPayment(), day.cashPayment()) && Objects.equals(r.stockPayment(), day.stockPayment())
                && r.reason() != null && r.reason().contains(LEGACY_CASH_CONFIRMATION));
    }
    private static Day confirmationReason(Day day) {
        String reason = day.reason() == null ? LEGACY_CASH_CONFIRMATION : day.reason() + "；" + LEGACY_CASH_CONFIRMATION;
        return new Day(day.date(), day.cash(), day.stock(), day.cashPayment(), day.stockPayment(), day.source(),
                day.snapshotId(), day.observationId(), day.observedAt(), day.resolution(), reason, day.eventKey(),
                day.financial(), day.withdrawn(), day.yieldPct(), day.previousClose(), day.fillDays());
    }
    private static boolean usable(Evidence e, String market, Instant now) {
        return e.groupsComplete() && knownSuccessful(e, market, now);
    }
    private static boolean knownSuccessful(Evidence e, String market, Instant now) {
        return e.version() == 2 && "NOMINAL_PER_EX_DATE".equals(e.basis())
                && e.observedAt() != null && available(e.observedAt(), now) && available(e.availableAt(), now)
                && Set.of("COMPLETE", "EMPTY_COMPLETE", "PARTIAL").contains(e.status()) && rank(market, e.provider()) < 100;
    }
    private static Set<LocalDate> componentDates(Evidence e, Instant now) {
        Set<LocalDate> dates = new HashSet<>();
        for (Component c : e.components()) if (available(c.availableAt(), now)) {
            if (c.cashDate() != null && inScope(c.cashDate(), e)) dates.add(c.cashDate());
            if (c.stockDate() != null && inScope(c.stockDate(), e)) dates.add(c.stockDate());
        }
        return dates;
    }
    private static boolean stableIdentity(String provider, Component component) {
        String id = component.id();
        if (id == null || id.isBlank()) return false;
        // All remaining current producers identify rows by action date (including calendar/pay date).
        return "FinMind[TaiwanStockDividend]".equals(provider)
                && (id.startsWith("FINMIND_PRIMARY_ID:") || id.startsWith("FINMIND_PRIMARY_PERIOD:"))
                || ("NASDAQ.stocks".equals(provider) || "NASDAQ.etf".equals(provider)) && id.startsWith("NASDAQ:");
    }
    public static int rank(String market, String provider) {
        if(provider == null) return 100;
        if("台股".equals(market)) return switch(provider) {
            case "TWSE_TWT48U_ALL+TPEX_EXRIGHT_PREPOST" -> 0;
            case "FinMind[TaiwanStockDividend]" -> 10;
            case "FinMind[TaiwanStockDividendResult]" -> 20;
            case "FUBON_SDK" -> 30;
            default -> 100;
        };
        if("美股".equals(market)) return switch(provider) {
            case "NASDAQ_DIVIDEND_CALENDAR" -> 0;
            case "NASDAQ.stocks", "NASDAQ.etf" -> 10;
            case "Yahoo Finance (nominal from verified splits)" -> 20;
            default -> 100;
        };
        return 100;
    }
    private static boolean available(Instant at, Instant now) { return at == null || !at.isAfter(now); }
    private static boolean newer(Evidence a, Evidence b) {
        int order = a.observedAt().compareTo(b.observedAt());
        return order > 0 || order == 0 && a.observationId() > b.observationId();
    }
    private static boolean inScope(LocalDate date, Evidence e) {
        return e.scopeFrom() != null && e.scopeTo() != null && !date.isBefore(e.scopeFrom()) && !date.isAfter(e.scopeTo());
    }
    private static boolean futureComplete(Evidence e, LocalDate today) {
        return e.complete() && Set.of("COMPLETE", "EMPTY_COMPLETE").contains(e.status()) && inScope(today, e) && inScope(today.plusDays(45), e);
    }
    private static Day aggregate(Key key, Candidate c, LocalDate today) {
        if (c.problem() != null) return unresolved(key, c.date(), c.problem());
        Map<String, Part> distinct = new LinkedHashMap<>();
        for (Part part : c.parts()) {
            String id = part.component().id() == null ? "ANONYMOUS" : part.component().id();
            // Cash and stock parts of the same component each contribute once.
            distinct.putIfAbsent(id + "|" + (part.cash().signum() != 0 ? "cash" : part.stock().signum() != 0 ? "stock" : "zero"), part);
        }
        BigDecimal cash = BigDecimal.ZERO, stock = BigDecimal.ZERO;
        Set<LocalDate> cashPay = new HashSet<>(), stockPay = new HashSet<>();
        boolean withdrawn = !distinct.isEmpty();
        boolean cashPayUnknown=false, stockPayUnknown=false;
        for (Part part : distinct.values()) {
            withdrawn &= part.component().withdrawn();
            if (part.component().withdrawn()) continue;
            cash = cash.add(part.cash()); stock = stock.add(part.stock());
            if(part.cash().signum()>0&&part.component().cashPayment()==null)cashPayUnknown=true;
            if(part.stock().signum()>0&&part.component().stockPayment()==null)stockPayUnknown=true;
            if (part.cash().signum() > 0 && part.component().cashPayment() != null) cashPay.add(part.component().cashPayment());
            if (part.stock().signum() > 0 && part.component().stockPayment() != null) stockPay.add(part.component().stockPayment());
        }
        boolean financial = !c.date().isAfter(today) || futureComplete(c.evidence(), today);
        return new Day(c.date(), withdrawn ? BigDecimal.ZERO : cash, withdrawn ? BigDecimal.ZERO : stock,
                cashPay.size() == 1 && !cashPayUnknown ? cashPay.iterator().next() : null, stockPay.size() == 1 && !stockPayUnknown ? stockPay.iterator().next() : null,
                c.evidence().provider(), c.evidence().snapshotId(), c.evidence().observationId(), c.evidence().observedAt(),
                withdrawn ? "CANCELLED" : financial ? "RESOLVED" : "FUTURE_PARTIAL", cashPay.size() > 1 || stockPay.size() > 1 || cashPayUnknown || stockPayUnknown ? "發放日有多值或部分未提供，請依分項公告確認" : financial ? null : "已公告配息估算，尚未證明未來期間或全年資料完整",
                key(key, c.date()), financial, withdrawn);
    }
    private static boolean sameAmount(BigDecimal a, BigDecimal b) { return a == null ? b == null : b != null && a.compareTo(b) == 0; }
    private static Day unresolved(Key key, LocalDate date, String reason) {
        return new Day(date, null, null, null, null, null, null, null, null, "UNRESOLVED", reason, key(key, date), true, false);
    }
}
