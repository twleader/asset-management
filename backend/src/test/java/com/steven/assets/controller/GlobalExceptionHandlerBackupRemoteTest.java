package com.steven.assets.controller;

import com.steven.assets.service.BackupRemoteUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerBackupRemoteTest {

    @Test
    void backupRemoteExceptionMapsExplicitlyTo503WithOnlySafeActionableDetail() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        List<String> sentinels = List.of(
                "ACCESS_SENTINEL", "REFRESH_SENTINEL", "CLIENT_ID_SENTINEL", "CLIENT_SECRET_SENTINEL",
                "TOKEN_JSON_SENTINEL", "PASSWORD_SENTINEL", "PASSWORD2_SENTINEL", "CRYPT_SENTINEL",
                "BEARER_SENTINEL");

        ProblemDetail detail = handler.handleBackupRemoteUnavailable(
                BackupRemoteUnavailableException.authenticationUnavailable());

        assertThat(detail.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE.value());
        assertThat(detail.getDetail())
                .contains("reconnect GoogleDriver:")
                .contains("GoogleDriver:asset-management-backup")
                .contains("source fingerprint 自動 reload")
                .contains("不需要 recreate business-services");
        for (String sentinel : sentinels) {
            assertThat(detail.getDetail()).doesNotContain(sentinel);
        }
    }

    @Test
    void rootMissing503ExplainsWrongAccountAndNoAutomaticCreation() {
        ProblemDetail detail = new GlobalExceptionHandler().handleBackupRemoteUnavailable(
                BackupRemoteUnavailableException.rootMissing());

        assertThat(detail.getStatus()).isEqualTo(503);
        assertThat(detail.getDetail())
                .contains("可能 reconnect 時選錯 Google 帳號")
                .contains("系統不會自動建立")
                .contains("GoogleDriver:asset-management-backup");
    }
}
