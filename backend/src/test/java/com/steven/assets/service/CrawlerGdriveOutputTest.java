package com.steven.assets.service;

import com.steven.assets.dto.CrawlerExportPathDto;
import com.steven.assets.model.CrawlerExportSetting;
import com.steven.assets.repository.CrawlerExportSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 爬蟲輸出同步 Google Drive 的設定驗證（Requirement 50 / Task 241）。
 *
 * <p>重點在三件事：（1）Drive 子路徑的驗證規則與本機不同（純字串、非 {@code Path} 判斷）；
 * （2）啟用開關與子路徑的「未送出＝不變更」語意，不得把使用者已存的設定靜默清掉；
 * （3）既有本機 {@code outputSubpath} 行為不因新欄位而改變（回歸）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CrawlerGdriveOutputTest {

    private static final String KEY = "news-poller";

    @Mock
    private CrawlerExportSettingRepository repo;
    @Mock private RcloneClient rcloneClient;
    @Mock private com.steven.assets.repository.AppUserRepository appUserRepo;
    @Mock private UserAdminService userAdminService;

    private CrawlerExportPathService service;

    @BeforeEach
    void setUp() {
        // Task 242 起驗證規則遷入 GdriveOutputSupport；注入真實元件（rclone 等相依在本測試用不到）。
        GdriveOutputSupport gdrive = new GdriveOutputSupport(
                rcloneClient, appUserRepo, userAdminService, "GDriveOutput");
        service = new CrawlerExportPathService(repo, "/home/steven", gdrive);
        // save 回傳被存進去的那個 entity，讓 assert 能直接看寫入結果
        when(repo.save(any(CrawlerExportSetting.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** 現有列（已啟用 Drive、已有子路徑），用來驗「不變更」與「不清空」語意。 */
    private CrawlerExportSetting existing(boolean enabled, String gdriveSubpath) {
        CrawlerExportSetting s = new CrawlerExportSetting();
        s.setCrawlerKey(KEY);
        s.setOutputSubpath("Project/SRPP/data/input");
        s.setGdriveEnabled(enabled);
        s.setGdriveSubpath(gdriveSubpath);
        return s;
    }

    // ===== Drive 子路徑驗證 =====

    @Test
    void 絕對路徑一律回400_不默默剝掉開頭斜線() {
        when(repo.findByCrawlerKey(anyString())).thenReturn(Optional.of(existing(false, null)));

        assertThatThrownBy(() -> service.update(KEY,
                new CrawlerExportPathDto.Request("input", true, "/投資理財/資產管理")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("相對路徑");
    }

    @Test
    void 含雙點路徑段回400() {
        when(repo.findByCrawlerKey(anyString())).thenReturn(Optional.of(existing(false, null)));

        assertThatThrownBy(() -> service.update(KEY,
                new CrawlerExportPathDto.Request("input", true, "投資理財/../../etc")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("..");
    }

    /**
     * {@code a..b} 是合法目錄名，不得被誤擋——驗證實作是逐段比對而非 {@code contains("..")}。
     * 這一條是刻意的迴歸：用 contains 會過不了。
     */
    @Test
    void 目錄名內含雙點但非路徑段_必須通過() {
        when(repo.findByCrawlerKey(anyString())).thenReturn(Optional.of(existing(false, null)));

        CrawlerExportPathDto.Response res = service.update(KEY,
                new CrawlerExportPathDto.Request("input", true, "投資理財/a..b"));

        assertThat(res.gdriveSubpath()).isEqualTo("投資理財/a..b");
        assertThat(res.gdriveEnabled()).isTrue();
    }

    /** 含 {@code :} 會被 rclone 解讀為切換 remote，一律擋掉。 */
    @Test
    void 含冒號回400_防remote切換() {
        when(repo.findByCrawlerKey(anyString())).thenReturn(Optional.of(existing(false, null)));

        assertThatThrownBy(() -> service.update(KEY,
                new CrawlerExportPathDto.Request("input", true, "other-remote:/x")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("冒號");
    }

    @Test
    void 啟用但子路徑為空回400() {
        when(repo.findByCrawlerKey(anyString())).thenReturn(Optional.of(existing(false, null)));

        assertThatThrownBy(() -> service.update(KEY,
                new CrawlerExportPathDto.Request("input", true, "  ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必須指定");
    }

    @Test
    void 未啟用且子路徑為空_通過() {
        when(repo.findByCrawlerKey(anyString())).thenReturn(Optional.of(existing(false, null)));

        CrawlerExportPathDto.Response res = service.update(KEY,
                new CrawlerExportPathDto.Request("input", false, ""));

        assertThat(res.gdriveEnabled()).isFalse();
        assertThat(res.gdriveSubpath()).isNull();
    }

    /** 關閉開關時不得清掉已填的路徑：使用者關掉再開回來不必重填。 */
    @Test
    void 關閉開關不清空既有子路徑() {
        when(repo.findByCrawlerKey(anyString())).thenReturn(Optional.of(existing(true, "投資理財/資產管理")));

        CrawlerExportPathDto.Response res = service.update(KEY,
                new CrawlerExportPathDto.Request("input", false, null));

        assertThat(res.gdriveEnabled()).isFalse();
        assertThat(res.gdriveSubpath()).isEqualTo("投資理財/資產管理");
    }

    /** 沒送 gdriveEnabled（舊版前端／只想改本機路徑）＝不變更，不得靜默關掉使用者已開的開關。 */
    @Test
    void 未送出啟用欄位時保留原設定() {
        when(repo.findByCrawlerKey(anyString())).thenReturn(Optional.of(existing(true, "投資理財/資產管理")));

        CrawlerExportPathDto.Response res = service.update(KEY,
                new CrawlerExportPathDto.Request("other", null, null));

        assertThat(res.gdriveEnabled()).isTrue();
        assertThat(res.gdriveSubpath()).isEqualTo("投資理財/資產管理");
        assertThat(res.outputSubpath()).isEqualTo("other");
    }

    @Test
    void 正常中文子路徑可儲存並讀回_且結尾斜線被正規化() {
        when(repo.findByCrawlerKey(anyString())).thenReturn(Optional.of(existing(false, null)));

        CrawlerExportPathDto.Response res = service.update(KEY,
                new CrawlerExportPathDto.Request("input", true, "投資理財/資產管理/"));

        assertThat(res.gdriveSubpath()).isEqualTo("投資理財/資產管理");
        assertThat(res.gdriveEnabled()).isTrue();
        assertThat(res.gdriveRemote()).isEqualTo("GDriveOutput");
    }

    // ===== 既有本機行為的迴歸 =====

    @Test
    void 本機子路徑空字串仍正規化為預設值() {
        when(repo.findByCrawlerKey(anyString())).thenReturn(Optional.empty());

        CrawlerExportPathDto.Response res = service.update(KEY,
                new CrawlerExportPathDto.Request("", null, null));

        assertThat(res.outputSubpath()).isEqualTo(CrawlerExportSetting.DEFAULT_SUBPATH);
    }

    @Test
    void 本機子路徑跳脫基底仍回400() {
        when(repo.findByCrawlerKey(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(KEY,
                new CrawlerExportPathDto.Request("../../etc", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * 讀取時不得因既有不合法的 Drive 值而擲例外——否則設定頁會 500，
     * 使用者就沒有任何入口能把它改回正常值（唯一的修正入口被自己鎖死）。
     */
    @Test
    void 讀取不合法的既有Drive值不擲例外() {
        CrawlerExportSetting bad = existing(true, "/絕對路徑:含冒號/..");
        when(repo.findByCrawlerKey(anyString())).thenReturn(Optional.of(bad));

        CrawlerExportPathDto.Response res = service.get(KEY);

        assertThat(res.gdriveSubpath()).isEqualTo("/絕對路徑:含冒號/..");
        assertThat(res.gdriveEnabled()).isTrue();
    }

    /** 使用者設定的 PUT 不得碰 ext 寫入的執行結果欄位。 */
    @Test
    void 更新設定不得覆寫上次上傳結果() {
        CrawlerExportSetting s = existing(true, "投資理財/資產管理");
        s.setGdriveLastStatus("成功：GDriveOutput:投資理財/資產管理/public_info_2026-07-27.json（1234 bytes）");
        when(repo.findByCrawlerKey(anyString())).thenReturn(Optional.of(s));

        service.update(KEY, new CrawlerExportPathDto.Request("input", true, "投資理財/資產管理"));

        assertThat(s.getGdriveLastStatus()).startsWith("成功：");
    }
}
