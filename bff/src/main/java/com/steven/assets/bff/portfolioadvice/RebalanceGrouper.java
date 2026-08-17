package com.steven.assets.bff.portfolioadvice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * AssetAllocationAdviceView（資產配置建議頁）專屬：把 business 回傳的扁平 {@code rebalancePlan}
 * 預先聚合成畫面直接可 render 的 {@code rebalanceGroups}（Task 344.23(1)）。
 *
 * <p>分組／分段／排序是 BFF 的職責（{@code spec/steering/structure.md} §3.2 第 5 條、CLAUDE.md
 * BFF 規範第 1 節「BFF 預先聚合／排序／過濾，前端只負責 render」）。本類別原本寫在
 * {@code AssetAllocationAdviceView.vue}，經 arch-auditor 稽核後搬進 BFF。
 *
 * <p>輸出形狀：
 * <pre>
 * [{ assetClass, header, sections: [{ subClass, rows: [...] }] }]
 * </pre>
 * {@code header} 為該 {@code assetClass} 的類別層級那一筆（{@code holding} 等於「整體」），
 * {@code sections} 為其餘明細列依子類別分段後的結果。
 *
 * <p><b>本頁專屬故放 {@code bff/portfolioadvice/}，不放 {@code bff/common/}</b>（後者是跨頁共用）。
 * 抽成獨立類別是為了能在不啟 Spring context 的情況下單元測試。
 */
public final class RebalanceGrouper {

    /**
     * 全類別唯一被允許的業務字面值：鏡像 business 端
     * {@code com.steven.assets.service.LocalPortfolioAllocationEngine.HOLDING_OVERALL}。
     * BFF 與 business 是兩個獨立 Maven 模組、無共用型別，故只能鏡像；
     * 五個子類別名稱（成長型／收益型（高股息）／短期債／中期債／長期債）<b>一個都不複製</b>，
     * 段落順序改由同一份回應的 {@code targetAllocation[].subAllocations[].subClass} 出現順序推導
     * （見 {@link #deriveSubClassOrder}）。
     */
    private static final String HOLDING_OVERALL = "整體";

    private RebalanceGrouper() {}

    /**
     * 由 {@code /api/portfolio-advice/latest} 的回應組出 {@code rebalanceGroups}。
     *
     * <p>下列情況一律回<b>空清單</b>（前端據此退回既有扁平 {@code rebalancePlan} 渲染）：
     * <ul>
     *   <li>{@code latest} 為 null 或空 Map（下游失敗時 controller 的 {@code onErrorReturn} 降級路徑）；</li>
     *   <li>{@code rebalancePlan} 缺漏／為 null／非清單／為空；</li>
     *   <li><b>整份 {@code rebalancePlan} 不存在任何 {@code holding} 等於「整體」的列</b>——
     *       即 {@code llm} 檔位與本任務落地前的舊建議。此時若硬分組，LLM 交錯輸出的列會被依
     *       {@code assetClass} 重排，牴觸「{@code llm} 檔位維持現況渲染」；回空清單讓前端逐字維持現況。</li>
     * </ul>
     * 任何形狀異常都不拋例外——這支只是顯示層聚合，不該把「200 ＋ 空資料」降級成 500。
     */
    public static List<Map<String, Object>> group(Map<String, Object> latest) {
        List<Map<String, Object>> plan = mapList(latest == null ? null : latest.get("rebalancePlan"));
        if (plan.isEmpty()) return List.of();

        boolean anyOverall = false;
        for (Map<String, Object> row : plan) {
            if (isOverall(row)) { anyOverall = true; break; }
        }
        if (!anyOverall) return List.of();

        List<String> subClassOrder = deriveSubClassOrder(latest);

        // 依 assetClass 在 rebalancePlan 中「首次出現的順序」建組（LinkedHashMap 保序）
        Map<String, Object> assetClassOfKey = new LinkedHashMap<>();
        Map<String, Map<String, Object>> headerOfKey = new LinkedHashMap<>();
        Map<String, List<Map<String, Object>>> detailsOfKey = new LinkedHashMap<>();
        for (Map<String, Object> row : plan) {
            Object assetClass = row.get("assetClass");
            String key = assetClass instanceof String s ? s : "";
            if (!detailsOfKey.containsKey(key)) {
                assetClassOfKey.put(key, assetClass);
                detailsOfKey.put(key, new ArrayList<>());
            }
            // 同一組出現第二筆「整體」時只認第一筆為 header，其餘退為明細列（不丟棄）
            if (isOverall(row) && !headerOfKey.containsKey(key)) {
                headerOfKey.put(key, row);
            } else {
                detailsOfKey.get(key).add(row);
            }
        }

        List<Map<String, Object>> groups = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : detailsOfKey.entrySet()) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("assetClass", assetClassOfKey.get(e.getKey()));
            // 混合形狀（部分 assetClass 有類別層級列、部分沒有）時，沒有的那組 header 為 null；
            // 「整份 plan 都沒有」已於上方提前回空清單，故此處不會產生「整組都是 header:null」的輸出。
            g.put("header", headerOfKey.get(e.getKey()));
            g.put("sections", sectionize(e.getValue(), subClassOrder));
            groups.add(g);
        }
        return groups;
    }

    /**
     * 子類別段落順序<b>不寫死清單</b>：由同一份回應的
     * {@code targetAllocation[].subAllocations[].subClass} 出現順序推導
     * （引擎產生的正規順序：成長型 → 收益型（高股息） → 短期債 → 中期債 → 長期債）。
     * {@code targetAllocation} 缺漏／為 null／非清單，或 {@code subAllocations} 為 null
     * （{@code llm} 檔位序列化為 null 鍵、非缺鍵）時回空清單，代表「沒有已知順序」。
     */
    private static List<String> deriveSubClassOrder(Map<String, Object> latest) {
        LinkedHashSet<String> order = new LinkedHashSet<>();
        for (Map<String, Object> target : mapList(latest == null ? null : latest.get("targetAllocation"))) {
            for (Map<String, Object> sub : mapList(target.get("subAllocations"))) {
                if (sub.get("subClass") instanceof String sc && !sc.isBlank()) order.add(sc);
            }
        }
        return new ArrayList<>(order);
    }

    /**
     * 明細列依 {@code subClass} 分段。順序：推導清單內的子類別（依推導順序）→
     * <b>推導清單以外的子類別</b>（依組內首次出現序，一律不得丟棄）→ 無 {@code subClass} 者
     * 合為單一無小標段落置末（存款群組明細、以及本任務落地前的舊資料）。
     * {@code rows} 為空的段落一律不輸出（否則畫面會出現空小標）。
     */
    private static List<Map<String, Object>> sectionize(List<Map<String, Object>> rows, List<String> subClassOrder) {
        Map<String, List<Map<String, Object>>> buckets = new LinkedHashMap<>();
        List<Map<String, Object>> noSubClass = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (row.get("subClass") instanceof String sc && !sc.isBlank()) {
                buckets.computeIfAbsent(sc, k -> new ArrayList<>()).add(row);
            } else {
                noSubClass.add(row);
            }
        }
        List<Map<String, Object>> sections = new ArrayList<>();
        for (String sc : subClassOrder) {
            List<Map<String, Object>> hit = buckets.remove(sc);
            if (hit != null && !hit.isEmpty()) sections.add(section(sc, hit));
        }
        for (Map.Entry<String, List<Map<String, Object>>> e : buckets.entrySet()) {
            if (!e.getValue().isEmpty()) sections.add(section(e.getKey(), e.getValue()));
        }
        if (!noSubClass.isEmpty()) sections.add(section(null, noSubClass));
        return sections;
    }

    private static Map<String, Object> section(String subClass, List<Map<String, Object>> rows) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("subClass", subClass); // 無小標段落為 null（LinkedHashMap 允許 null value，Map.of 不行）
        s.put("rows", rows);
        return s;
    }

    private static boolean isOverall(Map<String, Object> row) {
        return HOLDING_OVERALL.equals(row.get("holding"));
    }

    /** 把來路不明的 JSON 節點安全轉成 {@code List<Map<String,Object>>}：非清單回空、非 Map 的元素略過。 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        }
        return out;
    }
}
