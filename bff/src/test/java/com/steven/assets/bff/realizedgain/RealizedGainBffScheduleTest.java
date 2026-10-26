package com.steven.assets.bff.realizedgain;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.web.reactive.function.client.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

class RealizedGainBffScheduleTest {
    static final String JSON="{\"baseDir\":\"/home/steven\",\"gdriveRemote\":\"GDriveOutput\",\"schedules\":[{\"id\":9,\"name\":\"A\",\"runHour\":8,\"runMinute\":15,\"enabled\":false,\"outputSubpath\":\"input\"}]}";
    @Test void allIndependentRoutesUseOwnBusinessResourcesAndKeepFullList() {
        var requests=new ArrayList<ClientRequest>();
        var client=WebClient.builder().exchangeFunction(r->{requests.add(r);return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type","application/json").body(JSON).build());}).build();
        var c=new RealizedGainBffController(client);var body=Map.<String,Object>of("name","A","runHour",8,"runMinute",15);
        assertThat(c.getExportSchedules().block().getBody()).containsKey("schedules");
        assertThat(c.createExportSchedule(body).block().getBody()).containsKey("schedules");
        assertThat(c.updateExportSchedule(9L,body).block().getBody()).containsKey("schedules");
        assertThat(c.deleteExportSchedule(9L).block().getBody()).containsKey("schedules");
        assertThat(c.runExportNow(9L).block().getBody()).containsKey("schedules");
        assertThat(requests).extracting(r->r.method()+" "+r.url().getPath()).containsExactly("GET /api/realized-gains/export/schedules","POST /api/realized-gains/export/schedules","PUT /api/realized-gains/export/schedules/9","DELETE /api/realized-gains/export/schedules/9","POST /api/realized-gains/export/schedules/9/run-now");
    }
    @Test void retiredRoutesHaveNoUpstreamSideEffects() {
        var calls=new AtomicInteger();var c=new RealizedGainBffController(WebClient.builder().exchangeFunction(r->{calls.incrementAndGet();return Mono.error(new AssertionError("unexpected upstream"));}).build());
        assertThatThrownBy(c::retiredExportSettings).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode()).isEqualTo(HttpStatus.GONE));
        assertThatThrownBy(c::retiredExportRunNow).isInstanceOfSatisfying(ResponseStatusException.class,e->assertThat(e.getStatusCode()).isEqualTo(HttpStatus.GONE));
        assertThat(calls.get()).isZero();
    }
    @Test void upstreamErrorsRemainErrorsAndAreNeverEmptySuccess() {
        var c=new RealizedGainBffController(WebClient.builder().exchangeFunction(r->Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND).body("missing").build())).build());
        assertThatThrownBy(()->c.getExportSchedules().block()).isInstanceOf(WebClientResponseException.NotFound.class);
    }
    @Test void runNowKeepsAllSevenDualFormatFields() {
        String json="{\"path\":\"/x.xlsx\",\"sizeBytes\":1,\"gdrivePath\":null,\"gdriveStatus\":null,\"jsonPath\":\"/x.json\",\"jsonSizeBytes\":2,\"jsonGdrivePath\":null}";
        var c=new RealizedGainBffController(WebClient.builder().exchangeFunction(r->Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type","application/json").body(json).build())).build());
        assertThat(c.runExportNow(9L).block().getBody()).hasSize(7).containsEntry("path","/x.xlsx").containsEntry("jsonPath","/x.json");
    }
}
