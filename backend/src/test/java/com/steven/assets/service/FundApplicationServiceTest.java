package com.steven.assets.service;

import com.steven.assets.dto.*;
import com.steven.assets.model.*;
import com.steven.assets.repository.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FundApplicationServiceTest {
    private final FundMasterRepository funds = mock(FundMasterRepository.class);
    private final BankRepository banks = mock(BankRepository.class);
    private final FundNavService nav = mock(FundNavService.class);
    private final FundDividendService dividends = mock(FundDividendService.class);
    private final FundApplicationService service = new FundApplicationService(funds,banks,nav,dividends);
    @Test
    void creationRetainsRawExistenceCheckTrimmedPersistenceDefaultActiveAndMissingBank() {
        when(funds.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var result = service.createFund(new CreateFundRequest(" F01 ","name",5L,"USD","site","org","fund","class",null));
        verify(funds).existsById(" F01 ");
        verify(funds,never()).existsById("F01");
        verify(banks).findById(5L);
        assertThat(result.fundCode()).isEqualTo("F01");
        assertThat(result.active()).isTrue();
        assertThat(result.bankId()).isNull();
        assertThat(result.latestNav()).isNull();
        assertThat(result.annualDividendPerUnitTwd()).isNull();
    }
    @Test
    void invalidOrDuplicateRequestsHaveNoSaveOrEnrichmentSideEffects() {
        assertThatThrownBy(() -> service.createFund(new CreateFundRequest(" ",null,null,null,null,null,null,null,null)))
                .isInstanceOf(FundApplicationService.InvalidFundCodeException.class);
        when(funds.existsById("raw")).thenReturn(true);
        assertThatThrownBy(() -> service.createFund(new CreateFundRequest("raw",null,null,null,null,null,null,null,null)))
                .isInstanceOf(FundApplicationService.FundAlreadyExistsException.class).hasMessage("fundCode 已存在: raw");
        verify(funds,never()).save(any());
        verifyNoInteractions(banks,nav,dividends);
    }
    @Test
    void listEnrichesAllRowsOnRequestedDateAndPreservesNulls() {
        LocalDate date = LocalDate.of(2026,10,20);
        Bank bank = new Bank(); bank.setId(5L); bank.setDisplayName("銀行");
        var row = FundMaster.builder().fundCode("F01").bank(bank).active(false).build();
        var missing = FundMaster.builder().fundCode("F02").build();
        when(funds.findAll()).thenReturn(List.of(row,missing));
        when(nav.getNavTwdOnDate("F01",date)).thenReturn(Optional.of(new FundNavService.LatestNav("F01","USD",new BigDecimal("2.1"),date,new BigDecimal("32"),date)));
        when(dividends.getAnnualEstimateOnDate("F01",date)).thenReturn(Optional.of(new FundDividendService.AnnualDividendEstimate("F01","USD",BigDecimal.ONE,new BigDecimal("32"),new BigDecimal("32"),4)));
        var result = service.listFunds(date);
        assertThat(result).extracting(FundDto::fundCode).containsExactly("F01","F02");
        assertThat(result.getFirst().bankDisplayName()).isEqualTo("銀行");
        assertThat(result.getFirst().twdPerUnit()).isEqualByComparingTo("67.2");
        assertThat(result.getFirst().annualDividendPerUnitTwd()).isEqualByComparingTo("32");
        assertThat(result.getFirst().dividendMonthsCounted()).isEqualTo(4);
        assertThat(result.get(1).latestNavDate()).isNull();
        verify(nav).getNavTwdOnDate("F02",date);
        verify(dividends).getAnnualEstimateOnDate("F02",date);
    }
    @Test
    void updatePreservesCodeAndActiveWhileActivePatchUsesBooleanTrueOnly() {
        var row = FundMaster.builder().fundCode(" original ").active(true).build();
        when(funds.findById(" original ")).thenReturn(Optional.of(row));
        when(funds.save(any())).thenAnswer(inv -> inv.getArgument(0));
        var result = service.updateFund(" original ",new UpdateFundRequest("new",null,"EUR","s","o","f","c"));
        assertThat(result.fundCode()).isEqualTo(" original ");
        assertThat(result.active()).isTrue();
        assertThat(result.currency()).isEqualTo("EUR");
        assertThat(service.setActive(" original ",null).active()).isFalse();
        assertThatThrownBy(() -> service.updateFund("unknown",new UpdateFundRequest(null,null,null,null,null,null,null)))
                .isInstanceOf(FundApplicationService.FundNotFoundException.class);
        assertThatThrownBy(() -> service.setActive("unknown",true)).isInstanceOf(FundApplicationService.FundNotFoundException.class);
    }
    @Test
    void latestUnavailableRetainsRequestedFundCode() {
        assertThat(service.latestNav("raw ")).containsEntry("fundCode","raw ").containsEntry("available",false);
    }
}
