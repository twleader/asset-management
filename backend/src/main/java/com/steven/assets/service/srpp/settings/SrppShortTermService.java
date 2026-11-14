package com.steven.assets.service.srpp.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.dto.SrppShortTermDto.*;
import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.model.SrppSettingsDraft;
import com.steven.assets.repository.SrppSettingsDraftRepository;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.TradingRadarService;
import com.steven.assets.service.TradingRadarRuleEngine;
import com.steven.assets.service.srpp.decision.DecisionPolicyAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;

/** Owner-scoped projection of the existing pure radar reads. Draft thresholds never enter a radar evaluation. */
@Service
@Transactional(readOnly=true)
public class SrppShortTermService {
    private static final String EQUITY="TW_EQUITY",BOND="TW_BOND",US="US";
    private static final List<String> LIMITATIONS=List.of(
            "草稿參數尚未套用正式雷達；短線報酬未納入退休試算。",
            "這是雷達短線觀察，不代表SRPP短線部位的對帳、資金、深度、基本面及全部採用條件已通過。",
            "買進與減碼退出只沿用SHORT最終方向；沒有交易授權，採納時須自行在券商官方介面操作。",
            "完成日布林參考區間不是可成交限價、停損價或報酬目標；資料不足時僅等待資料。");
    private final CurrentUserContext user;
    private final SrppSettingsDraftRepository drafts;
    private final SrppSettingsService settings;
    private final TradingRadarService radar;
    private final DecisionPolicyAuthority authority;

    public SrppShortTermService(CurrentUserContext user,SrppSettingsDraftRepository drafts,SrppSettingsService settings,
            TradingRadarService radar,DecisionPolicyAuthority authority) {
        this.user=user;this.drafts=drafts;this.settings=settings;this.radar=radar;this.authority=authority;
    }
    public ListEnvelope list(String draftId,String revision) {
        long owner=owner();JsonNode values=draftValues(owner,draftId,revision),model=model();
        SixPolicy equity=twPolicy(model,"short_term_opportunity_d171"),bond=twPolicy(model,"bond_short_term_opportunity_d185");
        SixPolicy chosenEquity=values==null?equity:draftTw(values,"shortEquity",equity.symbols()),
                chosenBond=values==null?bond:draftTw(values,"shortBond",settings.allowedSymbols("shortBondSymbols"));
        UsValues us=usPolicy(model),draftUs=values==null?null:draftUs(values);
        TradingRadarDto.ListResponse source;
        try {source=radar.getList();}catch(RuntimeException failure){throw unavailable();}
        if(source==null)throw unavailable();
        List<Market> markets=List.of(market("台股",source.market()),market("美股",source.usMarket()));
        Map<String,Market> byMarket=new HashMap<>();markets.forEach(m->byMarket.put(m.market(),m));
        List<Row> rows=new ArrayList<>();Set<String> seen=new HashSet<>();
        boolean identity=nonblank(source.ruleVersion())&&nonblank(source.actionPolicyVersion())&&nonblank(source.generatedAt());
        for(TradingRadarDto.ListStock stock:source.stocks()) {
            if(stock==null||!nonblank(stock.stockCode())||!nonblank(stock.market()))continue;
            String group=group(stock.stockCode(),stock.market(),chosenEquity.symbols(),chosenBond.symbols());
            if(group==null)continue;
            if(!seen.add(stock.market()+"\0"+stock.stockCode()))throw unavailable();
            boolean complete=identity&&nonblank(stock.stockName())&&nonblank(stock.shortActionLabel())&&complete(byMarket.get(stock.market()),stock.shortAction(),stock.shortScore(),stock.price(),
                    stock.quoteStatus(),stock.priceUpdatedAt(),stock.dailyCandleAsOfDate());
            rows.add(new Row(stock.stockCode(),stock.stockName(),stock.market(),group,stock.held(),stock.shortAction(),
                    stock.shortActionLabel(),stock.shortScore(),complete?observation(stock.shortAction()):"WAIT",
                    complete?"RADAR_OBSERVATION":"WAITING_DATA",decimal(stock.price()),stock.quoteStatus(),stock.priceUpdatedAt(),
                    stock.dailyCandleAsOfDate(),stock.horizonConflict()));
        }
        rows.sort(Comparator.comparingInt((Row r)->groupOrder(r.group())).thenComparingInt(r->observationOrder(r.observationType()))
                .thenComparing(Row::shortScore,Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(Row::market)
                .thenComparing(Row::stockCode));
        List<MissingSymbol> missing=new ArrayList<>();
        for(var configured:List.of(Map.entry(EQUITY,chosenEquity.symbols()),Map.entry(BOND,chosenBond.symbols())))
            for(String symbol:configured.getValue())if(!seen.contains("台股\0"+symbol))missing.add(new MissingSymbol(symbol,"台股",configured.getKey(),"WAITING_DATA"));
        JsonNode e=model.path("execution_policy"),eq=e.path("short_term_opportunity_d171"),bo=e.path("bond_short_term_opportunity_d185"),u=e.path("us_megacap_tactical_policy_d133");
        List<Policy> policies=List.of(
                new TaiwanPolicy(EQUITY,"台股股票短線",equity,values==null?null:chosenEquity,text(eq,"rule"),text(eq,"exit_rule"),"NOT_MODELED",
                        "需反彈確認、淨報酬風險比、趨勢與同向雷達等條件；正式與草稿門檻列於下方。",
                        "支撐或趨勢失敗、賣方雷達、到壓力區或持有期限時重新評估；只處理已確認的短線部位。"),
                new TaiwanPolicy(BOND,"台股債券波段",bond,values==null?null:chosenBond,text(bo,"rule"),text(bo,"exit_rule"),"NOT_MODELED",
                        "債券波段使用獨立資金與反彈門檻；核心目標低或為零，不代表短線資格已通過。",
                        "依進場鎖定支撐、趨勢、壓力區與持有期限重新評估；不改回永久核心持有。"),
                new UsPolicy(US,"美股短線觀察",us,draftUs,text(u.path("candidate_universe"),"primary_path")+"\n"+text(u.path("minimum_profit_space_d185"),"rule"),
                        text(u.path("candidate_universe"),"other_gates_unchanged"),"NOT_MODELED",
                        "只觀察本輪目前帳號的美股雷達名單；仍需淨上漲空間、同向雷達及資金等獨立證據。",
                        "依正式個股短線計畫、退出條件與期限重新評估；本頁不產生股數或可成交委託價格。"));
        return new ListEnvelope(values==null?"FORMAL":"DRAFT_PARAMETER_PREVIEW_ONLY",false,authority.policy().bundleHash(),
                authority.policy().modelHash(),source.ruleVersion(),source.actionPolicyVersion(),source.generatedAt(),markets,policies,rows,missing,LIMITATIONS,false);
    }
    public DetailEnvelope stock(String code,String market) {
        owner();
        if(code==null||!code.matches("[A-Za-z0-9.\\-]{1,12}")||!Set.of("台股","美股").contains(market==null?"":market))throw bad("股票代號或市場別格式不合法");
        JsonNode model=model();SixPolicy equity=twPolicy(model,"short_term_opportunity_d171");
        String group=group(code,market,equity.symbols(),settings.allowedSymbols("shortBondSymbols"));if(group==null)throw notFound();
        com.steven.assets.dto.TradingRadarPanelDto.StockEvaluation source;
        try {source=radar.getStockEvaluation(code,market);}catch(IllegalArgumentException foreign){throw notFound();}
        catch(RuntimeException unavailable){throw unavailable();}
        if(source==null||source.stock()==null)throw unavailable();
        TradingRadarDto.StockDecision s=source.stock();
        if(!code.equals(s.stockCode())||!market.equals(s.market()))throw unavailable();
        String candle=s.dailyCandle()==null?null:s.dailyCandle().asOfDate();
        boolean complete=nonblank(source.ruleVersion())&&nonblank(source.actionPolicyVersion())&&nonblank(source.generatedAt())
                &&s.dataComplete()&&nonblank(s.stockName())&&nonblank(s.shortActionLabel())&&s.shortReasons()!=null&&s.shortRisks()!=null&&s.shortEvidenceConfidence()!=null
                &&s.shortDownsideRisk()!=null&&s.shortRiskCoverage()!=null&&Double.isFinite(s.shortRiskCoverage())
                &&s.shortRiskCoverage()>=0&&s.shortRiskCoverage()<=1
                &&complete(market(market,source.market()),s.shortAction(),s.shortScore(),s.price(),s.quoteStatus(),s.priceUpdatedAt(),candle);
        TradingRadarDto.PriceReference p=s.priceReference();
        PriceReference reference=p==null?null:new PriceReference(decimal(p.buyLower()),decimal(p.buyUpper()),decimal(p.sellLower()),
                decimal(p.sellUpper()),p.asOfDate(),p.applicableSide());
        return new DetailEnvelope(authority.policy().bundleHash(),authority.policy().modelHash(),source.ruleVersion(),source.actionPolicyVersion(),
                source.generatedAt(),code,s.stockName(),market,group,s.held(),complete?observation(s.shortAction()):"WAIT",
                complete?"RADAR_OBSERVATION":"WAITING_DATA",complete,s.shortAction(),s.shortActionLabel(),s.shortScore(),s.shortReasons(),s.shortRisks(),
                s.shortEvidenceConfidence(),s.shortDownsideRisk(),s.shortRiskCoverage(),s.horizonConflict(),decimal(s.price()),s.quoteStatus(),s.priceUpdatedAt(),
                candle,reference,LIMITATIONS,false);
    }
    private long owner() {
        if(!user.hasUser()||user.getEffectiveUserId()==null||user.getEffectiveUserId()<=0||!"ACTIVE".equals(user.getStatus()))
            throw new SrppSettingsProblem(401,"UNAUTHENTICATED","請先登入有效帳號");
        return user.getEffectiveUserId();
    }
    private JsonNode draftValues(long owner,String rawId,String rawRevision) {
        if(rawId==null&&rawRevision==null)return null;
        if(rawId==null||rawRevision==null)throw bad("草稿編號與版本必須成對提供");
        UUID id;long revision;
        try {id=UUID.fromString(rawId);if(!id.toString().equals(rawId.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException();
            if(!rawRevision.matches("[1-9][0-9]*"))throw new IllegalArgumentException();revision=Long.parseLong(rawRevision);
        }catch(RuntimeException invalid){throw bad("草稿編號或版本格式不合法");}
        SrppSettingsDraft d=drafts.findByIdAndOwnerUserId(id,owner).orElseThrow(SrppShortTermService::notFound);
        if(d.getRevision()!=revision)throw new SrppSettingsProblem(409,"REVISION_CONFLICT","草稿已更新，請重新載入");
        settings.ensureDraftCurrent(d);
        try{return SettingsCanonicalJson.parse(d.getValuesJson());}catch(RuntimeException invalid){throw unavailable();}
    }
    private JsonNode model(){return SettingsCanonicalJson.parse(authority.modelBytes());}
    private static SixPolicy twPolicy(JsonNode model,String key) {
        JsonNode p=model.path("execution_policy").path(key),limits=p.path("limits");
        return new SixPolicy(symbols(p.path("symbols")),num(p,"minimum_rebound_pct"),num(p,"minimum_net_reward_risk"),
                num(limits,"sleeve"),num(limits,"per_position"),integer(limits,"max_holding_sessions"));
    }
    private static SixPolicy draftTw(JsonNode values,String prefix,List<String> allowed) {
        List<String> symbols=symbols(values.path(prefix+"Symbols"));
        if(!new HashSet<>(allowed).containsAll(symbols)||new HashSet<>(symbols).size()!=symbols.size())throw unavailable();
        return new SixPolicy(symbols,num(values,prefix+"ReboundPct"),num(values,prefix+"NetRewardRisk"),num(values,prefix+"SleeveCap"),
                num(values,prefix+"PositionCap"),integer(values,prefix+"MaxHoldingSessions"));
    }
    private static UsValues usPolicy(JsonNode model) {
        JsonNode p=model.path("execution_policy").path("us_megacap_tactical_policy_d133"),limits=p.path("order_check_d186");
        return new UsValues(num(p.path("minimum_profit_space_d185"),"minimum_pct"),num(limits,"batch_cap"),num(limits,"company_cap"),num(limits,"tactical_total_cap"));
    }
    private static UsValues draftUs(JsonNode values){return new UsValues(num(values,"usTacticalNetUpsidePct"),num(values,"usTacticalBatchCap"),num(values,"usTacticalCompanyCap"),num(values,"usTacticalSleeveCap"));}
    private static List<String> symbols(JsonNode n) {
        if(!n.isArray())throw unavailable();List<String> out=new ArrayList<>();
        for(JsonNode v:n){if(!v.isTextual()||v.asText().isBlank())throw unavailable();out.add(v.asText());}return List.copyOf(out);
    }
    private static String group(String code,String market,List<String> equity,List<String> bonds) {
        if("美股".equals(market))return US;
        if(!"台股".equals(market))return null;
        return equity.contains(code)?EQUITY:bonds.contains(code)?BOND:null;
    }
    private static Market market(String name,TradingRadarDto.MarketSummary m){return m==null?new Market(name,true,false,null,null):new Market(name,m.stale(),m.dataComplete(),m.asOfDate(),m.quoteStatus());}
    private static boolean complete(Market m,String action,Integer score,BigDecimal price,String quoteStatus,String updated,String candle) {
        if(m==null||m.stale()||!m.dataComplete()||!nonblank(m.asOfDate())||score==null||score<0||score>100||price==null||price.signum()<=0||!nonblank(candle))return false;
        try {TradingRadarRuleEngine.Action.valueOf(action);}catch(RuntimeException invalid){return false;}
        return "LIVE".equals(quoteStatus)?nonblank(updated):"VERIFIED_CLOSE".equals(quoteStatus);
    }
    private static String observation(String action) {return switch(action){case "BUY_CANDIDATE","ADD_CANDIDATE","TRIAL_BUY"->"BUY_WATCH";case "REDUCE_CANDIDATE","EXIT_CANDIDATE"->"SELL_WATCH";default->"WAIT";};}
    private static int groupOrder(String value){return EQUITY.equals(value)?0:BOND.equals(value)?1:2;}
    private static int observationOrder(String value){return "BUY_WATCH".equals(value)?0:"SELL_WATCH".equals(value)?1:2;}
    private static String decimal(BigDecimal value){return value==null?null:value.toPlainString();}
    private static boolean nonblank(String value){return value!=null&&!value.isBlank();}
    private static double num(JsonNode n,String key){JsonNode v=n.path(key);if(!v.isNumber()||!Double.isFinite(v.doubleValue()))throw unavailable();return v.doubleValue();}
    private static int integer(JsonNode n,String key){JsonNode v=n.path(key);if(!v.isIntegralNumber()||!v.canConvertToInt())throw unavailable();return v.intValue();}
    private static String text(JsonNode n,String key){JsonNode v=n.path(key);if(!v.isTextual()||v.asText().isBlank())throw unavailable();return v.asText();}
    private static SrppSettingsProblem unavailable(){return new SrppSettingsProblem(503,"SHORT_DATA_UNAVAILABLE","短線來源目前無法完整讀取，請稍後重新查詢");}
    private static SrppSettingsProblem bad(String message){return new SrppSettingsProblem(400,"INVALID_REQUEST",message);}
    private static SrppSettingsProblem notFound(){return new SrppSettingsProblem(404,"NOT_FOUND","找不到目前帳號可觀察的短線標的或草稿");}
}
