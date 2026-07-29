package com.steven.assets.service;

import com.steven.assets.dto.AssetTransactionExportDto;
import com.steven.assets.model.AssetTransactionExportSchedule;
import com.steven.assets.repository.AssetTransactionExportScheduleRepository;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.security.UnauthenticatedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AssetTransactionExportScheduleService 單元測試（Requirement 49 / Task 238；Task 255 起每人多筆）。
 *
 * <p>最關鍵的兩件事：
 * <ol>
 *   <li>背景排程走 {@code exportAssetTransactionsForOwner(ownerId)}（owner-scoped）而非 HTTP 版
 *       {@code exportAssetTransactions()}——後者在無 request context 的背景執行緒會匯出所有人的交易。</li>
 *   <li>by-id 操作走 {@code findByIdAndOwnerUserId} 而非 {@code findById}／{@code deleteById}——
 *       後者不吃 Hibernate {@code @Filter}，會讓任何人以他人排程 id 讀改刪。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssetTransactionExportScheduleServiceTest {

    private static final ZoneId TW = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Mock private AssetTransactionExportScheduleRepository settingRepo;
    @Mock private ExcelExportService excelExportService;
    @Mock private ObjectProvider<CurrentUserContext> currentUserProvider;
    @Mock private RcloneClient rcloneClient;
    @Mock private com.steven.assets.repository.AppUserRepository appUserRepo;
    @Mock private UserAdminService userAdminService;
    // 啟用當下的自檢（Task 247）：替身預設回 null（＝自檢正常），本測試的斷言不受影響，
    // 同時保證這裡不會去讀容器內的 /etc/rclone/rclone.conf。
    @Mock private GdriveSelfCheck selfCheck;

    @TempDir Path baseDir;

    private AssetTransactionExportScheduleService service;

    @BeforeEach
    void setup() {
        // 注入真實的 GdriveOutputSupport（只把 rclone／使用者查詢換成替身），驗證規則才會真的被跑到。
        GdriveOutputSupport gdrive =
                new GdriveOutputSupport(rcloneClient, appUserRepo, userAdminService, selfCheck, "GDriveOutput");
        service = new AssetTransactionExportScheduleService(
                settingRepo, excelExportService, currentUserProvider, gdrive, baseDir.toString());
        when(settingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(settingRepo.findByOwnerUserIdOrderByRunHourAscRunMinuteAscIdAsc(anyLong())).thenReturn(List.of());
    }

    private static LocalDate today() { return LocalDate.now(TW); }
    private static LocalDate yesterday() { return today().minusDays(1); }

    /** id 與 owner 刻意分開給，才驗得出 by-id 端點有沒有帶 owner 條件。 */
    private static AssetTransactionExportSchedule sched(long id, long owner, String name, int h, int m,
                                                        boolean enabled, String subpath, LocalDate lastRun) {
        return AssetTransactionExportSchedule.builder()
                .id(id).ownerUserId(owner).name(name).runHour(h).runMinute(m)
                .enabled(enabled).outputSubpath(subpath).lastRunDate(lastRun).build();
    }

    private static AssetTransactionExportSchedule sched(long owner, int h, int m, boolean enabled,
                                                        String subpath, LocalDate lastRun) {
        return sched(owner, owner, null, h, m, enabled, subpath, lastRun);
    }

    private Path expectedFile(long owner, String subpath) {
        return baseDir.resolve(subpath).resolve("交易紀錄_" + owner + "_" + today().format(FILE_DATE) + ".xlsx");
    }

    private Path expectedFile(long owner, String name, String subpath) {
        return baseDir.resolve(subpath)
                .resolve("交易紀錄_" + owner + "_" + name + "_" + today().format(FILE_DATE) + ".xlsx");
    }

    private void givenCurrentUser(long id) {
        CurrentUserContext ctx = new CurrentUserContext();
        ctx.setEffectiveUserId(id);
        when(currentUserProvider.getObject()).thenReturn(ctx);
    }

    private static AssetTransactionExportDto.SettingRequest req(String name, Boolean enabled,
                                                                Integer h, Integer m, String subpath) {
        return new AssetTransactionExportDto.SettingRequest(name, enabled, h, m, subpath, null, null);
    }

    // ===== HTTP 路徑：欄位驗證 =====

    @Test
    void 時越界擲例外() {
        givenCurrentUser(1L);
        assertThatThrownBy(() -> service.createForCurrentUser(req(null, true, 24, 0, "input")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 分越界擲例外() {
        givenCurrentUser(1L);
        assertThatThrownBy(() -> service.createForCurrentUser(req(null, true, 8, 60, "input")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 子路徑跳脫基底被resolveDir擋下() {
        givenCurrentUser(1L);
        assertThatThrownBy(() -> service.createForCurrentUser(req(null, true, 8, 0, "../../etc")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 空子路徑正規化為input() {
        givenCurrentUser(1L);
        service.createForCurrentUser(req(null, true, 8, 0, "  "));

        org.mockito.ArgumentCaptor<AssetTransactionExportSchedule> cap =
                org.mockito.ArgumentCaptor.forClass(AssetTransactionExportSchedule.class);
        verify(settingRepo).save(cap.capture());
        assertThat(cap.getValue().getOutputSubpath()).isEqualTo("input");
    }

    @Test
    void 無使用者時擲UnauthenticatedException() {
        CurrentUserContext ctx = new CurrentUserContext(); // 無 effectiveUserId
        when(currentUserProvider.getObject()).thenReturn(ctx);
        assertThatThrownBy(() -> service.listForCurrentUser())
                .isInstanceOf(UnauthenticatedException.class);
    }

    // ===== 名稱驗證（會進檔名，故白名單） =====

    @Test
    void 合法名稱通過() {
        givenCurrentUser(1L);
        for (String name : List.of("早班", "morning-1", "a b", "晚班_2")) {
            service.createForCurrentUser(req(name, true, 8, 0, "input"));
        }
        org.mockito.ArgumentCaptor<AssetTransactionExportSchedule> cap =
                org.mockito.ArgumentCaptor.forClass(AssetTransactionExportSchedule.class);
        verify(settingRepo, org.mockito.Mockito.times(4)).save(cap.capture());
        assertThat(cap.getAllValues()).extracting(AssetTransactionExportSchedule::getName)
                .containsExactly("早班", "morning-1", "a b", "晚班_2");
    }

    @Test
    void 不合法名稱擲例外() {
        givenCurrentUser(1L);
        for (String bad : List.of("a/b", "..", "a:b", "a\nb", "一二三四五六七八九十一二三四五六七八九十一")) {
            assertThatThrownBy(() -> service.createForCurrentUser(req(bad, true, 8, 0, "input")))
                    .as("名稱 %s 應被擋下", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void 空白名稱正規化為null_有尾空白者trim後通過() {
        givenCurrentUser(1L);
        service.createForCurrentUser(req("   ", true, 8, 0, "input"));
        service.createForCurrentUser(req("x ", true, 8, 0, "input"));

        org.mockito.ArgumentCaptor<AssetTransactionExportSchedule> cap =
                org.mockito.ArgumentCaptor.forClass(AssetTransactionExportSchedule.class);
        verify(settingRepo, org.mockito.Mockito.times(2)).save(cap.capture());
        assertThat(cap.getAllValues().get(0).getName()).isNull();
        assertThat(cap.getAllValues().get(1).getName()).isEqualTo("x"); // trailing space 不進檔名
    }

    // ===== 數量上限 =====

    @Test
    void 達上限時新增擲例外且不存檔() {
        givenCurrentUser(1L);
        when(settingRepo.countByOwnerUserId(1L)).thenReturn(10L);

        assertThatThrownBy(() -> service.createForCurrentUser(req(null, true, 8, 0, "input")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("10");
        verify(settingRepo, never()).save(any());
    }

    // ===== by-id 驗歸屬（多租戶） =====

    @Test
    void 修改他人排程回NoSuchElement且不走findById() {
        givenCurrentUser(2L);
        when(settingRepo.findByIdAndOwnerUserId(99L, 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateForCurrentUser(99L, req(null, true, 8, 0, "input")))
                .isInstanceOf(NoSuchElementException.class);
        verify(settingRepo, never()).findById(any());
        verify(settingRepo, never()).save(any());
    }

    @Test
    void 刪除他人排程回NoSuchElement且不走deleteById() {
        givenCurrentUser(2L);
        when(settingRepo.findByIdAndOwnerUserId(99L, 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteForCurrentUser(99L))
                .isInstanceOf(NoSuchElementException.class);
        verify(settingRepo, never()).findById(any());
        verify(settingRepo, never()).deleteById(any());
        verify(settingRepo, never()).delete(any());
    }

    @Test
    void 對他人排程run_now回NoSuchElement且不產檔() throws Exception {
        givenCurrentUser(2L);
        when(settingRepo.findByIdAndOwnerUserId(99L, 2L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.runNowForCurrentUser(99L))
                .isInstanceOf(NoSuchElementException.class);
        verify(settingRepo, never()).findById(any());
        verify(excelExportService, never()).exportAssetTransactions();
    }

    @Test
    void 刪除本人排程走delete而非deleteById() {
        givenCurrentUser(1L);
        AssetTransactionExportSchedule s = sched(7L, 1L, null, 8, 0, true, "out", null);
        when(settingRepo.findByIdAndOwnerUserId(7L, 1L)).thenReturn(Optional.of(s));

        service.deleteForCurrentUser(7L);

        verify(settingRepo).delete(s);
        verify(settingRepo, never()).deleteById(any());
    }

    // ===== run-now =====

    @Test
    void runNow不動lastRunDate() throws Exception {
        givenCurrentUser(1L);
        AssetTransactionExportSchedule s = sched(7L, 1L, null, 8, 0, true, "out", yesterday());
        when(settingRepo.findByIdAndOwnerUserId(7L, 1L)).thenReturn(Optional.of(s));
        when(excelExportService.exportAssetTransactions()).thenReturn("xlsx".getBytes());

        AssetTransactionExportDto.RunNowResponse resp = service.runNowForCurrentUser(7L);

        assertThat(resp.path()).contains("交易紀錄_1_");
        // run-now 走 HTTP 版（非 owner 版），且不改當日 guard
        verify(excelExportService).exportAssetTransactions();
        verify(excelExportService, never()).exportAssetTransactionsForOwner(anyLong());
        assertThat(s.getLastRunDate()).isEqualTo(yesterday()); // 未被動到
    }

    @Test
    void runNow以該筆自己的名稱與目錄產檔() throws Exception {
        givenCurrentUser(1L);
        when(settingRepo.findByIdAndOwnerUserId(7L, 1L))
                .thenReturn(Optional.of(sched(7L, 1L, "晚班", 22, 0, true, "out2", yesterday())));
        when(excelExportService.exportAssetTransactions()).thenReturn("xlsx".getBytes());

        service.runNowForCurrentUser(7L);

        assertThat(expectedFile(1L, "晚班", "out2")).exists();
    }

    // ===== 背景排程 =====

    @Test
    void 命中執行時間且今日未跑則產檔並設當日guard_走owner版() throws Exception {
        when(settingRepo.findAll()).thenReturn(List.of(sched(1L, 0, 0, true, "out", yesterday())));
        when(excelExportService.exportAssetTransactionsForOwner(1L)).thenReturn("xlsx".getBytes());

        service.tick();

        // 背景必須走 owner-scoped 版，而非 HTTP 版（否則洩漏所有人的交易）
        verify(excelExportService).exportAssetTransactionsForOwner(1L);
        verify(excelExportService, never()).exportAssetTransactions();
        assertThat(expectedFile(1L, "out")).exists();
    }

    @Test
    void 今日已跑不重跑() throws Exception {
        when(settingRepo.findAll()).thenReturn(List.of(sched(1L, 0, 0, true, "out", today())));

        service.tick();

        verify(excelExportService, never()).exportAssetTransactionsForOwner(anyLong());
    }

    @Test
    void 停用列不跑() throws Exception {
        AssetTransactionExportSchedule s = sched(1L, 0, 0, false, "out", yesterday());
        when(settingRepo.findAll()).thenReturn(List.of(s));

        service.tick();

        verify(excelExportService, never()).exportAssetTransactionsForOwner(anyLong());
        assertThat(s.getLastRunDate()).isEqualTo(yesterday()); // 未被動到
    }

    @Test
    void 尚未到執行時間不跑() throws Exception {
        // 23:59 幾乎必然晚於測試執行當下
        when(settingRepo.findAll()).thenReturn(List.of(sched(1L, 23, 59, true, "out", yesterday())));

        service.tick();

        verify(excelExportService, never()).exportAssetTransactionsForOwner(anyLong());
    }

    @Test
    void 單一owner失敗記失敗仍設guard且不影響他人() throws Exception {
        AssetTransactionExportSchedule bad = sched(1L, 0, 0, true, "out1", yesterday());
        AssetTransactionExportSchedule good = sched(2L, 0, 0, true, "out2", yesterday());
        when(settingRepo.findAll()).thenReturn(List.of(bad, good));
        when(excelExportService.exportAssetTransactionsForOwner(1L)).thenThrow(new IOException("磁碟壞了"));
        when(excelExportService.exportAssetTransactionsForOwner(2L)).thenReturn("xlsx".getBytes());

        service.tick();

        assertThat(expectedFile(2L, "out2")).exists();          // 另一位使用者仍成功
        assertThat(bad.getLastRunStatus()).startsWith("失敗：");
        assertThat(bad.getLastRunDate()).isEqualTo(today());    // 失敗列仍設 guard
        assertThat(good.getLastRunDate()).isEqualTo(today());
        assertThat(Files.exists(baseDir.resolve("out1"))).isFalse();
    }

    // ===== Task 255：同一 owner 多筆 =====

    @Test
    void 同一owner多筆各自判斷執行時間() throws Exception {
        AssetTransactionExportSchedule due = sched(1L, 1L, "早班", 0, 0, true, "a", yesterday());
        AssetTransactionExportSchedule notYet = sched(2L, 1L, "晚班", 23, 59, true, "b", yesterday());
        when(settingRepo.findAll()).thenReturn(List.of(due, notYet));
        when(excelExportService.exportAssetTransactionsForOwner(1L)).thenReturn("xlsx".getBytes());

        service.tick();

        assertThat(expectedFile(1L, "早班", "a")).exists();
        assertThat(Files.exists(baseDir.resolve("b"))).isFalse();
        assertThat(due.getLastRunDate()).isEqualTo(today());
        assertThat(notYet.getLastRunDate()).isEqualTo(yesterday()); // 各自的 guard 互不影響
    }

    @Test
    void 同一owner兩筆到點時逐筆各自enableFilter產檔() throws Exception {
        AssetTransactionExportSchedule s1 = sched(1L, 1L, "早班", 0, 0, true, "a", yesterday());
        AssetTransactionExportSchedule s2 = sched(2L, 1L, "晚班", 0, 0, true, "b", yesterday());
        when(settingRepo.findAll()).thenReturn(List.of(s1, s2));
        when(excelExportService.exportAssetTransactionsForOwner(1L)).thenReturn("xlsx".getBytes());

        service.tick();

        // 每列各自呼叫 owner 版，不得合併成一次
        verify(excelExportService, org.mockito.Mockito.times(2)).exportAssetTransactionsForOwner(1L);
        verify(excelExportService, never()).exportAssetTransactions();
        assertThat(expectedFile(1L, "早班", "a")).exists();
        assertThat(expectedFile(1L, "晚班", "b")).exists();
    }

    @Test
    void 同一owner單筆失敗不影響同owner其他筆() throws Exception {
        AssetTransactionExportSchedule bad = sched(1L, 1L, "早班", 0, 0, true, "a", yesterday());
        AssetTransactionExportSchedule good = sched(2L, 1L, "晚班", 0, 0, true, "b", yesterday());
        when(settingRepo.findAll()).thenReturn(List.of(bad, good));
        when(excelExportService.exportAssetTransactionsForOwner(1L))
                .thenThrow(new IOException("磁碟壞了"))
                .thenReturn("xlsx".getBytes());

        service.tick();

        assertThat(bad.getLastRunStatus()).startsWith("失敗：");
        assertThat(bad.getLastRunDate()).isEqualTo(today());
        assertThat(good.getLastRunStatus()).startsWith("成功：");
        assertThat(expectedFile(1L, "晚班", "b")).exists();
    }

    // ===== 檔名相容性紅線 =====

    @Test
    void 無名稱時檔名與Task238逐字元相同() throws Exception {
        when(settingRepo.findAll()).thenReturn(List.of(sched(1L, 1L, null, 0, 0, true, "out", yesterday())));
        when(excelExportService.exportAssetTransactionsForOwner(1L)).thenReturn("xlsx".getBytes());

        service.tick();

        Path expected = baseDir.resolve("out")
                .resolve("交易紀錄_1_" + today().format(FILE_DATE) + ".xlsx");
        assertThat(expected).exists();
    }

    @Test
    void 有名稱時檔名多一段名稱() throws Exception {
        when(settingRepo.findAll()).thenReturn(List.of(sched(1L, 1L, "早班", 0, 0, true, "out", yesterday())));
        when(excelExportService.exportAssetTransactionsForOwner(1L)).thenReturn("xlsx".getBytes());

        service.tick();

        assertThat(expectedFile(1L, "早班", "out")).exists();
    }
}
