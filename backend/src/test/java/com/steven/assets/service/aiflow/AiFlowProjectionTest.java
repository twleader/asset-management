package com.steven.assets.service.aiflow;

import com.steven.assets.dto.aiflow.AiFlowDtos.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.steven.assets.service.aiflow.AiFlowFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiFlowProjectionTest {
    @Test void actualInstantsDriveDurationAndTaipeiLabelRegardlessOfSourceOffset() {
        List<Event> events=new ArrayList<>(complete());
        String western=OffsetDateTime.parse(DATE+"T09:05:04+08:00").withOffsetSameInstant(ZoneOffset.ofHours(-5)).toString();
        events.set(2,event(3,"call-one","END",western,List.of(id(2)),"CALL_RESULT"));
        var detail=AiFlowProjection.detail(stored(events),events);
        assertThat(detail.slowSteps().getFirst().durationMillis()).isEqualTo(3000);
        assertThat(detail.slowSteps().getFirst().endedAt()).isEqualTo(instant(DATE+"T09:05:04+08:00"));
        assertThat(detail.nodes().get(2).timeLabel()).isEqualTo(DATE.format(DateTimeFormatter.ofPattern("MM/dd"))+" 09:05:04");
    }
    @Test void publicCompleteLifecycleOnlyConfirmsExecutionNeverFinancialReport() {
        var detail=AiFlowProjection.detail(stored(complete()),complete());
        assertThat(detail.run().executionState()).isEqualTo("ENDED");assertThat(detail.run().traceCoverage()).isEqualTo("FULL_OBSERVED");
        assertThat(detail.run().verifiedReportOutcome()).isEqualTo("UNCONFIRMED");assertThat(detail.run().reportedOutcome()).isNull();
        assertThat(detail.run().durationMillis()).isEqualTo(6000);assertThat(detail.slowSteps()).hasSize(1);assertThat(detail.slowSteps().getFirst().durationMillis()).isEqualTo(3000);
        assertThat(detail.nodes().getLast().categoryLabel()).isEqualTo("紀錄完整度檢查");assertThat(detail.edges()).anySatisfy(e->assertThat(e.label()).isEqualTo("接續紀錄"));
    }
    @Test void partialTailCanRecoverAfterActualSourceGrows() {
        List<Event> running=new ArrayList<>(complete().subList(0,2));running.add(coverage(7,40,40,"b".repeat(64),"TRACE_PARTIAL"));
        assertThat(AiFlowProjection.detail(stored(running),running).run().traceCoverage()).isEqualTo("PARTIAL_OBSERVED");
        running.addAll(complete().subList(2,complete().size()));
        AiFlowProjection.validateGraph(running);assertThat(AiFlowProjection.detail(stored(running),running).run().traceCoverage()).isEqualTo("FULL_OBSERVED");
    }
    @Test void sourceTruncationAndPrefixRewritePermanentlyOverrideAllCompleteAssessments() {
        List<Event> e=new ArrayList<>(complete());e.add(coverage(7,30,30,"b".repeat(64),"SOURCE_TRACE_CHANGED"));e.add(coverage(8,200,200,"c".repeat(64),"TRACE_COMPLETE"));
        AiFlowProjection.validateGraph(e);var view=AiFlowProjection.detail(stored(e),e).run();assertThat(view.traceCoverage()).isEqualTo("PARTIAL_OBSERVED");assertThat(view.reasonCode()).isEqualTo("SOURCE_TRACE_CHANGED");
        assertThat(view.eventCount()).isEqualTo(8);
        e=new ArrayList<>(complete());e.add(coverage(7,100,100,"b".repeat(64),"TRACE_COMPLETE"));AiFlowProjection.validateGraph(e);assertThat(AiFlowProjection.detail(stored(e),e).run().traceCoverage()).isEqualTo("PARTIAL_OBSERVED");
    }
    @Test void sameExtentCompletePartialConflictRejectsButSourceChangedCanAlwaysAppend() {
        List<Event> e=new ArrayList<>(complete());e.add(coverage(7,100,100,"a".repeat(64),"TRACE_PARTIAL"));
        assertThatThrownBy(()->AiFlowProjection.validateGraph(e)).isInstanceOfSatisfying(AiFlowProblem.class,p->assertThat(p.code()).isEqualTo("INVALID_GRAPH"));
        e.set(e.size()-1,coverage(7,100,100,"a".repeat(64),"SOURCE_TRACE_CHANGED"));AiFlowProjection.validateGraph(e);
    }
    @Test void missingCallResultsAndFinalRemainPartialAndUnknownTimesDoNotRank() {
        List<Event> e=new ArrayList<>(complete());e.remove(2); // missing END; retained graph has no dangling parent
        assertThat(AiFlowProjection.detail(stored(e),e).run().traceCoverage()).isEqualTo("PARTIAL_OBSERVED");assertThat(AiFlowProjection.detail(stored(e),e).slowSteps()).isEmpty();
        e=new ArrayList<>(complete());e.remove(3);assertThat(AiFlowProjection.detail(stored(e),e).run().traceCoverage()).isEqualTo("PARTIAL_OBSERVED");
        e=new ArrayList<>(complete());e.set(2,event(3,"call-one","END",DATE+"T09:04:00+08:00",List.of(id(2)),"CALL_RESULT"));assertThat(AiFlowProjection.detail(stored(e),e).slowSteps()).isEmpty();
        assertThat(AiFlowProjection.detail(stored(e),e).run().traceCoverage()).isEqualTo("PARTIAL_OBSERVED");
    }
    @Test void graphRejectsMissingParentsSelfLoopsAndCyclesWithoutRecursion() {
        assertThatThrownBy(()->AiFlowProjection.validateGraph(List.of(event(1,"a","START",null,List.of(id(9)),"DEPENDS_ON")))).isInstanceOfSatisfying(AiFlowProblem.class,p->assertThat(p.code()).isEqualTo("MISSING_PARENT"));
        for(List<Event> e:List.of(List.of(event(1,"a","START",null,List.of(id(1)),"BRANCH")),List.of(event(1,"a","START",null,List.of(id(2)),"BRANCH"),event(2,"b","END",null,List.of(id(1)),"BRANCH"))))
            assertThatThrownBy(()->AiFlowProjection.validateGraph(e)).isInstanceOfSatisfying(AiFlowProblem.class,p->assertThat(p.code()).isEqualTo("INVALID_GRAPH"));
    }
}
