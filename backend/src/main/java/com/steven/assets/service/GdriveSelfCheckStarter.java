package com.steven.assets.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 服務啟動時觸發 Google Drive 輸出自檢（Requirement 52 / Task 247）。
 *
 * <p><b>為什麼要多一支薄元件</b>：{@link GdriveSelfCheck} 必須是葉節點（不能注入
 * {@code GdriveOutputSupport}，否則與它形成建構子循環依賴而整個服務起不來），
 * 但 remote 名稱的<b>唯一注入點</b>就是 {@code GdriveOutputSupport}（Task 242.1.4）。
 * 由這支只做「取名字、開執行緒」的元件把兩者接起來，三者之間就沒有環。
 *
 * <p><b>用 {@code ApplicationReadyEvent} 而不是 {@code @PostConstruct}</b>：後者可能早於 Liquibase
 * 套用 migration，而本自檢的前置條件要查八張表。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GdriveSelfCheckStarter {

    private final GdriveOutputSupport gdriveOutputSupport;
    private final GdriveSelfCheck selfCheck;

    /**
     * 啟動自檢：<b>立刻把工作丟到獨立 daemon 執行緒</b>，監聽器本身即刻返回。
     *
     * <p><b>理由不是「等依賴就緒」</b>——{@code ApplicationReadyEvent} 發布時 web server 已在 listen、
     * healthcheck 已可回應。真正的理由是：該事件的 listener 跑在<b>主執行緒</b>上、彼此<b>沒有順序保證</b>，
     * 本自檢的 L3 最長會花 20 秒（{@code rclone lsd} 的逾時上限），在主執行緒上阻塞會延後
     * {@code SpringApplication.run()} 收尾與同事件的其他 listener；若自檢剛好排在 availability listener
     * 之前，readiness 轉為 {@code ACCEPTING_TRAFFIC} 一樣會被拖 20 秒。
     *
     * <p>daemon 是為了不讓一個純觀測的執行緒擋住 JVM 關閉。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        Thread t = new Thread(() -> selfCheck.runStartupCheck(gdriveOutputSupport.remoteName()),
                "gdrive-self-check");
        t.setDaemon(true);
        t.start();
    }
}
