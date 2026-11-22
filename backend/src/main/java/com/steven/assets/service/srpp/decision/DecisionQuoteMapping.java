package com.steven.assets.service.srpp.decision;

import com.steven.assets.dto.QuoteDetailDto;
import com.steven.assets.service.PriceQueryService;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static com.steven.assets.service.srpp.decision.DecisionFacts.*;

/**
 * The one canonical mapping from cached live-price/NAV/persisted best-five reads to D-130 frozen facts.
 * Task 626 reuses it for order-plan v2 refresh frames; there is no second mapping. Pure: no IO.
 */
public final class DecisionQuoteMapping {
    private DecisionQuoteMapping() {}
    /** Identity-checked quote with the verified NAV premium, or null when the cached quote identity fails. */
    public static Quote quote(String code,PriceQueryService.LivePrice q,PriceQueryService.EtfNav nav){
        boolean quoteIdentity=q!=null&&code.equals(q.stockCode())&&"台股".equals(q.market())&&Boolean.FALSE.equals(q.closed());
        boolean navIdentity=nav!=null&&code.equals(nav.stockCode())&&"台股".equals(nav.market())&&nav.nav()!=null&&nav.nav().signum()>0&&nav.source()!=null&&!nav.source().isBlank();
        return !quoteIdentity?null:new Quote(q.price(),q.previousClose(),exactLots(q.volume()),localDate(q.tradingDate()),quoteTime(q.updatedAt()),q.source(),q.quoteStatus(),q.closed(),!navIdentity?null:nav.premiumDiscountPct(),!navIdentity?null:navDate(nav.navAsOf()));
    }
    public static Book book(QuoteDetailDto.Response b,String code){boolean valid=b!=null&&b.available()&&code.equals(b.stockCode())&&"台股".equals(b.market())&&"OPEN".equals(b.marketStatus());List<Level>levels=new ArrayList<>();if(b!=null&&b.levels()!=null){int i=0;for(var l:b.levels()){valid&=l.level()==++i&&l.bidVolumeLots()!=null&&l.askVolumeLots()!=null;levels.add(new Level(l.bidPrice(),l.bidVolumeLots()==null?-1:l.bidVolumeLots(),l.askPrice(),l.askVolumeLots()==null?-1:l.askVolumeLots()));}}return new Book(b==null?null:b.source(),b==null||b.sourceTime()==null?null:b.sourceTime().atZone(ZoneId.of("Asia/Taipei")).toLocalDate(),b==null?null:b.sourceTime(),b==null?null:b.fetchedAt(),List.copyOf(levels),valid);}
    static Long exactLots(Long shares){return shares!=null&&shares>0&&shares%1000==0?shares/1000:null;}
    private static LocalDate localDate(String d){try{return LocalDate.parse(d);}catch(RuntimeException e){return null;}}
    private static Instant quoteTime(String s){try{return Instant.parse(s);}catch(RuntimeException e){try{return LocalDateTime.parse(s).atZone(ZoneId.of("Asia/Taipei")).toInstant();}catch(RuntimeException ex){return null;}}}
    private static LocalDate navDate(String s){try{return LocalDate.parse(s.substring(0,8),DateTimeFormatter.BASIC_ISO_DATE);}catch(RuntimeException e){try{return LocalDate.parse(s);}catch(RuntimeException ex){return null;}}}
}
