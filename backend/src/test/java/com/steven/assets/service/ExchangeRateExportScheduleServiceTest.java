package com.steven.assets.service;

import com.steven.assets.dto.ExchangeRateExportDto;
import com.steven.assets.model.*;
import com.steven.assets.repository.*;
import com.steven.assets.security.*;
import com.steven.assets.service.export.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import jakarta.persistence.EntityManager;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real renderers/local dual writer; in-memory store has no claim to prove PostgreSQL transactions. */
class ExchangeRateExportScheduleServiceTest {
    @TempDir Path base;
    final List<ExchangeRateExportSchedule> rows = new ArrayList<>();
    final ExchangeRateExportScheduleRepository repo = mock(ExchangeRateExportScheduleRepository.class);
    final ExcelExportService excel = mock(ExcelExportService.class);
    final RcloneClient rclone = mock(RcloneClient.class);
    final AppUserRepository users = mock(AppUserRepository.class);
    final UserAdminService admin = mock(UserAdminService.class);
    final GdriveSelfCheck check = mock(GdriveSelfCheck.class);
    final ObjectProvider<CurrentUserContext> provider = mock(ObjectProvider.class);
    final CurrentUserContext user = new CurrentUserContext();
    final AtomicLong ids = new AtomicLong(100);
    ExchangeRateExportScheduleService service;
    ExchangeRateExportScheduleExecutionStore store;
    static final ZoneId TW = ZoneId.of("Asia/Taipei");
    @BeforeEach void setup() {
        user.setEffectiveUserId(1L); when(provider.getObject()).thenReturn(user);
        var fresh = mock(ExportScheduleFreshRead.class);
        when(fresh.refresh(any())).thenAnswer(i -> i.getArgument(0));
        store = new ExchangeRateExportScheduleExecutionStore(repo, fresh);
        var em = mock(EntityManager.class); var query = mock(jakarta.persistence.Query.class);
        when(em.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        ReflectionTestUtils.setField(store, "entityManager", em);
        when(repo.findAll()).thenAnswer(i -> new ArrayList<>(rows));
        when(repo.findAllByOwnerUserIdOrderByRunHourAscRunMinuteAscIdAsc(anyLong())).thenAnswer(i ->
                new ArrayList<>(rows.stream().filter(r -> r.getOwnerUserId().equals(i.getArgument(0))).toList()));
        when(repo.findLockedByIdAndOwnerUserId(anyLong(), anyLong())).thenAnswer(i -> rows.stream()
                .filter(r -> r.getId().equals(i.getArgument(0)) && r.getOwnerUserId().equals(i.getArgument(1))).findFirst());
        when(repo.saveAndFlush(any())).thenAnswer(i -> { ExchangeRateExportSchedule row = i.getArgument(0); row.setId(ids.incrementAndGet()); rows.add(row); return row; });
        doAnswer(i -> { rows.remove(i.getArgument(0)); return null; }).when(repo).delete(any());
        var drive = new GdriveOutputSupport(rclone, users, admin, check, "GDriveOutput");
        service = new ExchangeRateExportScheduleService(repo, store, excel, provider, drive, new ExcelDocRenderer(),
                new JsonDocRenderer(new com.fasterxml.jackson.databind.ObjectMapper()), new DualFormatExportWriter(drive), base.toString());
        when(excel.exchangeRatesDoc(eq("USD"),any(),any())).thenReturn(doc());
    }
    static ExportDoc doc() {
        return new ExportDoc("台幣兌美元", List.of(new ExportDoc.Sheet("資料", List.of(new ExportDoc.Table(null,null,
                List.of("代碼"),true,false,false,null,List.of(List.of("2330")))),1)));
    }
    ExchangeRateExportSchedule row(long id, int hour) {
        ExchangeRateExportSchedule row = ExchangeRateExportSchedule.builder().id(id).ownerUserId(1L).enabled(true).runHour(hour).runMinute(0).outputSubpath("input").build();
        rows.add(row); return row;
    }
    ExchangeRateExportDto.ScheduleRequest request(String name, Boolean enabled, Integer hour, Integer minute, String local, Boolean drive, String remote) {
        return new ExchangeRateExportDto.ScheduleRequest(name,enabled,hour,minute,local,null,drive,remote);
    }
    ExchangeRateExportDto.ScheduleRequest request(String name, int hour) { return request(name,false,hour,0,"input",null,null); }
    void configured(long id, boolean allowed) {
        var u = new AppUser(); u.setId(id); u.setEmail("owner"+id+"@example.test");
        when(users.findById(id)).thenReturn(Optional.of(u)); when(admin.isConfiguredAdmin(u.getEmail())).thenReturn(allowed);
    }
    @Test void emptyGetIsEmptyAndNeverCreatesRows() {
        assertThat(service.getForCurrentUser().schedules()).isEmpty(); verify(repo,never()).saveAndFlush(any()); verifyNoInteractions(check,rclone);
    }
    @Test void createDefaultsDisabledNormalizesNamesAndPathsAndAllowsSameTargets() {
        var first = service.createForCurrentUser(request("   ",null,8,0," ",null,null));
        assertThat(first.schedules()).singleElement().satisfies(r -> {
            assertThat(r.enabled()).isFalse(); assertThat(r.name()).isNull(); assertThat(r.outputSubpath()).isEqualTo("input");
        });
        service.createForCurrentUser(request(null,8)); assertThat(rows).hasSize(2);
    }
    @Test void listAndMutationResponsesAreCompleteAndSortedByTimeThenId() {
        row(4,12); row(3,8); row(2,8);
        assertThat(service.getForCurrentUser().schedules()).extracting(ExchangeRateExportDto.ScheduleItem::id).containsExactly(2L,3L,4L);
        assertThat(service.createForCurrentUser(request("中 A_1-",9)).schedules()).extracting(ExchangeRateExportDto.ScheduleItem::runHour).containsExactly(8,8,9,12);
    }
    @Test void editsKeepOnlyTheirOwnGuardAndExecutionMetadata() {
        var a=row(1,8); var b=row(2,9); var date=LocalDate.now(TW); a.setLastRunDate(date); a.setLastRunStatus("kept");
        a.setGdriveLastStatus("kept-drive"); b.setLastRunStatus("other");
        var response=service.updateForCurrentUser(1L,request("new",11));
        assertThat(response.schedules()).hasSize(2); assertThat(a.getLastRunDate()).isEqualTo(date);
        assertThat(a.getLastRunStatus()).isEqualTo("kept"); assertThat(a.getGdriveLastStatus()).isEqualTo("kept-drive");
        assertThat(b.getLastRunStatus()).isEqualTo("other");
    }
    @Test void deleteReturnsRemainingFullList() {
        row(1,8);row(2,9); assertThat(service.deleteForCurrentUser(1L).schedules()).extracting(ExchangeRateExportDto.ScheduleItem::id).containsExactly(2L);
    }
    @Test void eleventhScheduleIsRejected() {
        for(int i=0;i<10;i++) row(i+1,8);
        assertThatThrownBy(() -> service.createForCurrentUser(request(null,8))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("10");
        assertThat(rows).hasSize(10);
    }
    @Test void foreignOrUnknownIdsAreNotFoundForAllIdOperations() {
        var foreign=row(1,8);foreign.setOwnerUserId(2L);
        for(long id:List.of(1L,999L)) {
            assertThatThrownBy(() -> service.updateForCurrentUser(id,request(null,8))).isInstanceOf(NoSuchElementException.class);
            assertThatThrownBy(() -> service.deleteForCurrentUser(id)).isInstanceOf(NoSuchElementException.class);
            assertThatThrownBy(() -> service.runNowForCurrentUser(id)).isInstanceOf(NoSuchElementException.class);
        }
        verify(excel,never()).exchangeRatesDoc(eq("USD"),any(),any());verifyNoInteractions(rclone);
    }
    @Test void unauthenticatedAccessIsRejectedBeforeReadsOrFiles() {
        user.setEffectiveUserId(null);
        assertThatThrownBy(() -> service.getForCurrentUser()).isInstanceOf(UnauthenticatedException.class);
        assertThatThrownBy(() -> service.createForCurrentUser(request(null,8))).isInstanceOf(UnauthenticatedException.class);
        assertThatThrownBy(() -> service.runNowForCurrentUser(1L)).isInstanceOf(UnauthenticatedException.class);
        verify(repo,never()).findAllByOwnerUserIdOrderByRunHourAscRunMinuteAscIdAsc(anyLong());
    }
    @Test void invalidRequiredTimesAreRejectedBeforeMutation() {
        for(var r:List.of(request(null,false,null,0,"input",null,null),request(null,false,8,null,"input",null,null),
                request(null,-1),request(null,24),request(null,false,8,60,"input",null,null)))
            assertThatThrownBy(() -> service.createForCurrentUser(r)).isInstanceOf(IllegalArgumentException.class);
        assertThat(rows).isEmpty();
    }
    @Test void invalidNamesAndLongPathsAreRejected() {
        for(String name:List.of("../escape","a/b","a:b","emoji😀","a".repeat(21)))
            assertThatThrownBy(() -> service.createForCurrentUser(request(name,8))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createForCurrentUser(request(null,false,8,0,"x".repeat(256),null,null))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void traversalAndEscapingSymlinksAreRejectedAtSaveAndAtExport() throws Exception {
        assertThatThrownBy(() -> service.createForCurrentUser(request(null,false,8,0,"../escape",null,null))).isInstanceOf(IllegalArgumentException.class);
        var outside=Files.createTempDirectory("market512-outside-");
        try { Files.createSymbolicLink(base.resolve("alias"),outside);
            assertThatThrownBy(() -> service.createForCurrentUser(request(null,false,8,0,"alias",null,null))).isInstanceOf(IllegalArgumentException.class);
            var r=row(1,0);r.setOutputSubpath("alias");
            assertThatThrownBy(() -> service.runNowForCurrentUser(1L)).hasMessageContaining("不可跳脫");
            assertThat(Files.list(outside)).isEmpty();
        } finally { Files.deleteIfExists(base.resolve("alias"));Files.deleteIfExists(outside); }
    }
    @Test void symlinkChangedDuringRenderingIsRevalidatedBeforeWriting() throws Exception {
        var r=row(1,0);r.setOutputSubpath("switch"); var outside=Files.createTempDirectory("market512-render-");
        when(excel.exchangeRatesDoc(eq("USD"),any(),any())).thenAnswer(i -> { Files.createSymbolicLink(base.resolve("switch"),outside); return doc(); });
        try { assertThatThrownBy(() -> service.runNowForCurrentUser(1L)).hasMessageContaining("不可跳脫");
            assertThat(Files.list(outside)).isEmpty();
        } finally {Files.deleteIfExists(base.resolve("switch"));Files.deleteIfExists(outside);}
    }
    @Test void manualRunIgnoresEnabledUsesHttpDocAndPreservesDateGuard() {
        var r=row(1,0);r.setEnabled(false);r.setLastRunDate(LocalDate.now(TW).minusDays(1));
        var result=service.runNowForCurrentUser(1L);
        assertThat(Path.of(result.path())).exists();assertThat(Path.of(result.jsonPath())).exists();
        assertThat(r.getLastRunDate()).isEqualTo(LocalDate.now(TW).minusDays(1)); assertThat(r.getLastRunStatus()).contains("成功");
        verify(excel,times(1)).exchangeRatesDoc(eq("USD"),any(),any());
        assertThat(result.path()).endsWith("台幣兌美元_1_"+LocalDate.now(TW).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)+".xlsx");
    }
    @Test void nameIsInsertedIntoIdenticalJsonAndExcelBaseNames() {
        var r=row(1,0);r.setName("A 資產_1-"); var result=service.runNowForCurrentUser(1L);
        assertThat(result.path()).contains("台幣兌美元_1_A 資產_1-_");
        assertThat(result.jsonPath()).isEqualTo(result.path().replace(".xlsx",".json"));
    }
    @Test void storedInvalidNameFailsWithoutCreatingEscapingFiles() {
        var r=row(1,0);r.setName("../bad");assertThatThrownBy(() -> service.runNowForCurrentUser(1L)).hasMessageContaining("名稱");
    }
    @Test void dueRowsRunInTimeIdOrderAndOneFailureDoesNotBlockNextOwner() {
        var b=row(2,0);b.setOwnerUserId(2L);var a=row(1,0);
        when(excel.exchangeRatesDoc(eq("USD"),any(),any())).thenThrow(new IllegalStateException("first-fails")).thenReturn(doc());service.tick();
        assertThat(a.getLastRunDate()).isEqualTo(LocalDate.now(TW)); assertThat(a.getLastRunStatus()).contains("first-fails");
        assertThat(b.getLastRunDate()).isEqualTo(LocalDate.now(TW)); assertThat(b.getLastRunStatus()).contains("成功");
        service.tick(); verify(excel,times(2)).exchangeRatesDoc(eq("USD"),any(),any());
    }
    @Test void guardDisabledAndFutureTimeAreIndependent() {
        var done=row(1,0);done.setLastRunDate(LocalDate.now(TW));var off=row(2,0);off.setEnabled(false);
        var future=row(3,23);future.setRunMinute(59);var due=row(4,0);
        service.tick();assertThat(done.getLastRunStatus()).isNull();assertThat(off.getLastRunDate()).isNull();
        assertThat(store.due(3L,1L,LocalDate.now(TW),LocalTime.NOON)).isEmpty();
        assertThat(due.getLastRunDate()).isEqualTo(LocalDate.now(TW));
    }
    @Test void startupAndMinuteTickShareCasWhileExportIsInProgress() throws Exception {
        row(1,0); var started=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(excel.exchangeRatesDoc(eq("USD"),any(),any())).thenAnswer(i -> {started.countDown();assertThat(release.await(5,TimeUnit.SECONDS)).isTrue();return doc();});
        var executor=Executors.newSingleThreadExecutor();
        try {var run=executor.submit(service::selfHealOnStartup);assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();service.tick();
            release.countDown();run.get(5,TimeUnit.SECONDS);verify(excel,times(1)).exchangeRatesDoc(eq("USD"),any(),any());
        } finally {release.countDown();executor.shutdownNow();}
    }
    @Test void nonConfiguredAdminCannotEnableDriveButCanCreateLocalSchedule() {
        configured(1,false); assertThatThrownBy(() -> service.createForCurrentUser(request(null,false,8,0,"input",true,"drive"))).isInstanceOf(AdminRequiredException.class);
        assertThat(service.createForCurrentUser(request(null,8)).schedules()).hasSize(1);
    }
    @Test void driveNullFieldsKeepExistingAndTurningOffKeepsPath() {
        var r=row(1,8);r.setGdriveEnabled(true);r.setGdriveSubpath("drive");
        service.updateForCurrentUser(1L,request(null,8));assertThat(r.isGdriveEnabled()).isTrue();assertThat(r.getGdriveSubpath()).isEqualTo("drive");
        service.updateForCurrentUser(1L,request(null,false,8,0,"input",false,null));
        assertThat(r.isGdriveEnabled()).isFalse();assertThat(r.getGdriveSubpath()).isEqualTo("drive");
    }
    @Test void driveSelfCheckWarningIsEphemeralAndDoesNotReplaceLastUpload() {
        configured(1,true);var r=row(1,8);r.setGdriveLastStatus("old-upload");r.setGdriveLastRunAt(LocalDateTime.of(2026,1,1,1,0));
        when(check.checkLocal("GDriveOutput")).thenReturn("configuration unavailable");
        var result=service.updateForCurrentUser(1L,request(null,false,8,0,"input",true,"drive"));
        assertThat(result.gdriveSelfCheckWarning()).contains("unavailable");assertThat(r.getGdriveLastStatus()).isEqualTo("old-upload");
        assertThat(r.getGdriveLastRunAt()).isEqualTo(LocalDateTime.of(2026,1,1,1,0));
        assertThat(service.getForCurrentUser().gdriveSelfCheckWarning()).isNull();
        service.updateForCurrentUser(1L,request(null,false,8,0,"input",true,"drive"));verify(check,times(1)).checkLocal(anyString());
    }
    @Test void getReturnsInvalidStoredDrivePathWithoutValidationOrSelfCheck() {
        var r=row(1,8);r.setGdriveSubpath("/bad");assertThat(service.getForCurrentUser().schedules().getFirst().gdriveSubpath()).isEqualTo("/bad");verifyNoInteractions(check);
    }
    @Test void configuredAdminUploadsBothFormatsAndReportsDrivePaths() {
        configured(1,true);var r=row(1,0);r.setGdriveEnabled(true);r.setGdriveSubpath("drive");
        when(rclone.copyTo(anyString(),any(),anyString(),anyString())).thenAnswer(i -> "GDriveOutput:drive/"+i.getArgument(3));
        var result=service.runNowForCurrentUser(1L);assertThat(result.gdrivePath()).endsWith(".xlsx");assertThat(result.jsonGdrivePath()).endsWith(".json");
        verify(rclone,times(2)).copyTo(eq("GDriveOutput"),any(),eq("drive"),anyString());assertThat(r.getGdriveLastStatus()).contains("成功");
    }
    @Test void backgroundRechecksDriveOwnerButKeepsLocalFilesOnDenial() {
        configured(1,false);var r=row(1,0);r.setGdriveEnabled(true);r.setGdriveSubpath("drive");service.tick();
        assertThat(r.getLastRunStatus()).contains("成功");assertThat(r.getGdriveLastStatus()).contains("主要管理者");verifyNoInteractions(rclone);
    }
    @Test void driveFailureAndTimeoutRemainSeparateFromSuccessfulLocalWrite() {
        configured(1,true);var r=row(1,0);r.setGdriveEnabled(true);r.setGdriveSubpath("drive");
        when(rclone.copyTo(anyString(),any(),anyString(),anyString())).thenThrow(new RuntimeException("exit3"));service.tick();
        assertThat(r.getLastRunStatus()).contains("成功");assertThat(r.getGdriveLastStatus()).contains("失敗");
        doThrow(new RcloneClient.RcloneTimeoutException("timeout",45)).when(rclone).copyTo(anyString(),any(),anyString(),anyString());
        service.runNowForCurrentUser(1L);assertThat(r.getGdriveLastStatus()).contains("逾時","可能已完成");
    }
    @Test void localFailureSkipsAllUploadsAndStillRecordsDueGuard() {
        configured(1,true);var r=row(1,0);r.setGdriveEnabled(true);r.setGdriveSubpath("drive");
        when(excel.exchangeRatesDoc(eq("USD"),any(),any())).thenThrow(new IllegalStateException("doc-failure"));service.tick();
        assertThat(r.getLastRunDate()).isEqualTo(LocalDate.now(TW));assertThat(r.getLastRunStatus()).contains("doc-failure");
        assertThat(r.getGdriveLastStatus()).contains("跳過");verifyNoInteractions(rclone);
    }

    @Test void eachRowUsesItsOwnRangeAndOnePersistedGlobalHistoryReadPerExport() {
        var a=row(1,0);a.setRangeMonths(3);var b=row(2,0);b.setRangeMonths(17);b.setOutputSubpath("other");
        var c=row(3,0);c.setRangeMonths(null);c.setName("all");service.tick();
        var end=LocalDate.now(TW);
        verify(excel).exchangeRatesDoc("USD",end.minusMonths(3),end);verify(excel).exchangeRatesDoc("USD",end.minusMonths(17),end);verify(excel).exchangeRatesDoc("USD",end.minusYears(10),end);
        assertThat(base.resolve("other")).isDirectory();
    }
    @Test void allValidRangesRemainIndependentAndInvalidRangesAreRejected() {
        for(Integer months:Arrays.asList(null,1,3,6,12,17,36,60,120)) {
            var response=service.createForCurrentUser(new ExchangeRateExportDto.ScheduleRequest(null,false,8,0,"input",months,null,null));
            assertThat(response.schedules()).extracting(ExchangeRateExportDto.ScheduleItem::rangeMonths).contains(months);
        }
        for(int months:new int[]{0,-1,121})
            assertThatThrownBy(()->service.createForCurrentUser(new ExchangeRateExportDto.ScheduleRequest(null,false,8,0,"input",months,null,null))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void xlsxRenderFailureStillWritesJsonFromTheSameDocument() throws Exception {
        var renderer=mock(ExcelDocRenderer.class);when(renderer.render(any())).thenThrow(new IllegalStateException("render failed"));
        var drive=new GdriveOutputSupport(rclone,users,admin,check,"GDriveOutput");
        service=new ExchangeRateExportScheduleService(repo,store,excel,provider,drive,renderer,
                new JsonDocRenderer(new com.fasterxml.jackson.databind.ObjectMapper()),new DualFormatExportWriter(drive),base.toString());
        row(1,0);var result=service.runNowForCurrentUser(1L);
        assertThat(result.path()).isNull();assertThat(Path.of(result.jsonPath())).exists();verify(excel,times(1)).exchangeRatesDoc(eq("USD"),any(),any());
    }
}
