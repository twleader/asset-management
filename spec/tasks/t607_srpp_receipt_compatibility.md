# [t607] 同步早報 D-222 收據相容性修復的規則包權威

**對應 Requirements:** Requirement 210（SRPP 退休配置與規則包權威同步）
**前置任務:** t604、t606
**Liquibase changeset:** 無

## 背景

2026-11-19 Codex、Claude 早報均已啟動，但 SRPP report_evidence_guard._global_build_receipt_version_valid 要求 manifest.decision 恰為 D-222。當前整包 D-225 仍使用 D-222 009826 計算器，因此有效三張容量收據被誤判，兩份報告都沒有草稿。

## 要做什麼

- [ ] 607.1 同步 SRPP 修復後凍結的精確 manifest，重產原 insert-only registry SQL 與綁 hash 的既有 report golden。使用原 export_settings_runtime.py 必要同步 srpp-simulator/runtime（含 runtime-manifest 與 policy manifest），並以 --check 核對；模型與正式政策文件 bytes、金融 outputs 及 calculator hashes 保持不變。若權威檢查要求其他既有鏡像，僅以原 generator 同步，不改公式或規則值。
- [ ] 607.2 SRPP 修復只分離計算器 D-222 與整包 D-N（N>=222）身分，核對 reserve_allocation_d222.decision=D-222 且 enabled=true，保留原 hash、policy version、日／週上限、份數與所有硬閘；支援兩 consumer、兩 profile。後續規則失效、偽造上限、錯 hash/version、較舊或格式錯誤 manifest 均拒絕。資產端不改 Java、API、Swagger、公式、DB schema 或排程。
- [ ] 607.3 獨立審查與目標測試完成後，兩專案 feature commit、main no-ff merge、push 並核對 remote；從已合併 SQL insert-only 登錄並精確核對完整政策與公式內容。登錄後立即以新 hash 呼叫 9090 report-calculations 留存原始回應；503 表示 API 尚未驗收，不得冒稱成功或啟動 producer。
- [ ] 607.4 從乾淨 asset main 正式設定重建並 recreate business-services 與 srpp-simulator。保存容器與 image、JAR authority bytes、設定一致性及無關服務保留證據。唯讀核對 simulator catalog.baseBundleSha256 等於 business authority 與新 hash，並驗證既有設定中心 GET 不因更新出現 SIMULATOR_POLICY_MISMATCH。再以同 owner、consumer、日期、時段、完整 policy/Swagger hash 與固定 calculationRunId 純讀 API，執行原 SRPP verifier；合法報告採用仍須完整當輪必讀與直接 L0。不得重跑早報、建立草稿、寄信、交易或刷新來源。

## 驗證

在凍結 SRPP feature 路徑設為 SRPP_FEATURE 後執行：

```bash
bash scripts/spec-check.sh
python3 scripts/check-srpp-report-authority.py --srpp-root "$SRPP_FEATURE" --check
python3 -B "$SRPP_FEATURE/scripts/export_settings_runtime.py" --destination "$PWD/srpp-simulator/runtime" --check
python3 -m unittest discover -s "$SRPP_FEATURE/scripts" -p test_d222_report_compatibility.py
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=SrppPolicyDocumentTest,SrppReportModuleCalculatorTest test
```

generator 使用現有無參數 Ruby 入口產 SQL；以原 golden generator 重產後對照金融值。正式部署使用已合併 main 的現有設定執行 `docker compose -p asset-management build business-services srpp-simulator` 與 `docker compose -p asset-management up -d --no-deps --force-recreate business-services srpp-simulator`。實際 GET 參數先依 SRPP 當前 Swagger 路由核對再使用，保存完整回應和 verifier 收據；一般健康檢查不能取代精確 hash 業務 API 驗收。

## 完成報告

待驗收後補充；本輪未補跑早報。
