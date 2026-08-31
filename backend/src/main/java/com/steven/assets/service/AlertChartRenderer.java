package com.steven.assets.service;

import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.util.MarketZones;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.knowm.xchart.BitmapEncoder;
import org.knowm.xchart.XYChart;
import org.knowm.xchart.XYChartBuilder;
import org.knowm.xchart.XYSeries;
import org.knowm.xchart.internal.chartpart.Annotation;
import org.knowm.xchart.style.Styler;
import org.knowm.xchart.style.XYStyler;
import org.knowm.xchart.style.markers.SeriesMarkers;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * 警示 / 補發 email 內嵌的「股票分析走勢圖」PNG 渲染（Requirement 23、Task 93/94）。
 *
 * 與畫面 StockAnalysisDialog 同源、同數值、同版面：
 *  - 資料一律走 {@link HistoricalDataService#getStockHistory}（抓 120 個月與畫面同範圍、0000 大盤自動改讀
 *    twse_index_daily_history 含 OHLC、由 service 併入今日即時價）。
 *  - MA20/60/240：本類自有的 calcMa——每點對 window 重新加總（非滑動扣減）+ BigDecimal HALF_UP 2 位。
 *  - K/D：本類自有的 calcKd，與 {@link TechnicalIndicatorService} 同一遞迴（period=9、RSV=(close-ll)/(hh-ll)*100、
 *    hh==ll→50、K=prevK*2/3+RSV/3、D=prevD*2/3+K/3、seed 50/50、high/low 缺值 fallback close、續算用未捨入值）。
 *    整段歷史算完再切尾 252（≈1年），故尾值（legend）與畫面逐位一致。
 *
 * 版面：上 pane = 股價 + 月線MA20 + 季線MA60 + 年線MA240（隱藏 x 軸）；下 pane = K、D（Y 0..100、80/20 灰虛線）。
 * 兩 pane 各自渲成 BufferedImage 後用 Graphics2D 垂直合成一張 PNG。legend 文字 = 中文名稱 + 空白 + 最新值（%,.2f）。
 *
 * CJK 字型：用 {@link Font#createFonts(File)}（複數）挑出 TC face——.ttc 的 face 0 是日文變體，
 * 單數 createFont 會選到日系字形。找不到字型則 fallback SANS_SERIF（中文可能變方框但不崩）。
 *
 * 任何失敗（資料不足 / 繪圖例外）回 empty，由呼叫端省略圖片但仍寄文字。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AlertChartRenderer {

    private final HistoricalDataService historicalDataService;

    /** 顯示視窗 ≈ 1 年交易日，與畫面預設「1年」一致。 */
    private static final int DISPLAY_DAYS = 252;
    /** 抓滿 120 個月暖機，與畫面 StockAnalysisDialog 同範圍，確保 KD 遞迴尾值逐位一致。 */
    private static final int FETCH_MONTHS = 120;
    private static final int KD_PERIOD = 9;
    private static final ZoneId UTC = ZoneId.of("UTC");

    private static final int WIDTH = 900;
    private static final int TOP_H = 380;
    private static final int BOT_H = 170;
    /** 「當日」分時走勢單圖高度（單一 pane、無 KD 副圖）。 */
    private static final int INTRADAY_H = 420;

    // 顏色對齊畫面
    private static final Color C_PRICE = new Color(0x4a, 0x90, 0xe2); // 股價 藍
    private static final Color C_MA20  = new Color(0xf5, 0x9e, 0x0b); // 月線 橘
    private static final Color C_MA60  = new Color(0x8b, 0x5c, 0xf6); // 季線 紫
    private static final Color C_MA240 = new Color(0xef, 0x44, 0x44); // 年線 紅
    private static final Color C_K     = new Color(0xf5, 0x9e, 0x0b); // K 橘
    private static final Color C_D     = new Color(0x15, 0x80, 0x3d); // D 綠
    private static final Color C_REF   = new Color(0x94, 0xa3, 0xb8); // 80/20 參考線 灰
    private static final Color C_GRID  = new Color(0xF0, 0xF0, 0xF0);
    private static final Color C_HIGH  = new Color(0xdc, 0x26, 0x26); // 最高 紅（紅漲，對齊畫面 markPoint）
    private static final Color C_LOW   = new Color(0x16, 0xa3, 0x4a); // 最低 綠（綠跌，對齊畫面 markPoint）
    private static final Color C_FLAT  = new Color(0x4a, 0x90, 0xe2); // 平盤 / 無昨收可比 中性藍

    /** 最高 / 最低標記色塊第二行日期格式（UTC，與 xs 的 atStartOfDay(UTC) 同基準避免時區偏移）。 */
    private static final DateTimeFormatter MARK_DATE = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /** Alpine font-noto-cjk 的繁中字型（TC face）；deriveFont 後共用於兩個 chart。 */
    private static final Font CJK_FONT = loadCjkFont(13f);

    public Optional<byte[]> renderPriceMaPng(String code, String market) {
        try {
            LocalDate end = LocalDate.now();
            List<StockPriceHistory> hist =
                    historicalDataService.getStockHistory(code, market, end.minusMonths(FETCH_MONTHS), end);
            if (hist == null || hist.size() < 30) return Optional.empty();

            int n = hist.size();
            double[] closes = new double[n];
            for (int i = 0; i < n; i++) closes[i] = hist.get(i).getClosePrice().doubleValue();

            Double[] ma20  = calcMa(closes, 20);
            Double[] ma60  = calcMa(closes, 60);
            Double[] ma240 = calcMa(closes, 240);
            double[][] kd  = calcKd(hist);          // [0]=K, [1]=D（未達 period 為 NaN）
            double[] kArr = kd[0], dArr = kd[1];

            int from = Math.max(0, n - DISPLAY_DAYS);
            List<Date> xs = new ArrayList<>();
            List<Double> price = new ArrayList<>();
            List<Double> s20 = new ArrayList<>(), s60 = new ArrayList<>(), s240 = new ArrayList<>();
            List<Double> kS = new ArrayList<>(), dS = new ArrayList<>();
            for (int i = from; i < n; i++) {
                xs.add(Date.from(hist.get(i).getTradingDate().atStartOfDay(UTC).toInstant()));
                price.add(closes[i]);
                s20.add(ma20[i]);
                s60.add(ma60[i]);
                s240.add(ma240[i]);
                kS.add(Double.isNaN(kArr[i]) ? null : kArr[i]);
                dS.add(Double.isNaN(dArr[i]) ? null : dArr[i]);
            }

            // 兩 pane 用同寬（右對齊）的 Y 軸標籤 → 左 gutter 一致 → 上下 plot 對齊
            int gutterW = gutterWidth(price);
            BufferedImage top = renderTopPane(xs, price, s20, s60, s240, gutterW);
            BufferedImage bot = renderKdPane(xs, kS, dS, gutterW);

            BufferedImage combined = new BufferedImage(WIDTH, TOP_H + BOT_H, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = combined.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, WIDTH, TOP_H + BOT_H);
            g.drawImage(top, 0, 0, null);
            g.drawImage(bot, 0, TOP_H, null);
            g.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(combined, "png", baos);
            return Optional.of(baos.toByteArray());
        } catch (Exception e) {
            log.warn("走勢圖 PNG 生成失敗 {} {}: {}", code, market, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 警示 / 補發 email 內嵌的「當日分時走勢圖」PNG（與年圖並列，同一封信第二張圖）。
     *
     * 與畫面 StockAnalysisDialog「當日」走勢同資料源、同版面語意：
     *  - 分時 tick 走 {@link HistoricalDataService#fetchIntradaySession}（date=null → ext-materials 取最近有資料的
     *    交易日；讀 Redis tick LIST，與畫面「當日」同一支 business API，非直連外部行情）。
     *  - 比較基準、漲跌與標籤皆由 session 回應提供；renderer 不查第二份日線、不重新計算。
     *  - X 軸固定「開盤 → 收盤」（{@link MarketZones} 單一事實來源），以當日分鐘數為數值 X、min/max 鎖定開收盤，
     *    故軸延伸到收盤時間而非最後一筆 tick（與畫面 Requirement 13「X 軸延伸到收盤」同視覺行為）。
     *
     * 稀疏 tick（2 分輪詢 / 5 分 K）以「每分鐘最後成交」落點連成連續線（XChart 無 connectNulls，故只畫真實
     * tick 點、不鋪整段分鐘網格的 null）。Y 軸鎖定分時區間 + 昨收（各 +10% padding），避免基準線落在框外。
     * 任何失敗（無 tick / 繪圖例外）回 empty，由呼叫端省略此圖但仍寄文字與年圖。
     */
    public Optional<byte[]> renderIntradayPng(String code, String market) {
        try {
            HistoricalDataService.IntradaySession session =
                    historicalDataService.fetchIntradaySession(code, market, null);
            List<HistoricalDataService.IntradayTick> ticks = session.ticks();
            if (ticks == null || ticks.isEmpty()) return Optional.empty();

            // 交易時段 open→close → 當日分鐘數 X 軸範圍（MarketZones 單一事實來源，鏡射畫面 sessionHours）
            LocalTime open = MarketZones.openTime(market);
            LocalTime close = MarketZones.closeTime(market);
            int openMin = open.getHour() * 60 + open.getMinute();
            int closeMin = close.getHour() * 60 + close.getMinute();
            if (closeMin <= openMin) return Optional.empty();

            // 每分鐘取最後成交（後到覆寫）、邊界外（開盤前 / 收盤寬限窗）夾到端點；TreeMap 保時間升冪
            TreeMap<Integer, Double> byMinute = new TreeMap<>();
            for (HistoricalDataService.IntradayTick t : ticks) {
                Integer mm = parseTickMinute(t.time());
                if (mm == null || t.price() == null) continue;
                int clamped = Math.max(openMin, Math.min(closeMin, mm));
                byMinute.put(clamped, t.price().doubleValue());
            }
            if (byMinute.isEmpty()) return Optional.empty();

            List<Double> xs = new ArrayList<>(), ys = new ArrayList<>();
            for (Map.Entry<Integer, Double> e : byMinute.entrySet()) {
                xs.add((double) e.getKey());
                ys.add(e.getValue());
            }

            Double prevClose = session.comparisonPrice() == null ? null : session.comparisonPrice().doubleValue();
            double lastPrice = ys.get(ys.size() - 1);
            Color lineColor = session.change() == null ? C_FLAT
                    : session.change().signum() > 0 ? C_HIGH
                    : session.change().signum() < 0 ? C_LOW : C_FLAT;

            XYChart chart = new XYChartBuilder().width(WIDTH).height(INTRADAY_H).build();
            XYStyler styler = chart.getStyler();
            applyCommonStyle(styler);
            styler.setXAxisMin((double) openMin);
            styler.setXAxisMax((double) closeMin);   // X 軸固定延伸到收盤（非最後一筆 tick）
            chart.setCustomXAxisTickLabelsFormatter(v -> minToHHmm((int) Math.round(v)));
            DecimalFormat nf = new DecimalFormat("#,##0.00");
            chart.setCustomYAxisTickLabelsFormatter(nf::format);
            applyIntradayYRange(styler, ys, prevClose);

            // 已驗證的 session 比較基準線（先加 → 畫在股價線底下）
            if (prevClose != null) {
                addNumericRefLine(chart, session.comparisonKind().displayLabel() + " " + fmt(prevClose),
                        openMin, closeMin, prevClose);
            }

            // 分時股價線（legend 帶最新價 + 今日漲跌；線色即漲跌色）
            XYSeries s = chart.addSeries("股價 " + fmt(lastPrice) + changeSuffix(session), xs, ys);
            s.setMarker(SeriesMarkers.NONE);
            s.setLineColor(lineColor);
            s.setLineStyle(new BasicStroke(2.0f));

            BufferedImage img = BitmapEncoder.getBufferedImage(chart);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return Optional.of(baos.toByteArray());
        } catch (Exception e) {
            log.warn("當日分時圖 PNG 生成失敗 {} {}: {}", code, market, e.getMessage());
            return Optional.empty();
        }
    }

    /** 分時 tick 時間字串的 "HH:mm"（第 11–15 碼）→ 當日分鐘數（0..1439）；格式不符回 null。 */
    private static Integer parseTickMinute(String time) {
        if (time == null || time.length() < 16) return null;
        String hhmm = time.substring(11, 16);
        if (!hhmm.matches("\\d\\d:\\d\\d")) return null;
        int h = Integer.parseInt(hhmm.substring(0, 2));
        int m = Integer.parseInt(hhmm.substring(3, 5));
        return h * 60 + m;
    }

    /** 當日分鐘數 → "HH:mm"（X 軸刻度標籤）。 */
    private static String minToHHmm(int minuteOfDay) {
        int m = Math.max(0, minuteOfDay);
        return String.format("%02d:%02d", (m / 60) % 24, m % 60);
    }

    /** 分時 Y 軸：涵蓋分時區間與昨收基準線，上下各 +10% padding（比照畫面當日鎖定區間，基準線不落框外）。 */
    private static void applyIntradayYRange(XYStyler styler, List<Double> ys, Double prevClose) {
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (Double y : ys) {
            if (y == null) continue;
            lo = Math.min(lo, y);
            hi = Math.max(hi, y);
        }
        if (prevClose != null) { lo = Math.min(lo, prevClose); hi = Math.max(hi, prevClose); }
        if (lo == Double.POSITIVE_INFINITY) return;
        double pad = (hi - lo) * 0.1;
        if (pad == 0) pad = Math.abs(hi) * 0.001;   // 平盤 / 單點：給極小 padding 避免 min==max
        if (pad == 0) pad = 1;
        styler.setYAxisMin(lo - pad);
        styler.setYAxisMax(hi + pad);
    }

    /** 數值 X 軸的水平參考線（昨收）：兩端點灰虛線；顯示於 legend（與 K/D 隱藏式參考線不同）。 */
    private static void addNumericRefLine(XYChart chart, String name, int xFrom, int xTo, double y) {
        XYSeries s = chart.addSeries(name, List.of((double) xFrom, (double) xTo), List.of(y, y));
        s.setMarker(SeriesMarkers.NONE);
        s.setLineColor(C_REF);
        s.setLineStyle(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL,
                1f, new float[]{5f, 4f}, 0f));   // 虛線
    }

    /** 今日漲跌後綴僅讀 business session，不在 renderer 重算。 */
    private static String changeSuffix(HistoricalDataService.IntradaySession session) {
        if (session.change() == null || session.changePercent() == null) return "";
        double chg = session.change().doubleValue();
        double pct = session.changePercent().doubleValue();
        String arrow = chg > 0 ? "▲" : chg < 0 ? "▼" : "";
        return String.format(" %s%,.2f (%+.2f%%)", arrow, Math.abs(chg), pct);
    }

    // ── 上 pane：股價 + 3 條 MA ────────────────────────────────
    private BufferedImage renderTopPane(List<Date> xs, List<Double> price,
                                        List<Double> s20, List<Double> s60, List<Double> s240, int gutterW) {
        XYChart chart = new XYChartBuilder().width(WIDTH).height(TOP_H).build();
        XYStyler styler = chart.getStyler();
        applyCommonStyle(styler);
        styler.setXAxisTicksVisible(false);   // 日期只畫在下 pane
        DecimalFormat nf = new DecimalFormat("#,##0");
        chart.setCustomYAxisTickLabelsFormatter(v -> padLeft(nf.format(v), gutterW));

        addLine(chart, "股價 "      + fmt(last(price)), xs, price, C_PRICE, 2.0f);
        addLine(chart, "月線MA20 "  + fmt(last(s20)),   xs, s20,   C_MA20,  1.4f);
        addLine(chart, "季線MA60 "  + fmt(last(s60)),   xs, s60,   C_MA60,  1.4f);
        addLine(chart, "年線MA240 " + fmt(last(s240)),  xs, s240,  C_MA240, 1.4f);

        addHiLoMarkers(chart, xs, price);

        return BitmapEncoder.getBufferedImage(chart);
    }

    // ── 下 pane：K、D（0..100，80/20 參考線） ──────────────────
    private BufferedImage renderKdPane(List<Date> xs, List<Double> kS, List<Double> dS, int gutterW) {
        XYChart chart = new XYChartBuilder().width(WIDTH).height(BOT_H).build();
        XYStyler styler = chart.getStyler();
        applyCommonStyle(styler);
        styler.setYAxisMin(0.0);
        styler.setYAxisMax(100.0);
        styler.setDatePattern("yyyy/MM");
        DecimalFormat nf = new DecimalFormat("#,##0");
        chart.setCustomYAxisTickLabelsFormatter(v -> padLeft(nf.format(v), gutterW));

        // 80 / 20 參考線（兩點水平虛線、不進 legend）；先加 → 畫在 K/D 底下
        addRefLine(chart, "ref80", xs, 80.0);
        addRefLine(chart, "ref20", xs, 20.0);

        addLine(chart, "K " + fmt(last(kS)), xs, kS, C_K, 1.5f);
        addLine(chart, "D " + fmt(last(dS)), xs, dS, C_D, 1.5f);

        return BitmapEncoder.getBufferedImage(chart);
    }

    private void applyCommonStyle(XYStyler styler) {
        styler.setChartTitleVisible(false);
        styler.setAxisTitlesVisible(false);
        styler.setLegendVisible(true);
        styler.setLegendPosition(Styler.LegendPosition.InsideNW);
        styler.setLegendLayout(Styler.LegendLayout.Horizontal);
        styler.setLegendBackgroundColor(Color.WHITE);   // 白底，蓋住線條才看得清
        styler.setMarkerSize(0);
        styler.setChartBackgroundColor(Color.WHITE);
        styler.setPlotBackgroundColor(Color.WHITE);
        styler.setPlotGridLinesColor(C_GRID);
        styler.setPlotBorderVisible(false);
        // CJK 字型套在 legend / 軸刻度 / 軸標題，避免中文變方框 / 日系字形
        styler.setBaseFont(CJK_FONT);
        styler.setLegendFont(CJK_FONT.deriveFont(12f));
        styler.setAxisTickLabelsFont(CJK_FONT.deriveFont(11f));
        styler.setAxisTitleFont(CJK_FONT);
    }

    /** 整條皆 null（歷史不足以算該序列）的略過，避免 XChart 對全 null 序列算軸範圍時出錯。 */
    private static void addLine(XYChart chart, String name, List<Date> xs, List<Double> ys,
                               Color color, float width) {
        if (ys.stream().noneMatch(Objects::nonNull)) return;
        XYSeries s = chart.addSeries(name, xs, ys);
        s.setMarker(SeriesMarkers.NONE);
        s.setLineColor(color);
        s.setLineStyle(new BasicStroke(width));
    }

    /** 水平參考線（K/D 的 80、20）：兩端點水平虛線、不進 legend。 */
    private static void addRefLine(XYChart chart, String name, List<Date> xs, double y) {
        if (xs.isEmpty()) return;
        List<Date> ends = List.of(xs.get(0), xs.get(xs.size() - 1));
        List<Double> ys = List.of(y, y);
        XYSeries s = chart.addSeries(name, ends, ys);
        s.setMarker(SeriesMarkers.NONE);
        s.setLineColor(C_REF);
        s.setLineStyle(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL,
                1f, new float[]{4f, 4f}, 0f));   // 虛線
        s.setShowInLegend(false);
    }

    /**
     * 在股價線標出顯示區間內的「最高 / 最低」收盤點（紅最高、綠最低，比照畫面 StockAnalysisDialog 的 markPoint）。
     * 各以一個 {@link HiLoMarker} annotation 畫出：圓點落在線上 + 色塊兩行（第一行「最高/最低 + 價位」、第二行日期）。
     * 最高 / 最低同點（區間內平盤）時只畫最高。價位 / 日期入圖，與畫面互動圖一致。
     */
    private static void addHiLoMarkers(XYChart chart, List<Date> xs, List<Double> price) {
        if (xs.isEmpty()) return;
        int maxI = -1, minI = -1;
        double maxV = Double.NEGATIVE_INFINITY, minV = Double.POSITIVE_INFINITY;
        for (int i = 0; i < price.size(); i++) {
            Double v = price.get(i);
            if (v == null) continue;
            if (v > maxV) { maxV = v; maxI = i; }
            if (v < minV) { minV = v; minI = i; }
        }
        if (maxI < 0) return;
        chart.addAnnotation(new HiLoMarker(xs.get(maxI), maxV, true));
        if (minI != maxI) chart.addAnnotation(new HiLoMarker(xs.get(minI), minV, false));
    }

    /**
     * 股價線上的「最高 / 最低」標記：自繪圓點 + 圓角色塊（兩行：最高/最低+價位、日期）。
     * 用 {@link Annotation} 的軸→螢幕座標換算（paint 時 chart 已完成軸範圍計算），故點精準落在線上。
     * AnnotationText 的字色由 styler 全域共用、無法逐點分紅綠，故自繪以對齊畫面紅漲綠跌的雙色塊。
     */
    private static final class HiLoMarker extends Annotation {
        private final double xMillis;
        private final double yVal;
        private final boolean isHigh;
        private final String line1;   // 最高/最低 + 價位
        private final String line2;   // 日期

        private HiLoMarker(Date x, double y, boolean isHigh) {
            super(false);             // 座標為資料值（非螢幕像素）
            this.xMillis = x.getTime();
            this.yVal = y;
            this.isHigh = isHigh;
            this.line1 = (isHigh ? "最高 " : "最低 ") + fmt(y);
            this.line2 = Instant.ofEpochMilli(x.getTime()).atZone(UTC).toLocalDate().format(MARK_DATE);
        }

        @Override
        public void paint(Graphics2D g) {
            if (!isVisible) return;
            Object aa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int px = getXAxisScreenValue(xMillis);
            int py = getYAxisScreenValue(yVal);
            Color color = isHigh ? C_HIGH : C_LOW;

            // 線上的圓點（白邊讓它從藍線浮出）
            g.setColor(color);
            g.fillOval(px - 4, py - 4, 8, 8);
            g.setStroke(new BasicStroke(1.5f));
            g.setColor(Color.WHITE);
            g.drawOval(px - 4, py - 4, 8, 8);

            // 兩行色塊
            g.setFont(CJK_FONT.deriveFont(Font.BOLD, 11f));
            FontMetrics fm = g.getFontMetrics();
            int lh = fm.getHeight();
            int padX = 6, padY = 3, gap = 8;
            int boxW = Math.max(fm.stringWidth(line1), fm.stringWidth(line2)) + padX * 2;
            int boxH = lh * 2 + padY * 2;

            // 最高色塊放點下方、最低放上方（對齊畫面，避免撞 legend / 縮放軸）；水平夾在 plot 內避免出界
            int boxX = px - boxW / 2;
            int boxY = isHigh ? py + gap : py - gap - boxH;
            int left = getXAxisScreenValueForMin();
            int right = getXAxisScreenValueForMax();
            if (boxX < left) boxX = left;
            if (boxX + boxW > right) boxX = right - boxW;

            g.setColor(color);
            g.fill(new RoundRectangle2D.Float(boxX, boxY, boxW, boxH, 6, 6));
            g.setColor(Color.WHITE);
            int ty = boxY + padY + fm.getAscent();
            g.drawString(line1, boxX + (boxW - fm.stringWidth(line1)) / 2, ty);
            g.drawString(line2, boxX + (boxW - fm.stringWidth(line2)) / 2, ty + lh);

            if (aa != null) g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, aa);
        }
    }

    /** 每點對 window 重新加總（非滑動扣減）、四捨五入 2 位；不足 window 回 null。 */
    private static Double[] calcMa(double[] closes, int window) {
        Double[] out = new Double[closes.length];
        for (int i = 0; i < closes.length; i++) {
            if (i < window - 1) continue;
            double sum = 0;
            for (int j = i - window + 1; j <= i; j++) sum += closes[j];
            out[i] = round2(sum / window);
        }
        return out;
    }

    /**
     * 與 TechnicalIndicatorService 完全一致：period=9、RSV=(close-ll)/(hh-ll)*100（hh==ll→50）、
     * K=prevK*2/3+RSV/3、D=prevD*2/3+K/3、seed prevK=prevD=50、high/low 缺值 fallback closePrice、續算用未捨入值。
     * 回傳 [0]=K、[1]=D，未達 period 的點為 Double.NaN。
     */
    private static double[][] calcKd(List<StockPriceHistory> hist) {
        int n = hist.size();
        double[] k = new double[n], d = new double[n];
        double prevK = 50, prevD = 50;
        for (int i = 0; i < n; i++) {
            if (i < KD_PERIOD - 1) { k[i] = Double.NaN; d[i] = Double.NaN; continue; }
            double hh = Double.NEGATIVE_INFINITY, ll = Double.POSITIVE_INFINITY;
            for (int j = i - KD_PERIOD + 1; j <= i; j++) {
                StockPriceHistory bar = hist.get(j);
                double close = bar.getClosePrice().doubleValue();
                double high = bar.getHighPrice() != null ? bar.getHighPrice().doubleValue() : close;
                double low  = bar.getLowPrice()  != null ? bar.getLowPrice().doubleValue()  : close;
                if (high > hh) hh = high;
                if (low  < ll) ll = low;
            }
            double close = hist.get(i).getClosePrice().doubleValue();
            double rsv = (hh == ll) ? 50 : (close - ll) / (hh - ll) * 100;
            double kk = prevK * 2.0 / 3 + rsv / 3.0;
            double dd = prevD * 2.0 / 3 + kk / 3.0;
            k[i] = round2(kk);
            d[i] = round2(dd);
            prevK = kk; prevD = dd;   // 續算用未捨入值，與前端逐點遞迴一致
        }
        return new double[][]{k, d};
    }

    /** 兩 pane 共用的 Y 軸標籤字元寬：取股價最大值（含 ~10% headroom 容 nice-rounding）的位數，至少 3（KD「100」）。 */
    private static int gutterWidth(List<Double> price) {
        double max = price.stream().filter(Objects::nonNull).mapToDouble(Double::doubleValue).max().orElse(100);
        String label = new DecimalFormat("#,##0").format(Math.ceil(max * 1.1));
        return Math.max(label.length(), 3);
    }

    /** 左補空白（右對齊）到指定字元寬，讓兩 pane 的 Y 軸 gutter 等寬。 */
    private static String padLeft(String s, int w) {
        return String.format("%" + w + "s", s);
    }

    private static double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static Double last(List<Double> ys) {
        for (int i = ys.size() - 1; i >= 0; i--) if (ys.get(i) != null) return ys.get(i);
        return null;
    }

    /** 與畫面 legend 同形：2 位小數含千分位（54.35 / 2,305.00 / 45.53）。null → "-"。 */
    private static String fmt(Double v) {
        if (v == null) return "-";
        return String.format("%,.2f", v);
    }

    // ── CJK 字型載入 ──────────────────────────────────────────
    private static Font loadCjkFont(float size) {
        try {
            File file = findCjkFontFile();
            if (file != null) {
                Font[] faces = Font.createFonts(file);   // 複數：能取到 .ttc 內的各 face
                // 優先「比例 TC」，其次任何 TC，最後 face[0]（.ttc face0 為 JP，須避開）
                Font picked = Arrays.stream(faces)
                        .filter(f -> isTraditionalChinese(f) && !isMono(f))
                        .findFirst()
                        .or(() -> Arrays.stream(faces).filter(AlertChartRenderer::isTraditionalChinese).findFirst())
                        .orElse(faces.length > 0 ? faces[0] : null);
                if (picked != null) {
                    log.info("走勢圖 CJK 字型：{}（檔 {}）", picked.getFontName(Locale.ROOT), file);
                    return picked.deriveFont(Font.PLAIN, size);
                }
            }
            log.warn("找不到 CJK 字型檔，走勢圖 legend 中文可能變方框；fallback SANS_SERIF");
        } catch (Exception e) {
            log.warn("CJK 字型載入失敗，fallback SANS_SERIF: {}", e.getMessage());
        }
        return new Font(Font.SANS_SERIF, Font.PLAIN, (int) size);
    }

    private static boolean isTraditionalChinese(Font f) {
        String nm = (f.getFontName(Locale.ROOT) + " " + f.getName() + " " + f.getFamily(Locale.ROOT));
        return nm.contains("TC") || nm.contains("Traditional") || nm.contains("正體") || nm.contains("繁");
    }

    private static boolean isMono(Font f) {
        String nm = (f.getFontName(Locale.ROOT) + " " + f.getName() + " " + f.getFamily(Locale.ROOT));
        return nm.contains("Mono");
    }

    private static File findCjkFontFile() {
        String[] candidates = {
                "/usr/share/fonts/noto/NotoSansCJK-Regular.ttc",
                "/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc",
                "/usr/share/fonts/noto/NotoSansTC-Regular.otf",
                "/usr/share/fonts/noto-cjk/NotoSansTC-Regular.otf",
        };
        for (String c : candidates) {
            File f = new File(c);
            if (f.exists()) return f;
        }
        // 後備：遞迴掃 /usr/share/fonts 找 NotoSansCJK*.ttc / NotoSansTC*.otf
        try (Stream<Path> stream = Files.walk(Paths.get("/usr/share/fonts"), 6)) {
            return stream.map(Path::toFile)
                    .filter(f -> {
                        String n = f.getName();
                        return (n.startsWith("NotoSansCJK") && n.endsWith(".ttc"))
                                || (n.startsWith("NotoSansTC") && n.endsWith(".otf"));
                    })
                    .findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }
}
