package com.steven.assets.repository;

import com.steven.assets.model.BlogPublishCredential;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Blogger OAuth 憑證的全域單例儲存（Requirement 102 / Task 366）。
 *
 * <p>全表只會有 {@code id=1} 這一列（或完全不存在，代表尚未連接）。呼叫端一律用
 * {@code findById(1L)} 取得——{@code id} 固定為 1，直接以主鍵查詢比掃全表更明確也更快，
 * 且不會在誤插入異常資料時退化成「取任一列」的不確定行為。<b>不得</b>使用
 * {@code findAll().stream().findFirst()}。
 */
public interface BlogPublishCredentialRepository extends JpaRepository<BlogPublishCredential, Long> {
}
