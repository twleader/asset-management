package com.steven.assets.service.srpp.settings;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.dto.TradingRadarPanelDto;
import com.steven.assets.dto.SrppShortTermDto.*;
import com.steven.assets.model.SrppSettingsDraft;
import com.steven.assets.repository.SrppSettingsDraftRepository;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.TradingRadarService;
import com.steven.assets.service.srpp.decision.DecisionPolicyAuthority;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class SrppShortTermServiceTest {
    final ObjectMapper json=new ObjectMapper();
    final CurrentUserContext user=new CurrentUserContext();
    final SrppSettingsDraftRepository drafts=mock(SrppSettingsDraftRepository.class);
    final SrppSettingsService settings=mock(SrppSettingsService.class);
    final TradingRadarService radar=mock(TradingRadarService.class);
    final DecisionPolicyAuthority authority=new DecisionPolicyAuthority();
    SrppShortTermService service;
    @BeforeEach void setup(){user.setEffectiveUserId(7L);user.setStatus("ACTIVE");user.setRole("USER");service=new SrppShortTermService(user,drafts,settings,radar,authority);}
    TradingRadarDto.MarketSummary market(boolean stale)throws Exception {
        return json.readValue("{\"dataComplete\":true,\"stale\":"+stale+",\"asOfDate\":\"2026-11-13\",\"quoteStatus\":\"VERIFIED_CLOSE\"}",TradingRadarDto.MarketSummary.class);
    }
    TradingRadarDto.ListStock row(String code,String market,String action,Integer score)throws Exception {
        ObjectNode n=json.createObjectNode().put("stockCode",code).put("stockName","原來源 "+code).put("market",market).put("held",true)
                .put("shortAction",action).put("shortActionLabel","SHORT "+action).put("price","123.4500").put("quoteStatus","VERIFIED_CLOSE")
                .put("dailyCandleAsOfDate","2026-11-13").put("action","EXIT_CANDIDATE").put("score",1);
        if(score!=null)n.put("shortScore",score);
        return json.treeToValue(n,TradingRadarDto.ListStock.class);
    }
    void source(boolean stale,TradingRadarDto.ListStock...rows)throws Exception {
        when(radar.getList()).thenReturn(new TradingRadarDto.ListResponse("TW_RULES_TEST","EVIDENCE_GATE_TEST","2026-11-14T10:00:00+08:00",market(stale),market(stale),List.of(rows),0,List.of()));
    }
    TradingRadarDto.StockDecision decision(String code,String market,String action)throws Exception {
        ObjectNode n=json.valueToTree(row(code,market,action,65));
        n.remove("dailyCandleAsOfDate");n.put("dataComplete",true).put("shortEvidenceConfidence",80).put("shortDownsideRisk",25).put("shortRiskCoverage",0.9);
        n.putArray("shortReasons").add("SHORT來源理由");n.putArray("shortRisks").add("SHORT來源風險");
        n.putArray("reasons").add("中期理由不可當成短線利多");n.putArray("actionGateReasons").add("共用稽核不可當成短線利多");
        n.putObject("dailyCandle").put("asOfDate","2026-11-13");
        n.putObject("priceReference").put("buyLower","12.3456").put("buyUpper","13.2000").putNull("sellLower").putNull("sellUpper")
                .put("asOfDate","2026-11-13").put("applicableSide","BUY");
        return json.treeToValue(n,TradingRadarDto.StockDecision.class);
    }
    void detail(TradingRadarDto.StockDecision d)throws Exception {
        when(radar.getStockEvaluation(d.stockCode(),d.market())).thenReturn(new TradingRadarPanelDto.StockEvaluation("TW_RULES_TEST","EVIDENCE_GATE_TEST","2026-11-14T10:00:00+08:00",market(false),row(d.stockCode(),d.market(),d.shortAction(),d.shortScore()),d));
    }
    ObjectNode currentValues()throws Exception {
        byte[] catalog=getClass().getResourceAsStream("/srpp/settings/settings_catalog.json").readAllBytes();
        byte[] payroll=getClass().getResourceAsStream("/srpp/settings/pre_retirement_payroll.json").readAllBytes();
        return SettingsAuthorityValues.extract(SettingsCanonicalJson.parse(new String(catalog,StandardCharsets.UTF_8)),
                SettingsCanonicalJson.parse(authority.modelBytes()),SettingsCanonicalJson.parse(authority.manifestBytes()),payroll);
    }
    SrppSettingsDraft draft(ObjectNode values) {
        SrppSettingsDraft d=new SrppSettingsDraft();d.setId(UUID.randomUUID());d.setOwnerUserId(7L);d.setRevision(3);d.setValuesJson(SettingsCanonicalJson.canonical(values));
        when(drafts.findByIdAndOwnerUserId(d.getId(),7L)).thenReturn(Optional.of(d));return d;
    }
    @Test void listReadsOncePreservesShortFinalDirectionAndNeverFetchesEveryDetail()throws Exception {
        source(false,row("0050","台股","BUY_CANDIDATE",70),row("00697B","台股","EXIT_CANDIDATE",30),row("MSFT","美股","HOLD",80),row("NVDA","美股","FUTURE_UNKNOWN",100));
        ListEnvelope out=service.list(null,null);
        assertThat(out.configurationStatus()).isEqualTo("FORMAL");assertThat(out.thresholdsAppliedToRadar()).isFalse();assertThat(out.tradeAuthorization()).isFalse();
        assertThat(out.rows()).extracting(Row::stockCode).containsExactly("0050","00697B","NVDA","MSFT");
        assertThat(out.rows().get(0).observationType()).isEqualTo("BUY_WATCH");assertThat(out.rows().get(1).observationType()).isEqualTo("SELL_WATCH");
        assertThat(out.rows().get(0).price()).isEqualTo("123.4500");assertThat(out.rows().get(0).shortAction()).isEqualTo("BUY_CANDIDATE");
        assertThat(out.rows().get(2).observationType()).isEqualTo("WAIT");assertThat(out.rows().get(2).status()).isEqualTo("WAITING_DATA");
        assertThat(out.rows().get(3).status()).isEqualTo("RADAR_OBSERVATION");
        assertThat(out.missingSymbols()).extracting(MissingSymbol::stockCode).contains("009826","00679B","00751B");
        assertThat(out.policies()).hasSize(3);assertThat(out.limitations()).anyMatch(s->s.contains("未納入退休試算"));
        assertThatThrownBy(()->out.rows().clear()).isInstanceOf(UnsupportedOperationException.class);
        verify(radar).getList();verifyNoMoreInteractions(radar);
    }
    @Test void draftOnlyNarrowsSymbolsAndPreviewsThresholdsWithoutOverridingBlockedRadar()throws Exception {
        ObjectNode values=currentValues();values.putArray("shortEquitySymbols").add("0050");values.putArray("shortBondSymbols");values.put("shortEquityReboundPct",0).put("shortEquityNetRewardRisk",0.1);
        SrppSettingsDraft d=draft(values);
        source(false,row("0050","台股","NO_TRADE",99),row("00881","台股","BUY_CANDIDATE",90),row("00697B","台股","BUY_CANDIDATE",90));
        var out=service.list(d.getId().toString(),"3");
        assertThat(out.configurationStatus()).isEqualTo("DRAFT_PARAMETER_PREVIEW_ONLY");assertThat(out.thresholdsAppliedToRadar()).isFalse();
        assertThat(out.rows()).extracting(Row::stockCode).containsExactly("0050");assertThat(out.rows().get(0).shortAction()).isEqualTo("NO_TRADE");
        assertThat(out.rows().get(0).observationType()).isEqualTo("WAIT");
        TaiwanPolicy policy=(TaiwanPolicy)out.policies().get(0);assertThat(policy.formalValues().reboundPct()).isEqualTo(3);assertThat(policy.draftValues().reboundPct()).isZero();
        verify(settings).ensureDraftCurrent(d);verify(radar).getList();verifyNoMoreInteractions(radar);
    }
    @Test void staleMarketMissingScoreAndMissingPriceStayWaitingWithoutChangingSourceAction()throws Exception {
        source(true,row("0050","台股","BUY_CANDIDATE",99));var stale=service.list(null,null).rows().get(0);
        assertThat(stale.observationType()).isEqualTo("WAIT");assertThat(stale.shortAction()).isEqualTo("BUY_CANDIDATE");assertThat(stale.status()).isEqualTo("WAITING_DATA");
        source(false,row("0050","台股","BUY_CANDIDATE",null));assertThat(service.list(null,null).rows().get(0).status()).isEqualTo("WAITING_DATA");
        ObjectNode n=json.valueToTree(row("0050","台股","BUY_CANDIDATE",99));n.putNull("price");source(false,json.treeToValue(n,TradingRadarDto.ListStock.class));
        assertThat(service.list(null,null).rows().get(0).price()).isNull();assertThat(service.list(null,null).rows().get(0).status()).isEqualTo("WAITING_DATA");
    }
    @Test void identityAndDraftFailuresOccurBeforeRadarRead()throws Exception {
        user.setStatus("DISABLED");assertThatThrownBy(()->service.list(null,null)).isInstanceOf(SrppSettingsProblem.class).hasMessageContaining("登入");
        user.setStatus("ACTIVE");
        assertThatThrownBy(()->service.list(UUID.randomUUID().toString(),null)).isInstanceOf(SrppSettingsProblem.class).hasMessageContaining("成對");
        assertThatThrownBy(()->service.list("bad","1")).isInstanceOf(SrppSettingsProblem.class);
        assertThatThrownBy(()->service.list(UUID.randomUUID().toString(),"0")).isInstanceOf(SrppSettingsProblem.class);
        UUID other=UUID.randomUUID();when(drafts.findByIdAndOwnerUserId(other,7L)).thenReturn(Optional.empty());
        assertThatThrownBy(()->service.list(other.toString(),"1")).isInstanceOf(SrppSettingsProblem.class).hasMessageContaining("目前帳號");
        SrppSettingsDraft d=draft(currentValues());assertThatThrownBy(()->service.list(d.getId().toString(),"2")).isInstanceOf(SrppSettingsProblem.class).hasMessageContaining("草稿已更新");
        doThrow(new SrppSettingsProblem(409,"BASE_POLICY_CHANGED","來源已更新")).when(settings).ensureDraftCurrent(d);
        assertThatThrownBy(()->service.list(d.getId().toString(),"3")).isInstanceOf(SrppSettingsProblem.class).hasMessageContaining("來源已更新");
        verifyNoInteractions(radar);
    }
    @Test void detailUsesOnePureEvaluationAndOnlyShortReasonsAndCompletedReference()throws Exception {
        detail(decision("0050","台股","HOLD"));DetailEnvelope out=service.stock("0050","台股");
        assertThat(out.shortReasons()).containsExactly("SHORT來源理由");assertThat(out.shortRisks()).containsExactly("SHORT來源風險");
        assertThat(out.observationType()).isEqualTo("WAIT");assertThat(out.shortAction()).isEqualTo("HOLD");assertThat(out.tradeAuthorization()).isFalse();
        assertThat(out.priceReference().buyLower()).isEqualTo("12.3456");assertThat(out.priceReference().sellLower()).isNull();assertThat(out.priceReference().asOfDate()).isEqualTo("2026-11-13");
        assertThat(out.limitations()).anyMatch(s->s.contains("不是可成交限價"));verify(radar).getStockEvaluation("0050","台股");verifyNoMoreInteractions(radar);
    }
    @Test void detailRejectsUnownedOrUnconfiguredTargetsAndPreservesNullEvidence()throws Exception {
        assertThatThrownBy(()->service.stock("2330","台股")).isInstanceOf(SrppSettingsProblem.class);verifyNoInteractions(radar);
        when(radar.getStockEvaluation("FOREIGN","美股")).thenThrow(new IllegalArgumentException("找不到可分析標的"));
        assertThatThrownBy(()->service.stock("FOREIGN","美股")).isInstanceOf(SrppSettingsProblem.class).hasMessageContaining("目前帳號");
        ObjectNode n=json.valueToTree(decision("0050","台股","BUY_CANDIDATE"));n.putNull("shortEvidenceConfidence").putNull("priceReference").putNull("price");
        detail(json.treeToValue(n,TradingRadarDto.StockDecision.class));var out=service.stock("0050","台股");
        assertThat(out.status()).isEqualTo("WAITING_DATA");assertThat(out.observationType()).isEqualTo("WAIT");assertThat(out.price()).isNull();assertThat(out.priceReference()).isNull();
        assertThat(out.shortAction()).isEqualTo("BUY_CANDIDATE");
    }
}
