package com.steven.assets.service.srpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.srpp.SrppJcs;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/** Task 480 fixed renderer. No template engine is used because every byte is a contract. */
public final class DailyReportRenderer {
    private DailyReportRenderer() {}
    private static final Pattern PLACEHOLDER=Pattern.compile("\\{\\{[^{}]+\\}");
    private static final Pattern HASH=Pattern.compile("[a-f0-9]{64}");
    private static final Pattern SUFFIX=Pattern.compile("(?: 重跑-\\d{6}(?:-\\d+)?)?(?: 未完成)?");
    private static final String CANVAS="background:#ffffff;color:#000000;font-family:Arial,'PingFang TC',sans-serif;font-size:14px;line-height:1.6;";
    private static final String TITLE="color:#000000;font-weight:700;margin:16px 0 6px 0;";
    private static final String TABLE="role=\"presentation\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"border-collapse:collapse;width:100%;background:#ffffff;color:#000000;\"";
    private static final String TH="background:#e2e8f0;color:#000000;font-weight:700;text-align:left;border:1px solid #94a3b8;padding:6px 8px;";
    private static final String TD="background:#ffffff;color:#000000;text-align:left;border:1px solid #94a3b8;padding:6px 8px;";
    public record Rendered(String subject,String html,String text,String htmlSha256,String textSha256) {}

    public static Rendered validateAndRender(JsonNode root) {
        String key=text(root,"idempotencyKey");
        if (!key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}")) bad("INVALID_IDEMPOTENCY_KEY");
        String profile=text(root,"profile");
        if (!"core".equals(profile)&&!"appendix".equals(profile)) bad("INVALID_PROFILE");
        JsonNode facts=root.path("facts");
        if (!facts.isObject()) bad("FACT_REQUIRED:facts");
        String[] required={"trading_date","slot","consumer","generated_at","policy_bundle_sha256","input_snapshot_sha256","decision_id","conclusion"};
        Map<String,String> values=new LinkedHashMap<>();
        for(String f:required) { String v=text(facts,f); if(v.isBlank()) bad("decision_id".equals(f)?"DECISION_ID_REQUIRED":"FACT_REQUIRED:"+f); values.put(f,v); }
        if(!Set.of("09:05","11:40").contains(values.get("slot"))) bad("INVALID_SLOT");
        if(!Set.of("Codex","Claude").contains(values.get("consumer"))) bad("INVALID_CONSUMER");
        JsonNode payload=facts.path("payload"); if(!payload.isObject()) bad("CORE_APPENDIX_DECISION_MISMATCH");
        if(!values.get("decision_id").equals(text(payload,"decision_id"))) bad("CORE_APPENDIX_DECISION_MISMATCH");
        for(String field:List.of("expectedHtmlSha256","expectedTextSha256")) if(!HASH.matcher(text(root,field)).matches()) bad("INVALID_HASH:"+field);
        for(String f:required) if(PLACEHOLDER.matcher(values.get(f)).find()) bad("FACT_PLACEHOLDER_UNFILLED");
        String suffix=payload.has("subject_suffix") ? text(payload,"subject_suffix") : "";
        if(!SUFFIX.matcher(suffix).matches()) bad("INVALID_SUBJECT_SUFFIX");
        JsonNode summary=payload.path("summary"); if(!summary.isArray()) bad("SUMMARY_REQUIRED:"+profile);
        for(JsonNode n:summary) if(PLACEHOLDER.matcher(string(n)).find()) bad("SUMMARY_PLACEHOLDER_UNFILLED:"+profile);
        JsonNode tables=payload.path("tables"); if(!tables.isArray()) bad("TABLES_REQUIRED:"+profile);
        int min="core".equals(profile)?4:6; if(tables.size()<min) bad("MINIMUM_TABLES_REQUIRED:"+profile+":"+min);
        for(JsonNode table:tables) validateTable(table);
        validateEmergency(tables,summary);
        String label="core".equals(profile)?"核心":"附錄";
        String subject="[SRPP 每日持股市場交易建議-"+label+"] ("+values.get("consumer")+") "+values.get("trading_date")+" "+values.get("slot")+suffix;
        List<String> h=new ArrayList<>(); h.add("<div style=\""+CANVAS+"\">"); h.add("<p style=\""+TITLE+"font-size:18px;\">"+esc(subject)+"</p>");
        h.add("<p>結論：<b style=\"color:#c00000;font-weight:700;\">"+esc(values.get("conclusion"))+"</b></p>");
        h.add("<p>產生時間："+esc(values.get("generated_at"))+"<br>POLICY_BUNDLE_SHA256："+esc(values.get("policy_bundle_sha256"))+"<br>INPUT_SNAPSHOT_SHA256："+esc(values.get("input_snapshot_sha256"))+"</p>");
        for(JsonNode item:summary) h.add("<p>"+esc(string(item))+"</p>");
        for(JsonNode table:tables) { h.add("<p style=\""+TITLE+"\">"+esc(text(table,"title"))+"</p><table "+TABLE+"><thead><tr>"); for(JsonNode c:table.path("columns")) h.add("<th style=\""+TH+"\">"+esc(string(c))+"</th>"); h.add("</tr></thead><tbody>"); for(JsonNode row:table.path("rows")){h.add("<tr>");for(JsonNode c:row)h.add("<td style=\""+TD+"\">"+esc(string(c))+"</td>");h.add("</tr>");} h.add("</tbody></table>"); }
        h.add("</div>\n"); String html=String.join("\n",h);
        List<String> p=new ArrayList<>(List.of(subject,"","結論："+values.get("conclusion"),"產生時間："+values.get("generated_at"),"POLICY_BUNDLE_SHA256："+values.get("policy_bundle_sha256"),"INPUT_SNAPSHOT_SHA256："+values.get("input_snapshot_sha256"),""));
        for(JsonNode item:summary)p.add(string(item)); for(JsonNode table:tables){p.add("");p.add(text(table,"title"));for(JsonNode c:table.path("columns"))p.add(string(c));for(JsonNode row:table.path("rows")){for(JsonNode c:row)p.add(string(c));p.add("");}}
        String plain=String.join("\n",p)+"\n"; return new Rendered(subject,html,plain,sha(html),sha(plain));
    }
    private static void validateTable(JsonNode t){ if(!t.isObject()||text(t,"title").isBlank())bad("TABLE_TITLE_REQUIRED");JsonNode c=t.path("columns");if(!c.isArray()||c.isEmpty())bad("TABLE_COLUMNS_REQUIRED");for(JsonNode column:c)if(textValueBlank(column))bad("TABLE_COLUMNS_REQUIRED");JsonNode rows=t.path("rows");if(!rows.isArray())bad("TABLE_ROWS_REQUIRED");if(anyPlaceholder(t.path("title")))bad("TABLE_PLACEHOLDER_UNFILLED");for(JsonNode column:c)if(anyPlaceholder(column))bad("TABLE_PLACEHOLDER_UNFILLED");for(JsonNode r:rows){if(!r.isArray()||r.size()!=c.size())bad("TABLE_ROW_WIDTH_MISMATCH");for(JsonNode cell:r)if(anyPlaceholder(cell))bad("TABLE_PLACEHOLDER_UNFILLED");}}
    private static void validateEmergency(JsonNode tables, JsonNode summary){ List<JsonNode> found=new ArrayList<>();for(JsonNode t:tables)if(text(t,"title").replaceAll("\\s","").contains("00865B子帳與今日建議"))found.add(t);if(found.size()!=1)bad("EMERGENCY_00865B_TABLE_REQUIRED");JsonNode t=found.getFirst();int[] idx={index(t,"子帳用途"),index(t,"券商"),index(t,"持有.*(?:單位|市值)"),index(t,"目標.*缺口"),index(t,"今日建議.*買進後歸屬|買進後歸屬")};for(int i:idx)if(i<0)bad("EMERGENCY_00865B_COLUMNS_REQUIRED");if(t.path("rows").size()!=2)bad("EMERGENCY_00865B_TWO_SUBACCOUNTS_REQUIRED"); JsonNode e=null,s=null;for(JsonNode r:t.path("rows")){String v=string(r.get(idx[0]));if(v.contains("緊急備用金"))e=r;if(v.contains("策略配置"))s=r;} if(e==null||s==null||!validRow(e,idx,"緊急備用金","國泰證券")||!validRow(s,idx,"策略配置","富邦證券"))bad("EMERGENCY_00865B_ACCOUNT_ASSIGNMENT_INVALID"); if(!validAction(e,idx,"緊急備用金","國泰")||!validAction(s,idx,"策略配置","富邦"))bad("EMERGENCY_00865B_ACTION_ATTRIBUTION_REQUIRED"); boolean unverified=Pattern.compile("UNVERIFIED|無法核對|無法確認",Pattern.CASE_INSENSITIVE).matcher(join(e)+" "+join(s)).find();if(unverified){if(!blockedAction(e,idx)||!blockedAction(s,idx))bad("EMERGENCY_00865B_UNVERIFIED_NOT_BLOCKED");String all=join(summary)+" "+join(tables);if(Pattern.compile("00865B.{0,80}(?:買進|加碼).{0,40}[1-9][\\d,]*\\s*(?:張|股|元)|(?:買進|加碼).{0,40}00865B.{0,80}[1-9][\\d,]*\\s*(?:張|股|元)").matcher(all).find())bad("EMERGENCY_00865B_UNVERIFIED_BUY");}}
    private static int index(JsonNode t,String re){Pattern p=Pattern.compile(re);for(int i=0;i<t.path("columns").size();i++)if(p.matcher(string(t.path("columns").get(i))).find())return i;return -1;} private static boolean validRow(JsonNode r,int[]x,String purpose,String broker){for(int i:x){String v=string(r.get(i)).trim();if(v.isEmpty()||v.contains("{{")||v.contains("}}")||Pattern.compile("\\b(?:TODO|TBD)\\b",Pattern.CASE_INSENSITIVE).matcher(v).find())return false;}return string(r.get(x[0])).contains("00865B")&&string(r.get(x[0])).contains(purpose)&&string(r.get(x[1])).contains(broker);} private static boolean validAction(JsonNode r,int[]x,String p,String b){String a=string(r.get(x[4]));return a.matches("(?s).*(買進後歸屬|買後歸屬).*")&&a.contains(p)&&a.contains(b)&&(a.matches("(?s).*\\d[\\d,]*(?:\\.\\d+)?\\s*(?:張|股|元).*")||a.matches("(?s).*(不買進|無買進|停止買進|不可執行).*"));} private static boolean blockedAction(JsonNode r,int[]x){return string(r.get(x[4])).matches("(?s).*(不可執行|不得給可執行買進|停止買進).*");}
    private static boolean anyPlaceholder(JsonNode n){return PLACEHOLDER.matcher(string(n)).find();} private static boolean textValueBlank(JsonNode n){return !n.isTextual()||n.textValue().trim().isEmpty();} private static String text(JsonNode n,String f){JsonNode v=n.get(f);return v!=null&&v.isTextual()?v.textValue().trim():"";} private static String string(JsonNode n){return n==null?"null":n.isTextual()?n.textValue():n.isValueNode()?n.asText():n.toString();} private static String join(JsonNode n){StringBuilder s=new StringBuilder();if(n.isArray()||n.isObject())n.forEach(x->s.append(join(x)).append(' '));else s.append(string(n));return s.toString();} private static String esc(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");} private static String sha(String s){return SrppJcs.sha256Hex(s);} private static void bad(String code){throw new DailyReportMailProblem(HttpStatus.BAD_REQUEST,code);}
}
