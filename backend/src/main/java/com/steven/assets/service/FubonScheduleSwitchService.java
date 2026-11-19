package com.steven.assets.service;

import com.steven.assets.dto.FubonScheduleSwitchDto;
import com.steven.assets.model.FubonScheduleSwitch;
import com.steven.assets.repository.FubonScheduleSwitchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 富邦唯讀排程執行開關（Requirement 218／Task 612）。
 * {@link #isEnabled}：查無列或任何例外一律視為關閉（fail-closed），排程下一次觸發再重新判斷。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FubonScheduleSwitchService {

    private final FubonScheduleSwitchRepository repo;

    /** 排程閘判斷結果：只有 ON 放行；OFF 為使用者停用；MISSING／UNREADABLE 為異常（fail-closed）。 */
    public enum State { ON, OFF, MISSING, UNREADABLE }

    public State state(String scheduleKey) {
        try {
            return repo.findById(scheduleKey)
                    .map(row -> row.isEnabled() ? State.ON : State.OFF)
                    .orElse(State.MISSING);
        } catch (RuntimeException failure) {
            log.warn("Fubon schedule switch read failed key={} cause={}", scheduleKey, failure.getClass().getSimpleName());
            return State.UNREADABLE;
        }
    }

    public boolean isEnabled(String scheduleKey) {
        return state(scheduleKey) == State.ON;
    }

    public List<FubonScheduleSwitchDto> list() {
        return repo.findAllByOrderByScheduleKeyAsc().stream().map(FubonScheduleSwitchDto::from).toList();
    }

    /** 未知 key 擲 {@link NoSuchElementException}（→ 404）。 */
    @Transactional
    public FubonScheduleSwitchDto setEnabled(String scheduleKey, boolean enabled) {
        FubonScheduleSwitch row = repo.findById(scheduleKey)
                .orElseThrow(() -> new NoSuchElementException("未知的富邦排程開關：" + scheduleKey));
        row.setEnabled(enabled);
        row.setUpdatedAt(Instant.now());
        return FubonScheduleSwitchDto.from(repo.save(row));
    }
}
