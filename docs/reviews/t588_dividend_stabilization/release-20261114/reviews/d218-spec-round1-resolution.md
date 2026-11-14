# 第1輪處理紀錄

1. Major「缺wave時觀察起點缺來源」已修：獨立市場observation固定取D198同一前20個完成TW sessions，從該窗口首日重播running low，只出d218ObservedNoNewLowSessions，不建立wave/stage；缺state的wave-bound facts未知、額度0。要求正式module fixture證明市場完整/state缺失仍有市場事實。
2. Major「回測當日可確認」已修：design與requirements/task統一latest_completed_date > retest_date；回測日即使close上升仍等待，下一更後完成日才可確認，要求fixture。
3. 必要契約修訂：原fact name closed enum需新增六個D218欄位，明訂獨立observation與wave-bound值區別，維持shape與純讀路由，同步YAML/consumer schema/三份Swagger與新hash。
4. 歷史安全修訂：新增公式V3與可重播原bytes來源hash generator，V1/V2原bytes/fixture不改；V3沿V2 nullabledeposit語意與既有scheduler相容。原registry不更新刪除。
5. WAIT限縮：新FIRST/RETEST不加MA5/MA20/KD時機，但目前WAIT沒有可靠typed cause，保留其獨立方向限制並具名RADAR_WAIT_CAUSE_NOT_VERIFIABLE，不用自然語言猜原因。
6. 完整canonical state欄位：所有wave有approval_evidence_ref、wave_history_complete及prior_wave_closed_evidence；FIRST實際fill/pending含observation_anchor_date、observation_anchor_low_raw、evidence_ref。FIRST前同wave更新running low重算，FIRST後沿其原來源固定保護線。

未開始實作、未宣稱測試/部署完成。
