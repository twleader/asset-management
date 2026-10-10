package com.steven.assets.service.srpp.decision;

import java.math.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import static com.steven.assets.service.srpp.decision.DecisionFacts.*;

/** D-130/D-195 pure frozen-input calculator. All output is report-only. */
public final class DailyDecisionCalculator {
    public static final String VERSION="SRPP_D130_D195_V1";
    private static final BigDecimal THOUSAND=new BigDecimal("1000"),HUNDRED=new BigDecimal("100");
    private static final MathContext MC=MathContext.DECIMAL128;
    private DailyDecisionCalculator() {}
    public record Quality(boolean suitable,String reason,BigDecimal rawDrawdown,BigDecimal drawdownPercent,BigDecimal high) {}
    public static Result calculate(Policy p,Input in){
        Map<String,List<Receipt>> receipts=new LinkedHashMap<>();Map<String,SortedSet<String>> reasons=new LinkedHashMap<>();Map<String,Quality> qualities=new LinkedHashMap<>();
        BigDecimal roundBond=in.bondValue().add(in.budget().reservedBondValue()),roundAssets=in.totalAssets().subtract(in.budget().reservedFees());
        BigDecimal cash=in.totalDeposits().subtract(in.budget().reservedFunds()),factor=inflation(p,in.date());
        boolean cashBase=in.termDeposits().compareTo(p.termFloor().multiply(factor))>=0;
        for(String code:p.symbols()){
            Symbol s=in.symbols().get(code);List<Receipt> r=new ArrayList<>();SortedSet<String>b=new TreeSet<>();receipts.put(code,r);reasons.put(code,b);
            if(s==null){add(r,b,"SOURCE","UNAVAILABLE","SYMBOL_SOURCE_UNAVAILABLE",List.of("assets"),true,Map.of());continue;}
            evidence(r,b,"POSITION",s.position(),List.of("assets"),true);
            evidence(r,b,"PENDING",s.pending(),List.of("pending:"+code),true);
            if("00865B".equals(code))evidence(r,b,"SUBACCOUNTS",in.subaccounts(),List.of("subaccounts"),true);
            evidence(r,b,"RADAR",s.radar(),List.of("radar:"+code),true);
            boolean reverse=Set.of("REDUCE_CANDIDATE","EXIT_CANDIDATE","AVOID").contains(Objects.toString(s.mediumAction(),""))||Set.of("REDUCE_CANDIDATE","EXIT_CANDIDATE","AVOID").contains(Objects.toString(s.mediumCandidateAction(),""));
            add(r,b,"RADAR_DIRECTION",reverse?"BLOCK":"PASS",reverse?"RADAR_BUY_CONFLICT":"RADAR_NON_OPPOSITE",List.of("radar:"+code),true,Map.of());
            evidence(r,b,"VALUATION",s.valuation(),List.of("assets","classifications"),true);
            evidence(r,b,"TRADABILITY",s.tradability(),List.of("tradability:"+code),s.tradability()!=null&&"BLOCK".equals(s.tradability().status()));
            evidence(r,b,"FX",in.fxEvidence(),List.of("fx"),true);
            boolean fx=in.fx()!=null&&in.fx().signum()>0&&in.fx().compareTo(p.fxMax())<=0;
            add(r,b,"FX_CAP",fx?"PASS":"BLOCK",fx?"USD_TWD_WITHIN_CAP":"USD_TWD_OVER_LIMIT_OR_UNVERIFIED",List.of("fx","policy"),true,Map.of());
            evidence(r,b,"BEAR_WINDOW",in.bearWindow(),List.of("bear-window"),in.bearWindow()!=null&&"BLOCK".equals(in.bearWindow().status()));
            boolean quota=in.budget().verified();add(r,b,"DEDUP",quota?"PASS":"UNAVAILABLE",quota?"FILLS_AND_RESERVATIONS_RECONCILED":"DEDUP_SCOPE_UNVERIFIED",List.of("ledger","reservations"),true,Map.of());
            boolean fresh=quoteQualified(p,in,s.quote());add(r,b,"QUOTE",fresh?"PASS":"BLOCK",fresh?early(p,in,s.quote())?"EARLY_TRADED_PRICE_NO_AGE_LIMIT":"LIVE_QUOTE_FRESH":"LIVE_PRICE_UNVERIFIED",List.of("quote:"+code),true,Map.of());
            boolean book=bookQualified(p,in,s.book());add(r,b,"BOOK",book?"PASS":"BLOCK",book?"OPPOSITE_BOOK_VERIFIED":"BEST_FIVE_UNVERIFIED",List.of("book:"+code),true,Map.of());
            Quality q=quality(p,in,s);qualities.put(code,q);
            Map<String,String> values=new TreeMap<>();if(q.rawDrawdown()!=null){values.put("rawDrawdown",decimal(q.rawDrawdown()));values.put("drawdownPercent",decimal(q.drawdownPercent()));values.put("referenceHigh",decimal(q.high()));}
            add(r,b,"D-195",q.suitable()?"PASS":"BLOCK",q.reason(),List.of("quote:"+code,"daily:"+code,"dividends:"+code,"calendar","policy"),true,values);
            boolean target=s.currentValue()!=null&&s.currentValue().signum()>=0&&p.targets().get(code).multiply(in.totalAssets()).compareTo(s.currentValue().add(in.budget().strategicReservedValues().getOrDefault(code,BigDecimal.ZERO)))>0;
            add(r,b,"TARGET",target?"PASS":"BLOCK",target?"BELOW_INDIVIDUAL_TARGET":"TARGET_FULL_OR_UNVERIFIED",List.of("assets","policy"),true,Map.of());
            add(r,b,"FUNDS_FLOOR",cashBase?"PASS":"BLOCK",cashBase?"TERM_DEPOSIT_FLOOR_VERIFIED":"TWD_TERM_DEPOSIT_FLOOR",List.of("assets","policy"),true,Map.of());
            // Intraday evidence is frozen for audit but is explicitly outside D-130's medium horizon.
            add(r,b,"INTRADAY", "NOT_APPLICABLE","HORIZON_NOT_APPLICABLE",List.of("minute:"+code),false,Map.of("scope","SHORT_ONLY"));
        }
        List<String> ranking=p.symbols().stream().filter(c->reasons.get(c).isEmpty())
                .sorted(Comparator.<String,BigDecimal>comparing(c->qualities.get(c).drawdownPercent())
                        .thenComparing(c->in.symbols().get(c).quote().premium())
                        .thenComparing(c->normalizedGap(p,in,c),Comparator.reverseOrder()).thenComparing(c->c)).toList();
        String chosen=ranking.isEmpty()?null:ranking.get(0);List<Candidate> out=new ArrayList<>();
        BigDecimal available=cash.subtract(p.totalFloor().multiply(factor)).max(BigDecimal.ZERO);
        for(String code:p.symbols()){
            int lots=0;BigDecimal limit=null,amount=null;Symbol s=in.symbols().get(code);List<Receipt>r=receipts.get(code);SortedSet<String>b=reasons.get(code);
            if(code.equals(chosen)){
                int quota=Math.max(0,Math.min(p.dailyCap()-in.budget().strategyToday(),p.weeklyCap()-in.budget().strategyWeek()));
                lots=capacity(p,in,s,quota,available,false,roundBond,roundAssets);
                if(lots>0){limit=limit(s,lots);amount=limit.multiply(THOUSAND).multiply(BigDecimal.valueOf(lots));available=available.subtract(cost(p,amount));roundBond=roundBond.add(amount);roundAssets=roundAssets.subtract(cost(p,amount).subtract(amount));}
                add(r,b,"D-130_CAPACITY",lots>0?"PASS":"BLOCK",lots>0?"DAILY_WEEKLY_TARGET_FUNDS_DEPTH_MIN":"CAPACITY_OR_QUOTA_EXHAUSTED",List.of("assets","book:"+code,"ledger","reservations","policy"),true,Map.of("lots",Integer.toString(lots)));
            }else if(b.isEmpty())add(r,b,"D-195_SELECTION","BLOCK","NOT_SELECTED",List.of("policy","quote:"+code),true,Map.of());
            out.add(candidate(code,"STRATEGIC_FUBON",lots,limit,amount,b,r));
        }
        Symbol emergency=in.symbols().get("00865B");List<Receipt>er=new ArrayList<>();SortedSet<String>eb=new TreeSet<>();
        // Reuse every independent shared hard gate except the strategic target and selection.
        for(Receipt r:receipts.getOrDefault("00865B",List.of()))if(!Set.of("TARGET","D-130_CAPACITY","D-195_SELECTION").contains(r.ruleId())){er.add(r);if(Set.of("BLOCK","UNAVAILABLE").contains(r.status())&&!("UNAVAILABLE".equals(r.status())&&Set.of("TRADABILITY","BEAR_WINDOW").contains(r.ruleId())))eb.add(r.reason());}
        int elots=0;BigDecimal elimit=null,eamount=null;Quality eq=qualities.get("00865B");
        BigDecimal gap=in.emergencyValue()==null?null:p.emergencyTarget().multiply(factor).subtract(in.emergencyValue().add(in.budget().emergencyReservedValue()));
        if(eb.isEmpty()&&gap!=null&&gap.signum()>0&&eq!=null&&eq.suitable()){
            int priceCap=eq.drawdownPercent().compareTo(p.emergencyTwoDrawdown().multiply(HUNDRED).negate())<=0?2:1;
            int needed=gap.divide(emergency.quote().price().multiply(THOUSAND),0,RoundingMode.CEILING).intValueExact();
            int cap=Math.max(0,Math.min(needed,Math.min(priceCap,p.emergencyDailyCap()-in.budget().emergencyToday())));
            elots=capacity(p,in,emergency,cap,available,true,roundBond,roundAssets);
            if(elots>0){elimit=limit(emergency,elots);eamount=elimit.multiply(THOUSAND).multiply(BigDecimal.valueOf(elots));}
        }
        add(er,eb,"EMERGENCY_CAPACITY",elots>0?"PASS":"BLOCK",elots>0?"EMERGENCY_SEPARATE_QUOTA_SHARED_FUNDS":"EMERGENCY_TARGET_CAPACITY_OR_EVIDENCE",List.of("assets","subaccounts","reservations","policy","book:00865B"),true,Map.of("lots",Integer.toString(elots)));
        out.add(candidate("00865B","EMERGENCY_CATHAY",elots,elimit,eamount,eb,er));
        return new Result(List.copyOf(out),ranking);
    }
    private static Candidate candidate(String code,String strategy,int lots,BigDecimal limit,BigDecimal amount,SortedSet<String>b,List<Receipt>r){return new Candidate(code,strategy,lots>0?"ACTIONABLE_FOR_REPORT":"BLOCKED",lots>0?"BUY":"NONE",lots,limit,amount,List.copyOf(b),List.copyOf(r));}
    public static Quality quality(Policy p,Input in,Symbol s){
        Quote q=s.quote();if(q==null||q.price()==null||q.price().signum()<=0||q.premium()==null||q.premiumDate()==null||!q.premiumDate().equals(in.date()))return qualityFail("PREMIUM_OR_PRICE_UNVERIFIED");
        if(in.sessions().size()!=p.lookback()||s.closes()==null||s.closes().size()!=p.lookback())return qualityFail("COMPLETED_CLOSES_UNVERIFIED");
        if(!s.distributionVerified()&&!p.nonDistributing().contains(s.code()))return qualityFail("DISTRIBUTION_HISTORY_UNVERIFIED");
        Map<LocalDate,BigDecimal> events=new TreeMap<>();
        for(Distribution d:s.distributions()){
            LocalDate windowStart=in.sessions().getFirst();
            if(d.rightsDate()!=null&&d.rightsDate().isAfter(windowStart)&&!d.rightsDate().isAfter(in.date()))return qualityFail("STOCK_DIVIDEND_ADJUSTMENT_UNSUPPORTED");
            if(d.exDate()!=null&&(!d.exDate().isAfter(windowStart)||d.exDate().isAfter(in.date())))continue;
            if(d.source()==null||d.source().isBlank()||d.exDate()==null||d.cash()==null||d.cash().signum()<0||d.stock()!=null&&d.stock().signum()!=0 )return qualityFail("DISTRIBUTION_HISTORY_UNVERIFIED");
            BigDecimal old=events.putIfAbsent(d.exDate(),d.cash());if(old!=null&&old.compareTo(d.cash())!=0)return qualityFail("DISTRIBUTION_CONFLICT");
        }
        BigDecimal high=null;
        for(int i=0;i<p.lookback();i++){
            Close c=s.closes().get(i);if(!c.date().equals(in.sessions().get(i))||!c.date().isBefore(in.date())||!c.trusted()||c.price()==null||c.price().signum()<=0)return qualityFail("COMPLETED_CLOSES_UNVERIFIED");
            BigDecimal price=c.price();for(var e:events.entrySet())if(e.getKey().isAfter(c.date())&&!e.getKey().isAfter(in.date()))price=price.subtract(e.getValue());
            if(price.signum()<=0)return qualityFail("ADJUSTED_CLOSE_NONPOSITIVE");high=high==null?price:high.max(price);
        }
        BigDecimal raw=q.price().divide(high,MC).subtract(BigDecimal.ONE),percent=raw.multiply(HUNDRED).setScale(4,RoundingMode.HALF_EVEN);
        String reason=q.premium().abs().compareTo(p.pausePremium())>0?"D124_ABS_PREMIUM_OVER_LIMIT":q.premium().compareTo(p.maxPremium())>0?"PREMIUM_ABOVE_SUITABLE_LIMIT":raw.compareTo(p.minDrawdown().negate())>0?"DRAWDOWN_BELOW_SUITABLE_MIN":"DRAWDOWN_AND_PREMIUM_SUITABLE";
        return new Quality("DRAWDOWN_AND_PREMIUM_SUITABLE".equals(reason),reason,raw,percent,high);
    }
    private static Quality qualityFail(String r){return new Quality(false,r,null,null,null);}
    private static int capacity(Policy p,Input in,Symbol s,int cap,BigDecimal available,boolean emergency,BigDecimal roundBond,BigDecimal roundAssets){
        if(s==null||cap<=0)return 0;
        for(int n=cap;n>=1;n--){BigDecimal limit=limit(s,n);if(limit==null)continue;BigDecimal amount=limit.multiply(THOUSAND).multiply(BigDecimal.valueOf(n)),expense=cost(p,amount);if(expense.compareTo(available)>0)continue;
            BigDecimal denominator=roundAssets.subtract(expense.subtract(amount));
            BigDecimal protectedCash=p.totalFloor().multiply(inflation(p,in.date()));
            BigDecimal groupCapacity=p.totalBondCap().multiply(denominator).min(denominator.multiply(BigDecimal.ONE.subtract(p.normalEquityFloor())).subtract(protectedCash)).max(BigDecimal.ZERO);
            if(roundBond.add(amount).compareTo(groupCapacity)>0)continue;
            if(!emergency){if(s.currentValue().add(in.budget().strategicReservedValues().getOrDefault(s.code(),BigDecimal.ZERO)).add(amount).compareTo(p.targets().get(s.code()).multiply(denominator))>0)continue;
                if("00697B".equals(s.code())&&s.currentValue().add(in.budget().strategicReservedValues().getOrDefault(s.code(),BigDecimal.ZERO)).add(amount).compareTo(roundBond.add(amount).multiply(p.intermediateShareCap()))>0)continue;}
            return n;
        }return 0;
    }
    public static BigDecimal limit(Symbol s,int lots){
        if(s.book()==null||s.book().levels()==null)return null;long depth=0;BigDecimal last=null;
        for(Level l:s.book().levels()){depth+=l.askLots();if(depth>=lots){last=l.ask();break;}}
        if(last==null)return null;
        if(s.tradability()==null||!s.tradability().verified())last=s.book().levels().get(0).ask();
        // Taiwan bond ETFs use the ETF price grid, never the ordinary-equity grid.
        BigDecimal tick=last.compareTo(new BigDecimal("50"))<0?new BigDecimal("0.01"):new BigDecimal("0.05");
        return last.divide(tick,0,RoundingMode.FLOOR).multiply(tick);
    }
    static boolean quoteQualified(Policy p,Input in,Quote q){return q!=null&&q.price()!=null&&q.price().signum()>0&&in.date().equals(q.date())&&q.updatedAt()!=null&&!q.updatedAt().isAfter(in.capturedAt())&&q.updatedAt().atZone(ZoneId.of("Asia/Taipei")).toLocalDate().equals(in.date())&&q.source()!=null&&!q.source().isBlank()&&Boolean.FALSE.equals(q.closed())&&"LIVE".equals(q.status())&&(early(p,in,q)||Duration.between(q.updatedAt(),in.capturedAt()).compareTo(Duration.ofMinutes(p.acquisitionMinutes()))<=0);}
    private static boolean early(Policy p,Input in,Quote q){return "09:05".equals(in.slot())&&q.volume()!=null&&q.volume()>0&&q.volume()<p.earlyVolumeBelow()&&q.previousClose()!=null&&q.previousClose().signum()>0&&q.price()!=null&&q.price().compareTo(q.previousClose())!=0;}
    static boolean bookQualified(Policy p,Input in,Book b){if(b==null||!b.valid()||!in.date().equals(b.date())||b.sourceTime()==null||b.fetchedAt()==null||!Set.of("FUBON_BOOKS","YAHOO_TW").contains(Objects.toString(b.source(),""))||b.sourceTime().isAfter(in.capturedAt())||b.fetchedAt().isAfter(in.capturedAt())||Duration.between(b.sourceTime(),in.capturedAt()).compareTo(Duration.ofMinutes(p.acquisitionMinutes()))>0||b.levels()==null||b.levels().size()!=5)return false;
        BigDecimal bid=null,ask=null;for(Level l:b.levels()){if(l.bid()==null||l.ask()==null||l.bid().signum()<=0||l.ask().signum()<=0||l.bidLots()<0||l.askLots()<0||l.bid().compareTo(l.ask())>0||bid!=null&&l.bid().compareTo(bid)>=0||ask!=null&&l.ask().compareTo(ask)<=0)return false;bid=l.bid();ask=l.ask();}return true;}
    private static BigDecimal normalizedGap(Policy p,Input in,String c){BigDecimal target=p.targets().get(c).multiply(in.totalAssets());return target.subtract(in.symbols().get(c).currentValue().add(in.budget().strategicReservedValues().getOrDefault(c,BigDecimal.ZERO))).divide(target,MC);}
    private static BigDecimal cost(Policy p,BigDecimal amount){return amount.add(amount.multiply(p.feeRate()).setScale(2,RoundingMode.CEILING));}
    public static BigDecimal inflation(Policy p,LocalDate date){return BigDecimal.valueOf(StrictMath.pow(BigDecimal.ONE.add(p.inflation()).doubleValue(),ChronoUnit.DAYS.between(p.baseDate(),date)/365.25));}
    public static String decimal(BigDecimal d){return d.signum()==0?"0":d.stripTrailingZeros().toPlainString();}
    private static void evidence(List<Receipt> r,SortedSet<String>b,String id,Evidence e,List<String>refs,boolean block){add(r,b,id,e==null?"UNAVAILABLE":e.status(),e==null?id+"_UNVERIFIED":e.reason(),refs,block,Map.of());}
    private static void add(List<Receipt>r,SortedSet<String>b,String id,String status,String reason,List<String>refs,boolean block,Map<String,String>v){r.add(new Receipt(id,VERSION,refs,status,reason,v));if(block&&Set.of("BLOCK","UNAVAILABLE").contains(status))b.add(reason);}
}
