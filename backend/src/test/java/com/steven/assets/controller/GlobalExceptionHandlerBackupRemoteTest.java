package com.steven.assets.controller;

import com.steven.assets.service.BackupRemoteUnavailableException;
import com.steven.assets.service.BackupIndexCommitUncertainException;
import com.steven.assets.service.BackupIndexRollbackConfirmedException;
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
                .contains("asset-management-backup")
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
                .contains("資料夾身分與獨立釘選不符")
                .contains("系統不會自動建立")
                .contains("asset-management-backup");
    }

    @Test
    void uncertainIndexCommitMapsToConflictWithoutClaimingRollbackOrRetrySafety() {
        ProblemDetail detail = new GlobalExceptionHandler().handleBackupIndexCommitUncertain(
                BackupIndexCommitUncertainException.forFilename("asset_manual_20261002_010101_123.dump"));

        assertThat(detail.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(detail.getDetail())
                .contains("索引提交結果未定")
                .contains("asset_manual_20261002_010101_123.dump")
                .contains("不會自動重試、刪除或輪替")
                .doesNotContain("已回滾")
                .doesNotContain("確定未提交");
    }

    @Test
    void confirmedRollbackHasDistinctSafeConflictMessage() {
        ProblemDetail detail = new GlobalExceptionHandler().handleBackupIndexRollbackConfirmed(
                BackupIndexRollbackConfirmedException.forFilename("asset_manual_20261002_010101_123.dump"));
        assertThat(detail.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(detail.getDetail()).contains("已確認回滾", "遠端 exact 檔案與本地來源")
                .doesNotContain("提交結果未定");
    }
}
