# [t405] 固定富邦個人帳務 owner 與同批 explicit-account 寫入能力

> 階段性收尾：本檔尚未實作；第一輪3項 major已依建議修正文案，仍待第二輪獨立spec審查，不得視為已通過。接續前請讀 [交接紀錄](../../docs/handoffs/fubon-refactor-2026-08-30.md)。

**對應 Requirements:** 90／120／128／129／130。個人庫存、成交、銀行及帳務報表只能寫入使用者指定的既存 owner；行情、ETF、股利、技術指標仍為原全域範圍。
**前置基線:** 6aa219719c4d20d3ef405ba4b5cad75e1316df15，Task399／400 已落地；Task404 的 readiness／calendar／name／snapshot-lock port若已實作，必須沿用同一抽象。
**Liquibase changeset:** 無；不新增帳戶對應表、不改 app_user 或既有財務 schema。

## 背景

目前 Fubon 個人流程以 configuredAdmin 決定寫入者，變更 ADMIN_EMAIL 可能使同一套券商資料轉到別人。使用者明確只允許 tw.leader@gmail.com 的相關資料寫入，因此以專用設定固定目的地；這是本地授權對應，不假稱富邦提供了券商帳號與Google email的自動聯結。

寫入採保守界線：專用owner與configured admin必須是同一既存ACTIVE ADMIN。一般ADMIN_EMAIL變更只停止同步，不移轉資料。報表純讀另採「專用ACTIVE owner本人」；不因ADMIN_EMAIL變動或flags關閉而把自己的舊資料鎖死，也不能交給另一位ADMIN。Task394／395原財務入帳尚未完成，本task不新增該writer。

## 要做什麼

- [ ] **405.1 編輯範圍與協調。** backend的 `integration/fubon/` 同步／writer／snapshot ownership、必要 `service/fubon/` owner policy/ports、中立owner值及JPA/config adapter、對應tests與application設定；Python只為同批explicit-selector capability修改既有capture/五種accounting response及tests。必要追蹤設定僅 `.env.example`／Compose對FUBON_SYNC_OWNER_EMAIL的傳入與部署文件；正式.env只有協調者精準新增此key，禁止覆蓋其他值、secrets或旗標。本task與404/406會改同一批sync/writer/DTO，須同worktree按序接手，不能兩個agent同時編輯。其他worktree保留原樣，不stash/reset/刪除。

- [ ] **405.2 專用owner設定。** `fubon.sync-owner-email`只來自 `FUBON_SYNC_OWNER_EMAIL`，部署固定使用者本輪指定值；Java不能內嵌私人email常數。trim + lowercase(Locale.ROOT)後驗合法非空email，匹配既有正規化app_user.email；缺漏/非法回sanitized `SYNC_OWNER_NOT_CONFIGURED`。不fallback到ADMIN_EMAIL/第一位admin/第一位user、不loginUpsert、不建user、不升角色。範例文件用中性地址，catalog/DTO/log/error不輸出實際email。不得另加第二個env capability旗標或移動秘密掛載。

- [ ] **405.3 窄policy與fresh directory。** core依 `FubonSyncOwnerPolicy`及必要的config/directory ports，回不可變 `FubonOwnerDecision(ownerId, reason)`；中立identity可含id/normalizedEmail/active/admin，但只在business內部。JPA/Files/config注入留外層，不import UserAdminService concrete或將其managed AppUser作新跨層DTO。directory只需按email純讀projection與按expected id鎖後fresh projection；配置port提供dedicated/configured-admin正規化值，不複製所有管理者能力。正常寫入必dedicated id存在、ACTIVE ADMIN，且configured-admin id/email同一；不同回 `SYNC_OWNER_MISMATCH`，不存在/停用/非ADMIN回 `NO_ACTIVE_SYNC_OWNER`。可映射原各流程NO_OWNER outcome並保留reason，不改其他管理者功能。

- [ ] **405.4 五個preflight與writer全套用。** inventory、trades、bank-balance、settlement、realized各保留原feature/global/config/capacity/calendar順序；通過後才owner preflight，且在第一次broker HTTP與個人財務資料查詢之前。disabled仍零owner query／零HTTP。inventory/trade/bank各獨立writer與後續report writer均再核對expected owner，不能只在service驗一次。所有owner SQL顯式帶owner。configured admin改成B而dedicated仍A時，五流程都停止、不能改寫B；原市場資料流程不得因dedicated缺漏而停機或改成只查A雷達。

- [ ] **405.5 保留snapshot firstDB lock並消除OSIV過期授權。** 所有SDK/HTTP與外部preflight在短writer交易外；本次明定包含InventoryWriter在內的個人writer交易timeout=30秒，owner鎖等待不得超過交易剩餘預算，不能假設既有無timeout的@Transactional已提供期限，也不可為設定期限提前插入SQL；snapshot writer第一個DB operation仍為共用snapshot row lock，拿到之後才透過directory對目標app_user行做fresh `FOR UPDATE` projection。不得先查owner破壞首SQL，亦不得用OSIV中較早的AppUser或HTTP前decision。非snapshot writer先鎖expected app_user，再鎖該owner的ledger/report target；不新增全表鎖。鎖後比對expected id、dedicated/configured-admin正規化email/id、ACTIVE ADMIN，再驗immutable prepared batch、日期/數值/既有broker條件。owner行鎖持有至commit，並發停用/改email/降權不得在同次commit前穿過；失敗或lock timeout整批rollback，SUCCESS只有proxy真commit後。Task399的fresh children、相同snapshot鎖、aggregate與afterCommit backfill保持。

- [ ] **405.6 完整PUT的ownership同一政策。** `FubonSnapshotStockScopeOwnershipAdapter`以同一dedicated-owner政策取代單獨configuredAdmin；只有READY、inventory-enabled、!tw-live、同owner今日latest且PUT最終effectiveSnapshotDate合格才SOURCE_OWNED。其他owner含另一ADMIN均PAYLOAD_OWNED，其他手動CRUD與non-Fubon rows不改。本地ownership不假稱知道Python當次selector；不能在PUT打Python config/SDK，也不新增假能力旗標。writer仍另驗下面同批explicit-account能力。

- [ ] **405.7 五種normalized batch增加必填strict boolean。** 只對下表success envelope追加 `accountBindingExplicit`，其他route/欄位/排序/錯誤/counters不變。Python的同次不可變capture包含selector是否明確、selected account、response/token；只有秘密selector pair明確、selected精確匹配且所有既有raw account/branch驗證通過才true。legacy未設selector而唯一stock account的純讀可false；成功空回應仍須selected與明確selector一致。raw account/branch/token不出Python、不repr/log，HMAC只同批correlation、不當永久owner/account FK。

| 精確adapter路由 | 必加欄位的DTO |
|---|---|
| POST /internal/portfolio/read | PortfolioResponse |
| POST /internal/trades/read | TradeBatchResponse |
| POST /internal/bank-balance/read | BankBalance |
| POST /internal/settlement/read | SettlementBatch |
| POST /internal/realized-gains/read | RealizedGainBatch |

  Java decoder拒missing/null/number/string等非真正boolean；false的合法純讀batch不能進個人writer，reason為`ACCOUNT_BINDING_NOT_EXPLICIT`。prepared batch保留true證據且writer再次驗，不允許未帶能力的手工prepared值通過。正常舊圖表/市場/ETF DTO不加此欄；在mixed版本部署只可failclosed，不能missing當true。單一session/accounting capture、5/s與actual dispatch計數、所有deadline、30分鐘technical與quote240/min原樣。

- [ ] **405.8 僅收斂既有pure request helper。** inventory/trade的兩個internal token filter重用現有 `FubonAccountingSyncRequest.targetsEndpoint/hasValidParameters/dryRun`，保留各自exact PATH、constant-time token與outcome/filter鏈。只允許省略dryRun或單一小寫true/false，unknown/duplicate參數拒絕。controller仍委派，不改全域security/MVC matcher、不新增route；驗證只直接測helper/MockHttpServletRequest的純parser及原正常filter fixture，不寫或執行任何live/internal alias、方法矩陣或security probe。

- [ ] **405.9 規劃新增的正常／拒絕與race測試。** 以下均為規劃新增，不是既有通過報告：真PostgreSQL的A=專用owner/B=另一ACTIVE ADMIN，A正常inventory/trade/bank只改A，B同快照日/同成交號/同金額不變；ADMIN_EMAIL改B而專用仍A時五preflight零HTTP/零mutation，直呼writer(expected B)亦拒絕。缺設定/非法/不存在/停用/降角色/改email不建立user；capture explicit true/false、空batch、missing/null/string/0/1、source mismatch都測。OSIV預載舊AppUser、HTTP期間變更、等待snapshot lock期間變更、writer拿owner鎖後另連線update/commit順序、lock timeout與rollback用latch/兩連線可重現；另一連線持有owner鎖時須在剩餘30秒預算內退出且children／aggregate零mutation；驗firstDBlock、原full PUT併發、Task399/400、numeric/日期/ledger duplicate及manual row保留。不能mock掉DB後宣稱跨owner保證；不得真人SDK。

## 驗證

以下從feature worktree repo root執行；須先有獨立spec審查。只用fake SDK、隔離PostgreSQL/Testcontainers；不要為驗收啟用Fubon。

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
export DOCKER_HOST=unix:///Users/steven/.docker/run/docker.sock
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml clean test -DextraArgLine=-Dapi.version=1.44
PYTHONPATH=fubon-broker-service/src /tmp/fubon-venv-t395/bin/python -m pytest -q fubon-broker-service/tests
git diff --check
```

Python改動另用既有linux/amd64 test target及network none重跑完整suite，不能重複加總為另一套測試。所有新測試名稱若新增，清楚標「規劃新增」直到實際存在並取得結果；保留既有全量suite，不只測disabled。

協調者在對照main .env來源後，只設FUBON_SYNC_OWNER_EMAIL，不輸出整份.env/帳號/secret，核對其餘hash/key-values保持。完整diff獨立架構審查後，依run-stack從已測來源build/recreate business-services與fubon-broker-service，再驗image source hash/health與既有GET白名單、authenticated UI；保持FUBON_ENABLED=false及所有既有flags。不真人login/read、manual sync/subscription/rescan POST、不route alias/方法矩陣/security探測。401–405單獨階段catalog52/15/37，若406一併交付且兩報表已完整驗收則最終52/17/35；JOBS64不變。

通過後由協調者commit→main no-ff merge→push並從main重建／純讀驗收。不得因本task完成刪兩個原Claude refs。

## 完成報告

尚未實作；checkbox全部保持未勾選。完成時回填changed files、獨立審查、full suite與兩owner/鎖/rollback證據、env僅一key變更、原flags與secrets保留、main merge/push SHA及image來源；不把原Task394／395財務入帳標完成。
