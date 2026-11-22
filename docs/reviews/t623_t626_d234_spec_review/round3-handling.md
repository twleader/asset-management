# 第 3 輪（最後一輪）Minor 處理（Claude 主工作直接修正）
1. Requirement 223 coverage 敘述：requirements.md 改為與 626.16 一致（WAIT／VETO／NOT_DECIDED 或缺 base 單位為 NOT_EVALUATED）。
2. 626.5 收盤後段落：ADOPT／REDUCE → COMPLETE；WAIT／VETO／NOT_DECIDED → NOT_EVALUATED 並另加收盤後碼；補 WAIT 測試與 BFF coverage 重算一致斷言。
3. 623.3(2) mids 數值一律 canonical decimal 字串；623.22 加 JCS／envelope 通過測試。
4. 623.7(4)／623.8(4) 補 pace_ok 初始 false 不走訪 records；623.20 PACE_STATE_UNVERIFIED 案帶有效 records 並斷言 evidence 0。
5. 626.9 BOND 呼叫鏈改為 t625 之後簽章 bond(Frozen, Policy, model, extra)。
