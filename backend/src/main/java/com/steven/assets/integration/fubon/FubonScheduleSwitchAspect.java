package com.steven.assets.integration.fubon;

import com.steven.assets.service.FubonScheduleSwitchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/** 排程方法觸發前檢查開關；關閉（或查無、讀取失敗）則略過本次執行，不呼叫富邦（Requirement 218／Task 612）。 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class FubonScheduleSwitchAspect {

    private final FubonScheduleSwitchService switches;

    @Around("@annotation(marker)")
    public Object gate(ProceedingJoinPoint call, FubonScheduleSwitched marker) throws Throwable {
        var state = switches.state(marker.value());
        if (state != FubonScheduleSwitchService.State.ON) {
            if (state == FubonScheduleSwitchService.State.OFF) {
                log.debug("Fubon scheduled run skipped by switch key={}", marker.value());
            } else {
                log.warn("Fubon scheduled run skipped, switch {} key={}", state, marker.value());
            }
            return null;
        }
        return call.proceed();
    }
}
