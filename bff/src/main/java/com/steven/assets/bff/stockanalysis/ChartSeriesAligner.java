package com.steven.assets.bff.stockanalysis;

import com.steven.assets.bff.stockanalysis.dto.ChartSeriesDto;
import com.steven.assets.bff.stockanalysis.dto.IndicatorPointDto;
import com.steven.assets.bff.stockanalysis.dto.PricePointDto;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.function.Function;

/**
 * 保留 line 聯集相容，並在 BFF 完成日／ISO 週 candle 正規化。
 *
 * <p><b>對應實作（必須同步）：{@code com.steven.assets.service.WeeklyBarAggregator}</b>（Task 356.2）。
 * {@link #weekly} 的<b>分桶規則與它逐字一致</b>（同一組 {@code WeekFields.ISO} 的
 * {@code weekBasedYear} ＋ {@code weekOfWeekBasedYear}、{@code open} 取該週最早交易日、
 * {@code high}／{@code low} 取極值、{@code close} 取最晚交易日、週日期取最晚交易日）——同一檔股票在
 * 「股票分析走勢圖的週K」與「交易雷達的週K」若連哪幾天算同一週、哪一天是週收盤都不一樣，
 * 使用者無從解釋。改動任何一邊的週界都必須同步改另一邊。</p>
 *
 * <p><b>五處刻意不同，不得當成同一個量</b>：(1) 本側的週 KD／MACD／RSI 是「該週最後一個交易日的
 * <b>日K</b>指標值」，雷達那側是在週K 序列上重算；(2) 本側吃走勢圖的原始價基，雷達那側吃還原
 * 權息／分割後的日K；(3) 本側會畫出進行中週（圖本來就該畫到今天），雷達那側一律排除；
 * (4) {@code requestedStart} 起始週丟棄只有本側有；(5) <b>壞資料週</b>本側只要任一日 candle 不合法
 * 就丟掉整週，雷達那側改採逐欄 null、只有 {@code close} 缺值才丟整根。第 (5) 點在雙邊測試都有
 * 明確斷言（{@code ChartSeriesAlignerTest} 與 {@code WeeklyBarAggregatorTest}）。</p>
 */
public final class ChartSeriesAligner {
 private ChartSeriesAligner() {}
 public static ChartSeriesDto align(List<PricePointDto> p,List<IndicatorPointDto> i){return align(p,i,null);}
 public static ChartSeriesDto align(List<PricePointDto> p,List<IndicatorPointDto> i,LocalDate start){
  List<PricePointDto> ps=p==null?List.of():p; List<IndicatorPointDto> is=i==null?List.of():i;
  Map<String,PricePointDto> pm=index(ps,PricePointDto::tradingDate); Map<String,IndicatorPointDto> im=index(is,IndicatorPointDto::tradingDate);
  TreeSet<String> keys=new TreeSet<>(pm.keySet());keys.addAll(im.keySet()); List<String>d=new ArrayList<>(keys); Columns top=columns(d,im);
  return new ChartSeriesDto(d,d.stream().map(x->pm.containsKey(x)?pm.get(x).closePrice():null).toList(),top.ma5,top.ma20,top.ma60,top.ma240,top.k,top.d,top.j9,top.k3d2,top.rsv,top.ema12,top.ema26,top.dif,top.macd,top.osc,top.rsi5,top.rsi10,top.bias10,top.bias20,top.b10b20,top.wr9,latestOf(is),daily(d,pm,top),weekly(ps,im,start));
 }
 private static <T> Map<String,T> index(List<T> r,Function<T,String> f){Map<String,T> m=new LinkedHashMap<>();for(T x:r){String k=x==null?null:f.apply(x);if(k!=null)m.put(k,x);}return m;}
 static boolean validCandle(PricePointDto x){return x!=null&&positive(x.openPrice())&&positive(x.highPrice())&&positive(x.lowPrice())&&positive(x.closePrice())&&x.highPrice().compareTo(x.openPrice().max(x.closePrice()))>=0&&x.lowPrice().compareTo(x.openPrice().min(x.closePrice()))<=0&&x.highPrice().compareTo(x.lowPrice())>=0;}
 private static boolean positive(BigDecimal x){return x!=null&&x.signum()>0;}
 private static ChartSeriesDto.DailyFrame daily(List<String> all,Map<String,PricePointDto> pm,Columns top){
  int cut=-1;for(int n=0;n<all.size();n++)if(validCandle(pm.get(all.get(n))))cut=n;if(cut<0)return ChartSeriesDto.DailyFrame.empty();
  List<LocalDate>d=new ArrayList<>();List<BigDecimal>o=new ArrayList<>(),h=new ArrayList<>(),l=new ArrayList<>(),c=new ArrayList<>();for(String s:all.subList(0,cut+1)){d.add(LocalDate.parse(s));PricePointDto x=pm.get(s);if(validCandle(x)){o.add(x.openPrice());h.add(x.highPrice());l.add(x.lowPrice());c.add(x.closePrice());}else{o.add(null);h.add(null);l.add(null);c.add(null);}}
  Columns x=top.slice(cut+1);return dailyFrame(d,o,h,l,c,x);
 }
 private static ChartSeriesDto.WeeklyFrame weekly(List<PricePointDto> ps,Map<String,IndicatorPointDto> im,LocalDate start){
  TreeMap<Week,List<PricePointDto>> groups=new TreeMap<>();for(PricePointDto x:ps){LocalDate d=parse(x==null?null:x.tradingDate());if(d==null||(start!=null&&d.isBefore(start)))continue;Week w=Week.of(d);if(start!=null&&start.getDayOfWeek()!=DayOfWeek.MONDAY&&w.equals(Week.of(start)))continue;groups.computeIfAbsent(w,k->new ArrayList<>()).add(x);}
  List<LocalDate>d=new ArrayList<>();List<BigDecimal>o=new ArrayList<>(),h=new ArrayList<>(),l=new ArrayList<>(),c=new ArrayList<>();List<Columns> samples=new ArrayList<>();
  for(List<PricePointDto> g:groups.values()){g.sort(Comparator.comparing(PricePointDto::tradingDate));if(g.isEmpty()||g.stream().anyMatch(x->!validCandle(x)))continue;PricePointDto first=g.getFirst(),last=g.getLast();d.add(LocalDate.parse(last.tradingDate()));o.add(first.openPrice());h.add(g.stream().map(PricePointDto::highPrice).max(BigDecimal::compareTo).orElseThrow());l.add(g.stream().map(PricePointDto::lowPrice).min(BigDecimal::compareTo).orElseThrow());c.add(last.closePrice());samples.add(columnsRange(im,first.tradingDate(),last.tradingDate()));}
  if(d.isEmpty())return ChartSeriesDto.WeeklyFrame.empty();return weeklyFrame(d,o,h,l,c,Columns.transpose(samples));
 }
 private static LocalDate parse(String x){try{return x==null?null:LocalDate.parse(x);}catch(Exception e){return null;}}
 private record Week(int y,int w) implements Comparable<Week>{static Week of(LocalDate d){return new Week(d.get(WeekFields.ISO.weekBasedYear()),d.get(WeekFields.ISO.weekOfWeekBasedYear()));}public int compareTo(Week b){return y==b.y?Integer.compare(w,b.w):Integer.compare(y,b.y);}}
 private static ChartSeriesDto.DailyFrame dailyFrame(List<LocalDate>d,List<BigDecimal>o,List<BigDecimal>h,List<BigDecimal>l,List<BigDecimal>c,Columns x){return new ChartSeriesDto.DailyFrame(d,o,h,l,c,x.ma5,x.ma20,x.ma60,x.ma240,x.k,x.d,x.j9,x.k3d2,x.rsv,x.ema12,x.ema26,x.dif,x.macd,x.osc,x.rsi5,x.rsi10,x.bias10,x.bias20,x.b10b20,x.wr9,last(c),previous(c),latestColumns(x,false));}
 private static ChartSeriesDto.WeeklyFrame weeklyFrame(List<LocalDate>d,List<BigDecimal>o,List<BigDecimal>h,List<BigDecimal>l,List<BigDecimal>c,Columns x){return new ChartSeriesDto.WeeklyFrame(d,o,h,l,c,x.ma5,x.ma20,x.ma60,x.ma240,x.k,x.d,x.j9,x.k3d2,x.rsv,x.ema12,x.ema26,x.dif,x.macd,x.osc,x.rsi5,x.rsi10,x.bias10,x.bias20,x.b10b20,x.wr9,last(c),previous(c),latestColumns(x,true));}
 private static BigDecimal last(List<BigDecimal>a){for(int n=a.size()-1;n>=0;n--)if(a.get(n)!=null)return a.get(n);return null;} private static BigDecimal previous(List<BigDecimal>a){boolean seen=false;for(int n=a.size()-1;n>=0;n--)if(a.get(n)!=null){if(seen)return a.get(n);seen=true;}return null;}
 private static Columns columns(List<String>d,Map<String,IndicatorPointDto>m){return columnsRows(d.stream().map(m::get).toList());}
 private static Columns columnsRange(Map<String,IndicatorPointDto>m,String from,String to){return columnsRows(new TreeMap<>(m).entrySet().stream().filter(e->e.getKey().compareTo(from)>=0&&e.getKey().compareTo(to)<=0).map(Map.Entry::getValue).toList()).lastEach();}
 private static Columns columnsRows(List<IndicatorPointDto>r){return new Columns(col(r,IndicatorPointDto::ma5),col(r,IndicatorPointDto::ma20),col(r,IndicatorPointDto::ma60),col(r,IndicatorPointDto::ma240),col(r,IndicatorPointDto::k),col(r,IndicatorPointDto::d),col(r,IndicatorPointDto::j9),col(r,IndicatorPointDto::k3d2),col(r,IndicatorPointDto::rsv),col(r,IndicatorPointDto::ema12),col(r,IndicatorPointDto::ema26),col(r,IndicatorPointDto::dif),col(r,IndicatorPointDto::macd),col(r,IndicatorPointDto::osc),col(r,IndicatorPointDto::rsi5),col(r,IndicatorPointDto::rsi10),col(r,IndicatorPointDto::bias10),col(r,IndicatorPointDto::bias20),col(r,IndicatorPointDto::b10b20),col(r,IndicatorPointDto::wr9));}
 private static List<BigDecimal> col(List<IndicatorPointDto>r,Function<IndicatorPointDto,BigDecimal>f){return r.stream().map(x->x==null?null:f.apply(x)).toList();}
 private record Columns(List<BigDecimal>ma5,List<BigDecimal>ma20,List<BigDecimal>ma60,List<BigDecimal>ma240,List<BigDecimal>k,List<BigDecimal>d,List<BigDecimal>j9,List<BigDecimal>k3d2,List<BigDecimal>rsv,List<BigDecimal>ema12,List<BigDecimal>ema26,List<BigDecimal>dif,List<BigDecimal>macd,List<BigDecimal>osc,List<BigDecimal>rsi5,List<BigDecimal>rsi10,List<BigDecimal>bias10,List<BigDecimal>bias20,List<BigDecimal>b10b20,List<BigDecimal>wr9){
  Columns slice(int n){return new Columns(ma5.subList(0,n),ma20.subList(0,n),ma60.subList(0,n),ma240.subList(0,n),k.subList(0,n),d.subList(0,n),j9.subList(0,n),k3d2.subList(0,n),rsv.subList(0,n),ema12.subList(0,n),ema26.subList(0,n),dif.subList(0,n),macd.subList(0,n),osc.subList(0,n),rsi5.subList(0,n),rsi10.subList(0,n),bias10.subList(0,n),bias20.subList(0,n),b10b20.subList(0,n),wr9.subList(0,n));}
  Columns lastEach(){return new Columns(one(ma5),one(ma20),one(ma60),one(ma240),one(k),one(d),one(j9),one(k3d2),one(rsv),one(ema12),one(ema26),one(dif),one(macd),one(osc),one(rsi5),one(rsi10),one(bias10),one(bias20),one(b10b20),one(wr9));} static List<BigDecimal> one(List<BigDecimal>a){return Collections.singletonList(last(a));} static BigDecimal last(List<BigDecimal>a){for(int n=a.size()-1;n>=0;n--)if(a.get(n)!=null)return a.get(n);return null;}
  static Columns transpose(List<Columns>a){return new Columns(all(a,x->x.ma5),all(a,x->x.ma20),all(a,x->x.ma60),all(a,x->x.ma240),all(a,x->x.k),all(a,x->x.d),all(a,x->x.j9),all(a,x->x.k3d2),all(a,x->x.rsv),all(a,x->x.ema12),all(a,x->x.ema26),all(a,x->x.dif),all(a,x->x.macd),all(a,x->x.osc),all(a,x->x.rsi5),all(a,x->x.rsi10),all(a,x->x.bias10),all(a,x->x.bias20),all(a,x->x.b10b20),all(a,x->x.wr9));} static List<BigDecimal> all(List<Columns>a,Function<Columns,List<BigDecimal>>f){return a.stream().map(x->f.apply(x).getFirst()).toList();}}
 private static ChartSeriesDto.Latest latestOf(List<IndicatorPointDto>a){return a.isEmpty()?null:latest(a.getLast(),a.size()>1?a.get(a.size()-2):null);}
 private static ChartSeriesDto.Latest latestColumns(Columns x,boolean weekly){if(x.ma5.isEmpty())return null;int i=x.ma5.size()-1;Function<List<BigDecimal>,BigDecimal> q=a->weekly?at(a,i-1):prior(a,i);return new ChartSeriesDto.Latest(at(x.ma5,i),at(x.ma20,i),at(x.ma60,i),at(x.ma240,i),at(x.k,i),at(x.d,i),at(x.j9,i),at(x.k3d2,i),at(x.rsv,i),q.apply(x.k),q.apply(x.d),q.apply(x.j9),q.apply(x.k3d2),q.apply(x.rsv),at(x.ema12,i),at(x.ema26,i),at(x.dif,i),at(x.macd,i),at(x.osc,i),at(x.rsi5,i),at(x.rsi10,i),at(x.bias10,i),at(x.bias20,i),at(x.b10b20,i),at(x.wr9,i),q.apply(x.ema12),q.apply(x.ema26),q.apply(x.dif),q.apply(x.macd),q.apply(x.rsi5),q.apply(x.rsi10),q.apply(x.bias10),q.apply(x.bias20),q.apply(x.b10b20),q.apply(x.wr9));}
 private static BigDecimal at(List<BigDecimal>a,int i){return i>=0&&i<a.size()?a.get(i):null;}private static BigDecimal prior(List<BigDecimal>a,int i){for(int n=i-1;n>=0;n--)if(a.get(n)!=null)return a.get(n);return null;}
 private static ChartSeriesDto.Latest latest(IndicatorPointDto a,IndicatorPointDto b){return new ChartSeriesDto.Latest(a.ma5(),a.ma20(),a.ma60(),a.ma240(),a.k(),a.d(),a.j9(),a.k3d2(),a.rsv(),b==null?null:b.k(),b==null?null:b.d(),b==null?null:b.j9(),b==null?null:b.k3d2(),b==null?null:b.rsv(),a.ema12(),a.ema26(),a.dif(),a.macd(),a.osc(),a.rsi5(),a.rsi10(),a.bias10(),a.bias20(),a.b10b20(),a.wr9(),b==null?null:b.ema12(),b==null?null:b.ema26(),b==null?null:b.dif(),b==null?null:b.macd(),b==null?null:b.rsi5(),b==null?null:b.rsi10(),b==null?null:b.bias10(),b==null?null:b.bias20(),b==null?null:b.b10b20(),b==null?null:b.wr9());}
}
