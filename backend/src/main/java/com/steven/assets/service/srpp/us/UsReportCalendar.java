package com.steven.assets.service.srpp.us;

import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.service.MarketDataService;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;

/** Read-only NY cash-equity calendar. Finite exchange-published coverage, never a weekday fallback.
 * Source: NYSE Group's 2026/2027/2028 holiday and early-closing calendar (2025 announcement).
 * https://www.nyse.com/markets/hours-calendars
 */
@Component
public final class UsReportCalendar {
    public static final ZoneId NY=ZoneId.of("America/New_York"),TPE=ZoneId.of("Asia/Taipei");
    public static final String VERSION="NYSE_CASH_2026_2028_V1";
    public static final Set<LocalDate> EARLY_CLOSE=Set.of(LocalDate.of(2026,11,27),LocalDate.of(2026,12,24),LocalDate.of(2027,11,26),LocalDate.of(2028,7,3),LocalDate.of(2028,11,24));
    private final MarketDataService market;
    public UsReportCalendar(MarketDataService market){this.market=market;}
    public record Session(LocalDate date,Instant open,Instant close){
        public boolean contains(Instant at){return !at.isBefore(open)&&at.isBefore(close);}
        public ObjectNode json(){return JsonNodeFactory.instance.objectNode().put("timezone",NY.getId()).put("open",open.toString()).put("close",close.toString()).put("calendarVersion",VERSION);}
    }
    public boolean trading(LocalDate date){
        if(date==null||date.getYear()<2026||date.getYear()>2028)throw new UsReportProblem("CALENDAR_UNAVAILABLE");
        Optional<Boolean> known;
        try{known=market.isTradingDayCachedOnly("美股",date);}catch(RuntimeException unavailable){throw new UsReportProblem("CALENDAR_UNAVAILABLE");}
        if(known==null||known.isEmpty())throw new UsReportProblem("CALENDAR_UNAVAILABLE");return known.get();
    }
    public Session session(LocalDate date){if(!trading(date))throw new UsReportProblem("NON_TRADING_DAY");return new Session(date,date.atTime(9,30).atZone(NY).toInstant(),date.atTime(EARLY_CLOSE.contains(date)?LocalTime.of(13,0):LocalTime.of(16,0)).atZone(NY).toInstant());}
    public boolean producerWindow(Instant at){ZonedDateTime ny=at.atZone(NY);LocalTime t=ny.toLocalTime();return !t.isBefore(LocalTime.of(9,35))&&t.isBefore(LocalTime.of(10,45))&&session(ny.toLocalDate()).contains(at);}
    public boolean reportWindow(Instant at){ZonedDateTime ny=at.atZone(NY);LocalTime t=ny.toLocalTime();return !t.isBefore(LocalTime.of(9,35))&&t.isBefore(LocalTime.of(10,45))&&session(ny.toLocalDate()).contains(at);}
    public String displayTime(LocalDate session){return session.atTime(9,45).atZone(NY).withZoneSameInstant(TPE).toLocalTime().toString();}
    public ArrayNode days(LocalDate from,LocalDate through){if(from==null||through==null||from.isAfter(through)||from.plusYears(4).isBefore(through))throw new UsReportProblem("CALENDAR_UNAVAILABLE");ArrayNode rows=JsonNodeFactory.instance.arrayNode();for(LocalDate d=from;!d.isAfter(through);d=d.plusDays(1))rows.addObject().put("date",d.toString()).put("us",trading(d));return rows;}
    /** Existing per-market authority; Taiwan holidays are cache-only, so a miss cannot become a date. */
    public LocalDate valuationDate(String name,Instant at){
        ZoneId zone;LocalTime open;
        switch(name){case "美股"->{zone=NY;open=LocalTime.of(9,30);}case "台股"->{zone=TPE;open=LocalTime.of(9,0);}case "英股"->{zone=ZoneId.of("Europe/London");open=LocalTime.of(8,0);}default->throw new UsReportProblem("CALENDAR_UNAVAILABLE");}
        ZonedDateTime local=at.atZone(zone);LocalDate candidate=local.toLocalTime().isBefore(open)?local.toLocalDate().minusDays(1):local.toLocalDate();
        for(int i=0;i<20;i++,candidate=candidate.minusDays(1)){Optional<Boolean> flag;
            if("美股".equals(name))flag=Optional.of(trading(candidate));else flag=market.isTradingDayCachedOnly(name,candidate);
            if(flag==null||flag.isEmpty())throw new UsReportProblem("CALENDAR_UNAVAILABLE");if(flag.get())return candidate;}
        throw new UsReportProblem("CALENDAR_UNAVAILABLE");
    }
}
