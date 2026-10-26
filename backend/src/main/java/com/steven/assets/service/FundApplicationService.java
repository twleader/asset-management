package com.steven.assets.service;

import com.steven.assets.dto.*;
import com.steven.assets.model.Bank;
import com.steven.assets.model.FundMaster;
import com.steven.assets.repository.BankRepository;
import com.steven.assets.repository.FundMasterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Fund operations and enrichment independent of HTTP transport. */
@Service
@RequiredArgsConstructor
public class FundApplicationService {
    private final FundMasterRepository fundMasterRepo;
    private final BankRepository bankRepo;
    private final FundNavService fundNavService;
    private final FundDividendService fundDividendService;

    public List<FundDto> listFunds(LocalDate basedate) {
        return fundMasterRepo.findAll().stream().map(m -> toDto(m, basedate)).toList();
    }
    public FundDto createFund(CreateFundRequest req) {
        if (req.fundCode() == null || req.fundCode().isBlank())
            throw new InvalidFundCodeException();
        if (fundMasterRepo.existsById(req.fundCode()))
            throw new FundAlreadyExistsException(req.fundCode());
        Bank bank = req.bankId() != null ? bankRepo.findById(req.bankId()).orElse(null) : null;
        FundMaster m = FundMaster.builder()
                .fundCode(req.fundCode().trim())
                .fundName(req.fundName())
                .bank(bank)
                .currency(req.currency())
                .site(req.site())
                .fundclearOrgCode(req.fundclearOrgCode())
                .fundclearFundCode(req.fundclearFundCode())
                .fundclearClassCode(req.fundclearClassCode())
                .active(req.active() == null ? Boolean.TRUE : req.active())
                .build();
        return toDto(fundMasterRepo.save(m));
    }
    public FundDto updateFund(String fundCode, UpdateFundRequest req) {
        FundMaster m = fundMasterRepo.findById(fundCode)
                .orElseThrow(FundNotFoundException::new);
        Bank bank = req.bankId() != null ? bankRepo.findById(req.bankId()).orElse(null) : null;
        m.setFundName(req.fundName());
        m.setBank(bank);
        m.setCurrency(req.currency());
        m.setSite(req.site());
        m.setFundclearOrgCode(req.fundclearOrgCode());
        m.setFundclearFundCode(req.fundclearFundCode());
        m.setFundclearClassCode(req.fundclearClassCode());
        return toDto(fundMasterRepo.save(m));
    }
    public FundDto setActive(String fundCode, Boolean active) {
        FundMaster m = fundMasterRepo.findById(fundCode)
                .orElseThrow(FundNotFoundException::new);
        m.setActive(Boolean.TRUE.equals(active));
        return toDto(fundMasterRepo.save(m));
    }
    public Map<String, Object> latestNav(String fundCode) {
        return fundNavService.getLatestNavTwd(fundCode)
                .map(l -> Map.<String, Object>of(
                        "fundCode", l.fundCode(),
                        "currency", l.currency(),
                        "nav", l.nav(),
                        "navDate", l.navDate(),
                        "fxRate", l.fxRate(),
                        "fxDate", l.fxDate(),
                        "twdPerUnit", l.twdPerUnit()))
                .orElse(Map.of("fundCode", fundCode, "available", false));
    }
    private FundDto toDto(FundMaster m) { return toDto(m, null); }

    private FundDto toDto(FundMaster m, LocalDate basedate) {
        var latest = fundNavService.getNavTwdOnDate(m.getFundCode(), basedate).orElse(null);
        var div = fundDividendService.getAnnualEstimateOnDate(m.getFundCode(), basedate).orElse(null);
        return new FundDto(
                m.getFundCode(),
                m.getFundName(),
                m.getBank() != null ? m.getBank().getId() : null,
                m.getBank() != null ? m.getBank().getDisplayName() : null,
                m.getCurrency(),
                m.getSite(),
                m.getFundclearOrgCode(),
                m.getFundclearFundCode(),
                m.getFundclearClassCode(),
                m.getActive(),
                latest != null ? latest.nav() : null,
                latest != null ? latest.navDate() : null,
                latest != null ? latest.fxRate() : null,
                latest != null ? latest.fxDate() : null,
                latest != null ? latest.twdPerUnit() : null,
                div != null ? div.annualPerUnitTwd() : null,
                div != null ? div.monthsCounted() : null
        );
    }

    public static class InvalidFundCodeException extends RuntimeException {}
    public static class FundAlreadyExistsException extends RuntimeException {
        public FundAlreadyExistsException(String code) { super("fundCode 已存在: " + code); }
    }
    public static class FundNotFoundException extends RuntimeException {}
}
