---
description: 一個明確、逐輪（turn-by-turn）、以檔案記錄的 workflow — user 把 requests 寫進 devlog.md，quickdev 在那裡回答/實作，而非在 chat 裡。涵蓋任何明確的 quickdev session，不只是寫 code：discussions 與 troubleshooting 也算。當牽涉到 code 時，它還會套用一套 Node.js house style（require/pnpm/node:test、FP、snake_case），在每個 logical step 之後 auto-commit，並以 lazy 方式維護 codewalk.md/usage.md/README.md。僅在 user 明確指名時觸發 — 例如說 "quickdev"、"/quickdev"、"start a quickdev session"、"let's quickdev this"。不要從沒提到 quickdev 的一般 coding requests 去推論它。
---

## 這個 skill 是什麼

一個常駐的逐輪 workflow：user 寫下一批 requests 或 questions，quickdev 直接把回答寫進 `devlog.md`（實作某些東西、或僅是討論它們），而非在 terminal 裡對話，然後 user 閱讀並編輯該檔案以展開下一輪。

它適用於任何明確的 quickdev session，不只是寫 code — 同一套 file-logging 紀律涵蓋純 discussion、OS troubleshooting 或其它任何事。當某個 session 確實牽涉到寫 code 或 docs 時，它會額外規範 code 如何撰寫、保持 living docs 是最新的，並在不詢問的情況下自動 commit。

因為它改變了正常的預設行為（不詢問就 auto-commit、盡量減少 chat 回覆），所以只有在 user 明確指名時才啟用 — 絕不要從一般的「help me code this」request 去推論它。

## Top rules — 每一次 invocation 都先做 log

一經 invoke，在做任何其它事之前，先於 project root 打開 `devlog.md`（若尚不存在，以一個 `# Devlog` heading 及初始 template 建立它）。這在還沒有 request 文字時也適用（bare invocation、沒有先前訊息、devlog.md 裡也還沒被打進任何東西）


```initial round template
//--------------------------
// Ask

+
```

在檔案的最底部 append 一個新的 round，形狀完全如下：

```later round template
//--------------------------
// Ask

+ <the user's request>

//--------------------------
// Ans / <YYYY-MM-DD HH:MM:SS>

# <short heading for this round>

<your full write-up of what you did, decided, or are asking back, basically redirecting your output here so they can be persisted>
```

填寫此區塊的規則：

- 逐字（verbatim）複製 Ask。不要改寫、刪修或清理它 — 這是 user 自己對其所問內容的書面紀錄，不是你對它的 summary。通常 user 在 invoke 你之前已經把他們的 request 直接打進了 `devlog.md`；若是如此，別動那一行，只要在其下 append 這個 Ans block。

- 只有 Ans 那一行才加 timestamp，以 `Asia/Taipei` 時間。用以下取得：

	```bash
	TZ='Asia/Taipei' date '+%Y-%m-%d %H:%M:%S'
	```

	不要從 context 猜測時間 — 一律 shell out 去取。

- Ans section 是真正的 deliverable，不是註腳。把你原本會在 chat 解釋的一切都放進去：你建了或得出了什麼、為什麼、open questions、你還需要 user 提供什麼。一旦寫好，你在 terminal 的回覆應是一句短短的確認你完成了（例如「Logged in devlog.md」）— 之後不要再把 Ans 的內容重述、summary 或大聲重複一遍。這個檔案的整個重點，就是用一份雙方都直接讀寫的東西來取代來回的 chat。

- 每一個有意義的 round 都這樣 log — 一個 feature request、一個 bug report、一個 design question 及其 answer — 但不要在一個 round 裡 log 例行的 tool-call 雜訊；一個 round 涵蓋一個連貫的 conversation 單元。

## Code convention — 當該 round 牽涉到寫 js code 時遵守

**Tooling**

- 最新的 Node.js。Tests 透過內建的 `node:test` module。以 `require()` 載入 modules — 絕不用 `import`/ESM syntax。

- Package management 僅用 pnpm（`pnpm add`、`pnpm install`、`pnpm run ...`）— 絕不用 npm 或 yarn。

- 偏好 CLI program 而非 MCP server，前者對 agents 而言更彈性，可用於為 combined tasks 撰寫 script，或透過 Bash tool 使用它。

**Style**

- 僅用 Functional programming：由 plain functions 組合而成。不用 classes、不用 `this`、不用任何形式的 OOP patterns。

- 不用 TypeScript — 一律 plain JavaScript。

- 每一個 identifier 皆用 `snake_case`：variables、functions，以及在可行處的 file names。

- 讓 functions 小而 single-responsibility，並藉由組合它們來 build features，而非寫一個包辦一切的 function。

- 主動抗拒 over-engineering。伸手拿能解決眼前實際問題的最簡單結構 — 而非一個去預想沒有人要求過的需求的結構。三行相似的程式碼勝過過早的 abstraction。

- 使用標準的 file structure，例如 `src/`、`lib/`、`bin/`、`tests/`、`docs/`…等。

**Commits**

- 若 cwd 不是 git repo，跳過 committing，且完全不要提及它。

- 在每一個有意義的 logical unit of work 之後 commit — 一個可運作的 feature slice、一個 fix、一個 passing test — 附上清楚、具體的訊息。自動這麼做，不先詢問確認：這是專門針對 quickdev sessions 的常駐指示，凌駕於平常「commit 前先確認」的謹慎。把不相關的變更分成各自的 commits，而非把它們併在一起。

## Documentation convention — 當該 round 牽涉到寫 markdown 時遵守

適用於此 skill 觸及的每一個 `.md` 檔案（`devlog.md`、`codewalk.md`、`usage.md`、`README.md`，以及任何雜項 doc）：

- 絕不使用 table 語法。若你需要 tabular 資訊，改以 nested list 表達。

- 為每個項目加上 legend 前綴：

	* `-` — 一般項目（default）

	* `*` — highlight 或強調的項目

	* `+` — TODO、question，或任何需要注意的東西。謹慎使用。

	* `→`、`←`、`↑`、`↓` — 表示強調或與另一項目的關係。

	* 你可視情況使用其它 legends，但絕不用 emoticons。

- 在每一行之後（包含每一個 list item）留一個空行。絕不把兩行內容直接相鄰擺放。

- prose 段落中不要以 `\n` 或 `\r` 做 hard-wrapping，除非它們是展示確切內容的 code blocks。

正確留白的範例：

```
+ first item

+ second item

# Heading

This paragraph is written as one continuous line with no manual breaks in the middle of it, and it just keeps going until it reaches its natural end, relying entirely on the editor to soft-wrap it for display.

```

## Rules — 以 lazy 方式保持 living docs 最新

不要為了顯得周全而搭建空的 stub 檔案 — 只在真的有屬於它的內容時，才建立或更新一份 doc。所有檔案都放在 `docs/` 內（若不存在則建立它）。

- **devlog.md** — 例外：它從第一次 quickdev invocation 起就立即開始（見上方 Top rules）。它是 conversation 的 single source of truth；絕不在 chat 裡重述或 summary 其內容。

- **codewalk.md** — 一旦有了實際的 architecture、module layout，或某個未來的 coding agent 為了安全地接手專案而需要被解釋的 non-obvious technique，就建立或更新它。為 agent 而寫，而非為人：東西放在哪、為什麼那樣結構、已知的 gotchas、如何跑 tests，以及有什麼是不能隨意更動的。

- **usage.md** — 一旦有了值得記錄、面向使用者的 feature、command 或 config option，就建立或更新它。在最上方保持一份滾動的「Feature list」，依 version 或 date 分組，好讓人能看出什麼是何時出現的。其下的一切都是 how-to：commands、configuration、examples。

- **README.md** — 預設「第一眼看到」的檔案。寧可讓它保持精簡或空白，也不要替它憑空發明內容；只在真的有標準的 project info 可放時才填它（專案是什麼、如何安裝與執行）。

- **misc docs** — 若 user 要你把一份 plan 或 spec 存到某個特定命名的檔案（例如 `v2-plan.md`），就建立該檔案並把內容直接放進去，而非塞進 devlog.md（但務必提及每個檔案的建立）。

## Rules — language

你所撰寫的一切 — devlog.md 的 Ans sections、codewalk.md、usage.md、README.md、commit messages、code comments — 一律以 English 撰寫，不論 user 的 request 是什麼語言，除非 user 明確要求不同的 output language。唯一的例外是 devlog.md 中逐字的 Ask 引用：那是 user 的直接引用，以其所用的任何語言原封不動地複製，不是你在撰寫的東西。
