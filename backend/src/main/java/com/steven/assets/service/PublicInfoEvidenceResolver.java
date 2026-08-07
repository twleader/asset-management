package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.model.News;
import com.steven.assets.repository.NewsHeadlineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 從 {@code news_headline}（亦即 {@code public_info_*} 匯出的單一上游）挑選個股與產業證據。
 *
 * <p>只做相關性匹配與原文揭露，不把標題／摘要做情緒評分，也不從文字猜 EPS、PE
 * 或產業成長率。因此「找不到」只是沒有公開資訊證據，不是負分。</p>
 */
@Service
@RequiredArgsConstructor
public class PublicInfoEvidenceResolver {

    private static final Duration LOOKBACK = Duration.ofDays(120);
    private static final int MAX_EVIDENCE = 5;

    private final NewsHeadlineRepository repository;

    public record Evidence(
            List<TradingRadarDto.PublicInformationItem> company,
            List<TradingRadarDto.PublicInformationItem> industry) {
        public static final Evidence EMPTY = new Evidence(List.of(), List.of());
    }

    public Evidence resolve(String stockCode, String stockName, String industryName, Instant decisionInstant) {
        if (decisionInstant == null) return Evidence.EMPTY;
        return resolveFromRows(stockCode, stockName, industryName, decisionInstant,
                loadForEarliestDecision(decisionInstant));
    }

    /** 回測批次只查一次最早決策日前 120 天；各決策日仍會在記憶體中套用自己的上下界。 */
    List<News> loadForEarliestDecision(Instant earliestDecision) {
        if (earliestDecision == null) return List.of();
        List<News> rows = repository.findByPublishedAtGreaterThanEqualOrderByPublishedAtDesc(
                earliestDecision.minus(LOOKBACK));
        return rows == null ? List.of() : rows;
    }

    Evidence resolveFromRows(
            String stockCode,
            String stockName,
            String industryName,
            Instant decisionInstant,
            List<News> rows) {
        if (decisionInstant == null) return Evidence.EMPTY;
        if (rows == null || rows.isEmpty()) return Evidence.EMPTY;

        Pattern codePattern = exactCodePattern(stockCode);
        Predicate<News> companyMatch = row -> isVisible(row, decisionInstant)
                && (matches(row, codePattern) || containsExactPhrase(row, stockName));
        Predicate<News> industryMatch = row -> isVisible(row, decisionInstant)
                && containsExactPhrase(row, industryName);
        return new Evidence(select(rows, companyMatch), select(rows, industryMatch));
    }

    private List<TradingRadarDto.PublicInformationItem> select(List<News> rows, Predicate<News> predicate) {
        List<TradingRadarDto.PublicInformationItem> result = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (News row : rows) {
            if (!predicate.test(row) || (row.getId() != null && !seen.add(row.getId()))) continue;
            result.add(new TradingRadarDto.PublicInformationItem(
                    row.getRegion(), row.getTitle(), row.getSource(), row.getUrl(),
                    row.getPublishedAt() == null ? null : row.getPublishedAt().toString(), row.getSummary()));
            if (result.size() == MAX_EVIDENCE) break;
        }
        return List.copyOf(result);
    }

    private boolean isVisible(News row, Instant decisionInstant) {
        return row != null
                && News.CATEGORY_NEWS.equals(row.getCategory())
                && (row.getRegion() == null || "TW".equalsIgnoreCase(row.getRegion()))
                && row.getPublishedAt() != null
                && !row.getPublishedAt().isBefore(decisionInstant.minus(LOOKBACK))
                && !row.getPublishedAt().isAfter(decisionInstant);
    }

    private boolean matches(News row, Pattern pattern) {
        if (pattern == null) return false;
        return pattern.matcher(searchable(row)).find();
    }

    /** 全名稱／完整產業詞必須整段出現；不做縮寫、拆字或近似詞模糊匹配。 */
    private boolean containsExactPhrase(News row, String phrase) {
        if (phrase == null || phrase.isBlank()) return false;
        return searchable(row).contains(phrase.trim().toLowerCase(Locale.ROOT));
    }

    private String searchable(News row) {
        return ((row.getTitle() == null ? "" : row.getTitle()) + '\n'
                + (row.getSummary() == null ? "" : row.getSummary()))
                .toLowerCase(Locale.ROOT);
    }

    /** 代號前後不得緊接英數字，避免 2330 命中 12330 或 23301。 */
    static Pattern exactCodePattern(String stockCode) {
        if (stockCode == null || stockCode.isBlank()) return null;
        return Pattern.compile("(?<![A-Za-z0-9])" + Pattern.quote(stockCode.trim())
                + "(?![A-Za-z0-9])", Pattern.CASE_INSENSITIVE);
    }
}
