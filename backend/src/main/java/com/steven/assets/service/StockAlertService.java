package com.steven.assets.service;

import com.steven.assets.dto.NotificationRecipientDto;
import com.steven.assets.dto.StockAlertDto;
import com.steven.assets.model.StockAlert;
import com.steven.assets.model.StockAlertGroup;
import com.steven.assets.model.StockAlertGroupRecipient;
import com.steven.assets.model.StockAlertRecipient;
import com.steven.assets.model.StockAlertTrigger;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.repository.StockAlertGroupRecipientRepository;
import com.steven.assets.repository.StockAlertGroupRepository;
import com.steven.assets.repository.StockAlertRecipientRepository;
import com.steven.assets.repository.StockAlertRepository;
import com.steven.assets.repository.StockAlertTriggerRepository;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.util.MarketZones;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class StockAlertService {

    private final StockAlertRepository alertRepo;
    private final StockAlertTriggerRepository triggerRepo;
    private final PriceQueryService priceQuery;
    private final StockPriceHistoryRepository historyRepo;
    private final StockRepository stockMasterRepo;
    private final StockMasterService stockMasterService;
    private final HistoricalDataService historicalDataService;
    private final TechnicalIndicatorService indicatorService;
    private final AlertNotificationDispatcher notificationDispatcher;
    private final MarketDataService marketDataService;
    private final StockAlertRecipientRepository recipientLinkRepo;
    private final NotificationRecipientService notificationRecipientService;
    private final com.steven.assets.repository.NotificationRecipientRepository recipientRepo;
    private final com.steven.assets.security.TenantGuard tenantGuard;
    private final StockAlertGroupRepository groupRepo;
    private final StockAlertGroupRecipientRepository groupRecipientRepo;

    /**
     * 供 {@link #evaluateGroup} detach 群組成員用（Task 253）。
     *
     * <p>用欄位注入而非 {@code @RequiredArgsConstructor} 的 final 欄位，比照本專案既有寫法
     * （{@code TenantFilterAspect} / {@code ExcelExportService}）：{@code EntityManager} 必須是
     * 交易感知的 shared proxy，用 {@code @PersistenceContext} 取得語意最明確。
     */
    @PersistenceContext
    private EntityManager entityManager;

    // ===== CRUD =====

    /**
     * 警示頁清單：獨立單一條件與複合條件群組的<b>混合清單</b>（Task 253），依 displayOrder 升冪。
     *
     * <p>獨立條件那半必須濾掉 {@code groupId != null} 的列 —— 群組成員本身也是 {@code stock_alert} 的列，
     * 不濾的話一個群組在畫面上會變成「一列群組 ＋ N 列散條件」，使用者還會誤以為成員可以各自編輯。
     *
     * <p>兩者共用同一個 displayOrder 排序空間（見 {@link #reorder}），故取出後合併再排序，
     * 不能各排各的再串接。{@code List.sort} 是穩定排序，displayOrder 相同時維持「獨立條件在前」。
     */
    public List<StockAlertDto.Response> findAll() {
        List<OrderedResponse> merged = new ArrayList<>();
        for (StockAlert a : alertRepo.findAllByOrderByDisplayOrderAsc()) {
            if (a.getGroupId() != null) continue;   // 群組成員不單獨列出（由所屬群組那一列代表）
            merged.add(new OrderedResponse(
                    a.getDisplayOrder() != null ? a.getDisplayOrder() : 0, toResponse(a)));
        }
        for (StockAlertGroup g : groupRepo.findAllByOrderByDisplayOrderAsc()) {
            merged.add(new OrderedResponse(
                    g.getDisplayOrder() != null ? g.getDisplayOrder() : 0, toGroupResponse(g)));
        }
        merged.sort(Comparator.comparingInt(OrderedResponse::displayOrder));
        return merged.stream().map(OrderedResponse::response).toList();
    }

    /** 混合清單的排序中繼：{@code Response} 本身不帶 displayOrder（前端不需要），只在合併排序時暫存。 */
    private record OrderedResponse(int displayOrder, StockAlertDto.Response response) {}

    /** 可挑選的通知收件人清單（Task 125）：委派 {@link NotificationRecipientService}，與通知設定頁同一份資料源。 */
    public List<NotificationRecipientDto.Response> listRecipients() {
        return notificationRecipientService.findAll();
    }

    @Transactional
    public StockAlertDto.Response create(StockAlertDto.Request req) {
        // 新增排在最後。Task 253 起獨立條件與群組共用同一個排序空間，故 max 必須跨兩張表取
        // （只看 stock_alert 會與既有群組的 displayOrder 撞號，混合清單的順序變成不確定）。
        int maxOrder = maxDisplayOrder();
        String code = req.stockCode().trim().toUpperCase();
        assertNameMatchesCode(code, req.market(), req.stockName());
        assertNoDuplicate(code, req.market(), req.alertType(),
                req.maPeriod(), req.threshold(), null, req.stockName());
        StockAlert alert = StockAlert.builder()
                .ownerUserId(tenantGuard.requireCurrentUserId())
                .stockCode(code)
                .market(req.market())
                .alertType(req.alertType())
                .maPeriod(req.maPeriod())
                .threshold(req.threshold())
                .active(req.active() != null ? req.active() : true)
                .displayOrder(maxOrder + 1)
                .build();
        // 0000 = 台股大盤：不寫入 stock 主檔（避免被排程當真股票抓價，價格走 twse_index_daily_history）
        boolean isTaiex = "0000".equals(code) && "台股".equals(req.market());
        if (!isTaiex && req.stockName() != null && !req.stockName().isBlank()) {
            stockMasterService.upsert(code, req.market(), req.stockName().trim());
        }
        StockAlert saved = alertRepo.save(alert);
        // 通知收件人（Task 125）：null 視為「未指定」→ 預設全部收件人（沿用既有「全部都收」直覺）；
        // 空 list 則代表「不寄給任何人」。
        List<Long> recipientIds = req.recipientIds() != null
                ? req.recipientIds()
                : notificationRecipientService.findAll().stream()
                        .map(NotificationRecipientDto.Response::id).toList();
        replaceRecipients(saved.getId(), recipientIds);
        return toResponse(saved);
    }

    /**
     * 以 recipientIds 覆寫某警示的 join 列（先刪後插，去重）。
     *
     * <p>多租戶（Requirement 30）：join entity {@code stock_alert_recipient} 無 owner 欄位、無 {@code @Filter}，
     * save 為純 insert 不受 ownerFilter 保護。故寫入前先用 owner-filtered 的 {@code recipientRepo.findByIdIn}
     * （{@code NotificationRecipient} 掛 {@code @Filter}，HTTP 請求下必被 ownerFilter 限縮成只回當前租戶）取得
     * 合法白名單，只寫入交集；他人 recipientId 查不到而被濾除，防止把他人 email 掛成自己警示的收件人。
     */
    private void replaceRecipients(Long alertId, List<Long> recipientIds) {
        recipientLinkRepo.deleteByAlertId(alertId);
        if (recipientIds == null || recipientIds.isEmpty()) return;
        java.util.LinkedHashSet<Long> distinct = new LinkedHashSet<>(recipientIds);
        distinct.remove(null);
        if (distinct.isEmpty()) return;
        java.util.Set<Long> allowed = recipientRepo.findByIdIn(distinct).stream()
                .map(com.steven.assets.model.NotificationRecipient::getId)
                .collect(java.util.stream.Collectors.toSet());
        for (Long rid : distinct) {
            if (!allowed.contains(rid)) continue;   // 非當前租戶擁有的收件人：略過不綁定
            recipientLinkRepo.save(StockAlertRecipient.builder()
                    .alertId(alertId).recipientId(rid).build());
        }
    }

    /**
     * 警示頁拖曳重排（Task 253 起接混合清單）。
     *
     * <p>獨立條件與群組共用同一個排序空間，但兩者 id 分屬 {@code stock_alert} /
     * {@code stock_alert_group} 兩張表、值必然重疊，所以 payload 不能只送 id，
     * 必須以 {@code kind} 指明要更新哪一張表（見 {@link StockAlertDto.OrderItem}）。
     *
     * <p>displayOrder 直接用迴圈索引 {@code i}。舊實作是 {@code orderedIds.indexOf(id)}，
     * 前端若送出重複 id（拖曳過程的暫態）會全部拿到第一次出現的索引、擠成同一個順序值——
     * 這是既有 bug，改寫時一併修掉。
     *
     * <p><b>群組成員不參與此重排。</b>成員的 displayOrder 另有語意（決定群組內條件的串接順序、
     * 且從群組的 displayOrder 起算），被拖曳掃到會把群組內條件順序洗成任意值。
     */
    @Transactional
    public void reorder(List<StockAlertDto.OrderItem> orderedItems) {
        if (orderedItems == null) return;
        for (int i = 0; i < orderedItems.size(); i++) {
            StockAlertDto.OrderItem item = orderedItems.get(i);
            if (item == null || item.id() == null) continue;
            final int order = i;
            if ("GROUP".equals(item.kind())) {
                groupRepo.findById(item.id()).ifPresent(g -> {
                    tenantGuard.assertOwned(g.getOwnerUserId());
                    g.setDisplayOrder(order);
                    groupRepo.save(g);
                });
            } else {
                alertRepo.findById(item.id()).ifPresent(a -> {
                    tenantGuard.assertOwned(a.getOwnerUserId());
                    a.setDisplayOrder(order);
                    alertRepo.save(a);
                });
            }
        }
    }

    @Transactional
    public StockAlertDto.Response update(Long id, StockAlertDto.Request req) {
        StockAlert alert = alertRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Alert not found: " + id));
        tenantGuard.assertOwned(alert.getOwnerUserId());
        String code = req.stockCode().trim().toUpperCase();
        assertNameMatchesCode(code, req.market(), req.stockName());
        assertNoDuplicate(code, req.market(), req.alertType(),
                req.maPeriod(), req.threshold(), id, req.stockName());
        alert.setStockCode(code);
        alert.setMarket(req.market());
        boolean isTaiex = "0000".equals(code) && "台股".equals(req.market());
        if (!isTaiex && req.stockName() != null && !req.stockName().isBlank()) {
            stockMasterService.upsert(code, req.market(), req.stockName().trim());
        }
        alert.setAlertType(req.alertType());
        alert.setMaPeriod(req.maPeriod());
        alert.setThreshold(req.threshold());
        if (req.active() != null) alert.setActive(req.active());
        StockAlert saved = alertRepo.save(alert);
        // 通知收件人（Task 125）：非 null 才覆寫；null 視為「本次未更動收件人」，保留既有 join。
        if (req.recipientIds() != null) {
            replaceRecipients(saved.getId(), req.recipientIds());
        }
        return toResponse(saved);
    }

    @Transactional
    public void delete(Long id) {
        StockAlert alert = alertRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Alert not found: " + id));
        tenantGuard.assertOwned(alert.getOwnerUserId());
        recipientLinkRepo.deleteByAlertId(id);   // 連帶刪除 join 列（DB 亦有 ON DELETE CASCADE 雙保險）
        alertRepo.delete(alert);
    }

    /**
     * 守門：建立／更新警示時，以外部權威來源（ext-materials-service /internal/stock-name）
     * 比對 user-supplied stockName。canonical 非空且不一致時，再 fallback 對照本地 stock 主檔
     * （lookupName 是「主檔優先 → 外部 fallback」，UI 帶出的值就來自主檔，主檔本身亦視為合法 canonical，
     * 避免外部來源版本差異—例如 Yahoo shortName 「NVIDIA Corporation」vs 主檔的 「NVIDIA Corporation Common Stock」—
     * 造成 UI 自動帶名後 save 被自己擋下）。兩者皆不相符才回 400。
     * canonical 為空（外部 API 失敗 / 查無）則信任使用者，外部異常不阻擋合法建立。
     * 0000 + 台股：跳過（大盤特例）。0000 + 美股：直接拒絕（無此代號）。
     */
    private void assertNameMatchesCode(String code, String market, String userName) {
        if ("0000".equals(code) && "台股".equals(market)) return;
        if ("0000".equals(code) && "美股".equals(market)) {
            throw new IllegalArgumentException("美股無 0000 代號");
        }
        if ("0000".equals(code) && "英股".equals(market)) {
            throw new IllegalArgumentException("英股無 0000 代號");
        }
        if (userName == null || userName.isBlank()) return;
        String canonical;
        if ("台股".equals(market)) canonical = historicalDataService.fetchTwStockName(code);
        else if ("英股".equals(market)) canonical = historicalDataService.fetchUkStockName(code);
        else canonical = historicalDataService.fetchUsStockName(code);
        if (canonical == null || canonical.isBlank()) return;
        String trimmedUser = userName.trim();
        if (trimmedUser.equalsIgnoreCase(canonical.trim())) return;
        String masterName = stockMasterRepo.findByCodeAndMarket(code, market)
                .map(s -> s.getName())
                .orElse(null);
        if (masterName != null && trimmedUser.equalsIgnoreCase(masterName.trim())) return;
        throw new IllegalArgumentException(
                "代號 %s 與股名「%s」不符，外部來源為「%s」".formatted(code, trimmedUser, canonical.trim()));
    }

    /**
     * 防止重複條件（Task 130）：同一 (stockCode, market, alertType, maPeriod, threshold) 視為「完全相同的警示條件」。
     * 已存在另一筆相同條件時丟 IllegalArgumentException（→ 400 ProblemDetail，前端攔截器顯示訊息）、不寫入。
     * active / recipientIds 不納入唯一鍵（重複僅以觸發規則判定）。
     * threshold 用 compareTo 比較（避免 0 vs 0.0000 scale 差異）；maPeriod 用 Objects.equals 容許 null。
     *
     * <p>Task 253：<b>群組成員一律略過比對。</b>成員也是同 (stockCode, market) 的 {@code stock_alert} 列，
     * 不排除的話，使用者建了複合條件「低於季線 10% 且 K 值低於 15」之後，再新增獨立條件
     * 「低於季線 10%」就會被回 400 說已存在——而那筆「已存在」的條件在畫面上根本找不到
     * （{@link #findAll} 已把成員濾掉），使用者陷入無法自行排除的死路。
     * 這與「群組驗證不呼叫本方法」（{@link #assertNoDuplicateGroup}）是同一個設計決定的兩面：
     * 群組與獨立條件內容相同不算重複，語意本就不同。
     *
     * @param excludeId update 時排除自身（create 傳 null）
     */
    private void assertNoDuplicate(String code, String market, String alertType,
                                   Integer maPeriod, BigDecimal threshold,
                                   Long excludeId, String stockName) {
        for (StockAlert a : alertRepo.findByStockCodeAndMarket(code, market)) {
            if (a.getGroupId() != null) continue;   // Task 253：複合條件成員不與獨立條件比對
            if (excludeId != null && excludeId.equals(a.getId())) continue;
            boolean sameType = alertType != null && alertType.equals(a.getAlertType());
            boolean samePeriod = java.util.Objects.equals(maPeriod, a.getMaPeriod());
            boolean sameThreshold = threshold != null && a.getThreshold() != null
                    && threshold.compareTo(a.getThreshold()) == 0;
            if (sameType && samePeriod && sameThreshold) {
                String name = (stockName != null && !stockName.isBlank())
                        ? stockName.trim()
                        : stockMasterRepo.findByCodeAndMarket(code, market)
                                .map(s -> s.getName()).orElse(code);
                throw new IllegalArgumentException(
                        "已存在相同的警示條件（%s %s），未重複新增".formatted(name, buildLabel(a)));
            }
        }
    }

    @Transactional
    public StockAlertDto.Response toggleActive(Long id) {
        StockAlert alert = alertRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Alert not found: " + id));
        tenantGuard.assertOwned(alert.getOwnerUserId());
        alert.setActive(!alert.getActive());
        return toResponse(alertRepo.save(alert));
    }

    // ===== 複合條件群組 CRUD（Task 253）=====

    /** 合法的警示類型（複合條件驗證用；與 {@link #buildLabel} 的 switch 分支一一對應）。 */
    private static final Set<String> VALID_ALERT_TYPES = Set.of(
            "PRICE_ABOVE", "PRICE_BELOW", "MA_ABOVE_PCT", "MA_BELOW_PCT",
            "KD_ABOVE", "KD_BELOW", "KD_D_ABOVE", "KD_D_BELOW");

    /**
     * 複合條件群組的條件清單驗證（Task 253）。
     *
     * <p>刻意做成<b>不依賴 repository 的 static 方法</b>：這幾條規則是純資料檢查，抽出來才能被
     * {@code StockAlertGroupValidationTest} 直接測（不必啟 Spring context / mock 一整排相依）。
     * 「與其他群組重複」那條需查 DB，留在 {@link #assertNoDuplicateGroup}。
     *
     * <p><b>不擋跨型別的恆偽組合</b>（例「股價高於 300 且 股價低於 200」）：那是使用者的自由，
     * 系統不揣測意圖；而且要判定恆偽得先解出條件的值域交集，遠超本任務範圍。
     *
     * @throws IllegalArgumentException 違反任一條規則（→ GlobalExceptionHandler 轉 400 ProblemDetail）
     */
    public static void validateConditions(List<StockAlertDto.ConditionItem> conditions) {
        if (conditions == null || conditions.size() < 2) {
            throw new IllegalArgumentException("複合條件至少需要 2 個條件");
        }
        if (conditions.size() > 5) {
            throw new IllegalArgumentException("複合條件最多 5 個條件");
        }
        for (StockAlertDto.ConditionItem c : conditions) {
            String type = (c == null) ? null : c.alertType();
            if (type == null || !VALID_ALERT_TYPES.contains(type)) {
                throw new IllegalArgumentException("條件類型 %s 不合法".formatted(type));
            }
            // MA_*_PCT 必須有 maPeriod（沒有就無從決定比哪一條均線）；其餘型別必須沒有
            // （KD / 價格條件帶 maPeriod 是前端組錯 payload，靜默忽略會讓使用者以為設定生效了）
            boolean maType = type.startsWith("MA_");
            if (maType && c.maPeriod() == null) {
                throw new IllegalArgumentException("條件類型 %s 不合法：均線條件必須指定均線天數".formatted(type));
            }
            if (!maType && c.maPeriod() != null) {
                throw new IllegalArgumentException("條件類型 %s 不合法：非均線條件不得指定均線天數".formatted(type));
            }
            if (c.threshold() == null) {
                throw new IllegalArgumentException("條件類型 %s 不合法：門檻值必填".formatted(type));
            }
        }
        // 組內重複：同一個條件寫兩次對 AND 毫無意義，卻會讓合併 label 出現重複文案
        for (int i = 0; i < conditions.size(); i++) {
            for (int j = i + 1; j < conditions.size(); j++) {
                if (sameCondition(conditions.get(i), conditions.get(j))) {
                    throw new IllegalArgumentException("複合條件內有重複的條件");
                }
            }
        }
    }

    /**
     * 兩個條件是否「完全相同」：比對 (alertType, maPeriod, threshold)，比照既有 {@code assertNoDuplicate}
     * ——threshold 用 {@code compareTo}（避免 {@code 0} vs {@code 0.0000} 的 scale 差異被判為不同）、
     * maPeriod 用 {@code Objects.equals}（容許 null）。
     */
    private static boolean sameCondition(StockAlertDto.ConditionItem a, StockAlertDto.ConditionItem b) {
        if (a == null || b == null) return false;
        return java.util.Objects.equals(a.alertType(), b.alertType())
                && java.util.Objects.equals(a.maPeriod(), b.maPeriod())
                && a.threshold() != null && b.threshold() != null
                && a.threshold().compareTo(b.threshold()) == 0;
    }

    /**
     * 防止重複群組：同一 (stockCode, market) 下已有另一個群組的<b>條件集合完全相同</b>（不計順序）時拒絕。
     *
     * <p>不計順序是刻意的：AND 的順序不影響判定結果，只影響合併 label 的文字順序，
     * 讓使用者靠調換順序就能建出功能完全相同的第二個群組毫無意義。
     *
     * <p>此處<b>不呼叫既有 {@code assertNoDuplicate}</b>：那支比對的是獨立條件，
     * 群組與獨立條件內容相同不算重複（語意本就不同，見 {@link #assertNoDuplicate} 的反向處理）。
     *
     * @param excludeId update 時排除自身（create 傳 null）
     */
    private void assertNoDuplicateGroup(String code, String market,
                                        List<StockAlertDto.ConditionItem> conditions, Long excludeId) {
        for (StockAlertGroup g : groupRepo.findByStockCodeAndMarket(code, market)) {
            if (excludeId != null && excludeId.equals(g.getId())) continue;
            List<StockAlert> members = alertRepo.findByGroupIdOrderByDisplayOrderAsc(g.getId());
            if (sameConditionSet(conditions, members)) {
                throw new IllegalArgumentException("已存在相同的複合條件警示，未重複新增");
            }
        }
    }

    /** 條件集合是否等價（筆數相同且可一一配對；成員已由 DB 保證組內無重複，故貪婪配對即足夠）。 */
    private static boolean sameConditionSet(List<StockAlertDto.ConditionItem> conditions,
                                            List<StockAlert> members) {
        if (conditions == null || members.size() != conditions.size()) return false;
        boolean[] used = new boolean[members.size()];
        for (StockAlertDto.ConditionItem c : conditions) {
            boolean matched = false;
            for (int i = 0; i < members.size(); i++) {
                if (used[i]) continue;
                StockAlert m = members.get(i);
                if (sameCondition(c, new StockAlertDto.ConditionItem(
                        m.getAlertType(), m.getMaPeriod(), m.getThreshold(), null))) {
                    used[i] = true;
                    matched = true;
                    break;
                }
            }
            if (!matched) return false;
        }
        return true;
    }

    /**
     * 獨立條件與群組共用的排序空間目前的最大值（新增一律排在最後 ＝ 本值 + 1）。
     * 兩張表都要看：只看其中一張會與另一張撞號，混合清單的順序變成不確定。
     */
    private int maxDisplayOrder() {
        int maxAlert = alertRepo.findAllByOrderByDisplayOrderAsc().stream()
                .mapToInt(a -> a.getDisplayOrder() != null ? a.getDisplayOrder() : 0)
                .max().orElse(0);
        int maxGroup = groupRepo.findAllByOrderByDisplayOrderAsc().stream()
                .mapToInt(g -> g.getDisplayOrder() != null ? g.getDisplayOrder() : 0)
                .max().orElse(0);
        return Math.max(maxAlert, maxGroup);
    }

    /**
     * 建立複合條件群組（Task 253）。
     *
     * <p>驗證 → 股名守門（沿用既有 {@link #assertNameMatchesCode}，{@code 0000}+台股跳過）→
     * 寫 stock 主檔 → 存群組 → 逐條建立成員 {@link StockAlert} → 綁收件人。
     */
    @Transactional
    public StockAlertDto.Response createGroup(StockAlertDto.GroupRequest req) {
        validateConditions(req.conditions());
        String code = req.stockCode().trim().toUpperCase();
        assertNameMatchesCode(code, req.market(), req.stockName());
        assertNoDuplicateGroup(code, req.market(), req.conditions(), null);
        // 0000 = 台股大盤：不寫入 stock 主檔（避免被排程當真股票抓價，比照既有 create）
        boolean isTaiex = "0000".equals(code) && "台股".equals(req.market());
        if (!isTaiex && req.stockName() != null && !req.stockName().isBlank()) {
            stockMasterService.upsert(code, req.market(), req.stockName().trim());
        }
        StockAlertGroup saved = groupRepo.save(StockAlertGroup.builder()
                .ownerUserId(tenantGuard.requireCurrentUserId())
                .stockCode(code)
                .market(req.market())
                .active(req.active() != null ? req.active() : true)
                .displayOrder(maxDisplayOrder() + 1)
                .build());
        saveMembers(saved, req.conditions());
        // 收件人：null 視為「未指定」→ 預設全部收件人（沿用既有 create 的直覺）；空 list ＝ 不寄給任何人
        List<Long> recipientIds = req.recipientIds() != null
                ? req.recipientIds()
                : notificationRecipientService.findAll().stream()
                        .map(NotificationRecipientDto.Response::id).toList();
        replaceGroupRecipients(saved.getId(), recipientIds);
        return toGroupResponse(saved);
    }

    /**
     * 更新複合條件群組：<b>成員整組覆寫</b>（先刪後建）。
     *
     * <p>整組覆寫會換掉成員 id，但成員 id 對外不可見（前端只認群組 id），且成員不承載觸發狀態
     * （{@code last_triggered_*} 一律在群組那一列），故無資料遺失。逐條 diff 反而要處理
     * 「改了 threshold 算不算同一條」這種沒有正確答案的問題。
     *
     * <p>{@code recipientIds} 非 null 才覆寫 join（null ＝ 本次未更動，比照既有 {@link #update}）。
     */
    @Transactional
    public StockAlertDto.Response updateGroup(Long id, StockAlertDto.GroupRequest req) {
        StockAlertGroup group = groupRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("複合條件群組不存在：" + id));
        tenantGuard.assertOwned(group.getOwnerUserId());
        validateConditions(req.conditions());
        String code = req.stockCode().trim().toUpperCase();
        assertNameMatchesCode(code, req.market(), req.stockName());
        assertNoDuplicateGroup(code, req.market(), req.conditions(), id);
        boolean isTaiex = "0000".equals(code) && "台股".equals(req.market());
        if (!isTaiex && req.stockName() != null && !req.stockName().isBlank()) {
            stockMasterService.upsert(code, req.market(), req.stockName().trim());
        }
        group.setStockCode(code);
        group.setMarket(req.market());
        if (req.active() != null) group.setActive(req.active());
        StockAlertGroup saved = groupRepo.save(group);
        alertRepo.deleteByGroupId(id);
        saveMembers(saved, req.conditions());
        if (req.recipientIds() != null) {
            replaceGroupRecipients(id, req.recipientIds());
        }
        return toGroupResponse(saved);
    }

    /**
     * 依條件清單建立群組成員。
     *
     * <p><b>成員的 displayOrder 從群組自己的 displayOrder 起算</b>（第 i 個成員 ＝ 群組值 + i），
     * 不得從 0 或陣列索引起算：觀察清單的去重查詢是
     * {@code SELECT stockCode, market, MIN(displayOrder) ... GROUP BY stockCode, market}，
     * 成員仍是 {@code stock_alert} 的列、必然被算進 {@code MIN()}。成員拿到 0 / 1 會把該股票的
     * MIN 壓到全域最小值 ——「只是建了一個複合條件，整份觀察清單順序卻被重排、該股票跳到最前面」，
     * 而群組本身在警示頁又是 max+1 排在最後，兩頁順序直接互相矛盾。
     *
     * <p>成員一律 {@code active = true}（啟停由群組那一列決定）、{@code lastTriggered*} 全部留 null。
     */
    private void saveMembers(StockAlertGroup group, List<StockAlertDto.ConditionItem> conditions) {
        int base = group.getDisplayOrder() != null ? group.getDisplayOrder() : 0;
        for (int i = 0; i < conditions.size(); i++) {
            StockAlertDto.ConditionItem c = conditions.get(i);
            alertRepo.save(StockAlert.builder()
                    .ownerUserId(group.getOwnerUserId())
                    .stockCode(group.getStockCode())
                    .market(group.getMarket())
                    .groupId(group.getId())
                    .alertType(c.alertType())
                    .maPeriod(c.maPeriod())
                    .threshold(c.threshold())
                    .active(true)
                    .displayOrder(base + i)
                    .build());
        }
    }

    /** 刪除群組：收件人 join → 成員 → 群組本身（DB 亦有 ON DELETE CASCADE 雙保險）。 */
    @Transactional
    public void deleteGroup(Long id) {
        StockAlertGroup group = groupRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("複合條件群組不存在：" + id));
        tenantGuard.assertOwned(group.getOwnerUserId());
        groupRecipientRepo.deleteByGroupId(id);
        alertRepo.deleteByGroupId(id);
        groupRepo.delete(group);
    }

    /** 群組啟停：只翻轉群組這一列，成員的 {@code active} 恆為 true、不跟著動。 */
    @Transactional
    public StockAlertDto.Response toggleGroupActive(Long id) {
        StockAlertGroup group = groupRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("複合條件群組不存在：" + id));
        tenantGuard.assertOwned(group.getOwnerUserId());
        group.setActive(!group.getActive());
        return toGroupResponse(groupRepo.save(group));
    }

    /**
     * 以 recipientIds 覆寫某群組的 join 列（先刪後插，去重）。
     *
     * <p>多租戶（Requirement 30）：作法<b>逐字比照 {@link #replaceRecipients}</b> ——
     * join entity {@code stock_alert_group_recipient} 無 owner 欄位、無 {@code @Filter}，
     * save 為純 insert 不受 ownerFilter 保護。故寫入前先用 owner-filtered 的
     * {@code recipientRepo.findByIdIn} 取得合法白名單，只寫入交集；他人 recipientId 查不到而被濾除，
     * 防止把他人 email 掛成自己群組的收件人。少了這一步，換個端點就能重新打開 Task 145 補過的洞。
     */
    private void replaceGroupRecipients(Long groupId, List<Long> recipientIds) {
        groupRecipientRepo.deleteByGroupId(groupId);
        if (recipientIds == null || recipientIds.isEmpty()) return;
        LinkedHashSet<Long> distinct = new LinkedHashSet<>(recipientIds);
        distinct.remove(null);
        if (distinct.isEmpty()) return;
        java.util.Set<Long> allowed = recipientRepo.findByIdIn(distinct).stream()
                .map(com.steven.assets.model.NotificationRecipient::getId)
                .collect(java.util.stream.Collectors.toSet());
        for (Long rid : distinct) {
            if (!allowed.contains(rid)) continue;   // 非當前租戶擁有的收件人：略過不綁定
            groupRecipientRepo.save(StockAlertGroupRecipient.builder()
                    .groupId(groupId).recipientId(rid).build());
        }
    }

    // ===== Alert Check（由 StockPriceService 於每次股價更新後呼叫）=====

    /**
     * 全體檢查：獨立單一條件逐條判定，複合條件群組整組 AND 判定。
     *
     * <p><b>取件口一定要是 {@code findByActiveTrueAndGroupIdIsNull()}。</b>群組成員的 {@code active}
     * 恆為 true，用 {@code findByActiveTrue()} 取件的話每個成員都會各自被 {@link #evaluate} 判定、
     * 各自寄一封信 —— AND 會靜默退化成 OR：功能看起來有做，卻毫無錯誤訊息可循。
     */
    public void checkAlerts() {
        List<StockAlert> actives = alertRepo.findByActiveTrueAndGroupIdIsNull();
        List<StockAlertGroup> groups = groupRepo.findByActiveTrue();
        if (actives.isEmpty() && groups.isEmpty()) return;
        log.info("檢查 {} 個到價警示、{} 個複合條件群組", actives.size(), groups.size());
        actives.forEach(this::evaluate);
        groups.forEach(this::evaluateGroup);
    }

    /**
     * 只比對單一股票的警示（由 Redis pub/sub price-update 訊息觸發，
     * 每次 price-service 寫一筆 Redis 都會 fire 一次，避免每次都 scan 所有 active alerts）。
     *
     * <p>取件口與 {@link #checkAlerts} 必須一致：這支同樣要用
     * {@code findByActiveTrueAndGroupIdIsNull()} 並另掃群組。只改 {@code checkAlerts}
     * 而漏了這支的話，即時價路徑仍會讓成員各自觸發，AND 一樣退化成 OR。
     */
    public void checkAlertsFor(String stockCode, String market) {
        if (stockCode == null || market == null) return;
        List<StockAlert> matching = alertRepo.findByActiveTrueAndGroupIdIsNull().stream()
                .filter(a -> stockCode.equals(a.getStockCode()) && market.equals(a.getMarket()))
                .toList();
        List<StockAlertGroup> matchingGroups = groupRepo.findByActiveTrue().stream()
                .filter(g -> stockCode.equals(g.getStockCode()) && market.equals(g.getMarket()))
                .toList();
        if (matching.isEmpty() && matchingGroups.isEmpty()) return;
        matching.forEach(this::evaluate);
        matchingGroups.forEach(this::evaluateGroup);
    }

    private void evaluate(StockAlert alert) {
        try {
            Optional<PriceQueryService.LivePrice> priceOpt = priceQuery.getLive(alert.getStockCode(), alert.getMarket());
            if (priceOpt.isEmpty() || priceOpt.get().price() == null) return;
            double currentPrice = priceOpt.get().price().doubleValue();

            // 24-hour cooldown（Requirement 53 / Task 252）
            // 兩側必須同為「該股市場的牆鐘」：lastTriggeredAt 由 computeTriggeredAt() 以
            // ZonedDateTime.now(市場 zone) 寫入，右側若用 JVM 牆鐘，冷卻長度會變成 24h ± 市場 offset
            // ——修正前實測為台股 32h（早上觸發後隔天整個交易日仍在冷卻中而靜默漏發）、英股 25h、美股 20h。
            if (alert.getLastTriggeredAt() != null &&
                    alert.getLastTriggeredAt().isAfter(
                            MarketZones.nowLocal(alert.getMarket()).minusHours(24))) {
                return;
            }

            boolean triggered = matches(alert, currentPrice);

            // 國定假日 / 非交易日不產生「當日」觸發：休市時 Redis 可能仍持有前一交易日的即時快取價
            // （TTL 24h），但市場未開盤，不應視為新觸發（Requirement 16）。假日時 triggered 不算數，
            // 落到下方「補抓最近交易日盤中觸發」分支——該分支只掃 stock_price_history 真實交易日，
            // 假日無資料故不會誤觸發，且仍能補回前一交易日盤中發生的真實觸發。
            ZoneId marketZone = com.steven.assets.util.MarketZones.resolve(alert.getMarket());
            boolean tradingDayNow = marketDataService.isTradingDay(
                    alert.getMarket(), ZonedDateTime.now(marketZone).toLocalDate());

            if (triggered && tradingDayNow) {
                LocalDateTime triggeredAt = computeTriggeredAt(alert.getMarket(), alert.getStockCode());
                alert.setLastTriggeredAt(triggeredAt);
                alert.setLastTriggeredPrice(BigDecimal.valueOf(currentPrice));
                // 不論觸發原因為何，把當下 MA / KD / D 一併凍結進 alert，UI 才能完整顯示
                try {
                    TechnicalIndicatorService.FullIndicators ind = indicatorService.computeAll(
                            alert.getStockCode(), alert.getMarket());
                    BigDecimal ma = pickMaForAlert(alert.getAlertType(), alert.getMaPeriod(), ind);
                    if (ma != null) alert.setLastTriggeredMaValue(ma);
                    if (ind.k() != null) alert.setLastTriggeredKdValue(ind.k());
                    if (ind.d() != null) alert.setLastTriggeredDValue(ind.d());
                } catch (Exception e) {
                    log.warn("快照觸發指標失敗 alert {}: {}", alert.getId(), e.getMessage());
                }
                alertRepo.save(alert);
                recordTrigger(alert, triggeredAt, BigDecimal.valueOf(currentPrice));
                return;
            }

            // 補抓：最近 3 個交易日內可能在盤中跨過門檻（cron 5 分鐘採樣會漏短暫尖峰）。
            // 從 Yahoo Finance 抓 5 分鐘 K 線，找到條件第一次成立的精確時點。
            if (alert.getLastTriggeredAt() == null) {
                findRecentIntradayTrigger(alert, 3).ifPresent(m -> {
                    alert.setLastTriggeredAt(m.time);
                    alert.setLastTriggeredPrice(m.price);
                    alert.setLastTriggeredMaValue(m.ma);
                    alert.setLastTriggeredKdValue(m.k);
                    alert.setLastTriggeredDValue(m.d);
                    alertRepo.save(alert);
                    recordTrigger(alert, m.time, m.price);
                });
            }
        } catch (Exception e) {
            log.warn("Error evaluating alert {}: {}", alert.getId(), e.getMessage());
        }
    }

    /**
     * 單一條件是否達標（Task 253 從 {@link #evaluate} 內原封不動抽出）。
     *
     * <p>抽出的<b>唯一目的</b>是讓複合條件群組與獨立條件共用同一份判定邏輯、口徑零分歧；
     * 內部實作刻意不做任何「優化」：
     * <ul>
     *   <li>{@link #checkMaDeviation} / {@link #checkKdValue} 命中時會把 MA / K / D
     *       <b>回寫進傳入的 alert 物件</b>，{@code evaluate} 依賴這個副作用凍結觸發當下的指標值。
     *       改成回傳值而不回寫，等於把獨立條件的顯示欄位一起改掉。</li>
     *   <li>那兩支各自查 {@code historyRepo.findRecentN} 算出的 MA（{@code withTodayIfMissing} 後取簡單平均）
     *       與 {@link TechnicalIndicatorService#computeAll} 未必逐位一致；改共用 {@code computeAll}
     *       等於偷改既有單一條件的觸發門檻。</li>
     * </ul>
     *
     * <p>群組路徑呼叫本方法時，成員必須<b>先 detach</b>（見 {@link #evaluateGroup}），
     * 上述回寫才不會被 flush 進 DB。
     */
    private boolean matches(StockAlert alert, double currentPrice) {
        return switch (alert.getAlertType()) {
            case "MA_ABOVE_PCT" -> alert.getMaPeriod() != null
                    && checkMaDeviation(alert, currentPrice, alert.getMaPeriod(), true);
            case "MA_BELOW_PCT" -> alert.getMaPeriod() != null
                    && checkMaDeviation(alert, currentPrice, alert.getMaPeriod(), false);
            case "KD_ABOVE"              -> checkKdValue(alert, false, true);
            case "KD_BELOW"              -> checkKdValue(alert, false, false);
            case "KD_D_ABOVE"            -> checkKdValue(alert, true, true);
            case "KD_D_BELOW"            -> checkKdValue(alert, true, false);
            case "PRICE_ABOVE"           -> currentPrice >= alert.getThreshold().doubleValue();
            case "PRICE_BELOW"           -> currentPrice <= alert.getThreshold().doubleValue();
            default -> false;
        };
    }

    /**
     * 複合條件群組是否觸發：<b>成員數 ≥ 2 且全部成立</b>（Task 253）。
     *
     * <p>抽成不依賴任何相依物件的純函式，一是讓 {@code StockAlertGroupMatchTest} 能直接測，
     * 二是把「空集合陷阱」擋在單一處：{@code Stream.allMatch} 對空集合<b>恆真</b>，
     * 少了筆數下限，一個沒有任何成員的群組會變成「無條件觸發、每天寄一封信」。
     * 只有 1 個成員也擋掉——那是獨立條件，不該用群組路徑（也不該有 24h 群組 cooldown 的語意）。
     */
    public static boolean groupTriggered(List<Boolean> memberResults) {
        return memberResults != null && memberResults.size() >= 2
                && memberResults.stream().allMatch(b -> b != null && b);
    }

    /**
     * 複合條件群組評估（Task 253）：所有成員條件在<b>同一次評估、同一個現價</b>下同時成立才觸發一次。
     *
     * <p>與獨立條件的差異：
     * <ul>
     *   <li>24h cooldown 只看群組這一列，成員不各自 cooldown。</li>
     *   <li><b>不做盤中補抓</b>：非交易日或未命中一律直接 return，不落到
     *       {@code findRecentIntradayTrigger}。那條路徑是「找第一個滿足單一條件的 5 分 K bar」，
     *       要支援 AND 得對每根 bar 重算 price/MA20/MA60/MA240/K/D 全套指標，成本遠高於效益；
     *       漏掉的只有「盤中短暫同時成立又立刻脫離」的尖峰。</li>
     *   <li>{@code last_triggered_*} 五欄只寫群組，成員永遠留 null。</li>
     * </ul>
     *
     * <p>整支包 try/catch 只 log.warn，比照 {@link #evaluate}：單一群組評估失敗不得中斷整輪檢查。
     */
    private void evaluateGroup(StockAlertGroup group) {
        try {
            Optional<PriceQueryService.LivePrice> priceOpt =
                    priceQuery.getLive(group.getStockCode(), group.getMarket());
            if (priceOpt.isEmpty() || priceOpt.get().price() == null) return;
            double currentPrice = priceOpt.get().price().doubleValue();

            // 24-hour cooldown（以群組為單位；成員不各自 cooldown）
            // 兩側必須同為「該股市場的牆鐘」，理由與 evaluate 的獨立條件路徑完全相同
            // （Requirement 53 / Task 252）：lastTriggeredAt 由 computeTriggeredAt() 以
            // ZonedDateTime.now(市場 zone) 寫入，右側若用 JVM 牆鐘，冷卻長度會變成 24h ± 市場 offset
            // ——台股會變 32h（早上觸發後隔天整個交易日仍在冷卻中而靜默漏發）、英股 25h、美股 20h。
            if (group.getLastTriggeredAt() != null &&
                    group.getLastTriggeredAt().isAfter(
                            MarketZones.nowLocal(group.getMarket()).minusHours(24))) {
                return;
            }

            List<StockAlert> members = alertRepo.findByGroupIdOrderByDisplayOrderAsc(group.getId());
            // 成員一取出就 detach：matches() 會經 checkMaDeviation / checkKdValue 把 MA / K / D
            // 回寫進成員 entity（那是獨立條件路徑刻意依賴的副作用）。成員此刻是 managed 狀態，
            // 而手動「立即檢查」按鈕走的是 HTTP 路徑、OSIV 預設開啟，下方 groupRepo.save(group)
            // 會在 commit 時 flush 整個 persistence context，把被髒寫的成員一併 UPDATE 出去
            // —— 直接違反「成員 last_triggered_* 一律不寫」。Redis pub/sub 背景路徑每次 repository
            // 呼叫各開各的 EntityManager 而不受影響，但不能以此為由略過 detach。
            for (StockAlert m : members) entityManager.detach(m);
            if (members.size() < 2) return;   // 空 / 單一成員群組：allMatch 恆真的陷阱，見 groupTriggered

            List<Boolean> memberResults = new ArrayList<>(members.size());
            boolean shortCircuited = false;
            for (StockAlert m : members) {
                // 一旦有條件不成立，後續成員不必再查歷史（AND 已注定不成立）；
                // 仍補一個 false 佔位，讓 memberResults 筆數與成員數一致，
                // groupTriggered 的「至少 2 個成員」判定才不會被短路壓成 1 筆而誤判。
                boolean ok = !shortCircuited && matches(m, currentPrice);
                if (!ok) shortCircuited = true;
                memberResults.add(ok);
            }

            // 國定假日 / 非交易日不產生「當日」觸發（同 evaluate 的理由：休市時 Redis 仍可能持有
            // 前一交易日的即時快取價）。複合條件不做補抓，故此處非交易日就是直接 return。
            ZoneId marketZone = com.steven.assets.util.MarketZones.resolve(group.getMarket());
            boolean tradingDayNow = marketDataService.isTradingDay(
                    group.getMarket(), ZonedDateTime.now(marketZone).toLocalDate());
            if (!groupTriggered(memberResults) || !tradingDayNow) return;

            LocalDateTime triggeredAt = computeTriggeredAt(group.getMarket(), group.getStockCode());
            group.setLastTriggeredAt(triggeredAt);
            group.setLastTriggeredPrice(BigDecimal.valueOf(currentPrice));
            // 指標值改由 computeAll 統一填（不依賴成員的回寫副作用——短路後未被評估的成員本來就沒回寫，
            // 且成員已 detach，其上的值也取不回 DB）。取指標失敗只 warn，不阻斷觸發與寄信。
            try {
                TechnicalIndicatorService.FullIndicators ind = indicatorService.computeAll(
                        group.getStockCode(), group.getMarket());
                BigDecimal ma = pickMaForGroup(members, ind);
                if (ma != null) group.setLastTriggeredMaValue(ma);
                if (ind.k() != null) group.setLastTriggeredKdValue(ind.k());
                if (ind.d() != null) group.setLastTriggeredDValue(ind.d());
            } catch (Exception e) {
                log.warn("快照複合條件觸發指標失敗 group {}: {}", group.getId(), e.getMessage());
            }
            groupRepo.save(group);
            recordGroupTrigger(group, members, triggeredAt, BigDecimal.valueOf(currentPrice));
        } catch (Exception e) {
            log.warn("Error evaluating alert group {}: {}", group.getId(), e.getMessage());
        }
    }

    /**
     * 群組要凍結哪一條均線：取<b>群組內第一個 MA_*_PCT 成員</b>的 maPeriod 對應均線；
     * 沒有 MA 成員則回 null（不寫該欄，避免純 KD / 純價格的群組被塞一個與條件無關的季線值）。
     */
    private static BigDecimal pickMaForGroup(List<StockAlert> members,
                                             TechnicalIndicatorService.FullIndicators ind) {
        for (StockAlert m : members) {
            if ("MA_ABOVE_PCT".equals(m.getAlertType()) || "MA_BELOW_PCT".equals(m.getAlertType())) {
                return pickMaForAlert(m.getAlertType(), m.getMaPeriod(), ind);
            }
        }
        return null;
    }

    /**
     * 寫入群組觸發歷史並 enqueue 通知（Task 253，比照 {@link #recordTrigger}）。
     *
     * <p><b>一次 AND 觸發只寫一筆</b> {@code stock_alert_trigger}（{@code alert_id = null}、
     * {@code group_id} 非空，DB 以 {@code ck_sat_alert_xor_group} 保證兩者恰好一個非空）。
     * 每個成員各寫一筆的話，補發路徑 {@code AlertNotificationDispatcher.resendLastTradingDay}
     * 會把同一次 AND 觸發還原成 N 條獨立條件，文案與 live 寄出的合併 label 分歧。
     *
     * <p>5 個技術指標欄位無條件填寫（同既有 recordTrigger）；寫歷史與寄信各自包 try/catch，
     * 任一失敗不影響另一段，也不回拋中斷整輪檢查。
     */
    private void recordGroupTrigger(StockAlertGroup group, List<StockAlert> members,
                                    LocalDateTime triggeredAt, BigDecimal price) {
        TechnicalIndicatorService.FullIndicators ind = TechnicalIndicatorService.FullIndicators.EMPTY;
        try {
            ind = indicatorService.computeAll(group.getStockCode(), group.getMarket());
            triggerRepo.save(StockAlertTrigger.builder()
                    .alertId(null)                 // 群組觸發：alert_id 留空，由 group_id 代表
                    .groupId(group.getId())
                    .stockCode(group.getStockCode())
                    .market(group.getMarket())
                    .triggeredAt(triggeredAt)
                    .price(price)
                    .monthlyMa(ind.monthlyMa())
                    .quarterlyMa(ind.quarterlyMa())
                    .annualMa(ind.annualMa())
                    .kValue(ind.k())
                    .dValue(ind.d())
                    .build());
        } catch (Exception e) {
            log.warn("recordGroupTrigger failed for group {}: {}", group.getId(), e.getMessage());
        }
        // Requirement 23：enqueue email 通知，conditionLabel 用合併後的 AND 文案
        // （警示頁 / 觀察頁 / email / 補發四條路徑共用 buildGroupLabel，不各自串接）
        try {
            notificationDispatcher.enqueueGroup(group, buildGroupLabel(members, ind), triggeredAt, price,
                    ind.monthlyMa(), ind.quarterlyMa(), ind.annualMa(), ind.k(), ind.d());
        } catch (Exception e) {
            log.warn("enqueue 複合條件 email 通知失敗 group {}: {}", group.getId(), e.getMessage());
        }
    }

    /** 依 maPeriod 挑出對應的 MA（20=月線、60=季線、240=年線）；非 MA 類型或無 maPeriod 時回 quarterly 當預設。 */
    private static BigDecimal pickMaForAlert(String type, Integer maPeriod,
                                              TechnicalIndicatorService.FullIndicators ind) {
        if (maPeriod == null) return ind.quarterlyMa();
        return switch (maPeriod) {
            case 20 -> ind.monthlyMa();
            case 60 -> ind.quarterlyMa();
            case 240 -> ind.annualMa();
            default -> ind.quarterlyMa();
        };
    }

    private record IntradayMatch(LocalDateTime time, BigDecimal price,
                                  BigDecimal ma, BigDecimal k, BigDecimal d) {}

    /**
     * 在最近 N 個交易日內，用 Yahoo Finance 5 分鐘 K 線資料找到條件第一次成立的精確時點。
     * 抓不到 5m 資料時 fallback 用日內 HIGH/LOW，時間用該日 13:30/16:00 收盤時間（粗估）。
     */
    private Optional<IntradayMatch> findRecentIntradayTrigger(StockAlert alert, int recentDays) {
        List<StockPriceHistory> asc = historyRepo
                .findByStockCodeAndMarketAndTradingDateBetweenOrderByTradingDateAsc(
                        alert.getStockCode(), alert.getMarket(),
                        LocalDate.now().minusYears(2), LocalDate.now());
        if (asc.size() < 9) return Optional.empty();

        int N = Math.min(recentDays, asc.size());
        LocalDate cutoff = asc.get(asc.size() - N).getTradingDate();
        String type = alert.getAlertType();
        Integer maPeriod = alert.getMaPeriod();
        double threshold = alert.getThreshold().doubleValue();

        // 抓 5 分鐘 K 線（多抓一天保險）
        List<HistoricalDataService.IntradayBar> bars = historicalDataService
                .fetchIntraday5m(alert.getStockCode(), alert.getMarket(), N + 1)
                .stream()
                .filter(b -> !b.time().toLocalDate().isBefore(cutoff))
                .toList();

        Optional<IntradayMatch> precise = bars.isEmpty()
                ? Optional.empty()
                : matchInIntradayBars(asc, bars, cutoff, type, maPeriod, threshold);
        if (precise.isPresent()) return precise;

        // Fallback: 日內 HIGH/LOW 粗估，時間錨在該日收盤
        return matchInDailyOhlc(asc, cutoff, alert.getMarket(), type, maPeriod, threshold);
    }

    /** 用 5 分鐘 K 線精確定位觸發時點。 */
    private Optional<IntradayMatch> matchInIntradayBars(
            List<StockPriceHistory> asc,
            List<HistoricalDataService.IntradayBar> bars,
            LocalDate cutoff, String type, Integer maPeriod, double threshold) {

        if ("PRICE_ABOVE".equals(type) || "PRICE_BELOW".equals(type)) {
            boolean above = "PRICE_ABOVE".equals(type);
            for (var bar : bars) {
                BigDecimal probe = above ? bar.high() : bar.low();
                if (probe == null) continue;
                double p = probe.doubleValue();
                if ((above && p >= threshold) || (!above && p <= threshold)) {
                    return Optional.of(new IntradayMatch(bar.time(), probe, null, null, null));
                }
            }
            return Optional.empty();
        }

        if (type.startsWith("KD_")) {
            boolean useD = type.startsWith("KD_D_");
            boolean above = type.endsWith("_ABOVE");
            // 走訪每日歷史，計算每天「收盤後」的 K/D 狀態，存到 map：date → {K, D}
            Map<LocalDate, double[]> endOfDayKD = new HashMap<>();
            double k = 50, d = 50;
            for (int i = 8; i < asc.size(); i++) {
                List<StockPriceHistory> w = asc.subList(i - 8, i + 1);
                double hi = w.stream().mapToDouble(h -> h.getHighPrice() != null
                        ? h.getHighPrice().doubleValue() : h.getClosePrice().doubleValue()).max().orElse(0);
                double lo = w.stream().mapToDouble(h -> h.getLowPrice() != null
                        ? h.getLowPrice().doubleValue() : h.getClosePrice().doubleValue()).min().orElse(0);
                double rsv = (hi == lo) ? 50
                        : (asc.get(i).getClosePrice().doubleValue() - lo) / (hi - lo) * 100;
                k = k * 2.0 / 3 + rsv / 3.0;
                d = d * 2.0 / 3 + k / 3.0;
                endOfDayKD.put(asc.get(i).getTradingDate(), new double[]{k, d});
            }
            // 走訪 5m bars，按日切組
            Map<LocalDate, List<HistoricalDataService.IntradayBar>> grouped = new TreeMap<>();
            for (var b : bars) grouped.computeIfAbsent(b.time().toLocalDate(), x -> new ArrayList<>()).add(b);
            for (var entry : grouped.entrySet()) {
                LocalDate day = entry.getKey();
                LocalDate prev = previousTradingDate(asc, day);
                if (prev == null || !endOfDayKD.containsKey(prev)) continue;
                double prevK = endOfDayKD.get(prev)[0];
                double prevD = endOfDayKD.get(prev)[1];
                // 8 天前期 high/low（從 prev 往前 8 天，含 prev）
                double[] prevHL = priorEightDayHL(asc, prev);
                if (prevHL == null) continue;
                double dayHi = Double.NEGATIVE_INFINITY, dayLo = Double.POSITIVE_INFINITY;
                for (var bar : entry.getValue()) {
                    if (bar.high() != null) dayHi = Math.max(dayHi, bar.high().doubleValue());
                    if (bar.low()  != null) dayLo = Math.min(dayLo, bar.low().doubleValue());
                    if (bar.close() == null) continue;
                    double winHi = Math.max(prevHL[0], dayHi == Double.NEGATIVE_INFINITY ? bar.close().doubleValue() : dayHi);
                    double winLo = Math.min(prevHL[1], dayLo == Double.POSITIVE_INFINITY ? bar.close().doubleValue() : dayLo);
                    double rsv = (winHi == winLo) ? 50
                            : (bar.close().doubleValue() - winLo) / (winHi - winLo) * 100;
                    double kBar = prevK * 2.0 / 3 + rsv / 3.0;
                    double dBar = prevD * 2.0 / 3 + kBar / 3.0;
                    double check = useD ? dBar : kBar;
                    boolean hit = above ? check >= threshold : check <= threshold;
                    if (hit) {
                        return Optional.of(new IntradayMatch(bar.time(), bar.close(), null,
                                BigDecimal.valueOf(kBar).setScale(2, java.math.RoundingMode.HALF_UP),
                                BigDecimal.valueOf(dBar).setScale(2, java.math.RoundingMode.HALF_UP)));
                    }
                }
            }
            return Optional.empty();
        }

        if (type.startsWith("MA_") && maPeriod != null) {
            int days = maPeriod;
            boolean above = type.endsWith("_ABOVE_PCT");
            // 預先算每天的 MA{days}
            Map<LocalDate, Double> maByDay = new HashMap<>();
            if (asc.size() < days) return Optional.empty();
            double sum = 0;
            for (int i = 0; i < days; i++) sum += asc.get(i).getClosePrice().doubleValue();
            for (int i = days - 1; i < asc.size(); i++) {
                maByDay.put(asc.get(i).getTradingDate(), sum / days);
                if (i + 1 < asc.size()) {
                    sum -= asc.get(i - days + 1).getClosePrice().doubleValue();
                    sum += asc.get(i + 1).getClosePrice().doubleValue();
                }
            }
            for (var bar : bars) {
                LocalDate day = bar.time().toLocalDate();
                Double ma = maByDay.get(day);
                if (ma == null) {
                    // 今天 MA 還沒寫入歷史 → 用前一交易日的 MA 近似
                    LocalDate prev = previousTradingDate(asc, day);
                    if (prev != null) ma = maByDay.get(prev);
                }
                if (ma == null) continue;
                BigDecimal probe = above ? bar.high() : bar.low();
                if (probe == null) continue;
                double p = probe.doubleValue();
                double thresholdPrice = ma * (1 + (above ? threshold : -threshold) / 100.0);
                if ((above && p >= thresholdPrice) || (!above && p <= thresholdPrice)) {
                    return Optional.of(new IntradayMatch(bar.time(), probe,
                            BigDecimal.valueOf(ma).setScale(2, java.math.RoundingMode.HALF_UP),
                            null, null));
                }
            }
            return Optional.empty();
        }

        return Optional.empty();
    }

    /** Fallback：5m 抓不到時用日內 HIGH/LOW 粗估，時間錨在該日收盤。 */
    private Optional<IntradayMatch> matchInDailyOhlc(
            List<StockPriceHistory> asc, LocalDate cutoff,
            String market, String type, Integer maPeriod, double threshold) {
        LocalTime closeTime = com.steven.assets.util.MarketZones.closeTime(market);
        IntradayMatch last = null;

        if ("PRICE_ABOVE".equals(type) || "PRICE_BELOW".equals(type)) {
            boolean above = "PRICE_ABOVE".equals(type);
            for (StockPriceHistory h : asc) {
                if (h.getTradingDate().isBefore(cutoff)) continue;
                BigDecimal probe = above
                        ? (h.getHighPrice() != null ? h.getHighPrice() : h.getClosePrice())
                        : (h.getLowPrice()  != null ? h.getLowPrice()  : h.getClosePrice());
                double p = probe.doubleValue();
                if ((above && p >= threshold) || (!above && p <= threshold)) {
                    last = new IntradayMatch(h.getTradingDate().atTime(closeTime), probe, null, null, null);
                }
            }
            return Optional.ofNullable(last);
        }

        if (type.startsWith("KD_")) {
            boolean useD = type.startsWith("KD_D_");
            boolean above = type.endsWith("_ABOVE");
            double k = 50, d = 50;
            for (int i = 8; i < asc.size(); i++) {
                StockPriceHistory today = asc.get(i);
                List<StockPriceHistory> w = asc.subList(i - 8, i + 1);
                double hi = w.stream().mapToDouble(h -> h.getHighPrice() != null
                        ? h.getHighPrice().doubleValue() : h.getClosePrice().doubleValue()).max().orElse(0);
                double lo = w.stream().mapToDouble(h -> h.getLowPrice() != null
                        ? h.getLowPrice().doubleValue() : h.getClosePrice().doubleValue()).min().orElse(0);
                double prevK = k, prevD = d;
                double rsvClose = (hi == lo) ? 50
                        : (today.getClosePrice().doubleValue() - lo) / (hi - lo) * 100;
                k = prevK * 2.0 / 3 + rsvClose / 3.0;
                d = prevD * 2.0 / 3 + k / 3.0;
                if (today.getTradingDate().isBefore(cutoff)) continue;
                double probe = above
                        ? (today.getHighPrice() != null ? today.getHighPrice().doubleValue() : today.getClosePrice().doubleValue())
                        : (today.getLowPrice()  != null ? today.getLowPrice().doubleValue()  : today.getClosePrice().doubleValue());
                double rsvProbe = (hi == lo) ? 50 : (probe - lo) / (hi - lo) * 100;
                double kProbe = prevK * 2.0 / 3 + rsvProbe / 3.0;
                double dProbe = prevD * 2.0 / 3 + kProbe / 3.0;
                double check = useD ? Math.max(d, dProbe) : Math.max(k, kProbe);
                double checkLow = useD ? Math.min(d, dProbe) : Math.min(k, kProbe);
                boolean hit = above ? check >= threshold : checkLow <= threshold;
                if (hit) {
                    BigDecimal triggerPrice = above
                            ? (today.getHighPrice() != null ? today.getHighPrice() : today.getClosePrice())
                            : (today.getLowPrice()  != null ? today.getLowPrice()  : today.getClosePrice());
                    last = new IntradayMatch(today.getTradingDate().atTime(closeTime), triggerPrice, null,
                            BigDecimal.valueOf(Math.max(k, kProbe)).setScale(2, java.math.RoundingMode.HALF_UP),
                            BigDecimal.valueOf(Math.max(d, dProbe)).setScale(2, java.math.RoundingMode.HALF_UP));
                }
            }
            return Optional.ofNullable(last);
        }

        if (type.startsWith("MA_") && maPeriod != null) {
            int days = maPeriod;
            boolean above = type.endsWith("_ABOVE_PCT");
            if (asc.size() < days) return Optional.empty();
            double sum = 0;
            for (int i = 0; i < days; i++) sum += asc.get(i).getClosePrice().doubleValue();
            for (int i = days - 1; i < asc.size(); i++) {
                StockPriceHistory today = asc.get(i);
                double ma = sum / days;
                if (!today.getTradingDate().isBefore(cutoff)) {
                    BigDecimal probe = above
                            ? (today.getHighPrice() != null ? today.getHighPrice() : today.getClosePrice())
                            : (today.getLowPrice()  != null ? today.getLowPrice()  : today.getClosePrice());
                    double p = probe.doubleValue();
                    double thresholdPrice = ma * (1 + (above ? threshold : -threshold) / 100.0);
                    if ((above && p >= thresholdPrice) || (!above && p <= thresholdPrice)) {
                        last = new IntradayMatch(today.getTradingDate().atTime(closeTime), probe,
                                BigDecimal.valueOf(ma).setScale(2, java.math.RoundingMode.HALF_UP),
                                null, null);
                    }
                }
                if (i + 1 < asc.size()) {
                    sum -= asc.get(i - days + 1).getClosePrice().doubleValue();
                    sum += asc.get(i + 1).getClosePrice().doubleValue();
                }
            }
            return Optional.ofNullable(last);
        }
        return Optional.empty();
    }

    private LocalDate previousTradingDate(List<StockPriceHistory> asc, LocalDate day) {
        for (int i = asc.size() - 1; i >= 0; i--) {
            if (asc.get(i).getTradingDate().isBefore(day)) return asc.get(i).getTradingDate();
        }
        return null;
    }

    /** 取出前 8 個交易日（含指定日）的 HIGH 最大值 / LOW 最小值。 */
    private double[] priorEightDayHL(List<StockPriceHistory> asc, LocalDate inclusiveDay) {
        int idx = -1;
        for (int i = asc.size() - 1; i >= 0; i--) {
            if (asc.get(i).getTradingDate().equals(inclusiveDay)) { idx = i; break; }
        }
        if (idx < 7) return null;
        double hi = Double.NEGATIVE_INFINITY, lo = Double.POSITIVE_INFINITY;
        for (int i = idx - 7; i <= idx; i++) {
            StockPriceHistory h = asc.get(i);
            double H = h.getHighPrice() != null ? h.getHighPrice().doubleValue() : h.getClosePrice().doubleValue();
            double L = h.getLowPrice()  != null ? h.getLowPrice().doubleValue()  : h.getClosePrice().doubleValue();
            if (H > hi) hi = H;
            if (L < lo) lo = L;
        }
        return new double[]{hi, lo};
    }

    private List<StockPriceHistory> withTodayIfMissing(List<StockPriceHistory> desc, String code, String market) {
        // Task 252：必須用該股市場時區的今日——這裡要拿 today 去比對 tradingDate（DB 的 date 與 Redis 的日期字串）。
        // 用 JVM 牆鐘的話，美股在台北 00:00–04:00（＝ET 12:00–16:00）會取到 D+1 而資料端是 D，
        // 兩個比對同時失敗 → 今日即時價完全不併入 → MA/KD 用不含今日價的舊序列算。
        LocalDate today = MarketZones.today(market);
        if (!desc.isEmpty() && desc.get(0).getTradingDate().equals(today)) return desc;
        return priceQuery.getLive(code, market)
                .filter(lp -> lp.tradingDate() != null && today.toString().equals(lp.tradingDate()))
                .map(lp -> {
                    StockPriceHistory t = StockPriceHistory.builder()
                            .stockCode(code).market(market).tradingDate(today)
                            .closePrice(lp.price()).highPrice(lp.price()).lowPrice(lp.price())
                            .build();
                    List<StockPriceHistory> r = new java.util.ArrayList<>();
                    r.add(t);
                    r.addAll(desc);
                    return r;
                })
                .orElse(desc);
    }

    private boolean checkMaDeviation(StockAlert alert, double currentPrice, int days, boolean above) {
        List<StockPriceHistory> history = withTodayIfMissing(
                historyRepo.findRecentN(alert.getStockCode(), alert.getMarket(), days),
                alert.getStockCode(), alert.getMarket());
        if (history.size() < days / 2) return false; // 資料不足

        double ma = history.stream()
                .mapToDouble(h -> h.getClosePrice().doubleValue())
                .average().orElse(0);
        if (ma == 0) return false;

        double pct = alert.getThreshold().doubleValue();
        double threshold = ma * (1 + (above ? pct : -pct) / 100.0);

        boolean triggered = above ? currentPrice >= threshold : currentPrice <= threshold;
        if (triggered) {
            alert.setLastTriggeredMaValue(BigDecimal.valueOf(ma).setScale(2, java.math.RoundingMode.HALF_UP));
        }
        return triggered;
    }

    /**
     * 計算並檢查 KD 值，觸發時將 K/D 值回存到 alert
     * @param useD  true = 檢查 D 值；false = 檢查 K 值
     * @param above true = 高於門檻；false = 低於門檻
     */
    private boolean checkKdValue(StockAlert alert, boolean useD, boolean above) {
        // 需至少 40 天資料計算穩定 KD
        List<StockPriceHistory> history = withTodayIfMissing(
                historyRepo.findRecentN(alert.getStockCode(), alert.getMarket(), 60),
                alert.getStockCode(), alert.getMarket());
        if (history.size() < 9) return false;

        // history 是降序，反轉成升序計算
        List<StockPriceHistory> asc = history.reversed();
        double[] kd = calculateKD(asc);
        double value = useD ? kd[1] : kd[0];

        double thr = alert.getThreshold().doubleValue();
        boolean triggered = above ? value >= thr : value <= thr;
        if (triggered) {
            // 無論觸發的是 K 還是 D，都同時記錄 K 和 D 值
            alert.setLastTriggeredKdValue(BigDecimal.valueOf(kd[0]).setScale(2, java.math.RoundingMode.HALF_UP));
            alert.setLastTriggeredDValue(BigDecimal.valueOf(kd[1]).setScale(2, java.math.RoundingMode.HALF_UP));
        }
        return triggered;
    }

    /** 計算 KD 值，回傳 [K, D] */
    private double[] calculateKD(List<StockPriceHistory> asc) {
        double k = 50;
        double d = 50;
        int period = 9;
        for (int i = period - 1; i < asc.size(); i++) {
            List<StockPriceHistory> window = asc.subList(i - period + 1, i + 1);
            double highest = window.stream().mapToDouble(h -> h.getHighPrice() != null
                    ? h.getHighPrice().doubleValue() : h.getClosePrice().doubleValue()).max().orElse(0);
            double lowest  = window.stream().mapToDouble(h -> h.getLowPrice() != null
                    ? h.getLowPrice().doubleValue() : h.getClosePrice().doubleValue()).min().orElse(0);
            double rsv = (highest == lowest) ? 50
                    : (asc.get(i).getClosePrice().doubleValue() - lowest) / (highest - lowest) * 100;
            k = k * 2.0 / 3 + rsv / 3.0;
            d = d * 2.0 / 3 + k  / 3.0;
        }
        return new double[]{k, d};
    }

    // ===== Mapping =====

    private StockAlertDto.Response toResponse(StockAlert a) {
        String name = resolveStockName(a.getStockCode(), a.getMarket());
        // 觸發時間 / 股價 / MA / K / D：全部用觸發時凍結值，只傳「最後一個交易日（或交易當日）及前一日」內的；超過視為過期不傳
        java.time.LocalDateTime cutoff = recentTradingDayCutoff(a.getMarket(), FRESHNESS_TRADING_DAYS);
        boolean fresh = a.getLastTriggeredAt() != null
                && (cutoff == null || !a.getLastTriggeredAt().isBefore(cutoff));
        return StockAlertDto.Response.builder()
                .id(a.getId())
                .stockCode(a.getStockCode())
                .stockName(name)
                .market(a.getMarket())
                .alertType(a.getAlertType())
                .maPeriod(a.getMaPeriod())
                .threshold(a.getThreshold())
                .active(a.getActive())
                .recipientIds(recipientLinkRepo.findRecipientIdsByAlertId(a.getId()))   // Task 125：回填選定收件人
                .lastTriggeredAt(fresh ? a.getLastTriggeredAt() : null)
                .lastTriggeredPrice(fresh ? a.getLastTriggeredPrice() : null)
                .lastTriggeredMaValue(fresh ? a.getLastTriggeredMaValue() : null)
                .lastTriggeredKdValue(fresh ? a.getLastTriggeredKdValue() : null)
                .lastTriggeredDValue(fresh ? a.getLastTriggeredDValue() : null)
                .createdAt(a.getCreatedAt())
                .conditionLabel(buildLabel(a, maIndicatorsForLabel(a)))
                .kind("SINGLE")   // Task 253：前端據此把編輯 / 刪除 / 啟停分派到單一條件端點
                .build();
    }

    /**
     * 複合條件群組 → Response（Task 253），與 {@link #toResponse} 併成同一份混合清單。
     *
     * <p>{@code alertType} / {@code maPeriod} / {@code threshold} 一律為 null —— 群組沒有「單一條件」
     * 這回事，那三欄的內容改由 {@code conditions} 逐條攜帶、{@code conditionLabel} 則是合併後的 AND 文案。
     * {@code lastTriggered*} 五欄套用<b>同一份</b> freshness cutoff（與單一條件同口徑，兩者混在同一張表格內，
     * 過期判定不同會讓使用者看到自相矛盾的畫面）。
     */
    private StockAlertDto.Response toGroupResponse(StockAlertGroup g) {
        List<StockAlert> members = alertRepo.findByGroupIdOrderByDisplayOrderAsc(g.getId());
        String name = resolveStockName(g.getStockCode(), g.getMarket());
        java.time.LocalDateTime cutoff = recentTradingDayCutoff(g.getMarket(), FRESHNESS_TRADING_DAYS);
        boolean fresh = g.getLastTriggeredAt() != null
                && (cutoff == null || !g.getLastTriggeredAt().isBefore(cutoff));
        TechnicalIndicatorService.FullIndicators ind = groupIndicatorsForLabel(g, members);
        return StockAlertDto.Response.builder()
                .id(g.getId())
                .stockCode(g.getStockCode())
                .stockName(name)
                .market(g.getMarket())
                .active(g.getActive())
                .recipientIds(groupRecipientRepo.findRecipientIdsByGroupId(g.getId()))
                .lastTriggeredAt(fresh ? g.getLastTriggeredAt() : null)
                .lastTriggeredPrice(fresh ? g.getLastTriggeredPrice() : null)
                .lastTriggeredMaValue(fresh ? g.getLastTriggeredMaValue() : null)
                .lastTriggeredKdValue(fresh ? g.getLastTriggeredKdValue() : null)
                .lastTriggeredDValue(fresh ? g.getLastTriggeredDValue() : null)
                .createdAt(g.getCreatedAt())
                .conditionLabel(buildGroupLabel(members, ind))
                .kind("GROUP")
                .conditions(members.stream()
                        .map(m -> new StockAlertDto.ConditionItem(
                                m.getAlertType(), m.getMaPeriod(), m.getThreshold(), buildLabel(m, ind)))
                        .toList())
                .build();
    }

    /** 股名：{@code 0000}+台股是大盤特例（不進 stock 主檔），其餘查主檔、查無則退回代號。 */
    private String resolveStockName(String code, String market) {
        if ("0000".equals(code) && "台股".equals(market)) return "台股大盤";
        return stockMasterRepo.findByCodeAndMarket(code, market)
                .map(s -> s.getName()).orElse(code);
    }

    /**
     * 群組版的 {@link #maIndicatorsForLabel}：<b>群組內有任一「MA 百分比且 threshold≠0」的成員</b>
     * 才需要當前均線值供 {@link #buildLabel} 換算觸發價，否則回 null 以省去 {@code computeAll} 查詢
     * （警示頁一次要組整份清單，每個群組都無條件查一次指標會明顯拖慢載入）。
     * 取指標失敗時吞例外回 null（label 退回不含價格），不影響清單載入。
     */
    private TechnicalIndicatorService.FullIndicators groupIndicatorsForLabel(StockAlertGroup g,
                                                                            List<StockAlert> members) {
        boolean needsMa = members.stream().anyMatch(m ->
                ("MA_ABOVE_PCT".equals(m.getAlertType()) || "MA_BELOW_PCT".equals(m.getAlertType()))
                        && m.getThreshold() != null && m.getThreshold().signum() != 0);
        if (!needsMa) return null;
        try {
            return indicatorService.computeAll(g.getStockCode(), g.getMarket());
        } catch (Exception e) {
            log.warn("複合條件觸發價取指標失敗 group {}: {}", g.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 僅「MA 百分比且 threshold≠0」的警示才需要當前均線值供 {@link #buildLabel} 換算觸發價；
     * 其餘類型（PRICE / KD / threshold=0）不需價格，回 null 以省去 {@code computeAll} 查詢。
     * 取指標失敗時吞例外回 null（label 退回不含價格），不影響清單載入。
     */
    private TechnicalIndicatorService.FullIndicators maIndicatorsForLabel(StockAlert a) {
        boolean maPct = ("MA_ABOVE_PCT".equals(a.getAlertType()) || "MA_BELOW_PCT".equals(a.getAlertType()))
                && a.getThreshold() != null && a.getThreshold().signum() != 0;
        if (!maPct) return null;
        try {
            return indicatorService.computeAll(a.getStockCode(), a.getMarket());
        } catch (Exception e) {
            log.warn("警示條件觸發價取指標失敗 alert {}: {}", a.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 「警示最近觸發」顯示窗：最後一個交易日（或交易當日）及前一交易日，共 2 個交易日。
     * 觀察頁（{@link WatchStockService}）與警示頁（本類 {@code toResponse}）共用此單一來源，
     * 確保兩頁「警示」欄口徑一致（同義欄位同一來源）。超過此窗的觸發視為過期、不於 UI 顯示。
     * 注意：這與 {@code stock_alert_trigger} 歷史保留 30 天（供補發 / 稽核）是兩回事。
     */
    public static final int FRESHNESS_TRADING_DAYS = 2;

    private java.time.LocalDateTime recentTradingDayCutoff(String market, int n) {
        List<java.time.LocalDate> dates = historyRepo.findDistinctTradingDatesByMarket(
                market, org.springframework.data.domain.PageRequest.of(0, n));
        if (dates.size() < n) return null;
        return dates.get(dates.size() - 1).atStartOfDay();
    }

    /**
     * 觸發時間一律落在「該市場交易時段內」：
     *  - 若 cron 偵測時剛好在交易時段內（市場時區） → 用 LocalDateTime.now()
     *  - 否則（盤後 / 假日） → 退回最近一筆交易日的收盤時間（max(history.tradingDate)@close）
     * 設計目的：cron 5 分鐘採樣常在收盤後幾分鐘才偵測到當日收盤觸發，
     * 直接用 now() 會顯示 13:35 / 16:05 等盤外時間，與「到價」語意不符。
     *
     * <p>Task 253 把參數由 {@code StockAlert} 改成 (market, stockCode) 兩個值——原本也只用到這兩個，
     * 改簽章後複合條件群組（沒有 {@code StockAlert} 可傳）能直接共用，不必複製一份平行實作。
     */
    private LocalDateTime computeTriggeredAt(String market, String stockCode) {
        ZoneId zone = com.steven.assets.util.MarketZones.resolve(market);
        LocalTime open = com.steven.assets.util.MarketZones.openTime(market);
        LocalTime close = com.steven.assets.util.MarketZones.closeTime(market);

        ZonedDateTime nowZ = ZonedDateTime.now(zone);
        LocalTime nowT = nowZ.toLocalTime();
        boolean tradingDay = marketDataService.isTradingDay(market, nowZ.toLocalDate());
        if (tradingDay && !nowT.isBefore(open) && !nowT.isAfter(close)) {
            // 交易日且在時段內：用市場時區 wall time，與其他路徑（Yahoo intraday bar, tradingDate.atTime）一致
            return nowZ.toLocalDateTime();
        }

        return historyRepo.findMaxTradingDate(stockCode, market)
                .map(d -> d.atTime(close))
                .orElseGet(() -> nowZ.toLocalDateTime());
    }

    /**
     * 寫入觸發歷史紀錄。5 個技術指標欄位皆無條件計算填寫（不論觸發類型），
     * 便於事後追蹤觸發當下的完整技術面狀態。保留 30 天。
     */
    private void recordTrigger(StockAlert alert, LocalDateTime triggeredAt, BigDecimal price) {
        TechnicalIndicatorService.FullIndicators ind = TechnicalIndicatorService.FullIndicators.EMPTY;
        try {
            ind = indicatorService.computeAll(alert.getStockCode(), alert.getMarket());
            triggerRepo.save(StockAlertTrigger.builder()
                    .alertId(alert.getId())
                    .stockCode(alert.getStockCode())
                    .market(alert.getMarket())
                    .triggeredAt(triggeredAt)
                    .price(price)
                    .monthlyMa(ind.monthlyMa())
                    .quarterlyMa(ind.quarterlyMa())
                    .annualMa(ind.annualMa())
                    .kValue(ind.k())
                    .dValue(ind.d())
                    .build());
        } catch (Exception e) {
            log.warn("recordTrigger failed for alert {}: {}", alert.getId(), e.getMessage());
        }
        // Requirement 23：enqueue email 通知（dispatcher 自身已包 try/catch，不會回拋）
        // 三條均線（月線/季線/年線）一併帶過去，email 完整列出而非只印觸發條件對應的單一均線
        try {
            notificationDispatcher.enqueue(alert, triggeredAt, price,
                    ind.monthlyMa(), ind.quarterlyMa(), ind.annualMa(), ind.k(), ind.d());
        } catch (Exception e) {
            log.warn("enqueue email 通知失敗 alert {}: {}", alert.getId(), e.getMessage());
        }
    }

    /**
     * 每日 04:00 (Asia/Taipei) 清理 30 天前的觸發紀錄。
     */
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Taipei")
    @Transactional
    public void cleanupOldTriggers() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        int deleted = triggerRepo.deleteByCreatedAtBefore(cutoff);
        if (deleted > 0) log.info("清理 {} 筆 30 天前的警示觸發紀錄", deleted);
    }

    /** 公開為 static：WatchStockService 在組裝「警示條件」欄時共用同一份文案。 */
    public static String buildLabel(StockAlert a) {
        return buildLabel(a, null);
    }

    /**
     * 帶入當前技術指標時，MA 百分比條件（{@code MA_*_PCT} 且 threshold≠0）額外附上換算後的
     * 觸發價，例如「高於季線 20%（360）」；觸發價 = 對應均線 × (1 ± pct/100)。
     * {@code ind} 為 null（呼叫端不需價格）或該均線資料不足時，退回不含價格的純文字。
     * 觀察頁 / 警示頁共用此單一來源，確保兩頁「警示條件」欄口徑一致（同義欄位同一來源）。
     */
    public static String buildLabel(StockAlert a, TechnicalIndicatorService.FullIndicators ind) {
        double thr = a.getThreshold().doubleValue();
        return switch (a.getAlertType()) {
            case "MA_ABOVE_PCT" -> thr == 0
                    ? String.format("高於%s", maPeriodName(a.getMaPeriod()))
                    : String.format("高於%s %.0f%%%s", maPeriodName(a.getMaPeriod()), thr,
                            maTriggerPriceSuffix(a, ind, thr, true));
            case "MA_BELOW_PCT" -> thr == 0
                    ? String.format("低於%s", maPeriodName(a.getMaPeriod()))
                    : String.format("低於%s %.0f%%%s", maPeriodName(a.getMaPeriod()), thr,
                            maTriggerPriceSuffix(a, ind, thr, false));
            case "KD_ABOVE"              -> String.format("K 值高於 %.0f", thr);
            case "KD_BELOW"              -> String.format("K 值低於 %.0f", thr);
            case "KD_D_ABOVE"            -> String.format("D 值高於 %.0f", thr);
            case "KD_D_BELOW"            -> String.format("D 值低於 %.0f", thr);
            case "PRICE_ABOVE"           -> String.format("股價高於 %s", a.getThreshold().stripTrailingZeros().toPlainString());
            case "PRICE_BELOW"           -> String.format("股價低於 %s", a.getThreshold().stripTrailingZeros().toPlainString());
            default -> a.getAlertType();
        };
    }

    /**
     * 複合條件群組的合併文案（Task 253）：成員（已依 {@code displayOrder} 升冪）各自的
     * {@link #buildLabel} 以「 且 」串接，例「低於季線 10%（92.09） 且 K 值低於 15」。
     *
     * <p><b>分隔符的字面值固定為 {@code " 且 "}（半形空白 + 且 + 半形空白）。</b>
     * {@code buildLabel} 產出的單條 label 兩端都不帶空白（{@code "低於季線 10%（92.09）"} /
     * {@code "K 值低於 15"}），寫成不帶空白的 {@code "且"} 會得到「…（92.09）且K 值低於 15」這種黏在一起的字串；
     * 更要緊的是警示頁 / 觀察頁 / email digest / 補發四條路徑都用這一支，任一端各自假設不同寫法就必然有一邊對不上。
     *
     * <p>{@code ind} 可為 null（退回不含觸發價的純文字，比照 {@code buildLabel(a, null)}）。
     * 成員為空 list 時回空字串、不丟例外：孤兒群組（成員被外力清掉）不該讓整份清單載入失敗。
     */
    public static String buildGroupLabel(List<StockAlert> members,
                                         TechnicalIndicatorService.FullIndicators ind) {
        if (members == null || members.isEmpty()) return "";
        List<String> labels = members.stream().map(m -> buildLabel(m, ind)).toList();
        return String.join(" 且 ", labels);
    }

    /**
     * MA 百分比條件的觸發價後綴「（價格）」：價格 = 對應均線（依 maPeriod 取月/季/年線）× (1 ± pct/100)。
     * ind 為 null 或該均線資料不足（回 null / 0）時回空字串，label 不附價格。
     */
    private static String maTriggerPriceSuffix(StockAlert a, TechnicalIndicatorService.FullIndicators ind,
                                               double pct, boolean above) {
        if (ind == null) return "";
        BigDecimal ma = pickMaForAlert(a.getAlertType(), a.getMaPeriod(), ind);
        if (ma == null || ma.signum() == 0) return "";
        BigDecimal price = ma.multiply(BigDecimal.valueOf(1 + (above ? pct : -pct) / 100.0))
                .setScale(2, java.math.RoundingMode.HALF_UP);
        return String.format("（%s）", price.stripTrailingZeros().toPlainString());
    }

    /** maPeriod → 顯示名稱（20=月線、60=季線、240=年線，其他則回「MA{n}」）。 */
    private static String maPeriodName(Integer period) {
        if (period == null) return "均線";
        return switch (period) {
            case 20 -> "月線";
            case 60 -> "季線";
            case 240 -> "年線";
            default -> "MA" + period;
        };
    }
}
