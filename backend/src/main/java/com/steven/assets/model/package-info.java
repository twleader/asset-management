/**
 * 多租戶 owner 過濾器（Requirement 28）。
 *
 * <p>{@code ownerFilter} 統一定義於 package 層級，避免在多個 entity 重複 {@code @FilterDef} 造成重複定義錯誤。
 * 各受隔離 entity 以 {@code @Filter(name = "ownerFilter", condition = "owner_user_id = :ownerId")} 套用。
 * 過濾器僅在有 HTTP request 的請求中啟用（見 {@code TenantFilterAspect}）；背景 cron 不啟用，維持掃全體資料的既有行為。
 */
@org.hibernate.annotations.FilterDef(
        name = "ownerFilter",
        parameters = @org.hibernate.annotations.ParamDef(name = "ownerId", type = Long.class)
)
package com.steven.assets.model;
