package com.steven.assets.service;

import com.steven.assets.model.AssetSnapshot;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ExcelImportService 單元測試
 * 測試辨識存款類型、判斷美股代號等 helper 邏輯。
 */
@ExtendWith(MockitoExtension.class)
class ExcelImportServiceTest {

    @Mock private AssetService assetService;
    @Mock private InstitutionService institutionService;
    @Mock private com.steven.assets.repository.RealizedGainRepository gainRepo;
    @Mock private com.steven.assets.repository.AssetSnapshotRepository snapshotRepo;

    @InjectMocks
    private ExcelImportService service;

    // ---- resolveDepositType ----

    @Test
    void resolveDepositType_美元定存() {
        assertThat(invokeResolve("美元定存")).isEqualTo("美元定存");
    }

    @Test
    void resolveDepositType_美元活存() {
        assertThat(invokeResolve("美元活存")).isEqualTo("美元活存");
    }

    @Test
    void resolveDepositType_定存() {
        assertThat(invokeResolve("台幣定存")).isEqualTo("定存");
    }

    @Test
    void resolveDepositType_信用卡() {
        assertThat(invokeResolve("信用卡待付款")).isEqualTo("信用卡待付款");
    }

    @Test
    void resolveDepositType_證券戶() {
        assertThat(invokeResolve("證券戶")).isEqualTo("證券戶");
    }

    @Test
    void resolveDepositType_其他預設活存() {
        assertThat(invokeResolve("台幣活存")).isEqualTo("活存");
        assertThat(invokeResolve("綜合存款")).isEqualTo("活存");
    }

    @Test
    void resolveDepositType_null回傳null() {
        assertThat(invokeResolve(null)).isNull();
    }

    // ---- isUsStock ----

    @Test
    void isUsStock_大寫英文為美股() {
        assertThat(invokeIsUsStock("AAPL")).isTrue();
        assertThat(invokeIsUsStock("VTI")).isTrue();
        assertThat(invokeIsUsStock("TSLA")).isTrue();
    }

    @Test
    void isUsStock_數字開頭為台股() {
        assertThat(invokeIsUsStock("2330")).isFalse();
        assertThat(invokeIsUsStock("0050")).isFalse();
        assertThat(invokeIsUsStock("00642U")).isFalse();
    }

    @Test
    void isUsStock_null為false() {
        assertThat(invokeIsUsStock(null)).isFalse();
    }

    @Test
    void sameDateImportUsesLockedAssetUpdateInsteadOfDeleteAndRecreate() throws Exception {
        AssetSnapshot existing = AssetSnapshot.builder().id(42L).snapshotDate(LocalDate.of(2026, 8, 21)).build();
        when(snapshotRepo.findBySnapshotDate(LocalDate.of(2026, 8, 21))).thenReturn(Optional.of(existing));

        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            workbook.createSheet("20260821").createRow(0);
            workbook.write(bytes);
            var result = service.importExcel(new MockMultipartFile("file", "snapshot.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes.toByteArray()));

            assertThat(result.errors()).isEmpty();
            assertThat(result.snapshotsImported()).isEqualTo(1);
        }

        verify(assetService).updateSnapshot(org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.any());
        verify(assetService, never()).deleteSnapshot(42L);
        verify(assetService, never()).createSnapshot(org.mockito.ArgumentMatchers.any());
    }

    // ---- Helpers (reflective invocation) ----

    private String invokeResolve(String raw) {
        try {
            var m = ExcelImportService.class.getDeclaredMethod("resolveDepositType", String.class);
            m.setAccessible(true);
            return (String) m.invoke(service, raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private boolean invokeIsUsStock(String code) {
        try {
            var m = ExcelImportService.class.getDeclaredMethod("isUsStock", String.class);
            m.setAccessible(true);
            return (boolean) m.invoke(service, code);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
