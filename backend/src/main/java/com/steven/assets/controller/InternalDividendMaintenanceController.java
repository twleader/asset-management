package com.steven.assets.controller;

import com.steven.assets.service.DividendCurrentStateProjectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Task 363／Requirement 99：股利 current-state 投影的一次性維護端點。
 *
 * <p><b>成因</b>：{@code DividendFetchClient} 對 FinMind {@code TaiwanStockDividendResult}
 * fallback 表「權」型列曾誤把除權參考價落差當配股率入庫（見任務檔 t363 背景段落）。
 * {@code DividendCurrentStateProjectionService.projectOne} 的既有自癒機制（cancel pass
 * 只處理未來事件、collapse pass 只收斂金額完全相同的重複列）兩者都碰不到這批髒資料，
 * 必須經此端點顯式取消。</p>
 *
 * <p><b>不得直接注入 Repository</b>（Clean Architecture「Controller 不得直接注入
 * Repository」鐵則；{@link com.steven.assets.service.DividendCurrentStateRepository} 的
 * Javadoc 也明文寫「ACTIVE/CANCELLED decisions belong to
 * DividendCurrentStateProjectionService」）——本 controller 只委派
 * {@link DividendCurrentStateProjectionService#cancelEventById(String, String, long)}。</p>
 *
 * <p>路徑刻意命名為 {@code /internal/dividend/cancel-event}（呼應 external-materials-service
 * 既有 {@code /internal/dividend/sync} 的「資源/動作」式 {@code /internal/{domain}/{action}}
 * 命名風格），不得命名為 {@code /internal/dividend-history/...}——那是
 * external-materials-service 既有的另一支端點（{@code InternalPriceController}，查
 * FinMind 主表用），字面撞名容易讓實作者誤放進錯的 service／錯的 controller。</p>
 *
 * <p><b>不進 BFF、不進 9090 gateway</b>，只供 backend 容器內部呼叫（比照
 * {@code InternalBacktestController} 的授權立場：容器不對外映射 8080 即為隔離）。清理
 * 完成後本端點<b>予以保留</b>——它是通用的「取消單一 current-state 列」維護工具，未來
 * 若再發生類似資料品質事件仍可重用，不是「用完即丟」的暫時程式碼。</p>
 */
@Slf4j
@RestController
@RequestMapping("/internal/dividend")
@RequiredArgsConstructor
public class InternalDividendMaintenanceController {

    private final DividendCurrentStateProjectionService projectionService;

    /**
     * 取消單一 current-state ACTIVE 列。{@code id} 必須確實屬於 {@code code}／
     * {@code market} 底下目前 ACTIVE 的列，否則回 404、不做任何寫入。
     */
    @PostMapping("/cancel-event")
    public ResponseEntity<Void> cancelEvent(
            @RequestParam String code, @RequestParam String market, @RequestParam long id) {
        boolean cancelled = projectionService.cancelEventById(code, market, id);
        if (!cancelled) {
            log.warn("取消股利事件失敗，非 ACTIVE 或不存在：{} {} id={}", market, code, id);
            return ResponseEntity.notFound().build();
        }
        log.info("已取消股利事件：{} {} id={}", market, code, id);
        return ResponseEntity.ok().build();
    }
}
