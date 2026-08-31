package com.steven.assets.service;

import com.steven.assets.model.Stock;
import com.steven.assets.repository.AppFeatureRepository;
import com.steven.assets.repository.AssetClassRepository;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.BankRepository;
import com.steven.assets.repository.BondTermRepository;
import com.steven.assets.repository.BrokerRepository;
import com.steven.assets.repository.DepositTypeRepository;
import com.steven.assets.repository.FundClassOverrideRepository;
import com.steven.assets.repository.FundHoldingRepository;
import com.steven.assets.repository.MarketTypeRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.repository.StockStyleRepository;
import com.steven.assets.repository.TransitFundTypeRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InstitutionServiceSecurityListTest {

    @Mock private BankRepository bankRepo;
    @Mock private BrokerRepository brokerRepo;
    @Mock private DepositTypeRepository depositTypeRepo;
    @Mock private MarketTypeRepository marketTypeRepo;
    @Mock private TransitFundTypeRepository transitFundTypeRepo;
    @Mock private AssetClassRepository assetClassRepo;
    @Mock private StockRepository stockRepo;
    @Mock private AssetClassifier assetClassifier;
    @Mock private StockStyleRepository stockStyleRepo;
    @Mock private BondTermRepository bondTermRepo;
    @Mock private AssetSnapshotRepository snapshotRepo;
    @Mock private SettingsClassificationResolver settingsClassification;
    @Mock private FundHoldingRepository fundHoldingRepo;
    @Mock private FundClassOverrideRepository fundClassOverrideRepo;
    @Mock private AppFeatureRepository appFeatureRepo;

    @InjectMocks private InstitutionService service;

    @Test
    void settingsListExcludesOnlyTaiwanMarketIndex() {
        Stock taiex = Stock.builder().code("0000").market("台股").name("台股大盤").build();
        Stock etf = Stock.builder().code("0050").market("台股").name("元大台灣50").build();
        Stock sameCodeOtherMarket = Stock.builder().code("0000").market("美股").name("Example").build();

        when(stockRepo.findAll()).thenReturn(List.of(taiex, etf, sameCodeOtherMarket));
        when(fundClassOverrideRepo.findAll()).thenReturn(List.of());
        when(fundHoldingRepo.findDistinctFundNames()).thenReturn(List.of());
        SettingsClassificationResolver.Context context = new SettingsClassificationResolver.Context(Map.of(), null);
        SettingsClassificationResolver.Resolution stockRule = new SettingsClassificationResolver.Resolution(
                AssetClassifier.STOCK, "RULE", AssetClassifier.GROWTH, "RULE", null, null,
                null, null, null);
        when(settingsClassification.context()).thenReturn(context);
        when(settingsClassification.resolve(etf, context)).thenReturn(stockRule);
        when(settingsClassification.resolve(sameCodeOtherMarket, context)).thenReturn(stockRule);

        assertThat(service.getAllSecurities())
                .extracting(response -> response.market() + "|" + response.code())
                .containsExactly("台股|0050", "美股|0000");

        // The list and Radar share the same settings-equivalent resolver.
        // Excluding only the Taiwan index must not accidentally skip a
        // same-code security from another market.
        verify(settingsClassification).resolve(etf, context);
        verify(settingsClassification).resolve(sameCodeOtherMarket, context);
        verify(settingsClassification, never()).resolve(taiex, context);
    }
}
