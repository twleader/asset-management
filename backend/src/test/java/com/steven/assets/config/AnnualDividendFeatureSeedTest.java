package com.steven.assets.config;

import com.steven.assets.model.AppFeature;
import com.steven.assets.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AnnualDividendFeatureSeedTest {
    @Test void newFeatureIsEnabledAndKnownOldOrdersMoveWithoutChangingVisibilityOrCustomValues() {
        Map<String, AppFeature> stored = new HashMap<>();
        AppFeature advice = AppFeature.builder().code("/asset-allocation-advice").menuGroup("資產管理")
                .sortOrder(4).enabledForUser(false).build();
        AppFeature payment = AppFeature.builder().code("/payment-accounts").menuGroup("資產管理")
                .sortOrder(77).enabledForUser(false).build();
        stored.put(advice.getCode(), advice); stored.put(payment.getCode(), payment);
        AppFeatureRepository repo = mock(AppFeatureRepository.class);
        when(repo.findByCode(any())).thenAnswer(inv -> Optional.ofNullable(stored.get(inv.getArgument(0))));
        when(repo.save(any())).thenAnswer(inv -> { AppFeature feature = inv.getArgument(0); stored.put(feature.getCode(), feature); return feature; });
        DataInitializer initializer = new DataInitializer(mock(BankRepository.class), mock(BrokerRepository.class),
                mock(DepositTypeRepository.class), mock(MarketTypeRepository.class), mock(TransitFundTypeRepository.class),
                mock(FundMasterRepository.class), mock(PaymentCategoryRepository.class), mock(AssetClassRepository.class),
                mock(StockStyleRepository.class), mock(BondTermRepository.class), repo);
        ReflectionTestUtils.invokeMethod(initializer, "seedAppFeatures");
        assertThat(stored.get("/annual-dividends").getSortOrder()).isEqualTo(4);
        assertThat(stored.get("/annual-dividends").getEnabledForUser()).isTrue();
        assertThat(advice.getSortOrder()).isEqualTo(5);
        assertThat(advice.getEnabledForUser()).isFalse();
        assertThat(payment.getSortOrder()).isEqualTo(77);
        assertThat(payment.getEnabledForUser()).isFalse();
        ReflectionTestUtils.invokeMethod(initializer, "seedAppFeatures");
        assertThat(advice.getSortOrder()).isEqualTo(5);
        assertThat(stored.get("/annual-dividends").getSortOrder()).isEqualTo(4);
    }
}
