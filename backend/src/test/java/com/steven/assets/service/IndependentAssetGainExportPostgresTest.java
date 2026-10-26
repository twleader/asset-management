package com.steven.assets.service;

import com.steven.assets.dto.*;
import com.steven.assets.model.*;
import com.steven.assets.repository.*;
import com.steven.assets.security.*;
import com.steven.assets.service.export.*;
import jakarta.persistence.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.test.context.*;
import org.springframework.test.util.*;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Actual PostgreSQL commits and row/advisory locks; never writes files or calls remote services. */
@DataJpaTest(showSql=false,properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.liquibase.enabled=false"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({ExportScheduleFreshRead.class,ExportScheduleExecutionStore.class,RealizedGainExportScheduleExecutionStore.class,
        ExportScheduleService.class,RealizedGainExportScheduleService.class,CommodityExportScheduleService.class,ExchangeRateExportScheduleService.class,
        CommodityExportScheduleExecutionStore.class,ExchangeRateExportScheduleExecutionStore.class,ExportScheduleWritebackPostgresTest.Config.class})
@Testcontainers
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class IndependentAssetGainExportPostgresTest {
    enum Kind { ASSETS(ExportScheduleSetting.class), GAIN(RealizedGainExportSchedule.class), COMMODITY(CommodityExportSchedule.class), RATE(ExchangeRateExportSchedule.class);
        final Class<?> model; Kind(Class<?> m){model=m;}}
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("independent_export_511").withUsername("fixture").withPassword("fixture-only");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",PG::getJdbcUrl);
        r.add("spring.datasource.username",PG::getUsername);r.add("spring.datasource.password",PG::getPassword);}
    @Autowired EntityManager em;
    @Autowired EntityManagerFactory emf;
    @Autowired PlatformTransactionManager tm;
    @Autowired ExportScheduleService assetsService;
    @Autowired RealizedGainExportScheduleService gainService;
    @Autowired ExportScheduleExecutionStore assets;
    @Autowired RealizedGainExportScheduleExecutionStore gain;
    @Autowired CommodityExportScheduleExecutionStore commodity;
    @Autowired ExchangeRateExportScheduleExecutionStore rate;
    @Autowired CommodityExportScheduleService commodityService;
    @Autowired ExchangeRateExportScheduleService rateService;
    @MockitoBean ExcelExportService excel;
    @MockitoBean ExcelDocRenderer xlsx;
    @MockitoBean JsonDocRenderer json;
    @MockitoBean DualFormatExportWriter writer;
    @MockitoBean GdriveOutputSupport drive;
    @MockitoBean CurrentUserContext user;
    static final AtomicLong OWNERS=new AtomicLong(40000);
    static final LocalDate D1=LocalDate.of(2026,9,15), D2=D1.plusDays(1);
    static final LocalDateTime COMPLETED=D2.atTime(12,0);
    Long owner;
    @BeforeEach void setup() throws Exception {
        owner=OWNERS.incrementAndGet();when(user.hasUser()).thenReturn(true);when(user.getEffectiveUserId()).thenReturn(owner);
        when(drive.resolveUpdate(anyLong(),nullable(Boolean.class),nullable(String.class),anyBoolean(),nullable(String.class)))
                .thenAnswer(i->new GdriveOutputSupport.DriveSettings(i.getArgument(1)==null?i.getArgument(3):i.getArgument(1),
                        i.getArgument(2)==null?i.getArgument(4):i.getArgument(2),null));
        when(drive.syncQuietly(anyLong(),nullable(String.class),isNull())).thenReturn(new GdriveOutputSupport.SyncResult("跳過：未產檔",null));
        fakeWriter(()->new DualFormatExportWriter.DualResult(null,null,"local-result","drive-result",null,null));
    }
    Object service(Kind k){return switch(k){case ASSETS->assetsService;case GAIN->gainService;case COMMODITY->commodityService;case RATE->rateService;};}
    Object store(Kind k){return switch(k){case ASSETS->assets;case GAIN->gain;case COMMODITY->commodity;case RATE->rate;};}
    <T>T tx(Supplier<T> f){return new TransactionTemplate(tm).execute(s->f.get());}
    static Object get(Object p,String n){return new BeanWrapperImpl(p).getPropertyValue(n);}
    static void set(Object p,String n,Object v){new BeanWrapperImpl(p).setPropertyValue(n,v);}
    Object request(Kind k,String name,boolean enabled,int hour,String path){return switch(k){
        case ASSETS -> new ExportScheduleDto.ScheduleRequest(name,enabled,hour,0,path,false,null);
        case GAIN -> new RealizedGainExportDto.ScheduleRequest(name,enabled,hour,0,path,false,null);
        case COMMODITY -> new CommodityExportDto.ScheduleRequest(name,enabled,hour,0,path,6,false,null);
        case RATE -> new ExchangeRateExportDto.ScheduleRequest(name,enabled,hour,0,path,6,false,null);
    };}
    Object invoke(Object p,String method,Object...args){return ReflectionTestUtils.invokeMethod(p,method,args);}
    Long seed(Kind k,int hour){return seed(k,owner,hour);}
    Long seed(Kind k,Long o,int hour){return tx(()->{try{
        Object p=k.model.getConstructor().newInstance();set(p,"ownerUserId",o);set(p,"enabled",true);
        set(p,"runHour",hour);set(p,"runMinute",0);set(p,"outputSubpath","old-local");
        if(k==Kind.COMMODITY||k==Kind.RATE)set(p,"rangeMonths",6);set(p,"gdriveEnabled",true);set(p,"gdriveSubpath","old-drive");em.persist(p);em.flush();return (Long)get(p,"id");
    }catch(Exception e){throw new AssertionError(e);}});}
    Map<String,Object> read(Kind k,Long id){return tx(()->{em.clear();Object p=em.find(k.model,id);if(p==null)return Map.of();
        Map<String,Object> r=new HashMap<>();for(String n:List.of("name","enabled","runHour","runMinute","outputSubpath","gdriveEnabled",
                "gdriveSubpath","lastRunDate","lastRunAt","lastRunStatus","gdriveLastRunAt","gdriveLastStatus","updatedAt"))r.put(n,get(p,n));if(k==Kind.COMMODITY||k==Kind.RATE)r.put("rangeMonths",get(p,"rangeMonths"));return r;});}
    Object capture(Kind k,Long id,LocalDate day){List<?> r=invokeDue(k,id,day);assertThat(r).hasSize(1);return r.getFirst();}
    List<?> invokeDue(Kind k,Long id,LocalDate day){return (List<?>)invoke(store(k),"due",id,owner,day,LocalTime.MAX);}
    Object manual(Kind k,Long id){return ((Optional<?>)invoke(store(k),"manual",id,owner)).orElseThrow();}
    void complete(Kind k,Object input,LocalDateTime at,String local,String remote){invoke(store(k),"complete",input,at,local,remote);}
    void edit(Kind k,Long id,Consumer<Object> edit){tx(()->{Object row=em.find(k.model,id);edit.accept(row);return null;});}
    void scheduled(Kind k,Object capture){invoke(AopTestUtils.getUltimateTargetObject(service(k)),"runScheduled",capture);}
    void fakeWriter(Supplier<DualFormatExportWriter.DualResult> f)throws Exception{when(writer.write(anyLong(),any(Path.class),anyString(),nullable(byte[].class),nullable(byte[].class),anyBoolean(),nullable(String.class)))
            .thenAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return f.get();});}
    static void await(CountDownLatch latch){try{assertThat(latch.await(10,TimeUnit.SECONDS)).isTrue();}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    long count(Kind k){return tx(()->em.createQuery("select count(s) from "+k.model.getSimpleName()+" s where s.ownerUserId=:owner",Long.class).setParameter("owner",owner).getSingleResult());}
    Object create(Kind k,Object request){return invoke(service(k),"createForCurrentUser",request);}
    Object update(Kind k,Long id,Object request){return invoke(service(k),"updateForCurrentUser",id,request);}
    Object attempt(Supplier<?> f){try{f.get();return "created";}catch(RuntimeException e){return e;}}

    @ParameterizedTest @EnumSource(Kind.class)
    void concurrentFirstCreatesAreSerializedEvenWithoutExistingRowsAndSameNameIsLegal(Kind k)throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicInteger calls=new AtomicInteger();
        when(drive.resolveUpdate(anyLong(),nullable(Boolean.class),nullable(String.class),anyBoolean(),nullable(String.class)))
                .thenAnswer(i->{if(calls.incrementAndGet()==1){entered.countDown();await(release);}return new GdriveOutputSupport.DriveSettings(false,null,null);});
        var pool=Executors.newFixedThreadPool(2);try{
            var a=pool.submit(()->create(k,request(k,"same",false,8,"same")));await(entered);
            var b=pool.submit(()->create(k,request(k,"same",false,8,"same")));
            assertThatThrownBy(()->b.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();a.get(10,TimeUnit.SECONDS);b.get(10,TimeUnit.SECONDS);assertThat(count(k)).isEqualTo(2);
        }finally{release.countDown();pool.shutdownNow();}
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void tenthSlotConcurrentCreatesCannotExceedLimit(Kind k)throws Exception{
        for(int i=0;i<9;i++)seed(k,8);CountDownLatch start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{var a=pool.submit(()->{await(start);return attempt(()->create(k,request(k,"a",false,8,"same")));});
            var b=pool.submit(()->{await(start);return attempt(()->create(k,request(k,"b",false,8,"same")));});start.countDown();
            var results=List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
            assertThat(results.stream().filter("created"::equals)).hasSize(1);assertThat(results.stream().filter(IllegalArgumentException.class::isInstance)).hasSize(1);
            assertThat(count(k)).isEqualTo(10);
        }finally{start.countDown();pool.shutdownNow();}
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void foreignAndUnknownIdsReturnNotFoundWithoutMutations(Kind k){Long foreign=seed(k,owner+100000,8);
        for(Long id:List.of(foreign,foreign+1000)){
            assertThatThrownBy(()->update(k,id,request(k,"edit",false,9,"new"))).isInstanceOf(NoSuchElementException.class);
            assertThatThrownBy(()->invoke(service(k),"deleteForCurrentUser",id)).isInstanceOf(NoSuchElementException.class);
            assertThatThrownBy(()->invoke(service(k),"runNowForCurrentUser",id)).isInstanceOf(NoSuchElementException.class);
        }
        assertThat(read(k,foreign).get("outputSubpath")).isEqualTo("old-local");assertThat(count(k)).isZero();verifyNoInteractions(writer);
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void differentDueRowsKeepSeparateGuardsAndManualRunOnlyChangesItsStatus(Kind k){
        Long a=seed(k,0),b=seed(k,0);Object ca=capture(k,a,D1);complete(k,ca,COMPLETED,"first",null);
        assertThat(invokeDue(k,a,D1)).isEmpty();assertThat(invokeDue(k,b,D1)).hasSize(1);
        Object cm=manual(k,b);complete(k,cm,COMPLETED,"manual",null);
        assertThat(read(k,b).get("lastRunDate")).isNull();assertThat(read(k,b).get("lastRunStatus")).isEqualTo("manual");
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void lateD1ResultAfterD2DoesNotRollGuardOrNewerCompletionBackward(Kind k){
        Long id=seed(k,0);Object d1=capture(k,id,D1),d2=capture(k,id,D2);
        complete(k,d2,COMPLETED,"new","drive-new");complete(k,d1,COMPLETED.minusHours(1),"old","drive-old");
        var r=read(k,id);assertThat(r.get("lastRunDate")).isEqualTo(D2);assertThat(r.get("lastRunStatus")).isEqualTo("new");
        assertThat(r.get("gdriveLastStatus")).isEqualTo("drive-new");
    }
    static Stream<Arguments> changedFields(){return Arrays.stream(Kind.values()).flatMap(k->Stream.concat(Stream.of("name","enabled","runHour","runMinute","outputSubpath","gdriveEnabled","gdriveSubpath"),k==Kind.COMMODITY||k==Kind.RATE?Stream.of("rangeMonths"):Stream.empty()).map(f->Arguments.of(k,f)));}
    @ParameterizedTest @MethodSource("changedFields")
    void capturedResultIsDiscardedAfterEverySettingsFieldChanges(Kind k,String field){
        Long id=seed(k,8);Object c=capture(k,id,D1);Object value=switch(field){case "name"->"new-name";case "enabled","gdriveEnabled"->false;
            case "runHour"->9;case "runMinute"->15;case "rangeMonths"->12;default->"new-path";};edit(k,id,p->set(p,field,value));
        complete(k,c,COMPLETED,"stale","stale-drive");var r=read(k,id);assertThat(r.get(field)).isEqualTo(value);
        assertThat(r.get("lastRunDate")).isNull();assertThat(r.get("lastRunAt")).isNull();assertThat(r.get("gdriveLastRunAt")).isNull();
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void deletionAndRecreationNeverReceiveOrResurrectOldResult(Kind k){
        Long id=seed(k,8);Object c=capture(k,id,D1);invoke(service(k),"deleteForCurrentUser",id);Long replacement=seed(k,8);
        complete(k,c,COMPLETED,"stale","stale-drive");assertThat(read(k,id)).isEmpty();assertThat(replacement).isNotEqualTo(id);
        assertThat(read(k,replacement).get("lastRunAt")).isNull();
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void settingsEditDuringBlockedIoDiscardsLateResultAndPreservesGuard(Kind k)throws Exception{
        Long id=seed(k,0);Object c=capture(k,id,D1);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        fakeWriter(()->{entered.countDown();await(release);return new DualFormatExportWriter.DualResult(null,null,"stale","stale-drive",null,null);});
        var pool=Executors.newSingleThreadExecutor();try{var result=pool.submit(()->scheduled(k,c));await(entered);
            update(k,id,request(k,"new",false,9,"new-local"));release.countDown();result.get(10,TimeUnit.SECONDS);
            var r=read(k,id);assertThat(r.get("outputSubpath")).isEqualTo("new-local");assertThat(r.get("lastRunDate")).isNull();assertThat(r.get("lastRunStatus")).isNull();
        }finally{release.countDown();pool.shutdownNow();}
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void freshOsivReadPreventsLateCaptureFromOverwritingCommittedEdit(Kind k)throws Exception{
        Long id=seed(k,8);EntityManager osiv=emf.createEntityManager();TransactionSynchronizationManager.bindResource(emf,new EntityManagerHolder(osiv));
        try{Object c=manual(k,id);var pool=Executors.newSingleThreadExecutor();try{pool.submit(()->update(k,id,request(k,"edited",false,9,"osiv-new"))).get(10,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
            complete(k,c,COMPLETED,"stale","stale-drive");
        }finally{TransactionSynchronizationManager.unbindResource(emf);osiv.close();}
        var r=read(k,id);assertThat(r.get("outputSubpath")).isEqualTo("osiv-new");assertThat(r.get("lastRunAt")).isNull();assertThat(r.get("gdriveLastRunAt")).isNull();
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void manualExportSuspendsCallingTransactionAndDoesNotSetDailyGuard(Kind k){Long id=seed(k,0);
        tx(()->{invoke(service(k),"runNowForCurrentUser",id);assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();return null;});
        assertThat(read(k,id).get("lastRunDate")).isNull();assertThat(read(k,id).get("lastRunStatus")).isEqualTo("local-result");
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void fileFailureRecordsGuardAndSkippedDriveWithinIndependentShortTransaction(Kind k)throws Exception{
        Long id=seed(k,0);Object c=capture(k,id,D1);fakeWriter(()->{throw new IllegalStateException("file-failure");});scheduled(k,c);
        var r=read(k,id);assertThat(r.get("lastRunDate")).isEqualTo(D1);assertThat(r.get("lastRunStatus").toString()).contains("file-failure");assertThat(r.get("gdriveLastStatus")).isEqualTo("跳過：未產檔");
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void columnLimitsAreEnforcedWithoutRollingBackDateGuard(Kind k){Long id=seed(k,0);Object c=capture(k,id,D1);
        edit(k,id,p->set(p,"updatedAt",COMPLETED.plusHours(1)));complete(k,c,COMPLETED,"x".repeat(900),"y".repeat(900));
        var r=read(k,id);assertThat(r.get("lastRunDate")).isEqualTo(D1);assertThat(r.get("lastRunStatus").toString()).hasSize(500);assertThat(r.get("gdriveLastStatus").toString()).hasSize(512);
        assertThat(r.get("updatedAt")).isEqualTo(COMPLETED.plusHours(1));
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void deniedInitialCreateRollsBackAndDoesNotLeaveAPlaceholder(Kind k){when(drive.resolveUpdate(anyLong(),nullable(Boolean.class),nullable(String.class),anyBoolean(),nullable(String.class))).thenThrow(new AdminRequiredException("denied"));
        assertThatThrownBy(()->create(k,request(k,null,false,8,"local"))).isInstanceOf(AdminRequiredException.class);assertThat(count(k)).isZero();}
    Object alteredCapture(Object input, String field, Object value) {
        try {
            var fields=input.getClass().getRecordComponents();Object[] values=new Object[fields.length];Class<?>[] types=new Class<?>[fields.length];
            for(int i=0;i<fields.length;i++){types[i]=fields[i].getType();values[i]=fields[i].getName().equals(field)?value:fields[i].getAccessor().invoke(input);}
            return input.getClass().getConstructor(types).newInstance(values);
        } catch(Exception e){throw new AssertionError(e);}
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void resultIdentityRequiresExactIdOwnerAndCapturedTime(Kind k){Long id=seed(k,8);Object c=capture(k,id,D1);
        complete(k,alteredCapture(c,"scheduleId",id+100000),COMPLETED,"wrong-id",null);
        complete(k,alteredCapture(c,"ownerId",owner+100000),COMPLETED,"wrong-owner",null);
        complete(k,alteredCapture(c,"runHour",9),COMPLETED,"wrong-time",null);
        assertThat(read(k,id).get("lastRunDate")).isNull();assertThat(read(k,id).get("lastRunAt")).isNull();
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void separateRowsHaveNoSharedSummaryOrExecutionState(Kind k){Long first=seed(k,8),second=seed(k,9);
        Object a=capture(k,first,D2),b=capture(k,second,D2);
        complete(k,a,COMPLETED.plusHours(1),"newer-first",null);complete(k,b,COMPLETED,"older-second",null);
        assertThat(read(k,first).get("lastRunStatus")).isEqualTo("newer-first");assertThat(read(k,second).get("lastRunStatus")).isEqualTo("older-second");
        assertThat(read(k,second).get("lastRunDate")).isEqualTo(D2);
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void completionWaitsForActualUiRowLockThenDiscardsOldSettings(Kind k)throws Exception{
        Long id=seed(k,8);Object c=capture(k,id,D1);CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1);
        when(drive.resolveUpdate(anyLong(),nullable(Boolean.class),nullable(String.class),anyBoolean(),nullable(String.class)))
                .thenAnswer(i->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();locked.countDown();await(release);return new GdriveOutputSupport.DriveSettings(false,"edited-drive",null);});
        var pool=Executors.newFixedThreadPool(2);try{
            var ui=pool.submit(()->update(k,id,request(k,"edit",false,8,"edited-local")));await(locked);
            var outcome=pool.submit(()->complete(k,c,COMPLETED,"stale","stale-drive"));
            assertThatThrownBy(()->outcome.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();ui.get(10,TimeUnit.SECONDS);outcome.get(10,TimeUnit.SECONDS);
            var r=read(k,id);assertThat(r.get("outputSubpath")).isEqualTo("edited-local");assertThat(r.get("lastRunStatus")).isNull();assertThat(r.get("lastRunDate")).isNull();
        }finally{release.countDown();pool.shutdownNow();}
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void manualFailureRecordsResultWithoutMovingExistingGuard(Kind k)throws Exception{Long id=seed(k,8);edit(k,id,p->set(p,"lastRunDate",D1));
        fakeWriter(()->{throw new IllegalStateException("manual-file-failure");});
        assertThatThrownBy(()->invoke(service(k),"runNowForCurrentUser",id)).isInstanceOf(RuntimeException.class).hasMessageContaining("manual-file-failure");
        var r=read(k,id);assertThat(r.get("lastRunDate")).isEqualTo(D1);assertThat(r.get("lastRunStatus").toString()).contains("manual-file-failure");assertThat(r.get("gdriveLastStatus")).isEqualTo("跳過：未產檔");
    }
    @ParameterizedTest @EnumSource(Kind.class)
    void unknownManualNeverCreatesScheduleOrPerformsFileIo(Kind k){assertThatThrownBy(()->invoke(service(k),"runNowForCurrentUser",999999L)).isInstanceOf(NoSuchElementException.class);
        assertThat(count(k)).isZero();verifyNoInteractions(writer);}
}
