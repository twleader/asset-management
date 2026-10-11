# [t491] 升級三個 JVM 服務到 Spring Boot 4.1.1 與 Java 25

**對應 Requirements:** Requirement 189（統一 JVM runtime 並保持 API／金融安全契約）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

目前 backend、bff、external-materials-service 使用 Boot 3.4.4／Java21，BFF Cloud2024.0.1，Docker build/runtime也是21。使用者要求先升級指定的 Boot4.1.x stable line及Java25。經官方與Maven metadata核對，目標Boot4.1.1、Cloud2025.1.3、Java25（本機Temurin25.0.3）。Java25為LTS，Boot4.1.x不可宣稱官方LTS。本任務不實作Flowable、不改金融決策與排程。

## 要做什麼

- [ ] 491.1 三個 pom parent=4.1.1、java.version/release=25；bff Cloud BOM=2025.1.3；build image=maven:3.9-eclipse-temurin-25、runtime=eclipse-temurin:25-jre-alpine。Lombok去除1.18.38 override並讓processor引用BOM版本。backend/ext既有Testcontainers1.21.4保留及其舊artifact IDs／POI commons-compress相容處理。
- [ ] 491.2 按Boot4模組拆分starter：MVC使用spring-boot-starter-webmvc，AOP使用spring-boot-starter-aspectj，Liquibase使用spring-boot-starter-liquibase；測試顯式webmvc-test/webflux-test/data-jpa-test視模組需要加入。Gateway使用spring-cloud-starter-gateway-server-webflux與spring.cloud.gateway.server.webflux.*。依官方新模組搬移WebMvcTest/DataJpaTest/AutoConfigureTestDatabase/HibernatePropertiesCustomizer等import；MockBean/SpyBean改MockitoBean/MockitoSpyBean。不得在原無Security的backend額外引入Security來改filter語意。
- [ ] 491.3 保留Jackson2：加入官方spring-boot-jackson2，排除各相關main/test starter的spring-boot-starter-jackson（Jackson3）自動接管；spring.http.converters.preferred-json-mapper=jackson2及spring.http.codecs.preferred-json-mapper=jackson2。既有com.fasterxml.jackson主程式不做無關大量重寫；裸WebClient/custom ExchangeStrategies/test bindToController需一致使用Jackson2 codecs，不能只信property。實際HTTP回應驗精確decimal、null、ISO日期及request duplicate rejection；JCS／immutable receipt／未知欄位契約不變。
- [ ] 491.4 保留tenant、configured-admin bootstrap、OAuth2/select_account、token及exact 9090 routes，禁broker寫入／行情refresh／寄信smoke。Boot4的spring.codec.max-in-memory-size移至spring.http.codecs.max-in-memory-size並保持SRPP 1MiB+1拒絕；保留CORS origins/credentials、forwarded headers、profiles和所有安全限制。Hibernate7 filter/JPQL/JSONB/rollback/unique-index等現有全套測試不能以刪除、跳過、放寬assertions處理失敗；只做因新framework而必要的相容調整。
- [ ] 491.5 Docker保留非root、TZ=Asia/Taipei、UTF-8locale、memory/GCflags；backend保留pg16-client/rclone/fontconfig/freetype/dejavu/notoCJK/AWT；ext保留curl/rclone及中文檔名。部署先比對main有效env/secrets/TLS掛載，禁止顯示secret值。只rebuild/recreate此次三個JVM服務，不改datastore volumes或broker flags。
- [ ] 491.6 更新當前tech描述、activeDocker範例及版本說明；歷史任務的Boot3/Java21證據保留原歷史語意。API契約與三份Swagger需保持位元組一致，這個任務不新增route或schema migration。獨立spec及arch審查，通過runtime驗收後feature commit→main --no-ff merge→push，最後main重建再次驗證。
- [ ] 491.7 修復 Liquibase 5 對唯一既有 formatted SQL header logicalFilePath 的相容問題：master 的 v1.56 calendar include 明示 `logicalFilePath: db/changelog/changes/v1.55.0-trading-calendar-export-schedule.sql`。不改已套用 SQL bytes、id/author/checksum，亦不新增 changeset 或修改正式 databasechangelog。新增 metadata-only 歷史 fixture（只含身分/checksum，執行日期／順序用測試值）與 parser regression：完整 master 187 個 changeset 必與舊 4.29.2 解析結果逐筆相同、且全部命中歷史。真隔離 PostgreSQL seed 194 個已執行身分後，正常 SpringLiquibase.afterPropertiesSet 必成功且 history 不變／新增 changeset=0；負向控制移除該 include logicalFilePath 必重跑 v1.56 並因既有 calendar table 失敗。不得用 skip/markRan/clearCheckSums、pin 舊版或修改正式 DB 掩蓋問題。

## 驗證

三個模組各自目錄執行JDK25 clean test package，測試log保留命令／版本／數量／失敗與skip原因。不可使用net.bytebuddy.experimental或Java21替代，不可silent skip真PostgreSQL測試。新增actual MVC/WebFlux wire回歸並驗實際classpath/dependency-tree無Jackson3 HTTP接管。

```bash
cd backend
JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home /usr/local/apache-maven/current/bin/mvn -B clean test package
```

對bff及external-materials-service在各自目錄跑同一命令。root檢查：

```bash
bash scripts/spec-check.sh
bash scripts/tests/schema-sql-drift-test.sh
ruby scripts/render-9090-openapi-docs.rb --check
```

Docker透過run-stack流程重建且force-recreate business-services、bff、external-materials-service；容器內java -version與jar Boot版本實為25／4.1.1。驗health與一個既有純讀JSON路徑的schema/body，檢查TZ／locale／nonroot／字型／pg_dump/rclone/curl。必要的owner/date/calendar拒絕測試只證拒絕，不能當正常金融計算成功證據。main落地後從main部署並重驗同一組安全檢查。



### 可直接執行的 Docker 驗收

部署前先以不輸出值的比較器確認 feature/main `.env` 有效鍵值相同、TLS及rclone等唯讀掛載使用正式main來源；不一致則修正來源再部署。feature code驗收時project固定asset-management；必須在feature根目錄執行，但使用main正式env與掛載，不能把過期featureenv／fallbackTLS部署到共享image。以下命令在驗證過有效設定的feature根目錄執行；landing後同樣命令改在 `/Users/steven/Project/asset-management-main` 執行。

```bash
docker compose -p asset-management build business-services bff external-materials-service
docker compose -p asset-management up -d --no-deps --force-recreate business-services bff external-materials-service
docker compose -p asset-management ps business-services bff external-materials-service
docker compose -p asset-management exec -T business-services curl --fail --silent http://localhost:8080/api/snapshots
docker compose -p asset-management exec -T external-materials-service curl --fail --silent http://localhost:8080/internal/health
docker compose -p asset-management exec -T bff wget -qO- http://localhost:8080/actuator/health
curl --fail --silent 'http://127.0.0.1:9090/api/public/trading-calendar?year=2026' -o /tmp/boot41-calendar.json
python3 - <<'CHECK'
import json
v=json.load(open('/tmp/boot41-calendar.json'))
assert v['year']==2026 and v['timezone']=='Asia/Taipei'
assert len(v['days'])==365 and len({d['date'] for d in v['days']})==365
assert all(d[k] is None or isinstance(d[k],bool) for d in v['days'] for k in ('twTrading','usTrading','ukTrading'))
assert set(v['marketStatus'])=={'tw','us','uk'}
print('verified complete 2026 calendar wire response')
CHECK
```

BFF使用image內wget；其healthcheck已在compose內探測 `/actuator/health`，以container `.State.Health.Status=healthy`及healthcheck output確認body UP。三服務healthy上限120秒，JSON smoke只接受200與上述schema；availability若含null market flags須按正式nullable schema驗證並回報authority不可用，不能填false或宣称calendar證明開市。本例按nullable schema驗證 wire，null情況另外回報且不做金融probe。每個容器再以java -version、jar MANIFEST.MF及套件／locale驗證真實Java25／Boot4.1.1、非root、TZ=Asia/Taipei、UTF-8；backend驗pg_dump16/rclone與TC font，ext驗curl/rclone與中文檔名。不要以health或jar成功替代wire驗證。


## 完成報告

實作與 feature run-stack 驗收於 2026-10-11 完成；Git 收尾接續以 commit-merge-push 執行，通過後再從 main 重建複驗。

- backend JDK25 clean test package：318 classes／3029 tests、0 failure/error、2 既有條件性 skip；新增 Liquibase parser／真 PostgreSQL 4 tests 全通過，Linux filesystem 補充回歸全通過。BFF：819 tests、0 failure/error/skip；external：850 tests、0 failure/error/skip。合計4698 tests，未用 experimental flags 或 Java21 代替。
- 完整80檔最終差異獨立arch查證 critical/major/minor=0/0/0；Liquibase相容規格亦独立查證0/0/0。新候選容器三服務實際 Java25／Boot4.1.1、Jackson2 bridge且無Jackson3接管，皆healthy；UTF8／台北時區／nonroot／profile／memory-GC args、正式env/mounts一致。backend中文TC glyph／AWT PNG與pg_dump16/rclone/curl、ext工具／Unicode檔名與ProcessBuilder驗證通過。
- 唯讀9090 global日曆2026：200、完整365唯一日期、nullableboolean合法且三市場nullcount=0。未執行金融API、券商／Gmail／日報／rescan probe，其他容器未重建。
- Liquibase5修法僅master calendar include logicalFilePath一行，已套用SQL bytes不改。正式194筆歷史 id/author/filename/checksum前後逐位元相同（SHA256 c98225fb3871efae755e1f8a0e7fed77aee76561a1e49045ed114fccb82eefd6）；schema-only pg_dump實際重產115表，db/schema.sql bytes不變。B10及原金融renderer三份--check通過，未修改SRPP。
- 驗證紀錄：/tmp/boot41-backend-validation.json、/tmp/boot41-bff-final2.log、/tmp/boot41-ext-full.log、/tmp/boot41-runtime-corrected-acceptance.log及corrected-final-check.log。spec-check BLOCK0／CHECK1僅歷史migrationidentity引用，非schema現況來源；已由獨立審查確認。
