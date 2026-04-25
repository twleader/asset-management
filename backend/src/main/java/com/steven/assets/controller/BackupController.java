package com.steven.assets.controller;

import com.steven.assets.dto.BackupDto;
import com.steven.assets.service.BackupService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/backups")
@RequiredArgsConstructor
public class BackupController {

    private static final String CONFIRMATION_PHRASE = "確認還原";

    private final BackupService service;

    /** 立即備份。 */
    @PostMapping
    public BackupDto.CreateResponse create() {
        return service.runBackup(false);
    }

    /** 列出所有備份（manual/daily/weekly/monthly），依時間新→舊。 */
    @GetMapping
    public List<BackupDto.BackupItem> list() {
        return service.listBackups();
    }

    /** 還原指定備份。需於 body 內附帶 confirmation = "確認還原"。 */
    @PostMapping("/restore")
    public BackupDto.RestoreResponse restore(@RequestBody BackupDto.RestoreRequest req) {
        if (req == null || !CONFIRMATION_PHRASE.equals(req.getConfirmation())) {
            throw new IllegalArgumentException("請輸入「" + CONFIRMATION_PHRASE + "」以確認還原");
        }
        return service.runRestore(req.getFolder(), req.getFilename());
    }
}
