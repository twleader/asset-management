# [t407] 角色功能管理——一般使用者可用功能由管理者設定

**對應 Requirements:** Requirement 134（角色功能管理：管理者可設定一般使用者角色能看到／使用哪些既有功能頁面，不必改程式碼）
**前置任務:** 無（依賴既有 Requirement 28 認證/多租戶機制與既有 `/settings/*` 設定頁模式，皆已落地）
**Liquibase changeset:** v1.121.0-app-feature-role-access.sql

## 背景

系統目前只有兩種角色：`ADMIN`（管理者）、`USER`（一般使用者），定義於 `backend/src/main/java/com/steven/assets/model/AppUser.java`（`ROLE_ADMIN = "ADMIN"`、`ROLE_USER = "USER"` 常數，`role` 欄位為純字串、非 JPA enum）。目前「僅管理者可用」的限制（使用者管理、備份/還原等）全部寫死在三處程式碼，彼此獨立、靠人工保持一致：

1. 前端路由 `frontend/src/router/index.js`：`meta: { requiresAdmin: true }` ＋ 全域 `beforeEach` guard（約第 246 行）：`if (to.meta.requiresAdmin && !auth.isAdmin) return '/dashboard'`。
2. 前端選單 `frontend/src/App.vue`：各處 `v-if="auth.isAdmin"`。
3. 後端／BFF：BFF `bff/src/main/java/com/steven/assets/bff/config/SecurityConfig.java` 的 `pathMatchers(...).hasAuthority(...)`，backend `backend/src/main/java/com/steven/assets/security/AdminGateInterceptor.java` 的路徑清單。

目前除了這組寫死的「管理者專屬」清單外，**一般使用者角色對其餘所有功能頁面沒有任何細粒度限制**——一般使用者能看到、進入除管理者專屬頁面外的全部選單項目。使用者要求新增一個「角色功能管理」頁面，讓管理者可以自行勾選/取消勾選，決定一般使用者角色能看到哪些既有功能，不需要改程式碼重新部署。

**正確行為（本任務要做到的）：** 管理者在「權限管理 → 角色功能管理」頁面看到 25 個可控管功能項目（清單見下方，對應現有側邊選單中原本對一般使用者全開的頁面），可切換每一項對一般使用者角色的開放狀態；一般使用者下次整頁載入或重新登入後，被關閉的功能項目不再出現在其側邊選單，且直接輸入該頁面網址會被路由導回 `/dashboard`。管理者角色永遠看得到、用得到全部功能（含本頁面本身），不受此設定影響。

**明確不做（範圍界線，避免實作或審查誤判為遺漏）：**

- **不新增／變更任何業務 API endpoint 本身的權限檢查。** 本任務只控制前端選單顯示與路由導覽層級的存取。一般使用者若技術上直接呼叫被關閉功能對應的業務 API（例如 `/api/history`），目前仍可取得資料——這與功能關閉前的既有行為（該端點本就無角色限制）一致。這是刻意的範圍界線，不是安全漏洞遺漏。
- **不動既有寫死的管理者專屬限制。** `SecurityConfig`、`AdminGateInterceptor`、`router` 的 `meta.requiresAdmin` 涵蓋的「使用者管理」「備份/還原 資料」等既有端點與頁面完全不變，不整合進本次新表。
- **不把角色本身資料庫化。** `AppUser.ROLE_ADMIN`／`ROLE_USER` 常數與 `backend/src/main/java/com/steven/assets/service/UserAdminService.java` 的 `VALID_ROLE = Set.of(AppUser.ROLE_ADMIN, AppUser.ROLE_USER)` 驗證集合維持原樣，不做成資料庫可擴充的角色主檔。
- **不開放管理者透過本頁新增／刪除功能項目。** 25 項清單由系統 seed 固定維護，管理者只能切換既有項目的開關。

## 要做什麼

### 407.1 資料庫

新增 `backend/src/main/resources/db/changelog/changes/v1.121.0-app-feature-role-access.sql`：

```sql
--liquibase formatted sql
--changeset steven:v1.121.0-app-feature-role-access
--comment: Requirement 134／Task 407：角色功能管理，一般使用者角色可用功能由管理者設定
CREATE TABLE IF NOT EXISTS app_feature (
    id BIGSERIAL PRIMARY KEY,
    code VARCHAR(100) NOT NULL UNIQUE,
    display_name VARCHAR(50) NOT NULL,
    menu_group VARCHAR(50),
    sort_order INTEGER NOT NULL DEFAULT 0,
    enabled_for_user BOOLEAN NOT NULL DEFAULT TRUE
);
```

在 `backend/src/main/resources/db/changelog/db.changelog-master.yaml` 尾端（緊接 `v1.120.0-fubon-etf-holdings-snapshot.sql` 之後）追加：

```yaml
  - include:
      file: db/changelog/changes/v1.121.0-app-feature-role-access.sql
      relativeToChangelogFile: false
```

完成後依 `db/schema.sql` 檔頭「重新產生」段的指令重產該檔，納入本次變更；執行 `bash scripts/tests/schema-sql-drift-test.sh` 確認回傳 0。

### 407.2 Entity / Repository（backend）

新增 `backend/src/main/java/com/steven/assets/model/AppFeature.java`：

```java
package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "app_feature")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppFeature {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String code;

    @Column(nullable = false, length = 50)
    private String displayName;

    @Column(length = 50)
    private String menuGroup;

    @Column(nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean enabledForUser = true;
}
```

新增 `backend/src/main/java/com/steven/assets/repository/AppFeatureRepository.java`：

```java
package com.steven.assets.repository;

import com.steven.assets.model.AppFeature;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AppFeatureRepository extends JpaRepository<AppFeature, Long> {

    Optional<AppFeature> findByCode(String code);

    List<AppFeature> findAllByOrderBySortOrderAscDisplayNameAsc();
}
```

（不需要 `findByEnabledForUser` 等額外查詢方法——列表一律回全部 25 筆，前端自行依 `enabledForUser` 過濾。）

### 407.3 DataInitializer seed

在 `backend/src/main/java/com/steven/assets/config/DataInitializer.java` 依既有 `seedMarketTypes()` 等方法的寫法（record + `List.of(...)` + `findByCode` 冪等判斷），新增 `seedAppFeatures()` 並在 `run()` 內呼叫。**關鍵：只在列不存在時新增，已存在的列一律跳過（不得覆寫 `enabledForUser`）**，避免每次重啟服務把管理者先前的關閉設定打回開啟：

```java
private void seedAppFeatures() {
    record FeatureSeed(String menuGroup, String code, String displayName, int sortOrder) {}

    List<FeatureSeed> seeds = List.of(
        new FeatureSeed("資產管理", "/history", "歷年資產管理", 1),
        new FeatureSeed("資產管理", "/realized-gains", "已實現損益", 2),
        new FeatureSeed("資產管理", "/transactions", "交易紀錄", 3),
        new FeatureSeed("資產管理", "/asset-allocation-advice", "資產配置建議", 4),
        new FeatureSeed("資產管理", "/payment-accounts", "自動代繳", 5),
        new FeatureSeed("股市綜合分析", "/gdp-twse", "股市大盤查詢", 6),
        new FeatureSeed("股市綜合分析", "/performance-comparison", "績效比較", 7),
        new FeatureSeed("股市綜合分析", "/today-market-analysis", "今日股市分析", 8),
        new FeatureSeed("股市綜合分析", "/trading-radar", "今日交易雷達", 9),
        new FeatureSeed("股市綜合分析", "/stocks", "股票觀察", 10),
        new FeatureSeed("公開資訊", "/trading-calendar", "交易日曆", 11),
        new FeatureSeed("公開資訊", "/exchange-rate", "台幣兌美元", 12),
        new FeatureSeed("公開資訊", "/commodity-price", "油價金價", 13),
        new FeatureSeed("公開資訊", "/crawler-data", "爬蟲資訊查詢", 14),
        new FeatureSeed("系統資訊", "/schedule-list", "排程列表", 15),
        new FeatureSeed("系統資訊", "/open-api", "開放 API", 16),
        new FeatureSeed("系統資訊", "/fubon-api", "富邦證 API", 17),
        new FeatureSeed("系統設定", "/settings/banks", "銀行設定", 18),
        new FeatureSeed("系統設定", "/settings/brokers", "券商設定", 19),
        new FeatureSeed("系統設定", "/settings/deposit-types", "存款類型設定", 20),
        new FeatureSeed("系統設定", "/settings/market-types", "市場類型設定", 21),
        new FeatureSeed("系統設定", "/settings/asset-classes", "資產類別歸類", 22),
        new FeatureSeed("系統設定", "/settings/transit-fund-types", "在途款項類型設定", 23),
        new FeatureSeed("系統設定", "/settings/funds", "信託基金設定", 24),
        new FeatureSeed("系統設定", "/settings/notifications", "警示通知設定", 25)
    );

    for (FeatureSeed s : seeds) {
        if (appFeatureRepo.findByCode(s.code()).isEmpty()) {
            appFeatureRepo.save(AppFeature.builder()
                    .code(s.code())
                    .displayName(s.displayName())
                    .menuGroup(s.menuGroup())
                    .sortOrder(s.sortOrder())
                    .enabledForUser(true)
                    .build());
            log.info("初始化功能項目: {}", s.displayName());
        }
    }
}
```

`/dashboard`（總覽儀表板）與既有寫死僅管理者可見的頁面（`/settings/users`、`/settings/backup-restore`、`/settings/role-features` 本身）**不放進這份清單**。

### 407.4 DTO / Service / Controller（backend，`/api/settings/app-features`）

在 `backend/src/main/java/com/steven/assets/dto/`（可新增獨立 `AppFeatureDto.java`，或加進既有 `InstitutionDto.java`，擇一即可，需符合現有 record 慣例）：

```java
public record AppFeatureResponse(
        Long id,
        String code,
        String displayName,
        String menuGroup,
        Integer sortOrder,
        Boolean enabledForUser
) {}

public record UpdateAppFeatureEnabledRequest(
        @NotNull Boolean enabled
) {}
```

在既有 `InstitutionService`（`backend/src/main/java/com/steven/assets/service/InstitutionService.java`，比照 MarketType 區塊的分層寫法）或新開一支 `AppFeatureService`（擇一，須符合 Clean Architecture：業務邏輯不放 Controller），新增：

```java
@Transactional(readOnly = true)
public List<AppFeatureResponse> getAllAppFeatures() {
    return appFeatureRepo.findAllByOrderBySortOrderAscDisplayNameAsc().stream()
            .map(this::toAppFeatureResponse)
            .toList();
}

@Transactional
public AppFeatureResponse setAppFeatureEnabledForUser(Long id, boolean enabled) {
    AppFeature entity = appFeatureRepo.findById(id)
            .orElseThrow(() -> new java.util.NoSuchElementException("找不到功能項目 ID: " + id));
    entity.setEnabledForUser(enabled);
    return toAppFeatureResponse(appFeatureRepo.save(entity));
}

private AppFeatureResponse toAppFeatureResponse(AppFeature f) {
    return new AppFeatureResponse(f.getId(), f.getCode(), f.getDisplayName(), f.getMenuGroup(), f.getSortOrder(), f.getEnabledForUser());
}
```

新增 Controller（可加進既有 `InstitutionController`，`@RequestMapping("/api/settings")`，或新開一支同前綴的 controller）：

```java
@GetMapping("/app-features")
public List<AppFeatureResponse> getAllAppFeatures() {
    return institutionService.getAllAppFeatures(); // 或對應 service
}

@PatchMapping("/app-features/{id}/enabled-for-user")
public ResponseEntity<AppFeatureResponse> setAppFeatureEnabledForUser(
        @PathVariable Long id,
        @Valid @RequestBody UpdateAppFeatureEnabledRequest req) {
    return ResponseEntity.ok(institutionService.setAppFeatureEnabledForUser(id, req.enabled()));
}
```

**不提供 `POST`／`DELETE`。** `/api/settings/**` 已被既有 `WebConfig.java`（第 24-27 行）與 `AdminGateInterceptor.java` 的 `isGlobalReferenceDataPath` 通用規則涵蓋（GET 開放已登入者、寫入限 ADMIN），**不需要改動這兩個檔案**。

### 407.5 BFF route + SecurityConfig

新增 `bff/src/main/java/com/steven/assets/bff/appfeaturesettings/AppFeatureSettingsBffRoutes.java`（比照 `MarketTypeSettingsBffRoutes.java` 寫法）：

```java
package com.steven.assets.bff.appfeaturesettings;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppFeatureSettingsBffRoutes {

    @Value("${business-services.url}")
    private String businessServicesUrl;

    @Bean
    public RouteLocator appFeatureSettingsRoutes(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("app-feature-settings-route", r -> r
                        .path("/api/bff/app-feature-settings/**")
                        .filters(f -> f.rewritePath(
                                "/api/bff/app-feature-settings(?<seg>/?.*)",
                                "/api/settings/app-features${seg}"))
                        .uri(businessServicesUrl))
                .build();
    }
}
```

在 `bff/src/main/java/com/steven/assets/bff/config/SecurityConfig.java` 的 `GLOBAL_SETTINGS_PATHS` 陣列（約第 62-73 行）加入一行：

```java
            "/api/bff/app-feature-settings/**"
```

**不需要新增任何 `.pathMatchers(...)` 規則**——加進這個陣列即自動套用「POST/PUT/PATCH/DELETE 限 ADMIN、GET 落到 `anyExchange().authenticated()`」的既有規則。

### 407.6 前端 API 呼叫

`frontend/src/api/index.js` 新增：

```js
export const appFeatureSettingsApi = {
  getAll: () => api.get('/bff/app-feature-settings'),
  setEnabledForUser: (id, enabled) => api.patch(`/bff/app-feature-settings/${id}/enabled-for-user`, { enabled })
}
```

### 407.7 authStore：選單/路由過濾狀態

`frontend/src/stores/authStore.js` 新增 state `disabledFeatureCodes`（初始 `[]`），以及：

- 在既有 `fetchMe()` 成功後（`me` 已設定、`isAdmin` 可判斷之後），若 `!isAdmin`，呼叫 `appFeatureSettingsApi.getAll()`，把回應中 `enabledForUser === false` 的項目的 `code` 存進 `disabledFeatureCodes`；若 `isAdmin === true`，`disabledFeatureCodes` 固定為 `[]`（不呼叫 API）。呼叫失敗時 fail-open 或 fail-closed 皆須明確：本任務採 **fail-closed**（呼叫失敗時 `disabledFeatureCodes` 維持空陣列，即退回「全部顯示」，因為本功能只影響 UI 可見性、非安全邊界，失敗時不應讓一般使用者整個選單消失）。
- 新增 getter 或方法 `isFeatureEnabled(path)`：

```js
isFeatureEnabled(path) {
  return this.isAdmin || !this.disabledFeatureCodes.includes(path)
}
```

### 407.8 App.vue：選單過濾

`frontend/src/App.vue` 的 template：

- 對 `mainMenuItems` 的 `v-for` 區塊（第 20-35 行附近），單層項目（`el-menu-item v-else`）與群組（`el-sub-menu` 內的 `children`）渲染前都要加上 `v-if="auth.isFeatureEnabled(item.path)"`／`v-if="auth.isFeatureEnabled(child.path)"`。群組本身：若過濾後 `children` 為空陣列，該 `el-sub-menu` 不渲染（可在 `mainMenuItems` computed 內先過濾好 `children`，或在 template 用 `v-if` 判斷過濾後長度 > 0，兩種寫法皆可，擇一）。
- 對硬編碼的「系統設定」`el-sub-menu`（第 49-91 行附近，緊接在第 37-47 行「權限管理」`index="permissions"` 子選單之後）內非管理者專屬的 8 個 `el-menu-item`（銀行設定、券商設定、存款類型設定、市場類型設定、資產類別歸類、在途款項類型設定、信託基金設定、警示通知設定），逐一加上 `v-if="auth.isFeatureEnabled('/settings/banks')"` 等（每項對應各自 path）。**「備份/還原 資料」維持原有 `v-if="auth.isAdmin"`，不加這個判斷。**
- 「自動代繳」（`path: '/payment-accounts'`）目前是 `mainMenuItems` 內「資產管理」群組（`index: 'asset-management'`）`children` 陣列的最後一項（非獨立頂層項目），隨該群組 `children` 的通用過濾規則（上方第一點）自動套用 `auth.isFeatureEnabled(child.path)`，不需要另外特殊處理。

在既有 `index="permissions"` 子選單（本次「權限管理」分組，前一次變更已建立）內、「使用者管理」`el-menu-item` 之後，新增：

```html
<el-menu-item index="/settings/role-features">
  <el-icon><Grid /></el-icon>
  <template #title>角色功能管理</template>
</el-menu-item>
```

（`Grid` 為 `@element-plus/icons-vue` 既有圖示，透過 `frontend/src/main.js` 既有的全域迴圈註冊，無需額外 import；若實作時發現該圖示已被其他既有選單項目使用而想避免視覺混淆，可換用其他未使用過的既有圖示，唯一要求是圖示需存在於 `@element-plus/icons-vue`。）

此 `el-menu-item` **不需要**額外的 `v-if="auth.isFeatureEnabled(...)"`——沿用父層 `el-sub-menu v-if="auth.isAdmin"` 的既有寫死管理者限制（與「使用者管理」相同模式）。

### 407.9 router/index.js：新路由 + guard 擴充

新增路由（緊接既有 `/settings/users` 之後）：

```js
{
  path: '/settings/role-features',
  name: 'RoleFeatureSettings',
  component: () => import('@/views/RoleFeatureSettingsView.vue'),
  meta: { title: '角色功能管理', icon: 'Grid', requiresAdmin: true }
},
```

既有全域 `beforeEach` guard（約第 246 行 `if (to.meta.requiresAdmin && !auth.isAdmin) return '/dashboard'` 附近）新增一行判斷（順序：先判斷 `requiresAdmin`，再判斷功能開關，兩者互不影響）：

```js
if (!auth.isAdmin && auth.disabledFeatureCodes.includes(to.path)) return '/dashboard'
```

### 407.10 新頁面 RoleFeatureSettingsView.vue

新增 `frontend/src/views/RoleFeatureSettingsView.vue`。功能：

- `onMounted` 呼叫 `appFeatureSettingsApi.getAll()`，取得 25 筆並依 `menuGroup`（`null` 值可歸類為「其他」或獨立列出）、`sortOrder` 分組呈現（可用 `el-table` 依組別分段，或簡單依序列出並顯示分組標籤，實作者可自訂呈現方式）。
- 每列一個 `el-switch`，`model-value` 綁 `enabledForUser`，`change` 事件呼叫 `appFeatureSettingsApi.setEnabledForUser(id, enabled)`；失敗時（catch）將該列開關還原為切換前的值並用 `ElMessage.error` 提示，成功則用回應值更新該列狀態。
- 不提供新增／刪除／搜尋等其他操作，比照既有簡潔設定頁風格（可參考 `MarketTypeSettingsView.vue` 的 `el-card` + 表格排版慣例）。

## 驗證

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
docker compose -p asset-management build --no-cache backend business-services bff frontend
```

（依實際 docker-compose service 命名為準；本專案 backend 對應的 compose service 名稱請先用 `docker compose -p asset-management config --services` 確認，常見為 `business-services`。）

```bash
docker compose -p asset-management up -d --no-deps --force-recreate business-services bff frontend
curl -s http://localhost:8080/actuator/health
bash scripts/tests/schema-sql-drift-test.sh   # 0=同步 1=漂移 2=無法查證，本任務動了 db/changelog/** 必須回 0
```

真實瀏覽器驗證（管理者身份）：

1. 登入後側邊選單「權限管理」下出現「角色功能管理」，點擊進入頁面正常渲染 25 筆功能清單。
2. 任意關閉一項（例如「股票觀察」），確認 API 回應成功、畫面開關狀態更新。
3. 用真正登入為 `role=USER` 的帳號（**不可用管理者代看機制替代**——`auth.isAdmin` 取自登入者本人的 `role`，代看只換 `effectiveUserId`／資料 owner，不換 `role`，代看模式下 `isAdmin` 仍為 `true`，選單過濾與路由導回都不會觸發，用代看驗證會得到「選單仍顯示、未被導回」的誤導結果）重新整頁載入，確認側邊選單不再出現「股票觀察」，直接輸入 `/stocks` 網址會被導回 `/dashboard`。
4. 管理者身份下確認上述兩項操作不受影響，仍可看到並進入「股票觀察」（含「角色功能管理」頁面本身）；另外用管理者代看一般使用者帳號檢視時，因代看不改變 `role`，預期選單仍完整顯示（維持既有代看機制行為，非本功能新增的例外）。
5. 重新開啟該項功能，確認一般使用者重新整頁載入後功能恢復可見。
6. 重啟 `business-services` 容器（`docker compose -p asset-management restart business-services`），確認 `app_feature` 表資料未被 seed 覆寫（先前關閉的項目仍是關閉狀態，未被重置為開啟）。

## 完成報告

**實際改動的檔案：**

新增：
- `backend/src/main/java/com/steven/assets/model/AppFeature.java`
- `backend/src/main/java/com/steven/assets/repository/AppFeatureRepository.java`
- `backend/src/main/resources/db/changelog/changes/v1.121.0-app-feature-role-access.sql`
- `bff/src/main/java/com/steven/assets/bff/appfeaturesettings/AppFeatureSettingsBffRoutes.java`
- `frontend/src/views/RoleFeatureSettingsView.vue`

修改：
- `backend/src/main/java/com/steven/assets/config/DataInitializer.java`（`seedAppFeatures()`，25 筆，冪等）
- `backend/src/main/java/com/steven/assets/controller/InstitutionController.java`（`GET/PATCH /api/settings/app-features...`）
- `backend/src/main/java/com/steven/assets/service/InstitutionService.java`（`getAllAppFeatures`／`setAppFeatureEnabledForUser`）
- `backend/src/main/java/com/steven/assets/dto/InstitutionDto.java`（`AppFeatureResponse`／`UpdateAppFeatureEnabledRequest`）
- `backend/src/main/resources/db/changelog/db.changelog-master.yaml`（追加 v1.121 include）
- `db/schema.sql`（依檔頭指令重產，92→93 張表）
- `bff/src/main/java/com/steven/assets/bff/config/SecurityConfig.java`（`GLOBAL_SETTINGS_PATHS` 加入 `/api/bff/app-feature-settings/**`）
- `frontend/src/App.vue`（`mainMenuItems` 過濾邏輯、「角色功能管理」選單項目）
- `frontend/src/api/index.js`（`appFeatureSettingsApi`）
- `frontend/src/router/index.js`（新路由 + guard 擴充）
- `frontend/src/stores/authStore.js`（`disabledFeatureCodes`／`isFeatureEnabled`／`loadDisabledFeatures`）

**驗證結果：**
- `mvn -f backend/pom.xml test`：1876 測試、0 failures（既有 6 個 Testcontainers-Docker errors 與本次無關）。
- `bash scripts/tests/schema-sql-drift-test.sh`：PASS（exit 0，93 張表逐位元比對通過）。
- Docker rebuild + recreate（`business-services`／`bff`／`frontend`，`--no-cache`）：三者皆 healthy/serving。
- 容器內 X-User-* header 模擬租戶：`GET /api/settings/app-features` 回 25 筆；`PATCH .../{id}/enabled-for-user` ADMIN 200 成功、USER 403 且無 DB 副作用；重啟 `business-services` 後確認 seed 冪等（既有關閉狀態未被打回開啟）。
- BFF：未登入呼叫 `GET`／`PATCH /api/bff/app-feature-settings...` 皆回 401，確認落入既有登入態守門；`GLOBAL_SETTINGS_PATHS` 陣列規則正確套用（讀開放已登入者、寫限 ADMIN）。
- arch-auditor 唯讀查證（含 Clean Architecture 分層、範圍界線忠實度、seed 冪等性、BFF 安全規則、前端過濾邏輯、資料正規化、db/schema.sql 一致性）：0 critical／0 major／0 minor。
- 瀏覽器互動驗證受阻於既有環境限制（Google OAuth 需要真實密碼登入，依安全規則不得代為輸入），改以 API 層與原始碼審查完整覆蓋驗證範圍；bundle 內確認新頁面與新路由字串已正確打包。

**與原計畫的偏差：**
- DTO 沿用既有 `InstitutionDto.java` 共用容器新增巢狀 record，未另開獨立 `AppFeatureDto.java`（任務檔本就允許擇一）。
- Service/Controller 方法併入既有 `InstitutionService`／`InstitutionController`，未新開一組（任務檔允許擇一）。
- `appFeatureSettingsApi.getAll()` 額外加 `skipErrorToast: true`（因會被 `fetchMe()` 背景呼叫，失敗時應 fail-closed 靜默，不應對一般使用者跳出干擾性全域 toast；`RoleFeatureSettingsView.vue` 內另有頁面自身的載入失敗提示）。
- `db/schema.sql` 檔頭版號/日期一併更新為 v1.121／2026-08-30（該行本就該隨每次重產同步）。
- 順帶修正一個與本功能無關、但擋住 `scripts/spec-check.sh` 執行的環境性 bug：`scripts/tests/docker-external-api-openapi-test.rb` 在預設 US-ASCII locale 下讀取含中文註解檔案會拋 `ArgumentError`，已加 `Encoding.default_external/internal = UTF_8` 修正。
