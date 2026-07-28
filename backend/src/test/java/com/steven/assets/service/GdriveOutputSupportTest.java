package com.steven.assets.service;

import com.steven.assets.model.AppUser;
import com.steven.assets.repository.AppUserRepository;
import com.steven.assets.security.AdminRequiredException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GdriveOutputSupport} 單元測試（Requirement 51 / Task 242）。
 *
 * <p>本元件是八個匯出頁＋爬蟲頁<b>共用</b>的 Drive 規則來源，它們寫進的是同一個 Drive；
 * 這裡的每一條都是「一旦放寬就會同時影響九個頁面」的規則：
 * <ul>
 *   <li>子路徑驗證（開頭 {@code /} 回 400 而非默默剝掉、{@code ..} 逐段比對而非 {@code contains}、拒 {@code :}）</li>
 *   <li>{@code isDriveAllowedFor} 一律 fail-closed，且判準是主要管理者而非 {@code role == ADMIN}</li>
 *   <li><b>逾時與確定性失敗的措辭必須分開</b>——實測發生過「狀態記逾時、Drive 上檔案卻完整」的假失敗</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GdriveOutputSupportTest {

    private static final String REMOTE = "GDriveOutput";
    private static final long ADMIN_ID = 1L;
    private static final long OTHER_ID = 2L;

    @Mock private RcloneClient rcloneClient;
    @Mock private AppUserRepository userRepo;
    @Mock private UserAdminService userAdminService;
    /** 啟用當下的自檢（Task 247）；用替身才能在不碰檔案系統的情況下決定「有沒有警告」。 */
    @Mock private GdriveSelfCheck selfCheck;

    @TempDir Path tmp;

    private GdriveOutputSupport gdrive;

    @BeforeEach
    void setup() {
        gdrive = new GdriveOutputSupport(rcloneClient, userRepo, userAdminService, selfCheck, REMOTE);
    }

    private void givenUser(long id, String email, boolean configuredAdmin) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setEmail(email);
        when(userRepo.findById(id)).thenReturn(Optional.of(u));
        when(userAdminService.isConfiguredAdmin(email)).thenReturn(configuredAdmin);
    }

    private Path localFile(String name) throws IOException {
        Path f = tmp.resolve(name);
        Files.writeString(f, "x".repeat(20));
        return f;
    }

    // ===== 子路徑正規化與驗證 =====

    @Test
    void 正規化去頭尾空白與結尾斜線_空字串回null() {
        assertThat(gdrive.normalizeSubpath("  投資理財/資產管理//  ")).isEqualTo("投資理財/資產管理");
        assertThat(gdrive.normalizeSubpath("   ")).isNull();
        assertThat(gdrive.normalizeSubpath(null)).isNull();
    }

    @Test
    void 開頭斜線回400而非默默剝除() {
        // 先剝再檢查會讓這個分支永遠不可達；使用者該知道只能填相對子路徑。
        assertThat(gdrive.normalizeSubpath("/投資理財")).isEqualTo("/投資理財");
        assertThatThrownBy(() -> gdrive.validateSubpath("/投資理財"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不可以 / 開頭");
    }

    @Test
    void 含冒號被擋_防被解讀成切換remote() {
        assertThatThrownBy(() -> gdrive.validateSubpath("OtherRemote:資料"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("冒號");
    }

    @Test
    void 上層路徑段被擋但合法目錄名a點點b不被誤擋() {
        assertThatThrownBy(() -> gdrive.validateSubpath("投資理財/../其他"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("..");
        // contains("..") 會誤擋這個合法目錄名——必須逐段比對
        gdrive.validateSubpath("投資理財/a..b/資產管理");
    }

    @Test
    void 啟用時子路徑必填() {
        assertThatThrownBy(() -> gdrive.normalizeAndValidate("  ", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必須指定");
        assertThat(gdrive.normalizeAndValidate("  ", false)).isNull();
    }

    // ===== 權限判定（fail-closed）=====

    @Test
    void 主要管理者可用Drive() {
        givenUser(ADMIN_ID, "tw.leader@gmail.com", true);
        assertThat(gdrive.isDriveAllowedFor(ADMIN_ID)).isTrue();
    }

    @Test
    void 非主要管理者不可用_即使role是ADMIN() {
        // 判準是 isConfiguredAdmin（比對 ADMIN_EMAIL，全庫唯一一人），不是 role 欄位；
        // role 可以有多列 ADMIN，第二位若被升為 ADMIN，其報表照樣會進到 remote 擁有者的 Drive。
        givenUser(OTHER_ID, "hi.steven@gmail.com", false);
        assertThat(gdrive.isDriveAllowedFor(OTHER_ID)).isFalse();
    }

    @Test
    void 查無使用者或email為空一律failClosed() {
        when(userRepo.findById(99L)).thenReturn(Optional.empty());
        assertThat(gdrive.isDriveAllowedFor(99L)).isFalse();
        assertThat(gdrive.isDriveAllowedFor(null)).isFalse();

        AppUser noEmail = new AppUser();
        noEmail.setId(3L);
        noEmail.setEmail("  ");
        when(userRepo.findById(3L)).thenReturn(Optional.of(noEmail));
        assertThat(gdrive.isDriveAllowedFor(3L)).isFalse();
    }

    // ===== 設定更新：null 語意與 403 =====

    @Test
    void 未送出的欄位視為不變更_不得把已開啟的開關靜默關掉() {
        // 舊版前端、或只想改本機路徑的呼叫端送不出這兩欄；那不該關掉使用者已開啟的 Drive 同步。
        GdriveOutputSupport.DriveSettings r =
                gdrive.resolveUpdate(ADMIN_ID, null, null, true, "投資理財/資產管理");
        assertThat(r.enabled()).isTrue();
        assertThat(r.subpath()).isEqualTo("投資理財/資產管理");
    }

    @Test
    void 明確送false會關閉但不清空既有子路徑() {
        // 關掉再開回來不必重填。
        GdriveOutputSupport.DriveSettings r =
                gdrive.resolveUpdate(ADMIN_ID, false, null, true, "投資理財/資產管理");
        assertThat(r.enabled()).isFalse();
        assertThat(r.subpath()).isEqualTo("投資理財/資產管理");
    }

    @Test
    void 非主要管理者要啟用擲AdminRequired而非IllegalArgument() {
        // 必須是 403：這是權限問題而非輸入錯誤。IllegalArgumentException 會被映成 400。
        givenUser(OTHER_ID, "hi.steven@gmail.com", false);
        assertThatThrownBy(() -> gdrive.resolveUpdate(OTHER_ID, true, "投資理財", false, null))
                .isInstanceOf(AdminRequiredException.class)
                .hasMessageContaining("主要管理者");
    }

    @Test
    void 主要管理者啟用但未填資料夾回400() {
        givenUser(ADMIN_ID, "tw.leader@gmail.com", true);
        assertThatThrownBy(() -> gdrive.resolveUpdate(ADMIN_ID, true, "  ", false, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必須指定");
    }

    @Test
    void 既有已啟用而本次未送該欄時不做權限檢查() {
        // 否則 ADMIN_EMAIL 換人後，該使用者連本機輸出路徑與排程時間都會被 403 鎖死——
        // 而那兩項本來就開放給所有使用者。真正的閘門是每一輪產檔前的複驗。
        GdriveOutputSupport.DriveSettings r =
                gdrive.resolveUpdate(OTHER_ID, null, "新資料夾", true, "舊資料夾");
        assertThat(r.enabled()).isTrue();
        assertThat(r.subpath()).isEqualTo("新資料夾");
        verify(userRepo, never()).findById(OTHER_ID);
    }

    // ===== 啟用當下的自檢（Requirement 52 / Task 247.3.1(a)）=====

    @Test
    void 開關由false翻true時做一次本地自檢並把警告帶回當次回應() {
        // 這一處是八個匯出頁共用的掛載點：改這一處，八頁全部有。
        givenUser(ADMIN_ID, "tw.leader@gmail.com", true);
        when(selfCheck.checkLocal(REMOTE)).thenReturn("[GDriveOutput] 的 token 缺 refresh_token…");

        GdriveOutputSupport.DriveSettings r =
                gdrive.resolveUpdate(ADMIN_ID, true, "投資理財/資產管理", false, null);

        assertThat(r.enabled()).isTrue();
        assertThat(r.selfCheckWarning()).contains("refresh_token");
        verify(selfCheck).checkLocal(REMOTE);   // remote 由本元件傳入，自檢元件不得自己注入
    }

    @Test
    void 已啟用時再次儲存不重複自檢() {
        // true→true 是「只改資料夾」或「只改排程時間」的儲存，每次都跑等於白付成本。
        givenUser(ADMIN_ID, "tw.leader@gmail.com", true);   // 明確送 true 仍會走權限檢查
        GdriveOutputSupport.DriveSettings r =
                gdrive.resolveUpdate(ADMIN_ID, true, "新資料夾", true, "舊資料夾");

        assertThat(r.selfCheckWarning()).isNull();
        verify(selfCheck, never()).checkLocal(anyString());
    }

    @Test
    void 關閉或維持關閉時不自檢() {
        assertThat(gdrive.resolveUpdate(ADMIN_ID, false, null, true, "投資理財").selfCheckWarning()).isNull();
        assertThat(gdrive.resolveUpdate(ADMIN_ID, null, "投資理財", false, null).selfCheckWarning()).isNull();
        verify(selfCheck, never()).checkLocal(anyString());
    }

    @Test
    void 自檢正常時警告為null() {
        givenUser(ADMIN_ID, "tw.leader@gmail.com", true);
        when(selfCheck.checkLocal(REMOTE)).thenReturn(null);

        assertThat(gdrive.resolveUpdate(ADMIN_ID, true, "投資理財", false, null).selfCheckWarning()).isNull();
    }

    @Test
    void 自檢在輸入驗證之後才跑_不合法輸入不該同時收到兩種訊息() {
        givenUser(ADMIN_ID, "tw.leader@gmail.com", true);

        assertThatThrownBy(() -> gdrive.resolveUpdate(ADMIN_ID, true, "/絕對路徑", false, null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(selfCheck, never()).checkLocal(anyString());
    }

    @Test
    void 過長的自檢訊息沿用狀態欄的截斷機制() {
        // 與 gdrive_last_status 同一個 512 上限與措辭風格，前端顯示區塊才不會被撐爆。
        givenUser(ADMIN_ID, "tw.leader@gmail.com", true);
        when(selfCheck.checkLocal(REMOTE)).thenReturn("錯".repeat(1000));

        String warning = gdrive.resolveUpdate(ADMIN_ID, true, "投資理財", false, null).selfCheckWarning();

        assertThat(warning).hasSize(512).endsWith("…");
    }

    // ===== 同步：狀態措辭 =====

    @Test
    void 上傳成功回落點與大小() throws IOException {
        givenUser(ADMIN_ID, "tw.leader@gmail.com", true);
        Path f = localFile("資產總覽_1_20260728.xlsx");
        when(rcloneClient.copyTo(eq(REMOTE), eq(f), eq("投資理財/資產管理"), eq(f.getFileName().toString())))
                .thenReturn("GDriveOutput:投資理財/資產管理/資產總覽_1_20260728.xlsx");

        GdriveOutputSupport.SyncResult r = gdrive.syncQuietly(ADMIN_ID, "投資理財/資產管理", f);

        assertThat(r.status()).startsWith("成功：").contains("20 bytes");
        assertThat(r.path()).isEqualTo("GDriveOutput:投資理財/資產管理/資產總覽_1_20260728.xlsx");
    }

    @Test
    void 逾時的措辭與確定性失敗分開() throws IOException {
        // 判準是「行程未在時限內 exit」而不是「檔案沒上去」：實測發生過狀態記逾時、
        // Drive 上檔案卻完整且與本機逐 byte 相同的假失敗。措辭混用會讓使用者白白重跑。
        givenUser(ADMIN_ID, "tw.leader@gmail.com", true);
        Path f = localFile("a.xlsx");
        when(rcloneClient.copyTo(anyString(), any(), anyString(), anyString()))
                .thenThrow(new RcloneClient.RcloneTimeoutException("timeout", 45));

        GdriveOutputSupport.SyncResult r = gdrive.syncQuietly(ADMIN_ID, "投資理財", f);

        assertThat(r.status()).contains("逾時（45 秒）").contains("可能已完成").doesNotStartWith("失敗");
        assertThat(r.path()).isNull();
    }

    @Test
    void 速率限制不寫成失敗_也不寫成逾時() throws IOException {
        // 第三類狀態：設定與授權都正確，只是這一刻 Drive API 額度滿了，下一輪排程會自動重試成功。
        // 實測 [GDriveOutput] 未設 client_id ＝ 用 rclone 內建共用 OAuth client，全球使用者共用同一份
        // 每分鐘配額，故會間歇性撞到。寫成「失敗」會讓使用者去排查一個自己會好的狀況。
        givenUser(ADMIN_ID, "tw.leader@gmail.com", true);
        Path f = localFile("a.xlsx");
        when(rcloneClient.copyTo(anyString(), any(), anyString(), anyString()))
                .thenThrow(new RcloneClient.RcloneRateLimitedException("Google Drive API 目前達到每分鐘查詢上限"));

        GdriveOutputSupport.SyncResult r = gdrive.syncQuietly(ADMIN_ID, "投資理財", f);

        assertThat(r.status())
                .contains("暫時未上傳")
                .contains("下一輪")
                .doesNotStartWith("失敗")
                .doesNotContain("逾時");
        assertThat(r.path()).isNull();
    }

    @Test
    void rclone非零退出才寫失敗() throws IOException {
        givenUser(ADMIN_ID, "tw.leader@gmail.com", true);
        Path f = localFile("a.xlsx");
        when(rcloneClient.copyTo(anyString(), any(), anyString(), anyString()))
                .thenThrow(new RuntimeException("rclone exit 3"));

        GdriveOutputSupport.SyncResult r = gdrive.syncQuietly(ADMIN_ID, "投資理財", f);

        assertThat(r.status()).startsWith("失敗：").contains("rclone exit 3");
    }

    @Test
    void 本輪沒產檔就跳過_絕不上傳前一次的舊檔() {
        GdriveOutputSupport.SyncResult r = gdrive.syncQuietly(ADMIN_ID, "投資理財", null);

        assertThat(r.status()).startsWith("跳過：");
        verify(rcloneClient, never()).copyTo(anyString(), any(), anyString(), anyString());
    }

    @Test
    void owner非主要管理者時跳過且不呼叫rclone() throws IOException {
        // 背景排程沒有 request context，PUT 當下的檢查在此不適用，故每一輪都要重驗。
        givenUser(OTHER_ID, "hi.steven@gmail.com", false);
        Path f = localFile("a.xlsx");

        GdriveOutputSupport.SyncResult r = gdrive.syncQuietly(OTHER_ID, "投資理財", f);

        assertThat(r.status()).contains("跳過").contains("主要管理者");
        verify(rcloneClient, never()).copyTo(anyString(), any(), anyString(), anyString());
    }

    @Test
    void 既有不合法子路徑寫成跳過而非擲例外() throws IOException {
        // DB 值可能被繞過 API 直改；產檔流程不能因此炸掉。
        givenUser(ADMIN_ID, "tw.leader@gmail.com", true);
        Path f = localFile("a.xlsx");

        GdriveOutputSupport.SyncResult r = gdrive.syncQuietly(ADMIN_ID, "/絕對路徑", f);

        assertThat(r.status()).contains("跳過").contains("不合法");
        verify(rcloneClient, never()).copyTo(anyString(), any(), anyString(), anyString());
    }

    @Test
    void 未設定Drive資料夾時跳過() throws IOException {
        givenUser(ADMIN_ID, "tw.leader@gmail.com", true);
        Path f = localFile("a.xlsx");

        assertThat(gdrive.syncQuietly(ADMIN_ID, "   ", f).status()).contains("未設定");
    }

    // ===== 目錄列舉與瀏覽路徑 =====

    @Test
    void 列目錄過濾dotfiles並依名稱排序() {
        when(rcloneClient.listDirs(REMOTE, "投資理財"))
                .thenReturn(List.of("Zebra", ".trash", "apple", "Banana"));

        assertThat(gdrive.listDirsSorted("投資理財")).containsExactly("apple", "Banana", "Zebra");
    }

    @Test
    void 瀏覽路徑比儲存寬鬆_剝開頭斜線但仍擋冒號與上層段() {
        assertThat(gdrive.normalizeBrowseSubpath("//投資理財/")).isEqualTo("投資理財");
        assertThat(gdrive.normalizeBrowseSubpath(null)).isEmpty();
        assertThatThrownBy(() -> gdrive.normalizeBrowseSubpath("a:b"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gdrive.normalizeBrowseSubpath("a/../b"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void remote名稱來自設定而非寫死() {
        assertThat(gdrive.remoteName()).isEqualTo(REMOTE);
    }
}
