package com.steven.assets.service;

import com.steven.assets.dto.StockAlertDto;
import com.steven.assets.model.StockAlert;
import com.steven.assets.model.StockAlertGroup;
import com.steven.assets.model.StockAlertTrigger;
import com.steven.assets.repository.NotificationRecipientRepository;
import com.steven.assets.repository.StockAlertGroupRecipientRepository;
import com.steven.assets.repository.StockAlertGroupRepository;
import com.steven.assets.repository.StockAlertRecipientRepository;
import com.steven.assets.repository.StockAlertRepository;
import com.steven.assets.repository.StockAlertTriggerRepository;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.security.TenantGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Requirement 194：用途驗證、群組單一來源與 trigger 快照。 */
@ExtendWith(MockitoExtension.class)
class StockAlertPurposeTest {

    @Mock StockAlertRepository alertRepo;
    @Mock StockAlertTriggerRepository triggerRepo;
    @Mock PriceQueryService priceQuery;
    @Mock StockPriceHistoryRepository historyRepo;
    @Mock StockRepository stockMasterRepo;
    @Mock StockMasterService stockMasterService;
    @Mock HistoricalDataService historicalDataService;
    @Mock TechnicalIndicatorService indicatorService;
    @Mock AlertNotificationDispatcher notificationDispatcher;
    @Mock StockAlertTriggerExportService triggerExportService;
    @Mock MarketDataService marketDataService;
    @Mock StockAlertRecipientRepository recipientLinkRepo;
    @Mock NotificationRecipientService notificationRecipientService;
    @Mock NotificationRecipientRepository recipientRepo;
    @Mock TenantGuard tenantGuard;
    @Mock StockAlertGroupRepository groupRepo;
    @Mock StockAlertGroupRecipientRepository groupRecipientRepo;
    @Mock TwseIndexDailyHistoryRepository twseIndexHistoryRepo;
    @InjectMocks StockAlertService service;

    private void stubGroupCreateDefaults() {
        when(alertRepo.findAllByOrderByDisplayOrderAsc()).thenReturn(List.of());
        when(groupRepo.findAllByOrderByDisplayOrderAsc()).thenReturn(List.of());
        when(groupRepo.findByStockCodeAndMarket(any(), any())).thenReturn(List.of());
        when(stockMasterRepo.findByCodeAndMarket(any(), any())).thenReturn(Optional.empty());
        when(historyRepo.findDistinctTradingDatesByMarket(any(), any())).thenReturn(List.of());
        when(groupRecipientRepo.findRecipientIdsByGroupId(any())).thenReturn(List.of());
        when(tenantGuard.requireCurrentUserId()).thenReturn(1L);
    }

    @Test
    void onlyExplicitBuyAndSellAreValidNewPurposes() {
        assertDoesNotThrow(() -> StockAlertService.validatePurpose("BUY"));
        assertDoesNotThrow(() -> StockAlertService.validatePurpose("SELL"));
        for (String invalid : List.of("", "UNSPECIFIED", "buy", "HOLD")) {
            assertThrows(IllegalArgumentException.class, () -> StockAlertService.validatePurpose(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> StockAlertService.validatePurpose(null));
    }

    @Test
    void singleAndGroupEntryPointsRejectEveryLegacyOrUnknownPurposeBeforeSaving() {
        when(alertRepo.findAllByOrderByDisplayOrderAsc()).thenReturn(List.of());
        when(groupRepo.findAllByOrderByDisplayOrderAsc()).thenReturn(List.of());

        for (String invalid : new String[]{null, "", "UNSPECIFIED", "buy", "HOLD"}) {
            assertThrows(IllegalArgumentException.class, () -> service.create(request(invalid)));
            assertThrows(IllegalArgumentException.class, () -> service.createGroup(groupRequest(invalid)));
        }

        verify(alertRepo, never()).save(any(StockAlert.class));
        verify(groupRepo, never()).save(any(StockAlertGroup.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"BUY", "SELL"})
    void singleCreatePersistsExplicitPurpose(String purpose) {
        when(alertRepo.findAllByOrderByDisplayOrderAsc()).thenReturn(List.of());
        when(groupRepo.findAllByOrderByDisplayOrderAsc()).thenReturn(List.of());
        when(alertRepo.findByStockCodeAndMarket(any(), any())).thenReturn(List.of());
        when(stockMasterRepo.findByCodeAndMarket(any(), any())).thenReturn(Optional.empty());
        when(tenantGuard.requireCurrentUserId()).thenReturn(1L);
        when(alertRepo.save(any(StockAlert.class))).thenAnswer(invocation -> {
            StockAlert saved = invocation.getArgument(0);
            saved.setId(10L);
            return saved;
        });
        when(recipientLinkRepo.findRecipientIdsByAlertId(10L)).thenReturn(List.of());
        when(historyRepo.findDistinctTradingDatesByMarket(any(), any())).thenReturn(List.of());

        StockAlertDto.Response response = service.create(request(purpose));

        assertEquals(purpose, response.purpose());
    }

    @Test
    void legacyUnspecifiedPurposeIsReturnedWithoutBeingInferred() {
        StockAlert legacy = alert(6L, null, "UNSPECIFIED");
        when(alertRepo.findAllByOrderByDisplayOrderAsc()).thenReturn(List.of(legacy));
        when(groupRepo.findAllByOrderByDisplayOrderAsc()).thenReturn(List.of());
        when(stockMasterRepo.findByCodeAndMarket("2330", "台股")).thenReturn(Optional.empty());
        when(historyRepo.findDistinctTradingDatesByMarket(any(), any())).thenReturn(List.of());
        when(recipientLinkRepo.findRecipientIdsByAlertId(6L)).thenReturn(List.of());

        StockAlertDto.Response response = service.findAll().getFirst();

        assertEquals("UNSPECIFIED", response.purpose());
    }

    @ParameterizedTest
    @ValueSource(strings = {"BUY", "SELL"})
    void groupCreateSynchronizesPurposeToEveryMember(String purpose) {
        stubGroupCreateDefaults();
        List<StockAlert> savedMembers = new ArrayList<>();
        when(groupRepo.save(any(StockAlertGroup.class))).thenAnswer(invocation -> {
            StockAlertGroup group = invocation.getArgument(0);
            group.setId(21L);
            return group;
        });
        when(alertRepo.save(any(StockAlert.class))).thenAnswer(invocation -> {
            StockAlert member = invocation.getArgument(0);
            savedMembers.add(member);
            return member;
        });
        when(alertRepo.findByGroupIdOrderByDisplayOrderAsc(21L)).thenAnswer(invocation -> savedMembers);

        StockAlertDto.Response response = service.createGroup(groupRequest(purpose));

        assertEquals(purpose, response.purpose());
        assertEquals(2, savedMembers.size());
        savedMembers.forEach(member -> assertEquals(purpose, member.getPurpose()));
    }

    @Test
    void singleAlertMutationsRejectGroupMembersBeforeWrites() {
        StockAlert member = alert(7L, 99L, "UNSPECIFIED");
        when(alertRepo.findById(7L)).thenReturn(Optional.of(member));

        assertThrows(IllegalArgumentException.class, () -> service.update(7L, request("BUY")));
        assertThrows(IllegalArgumentException.class, () -> service.delete(7L));
        assertThrows(IllegalArgumentException.class, () -> service.toggleActive(7L));
        assertThrows(IllegalArgumentException.class,
                () -> service.reorder(List.of(new StockAlertDto.OrderItem("SINGLE", 7L))));

        verify(alertRepo, never()).save(any(StockAlert.class));
        verify(recipientLinkRepo, never()).deleteByAlertId(any());
    }

    @Test
    void reorderPreflightsEveryItemBeforeChangingAnEarlierStandaloneAlert() {
        StockAlert standalone = alert(1L, null, "BUY");
        StockAlert member = alert(2L, 99L, "BUY");
        when(alertRepo.findById(1L)).thenReturn(Optional.of(standalone));
        when(alertRepo.findById(2L)).thenReturn(Optional.of(member));

        assertThrows(IllegalArgumentException.class, () -> service.reorder(List.of(
                new StockAlertDto.OrderItem("SINGLE", 1L),
                new StockAlertDto.OrderItem("SINGLE", 2L))));

        verify(alertRepo, never()).save(any(StockAlert.class));
    }

    @Test
    void singleAndGroupTriggersStoreCurrentPurposeAsImmutableSnapshot() throws Exception {
        when(indicatorService.computeAll(any(), any())).thenReturn(TechnicalIndicatorService.FullIndicators.EMPTY);
        StockAlert single = alert(3L, null, "BUY");
        invokePrivate("recordTrigger", new Class<?>[]{StockAlert.class, LocalDateTime.class, BigDecimal.class},
                single, LocalDateTime.of(2026, 10, 18, 10, 0), BigDecimal.TEN);
        ArgumentCaptor<StockAlertTrigger> singleTrigger = ArgumentCaptor.forClass(StockAlertTrigger.class);
        verify(triggerRepo).save(singleTrigger.capture());
        assertEquals("BUY", singleTrigger.getValue().getPurpose());

        StockAlertGroup group = StockAlertGroup.builder().id(4L).ownerUserId(1L)
                .stockCode("2330").market("台股").purpose("SELL").build();
        StockAlert member = alert(5L, 4L, "SELL");
        invokePrivate("recordGroupTrigger",
                new Class<?>[]{StockAlertGroup.class, List.class, LocalDateTime.class, BigDecimal.class},
                group, List.of(member), LocalDateTime.of(2026, 10, 18, 10, 5), BigDecimal.valueOf(11));
        ArgumentCaptor<StockAlertTrigger> groupTrigger = ArgumentCaptor.forClass(StockAlertTrigger.class);
        verify(triggerRepo, org.mockito.Mockito.times(2)).save(groupTrigger.capture());
        assertEquals("SELL", groupTrigger.getAllValues().getLast().getPurpose());
    }

    private static StockAlertDto.Request request(String purpose) {
        return new StockAlertDto.Request("2330", "台積電", "台股", "PRICE_ABOVE", null,
                BigDecimal.ONE, purpose, true, List.of());
    }

    private static StockAlertDto.GroupRequest groupRequest(String purpose) {
        return new StockAlertDto.GroupRequest("2330", "台積電", "台股", List.of(
                new StockAlertDto.ConditionItem("PRICE_ABOVE", null, BigDecimal.ONE, null),
                new StockAlertDto.ConditionItem("PRICE_BELOW", null, BigDecimal.TEN, null)),
                purpose, true, List.of());
    }

    private static StockAlert alert(Long id, Long groupId, String purpose) {
        return StockAlert.builder().id(id).ownerUserId(1L).stockCode("2330").market("台股")
                .groupId(groupId).alertType("PRICE_ABOVE").threshold(BigDecimal.ONE)
                .purpose(purpose).active(true).build();
    }

    private void invokePrivate(String name, Class<?>[] parameterTypes, Object... arguments) throws Exception {
        Method method = StockAlertService.class.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        method.invoke(service, arguments);
    }
}
