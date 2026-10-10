package com.steven.assets.service.srpp.decision;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.srpp.SrppJcs;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import static com.steven.assets.service.srpp.decision.DecisionFacts.*;

/** Full immutable authored policy bytes; Swagger is independently identified per D-166. */
@Component
public class DecisionPolicyAuthority {
    private final String modelBytes,manifestBytes;
    private final Policy policy;
    public DecisionPolicyAuthority(){this(resource("model_inputs.json"),resource("policy_manifest.json"));}
    DecisionPolicyAuthority(String modelBytes,String manifestBytes){
        this.modelBytes=modelBytes;this.manifestBytes=manifestBytes;
        try {
            ObjectMapper mapper=new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
            JsonNode model=mapper.readTree(modelBytes),manifest=SrppJcs.parseStrict(manifestBytes);
            String modelHash=SrppJcs.sha256Hex(modelBytes),bundle=manifest.path("POLICY_BUNDLE_SHA256").asText();
            ObjectNode body=((ObjectNode)manifest).deepCopy();body.remove("POLICY_BUNDLE_SHA256");
            if(!bundle.equals(SrppJcs.hash(body))||!modelHash.equals(manifest.path("files").path("assumptions/model_inputs.json").asText()))throw new IllegalArgumentException("POLICY_BYTES_MISMATCH");
            for(String key:List.of("decision","policy_version","model_version"))if(!model.path(key).asText().equals(manifest.path(key).asText()))throw new IllegalArgumentException("POLICY_VERSION_MISMATCH");
            JsonNode e=model.path("execution_policy"),d=e.path("bond_price_selection_d195"),fund=e.path("stock_purchase_funding_policy_d150"),fresh=e.path("bond_trade_price_policy").path("quote_freshness_policy");
            List<String> symbols=new ArrayList<>();d.path("strategic_symbols").forEach(v->symbols.add(v.asText()));
            Map<String,BigDecimal> targets=new TreeMap<>();symbols.forEach(s->targets.put(s,num(model.path("detailed_target_allocation_2027"),s)));
            Set<String> non=new TreeSet<>();e.path("daily_topup_tier_policy").path("non_distributing_symbols").forEach(v->non.add(v.asText()));
            BigDecimal fx=num(model.path("simulation_contract").path("ordinary_rebalancing_proxy"),"fx_upper_bound");
            if(!e.path("bond_purchase_cadence").path("pause_conditions").toString().contains("usd_twd_over_"+fx.toPlainString()))throw new IllegalArgumentException("FX_POLICY_MISMATCH");
            policy=new Policy(bundle,modelHash,SrppJcs.sha256Hex(manifestBytes),model.path("decision").asText(),model.path("policy_version").asText(),model.path("model_version").asText(),List.copyOf(symbols),Map.copyOf(targets),d.path("lookback_completed_sessions").asInt(),num(d,"suitable_min_drawdown_from_adjusted_high"),num(d,"max_premium_discount_pct"),num(d,"premium_pause_abs_pct"),d.path("strategic_max_lots_per_day").asInt(),d.path("strategic_weekly_total_lot_cap").asInt(),d.path("emergency_max_lots_per_day").asInt(),num(d,"emergency_two_lot_drawdown_from_adjusted_high"),num(e.path("emergency_reserve_policy_d193"),"reserve_etf_target_twd_2026_real"),num(fund,"twd_term_deposit_floor_twd"),num(fund,"twd_total_deposit_floor_twd"),num(e,"annual_inflation_rate"),LocalDate.parse(e.path("twd_policy_amount_basis_d150").path("base_date").asText()),num(e.path("tactical_profit_policy"),"broker_commission_max_per_leg"),fx,num(e.path("dynamic_allocation_policy_d141"),"intermediate_bond_max_share_of_bonds"),num(e.path("dynamic_allocation_policy_d141"),"total_bond_cap"),num(e.path("dynamic_allocation_policy_d141"),"normal_equity_floor"),Set.copyOf(non),fresh.path("default_acquisition_max_minutes").asInt(),fresh.path("default_final_limit_max_minutes").asInt(),fresh.path("early_volume_below_lots").asInt());
            if(policy.symbols().size()!=3||policy.lookback()<1||policy.dailyCap()<1||policy.weeklyCap()<policy.dailyCap())throw new IllegalArgumentException("POLICY_PARAMETERS_INVALID");
        }catch(Exception invalid){throw new IllegalStateException("Immutable SRPP decision authority failed verification",invalid);}
    }
    public Policy policy(){return policy;}
    public String modelBytes(){return modelBytes;}
    public String manifestBytes(){return manifestBytes;}
    private static BigDecimal num(JsonNode n,String key){JsonNode v=n.get(key);if(v==null||!v.isNumber())throw new IllegalArgumentException(key);return v.decimalValue().stripTrailingZeros();}
    private static String resource(String name){try(var in=DecisionPolicyAuthority.class.getResourceAsStream("/srpp/decision/"+name)){if(in==null)throw new IllegalArgumentException("Missing decision authority");return new String(in.readAllBytes(),StandardCharsets.UTF_8);}catch(Exception e){throw new IllegalStateException(e);}}
}
