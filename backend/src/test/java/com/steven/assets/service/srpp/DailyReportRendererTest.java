package com.steven.assets.service.srpp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DailyReportRendererTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void fixedRendererEscapesOnlyHtmlTextNodesAndEndsWithLf() throws Exception {
        var root=json.readTree(valid()); var rendered=DailyReportRenderer.validateAndRender(root);
        assertTrue(rendered.html().contains("A&amp;B &lt;C&gt; 'quote' \"quote\""));
        assertTrue(rendered.html().endsWith("</div>\n")); assertTrue(rendered.text().endsWith("\n"));
        assertEquals(64,rendered.htmlSha256().length()); assertEquals(64,rendered.textSha256().length());
    }
    @Test void unverifiedEmergencyRowCannotCarryExecutableBuy() throws Exception {
        String value=valid().replace("不可執行，買進後歸屬緊急備用金國泰", "買進 1 張，買進後歸屬緊急備用金國泰 UNVERIFIED");
        DailyReportMailProblem ex=assertThrows(DailyReportMailProblem.class,()->DailyReportRenderer.validateAndRender(json.readTree(value)));
        assertEquals("EMERGENCY_00865B_UNVERIFIED_NOT_BLOCKED",ex.code);
    }
    private static String valid(){return """
      {"idempotencyKey":"Codex-20261007:core","profile":"core","expectedHtmlSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","expectedTextSha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","facts":{"trading_date":"2026-10-07","slot":"09:05","consumer":"Codex","generated_at":"2026-10-07T09:05:00+08:00","policy_bundle_sha256":"abc","input_snapshot_sha256":"def","decision_id":"D1","conclusion":"A&B <C> 'quote' \\"quote\\"","payload":{"decision_id":"D1","summary":["ok"],"tables":[{"title":"00865B 子帳與今日建議","columns":["子帳用途","券商","持有單位","目標缺口","今日建議買進後歸屬"],"rows":[["00865B 緊急備用金","國泰證券","1 張","0","不可執行，買進後歸屬緊急備用金國泰"],["00865B 策略配置","富邦證券","1 張","0","不可執行，買進後歸屬策略配置富邦"]]},{"title":"t2","columns":["a"],"rows":[["b"]]},{"title":"t3","columns":["a"],"rows":[["b"]]},{"title":"t4","columns":["a"],"rows":[["b"]]}]}}}
      """;}
}
