package com.steven.assets.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.dto.IndexExportDto;
import com.steven.assets.model.AppUser;
import com.steven.assets.model.IndexExportSchedule;
import com.steven.assets.repository.AppUserRepository;
import com.steven.assets.repository.IndexExportScheduleRepository;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.security.AdminRequiredException;
import com.steven.assets.service.export.DualFormatExportWriter;
import com.steven.assets.service.export.ExcelDocRenderer;
import com.steven.assets.service.export.ExportDoc;
import com.steven.assets.service.export.JsonDocRenderer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IndexExportScheduleServiceTest {
    private static final Long OWNER = 42L;
    private static final AtomicLong IDS = new AtomicLong(100);

    @Mock private IndexExportScheduleRepository repository;
    @Mock private IndexExportScheduleExecutionStore store;
    @Mock private ExcelExportService excelExportService;
    @Mock private ObjectProvider<CurrentUserContext> currentUserProvider;
    @Mock private CurrentUserContext currentUser;
    @Mock private RcloneClient rcloneClient;
    @Mock private AppUserRepository appUserRepository;
    @Mock private UserAdminService userAdminService;
    @Mock private GdriveSelfCheck gdriveSelfCheck;

    @TempDir Path baseDir;
    @TempDir Path outsideBaseDir;

    private final List<IndexExportSchedule> owned = new ArrayList<>();
    private GdriveOutputSupport gdrive;
    private IndexExportScheduleService service;

    @BeforeEach
    void setUp() {
        owned.clear();
        when(currentUserProvider.getObject()).thenReturn(currentUser);
        when(currentUser.hasUser()).thenReturn(true);
        when(currentUser.getEffectiveUserId()).thenReturn(OWNER);
        when(store.listForOwner(OWNER)).thenAnswer(invocation -> new ArrayList<>(owned));
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked") Function<List<IndexExportSchedule>, Object> operation = invocation.getArgument(1);
            return operation.apply(owned);
        }).when(store).write(eq(OWNER), any());
        doAnswer(invocation -> {
            IndexExportSchedule schedule = invocation.getArgument(0);
            if (schedule.getId() == null) schedule.setId(IDS.incrementAndGet());
            return schedule;
        }).when(repository).saveAndFlush(any(IndexExportSchedule.class));
        when(repository.findByIdAndOwnerUserId(anyLong(), eq(OWNER))).thenAnswer(invocation -> owned.stream()
                .filter(schedule -> schedule.getId().equals(invocation.getArgument(0)))
                .filter(schedule -> schedule.getOwnerUserId().equals(invocation.getArgument(1)))
                .findFirst());

        gdrive = new GdriveOutputSupport(rcloneClient, appUserRepository, userAdminService,
                gdriveSelfCheck, "TestDrive");
        service = new IndexExportScheduleService(repository, store, excelExportService, currentUserProvider,
                gdrive, new ExcelDocRenderer(), new JsonDocRenderer(new ObjectMapper()),
                new DualFormatExportWriter(gdrive), baseDir.toString());
    }

    @Test
    void createUpdateDeleteReturnCompleteListAndNormalizeNameMarketsAndCatalog() {
        IndexExportDto.SettingResponse created = service.createForCurrentUser(request(
                " 早盤 ", true, 8, 5, List.of(" twse ", "spx"), 6, "index", false, null));

        assertThat(created.schedules()).hasSize(1);
        IndexExportDto.ScheduleItem first = created.schedules().getFirst();
        assertThat(first.name()).isEqualTo("早盤");
        assertThat(first.markets()).containsExactly("SPX", "TWSE");
        assertThat(created.marketOptions()).extracting(IndexExportDto.MarketOption::value)
                .contains("TWSE", "TPEX", "SPX");
        assertThat(created.marketOptions().stream().filter(option -> option.value().equals("TWSE")).findFirst().orElseThrow().label())
                .isEqualTo(ExcelExportService.indexLabel("TWSE"));

        IndexExportDto.SettingResponse updated = service.updateForCurrentUser(first.id(), request(
                null, false, 9, 45, List.of("TPEX"), null, "index/new", false, null));
        assertThat(updated.schedules()).hasSize(1);
        assertThat(updated.schedules().getFirst().name()).isNull();
        assertThat(updated.schedules().getFirst().runHour()).isEqualTo(9);
        assertThat(updated.schedules().getFirst().runMinute()).isEqualTo(45);
        assertThat(updated.schedules().getFirst().markets()).containsExactly("TPEX");
        assertThat(updated.schedules().getFirst().rangeMonths()).isNull();

        IndexExportDto.SettingResponse deleted = service.deleteForCurrentUser(first.id());
        assertThat(deleted.schedules()).isEmpty();
        verify(repository).delete(any(IndexExportSchedule.class));
    }

    @Test
    void validatesTimeRangeNameMarketsAndOwnerMaximum() {
        assertInvalid(request("bad/name", false, 8, 0, List.of("TWSE"), 6, "out", false, null));
        assertInvalid(request("name", false, 24, 0, List.of("TWSE"), 6, "out", false, null));
        assertInvalid(request("name", false, 8, 60, List.of("TWSE"), 6, "out", false, null));
        assertInvalid(request("name", false, 8, 0, List.of("TWSE"), 0, "out", false, null));
        assertInvalid(request("name", false, 8, 0, List.of("TWSE"), 121, "out", false, null));
        assertInvalid(request("name", false, 8, 0, List.of(" "), 6, "out", false, null));
        assertInvalid(request("name", false, 8, 0, List.of("unknown"), 6, "out", false, null));
        assertInvalid(request("name", false, 8, 0, List.of("TWSE"), 6, "../escape", false, null));

        for (int i = 0; i < 10; i++) owned.add(item((long) i + 1, OWNER, "s" + i, List.of("TWSE"), "slot-" + i));
        assertThatThrownBy(() -> service.createForCurrentUser(request(
                "new", false, 8, 0, List.of("TPEX"), 6, "last", false, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("最多建立 10 筆");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void collisionUsesResolvedLocalTargetAndAllowsDifferentNameOrDirectory() {
        owned.add(item(501L, OWNER, "晨間", List.of("TWSE"), "archive/../index"));
        assertThatThrownBy(() -> service.createForCurrentUser(request(
                " 晨間 ", false, 9, 0, List.of("TWSE"), 6, "./index", false, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("排程 ID 501");

        assertThat(service.createForCurrentUser(request(
                "晚間", false, 9, 0, List.of("TWSE"), 6, "index", false, null)).schedules()).hasSize(2);
        assertThat(service.createForCurrentUser(request(
                "晨間", false, 10, 0, List.of("TWSE"), 6, "other-index", false, null)).schedules()).hasSize(3);
    }

    @Test
    void symlinkAliasesShareCollisionTargetAndExternalSymlinkCannotEscapeBase() throws Exception {
        Path physical = Files.createDirectories(baseDir.resolve("physical"));
        Files.createSymbolicLink(baseDir.resolve("alias"), physical);
        owned.add(item(502L, OWNER, "同名", List.of("TWSE"), "physical/nested"));

        assertThatThrownBy(() -> service.createForCurrentUser(request(
                "同名", false, 9, 0, List.of("TWSE"), 6, "alias/nested", false, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("排程 ID 502");

        Files.createSymbolicLink(baseDir.resolve("outside"), outsideBaseDir);
        assertThatThrownBy(() -> service.createForCurrentUser(request(
                "外部", false, 9, 0, List.of("TPEX"), 6, "outside/new-folder", false, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不可跳脫基底目錄");
    }

    @Test
    void runNowRevalidatesSavedPathBeforeFetchingOrWritingWhenItBecomesExternalSymlink() throws Exception {
        Files.createSymbolicLink(baseDir.resolve("reports"), outsideBaseDir);
        IndexExportScheduleCapture capture = new IndexExportScheduleCapture(503L, OWNER, "報表", false,
                8, 0, null, "reports", false, null, 6, List.of("TWSE"));
        when(store.manual(503L, OWNER)).thenReturn(Optional.of(capture));

        IndexExportDto.RunNowResponse response = service.runNowForCurrentUser(503L);

        assertThat(response.results()).singleElement().satisfies(result ->
                assertThat(result.error()).contains("不可跳脫基底目錄"));
        verify(excelExportService, never()).indexDailyDoc(eq("TWSE"), any(), any());
    }

    @Test
    void runNowRevalidatesPathAfterFetchBeforeWritingWhenItBecomesExternalSymlink() throws Exception {
        Path output = baseDir.resolve("reports");
        IndexExportScheduleCapture capture = new IndexExportScheduleCapture(504L, OWNER, "報表", false,
                8, 0, null, "reports", false, null, 6, List.of("TWSE"));
        when(store.manual(504L, OWNER)).thenReturn(Optional.of(capture));
        when(excelExportService.indexDailyDoc(eq("TWSE"), any(), any())).thenAnswer(invocation -> {
            Files.createSymbolicLink(output, outsideBaseDir);
            return doc("TWSE");
        });

        IndexExportDto.RunNowResponse response = service.runNowForCurrentUser(504L);

        assertThat(response.results()).singleElement().satisfies(result ->
                assertThat(result.error()).contains("不可跳脫基底目錄"));
        try (var files = Files.list(outsideBaseDir)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @Test
    void localPathComparisonUsesDetectedVolumeCaseSemanticsWithoutCreatingTarget() throws Exception {
        Path existing = baseDir.resolve("T464CaseProbe");
        Files.createDirectories(existing);
        Path caseVariant = baseDir.resolve("t464caseprobe");
        boolean actualCaseInsensitive = Files.exists(caseVariant) && Files.isSameFile(existing, caseVariant);

        assertThat(LocalExportPathComparator.caseInsensitiveVolume(
                existing.resolve("not-created-a"), caseVariant.resolve("not-created-b")))
                .isEqualTo(actualCaseInsensitive);
        assertThat(Files.exists(existing.resolve("not-created-a"))).isFalse();
        assertThat(Files.exists(existing.resolve("not-created-b"))).isFalse();

        assertThat(LocalExportPathComparator.sameName("Morning", "morning", false)).isFalse();
        assertThat(LocalExportPathComparator.sameName("Morning", "morning", true)).isTrue();
        assertThat(LocalExportPathComparator.sameTarget(
                Path.of("/tmp/Reports"), Path.of("/tmp/reports"), false)).isFalse();
        assertThat(LocalExportPathComparator.sameTarget(
                Path.of("/tmp/Reports"), Path.of("/tmp/reports"), true)).isTrue();
    }

    @Test
    void caseProbeStopsAtDifferentFileStoreBoundary() {
        Path mountedVolumeRoot = Path.of("/virtual/CaseMount");
        Path parent = mountedVolumeRoot.getParent();
        Path existingParentEntry = parent.resolve("CaseEntry");
        Set<Path> inspectedDirectories = new HashSet<>();
        LocalExportPathComparator.FileSystemProbe probe = new LocalExportPathComparator.FileSystemProbe() {
            @Override public LocalExportPathComparator.EntryIdentity noFollowIdentity(Path path) {
                return new LocalExportPathComparator.EntryIdentity(false, "same-entry");
            }
            @Override public Object fileStore(Path path) {
                return mountedVolumeRoot.equals(path) ? "mounted-volume" : "parent-volume";
            }
            @Override public List<Path> children(Path directory) {
                inspectedDirectories.add(directory);
                return parent.equals(directory) ? List.of(existingParentEntry) : List.of();
            }
        };

        assertThat(LocalExportPathComparator.hasCaseInsensitiveComponent(mountedVolumeRoot, probe)).isFalse();
        assertThat(inspectedDirectories).contains(mountedVolumeRoot).doesNotContain(parent);
    }

    @Test
    void caseProbeCanInspectParentWhenItSharesTheTargetFileStore() {
        Path existingDirectory = Path.of("/virtual/CaseMount/nested");
        Path parent = existingDirectory.getParent();
        Path existingEntry = parent.resolve("CaseEntry");
        LocalExportPathComparator.FileSystemProbe probe = new LocalExportPathComparator.FileSystemProbe() {
            @Override public LocalExportPathComparator.EntryIdentity noFollowIdentity(Path path) {
                return new LocalExportPathComparator.EntryIdentity(false, "same-entry");
            }
            @Override public Object fileStore(Path path) { return "same-volume"; }
            @Override public List<Path> children(Path directory) {
                return parent.equals(directory) ? List.of(existingEntry) : List.of();
            }
        };

        assertThat(LocalExportPathComparator.hasCaseInsensitiveComponent(existingDirectory, probe)).isTrue();
    }

    @Test
    void symlinkToCaseVariantDoesNotProveCaseInsensitiveFilesystemOnLinux() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux"),
                "This regression requires Linux filesystem semantics");
        Path directory = baseDir.resolve("t464-symlink-case-probe");
        Files.createDirectories(directory);
        Path swappedDirectory = baseDir.resolve("T464-SYMLINK-CASE-PROBE");
        Assumptions.assumeFalse(Files.exists(swappedDirectory) && Files.isSameFile(directory, swappedDirectory),
                "This regression requires a case-sensitive test volume");

        Path regularFile = directory.resolve("Foo");
        Path symlink = directory.resolve("FOO");
        Files.writeString(regularFile, "fixture");
        Files.createSymbolicLink(symlink, Path.of("Foo"));

        // The old follow-link check considered these paths the same file and falsely inferred
        // case-insensitive lookup from a case-sensitive directory.
        assertThat(Files.isSameFile(regularFile, symlink)).isTrue();
        assertThat(LocalExportPathComparator.hasCaseInsensitiveComponent(directory)).isFalse();
    }

    @Test
    void byIdAccessReturns404ForForeignOrMissingSchedule() {
        assertThatThrownBy(() -> service.updateForCurrentUser(999L,
                request("x", false, 8, 0, List.of("TWSE"), 6, "out", false, null)))
                .isInstanceOf(NoSuchElementException.class).hasMessageContaining("找不到排程 999");
        when(store.manual(888L, OWNER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.runNowForCurrentUser(888L))
                .isInstanceOf(NoSuchElementException.class).hasMessageContaining("找不到排程 888");
    }

    @Test
    void runNowExportsEachSavedMarketIndependentlyAndDoesNotSupplyDueDateGuard() {
        IndexExportScheduleCapture capture = new IndexExportScheduleCapture(77L, OWNER, "saved", false,
                8, 15, null, "reports", false, null, 6, List.of("SPX", "TWSE"));
        when(store.manual(77L, OWNER)).thenReturn(Optional.of(capture));
        when(excelExportService.indexDailyDoc(eq("SPX"), any(), any())).thenReturn(doc("SPX"));
        when(excelExportService.indexDailyDoc(eq("TWSE"), any(), any()))
                .thenThrow(new IllegalStateException("synthetic market failure"));

        IndexExportDto.RunNowResponse response = service.runNowForCurrentUser(77L);

        assertThat(response.id()).isEqualTo(77L);
        assertThat(response.results()).hasSize(2);
        assertThat(response.results().get(0).market()).isEqualTo("SPX");
        assertThat(response.results().get(0).path()).isNotBlank();
        assertThat(response.results().get(0).jsonPath()).isNotBlank();
        assertThat(Path.of(response.results().get(0).path())).exists();
        assertThat(Path.of(response.results().get(0).jsonPath())).exists();
        String date = LocalDate.now(ZoneId.of("Asia/Taipei")).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String expectedBase = ExcelExportService.indexLabel("SPX") + "_42_saved_" + date;
        assertThat(Path.of(response.results().get(0).path()).getFileName().toString()).isEqualTo(expectedBase + ".xlsx");
        assertThat(Path.of(response.results().get(0).jsonPath()).getFileName().toString()).isEqualTo(expectedBase + ".json");
        assertThat(response.results().get(1).error()).contains("synthetic market failure");
        assertThat(response.gdriveStatus()).isNull();
        ArgumentCaptor<IndexExportScheduleCapture> captureArg = ArgumentCaptor.forClass(IndexExportScheduleCapture.class);
        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        verify(store).complete(captureArg.capture(), any(LocalDateTime.class), status.capture(), eq(null));
        assertThat(captureArg.getValue().attemptDate()).isNull();
        assertThat(status.getValue()).contains("SPX 成功", "TWSE 失敗").hasSizeLessThanOrEqualTo(500);
        verify(excelExportService, times(1)).indexDailyDoc(eq("SPX"), any(), any());
        verify(excelExportService, times(1)).indexDailyDoc(eq("TWSE"), any(), any());
    }

    @Test
    void placeholderRunNowReturnsBadRequestAndDoesNotWriteLastRun() {
        when(store.manual(88L, OWNER)).thenReturn(Optional.of(new IndexExportScheduleCapture(
                88L, OWNER, null, false, 8, 0, null, "input", false, null, null, List.of())));
        assertThatThrownBy(() -> service.runNowForCurrentUser(88L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("請先選擇至少一個指數");
        verify(store, never()).complete(any(), any(), any(), any());
    }

    @Test
    void driveAuthorizationAndPerMarketDriveStatusesUseSharedSupport() {
        AppUser admin = AppUser.builder().id(OWNER).email("owner@example.com").build();
        when(appUserRepository.findById(OWNER)).thenReturn(Optional.of(admin));
        when(userAdminService.isConfiguredAdmin("owner@example.com")).thenReturn(false);
        assertThatThrownBy(() -> service.createForCurrentUser(request(
                "drive", false, 8, 0, List.of("TWSE"), 6, "out", true, "Reports")))
                .isInstanceOf(AdminRequiredException.class);

        when(userAdminService.isConfiguredAdmin("owner@example.com")).thenReturn(true);
        when(gdriveSelfCheck.checkLocal("TestDrive")).thenReturn("Drive 設定待確認");
        IndexExportDto.SettingResponse configured = service.createForCurrentUser(request(
                "drive", false, 8, 0, List.of("TWSE"), 6, "out", true, "Reports/"));
        assertThat(configured.gdriveSelfCheckWarning()).isEqualTo("Drive 設定待確認");
        assertThat(configured.schedules().getFirst().gdriveSubpath()).isEqualTo("Reports");
        assertThat(configured.schedules().getFirst().gdriveEnabled()).isTrue();
    }

    private void assertInvalid(IndexExportDto.ScheduleRequest request) {
        assertThatThrownBy(() -> service.createForCurrentUser(request)).isInstanceOf(IllegalArgumentException.class);
    }

    private static IndexExportDto.ScheduleRequest request(String name, Boolean enabled, Integer hour, Integer minute,
                                                          List<String> markets, Integer range, String path,
                                                          Boolean driveEnabled, String drivePath) {
        return new IndexExportDto.ScheduleRequest(name, enabled, hour, minute, markets, range,
                path, driveEnabled, drivePath);
    }

    private static IndexExportSchedule item(Long id, Long owner, String name, List<String> markets, String path) {
        return IndexExportSchedule.builder().id(id).ownerUserId(owner).name(name).enabled(false)
                .runHour(8).runMinute(0).markets(new LinkedHashSet<>(markets)).rangeMonths(6)
                .outputSubpath(path).build();
    }

    private static ExportDoc doc(String title) {
        var table = new ExportDoc.Table(null, null, List.of("日期", "值"), true, false, false,
                List.of(ExportDoc.Format.DATE, ExportDoc.Format.NUM4),
                List.of(List.of(LocalDate.of(2026, 7, 30), new BigDecimal("1.2345"))));
        return new ExportDoc(title, List.of(new ExportDoc.Sheet(title, List.of(table), 2)));
    }
}
