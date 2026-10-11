package com.steven.assets.integration.fubon;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.repository.FubonSrppSourceRepository;
import com.steven.assets.service.fubon.FubonSrppSourceStore;
import com.steven.assets.srpp.SrppJcs;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
@Testcontainers
class FubonSrppSourceRepositoryPostgresTest {
    @Container static PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("task489_official_test").withUsername("assets").withPassword("test-only");
    JdbcTemplate jdbc;FubonSrppSourceRepository repo;String migration;
    @BeforeEach void setup()throws Exception{jdbc=new JdbcTemplate(new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword()));jdbc.execute("DROP SCHEMA public CASCADE;CREATE SCHEMA public;CREATE TABLE app_user(id bigint PRIMARY KEY);INSERT INTO app_user VALUES(7),(8)");migration=new ClassPathResource("db/changelog/changes/v1.151.0-fubon-srpp-official-source.sql").getContentAsString(StandardCharsets.UTF_8);jdbc.execute(migration);repo=new FubonSrppSourceRepository(jdbc,new ObjectMapper());}
    @Test void immutableReplayOwnerIsolationAndLatestFailureNotSuccess()throws Exception{
        ObjectNode p=(ObjectNode)SrppJcs.parseStrict(new ClassPathResource("srpp/fubon-source-knownzero.json").getContentAsString(StandardCharsets.UTF_8));LocalDate date=LocalDate.parse(p.path("queryDate").asText());Instant at=Instant.parse(p.path("capturedAt").asText());UUID id=UUID.randomUUID();var first=new FubonSrppSourceStore.Observation(id,7,date,p.path("accountFingerprint").asText(),at,"VERIFIED",p.path("revision").asText(),p);repo.append(first);jdbc.execute(migration);assertThat(repo.latest(7,date).orElseThrow().payload()).isEqualTo(p);assertThat(repo.latest(8,date)).isEmpty();assertThat(repo.latest(7,date.plusDays(1))).isEmpty();assertThatThrownBy(()->jdbc.update("UPDATE fubon_srpp_source_snapshot SET status='UNAVAILABLE' WHERE id=?",id)).isInstanceOf(RuntimeException.class);assertThatThrownBy(()->jdbc.update("DELETE FROM fubon_srpp_source_snapshot WHERE id=?",id)).isInstanceOf(RuntimeException.class);
        ObjectNode failure=new ObjectMapper().createObjectNode().put("reason","SDK_PERMISSION_DENIED");repo.append(new FubonSrppSourceStore.Observation(UUID.randomUUID(),7,date,null,at.plusSeconds(1),"UNAVAILABLE",SrppJcs.hash(failure),failure));assertThat(repo.latest(7,date).orElseThrow().status()).isEqualTo("UNAVAILABLE");assertThat(jdbc.queryForObject("SELECT count(*) FROM fubon_srpp_source_snapshot",Integer.class)).isEqualTo(2);
    }
}
