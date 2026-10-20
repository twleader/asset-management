package com.steven.assets.bff.aiflow;

import com.steven.assets.bff.security.*;
import java.time.*;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
final class AiFlowBffService {
    private final BusinessUserClient users;
    private final WebClient business;
    private final AiFlowCredentials credentials;
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    AiFlowBffService(BusinessUserClient users,@Qualifier("businessServicesClient") WebClient business,AiFlowCredentials credentials) {
        this(users,business,credentials,Clock.system(ZoneId.of("Asia/Taipei")));
    }
    AiFlowBffService(BusinessUserClient users,WebClient business,AiFlowCredentials credentials,Clock clock) {
        this.users=users; this.business=business; this.credentials=credentials; this.clock=clock;
    }
    Mono<AiFlowDtos.RunList> list(ServerHttpRequest request) {
        return Mono.defer(()->{
            var q=AiFlowProtocol.Query.parse(request.getQueryParams(),false,clock);
            return owner().flatMap(owner->business.get().uri(u->u.path("/api/ai-flow/runs").queryParam("date",q.date()).queryParam("consumer",q.consumer()).queryParam("period",q.period()).build())
                .headers(h->ownerHeaders(h,owner)).accept(MediaType.APPLICATION_JSON).exchangeToMono(r->read(r,AiFlowDtos.RunList.class,200,"/api/ai-flow/runs"))
                .map(list->{validate(()->AiFlowProtocol.list(list,q,owner.id(),clock));return list;}).timeout(Duration.ofSeconds(5)));
        }).onErrorMap(AiFlowBffService::classify).contextWrite(c->c.delete(AuthConstants.CTX_IDENTITY));
    }
    Mono<AiFlowDtos.Detail> detail(String id,ServerHttpRequest request) {
        return Mono.defer(()->{
            var q=AiFlowProtocol.Query.parse(request.getQueryParams(),true,clock); AiFlowProtocol.uuid(id);
            return owner().flatMap(owner->readDetail(id,q,owner)
                // Node summary deliberately omits parameters.  Before returning it, fetch the
                // same immutable event's private detail and actual toolCall internally and prove
                // the selected choice still equals that call; never add either value back to Node.
                .flatMap(d->validateChoiceSummaries(d,q,owner,id).thenReturn(d)).timeout(Duration.ofSeconds(5)));
        }).onErrorMap(AiFlowBffService::classify).contextWrite(c->c.delete(AuthConstants.CTX_IDENTITY));
    }
    Mono<AiFlowDtos.ToolCallDetail> toolCall(String runId,String eventId,ServerHttpRequest request) {
        return Mono.defer(()->{
            var q=AiFlowProtocol.Query.parse(request.getQueryParams(),true,clock); AiFlowProtocol.uuid(runId); AiFlowProtocol.uuid(eventId);
            String path="/api/ai-flow/runs/"+runId+"/nodes/"+eventId+"/tool-call";
            return owner().flatMap(owner->business.get().uri(u->u.path(path).queryParam("date",q.date()).queryParam("consumer",q.consumer()).build())
                .headers(h->ownerHeaders(h,owner)).accept(MediaType.APPLICATION_JSON).exchangeToMono(r->read(r,AiFlowDtos.ToolCallDetail.class,200,path))
                .map(d->{validate(()->AiFlowProtocol.toolCallDetail(d,q,runId,eventId,clock));return d;}).timeout(Duration.ofSeconds(5)));
        }).onErrorMap(AiFlowBffService::classify).contextWrite(c->c.delete(AuthConstants.CTX_IDENTITY));
    }
    Mono<AiFlowDtos.ChoiceSetDetail> choiceSet(String runId,String eventId,ServerHttpRequest request) {
        return Mono.defer(()->{
            var q=AiFlowProtocol.Query.parse(request.getQueryParams(),true,clock); AiFlowProtocol.uuid(runId); AiFlowProtocol.uuid(eventId);
            return owner().flatMap(owner->readChoiceSet(runId,eventId,q,owner).flatMap(choice->{
                // Old/non-choice nodes remain a truthful nullable read and need no synthetic
                // actual tool call.  A non-null choice set must prove its same-event START node,
                // summary and selected call through internal read-only business requests.
                if(choice.choiceSet()==null) return Mono.just(choice);
                return Mono.zip(readToolCall(runId,eventId,q,owner),readDetail(runId,q,owner)).map(pair->{
                    var node=pair.getT2().nodes().stream().filter(candidate->candidate.eventId().equals(eventId)).findFirst()
                        .orElseThrow(()->new AiFlowProblem("UPSTREAM_INVALID"));
                    validate(()->AiFlowProtocol.choiceSetMatchesActual(choice.choiceSet(),node.eventKind(),pair.getT1().toolCall()));
                    validate(()->AiFlowProtocol.choiceSetSummaryMatches(choice.choiceSet(),node.choiceSetSummary()));
                    return choice;
                });
            }).timeout(Duration.ofSeconds(5)));
        }).onErrorMap(AiFlowBffService::classify).contextWrite(c->c.delete(AuthConstants.CTX_IDENTITY));
    }
    private Mono<AiFlowDtos.Detail> readDetail(String runId,AiFlowProtocol.Query q,BffUser owner) {
        String path="/api/ai-flow/runs/"+runId;
        return business.get().uri(u->u.path(path).queryParam("date",q.date()).queryParam("consumer",q.consumer()).build())
            .headers(h->ownerHeaders(h,owner)).accept(MediaType.APPLICATION_JSON).exchangeToMono(r->read(r,AiFlowDtos.Detail.class,200,path))
            .map(d->{validate(()->AiFlowProtocol.detail(d,q,owner.id(),runId,clock));return d;});
    }
    private Mono<AiFlowDtos.ToolCallDetail> readToolCall(String runId,String eventId,AiFlowProtocol.Query q,BffUser owner) {
        String path="/api/ai-flow/runs/"+runId+"/nodes/"+eventId+"/tool-call";
        return business.get().uri(u->u.path(path).queryParam("date",q.date()).queryParam("consumer",q.consumer()).build())
            .headers(h->ownerHeaders(h,owner)).accept(MediaType.APPLICATION_JSON).exchangeToMono(r->read(r,AiFlowDtos.ToolCallDetail.class,200,path))
            .map(d->{validate(()->AiFlowProtocol.toolCallDetail(d,q,runId,eventId,clock));return d;});
    }
    private Mono<AiFlowDtos.ChoiceSetDetail> readChoiceSet(String runId,String eventId,AiFlowProtocol.Query q,BffUser owner) {
        String path="/api/ai-flow/runs/"+runId+"/nodes/"+eventId+"/choice-set";
        return business.get().uri(u->u.path(path).queryParam("date",q.date()).queryParam("consumer",q.consumer()).build())
            .headers(h->ownerHeaders(h,owner)).accept(MediaType.APPLICATION_JSON).exchangeToMono(r->read(r,AiFlowDtos.ChoiceSetDetail.class,200,path))
            .map(d->{validate(()->AiFlowProtocol.choiceSetDetail(d,q,runId,eventId,clock));return d;});
    }
    private Mono<Void> validateChoiceSummaries(AiFlowDtos.Detail detail,AiFlowProtocol.Query q,BffUser owner,String runId) {
        return Flux.fromIterable(detail.nodes()).filter(node->node.choiceSetSummary()!=null).concatMap(node->
            Mono.zip(readChoiceSet(runId,node.eventId(),q,owner),readToolCall(runId,node.eventId(),q,owner)).map(pair->{
                validate(()->AiFlowProtocol.choiceSetMatchesActual(pair.getT1().choiceSet(),node.eventKind(),pair.getT2().toolCall()));
                validate(()->AiFlowProtocol.choiceSetSummaryMatches(pair.getT1().choiceSet(),node.choiceSetSummary()));
                return node;
            })
        ).then();
    }
    record Recorded(int status,AiFlowDtos.Ack ack) {}
    Mono<Recorded> record(ServerHttpRequest request,byte[] bytes) {
        return Mono.defer(()->{
            var grant=credentials.authenticate(request.getHeaders().get(AiFlowCredentials.HEADER));
            MediaType media=request.getHeaders().getContentType();
            if(media==null || !MediaType.APPLICATION_JSON.isCompatibleWith(media)) throw new AiFlowProblem("UNSUPPORTED_MEDIA_TYPE");
            if(!request.getQueryParams().isEmpty()) throw AiFlowProblem.invalid();
            var batch=AiFlowProtocol.batch(bytes,clock); credentials.authorize(grant,batch.run());
            credentials.authorizeChoiceSet(grant,batch.run(),batch.events().stream().anyMatch(event->event.choiceSet()!=null));
            return owner().flatMap(owner->business.post().uri(AiFlowProducerTokenWebFilter.PATH)
                .headers(h->{ownerHeaders(h,owner);h.set(AiFlowCredentials.HEADER,request.getHeaders().getFirst(AiFlowCredentials.HEADER));})
                .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON).bodyValue(bytes)
                .exchangeToMono(response->{int status=response.statusCode().value();
                    return read(response,AiFlowDtos.Ack.class,status==201?201:200,AiFlowProducerTokenWebFilter.PATH).map(ack->{validate(()->AiFlowProtocol.ack(ack,batch,status));return new Recorded(status,ack);});})
                .timeout(Duration.ofSeconds(5)));
        }).onErrorMap(AiFlowBffService::classify).contextWrite(c->c.delete(AuthConstants.CTX_IDENTITY));
    }
    private Mono<BffUser> owner() {
        return Mono.defer(users::configuredAdmin).timeout(Duration.ofSeconds(5))
            .onErrorMap(e->new AiFlowProblem("OWNER_UNAVAILABLE"))
            .switchIfEmpty(Mono.error(new AiFlowProblem("OWNER_UNAVAILABLE")))
            .flatMap(o->o.id()!=null && o.id()>0 && o.isAdmin() && o.isActive() && o.configuredAdmin()
                ? Mono.just(o):Mono.error(new AiFlowProblem("OWNER_UNAVAILABLE")));
    }
    private <T> Mono<T> read(ClientResponse r,Class<T> type,int okay,String instance) {
        return r.bodyToMono(byte[].class).defaultIfEmpty(new byte[0]).flatMap(bytes->{
            try {
                if(r.statusCode().value()!=okay) {
                    var p=AiFlowProtocol.decode(bytes,AiFlowDtos.Problem.class,false);
                    AiFlowProtocol.require(r.headers().contentType().map(t->t.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)).orElse(false)
                        && p.type().equals("about:blank") && p.instance().equals(instance) && !p.title().isBlank() && !p.detail().isBlank()
                        && p.retryable()==(p.status()>=500) && AiFlowProblem.CODES.containsKey(p.code())
                        && AiFlowProblem.CODES.get(p.code())==p.status() && p.status()==r.statusCode().value());
                    return Mono.error(new AiFlowProblem(p.code()));
                }
                AiFlowProtocol.jsonMedia(r.headers().contentType().orElse(null));
                return Mono.just(AiFlowProtocol.decode(bytes,type,false));
            } catch(RuntimeException invalid) {return Mono.error(new AiFlowProblem("UPSTREAM_INVALID"));}
        });
    }
    private static void validate(Runnable validation) {
        try {validation.run();} catch(RuntimeException invalid) {throw new AiFlowProblem("UPSTREAM_INVALID");}
    }
    private static Throwable classify(Throwable e) {
        if(e instanceof AiFlowProblem) return e;
        if(e instanceof TimeoutException) return new AiFlowProblem("UPSTREAM_TIMEOUT");
        return new AiFlowProblem("UPSTREAM_INVALID");
    }
    private static void ownerHeaders(HttpHeaders h,BffUser o) {
        h.set(AuthConstants.HDR_USER_ID,String.valueOf(o.id())); h.set(AuthConstants.HDR_USER_ROLE,o.role()); h.set(AuthConstants.HDR_USER_STATUS,o.status());
    }
}
