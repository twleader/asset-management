package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Pure D218 replay. Caller verifies canonical ownership, calendars and source provenance. */
public final class SrppDividendStabilization {
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private SrppDividendStabilization() {}

    public static ObjectNode config() {
        ObjectNode n=F.objectNode();n.put("decision","D-218").put("enabled",true);
        n.putArray("symbols").add("0056").add("00713").add("00878");
        n.put("completed_no_new_low_sessions",2).put("requires_five_day_ma",false)
            .put("first_cumulative_budget_fraction","0.10").put("retest_cumulative_budget_fraction","0.30")
            .put("follow_cumulative_budget_fraction","1.00").put("follow_requires_monthly_above",true)
            .put("follow_requires_k_above_d",true).put("same_wave_first_once",true)
            .put("same_wave_retest_once",true).put("freeze_on_anchor_break",true)
            .put("new_wave_requires_closed_prior_evidence",true);return n;
    }

    /** Exactly the D198 20-session market window; never opens a wave or grants a budget. */
    public static ObjectNode observe(JsonNode fact,JsonNode config) {
        ObjectNode out=base(fact,true);
        try {
            check(config().equals(config),"D218_POLICY_UNVERIFIED");scope(fact);
            List<String> sessions=sessions(fact,20,null,"D218_OBSERVATION_CALENDAR_UNVERIFIED");
            List<Price> prices=prices(fact,sessions,null);
            Price anchor=minimum(prices);int count=countAfter(prices,anchor.date());
            out.put("status","OBSERVATION_ONLY").put("reason",count>=2?"MARKET_TWO_SESSION_OBSERVATION":"WAIT_TWO_COMPLETED_SESSIONS")
                .put("observation_anchor_date",anchor.date()).put("observation_anchor_low_raw",d(anchor.rawLow()))
                .put("anchor_low",d(anchor.low())).put("completed_no_new_low_sessions",count)
                .put("stabilized",count>=2).put("latest_completed_date",sessions.getLast());
        } catch(RuntimeException invalid) {out.put("reason",reason(invalid,"D218_MARKET_UNVERIFIED"));}
        return out;
    }

    public static ObjectNode evaluate(JsonNode fact,JsonNode state,JsonNode config) {
        ObjectNode out=base(fact,false);
        try {
            check(config().equals(config),"D218_POLICY_UNVERIFIED");scope(fact);
            check(state!=null&&state.isObject(),"D218_INPUT_UNVERIFIED");
            String code=fact.path("stock_code").asText(),date=day(fact.path("trading_date")),completed=day(fact.path("completed_session"));
            check(code.equals(state.path("stock_code").asText())&&"台股".equals(state.path("market").asText()),"D218_WAVE_IDENTITY_UNVERIFIED");
            String wave=text(state.path("wave_id"),"D218_HISTORY_UNVERIFIED"),anchorDate=day(state.path("anchor_date"));
            BigDecimal anchorRaw=number(state.path("anchor_low_raw"),true),budget=number(state.path("budget_twd"),true);
            check("TW_SPLIT_STOCK_PRICE".equals(state.path("price_basis").asText()),"D218_PRICE_BASIS_UNVERIFIED");
            check(isTrue(state.path("history_complete"))&&isTrue(state.path("wave_history_complete")),"D218_HISTORY_UNVERIFIED");
            check(state.path("frozen").isBoolean()&&day(state.path("coverage_start_date")).compareTo(anchorDate)<=0&&day(state.path("coverage_end_date")).equals(date),"D218_HISTORY_COVERAGE_UNVERIFIED");
            text(state.path("approval_evidence_ref"),"D218_APPROVAL_EVIDENCE_REQUIRED");
            JsonNode prior=state.get("prior_wave_closed_evidence");check(prior!=null&&(prior.isNull()||prior.isTextual()&&!prior.asText().isBlank()),"D218_PRIOR_WAVE_UNVERIFIED");
            List<String> sessions=sessions(fact,0,anchorDate,"D218_COMPLETED_CALENDAR_UNVERIFIED");
            List<Price> prices=prices(fact,sessions,anchorRaw);
            BigDecimal current=number(fact.path("current_price"),true),currentLow=number(fact.path("current_low"),true);
            check(currentLow.compareTo(current)<=0,"D218_CURRENT_LOW_UNVERIFIED");
            List<Record> fills=new ArrayList<>(),pending=new ArrayList<>();Set<String>ids=new HashSet<>();
            for(String key:List.of("confirmed_fills","pending_orders")) {
                JsonNode rows=state.path(key);check(rows.isArray(),"D218_HISTORY_UNVERIFIED");
                for(JsonNode row:rows) {
                    String id=text(row.path("id"),"D218_HISTORY_UNVERIFIED"),rd=day(row.path("date"));
                    check(ids.add(id)&&wave.equals(row.path("wave_id").asText())&&rd.compareTo(anchorDate)>=0&&rd.compareTo(date)<=0,"D218_HISTORY_IDENTITY_CONFLICT");
                    check(sessions.contains(rd)||rd.equals(date),"D218_FILL_SESSION_UNVERIFIED");
                    BigDecimal amount=number(row.path("amount_twd"),true);String kind=row.path("kind").asText(),stage=row.path("stage").asText();
                    check(Set.of("STABILIZATION","DCA","CORE_TOPUP").contains(kind),"D218_HISTORY_KIND_UNVERIFIED");
                    check(kind.equals("STABILIZATION")?Set.of("FIRST","RETEST","FOLLOW").contains(stage):row.has("stage")&&row.path("stage").isNull(),"D218_HISTORY_STAGE_UNVERIFIED");
                    if(stage.equals("FIRST")) {
                        text(row.path("evidence_ref"),"D218_FIRST_SOURCE_REQUIRED");
                        String fixedDate=day(row.path("observation_anchor_date"));BigDecimal fixedRaw=number(row.path("observation_anchor_low_raw"),true);
                        check(fixedDate.compareTo(anchorDate)>=0&&fixedDate.compareTo(rd)<0&&sessions.contains(fixedDate),"D218_FIRST_ANCHOR_UNVERIFIED");
                        List<Price> before=prices.stream().filter(p->p.date().compareTo(rd)<0).toList();
                        Price fixed=before.stream().filter(p->p.date().equals(fixedDate)).findFirst().orElseThrow(()->bad("D218_FIRST_ANCHOR_UNVERIFIED"));
                        check(!before.isEmpty()&&fixed.rawLow().compareTo(fixedRaw)==0,"D218_FIRST_ANCHOR_UNVERIFIED");
                        Price observed=minimum(before);check(observed.date().equals(fixedDate)&&countAfter(before,fixedDate)>=2,"D218_FIRST_TIMING_UNVERIFIED");
                    }
                    (key.equals("confirmed_fills")?fills:pending).add(new Record(row,amount,rd,kind,stage));
                }
            }
            BigDecimal used=ZERO;for(Record r:fills)used=used.add(r.amount());for(Record r:pending)used=used.add(r.amount());
            out.put("wave_id",wave).put("used_twd",d(used));
            List<Record> first=stage(fills,"FIRST"),retest=stage(fills,"RETEST"),follow=stage(fills,"FOLLOW");
            check(retest.isEmpty()||!first.isEmpty()&&earliest(retest).compareTo(earliest(first))>0,"D218_STAGE_SEQUENCE_UNVERIFIED");
            check(follow.isEmpty()||!retest.isEmpty()&&earliest(follow).compareTo(earliest(retest))>0,"D218_STAGE_SEQUENCE_UNVERIFIED");
            List<Record> fixed=new ArrayList<>(first);fixed.addAll(stage(pending,"FIRST"));
            String observedDate;BigDecimal observedRaw,observedLow;
            if(!fixed.isEmpty()) {
                Record r=fixed.getFirst();observedDate=day(r.row().path("observation_anchor_date"));observedRaw=number(r.row().path("observation_anchor_low_raw"),true);
                for(Record other:fixed)check(observedDate.equals(other.row().path("observation_anchor_date").asText())&&observedRaw.compareTo(number(other.row().path("observation_anchor_low_raw"),true))==0,"D218_FIRST_ANCHOR_CONFLICT");
                observedLow=adjust(observedDate,observedRaw,cash(fact,sessions.getFirst()));
            } else {Price observed=minimum(prices);observedDate=observed.date();observedRaw=observed.rawLow();observedLow=observed.low();}
            int count=countAfter(prices,observedDate);out.put("anchor_date",observedDate).put("anchor_low",d(observedLow))
                .put("observation_anchor_date",observedDate).put("observation_anchor_low_raw",d(observedRaw))
                .put("completed_no_new_low_sessions",count).put("latest_completed_date",completed);
            boolean broken=currentLow.compareTo(observedLow)<0;
            for(Price p:prices)if(p.date().compareTo(observedDate)>0&&p.low().compareTo(observedLow)<0)broken=true;
            out.put("stabilized",count>=2&&!broken&&!state.path("frozen").asBoolean());
            if(state.path("frozen").asBoolean()||!fixed.isEmpty()&&broken)return finish(out,"FROZEN","FROZEN_ANCHOR_BREAK");
            if(fixed.isEmpty()&&currentLow.compareTo(observedLow)<0) {
                out.put("observation_anchor_date",date).put("observation_anchor_low_raw",d(currentLow))
                    .put("anchor_date",date).put("anchor_low",d(currentLow)).put("completed_no_new_low_sessions",0);
                return finish(out,"WAIT","WAIT_TWO_COMPLETED_SESSIONS");
            }
            if(count<2)return finish(out,"WAIT","WAIT_TWO_COMPLETED_SESSIONS");
            if(!pending.isEmpty())return finish(out,"WAIT","WAIT_PENDING_ORDER");
            String stage;BigDecimal fraction;
            if(first.isEmpty()) {stage="FIRST";fraction=number(config.path("first_cumulative_budget_fraction"),false);}
            else if(retest.isEmpty()) {
                String firstDate=earliest(first);boolean higher=false,retested=false;
                for(int i=0;i<prices.size();i++) {Price p=prices.get(i);if(p.date().compareTo(firstDate)<=0)continue;if(p.date().equals(completed))break;
                    if(higher&&i>0&&p.low().compareTo(observedLow)>=0&&p.low().compareTo(prices.get(i-1).low())<0)retested=true;
                    if(p.low().compareTo(observedLow)>0)higher=true;
                }
                if(!retested||prices.size()<2||prices.getLast().close().compareTo(prices.get(prices.size()-2).close())<=0)return finish(out,"WAIT","WAIT_RETEST_CONFIRMATION");
                stage="RETEST";fraction=number(config.path("retest_cumulative_budget_fraction"),false);
            } else {
                JsonNode radar=fact.path("radar");BigDecimal k=number(radar.path("k"),false),dd=number(radar.path("d"),false);
                check(k.compareTo(new BigDecimal("100"))<=0&&dd.compareTo(new BigDecimal("100"))<=0,"D218_RADAR_UNVERIFIED");
                if(!"ABOVE".equals(radar.path("monthly_confirmation").asText())||k.compareTo(dd)<=0)return finish(out,"WAIT","WAIT_FOLLOW_TREND");
                stage="FOLLOW";fraction=number(config.path("follow_cumulative_budget_fraction"),false);
            }
            BigDecimal ceiling=budget.multiply(fraction).subtract(used).max(ZERO);
            if(stage.equals("RETEST"))ceiling=ceiling.min(budget.multiply(number(config.path("retest_cumulative_budget_fraction"),false).subtract(number(config.path("first_cumulative_budget_fraction"),false))));
            out.put("stage",stage).put("cumulative_fraction",d(fraction)).put("budget_ceiling_twd",d(ceiling));
            return finish(out,ceiling.signum()>0?"AVAILABLE":"WAIT",ceiling.signum()>0?"STABILIZATION_BUDGET_AVAILABLE":"CUMULATIVE_BUDGET_EXHAUSTED");
        } catch(RuntimeException invalid) {return finish(out,"UNVERIFIED",reason(invalid,"D218_INPUT_UNVERIFIED"));}
    }

    private record Price(String date,BigDecimal low,BigDecimal close,BigDecimal rawLow) {}
    private record Record(JsonNode row,BigDecimal amount,String date,String kind,String stage) {}
    private static List<Record> stage(List<Record> rows,String stage){return rows.stream().filter(r->r.kind().equals("STABILIZATION")&&r.stage().equals(stage)).toList();}
    private static String earliest(List<Record> rows){return rows.stream().map(Record::date).min(String::compareTo).orElseThrow();}
    private static Price minimum(List<Price> prices){return prices.stream().min(Comparator.comparing(Price::low).thenComparing(Price::date)).orElseThrow();}
    private static int countAfter(List<Price> prices,String date){return (int)prices.stream().filter(p->p.date().compareTo(date)>0).count();}
    private static List<String> sessions(JsonNode fact,int count,String anchor,String reason){
        String date=day(fact.path("trading_date")),completed=day(fact.path("completed_session"));JsonNode raw=fact.path("completed_sessions");
        check(raw.isArray()&&!raw.isEmpty()&&(count==0||raw.size()==count),reason);List<String>out=new ArrayList<>();String prior=null;
        for(JsonNode n:raw){String d=day(n);check(prior==null||d.compareTo(prior)>0,reason);out.add(d);prior=d;}
        check(out.getLast().equals(completed)&&completed.compareTo(date)<0&&(anchor==null||out.getFirst().equals(anchor)),reason);return out;
    }
    private static List<Price> prices(JsonNode fact,List<String> sessions,BigDecimal anchor){
        JsonNode rows=fact.path("rows");check(rows.isArray()&&rows.size()==sessions.size(),"D218_COMPLETED_ROWS_UNVERIFIED");
        Map<String,BigDecimal>cash=cash(fact,sessions.getFirst());List<Price>out=new ArrayList<>();
        for(int i=0;i<sessions.size();i++){JsonNode row=rows.get(i);check(sessions.get(i).equals(row.path("date").asText()),"D218_COMPLETED_ROWS_UNVERIFIED");
            BigDecimal low=number(row.path("low"),true),close=number(row.path("close"),true);check(low.compareTo(close)<=0&&(i!=0||anchor==null||low.compareTo(anchor)==0),"D218_ANCHOR_OR_OHLC_UNVERIFIED");
            out.add(new Price(sessions.get(i),adjust(sessions.get(i),low,cash),adjust(sessions.get(i),close,cash),low));}return out;
    }
    private static Map<String,BigDecimal> cash(JsonNode fact,String anchor){
        JsonNode events=fact.path("cash_events");check(events.isArray(),"D218_CASH_EVENTS_UNVERIFIED");String date=day(fact.path("trading_date"));Map<String,BigDecimal>out=new TreeMap<>();
        for(JsonNode e:events){String ex=day(e.path("date"));BigDecimal amount=number(e.path("amount"),false);check(ex.compareTo(anchor)>0&&ex.compareTo(date)<=0,"D218_CASH_EVENT_WINDOW_UNVERIFIED");
            BigDecimal previous=out.putIfAbsent(ex,amount);check(previous==null||previous.compareTo(amount)==0,"D218_CASH_EVENTS_CONFLICT");}return out;
    }
    private static BigDecimal adjust(String date,BigDecimal value,Map<String,BigDecimal>cash){BigDecimal out=value;for(var e:cash.entrySet())if(e.getKey().compareTo(date)>0)out=out.subtract(e.getValue());check(out.signum()>0,"D218_ADJUSTED_PRICE_UNVERIFIED");return out;}
    private static void scope(JsonNode fact){check(fact!=null&&fact.isObject()&&Set.of("0056","00713","00878").contains(fact.path("stock_code").asText())&&"台股".equals(fact.path("market").asText()),"D218_SCOPE_UNVERIFIED");}
    private static ObjectNode base(JsonNode fact,boolean observation){ObjectNode n=F.objectNode();n.put("decision","D-218").put("status","UNVERIFIED").put("reason",observation?"D218_MARKET_UNVERIFIED":"D218_INPUT_UNVERIFIED");
        if(fact!=null&&fact.path("stock_code").isTextual())n.set("stock_code",fact.path("stock_code"));else n.putNull("stock_code");
        for(String key:List.of("stage","wave_id","anchor_low","observation_anchor_date","observation_anchor_low_raw","latest_completed_date"))n.putNull(key);
        if(observation)n.put("observation_only",true);else n.putNull("anchor_date").put("used_twd","0").put("cumulative_fraction","0");
        n.put("stabilized",false).put("completed_no_new_low_sessions",0).put("budget_ceiling_twd","0").put("places_orders",false).put("trade_authorization",false);return n;}
    private static ObjectNode finish(ObjectNode n,String status,String reason){return n.put("status",status).put("reason",reason);}
    private static boolean isTrue(JsonNode n){return n.isBoolean()&&n.booleanValue();}
    private static String text(JsonNode n,String reason){check(n.isTextual()&&!n.asText().isBlank(),reason);return n.asText();}
    private static String day(JsonNode n){String s=text(n,"D218_DATE_UNVERIFIED");try{check(LocalDate.parse(s).toString().equals(s),"D218_DATE_UNVERIFIED");return s;}catch(RuntimeException invalid){throw bad("D218_DATE_UNVERIFIED");}}
    private static BigDecimal number(JsonNode n,boolean positive){check(n.isTextual()||n.isNumber(),"D218_NUMBER_UNVERIFIED");try{BigDecimal v=new BigDecimal(n.asText());check(positive?v.signum()>0:v.signum()>=0,"D218_NUMBER_UNVERIFIED");return v;}catch(NumberFormatException invalid){throw bad("D218_NUMBER_UNVERIFIED");}}
    private static String d(BigDecimal v){return v.signum()==0?"0":v.stripTrailingZeros().toPlainString();}
    private static void check(boolean valid,String reason){if(!valid)throw bad(reason);}
    private static IllegalArgumentException bad(String reason){return new IllegalArgumentException(reason);}
    private static String reason(RuntimeException e,String fallback){String s=e.getMessage();return e instanceof IllegalArgumentException&&s!=null&&s.startsWith("D218_")?s:fallback;}
}
