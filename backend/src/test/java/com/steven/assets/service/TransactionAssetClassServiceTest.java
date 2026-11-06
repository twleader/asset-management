package com.steven.assets.service;

import com.steven.assets.dto.AssetTransactionDto.AssetClassChange;
import com.steven.assets.dto.InstitutionDto;
import com.steven.assets.model.*;
import com.steven.assets.repository.*;
import com.steven.assets.security.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionAssetClassServiceTest {
    @Mock StockRepository stocks;
    @Mock FundClassOverrideRepository funds;
    @Mock AssetClassRepository categories;
    @Mock InstitutionService institutions;
    @Mock CurrentUserContext currentUser;
    TransactionAssetClassService service;
    @BeforeEach void setup() {
        service = new TransactionAssetClassService(stocks, funds, categories, new AssetClassifier(), institutions, currentUser);
    }
    @Test void rulesAndOverridesUseExistingClassifierForStocksAndFunds() {
        var context = new TransactionAssetClassService.Context(Map.of(
                "台股|00679B", Stock.builder().code("00679B").market("台股").assetClass("STOCK").build()),
                Map.of("收益基金", FundClassOverride.builder().fundName("收益基金").assetClass("STOCK").build()));
        assertThat(service.resolve(stock("00679B"), context).effectiveAssetClass()).isEqualTo("STOCK");
        assertThat(service.resolve(stock("00679B"), new TransactionAssetClassService.Context(Map.of(), Map.of()))
                .effectiveAssetClass()).isEqualTo("BOND");
        assertThat(service.resolve(fund("收益基金"), context).effectiveAssetClass()).isEqualTo("STOCK");
        assertThat(service.resolve(fund("收益基金"), new TransactionAssetClassService.Context(Map.of(), Map.of()))
                .effectiveAssetClass()).isEqualTo("BOND");
    }
    @Test void omittedChangeNeverRequiresAdminOrTouchesAuthority() {
        service.apply(stock(null), null);
        verifyNoInteractions(currentUser, categories, institutions, stocks, funds);
    }
    @Test void forgedUserChangeIsRejectedBeforeAuthorityReadOrWrite() {
        assertThatThrownBy(() -> service.apply(stock("00881"), new AssetClassChange("BOND")))
                .isInstanceOf(AdminRequiredException.class);
        verifyNoInteractions(categories, institutions, stocks, funds);
    }
    @Test void missingMasterAndIndexCannotBeAssignedButLookupIsRuleOnly() {
        when(currentUser.isAdmin()).thenReturn(true);
        when(categories.findByCode("BOND")).thenReturn(Optional.of(AssetClass.builder().code("BOND").build()));
        when(stocks.findAllByCodeIn(List.of("00881"))).thenReturn(List.of());
        assertThatThrownBy(() -> service.apply(stock("00881"), new AssetClassChange("BOND"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.lookup("股票", null, "台股", "任意").assetClassAssignable()).isFalse();
        when(stocks.findAllByCodeIn(List.of("0000"))).thenReturn(List.of(Stock.builder().code("0000").market("台股").build()));
        assertThatThrownBy(() -> service.apply(stock("0000"), new AssetClassChange(null))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(institutions);
    }
    @Test void inactiveNonCashCategoryUsesSameAuthorityAndAutoClearsSameFundIdentity() {
        when(currentUser.isAdmin()).thenReturn(true);
        when(categories.findByCode("EXTRA")).thenReturn(Optional.of(AssetClass.builder().code("EXTRA").active(false).build()));
        service.apply(fund("新基金"), new AssetClassChange("EXTRA"));
        service.apply(fund("新基金"), new AssetClassChange(null));
        verify(institutions).setSecurityAssetClass(new InstitutionDto.SetSecurityAssetClassRequest("新基金", "基金", "EXTRA"));
        verify(institutions).setSecurityAssetClass(new InstitutionDto.SetSecurityAssetClassRequest("新基金", "基金", null));
    }
    @Test void cashUnknownCategoriesAndInvalidFundNamesCannotBeAssigned() {
        when(currentUser.isAdmin()).thenReturn(true);
        for (String code : List.of("CASH", "MISSING")) {
            assertThatThrownBy(() -> service.apply(fund("新基金"), new AssetClassChange(code))).isInstanceOf(IllegalArgumentException.class);
        }
        for (String name : List.of("", " ", "基".repeat(151))) {
            assertThatThrownBy(() -> service.apply(fund(name), new AssetClassChange(null))).isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(institutions);
    }
    private static AssetTransaction stock(String code) {
        return AssetTransaction.builder().assetType("股票").assetCode(code).market("台股").assetName("股票").build();
    }
    private static AssetTransaction fund(String name) {
        return AssetTransaction.builder().assetType("基金").assetName(name).build();
    }
}
