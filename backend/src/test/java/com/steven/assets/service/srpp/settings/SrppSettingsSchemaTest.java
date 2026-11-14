package com.steven.assets.service.srpp.settings;

import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;

/** Real isolated PostgreSQL verifies tenant FKs, migration idempotence and frozen evidence fences. */
@Testcontainers
class SrppSettingsSchemaTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine");
    @Test void migrationTwiceAndDatabaseRejectsCrossOwnerOrHistoricalMutation()throws Exception {
        try(Connection c=DriverManager.getConnection(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());Statement s=c.createStatement()) {
            s.execute("CREATE TABLE app_user(id BIGINT PRIMARY KEY);INSERT INTO app_user VALUES(7),(8);CREATE FUNCTION reject_srpp_immutable_update() RETURNS trigger LANGUAGE plpgsql AS $$BEGIN RAISE EXCEPTION 'immutable';END$$;");
            String sql=new String(getClass().getResourceAsStream("/db/changelog/changes/v1.166.0-srpp-settings-simulation.sql").readAllBytes(),StandardCharsets.UTF_8);s.execute(sql);s.execute(sql);
            String d="11111111-1111-4111-8111-111111111111",r="22222222-2222-4222-8222-222222222222",rel="33333333-3333-4333-8333-333333333333",h="a".repeat(64);
            s.execute("INSERT INTO srpp_settings_draft VALUES('"+d+"',7,'草稿',1,'"+h+"','"+h+"','V1','"+h+"','"+h+"','{}','[]',now(),now())");
            String insert="INSERT INTO srpp_settings_run(id,owner_user_id,draft_id,draft_revision,mode,status,config_sha256,input_sha256,request_json,changes_json,ai_status,created_at) VALUES('"+r+"',%d,'"+d+"',1,'LOCAL','PROCESSING','"+h+"','"+h+"','{}','[]','NOT_REQUESTED',now())";
            assertThatThrownBy(()->s.execute(insert.formatted(8))).isInstanceOf(SQLException.class);s.execute(insert.formatted(7));
            assertThatThrownBy(()->s.execute("UPDATE srpp_settings_run SET request_json='bad' WHERE id='"+r+"'" )).isInstanceOf(SQLException.class);
            s.execute("UPDATE srpp_settings_run SET result_json='{}',result_sha256='"+h+"' WHERE id='"+r+"'");
            assertThatThrownBy(()->s.execute("UPDATE srpp_settings_run SET result_json='changed' WHERE id='"+r+"'" )).isInstanceOf(SQLException.class);
            s.execute("UPDATE srpp_settings_run SET status='SUCCEEDED',completed_at=now() WHERE id='"+r+"'");
            assertThatThrownBy(()->s.execute("UPDATE srpp_settings_run SET status='PROCESSING' WHERE id='"+r+"'" )).isInstanceOf(SQLException.class);
            s.execute("INSERT INTO srpp_settings_release VALUES('"+rel+"',7,'"+d+"','"+r+"','"+h+"','{}',now())");
            assertThatThrownBy(()->s.execute("UPDATE srpp_settings_release SET payload_json='changed'" )).isInstanceOf(SQLException.class);
            assertThatThrownBy(()->s.execute("DELETE FROM srpp_settings_release" )).isInstanceOf(SQLException.class);
        }
    }
}
