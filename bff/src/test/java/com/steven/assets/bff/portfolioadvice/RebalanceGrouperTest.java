package com.steven.assets.bff.portfolioadvice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link RebalanceGrouper}（Task 344.23(1)）：再平衡明細的分組／分段／排序改由 BFF 預先算好，
 * 前端只 render。純函式，不需要起 Spring context 或 mock WebClient。
 *
 * <p>覆蓋任務檔列的 (i)～(v)：段落順序由 {@code subAllocations} 推導、無子類別者置末、
 * 混合形狀的 {@code header: null}、三種降級形狀不拋例外且輸出空陣列、推導清單以外的子類別不被丟棄。
 */
class RebalanceGrouperTest {

    private static final String OVERALL = "整體";

    // ---- fixtures ---------------------------------------------------------

    private static Map<String, Object> row(String assetClass, String subClass, String holding) {
        Map<String, Object> m = new HashMap<>();
        m.put("assetClass", assetClass);
        m.put("subClass", subClass);
        m.put("holding", holding);
        m.put("action", OVERALL.equals(holding) ? "SELL" : "BUY");
        return m;
    }

    /** 一筆 targetAllocation；{@code subClasses} 為 null 代表該筆的 {@code subAllocations} 是 null 鍵（llm 檔位形狀）。 */
    private static Map<String, Object> target(String assetClass, List<String> subClasses) {
        Map<String, Object> t = new HashMap<>();
        t.put("assetClass", assetClass);
        if (subClasses == null) {
            t.put("subAllocations", null);
        } else {
            List<Map<String, Object>> subs = new ArrayList<>();
            for (String sc : subClasses) {
                Map<String, Object> s = new HashMap<>();
                s.put("subClass", sc);
                subs.add(s);
            }
            t.put("subAllocations", subs);
        }
        return t;
    }

    /** 引擎產生的正規子類別順序（本測試自己列出，來源是 fixture 的 subAllocations，不是被測程式的常數）。 */
    private static final List<String> ENGINE_SUBCLASSES =
            List.of("成長型", "收益型（高股息）", "短期債", "中期債", "長期債");

    private static Map<String, Object> latest(List<Map<String, Object>> plan, Object targetAllocation) {
        Map<String, Object> m = new HashMap<>();
        m.put("rebalancePlan", plan);
        m.put("targetAllocation", targetAllocation);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> sectionSubClasses(Map<String, Object> group) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> s : (List<Map<String, Object>>) group.get("sections")) {
            out.add(s.get("subClass"));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> sectionHoldings(Map<String, Object> group, int sectionIndex) {
        List<Map<String, Object>> sections = (List<Map<String, Object>>) group.get("sections");
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> r : (List<Map<String, Object>>) sections.get(sectionIndex).get("rows")) {
            out.add(r.get("holding"));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static int totalRows(List<Map<String, Object>> groups) {
        int n = 0;
        for (Map<String, Object> g : groups) {
            if (g.get("header") != null) n++;
            for (Map<String, Object> s : (List<Map<String, Object>>) g.get("sections")) {
                n += ((List<Map<String, Object>>) s.get("rows")).size();
            }
        }
        return n;
    }

    // ---- (i) 段落順序由 subAllocations 推導 --------------------------------

    @Test
    @DisplayName("(i) 段落順序由同一份回應的 subAllocations 出現順序推導，不受明細列本身的順序影響")
    void 段落順序依subAllocations推導() {
        // 明細列刻意「反序」給進來，段落仍須照 subAllocations 的出現順序排
        List<String> reversed = new ArrayList<>(ENGINE_SUBCLASSES);
        Collections.reverse(reversed);
        List<Map<String, Object>> plan = new ArrayList<>();
        plan.add(row("股票", null, OVERALL));
        for (String sc : reversed) plan.add(row("股票", sc, "H-" + sc));

        List<Map<String, Object>> groups = RebalanceGrouper.group(
                latest(plan, List.of(target("股票", ENGINE_SUBCLASSES))));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).get("assetClass")).isEqualTo("股票");
        assertThat(groups.get(0).get("header")).isSameAs(plan.get(0));
        assertThat(sectionSubClasses(groups.get(0)))
                .as("段落順序＝subAllocations 的出現順序，非明細列順序、非寫死清單")
                .containsExactlyElementsOf(ENGINE_SUBCLASSES);
        assertThat(totalRows(groups)).as("沒有任何一列被丟棄").isEqualTo(plan.size());
    }

    @Test
    @DisplayName("(i-b) rows 為空的段落不輸出；groups 依 assetClass 在 rebalancePlan 的首次出現序排列")
    void 空段落不輸出且群組依首次出現序() {
        // 三桶都有類別層級列，但只有「股票」有子類別明細；「信託基金」只有整體列（本任務落地前的舊資料形狀）
        List<Map<String, Object>> plan = List.of(
                row("存款", null, OVERALL),
                row("股票", null, OVERALL),
                row("股票", "短期債", "00679B"),
                row("信託基金", null, OVERALL),
                row("存款", null, "台銀活存"));

        List<Map<String, Object>> groups = RebalanceGrouper.group(
                latest(plan, List.of(target("股票", ENGINE_SUBCLASSES), target("信託基金", ENGINE_SUBCLASSES))));

        assertThat(groups).extracting(g -> g.get("assetClass"))
                .as("依 rebalancePlan 首次出現序：存款 → 股票 → 信託基金")
                .containsExactly("存款", "股票", "信託基金");
        assertThat(sectionSubClasses(groups.get(1)))
                .as("五個子類別只有一個有列，其餘四個空段落不得輸出")
                .containsExactly("短期債");
        assertThat(groups.get(2).get("sections"))
                .as("只有整體列的群組 sections 為空清單（畫面上就是原本的單列）")
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).isEmpty();
        assertThat(totalRows(groups)).isEqualTo(plan.size());
    }

    // ---- (ii) subClass 為 null 者集中且置末 --------------------------------

    @Test
    @DisplayName("(ii) subClass 為 null／空白者合為單一無小標段落並置於最後")
    void 無子類別者集中置末() {
        List<Map<String, Object>> plan = List.of(
                row("存款", null, OVERALL),
                row("存款", null, "台銀-活期存款"),
                row("存款", "長期債", "誤植子類別的一列"), // 讓「置末」這件事有東西可比
                row("存款", "", "空字串視同無子類別"),
                row("存款", null, "兆豐-定期存款"));

        List<Map<String, Object>> groups = RebalanceGrouper.group(
                latest(plan, List.of(target("股票", ENGINE_SUBCLASSES))));

        assertThat(groups).hasSize(1);
        assertThat(sectionSubClasses(groups.get(0)))
                .as("有小標的段落在前，無小標段落恆為最後一段")
                .containsExactly("長期債", null);
        assertThat(sectionHoldings(groups.get(0), 1))
                .as("null 與空字串併入同一段，且維持原順序")
                .containsExactly("台銀-活期存款", "空字串視同無子類別", "兆豐-定期存款");
        assertThat(totalRows(groups)).isEqualTo(plan.size());
    }

    // ---- (iii) 混合形狀：沒有整體列的 assetClass → header: null -------------

    @Test
    @DisplayName("(iii) 混合形狀下，沒有「整體」列的 assetClass 群組 header 為 null 且其列不被丟棄")
    void 沒有整體列的群組header為null() {
        List<Map<String, Object>> plan = List.of(
                row("股票", null, OVERALL),
                row("股票", "成長型", "2330"),
                row("海外股票", null, "VOO"),  // 這一桶沒有類別層級列
                row("海外股票", null, "QQQ"));

        List<Map<String, Object>> groups = RebalanceGrouper.group(
                latest(plan, List.of(target("股票", ENGINE_SUBCLASSES))));

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).get("header")).as("有整體列者 header 為該列").isNotNull();
        assertThat(groups.get(1).get("assetClass")).isEqualTo("海外股票");
        assertThat(groups.get(1).get("header")).as("沒有整體列者 header 為 null").isNull();
        assertThat(sectionHoldings(groups.get(1), 0)).containsExactly("VOO", "QQQ");
        assertThat(totalRows(groups)).isEqualTo(plan.size());
    }

    @Test
    @DisplayName("整份 rebalancePlan 都沒有「整體」列（llm 檔位）→ 空陣列，不得產生一堆 header:null 的群組")
    void llm形狀整份無整體列輸出空陣列() {
        // llm 交錯輸出：2330 → VOO → 0050，依 assetClass 重排會牴觸「llm 檔位維持現況渲染」
        List<Map<String, Object>> plan = List.of(
                row("台股", null, "2330"),
                row("海外股票", null, "VOO"),
                row("台股", null, "0050"));

        assertThat(RebalanceGrouper.group(latest(plan, null)))
                .as("回空陣列讓前端走扁平 fallback、逐字維持現況、不重排 LLM 的輸出順序")
                .isEmpty();
    }

    // ---- (iv) 三種降級形狀：不拋例外且輸出空陣列 ---------------------------

    @Test
    @DisplayName("(iv) latest 為空 Map（下游失敗降級）→ 不拋例外且輸出空陣列")
    void latest為空Map() {
        assertThatCode(() -> {
            // Collections.emptyMap() 正是 controller 的 onErrorReturn 降級值（不可變）
            assertThat(RebalanceGrouper.group(Collections.emptyMap())).isEmpty();
            assertThat(RebalanceGrouper.group(null)).isEmpty();
            assertThat(RebalanceGrouper.group(latest(null, null))).isEmpty();
            assertThat(RebalanceGrouper.group(latest(List.of(), List.of()))).isEmpty();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("(iv) targetAllocation 缺漏、為 null、非清單 → 不拋例外且輸出空陣列（搭配 llm 形狀的 plan）")
    void targetAllocation缺漏或為null() {
        List<Map<String, Object>> llmPlan = List.of(row("台股", null, "2330"));

        Map<String, Object> missing = new HashMap<>();
        missing.put("rebalancePlan", llmPlan); // 完全沒有 targetAllocation 鍵

        Map<String, Object> nullKey = latest(llmPlan, null); // 有鍵但值為 null

        Map<String, Object> notAList = latest(llmPlan, "unexpected"); // 形狀異常也不得炸

        assertThatCode(() -> {
            assertThat(RebalanceGrouper.group(missing)).isEmpty();
            assertThat(RebalanceGrouper.group(nullKey)).isEmpty();
            assertThat(RebalanceGrouper.group(notAList)).isEmpty();
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("(iv) subAllocations 為 null 鍵（llm 檔位序列化，非缺鍵）→ 不拋例外且輸出空陣列")
    void subAllocations為null鍵() {
        List<Map<String, Object>> llmPlan = List.of(row("台股", null, "2330"));
        Map<String, Object> withNullSubs = latest(llmPlan, Arrays.asList(target("台股", null)));

        assertThatCode(() -> assertThat(RebalanceGrouper.group(withNullSubs)).isEmpty())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("(iv-b) 推導不到任何順序但有「整體」列時仍不拋例外，明細改依出現順序分段、不丟列")
    void 推導不到順序時退回出現順序() {
        List<Map<String, Object>> plan = List.of(
                row("股票", null, OVERALL),
                row("股票", "長期債", "00679B"),
                row("股票", "成長型", "2330"),
                row("股票", null, "無子類別的一列"));

        List<Map<String, Object>> groups = RebalanceGrouper.group(latest(plan, null));

        assertThat(sectionSubClasses(groups.get(0)))
                .as("推導清單為空時依組內首次出現序，無小標段落仍置末")
                .containsExactly("長期債", "成長型", null);
        assertThat(totalRows(groups)).isEqualTo(plan.size());
    }

    // ---- (v) 推導清單以外的 subClass 不得丟棄 ------------------------------

    @Test
    @DisplayName("(v) 推導清單以外的 subClass 接在已知段落之後、無小標段落之前，一律不得丟棄")
    void 推導清單以外的子類別不被丟棄() {
        List<Map<String, Object>> plan = List.of(
                row("股票", null, OVERALL),
                row("股票", "未來新增的子類別", "NEW-1"),
                row("股票", "成長型", "2330"),
                row("股票", "另一個未知子類別", "NEW-2"),
                row("股票", "長期債", "00679B"),
                row("股票", null, "無子類別的一列"));

        List<Map<String, Object>> groups = RebalanceGrouper.group(
                latest(plan, List.of(target("股票", ENGINE_SUBCLASSES))));

        assertThat(sectionSubClasses(groups.get(0)))
                .as("已知段落依推導序 → 未知段落依組內首次出現序 → 無小標段落置末")
                .containsExactly("成長型", "長期債", "未來新增的子類別", "另一個未知子類別", null);
        assertThat(totalRows(groups)).as("未知子類別的列一列都不能少").isEqualTo(plan.size());
    }
}
