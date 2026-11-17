package com.steven.assets.service.tradereport;

import jakarta.mail.*;
import jakarta.mail.internet.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.*;
import org.springframework.stereotype.Component;
import java.io.*;
import java.math.BigDecimal;
import java.nio.*;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.regex.*;

/** Bounded, offline parser. No URL, attachment or HTML execution is ever performed. */
@Component
public class TradeReportParser {
    public static final String VERSION="FUBON_EMAIL_V1";
    public static final int MAX_BYTES=512*1024;
    private static final List<String> HEADER=List.of("股票名稱","交易類別","成交股數","成交單價","成交價金","委託書編號","成交時間","備註");
    private static final Pattern DATE=Pattern.compile("(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*日");
    public record Row(String stockCode,String stockName,String side,LocalDate tradeDate,BigDecimal shares,
                      BigDecimal price,BigDecimal amount,String orderNo,String filledTime) {}
    public record Parsed(String rawSha256,List<Row> rows) {}
    public static class Invalid extends RuntimeException { public Invalid(String code){super(code);} }
    public Parsed parse(String base64, LocalDate today) {
        try {
            if(base64==null || base64.length()>((MAX_BYTES+2)/3)*4) throw new Invalid("MIME_TOO_LARGE");
            byte[] raw=Base64.getDecoder().decode(base64);
            if(raw.length==0||raw.length>MAX_BYTES)throw new Invalid("MIME_TOO_LARGE");
            MimeMessage msg=new MimeMessage(Session.getInstance(new Properties()),new ByteArrayInputStream(raw));
            List<String> html=new ArrayList<>(); List<String> plain=new ArrayList<>();
            walk(msg,0,new int[]{0},html,plain);
            List<Row> result=null;
            for(String body:html){ List<Row> rows=parseHtml(body,today); if(rows==null)continue;
                if(result!=null&&!result.equals(rows))throw new Invalid("AMBIGUOUS_BODY"); result=rows; }
            if(result==null)throw new Invalid("REPORT_TABLE_MISSING");
            // A text alternative is accepted only if it contains every exact normalized row fact.
            for(String p:plain)for(Row r:result){String text=compact(p).replace(",", "");
                if(!text.contains(r.stockCode())||!text.contains(r.orderNo())||!text.contains(r.filledTime())
                    ||!text.contains(r.shares().toPlainString())||!text.contains(r.price().toPlainString()))throw new Invalid("AMBIGUOUS_BODY");}
            Matcher subject=DATE.matcher(Optional.ofNullable(msg.getSubject()).orElse(""));
            while(subject.find())if(!date(subject).equals(result.getFirst().tradeDate()))throw new Invalid("DATE_MISMATCH");
            return new Parsed(hash(raw),List.copyOf(result));
        }catch(Invalid e){throw e;}catch(Exception e){throw new Invalid("INVALID_MIME");}
    }
    private void walk(Part p,int depth,int[] count,List<String> html,List<String> plain)throws Exception{
        if(depth>5||++count[0]>16)throw new Invalid("MIME_STRUCTURE_LIMIT");
        String filename=p.getFileName();
        if(filename!=null&&!filename.isBlank()){
            if(filename.equalsIgnoreCase("smime.p7s")&&(p.isMimeType("application/x-pkcs7-signature")||p.isMimeType("application/pkcs7-signature")))return;
            throw new Invalid("UNSUPPORTED_ATTACHMENT");
        }
        if(p.isMimeType("multipart/*")){
            if(p.isMimeType("multipart/signed")){
                String protocol=new ContentType(p.getContentType()).getParameter("protocol");
                if(!"application/x-pkcs7-signature".equalsIgnoreCase(protocol)&&!"application/pkcs7-signature".equalsIgnoreCase(protocol))throw new Invalid("UNSUPPORTED_SIGNATURE");
            }
            Object v=p.getContent();if(!(v instanceof Multipart m))throw new Invalid("INVALID_MULTIPART");
            for(int i=0;i<m.getCount();i++)walk(m.getBodyPart(i),depth+1,count,html,plain);return;
        }
        if(p.isMimeType("application/x-pkcs7-signature")||p.isMimeType("application/pkcs7-signature"))return;
        if(!p.isMimeType("text/html")&&!p.isMimeType("text/plain"))throw new Invalid("UNSUPPORTED_PART");
        String charset=new ContentType(p.getContentType()).getParameter("charset");
        if(charset==null)charset="UTF-8";
        if(!Set.of("utf-8","utf8","big5","big-5","us-ascii").contains(charset.toLowerCase(Locale.ROOT)))throw new Invalid("UNSUPPORTED_CHARSET");
        byte[] bytes=p.getInputStream().readNBytes(MAX_BYTES+1);if(bytes.length>MAX_BYTES)throw new Invalid("MIME_TOO_LARGE");
        String s=Charset.forName(charset).newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        (p.isMimeType("text/html")?html:plain).add(s);
    }
    private List<Row> parseHtml(String html,LocalDate today){
        Document doc=Jsoup.parse(html); doc.select("script,style").remove();
        Element table=null;Element header=null;
        for(Element t:doc.select("table"))for(Element tr:t.select("tr")){
            if(tr.closest("table")!=t)continue;
            List<String> values=tr.children().stream().filter(e->e.tagName().equals("td")||e.tagName().equals("th")).map(e->compact(e.text())).toList();
            if(values.contains("股票名稱")||values.contains("成交股數")){
                if(!values.equals(HEADER))throw new Invalid("UNKNOWN_REPORT_COLUMNS");
                if(table!=null)throw new Invalid("AMBIGUOUS_TABLE");table=t;header=tr;
            }
        }
        if(table==null)return null;
        // The report date is explicitly in the introduction, never the mail delivery date.
        String text=doc.text();int intro=text.indexOf("以下是帳號");int end=text.indexOf("的成交資料",intro);
        if(intro<0||end<0)throw new Invalid("TRADE_DATE_MISSING");
        Matcher dm=DATE.matcher(text.substring(intro,end));if(!dm.find())throw new Invalid("TRADE_DATE_MISSING");
        LocalDate day=date(dm);if(dm.find()||day.isAfter(today))throw new Invalid("INVALID_TRADE_DATE");
        List<Row> rows=new ArrayList<>();boolean past=false;
        for(Element tr:table.select("tr")){
            if(tr.closest("table")!=table)continue;if(tr==header){past=true;continue;}if(!past)continue;
            List<String> c=tr.children().stream().filter(e->e.tagName().equals("td")||e.tagName().equals("th")).map(e->compact(e.text())).toList();
            if(c.isEmpty())continue;if(c.size()!=8)throw new Invalid("INVALID_REPORT_ROW");
            Matcher name=Pattern.compile("^([0-9]{4,6}[A-Z]?)([^0-9].*)$").matcher(c.get(0));if(!name.matches())throw new Invalid("INVALID_STOCK");
            String side=switch(c.get(1)){case "現買"->"買";case "現賣"->"賣";default->throw new Invalid("INVALID_SIDE");};
            BigDecimal qty=number(c.get(2),0),price=number(c.get(3),6),amount=number(c.get(4),2);
            if(qty.precision()>15||price.precision()>17||amount.precision()>20||qty.multiply(price).compareTo(amount)!=0)throw new Invalid("AMOUNT_MISMATCH");
            if(!c.get(5).matches("[A-Za-z0-9_-]{1,50}"))throw new Invalid("INVALID_ORDER_NO");
            if(!c.get(6).matches("\\d{2}:\\d{2}:\\d{2}"))throw new Invalid("INVALID_FILLED_TIME");LocalTime.parse(c.get(6));
            rows.add(new Row(name.group(1),name.group(2),side,day,qty,price,amount,c.get(5),c.get(6)));
        }
        if(rows.isEmpty()||rows.size()>100)throw new Invalid("REPORT_ROW_LIMIT");return rows;
    }
    private static String compact(String s){return s.replace('\u00a0',' ').replaceAll("\\s+","").trim();}
    private static LocalDate date(Matcher m){return LocalDate.of(Integer.parseInt(m.group(1)),Integer.parseInt(m.group(2)),Integer.parseInt(m.group(3)));}
    private static BigDecimal number(String s,int scale){
        if(!s.matches("(?:[0-9]+|[1-9][0-9]{0,2}(?:,[0-9]{3})+)(?:\\.[0-9]+)?"))throw new Invalid("INVALID_NUMBER");
        BigDecimal n=new BigDecimal(s.replace(",",""));if(n.signum()<=0||n.scale()>scale)throw new Invalid("INVALID_NUMBER");return n.stripTrailingZeros().scale()<0?n.setScale(0):n.stripTrailingZeros();
    }
    public static String hash(byte[] b){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}catch(Exception e){throw new IllegalStateException(e);}}
}
