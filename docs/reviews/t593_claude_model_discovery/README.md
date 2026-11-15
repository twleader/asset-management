# Claude CLI 模型自動探索驗收

Task 593；2026-11-15（Asia/Taipei）。取代Task592 Claude三個CONFIGURED型號與固定effort清單；現在由已安裝Claude CLI的initialize控制回應讀取與官方SDK supportedModels()同源的模型metadata，來源為RUNTIME。只傳控制初始化，不傳user prompt或啟動推論。

## 行為

- 型號使用CLI具體resolvedModel，標籤顯示名稱與版本；effort只取CLI supportedEffortLevels，不在程式或registry預先列型號／能力。
- 舊registry models原bytes與權限保留，但不再是來源、過濾條件或失敗fallback；新建Claude工具不含models配置。
- 模型與effort變更納catalogRevision，舊選擇須重新載入；歷史run與數值不改。
- 同一模型別名去重；default/opusplan等行為模式、Fable、付費context修飾、未知費用與未知schema不放行。維持個人訂閱、extra_usage=false、無APIkey、無付費fallback。

本次CLI實測有四個具體模型，其中Fable沒有可驗證的訂閱內費用證據，因此安全清單是Haiku／Opus／Sonnet5.5三項；這是當下CLI及費用條件的結果，不是三個固定型號上限。測試以兩個不同標準Opus版本加Sonnet/Haiku證明第四個安全模型也會完整呈現。新lineage仍須有可查證的費用政策，不能為增加選項開放額外付費。

## 驗證

59項Python測試（38原回歸、21新增）由實作者與主代理各自執行通過；含真實去識別metadata、第四模型、去重與矛盾effort、未知metadata、legacy不裁切、費用拒絕零推論、共同7.5秒deadline、成功／失敗／超限的程序及pipe清理。實際metadata-only source檢查約1.33秒。

規格獨立審查PASS（1項minor為自足任務模板格式）；架構獨立審查PASS（無finding）。本輪沒有Java/BFF/Vue/DB/9090或SRPP規則變更，不重建未變更容器；host服務需要installer重新載入，並透過既有business center GET驗證新資料。實機部署與訂閱解讀收據見feature-runtime.json，未保留token、帳戶身分或完整模型文字。

[測試及source hash](tests.json) · [規格審查](spec-review.md) · [架構審查](arch-review.md) · [操作說明](../../operations/srpp-local-ai.md)

使用時，在情境模擬的AI工具區按「重新載入」，選Claude CLI；模型清單會從CLI探索，顯示「由工具提供」。能力快取最長20秒；探索失敗會顯示不可用，不會退回三個固定配置。
