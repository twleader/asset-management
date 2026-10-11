package com.steven.assets.service.aiflow;

import com.steven.assets.repository.aiflow.AiFlowRepository;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.UserAdminService;
import com.steven.assets.model.AppUser;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.util.LinkedMultiValueMap;
import static com.steven.assets.service.aiflow.AiFlowFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AiFlowServiceTest {
    AiFlowRepository repository;AiFlowOwnerPort owner;AiFlowService service;PlatformTransactionManager manager;
    @BeforeEach void setup(){repository=mock(AiFlowRepository.class);owner=mock(AiFlowOwnerPort.class);manager=mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());when(owner.configuredOwner()).thenReturn(7L);when(owner.readOwner()).thenReturn(7L);when(repository.databaseDate()).thenReturn(DATE);
        service=new AiFlowService(repository,owner,new AiFlowCredentialService("codex-token","claude-token","collector-token"),manager,Clock.fixed(OffsetDateTime.parse(AT).toInstant(),ZoneOffset.UTC));}
    @Test void missingWrongMultipleAndEmptyTokenRejectBeforeAnyOwnerOrTransaction(){
        for(List<String> token:List.of(List.<String>of(),List.of("wrong"),List.of(""),List.of("collector-token","collector-token")))
            assertThatThrownBy(()->service.ingest(token,"application/json",raw(complete()).toString())).isInstanceOfSatisfying(AiFlowProblem.class,p->assertThat(p.status()).isEqualTo(401));
        verifyNoInteractions(owner,repository,manager);
    }
    @Test void missingOrOverlappingConfiguredCredentialsFailClosedWithoutIo(){
        for(var auth:List.of(new AiFlowCredentialService("a","b",""),new AiFlowCredentialService("a","a","b")))
            assertThatThrownBy(()->auth.authenticate(List.of("a"))).isInstanceOfSatisfying(AiFlowProblem.class,p->assertThat(p.status()).isEqualTo(503));
    }
    @Test void scheduleGrantCannotClaimAppObservationOrOtherConsumer(){
        assertThatThrownBy(()->service.ingest(List.of("codex-token"),"application/json",raw(complete()).toString())).isInstanceOfSatisfying(AiFlowProblem.class,p->assertThat(p.code()).isEqualTo("FORBIDDEN"));
        var auth=new AiFlowCredentialService("c","h","r");assertThatThrownBy(()->auth.authorize(auth.authenticate(List.of("c")),"Claude","SCHEDULE_EVENTS")).isInstanceOf(AiFlowProblem.class);
        assertThatThrownBy(()->auth.authorize(auth.authenticate(List.of("r")),"Codex","SCHEDULE_EVENTS")).isInstanceOf(AiFlowProblem.class);
        verifyNoInteractions(owner,repository,manager);
    }
    @Test void expiryAndFutureAndStrictQueryRejectBeforeLookupEvenAfterPurge(){
        var expired=query(DATE.minusDays(90).toString());
        assertThatThrownBy(()->service.detail(RUN.toString(),expired)).isInstanceOfSatisfying(AiFlowProblem.class,p->assertThat(p.code()).isEqualTo("RETENTION_EXPIRED"));
        assertThatThrownBy(()->service.list(query(DATE.plusDays(1).toString()))).isInstanceOf(AiFlowProblem.class);
        var duplicate=query(DATE.toString());duplicate.add("consumer","Codex");assertThatThrownBy(()->service.list(duplicate)).isInstanceOf(AiFlowProblem.class);
        var unknown=query(DATE.toString());unknown.add("ownerId","8");assertThatThrownBy(()->service.list(unknown)).isInstanceOf(AiFlowProblem.class);
        var missing=new LinkedMultiValueMap<String,String>();missing.add("consumer","Codex");assertThatThrownBy(()->service.detail(RUN.toString(),missing)).isInstanceOf(AiFlowProblem.class);
        verifyNoInteractions(owner,repository,manager);
    }
    @Test void fourPeriodsEchoSelectedQueryAndDefaultToAll() {
        when(repository.list(anyLong(), any(), anyString(), anyString())).thenReturn(List.of());
        for (String period : List.of("ALL", "MORNING", "NOON", "US")) {
            var selected = query(DATE.toString()); selected.add("period", period);
            assertThat(service.list(selected).period()).isEqualTo(period);
            verify(repository).list(7, DATE, "Codex", period);
        }
        assertThat(service.list(query(DATE.toString())).period()).isEqualTo("ALL");
        var claudeUs = query(DATE.toString()); claudeUs.set("consumer", "Claude"); claudeUs.set("period", "US");
        assertThat(service.list(claudeUs).runs()).isEmpty();
        verify(repository).list(7, DATE, "Claude", "US");
    }
    @Test void malformedOrDuplicatePeriodsRejectBeforeOwnerAndDatabaseIo() {
        for (String period : List.of("", " ", "us", "22:00", "EVENING")) {
            var invalid = query(DATE.toString()); invalid.add("period", period);
            assertThatThrownBy(() -> service.list(invalid)).isInstanceOfSatisfying(AiFlowProblem.class,
                    problem -> assertThat(problem.code()).isEqualTo("INVALID_REQUEST"));
        }
        for (String period : List.of("ALL", "MORNING", "NOON", "US")) {
            var duplicate = query(DATE.toString()); duplicate.add("period", period); duplicate.add("period", period);
            assertThatThrownBy(() -> service.list(duplicate)).isInstanceOf(AiFlowProblem.class);
        }
        verifyNoInteractions(owner, repository, manager);
    }
    @Test void windowIncludesTodayAnd89DaysAndTaipeiMidnightBoundary(){
        when(repository.list(anyLong(),any(),anyString(),anyString())).thenReturn(List.of());
        assertThat(service.list(query(DATE.toString())).earliestDate()).isEqualTo(DATE.minusDays(89));
        service.list(query(DATE.minusDays(89).toString()));verify(repository).list(7,DATE.minusDays(89),"Codex","ALL");
        var later=new AiFlowService(repository,owner,new AiFlowCredentialService("c","h","r"),manager,
                Clock.fixed(DATE.atStartOfDay(ZoneId.of("Asia/Taipei")).plusDays(1).toInstant(),ZoneOffset.UTC));
        assertThatThrownBy(()->later.list(query(DATE.minusDays(89).toString()))).isInstanceOfSatisfying(AiFlowProblem.class,p->assertThat(p.code()).isEqualTo("RETENTION_EXPIRED"));
    }
    @Test void configuredOwnerIgnoresCallerForProducerAndReadRequiresExactAdmin(){
        var users=mock(UserAdminService.class);var context=mock(CurrentUserContext.class);
        when(users.configuredAdmin()).thenReturn(Optional.of(AppUser.builder().id(7L).role("ADMIN").status("ACTIVE").build()));
        when(context.getEffectiveUserId()).thenReturn(8L);when(context.getStatus()).thenReturn("ACTIVE");when(context.hasUser()).thenReturn(true);when(context.isAdmin()).thenReturn(true);
        var adapter=new ConfiguredAiFlowOwner(users,context);assertThat(adapter.configuredOwner()).isEqualTo(7);verifyNoInteractions(context);
        assertThatThrownBy(adapter::readOwner).isInstanceOfSatisfying(AiFlowProblem.class,p->assertThat(p.status()).isEqualTo(403));
        when(context.getEffectiveUserId()).thenReturn(7L);assertThat(adapter.readOwner()).isEqualTo(7);
        clearInvocations(users);when(context.isAdmin()).thenReturn(false);assertThatThrownBy(adapter::readOwner).isInstanceOf(AiFlowProblem.class);verifyNoInteractions(users);
    }
    @Test void purgeIsBoundedAtTwentyBatchesAndNeverTouchesFinancialRepositories(){
        when(repository.purgeExpired(500)).thenReturn(500);when(repository.expiredRemain()).thenReturn(true);
        new AiFlowRetentionService(repository,manager).startup();verify(repository,times(20)).purgeExpired(500);
        verify(repository,times(20)).writeDeadline();verify(repository).expiredRemain();verifyNoMoreInteractions(repository);
    }
    static LinkedMultiValueMap<String,String> query(String date){var q=new LinkedMultiValueMap<String,String>();q.add("date",date);q.add("consumer","Codex");return q;}
}
