package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.NewsRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 公開資訊個股過濾（Task 178）：只保留 {@code stock} 主檔個股＋總體/國際新聞，濾除「明確指向的個股全部不在主檔」者。
 *
 * <p>判定只採<b>高可信訊號</b>以免誤傷總經新聞（原型實測裸 4 位數字會把年份 2024/2030 誤當代號、短公司名
 * 子字串比對會誤命中）：
 * <ol>
 *   <li>wantgoo 來源的結構化 {@link NewsRow#tags()}（{@code newsTags} 相關實體），對應全市場名冊 {@code name→code}；</li>
 *   <li>所有來源的<b>明確代號格式</b>（須帶 {@code -TW} 後綴，如 {@code (6967-TW)}／{@code 6967-TW}；刻意不認裸數字或括號內年份如 {@code (2023)}，避免年份＝真實鋼鐵股代號被誤當個股）。</li>
 * </ol>
 *
 * <p>方向保守（寧留勿誤濾）：只有「識別到的個股全部不在 {@code stock} 主檔」才濾；命中主檔／總體大盤國際／
 * {@code twse-*} 一律留。全市場名冊載入失敗（空）則本輪全留（graceful）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublicInfoStockFilter {

    private final MarketDataFetchService marketData;
    private final StockSourceQuery source;

    /**
     * 明確代號格式：<b>必須帶 {@code -TW} 後綴</b>（可含括號），如 {@code (6967-TW)}／{@code 6967-TW}／{@code （2330-TW）}。
     * 刻意不認裸數字或括號內裸年份（如 {@code (2023)}／{@code (2030)}）——2020～2031 等年份正好是真實上市鋼鐵股代號，
     * 若採信會把含年份的總經新聞誤當個股濾掉（Task 178 review 修正）。
     */
    private static final Pattern EXPLICIT_CODE = Pattern.compile(
            "[\\(（]?\\s*(\\d{4,6}[A-Z]?)\\s*-TW\\s*[\\)）]?");
    private static final Pattern BARE_CODE = Pattern.compile("\\d{4,6}[A-Z]?");

    public List<NewsRow> retain(List<NewsRow> rows) {
        Map<String, String> nameToCode;
        Set<String> keep;
        try {
            nameToCode = marketData.twMarketNameToCode();
            keep = source.allStockCodes();
        } catch (Exception e) {
            log.warn("個股過濾：名冊/主檔讀取失敗，本輪全留（不誤濾）：{}", e.getMessage());
            return rows;
        }
        if (nameToCode == null || nameToCode.isEmpty()) {
            log.warn("個股過濾：全市場名冊為空，本輪全留（不誤濾）");
            return rows;
        }
        Set<String> marketCodes = new HashSet<>(nameToCode.values());

        List<NewsRow> out = new ArrayList<>(rows.size());
        int dropped = 0;
        for (NewsRow r : rows) {
            // 量化/總體公開資訊（twse-* 三大法人・成交量、fx 匯率、us-market 美股指數）一律保留，不做個股過濾；
            // 只有 category="news" 的一般新聞才進個股過濾判定（Task 178；Task 180 擴及 fx / us-market）。
            if (r.category() != null && !"news".equals(r.category())) {
                out.add(r);
                continue;
            }
            Set<String> mentioned = mentionedStocks(r, nameToCode, marketCodes);
            if (!mentioned.isEmpty() && mentioned.stream().noneMatch(keep::contains)) {
                dropped++;
                continue;
            }
            out.add(r);
        }
        log.info("個股過濾：{} → 保留 {}、濾除 {}（非主檔個股）", rows.size(), out.size(), dropped);
        return out;
    }

    /** 一則新聞明確指向的台股個股代號集合（tags＋明確代號格式，皆須落在全市場名冊內）。 */
    private Set<String> mentionedStocks(NewsRow r, Map<String, String> nameToCode, Set<String> marketCodes) {
        Set<String> m = new HashSet<>();
        // 1. wantgoo 結構化 tags → 全市場個股（name 完全命中，或 tag 本身就是代號）
        for (String tag : r.tags()) {
            if (tag == null || tag.isBlank()) continue;
            String c = nameToCode.get(tag);
            if (c != null) {
                m.add(c);
            } else if (BARE_CODE.matcher(tag).matches() && marketCodes.contains(tag)) {
                m.add(tag);
            }
        }
        // 2. 明確代號格式（所有來源，須帶 -TW；不認裸數字/括號年份）
        String text = (r.title() == null ? "" : r.title()) + " " + (r.summary() == null ? "" : r.summary());
        Matcher mt = EXPLICIT_CODE.matcher(text);
        while (mt.find()) {
            String c = mt.group(1);
            if (c != null && marketCodes.contains(c)) m.add(c);
        }
        return m;
    }
}
