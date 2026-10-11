# [t495] 所有 9090 API 統一 Swagger 同步 SRPP

**對應 Requirements:** Requirement 190（AI 流程觀測與完整 API 文件）
**前置任務:** t492、t494
**Liquibase changeset:** 無

## 背景

原金融 24 pairs 與觀測 1 pair 分兩文件，SRPP mirror 只有金融內容。使用者本輪明確要求所有 API 同一檔，取代先前本輪不可改 SRPP 與金融 Markdown bytes 的限制。

## 要做什麼

- [x] 495.1 保持 docker-external-api.yaml 1.25.0 和 ai-flow-observation-api.yaml 1.1.0 的原 bytes。renderer 先逐份驗 refs 再合併，拒 path／operationId／component 同名碰撞；顯示全部25pairs（20 GET／5 POST）、用途、operation security、requestBody、參數位置、回應headers／schema／範例與可達 schema 必填／nullable／閉合／限制／oneOf。保留既有金融版本表與另列觀測版，不改路由、授權或 API 行為。
- [x] 495.2 三份 unified Markdown 位元組一致：docs/openapi/9090-api-swagger.md、backend/src/main/resources/srpp/9090-api-swagger.md、/Users/steven/Project/SRPP/docs/9090 Port API Swagger.md；--stdout／--check 零寫入，stale失敗，CLAUDE_CODE_REMOTE=true只處理前兩份。獨立觀測文件仍可保留。
- [x] 495.3 PublishedSwaggerIdentity 原resource機制採新hash，重建/recreate business-services 並驗jar實際resource hash一致；不改歷史immutable receipt。SRPP manifest排除Swagger文件但包含consumer schema。先驗每個source既有manifest hash與path集合一致，任何其他漂移都拒絕；只改schema x-swagger-sha256且驗證schema keywords、model_inputs及formula bytes原样，用原工具重產僅此metadata變化的新manifest，同步backend decision/policy_manifest.json並以srpp-report-policy-register.rb產生冪等registry SQL登錄新bundle，保留原row／歷史receipts。驗policy_bundle check，不改策略／日報排程，不執行金融POST。
- [x] 495.4 回歸既有OpenAPI/route union測試，補合併輸出覆蓋、request/security/schema限制、collision/stale與雲端mirror行為。部署只讀smoke，不建立金融capture/evaluate/mail/events。

## 驗證

```bash
ruby scripts/render-9090-openapi-docs.rb --check
ruby scripts/tests/docker-external-api-openapi-test.rb
ruby scripts/tests/unified-9090-openapi-renderer-test.rb
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=PublishedSwaggerIdentityTest test
python3 /Users/steven/Project/SRPP/scripts/policy_bundle.py check --purpose daily --consumer Codex
docker compose -p asset-management build business-services
docker compose -p asset-management up -d --no-deps --force-recreate business-services
```

部署前以正式 main .env 為權威比對feature設定；部署後讀jar resource SHA並核三份文件，health／API只讀檢查。兩repo只提交本次授權變更，保留其他未提交變動。

## 完成報告

已實作兩 YAML 聯合 renderer，25 method/path pairs（20 GET、5 POST）及226個可達schemas；requestBody/security/參數位置/完整response與schema限制均可查。三份Markdown一致，SHA256 `426b3d1f1423fcae248fb69a5a84c44d54e03d1b6213f22daabd9620ad942357`。金融1.25.0／觀測1.1.0及兩份YAML bytes不變。新增metadata sync工具，先驗126個來源完整一致、只更新consumer schema x-swagger及runtime對應sourcehash，重產manifest並同步backend artifact；policy bundle為 `bb6f19084c6c9720e3571ac9ad1cd5ef68245759d231a26a2981d4033988db5c`。schema所有驗證keywords／runtime所有語義／model／formula bytes原樣，golden只同步來源身分header，歷史receipt不改。

獨立spec審查3輪均0 findings；最後一輪補runtime metadata依賴。架構審查發現--check bytecache與首次write失敗rollback兩項，已修且獨立複核0 findings。renderer3 tests/531 assertions、metadata4隔離測試、Java26 tests（PublishedSwaggerIdentity／DailyDecisionCalculator／SrppReportModuleCalculator）、SRPP52 consumer/runtime/shared tests皆PASS；完整OpenAPI、spec-check（0 BLOCK/0 CHECK）、policy_bundle daily check及daily_runtime_contract check皆PASS。feature business-services image rebuild/recreate驗收及新bundle冪等registry登錄由run-stack完成，Jar內兩resource SHA需與source一致，保留舊bundle。正式main落地與部署結果由本輪最終回報記錄。

Task495 依賴補充：daily_runtime_contract.json 的 source_sha256 同樣納 consumer schema。更新schema後，先以既有 daily_runtime_contract.make_contract/canonical 重產，驗僅 source_sha256[assumptions/srpp_report_calculations_schema.json] 變化、所有語義欄位原樣，再重產policy manifest（只允許schema及runtime contract兩個source hashes變化）。metadata同步工具須fail closed於其他漂移，失敗rollback三個SRPP檔及backend artifact；--check零寫入。
