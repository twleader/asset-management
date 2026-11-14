# SRPP D218 validation scope

New D218 tests cover two completed sessions without MA5, pre-FIRST same-wave running-low reset, equal low, intraday new low, real FIRST/pending protection-anchor freeze, ordered post-fill retest and separate confirmation day, partial-FIRST RETEST incremental 20% cap, DCA shared debits without stage fabrication, FOLLOW monthly ABOVE / K>D, cash-distribution exact boundary, missing approved wave/history and duplicates; API/report tests cover missing receipt, wrong scope, fee-inclusive cap, final repricing and quantity, same-symbol shared budget, 00919 and genuinely adopted short-strategy scope.

## Initial broader regression failures and bounded repairs

Initial 15-module regression: 243 tests, six failures and five errors. `regression.log` preserves the output.

- Missing ignored historical `current_cash_income.json` and `ten_year_performance.json`: copied exact source bytes from the primary SRPP checkout, only for existing offline unit tests. Source/destination hashes are in `historical-fixture-copies.json`. No refresh, formal retirement rerun, or current-trade adoption.
- Missing canonical local test calendars: copied exact 2025 and 2026 files with hashes in `calendar-fixture-copies.json`, only for offline regression.
- Host Python lacked already-declared `rfc8785==0.1.4`: installed that pinned dependency into this feature worktree’s isolated `.venv` with existing scientific packages available through system-site-packages; global Python was not changed.
- `test_rule_cleanup` still expected old D169 messages before accepted D217 configurable V2 totals. Expectations now identify the precise V2 fixed-limit / derived-band rejection. Mutated limits must still raise; no guard was weakened. Bond-cadence mutation assertion accepts the actual existing D195 price-selection rejection as well as its legacy D130 label.
- Real staged-source collector omitted `assumptions/settings_catalog.json` despite the authority verifier requiring it since D217. Added that exact authored source to the existing allowlist; exact-index and zero-write staged checks remain. This correction is needed for the current complete release, and is included in the new bundle.

`regression-v2.log` records the bounded rerun. Rule/runtime/three-consumer checks are stored alongside this note. Swagger metadata synchronization will intentionally produce a later bundle; rerun the identity checks against that final bundle before landing/deployment.
