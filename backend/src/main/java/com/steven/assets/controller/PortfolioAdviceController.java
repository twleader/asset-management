package com.steven.assets.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.dto.CurrentAllocationDto;
import com.steven.assets.dto.InvestmentProfileDto;
import com.steven.assets.dto.InvestmentProfileInput;
import com.steven.assets.dto.PortfolioAdviceDto;
import com.steven.assets.dto.PortfolioAdviceSettingsDto;
import com.steven.assets.model.PortfolioAdvice;
import com.steven.assets.security.AdminRequiredException;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.PortfolioAdviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 資產配置建議（Requirement 32）business API。
 *
 * <p>profile / 建議 / 現況配置皆 owner-scoped（每人只見自己的，{@code TenantFilterAspect} + {@code TenantGuard} 保障），
 * GET/PUT profile 與 POST /generate 開放給已登入者（各自產自己的建議、承擔自己的成本）。
 * 成本控管設定（模型／思考深度／web 搜尋）為全域，寫入限管理者（{@link CurrentUserContext#isAdmin()} 縱深防禦，
 * BFF 已對外擋一層 ADMIN）。
 */
@RestController
@RequestMapping("/api/portfolio-advice")
@RequiredArgsConstructor
public class PortfolioAdviceController {

    private final PortfolioAdviceService adviceService;
    private final CurrentUserContext currentUser;
    private final ObjectMapper objectMapper;

    /** 最新一筆建議；尚無資料回 {status:"NONE"}。 */
    @GetMapping("/latest")
    public PortfolioAdviceDto latest() {
        PortfolioAdvice latest = adviceService.latest();
        return latest == null ? PortfolioAdviceDto.none() : PortfolioAdviceDto.from(latest, objectMapper);
    }

    /** 近 N 筆建議（created_at 降序）。 */
    @GetMapping("/history")
    public List<PortfolioAdviceDto> history(@RequestParam(defaultValue = "20") int limit) {
        return adviceService.history(limit).stream()
                .map(a -> PortfolioAdviceDto.from(a, objectMapper))
                .toList();
    }

    /** 目前使用者的理財條件（含表單可選清單）。 */
    @GetMapping("/profile")
    public InvestmentProfileDto getProfile() {
        return adviceService.getProfile();
    }

    /** 儲存理財條件。 */
    @PutMapping("/profile")
    public InvestmentProfileDto saveProfile(@RequestBody Map<String, Object> body) {
        return adviceService.saveProfile(toInput(body));
    }

    /** 目前使用者最新快照的資產配置概覽。 */
    @GetMapping("/current-allocation")
    public CurrentAllocationDto currentAllocation() {
        return adviceService.getCurrentAllocation();
    }

    /** 產生建議（同步；thinking + web_search 可能耗數十秒）。body 帶入理財條件，會一併儲存為 profile。 */
    @PostMapping("/generate")
    public PortfolioAdviceDto generate(@RequestBody(required = false) Map<String, Object> body) {
        PortfolioAdvice row = adviceService.generate(toInput(body == null ? Map.of() : body));
        return PortfolioAdviceDto.from(row, objectMapper);
    }

    /** 目前成本控管設定 + 各自可選清單。已登入者可讀。 */
    @GetMapping("/settings")
    public PortfolioAdviceSettingsDto getSettings() {
        return adviceService.getSettings();
    }

    /** 更新模型／思考深度／web 搜尋次數（限管理者；白名單驗證於 service）。 */
    @PutMapping("/settings")
    public PortfolioAdviceSettingsDto updateSettings(@RequestBody Map<String, Object> body) {
        if (!currentUser.isAdmin()) {
            throw new AdminRequiredException();
        }
        return adviceService.updateSettings(
                str(body, "model"),
                str(body, "effort"),
                intOrNull(body, "webSearchMaxUses"));
    }

    // ===== body 解析小工具 =====

    /** body → 理財條件命令物件（生日／退休／勞保勞退日期為整日 YYYY-MM-DD；plannedExpenses 為大筆花費清單）。 */
    private static InvestmentProfileInput toInput(Map<String, Object> body) {
        return new InvestmentProfileInput(
                localDateOrNull(body, "birthDate"),
                intOrNull(body, "investmentHorizonYears"),
                bigDecimalOrNull(body, "monthlyInvestment"),
                localDateOrNull(body, "retirementDate"),
                bigDecimalOrNull(body, "laborInsuranceMonthly"),
                localDateOrNull(body, "laborInsuranceStartDate"),
                bigDecimalOrNull(body, "laborPensionLumpSum"),
                localDateOrNull(body, "laborPensionClaimDate"),
                bigDecimalOrNull(body, "assumedAnnualInflationRate"),
                stringList(body, "goals"),
                str(body, "riskTolerance"),
                str(body, "expectedAnnualReturn"),
                expenseList(body, "plannedExpenses"));
    }

    /** body 的 plannedExpenses（JSON 陣列，每項 {expenseDate,name,amount}）→ 命令物件清單。 */
    private static List<InvestmentProfileInput.PlannedExpenseInput> expenseList(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (!(v instanceof List<?> list)) {
            return List.of();
        }
        List<InvestmentProfileInput.PlannedExpenseInput> out = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) m;
            out.add(new InvestmentProfileInput.PlannedExpenseInput(
                    localDateOrNull(row, "expenseDate"),
                    str(row, "name"),
                    bigDecimalOrNull(row, "amount")));
        }
        return out;
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : v.toString();
    }

    /** 解析 "YYYY-MM-DD" 整日；空字串／缺值回 null，格式錯拋 IllegalArgument。 */
    private static LocalDate localDateOrNull(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null) {
            return null;
        }
        String s = v.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(s); // 接受 ISO "2040-06-15"
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("日期格式錯誤（" + key + "，需 YYYY-MM-DD）：" + v);
        }
    }

    private static Integer intOrNull(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        String s = v.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Integer.valueOf(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("數值格式錯誤（" + key + "）：" + v);
        }
    }

    private static BigDecimal bigDecimalOrNull(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        String s = v.toString().trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("金額格式錯誤（" + key + "）：" + v);
        }
    }

    /** body 值 → List<String>（接受 JSON 陣列或逗號分隔字串）。 */
    private static List<String> stringList(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null) {
            return List.of();
        }
        if (v instanceof List<?> list) {
            return list.stream().filter(java.util.Objects::nonNull).map(Object::toString).toList();
        }
        String s = v.toString().trim();
        if (s.isEmpty()) {
            return List.of();
        }
        return java.util.Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }
}
