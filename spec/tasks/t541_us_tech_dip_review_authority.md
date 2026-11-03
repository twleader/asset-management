# [t541] 確認美國科技股低接研究規則包發布及獨立驗收

**對應 Requirements:** Requirements 187／193（SRPP 唯讀報告計算包與現行規則包 authority 發布鏈）
**前置任務:** SRPP D-206 已合併，t540 已發布包含 D-206 的共同規則包
**Liquibase changeset:** 無

## 背景

使用者要求將美國科技巨頭低接分析方法納入共同規則包。SRPP D-206／政策2.55.0新增動態、具來源的成長、資本支出與自由現金流、正常化估值及財報事件研究流程，區分研究排序與可執行建議；不永久指定公司排序、不硬編當日行情或財報數字，不放寬既有金融公式、雷達方向、15%淨獲利空間、0.25%／0.75%／2%部位上限或資金底線。

SDD第1、2步確認：既有 Requirement193 的 User Story／AC及 design 的 authority 發布資料流已涵蓋政策版本演進、精確 model／manifest bytes、golden、insert-only SQL、JAR及exact-hash API回讀。本輪資產端無新增契約，因此不修改共享 requirements.md／design.md。初版task已獨立spec-review；其後並行t540吸收已合併D-206，完成共同來源的實際產物、登錄與部署。本任務改為確認與獨立驗收已發布同版，避免重複生成、登錄或部署。

## 要做什麼

- [x] 541.1 保留原D-206核准範圍，再以已合併main為最終權威。原SRPP feature commit為af5ae389f0fbaf3e4b96b360f96c0312848f4596，初始bundle為743a14f10c61c3d299519cf8b1656af9167b8414ee01191b748ffee2e2e25b5d。t540接回修復後，SRPP main為6ff2f886e6684655e6b677cb33567bcc65ae412b；最終bundle為3713fffaf08ca7f365a9c942c9f5e8c298bed99816f6ac1f29d304506c6c1521。逐位元確認D-206文件及canonical研究設定的原指紋完整保留，不拿初始hash當最終部署證據。
- [x] 541.2 實讀t540完成報告及兩個main產物，確認其原工具產生的model／manifest／insert-only SQL與兩份golden可沿用；不取用他人未合併工作。資產classpath的model與manifest須分別逐位元等於SRPP main，不能只改manifest。主22案例及long-short15案例全部金融輸入／expected與D-206前基準逐值相同；calculator hashes僅承接已合併午盤修復。正式targets與公式保持。
- [x] 541.3 保留strict JSON／duplicate-key／raw-byte／JCS及report十欄semantic gate。t540已完成獨立spec／架構審查、42項Java、13項Ruby／307 assertions、4項authority負例及golden檢查；本輪另獨立執行authority checker與15例long-short check，不重跑已通過同批測試。最初對SRPP-main檢查因缺ignored日曆失敗，保留首錯；改用已安全快轉到相同main commit且含原始2026日曆的驗收feature，日曆與實際runtime逐位元核對後checker PASS。未合成日曆、未修改來源或金融閘門。
- [x] 541.4 沿用t540已完成的兩repo feature／no-ff main／push／exact-ref、單次insert-only registry及clean-main business-services rebuild／recreate；本輪不重複登錄或部署。獨立唯讀查新registry row的bundle、formula version、policy document、formula manifest，四欄逐值等於已合併generator SQL；新JAR的model／manifest／Swagger／SQL四資源逐位元等於main。D-206原文件／設定指紋必保持，容器／image在驗證前後一致。歷史26row未改、46環境鍵與其他8容器保留的證據由t540收據提供，不誤稱本輪重新執行。
- [x] 541.5 從實際SRPP工作區及Python唯讀執行發布連線檢查。兩consumer精確新hash首查503 CONTEXT_NOT_READY原始回應保留；既有背景狀態變為已就緒後，各自具名新檔再GET一次，直接取得同email最新資產並以既有verifier驗L0、ACTIVE owner、consumer、日期／slot、來源、model／manifest、input／content及完整契約。200只證明晚間診斷包接受性；正式盤中日報採用仍NOT_YET_EXECUTED，trade_authorization=false，未知與PARTIAL／UNAVAILABLE不補零、不解除已驗證BLOCKED。禁止手動producer、POST、刷新、重跑報告、寄信或交易。所有raw與私人財務資料只留本機/private/tmp，不提交公開repo；本task僅保存去識別的狀態、hash、counts及證據路徑。

## 驗證

以下指令只驗已發布產物；帶--check的工具不改來源。

```bash
python3 -B /Users/steven/Project/SRPP/scripts/policy_bundle.py check --purpose daily --consumer Codex
python3 -B /Users/steven/Project/SRPP/scripts/daily_runtime_contract.py check
python3 -B scripts/check-srpp-report-authority.py --srpp-root /private/tmp/srpp-us-tech-d206-20261103 --check
python3 -B scripts/generate-srpp-long-short-exit-golden.py --srpp-root /Users/steven/Project/SRPP-main --check
bash scripts/spec-check.sh
git diff --check
```

既有發布連線工具實際從/Users/steven/Project/SRPP執行，參數為最終3713fffaf08ca7f365a9c942c9f5e8c298bed99816f6ac1f29d304506c6c1521，原收據使用exclusive-create，不覆寫。report-calculations使用同hash與Swagger d8211f7c50da18c0eba69bb2dfdfcc4f4b2a6cc5ff6341ec28d6d63dd00116f3，reportSlot=12:00及真實檢查時間；不得假造盤中時鐘或更換來源取得PASS。本次文件收尾須依commit-merge-push落地main與既有origin；不變更執行資源，不重建同版服務。

## 完成報告

- D-206文件SHA為20a27fa0f06f22d661caaa3b38831992b6c4cd8e897ac370d4a603ab925f0347；canonical研究設定SHA為113f36ef5db148d1d06b70472f803169302aa221c62be8e2083a9d2e3f9e418e，與核准來源完全一致。最終model原文SHA為7a7ad412bb829db6f1451e7b1d7c46482137a0cec810ddea1ae22b34e5702f7e；manifest原文SHA為c066521b622bed5b0a1365add60424b75536c5d91247edcca96057be5def598e，SRPP main、實際runtime、asset classpath與運行JAR一致。
- 23:52:41獨立回讀PASS：registry新row四欄全文等於已合併SQL；formula version為ASSET_MGMT_SRPP_V2。運行container為d3ba04cf5914b3c837a0a483f1ded3dab06775b684d8cf36b18b3dc11db13dc0，image為sha256:8ba68f3fb41c30aaf8c38e35261e79fbe3058552c556281cab68f4c3a670bf9f且healthy；JAR四資源逐位元同源。23:56後再次inspect確認仍是同一container/image。
- 23:52:38發布連線檢查PASS：官方日曆365列、TWSE日線21點、台股報價23列皆HTTP200且內容合格；使用實際Python3.14。23:53:00兩consumer首查503均保存；背景自然就緒後，23:56:32／33兩consumer新GET均200且既有獨立verifier PASS，同email直接資產L0與ACTIVE owner核對成功。coverage均0 COMPLETE／7 PARTIAL／5 UNAVAILABLE，trade_authorization=false；正式日報盤中採用維持NOT_YET_EXECUTED。
- 安全證據摘要與完整本機raw分離：/private/tmp/d206-asset-release/approved-d206-scope.json、combined-main-static-readback.json、independent-runtime-readback.json、9090-connectivity.json、report-exact-hash-summary.json（保留503）、independent-api-acceptance-ready.json（後續200）。完整raw與verifier結果僅同目錄本機保存，不納公開Git。
- 執行資源已由t540落地SRPP main6ff2f886e6684655e6b677cb33567bcc65ae412b及asset main171c593ccc3a750b25bfa673d29312ceb6069fa8；t540文件收尾已至asset main3479ada5。此t541僅文件驗收收尾，不改bundle或已驗收服務。
