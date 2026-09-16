package com.steven.assets.service;

import com.steven.assets.dto.ExportScheduleDto;
import com.steven.assets.repository.ExportScheduleSettingRepository;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.security.UnauthenticatedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Google Drive 目錄列舉 {@code ExportScheduleService.browseGdrive}（Requirement 50 / Task 241）。
 *
 * <p>rclone 呼叫一律以 {@link RcloneClient} 替身注入，<b>不實際連網</b>。
 *
 * <p>最關鍵的是 remote 不可用時的行為：必須讓例外浮上去（controller 轉 503 帶可讀訊息），
 * <b>不得</b>吞成空清單——空樹會被使用者誤讀為「Drive 裡沒有資料夾」而以為是自己選錯位置。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GdriveBrowseTest {

    @Mock private ExportScheduleSettingRepository settingRepo;
    @Mock private ExcelExportService excelExportService;
    @Mock private ObjectProvider<CurrentUserContext> currentUserProvider;
    @Mock private CurrentUserContext currentUser;
    @Mock private RcloneClient rcloneClient;
    @Mock private com.steven.assets.repository.AppUserRepository appUserRepo;
    @Mock private UserAdminService userAdminService;
    // 啟用當下的自檢（Task 247）：本測試不涉及啟用路徑，替身預設回 null（＝自檢正常），
    // 同時保證這裡不會去讀容器內的 /etc/rclone/rclone.conf。
    @Mock private GdriveSelfCheck selfCheck;

    private ExportScheduleService service;

    @BeforeEach
    void setUp() {
        when(currentUserProvider.getObject()).thenReturn(currentUser);
        when(currentUser.hasUser()).thenReturn(true);
        when(currentUser.getEffectiveUserId()).thenReturn(1L);
        // Task 242 起 Drive 邏輯集中在 GdriveOutputSupport；此處注入真實元件、只把 rclone 換成替身，
        // 讓「remote 不可用必須往上拋、不得吞成空清單」這條斷言仍測到真正的路徑。
        GdriveOutputSupport gdrive = new GdriveOutputSupport(
                rcloneClient, appUserRepo, userAdminService, selfCheck, "GDriveOutput");
        service = new ExportScheduleService(settingRepo, excelExportService, currentUserProvider,
                gdrive,
                new com.steven.assets.service.export.ExcelDocRenderer(),
                new com.steven.assets.service.export.JsonDocRenderer(new com.fasterxml.jackson.databind.ObjectMapper()),
                new com.steven.assets.service.export.DualFormatExportWriter(gdrive),
                "/home/steven");
        com.steven.assets.service.ExportScheduleUnitHarness.attach(service, settingRepo);
    }

    @Test
    void 正常列舉_回傳形狀與本機browse一致且依名稱排序() {
        when(rcloneClient.listDirs(eq("GDriveOutput"), eq("投資理財")))
                .thenReturn(List.of("資產管理", "保單", ".隱藏"));

        ExportScheduleDto.BrowseResponse res = service.browseGdrive("投資理財");

        assertThat(res.baseDir()).isEqualTo("GDriveOutput:");
        assertThat(res.subpath()).isEqualTo("投資理財");
        assertThat(res.absolutePath()).isEqualTo("GDriveOutput:投資理財");
        // dotfiles 隱藏（與本機 browse 一致）
        assertThat(res.directories()).extracting(ExportScheduleDto.DirEntry::name)
                .containsExactly("保單", "資產管理");
        // 子路徑要串上父層，前端才能逐層懶載入
        assertThat(res.directories()).extracting(ExportScheduleDto.DirEntry::path)
                .containsExactly("投資理財/保單", "投資理財/資產管理");
    }

    @Test
    void 根目錄列舉_子路徑不加前置斜線() {
        when(rcloneClient.listDirs(eq("GDriveOutput"), eq(""))).thenReturn(List.of("投資理財"));

        ExportScheduleDto.BrowseResponse res = service.browseGdrive("");

        assertThat(res.directories()).hasSize(1);
        assertThat(res.directories().get(0).path()).isEqualTo("投資理財");
    }

    /** 瀏覽刻意比儲存寬鬆：開頭斜線剝除而非回 400（同本機既有 browse 的取捨）。 */
    @Test
    void 瀏覽時開頭斜線被剝除而非回400() {
        when(rcloneClient.listDirs(eq("GDriveOutput"), eq("投資理財"))).thenReturn(List.of());

        ExportScheduleDto.BrowseResponse res = service.browseGdrive("/投資理財/");

        assertThat(res.subpath()).isEqualTo("投資理財");
    }

    /**
     * remote 未設定／授權失效時例外必須浮上去，且<b>不得</b>回空清單。
     * 這一條是本檔最重要的迴歸：吞成空樹會讓使用者以為 Drive 裡真的沒資料夾。
     */
    @Test
    void remote不可用時擲例外而非回空清單() {
        when(rcloneClient.listDirs(anyString(), anyString()))
                .thenThrow(new RcloneClient.RcloneUnavailableException(
                        "Google Drive remote「GDriveOutput」尚未設定或授權失效，請先完成 rclone 授權設定。"));

        assertThatThrownBy(() -> service.browseGdrive(""))
                .isInstanceOf(RcloneClient.RcloneUnavailableException.class)
                .hasMessageContaining("尚未設定或授權失效");
    }

    @Test
    void 子路徑含雙點段回400且不呼叫rclone() {
        assertThatThrownBy(() -> service.browseGdrive("投資理財/../../etc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("..");

        verify(rcloneClient, never()).listDirs(anyString(), anyString());
    }

    @Test
    void 子路徑含冒號回400且不呼叫rclone() {
        assertThatThrownBy(() -> service.browseGdrive("other-remote:x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("冒號");

        verify(rcloneClient, never()).listDirs(anyString(), anyString());
    }

    @Test
    void 未登入時擋下且不呼叫rclone() {
        when(currentUser.hasUser()).thenReturn(false);

        assertThatThrownBy(() -> service.browseGdrive(""))
                .isInstanceOf(UnauthenticatedException.class);

        verify(rcloneClient, never()).listDirs(anyString(), anyString());
    }
}
