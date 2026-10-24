package com.steven.assets.service.aiflow;

import com.steven.assets.dto.aiflow.AiFlowDtos.*;
import com.steven.assets.repository.aiflow.AiFlowRepository;
import com.steven.assets.repository.aiflow.AiFlowRepository.*;
import com.steven.assets.srpp.SrppJcs;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.MultiValueMap;

/** Task492：授權後原子追加與純讀查詢，從不觸發金融計算或外部請求。 */
@Service
public class AiFlowService {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private final AiFlowRepository repository;
    private final AiFlowOwnerPort owner;
    private final AiFlowCredentialService credentials;
    private final Clock clock;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;
    @Autowired
    public AiFlowService(AiFlowRepository repository, AiFlowOwnerPort owner,
                         AiFlowCredentialService credentials, PlatformTransactionManager manager) {
        this(repository, owner, credentials, manager, Clock.systemUTC());
    }
    public AiFlowService(AiFlowRepository repository, AiFlowOwnerPort owner,
                         AiFlowCredentialService credentials, PlatformTransactionManager manager, Clock clock) {
        this.repository = repository; this.owner = owner; this.credentials = credentials; this.clock = clock;
        writes = new TransactionTemplate(manager); writes.setTimeout(5);
        reads = new TransactionTemplate(manager); reads.setReadOnly(true); reads.setTimeout(5);
    }
    public AppendResult ingest(List<String> token, String contentType, String raw) {
        var grant = credentials.authenticate(token);
        try {
            var type = MediaType.parseMediaType(contentType == null ? "" : contentType);
            if (!type.getType().equalsIgnoreCase("application") || !type.getSubtype().equalsIgnoreCase("json"))
                throw new AiFlowProblem("UNSUPPORTED_MEDIA_TYPE");
        } catch (IllegalArgumentException ignored) { throw new AiFlowProblem("UNSUPPORTED_MEDIA_TYPE"); }
        var validated = AiFlowProtocol.parse(raw); Run r = validated.batch().run();
        credentials.authorize(grant, r.consumer(), r.sourceKind());
        credentials.authorizeChoiceSet(grant, r.consumer(), r.sourceKind(),
                validated.batch().events().stream().anyMatch(event -> event.choiceSet() != null));
        credentials.authorizePublicDecision(grant, r.consumer(), r.sourceKind(),
                validated.batch().events().stream().anyMatch(event -> event.publicDecision() != null));
        window(r.reportDate());
        long ownerId = owner.configuredOwner();
        try {
            return Objects.requireNonNull(writes.execute(status -> append(ownerId, validated)));
        } catch (DataAccessException failure) {
            throw mapDatabase(failure);
        }
    }
    private AppendResult append(long ownerId, AiFlowProtocol.Validated validated) {
        repository.writeDeadline();
        Run r = validated.batch().run(); UUID runId = UUID.fromString(r.runId());
        StoredRun candidate = new StoredRun(ownerId, runId, r.consumer(), r.reportDate(),
                r.reportSlot(), r.sourceKind(), r.sourceRunId(), validated.metadataJson(), validated.metadataHash());
        repository.insertRun(candidate);
        StoredRun existing = repository.lockRun(ownerId, runId).orElseGet(() -> repository.sourceRun(ownerId,
                r.consumer(), r.sourceKind(), r.sourceRunId()).orElseThrow(() -> new AiFlowProblem("RUN_METADATA_MISMATCH")));
        if (!existing.runId().equals(runId) || !existing.metadata().equals(candidate.metadata())
                || !existing.hash().equals(candidate.hash())) throw new AiFlowProblem("RUN_METADATA_MISMATCH");
        List<StoredEvent> saved = repository.events(ownerId, runId);
        Map<String, StoredEvent> savedIds = new HashMap<>(); List<Event> graph = new ArrayList<>();
        for (StoredEvent event : saved) {
            requireHash(event.payload(), event.hash());
            savedIds.put(event.eventId().toString(), event); graph.add(AiFlowProtocol.readEvent(event.payload()));
        }
        List<StoredEvent> inserts = new ArrayList<>(); int replayed = 0;
        for (int i = 0; i < validated.batch().events().size(); i++) {
            Event e = validated.batch().events().get(i); StoredEvent old = savedIds.get(e.eventId());
            if (old != null) {
                if (!old.payload().equals(validated.payloads().get(i)) || !old.hash().equals(validated.hashes().get(i)))
                    throw new AiFlowProblem("EVENT_CONFLICT");
                replayed++;
            } else {
                inserts.add(new StoredEvent(UUID.fromString(e.eventId()), e.sequence(), validated.payloads().get(i), validated.hashes().get(i)));
                graph.add(e);
            }
        }
        AiFlowProjection.validateGraph(graph);
        for (StoredEvent e : inserts) repository.insertEvent(ownerId, runId, e);
        return new AppendResult(inserts.isEmpty() ? 200 : 201,
                new Ack(r.runId(), inserts.size(), replayed, validated.batch().events().stream().map(Event::eventId).toList()));
    }
    public RunList list(MultiValueMap<String, String> query) {
        Parsed q = query(query, true); window(q.date()); long ownerId = owner.readOwner();
        return Objects.requireNonNull(reads.execute(tx -> {
            LocalDate databaseToday = repository.databaseDate();
            window(q.date(), databaseToday);
            List<RunView> runs = repository.list(ownerId, q.date(), q.consumer(), q.period()).stream()
                    .map(r -> project(r).run()).sorted(Comparator.comparing(RunView::reportSlot)
                            .thenComparing(RunView::startedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(RunView::runId)).toList();
            return new RunList(q.date(), q.consumer(), q.period(), "Asia/Taipei", 90, databaseToday.minusDays(89), runs);
        }));
    }
    public Detail detail(String runId, MultiValueMap<String, String> query) {
        Parsed q = query(query, false); window(q.date()); AiFlowProtocol.uuid(runId); long ownerId = owner.readOwner();
        return Objects.requireNonNull(reads.execute(tx -> {
            window(q.date(), repository.databaseDate());
            return project(repository.detail(ownerId, UUID.fromString(runId), q.date(), q.consumer())
                    .orElseThrow(() -> new AiFlowProblem("NOT_FOUND")));
        }));
    }
    public ToolCallDetail toolCall(String runId, String eventId, MultiValueMap<String, String> query) {
        Parsed q = query(query, false); window(q.date()); AiFlowProtocol.uuid(runId); AiFlowProtocol.uuid(eventId); long ownerId = owner.readOwner();
        return Objects.requireNonNull(reads.execute(tx -> {
            window(q.date(), repository.databaseDate());
            UUID parsedRunId = UUID.fromString(runId);
            StoredRun storedRun = repository.detail(ownerId, parsedRunId, q.date(), q.consumer())
                    .orElseThrow(() -> new AiFlowProblem("NOT_FOUND"));
            validatedMetadata(storedRun);
            StoredEvent storedEvent = repository.event(ownerId, parsedRunId, UUID.fromString(eventId), q.date(), q.consumer())
                    .orElseThrow(() -> new AiFlowProblem("NOT_FOUND"));
            requireHash(storedEvent.payload(), storedEvent.hash());
            Event event = AiFlowProtocol.readEvent(storedEvent.payload());
            if (!Objects.equals(event.eventId(), eventId) || event.sequence() != storedEvent.sequence())
                throw new AiFlowProblem("UPSTREAM_INVALID");
            return new ToolCallDetail(runId, eventId, event.toolCall());
        }));
    }
    public ChoiceSetDetail choiceSet(String runId, String eventId, MultiValueMap<String, String> query) {
        Parsed q = query(query, false); window(q.date()); AiFlowProtocol.uuid(runId); AiFlowProtocol.uuid(eventId); long ownerId = owner.readOwner();
        return Objects.requireNonNull(reads.execute(tx -> {
            window(q.date(), repository.databaseDate());
            UUID parsedRunId = UUID.fromString(runId);
            StoredRun storedRun = repository.detail(ownerId, parsedRunId, q.date(), q.consumer())
                    .orElseThrow(() -> new AiFlowProblem("NOT_FOUND"));
            validatedMetadata(storedRun);
            StoredEvent storedEvent = repository.event(ownerId, parsedRunId, UUID.fromString(eventId), q.date(), q.consumer())
                    .orElseThrow(() -> new AiFlowProblem("NOT_FOUND"));
            requireHash(storedEvent.payload(), storedEvent.hash());
            Event event = AiFlowProtocol.readEvent(storedEvent.payload());
            if (!Objects.equals(event.eventId(), eventId) || event.sequence() != storedEvent.sequence())
                throw new AiFlowProblem("UPSTREAM_INVALID");
            return new ChoiceSetDetail(runId, eventId, AiFlowProtocol.choiceSet(event));
        }));
    }
    public PublicDecisionDetail publicDecision(String runId, String eventId, MultiValueMap<String, String> query) {
        Parsed q = query(query, false); window(q.date()); AiFlowProtocol.uuid(runId); AiFlowProtocol.uuid(eventId); long ownerId = owner.readOwner();
        return Objects.requireNonNull(reads.execute(tx -> {
            window(q.date(), repository.databaseDate());
            UUID parsedRunId = UUID.fromString(runId);
            StoredRun storedRun = repository.detail(ownerId, parsedRunId, q.date(), q.consumer())
                    .orElseThrow(() -> new AiFlowProblem("NOT_FOUND"));
            validatedMetadata(storedRun);
            StoredEvent storedEvent = repository.event(ownerId, parsedRunId, UUID.fromString(eventId), q.date(), q.consumer())
                    .orElseThrow(() -> new AiFlowProblem("NOT_FOUND"));
            requireHash(storedEvent.payload(), storedEvent.hash());
            Event event = AiFlowProtocol.readEvent(storedEvent.payload());
            if (!Objects.equals(event.eventId(), eventId) || event.sequence() != storedEvent.sequence())
                throw new AiFlowProblem("UPSTREAM_INVALID");
            return new PublicDecisionDetail(runId, eventId, AiFlowProtocol.publicDecision(event));
        }));
    }
    private Detail project(StoredRun r) {
        Run metadata = validatedMetadata(r);
        List<Event> events = repository.events(r.owner(), r.runId()).stream().map(e -> {
            requireHash(e.payload(), e.hash()); Event event = AiFlowProtocol.readEvent(e.payload());
            if (!event.eventId().equals(e.eventId().toString()) || event.sequence() != e.sequence()) throw new AiFlowProblem("UPSTREAM_INVALID");
            return event;
        }).toList();
        try { AiFlowProjection.validateGraph(events); }
        catch (AiFlowProblem ignored) { throw new AiFlowProblem("UPSTREAM_INVALID"); }
        return AiFlowProjection.detail(r, events);
    }
    private static Run validatedMetadata(StoredRun r) {
        requireHash(r.metadata(), r.hash());
        Run metadata = AiFlowProtocol.readRun(r.metadata());
        if (!metadata.runId().equals(r.runId().toString()) || !metadata.consumer().equals(r.consumer())
                || !metadata.reportDate().equals(r.date()) || !metadata.reportSlot().equals(r.slot())
                || !metadata.sourceKind().equals(r.sourceKind()) || !metadata.sourceRunId().equals(r.sourceRunId()))
            throw new AiFlowProblem("UPSTREAM_INVALID");
        return metadata;
    }
    private record Parsed(LocalDate date, String consumer, String period) {}
    private Parsed query(MultiValueMap<String, String> query, boolean list) {
        Set<String> allowed = list ? Set.of("date", "consumer", "period") : Set.of("date", "consumer");
        for (var e : query.entrySet()) if (!allowed.contains(e.getKey()) || e.getValue() == null || e.getValue().size() != 1
                || e.getValue().getFirst() == null || e.getValue().getFirst().isBlank()) throw new AiFlowProblem("INVALID_REQUEST");
        if (!query.containsKey("date") || !query.containsKey("consumer")) throw new AiFlowProblem("INVALID_REQUEST");
        String consumer = query.getFirst("consumer"); if (!Set.of("Codex", "Claude").contains(consumer)) throw new AiFlowProblem("INVALID_REQUEST");
        String period = query.containsKey("period") ? query.getFirst("period") : "ALL";
        if (!Set.of("ALL", "MORNING", "NOON", "US").contains(period)) throw new AiFlowProblem("INVALID_REQUEST");
        return new Parsed(AiFlowProtocol.date(query.getFirst("date")), consumer, period);
    }
    public LocalDate earliest() { return LocalDate.now(clock.withZone(TAIPEI)).minusDays(89); }
    private void window(LocalDate date) {
        window(date, LocalDate.now(clock.withZone(TAIPEI)));
    }
    private void window(LocalDate date, LocalDate today) {
        if (today == null) throw new AiFlowProblem("UPSTREAM_INVALID");
        if (date.isBefore(today.minusDays(89))) throw new AiFlowProblem("RETENTION_EXPIRED");
        if (date.isAfter(today)) throw new AiFlowProblem("INVALID_REQUEST");
    }
    private static void requireHash(String payload, String hash) {
        if (!SrppJcs.sha256Hex(payload).equals(hash)) throw new AiFlowProblem("UPSTREAM_INVALID");
    }
    private static AiFlowProblem mapDatabase(DataAccessException failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql) {
                if (sql.getMessage() != null && sql.getMessage().contains("AI_FLOW_RETENTION_EXPIRED")) return new AiFlowProblem("RETENTION_EXPIRED");
                if (sql.getMessage() != null && sql.getMessage().contains("AI_FLOW_FUTURE_DATE")) return new AiFlowProblem("INVALID_REQUEST");
                if (Set.of("57014", "55P03").contains(sql.getSQLState())) return new AiFlowProblem("UPSTREAM_TIMEOUT");
            }
        }
        return new AiFlowProblem("INTERNAL_ERROR");
    }
}
