package com.steven.assets.controller;

import com.steven.assets.dto.BacktestDto;
import com.steven.assets.service.BacktestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 交易雷達規則回測的手動觸發端點（Task 273／Requirement 56）。
 *
 * <p><b>刻意不排程、不進 BFF、不進前端。</b>全市場十年逐日重算是分鐘級運算，掛進任何使用者請求
 * 路徑都會拖垮「今日交易雷達」頁；本端點的消費者是「調門檻時的維護者」，不是每日使用者。
 * 故也<b>不需要</b>在「公開資訊 → 排程列表」（{@code SchedulePublicBffController.JOBS}）新增項目。</p>
 *
 * <h3>服務歸屬與授權</h3>
 * <p>這是 business-services <b>第一支</b>作業類 {@code /internal} 端點（此前只有
 * {@code UserAdminController} 的 {@code /internal/users}）。路由風格比照 external-materials-service 的
 * {@code InternalPriceController}，但<b>該類別不在本服務</b>。</p>
 *
 * <p><b>授權立場（已定案）</b>：本端點<b>不</b>納入 {@code AdminGateInterceptor}，
 * 僅靠「容器不對外映射 8080」隔離——{@code asset-business-services} 只有內部 {@code 8080/tcp}，
 * host 的 8080 是 {@code asset-bff}。理由：(a) 它唯讀、不寫入任何資料表、不觸發外部請求；
 * (b) 驗收指令是在容器內裸打、不帶 {@code X-User-*} header，納入 AdminGate 會讓維運無法直接呼叫。
 * <b>若日後把 business 的 8080 對外映射，本決定即失效，必須改為納入 AdminGate。</b></p>
 */
@Slf4j
@RestController
@RequestMapping("/internal/backtest")
@RequiredArgsConstructor
public class InternalBacktestController {

    private final BacktestService backtestService;

    /** JSON 結果。請求 body 可為空物件 {@code {}}，代表全部台股、全部期間、預設四個 horizon。 */
    @PostMapping("/rules")
    public BacktestDto.Response run(@RequestBody(required = false) BacktestDto.Request request) {
        log.info("交易雷達回測開始：{}", request);
        BacktestDto.Response response = backtestService.run(request);
        log.info("交易雷達回測完成：標的 {} 檔、期間 {} ~ {}、統計 {} 列",
                response.codeCount(), response.dataFrom(), response.dataTo(), response.results().size());
        return response;
    }

    /** 同一份結果的 CSV 形式，供離線分析。**格式產生在 service**，本方法只包 ResponseEntity。 */
    @PostMapping(value = "/rules.csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<String> runCsv(@RequestBody(required = false) BacktestDto.Request request) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(backtestService.toCsv(request));
    }
}
