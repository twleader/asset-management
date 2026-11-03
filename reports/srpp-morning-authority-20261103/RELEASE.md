# 台股早盤讀取恢復修正的規則包發布（2026-11-03）

使用者先要求修正早盤缺報，於具體變更與驗證完成後明確同意「合併、推送與部署」。本次只同步SRPP流程修復的新規則包身分，不改金融規則、model、公式、targets、Swagger或API。

## 來源與Git

- SRPP feature：`c68fdd41a48fe1e451a12ab2f9737e272bc0d331`；main／遠端：`9a9a19705a447c7e42429d636479f39fecc966df`。
- Asset實作feature：`332b0242bfc7b8b740bfbd3279081557c6276c4c`；實際部署main／遠端：`6ce1350bd0742a562163989da8ecf95a3c183167`，採no-ff合併，部署時main乾淨。
- Bundle：`d4d93e3e44db07a154872a7fa22b98e2962cd745bd986bb16372af15c1c62021`。
- Manifest raw SHA-256：`8726b9779a12d5fc9ae8011c6704bbe88ac6249cc9fb5426bf7843bc53c0bf4f`。
- Model raw SHA-256：`e272c4a75c2081393548f4f05e9693985112dd10a4e3a6f3a056bb4b2bf46ceb`。
- Swagger SHA-256：`d8211f7c50da18c0eba69bb2dfdfcc4f4b2a6cc5ff6341ec28d6d63dd00116f3`。

本文件及Task539完成報告是在上述執行資源發布後回填；不重寫已發布artifact，也不預填此文件尚未產生的Git提交SHA。

## 離線驗收

官方bundle/runtime、獨立authority checker、renderer／identity checks皆PASS。42項Java、13項Ruby／307 assertions、4項release-gate regression通過；spec-check BLOCK0／CHECK0，獨立spec與架構審查無未解問題。兩份golden只更新bundle metadata，全部37個金融案例及calculator hashes逐值不變；25個artifact基準只變預期的manifest、SQL與兩份golden。

初次主golden生成曾因隔離SRPP工作區缺少忽略的2026日曆而失敗；來源負責者複製完整既有日曆供離線回歸後，由原工具重新生成並通過。未捏造日曆、未將離線fixture當作當輪行情或報告證據。

## Registry與服務

兩repo完成合併／推送／遠端讀回後，21:50:39以transaction執行原Ruby產生的受追蹤INSERT-only SQL，registry25→26，歷史25row（含registered_at）逐值不變。新row exact bundle、formula version `ASSET_MGMT_SRPP_V2`、policy document全文及formula manifest全文均精確符合generator：

- Policy document SHA-256：`621b6a14f4b86d9d4ebb9a0d1ce592c8ffd7b9dc73e2cef4fa91f22e3b98ad93`。
- Formula manifest SHA-256：`0f3d9b67dcb7d519f8ef5e9ee378d86eaf26228409199bc487036732e0186e86`。

只從乾淨main與其原部署設定重建並recreate business-services，21:51:22驗收PASS：

- Image：`sha256:183dde043c49a557c33ca21cc0160732ab7fba6a3b5a88ff3c1b6d9cd943168f`。
- Container：`9102ace61d09a73f0cf849a469fed14293151dc189c92eb05c8c619a20fe177d`。
- JAR SHA-256：`7253c453b37a8066b5692053f3d68d88c03226c7659a5da4e66ac7346e91a40a`。
- JAR內manifest、model、Swagger及SQL四資源與main／凍結來源逐位元相同。
- Docker healthy；gateway TWSE安全GET首試回合法JSON、BFF UP；官方2026日曆台股availability為AVAILABLE。
- 46項有效環境設定、main.env及三個掛載符合main Compose；其他8容器ID與image保留，最終image仍為同一顆。

發布負責者另獨立唯讀確認current container／image／healthy、四份JAR bytes與新registry row精確一致。完整實際收據保留 `/private/tmp/asset-morning-release/registry-receipt.json`、`deployment-result.json`、`independent-final-verification.json`。

## 正式金融驗收狀態

維護發生在台股交易時窗之外，正式金融採用為 `NOT_YET_EXECUTED`。本輪未呼叫report-calculations、重跑正式報告、建立或寄出Gmail、POST觀測或操作券商。部署健康與hash一致不能代替下一合法排程的報告產出驗收。
