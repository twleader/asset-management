package com.steven.assets.service.tradereport;
import org.junit.jupiter.api.Test;
import java.nio.charset.*;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class TradeReportParserTest {
 static final LocalDate DAY=LocalDate.of(2026,11,17);
 static String html(String rows){return "<html><body><p>以下是帳號 9***-**000-0在 2026 年 11 月 17 日的成交資料</p><table><tr><td>廣告</td></tr></table><img src='https://invalid.invalid/tracker'><table><thead><tr><td>股票名稱</td><td>交易類別</td><td>成交股數</td><td>成交單價</td><td>成交價金</td><td>委託書編號</td><td>成交時間</td><td>備註</td></tr></thead><tbody>"+rows+"</tbody></table><p>成交價金不含手續費</p></body></html>";}
 static String row(String code,String order){return "<tr><td>"+code+"測試ETF</td><td>現買</td><td>1,000</td><td>9.95</td><td>9,950</td><td>"+order+"</td><td>13:09:51</td><td>&nbsp;</td></tr>";}
 static String mime(String html){return mime(html,"UTF-8");}
 static String mime(String html,String charset){String raw="MIME-Version: 1.0\r\nSubject: report\r\nContent-Type: text/html; charset="+charset+"\r\nContent-Transfer-Encoding: base64\r\n\r\n"+Base64.getMimeEncoder().encodeToString(html.getBytes(Charset.forName(charset)));return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));}
 @Test void validOfflineUtf8AndBig5(){for(String charset:List.of("UTF-8","Big5")){var p=new TradeReportParser().parse(mime(html(row("0050","aA123")),charset),DAY);assertThat(p.rows()).hasSize(1);assertThat(p.rows().getFirst().stockCode()).isEqualTo("0050");assertThat(p.rows().getFirst().amount()).isEqualByComparingTo("9950");}}
 @Test void multiRows(){assertThat(new TradeReportParser().parse(mime(html(row("0050","a1")+row("00679B","a2"))),DAY).rows()).hasSize(2);}
 @Test void invalidNumbersDateAndSide(){String raw=html(row("0050","aA123"));for(String bad:List.of(raw.replace("1,000","10,00"),raw.replace("9,950","9,951"),raw.replace("現買","融買"),raw.replace("13:09:51","25:09:51"),raw.replace("2026 年","2027 年"),raw.replace("委託書編號","未知欄位"))){assertThatThrownBy(()->new TradeReportParser().parse(mime(bad),DAY)).isInstanceOf(TradeReportParser.Invalid.class);}}
 @Test void oversizedAndInvalidBase64(){assertThatThrownBy(()->new TradeReportParser().parse("!",DAY)).isInstanceOf(TradeReportParser.Invalid.class);assertThatThrownBy(()->new TradeReportParser().parse(Base64.getEncoder().encodeToString(new byte[TradeReportParser.MAX_BYTES+1]),DAY)).isInstanceOf(TradeReportParser.Invalid.class);}
 @Test void duplicateTableRejected(){String body=html(row("0050","a1"));assertThatThrownBy(()->new TradeReportParser().parse(mime(body+body),DAY)).isInstanceOf(TradeReportParser.Invalid.class);}
 @Test void signedMimeIgnoresOnlySignature(){String body="MIME-Version: 1.0\r\nContent-Type: multipart/signed; boundary=x; protocol=\"application/x-pkcs7-signature\"\r\n\r\n--x\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n"+html(row("0050","a1"))+"\r\n--x\r\nContent-Type: application/x-pkcs7-signature; name=smime.p7s\r\nContent-Disposition: attachment; filename=smime.p7s\r\n\r\ndummy\r\n--x--";assertThat(new TradeReportParser().parse(Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8)),DAY).rows()).hasSize(1);assertThatThrownBy(()->new TradeReportParser().parse(Base64.getEncoder().encodeToString(body.replace("smime.p7s","report.pdf").getBytes(StandardCharsets.UTF_8)),DAY)).isInstanceOf(TradeReportParser.Invalid.class);}
}
