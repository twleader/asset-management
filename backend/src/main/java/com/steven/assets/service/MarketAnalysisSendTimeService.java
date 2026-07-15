package com.steven.assets.service;

import com.steven.assets.dto.MarketAnalysisSettingsDto;
import com.steven.assets.model.MarketAnalysisSendTime;
import com.steven.assets.repository.MarketAnalysisSendTimeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 今日股市分析（Requirement 31 / Task 184）可設定的「分析寄送時間」CRUD。
 *
 * <p>每個 {@code active=true} 列＝一個每台股交易日觸發時點，{@link MarketAnalysisScheduler} 每分鐘 tick
 * 比對現在 HH:mm 命中即重跑一次分析並各寄一封。全域單一排程設定、無 owner 過濾；
 * 寫入端限管理者（controller 以 {@code isAdmin()} 縱深防禦、BFF 亦擋一層 ADMIN）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketAnalysisSendTimeService {

    /** 對外顯示 / 回傳格式（HH:mm，Asia/Taipei）。 */
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    private final MarketAnalysisSendTimeRepository repo;

    /** 全部寄送時間（升序）——管理清單／設定聚合用。 */
    public List<MarketAnalysisSettingsDto.SendTime> list() {
        return repo.findAllByOrderBySendTimeAsc().stream().map(this::toDto).toList();
    }

    /** 啟用中的時點（截到分，升序）——排程 tick／self-heal 用。 */
    public List<LocalTime> activeTimes() {
        return repo.findByActiveTrueOrderBySendTimeAsc().stream()
                .map(t -> t.getSendTime().truncatedTo(ChronoUnit.MINUTES))
                .toList();
    }

    /**
     * 新增一個寄送時間（HH:mm）。格式非法或已存在皆拋 {@link IllegalArgumentException}（→ 400）。回更新後清單。
     */
    @Transactional
    public List<MarketAnalysisSettingsDto.SendTime> add(String time) {
        LocalTime t = parse(time);
        repo.findBySendTime(t).ifPresent(existing -> {
            throw new IllegalArgumentException("寄送時間 " + t.format(HH_MM) + " 已存在");
        });
        MarketAnalysisSendTime row = new MarketAnalysisSendTime();
        row.setSendTime(t);
        row.setActive(Boolean.TRUE);
        row.setCreatedAt(Instant.now());
        repo.save(row);
        log.info("今日股市分析：新增寄送時間 {}", t.format(HH_MM));
        return list();
    }

    /** 刪除一個寄送時間。回更新後清單。 */
    @Transactional
    public List<MarketAnalysisSettingsDto.SendTime> delete(Long id) {
        repo.findById(id).ifPresent(row -> {
            repo.delete(row);
            log.info("今日股市分析：刪除寄送時間 {}", row.getSendTime().format(HH_MM));
        });
        return list();
    }

    /** 切換某寄送時間的啟用／停用。回更新後清單。 */
    @Transactional
    public List<MarketAnalysisSettingsDto.SendTime> toggleActive(Long id) {
        MarketAnalysisSendTime row = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("寄送時間不存在：" + id));
        row.setActive(!Boolean.TRUE.equals(row.getActive()));
        repo.save(row);
        log.info("今日股市分析：寄送時間 {} 啟用切換為 {}", row.getSendTime().format(HH_MM), row.getActive());
        return list();
    }

    /** 解析 {@code HH:mm}（容忍 {@code HH:mm:ss}）並截到分；空白／非法格式拋 {@link IllegalArgumentException}。 */
    private LocalTime parse(String time) {
        if (time == null || time.isBlank()) {
            throw new IllegalArgumentException("寄送時間不可為空（格式 HH:mm）");
        }
        try {
            return LocalTime.parse(time.trim()).truncatedTo(ChronoUnit.MINUTES);
        } catch (Exception e) {
            throw new IllegalArgumentException("寄送時間格式錯誤（須為 HH:mm）：" + time);
        }
    }

    private MarketAnalysisSettingsDto.SendTime toDto(MarketAnalysisSendTime row) {
        return new MarketAnalysisSettingsDto.SendTime(
                row.getId(),
                row.getSendTime().truncatedTo(ChronoUnit.MINUTES).format(HH_MM),
                row.getActive());
    }
}
