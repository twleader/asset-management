# [t439] 匯出背景完成結果以短交易守住最新設定

**對應 Requirements:** Requirement 157（匯出背景完成結果以短交易守住最新設定）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

四服務目前讀取aggregate後經長I/O，finally save原parent/times；UI並行刪時間點或改路徑會被stale merge覆蓋甚至復活。舊未整合修補不能原樣搬入。所有API及dual output能力維持，僅修收尾競態。

## 要做什麼

- 僅四服務ExportScheduleService、CommodityExportScheduleService、RealizedGainExportScheduleService、IndexExportScheduleService接手此修復。長產檔／本機寫檔／Drive I/O在交易外，用不可變captured執行參數；獨立Spring proxy bean短交易新讀目前parent及child，只寫lastRunDate/lastRunAt/lastRunStatus/必要updatedAt與現行代表摘要。不得merge captured entity、重建times、復活removed child或deleted parent。
- persisted identity包含schedule id+owner id+child id+captured hour/minute。guard使用captured執行日，只能落已存在且同parent並時間相同的child；disabled但身份時間相同可記實際結果，時間改變則skip。legacy空children只允許執行前短交易鎖parent、新讀確認仍空且legacy時間相同後建立並flush既有fallback child取得id，再capture/I/O；結果階段永不建立child，移除重建同時間但不同id亦skip。
- UI更新與短結果交易採同一parent pessimistic write lock，或等效無stale entity merge的原子欄位更新，不能只鎖background而UI仍load/save舊aggregate。parent/child lock order一致，建立設定用existing唯一owner約束且競態失敗重讀，不額外新增schema。parent代表摘要依current children現行演算法新算，不用captured時間表。
- Drive結果只在current gdriveEnabled/gdriveSubpath及所有被capture且影響Drive目的地的設定仍完全一致才更新gdriveLastRunAt/gdriveLastStatus；本機結果不因此被丟棄。runNow同樣不可save stale aggregate；不存在且執行前未保存的default設定不因產檔完成而插入，結果仍依現有response回傳；manual不更新child lastRunDate。成功/失敗保留既有guard及status截斷長度語意。
- HTTP/BFF/DTO與排程cron、時間語意、tenant隔離、雙格式一次doc、檔名/路徑/Drive功能不變；不觸發實機匯出或Drive驗收，以fake I/O和真交易競態證明。

- [x] 439.1 三階段capture/outside-I/O/writeback。獨立Spring bean public capture與outcome writer REQUIRES_NEW；不可self-invoke註解式交易。capture lock current parent，產immutable input/完整child identity和Drive目的地，退出交易才doc/render/file/Drive。runDueExports不包長交易、public入口不可整輪transaction。JPA child在短交易中初始化後轉值物件，不讓detached lazy集合穿越I/O。
- [x] 439.2 四服務UI update方法也在同parent PESSIMISTIC_WRITE鎖後fresh read，再只更改設定欄/目前子集合，保留最新run欄。background writer依parent→child lock order，只更新結果，不merge captured JPA entity；child id/hour/min與parent/owner不同skip。legacy在執行前fresh lock空children時依現存fallback建立flush取得id，結果不能建立。deleted parent立即skip。
- [x] 439.3 child lastRunDate成功／失敗皆guard，取max(current.lastRunDate,captured attemptDate)，null視為尚未執行，日期只能前進；lastRunAt與lastRunStatus必須在同一completedAt>=current.lastRunAt條件下原子更新。較晚完成的D1可以成為最近完成結果，但不得把已完成D2的日期guard倒退。parent最近摘要只在completedAt>=current.lastRunAt時更新並按current children現存代表法同步legacy代表欄；時間相同同id和已disabled child仍可記執行，時間變化skip。Drive metadata需captured enabled/subpath與所有目的地設定相同且結果不較舊才寫，不延伸變更任一設置。四服務皆補跨日D1晚於D2收尾的PostgreSQL真交易fake-I/O測試，驗證D2後續due判定不再執行。
- [x] 439.4 manual runNow捕捉設定但長I/O外，結果同writer而不寫任何child日期guard；執行前setting不存在可用immutable defaults產結果，但不在結果階段insert。保留各頁原RunNowResponse/錯誤status與同一doc一次render雙檔，error本機失敗不得真的syncDrive驗收。
- [x] 439.5 不改cron；故無新增schedule登錄。修改所有四服務實際入口/catch/finally及repository lock支援，保留explicit-owner查詢、文件truncate長度、各owner/time失敗隔離。不要順手修另外四匯出服務。

## 驗證

```bash
bash scripts/spec-check.sh
JAVA_HOME=$(/usr/libexec/java_home -v 21) /usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
```

新增focused fake dual-writer測試與PostgreSQL真交易整合（僅測試fixture schema/database，絕不改production rows）。用CountDownLatch在I/O fake阻塞、另一thread UIupdate commit後release，在5秒bounded await/future下證明父/child沒有復活、時間/路徑/Drive變更保留、same-time新id不誤記、deleted parent skip、legacy前置child id、manual不更guard、out-of-order completion不倒退。偵測TransactionSynchronizationManager.isActualTransactionActive：fake I/O必false，proxy capture/writeback true，兩thread真commit，不用Mockito宣稱有鎖。測試須覆蓋每四服務及UIupdate反向競態；fake本機fail/Drivefail仍只正確status。

Docker驗收只讀既有四setting GET，必要顯示新image provenance；禁止tick/selfHeal/runNow啟動真匯出，必要控制test環境scheduler停用，不能以外部Drive成功當驗收。


驗收不呼叫券商、不下單、不觸發外部通知，不讀出secret。新增單元／整合測試為待實作，不以不存在的具名測試類作已完成證據。實作後依project規定作獨立diff-scoped架構審查、Docker image rebuild/container recreate（從main同步環境或先比對.env，不輸出值）與只讀功能驗收；不以container healthy取代資料語意。最後自動focused commit/no-ff merge/push，main部署與版本一致。

## 完成報告

已完成四服務 immutable capture、短 REQUIRES_NEW proxy 與慢速 I/O 分離、UI 同鎖新讀、OSIV 舊子集合 detach／鎖定 reload、identity／日期 guard／父摘要／Drive metadata 時間守門。真 PostgreSQL 70 項（父摘要案例涵蓋三個 non-Index store）與既有 I/O 96 項皆通過；只用 fake I/O，不執行正式匯出或 Drive。

本批後端最新256組測試報告共2214項、失敗0／錯誤0；BFF全套67組316項、失敗0／錯誤0。獨立架構最終覆核0 critical／0 major／0 minor；機械 spec／OpenAPI／兩鏡像／102表 schema drift 已通過。feature business／frontend／BFF 均已重建並重新部署驗收；分組提交、no-ff main 合併推送與 main 最終重建於本批收尾執行，不由測試替代部署。
