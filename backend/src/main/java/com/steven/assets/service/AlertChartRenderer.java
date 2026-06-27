package com.steven.assets.service;

import com.steven.assets.model.StockPriceHistory;
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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 警示 / 補發 email 內嵌的「股票分析走勢圖」PNG 渲染（Requirement 23、Task 93/94）。
 *
 * 與畫面 StockAnalysisDialog 同源、同數值、同版面：
 *  - 資料一律走 {@link HistoricalDataService#getStockHistory}（抓 120 個月與畫面同範圍、0000 大盤自動改讀
 *    twse_index_daily_history 含 OHLC、由 service 併入今日即時價）。
 *  - MA20/60/240：與前端 calcMA 相同——每點對 window 重新加總（非滑動扣減）+ BigDecimal HALF_UP 2 位。
 *  - K/D：與前端 calcKD / {@link TechnicalIndicatorService} 同一遞迴（period=9、RSV=(close-ll)/(hh-ll)*100、
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

    /** 與前端 calcMA 一致：每點對 window 重新加總（非滑動扣減）、四捨五入 2 位；不足 window 回 null。 */
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
     * 與前端 calcKD / TechnicalIndicatorService 完全一致：period=9、RSV=(close-ll)/(hh-ll)*100（hh==ll→50）、
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
