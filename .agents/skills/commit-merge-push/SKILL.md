---
name: commit-merge-push
description: 在 asset-management 的 feature worktree 上一次完成「commit → merge 進 main → push」。嚴守兩段式 merge 慣例：feature 分支單行短中文 commit，main 用 --no-ff merge commit，禁止 fast-forward 直推。當使用者說「commit & push」「commit + merge + push」「merge 到 main」「上 main」「推上去」之類指令、且當前在 claude/* feature 分支時使用。
---

# Commit → Merge → Push（兩段式 merge）

把當前 feature worktree 的變更，依本專案慣例落到 `main` 並推上 origin。
產生的 history 樣貌固定為：**main 上一個 `merge: …` commit + 其下掛 feature 分支的 commit(s)**。

> 觸發詞：commit & push、commit + merge + push、merge 到 main、上 main、推上去、把這個 push。

---

## 鐵則（違反就是錯）

1. **commit 訊息**：單行短中文片語（例：「變更畫面」「警示顯示窗收斂為最後交易日及前一日」）。
   **不寫 body、不加 `Co-Authored-By` trailer、不用英文。** 多個關注點要拆多個 commit，不要塞 body。
2. **merge 訊息**：`merge: <該功能簡述>`（同一句中文簡述）。
3. **禁止 fast-forward 直推 main**。一律 `git merge --no-ff`，讓每個功能單元在 main 上呈現為一個 merge commit。
4. **SDD**：凡涉及商業邏輯（controller/model/dto/views/router/liquibase/bff）變更，**同一個 commit 必須含 `spec/` 變更**，否則 commit-msg hook 會擋。
   純樣式 / CSS / AGENTS.md / 本類 skill / typo / import 整理 → commit 訊息加 `[skip-spec]` 前綴即可，**不需要 `--no-verify`**。

> ⚠ **舊寫法已失效，別再照抄。** 這個 gate 從前是 `pre-commit` hook，讀 `COMMIT_EDITMSG` 偵測
> `[skip-spec]`；但那個階段 git 還沒把 `-m` 的訊息寫進該檔，造成兩個 bug：加了 `[skip-spec]`
> 仍被擋（fail-closed），而且**下一次 commit 會讀到上一次殘留的訊息而被靜默放行**（fail-open）。
> 現已改為 `scripts/git-hooks/commit-msg`，以訊息檔路徑作為 `$1`，判斷可靠。
> 因此 `git commit -m "[skip-spec] ..."` 現在會正常放行，`--no-verify` 請留給真正的緊急情況。

---

## Step 0 — Preflight

```bash
WT=$(git rev-parse --show-toplevel)                 # 當前 feature worktree
FEAT=$(git -C "$WT" branch --show-current)           # 例：claude/beautiful-ellis-bfde40
echo "feature 分支：$FEAT"
git -C "$WT" status --short
git -C "$WT" diff --stat
```

- **若 `$FEAT` 是 `main`**：停。本 skill 只在 feature 分支用；直接在 main 上 commit 違反兩段式慣例。
- 找出 main 所在的 worktree（main 不在這個 worktree，通常 checkout 在主 repo 目錄）：

```bash
MAIN_WT=$(git worktree list --porcelain | awk '/^worktree /{p=$2} /^branch refs\/heads\/main$/{print p}')
echo "main worktree：$MAIN_WT"
```

---

## Step 1 — 在 feature 分支 commit

先判斷要不要 `[skip-spec]`：看 `git diff --stat` 的路徑。
- 只動到 `frontend/src` 樣式、`AGENTS.md`、`.claude/skills/**`、純 typo → 加 `[skip-spec]`。
- 動到 `*/controller`、`*/model`、`*/dto`、`views`、`router`、liquibase changelog、`bff/**` → **不可** skip；確認 `spec/` 也一起改了且會被 staged。

```bash
git -C "$WT" add -A                                  # .env 等已被 .gitignore，不會誤入
# 一般（含 spec/ 變更）：
git -C "$WT" commit -m "<單行短中文簡述>"
# 純樣式 / 工具（無商業邏輯）：加 [skip-spec] 前綴即可，**不需要** --no-verify
git -C "$WT" commit -m "[skip-spec] <簡述>"
```

- commit 被 hook 擋（要求 spec）= 訊號：要嘛補 `spec/` 再 commit，要嘛確認真的是純樣式 → 改用 `-m "[skip-spec] ..."`。
- `--no-verify` 只留給真正的緊急情況（見鐵則 4）。現行 hook 是 `commit-msg`、以訊息檔路徑作為 `$1`，`-m` 裡的 `[skip-spec]` 讀得到、會正常放行；主 clone 的 `scripts/git-hooks/` 底下也已無舊的 `pre-commit`。
- 沒有任何變更可 commit？確認是否早已 commit；若只是要 merge 既有 commit，跳到 Step 3。

## Step 2 — 推 feature 分支到 origin（保留分支）

```bash
git -C "$WT" push origin "HEAD:refs/heads/$FEAT"
```

## Step 3 — 同步 main 後 `--no-ff` merge

**重點：main 被 checkout 在 `$MAIN_WT`，不能在本 worktree `git checkout main`。一律用 `git -C "$MAIN_WT"`。**
**main 會被別的 worktree 中途推進，merge 前一定先 fetch + 同步本地 main 到 origin/main。**

```bash
# main worktree 必須乾淨，否則停下來問使用者
test -z "$(git -C "$MAIN_WT" status --porcelain)" || { echo "⚠ main worktree 有未提交變更，停"; git -C "$MAIN_WT" status --short; }

git -C "$MAIN_WT" fetch origin
git -C "$MAIN_WT" merge --ff-only origin/main        # 先把本地 main 追平 origin（吸收他人推進）
git -C "$MAIN_WT" merge --no-ff "$FEAT" -m "merge: <該功能簡述>"
```

- `--ff-only` 失敗（本地 main 與 origin 分岔）→ 停，回報，不要硬 merge 或 reset。
- merge 出現衝突 → 停，回報衝突檔，交使用者決定，不要自行亂解。

## Step 4 — 推 main

```bash
git -C "$MAIN_WT" push origin main
```

## Step 5 — 驗證

```bash
git -C "$MAIN_WT" log --oneline --graph -6
```

預期最上方是 `merge: <簡述>`，其下掛 `$FEAT` 的 commit(s)，topology 與既有歷史一致。

---

## 完成判準

1. feature 分支有單行短中文 commit（必要時帶 `[skip-spec]`），且已推到 origin。
2. main 上多一個 `merge: <簡述>` 的 **--no-ff** merge commit（非 fast-forward）。
3. `git push origin main` 成功。
4. `--graph` 顯示的 topology 與專案既有歷史一致。

## 不要做

- 不要 `git checkout main`（在這個 worktree 會失敗，main 在別處）。
- 不要 fast-forward 直推 main。
- 不要在 commit/merge 訊息加英文 body 或 `Co-Authored-By`。
- 衝突或 `--ff-only` 失敗時不要自行 reset / force / 硬解，停下來回報。
