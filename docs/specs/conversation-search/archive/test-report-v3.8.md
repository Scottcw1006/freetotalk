# 驗證結果總覽

**pass** —— `spec-tasks.md` 任務 1–14 的每一條驗收、`spec-architecture.md` §A–§G 的每一條、以及 `spec.md` §1–§8 列出的邊界情況全部逐條核對過，沒有任何一條判 fail；三條依 spec 自己的但書記為「本環境無法觀察／無法執行」（任務 9 驗收 12、任務 12 驗收 5、以及 §1.8 串流那一半的即時觀察），依條文規定不判 fail。

驗證方式：JVM 單元測試實跑（48 + 32 條全綠）＋ 五次突變驗證（在 scratchpad 的複本上做，專案原始碼全程唯讀）＋ Pixel 9 實機操作（含中文輸入法手動打字、旋轉、顯示大小、行程回收、停止生成與時間戳存檔判讀）。

**本輪另有四點要回給 PM 的條文品質問題**（都不影響上面的 pass，見最後一節）：任務 10 驗收 6 的可判定性、「未被本次改動觸及」判定基準第 1 項的基準日在跨輪情境下已失效、任務 8 驗收 14 的「路徑邊界」在本實作上其實會影響結論、以及任務 12 驗收 2 指名的操作（貼上）與指名的字串在本環境只能部分重現。

---

# 逐項驗收結果

## 前置：實機建置證明（§F.6 / 任務 9 驗收 14，先做）

- 裝置上原本安裝的建置時間：`lastUpdateTime=2026-09-09 17:17:14`。
- 原始碼最後一次變更：`app/src/main/java/com/example/demo/chat/Conversation.kt` = `2026-09-09 17:09:34`（其次是 `ConversationSearchTest.kt` = `17:08:50`）。
- 我沒有沿用它。**本輪重新 `./gradlew installDebug`**，新的 `lastUpdateTime=2026-09-09 22:09:39`，晚於最後一次原始碼變更 5 小時。安裝前後 app 私有資料雜湊完全相同（重新安裝不清資料）。
- **所有實機判讀都在 22:09:39 之後進行。**

---

## 任務 1：對話存取的分層抽出

- **驗收 1**（狀態層不再直接持有以檔案為底的存取物）：**pass**。`ChatViewModel` 只 `import`／建構 `ConversationRepository` 與 `SessionPreferences`；全專案 `ConversationStore` 的唯一建構點在 `ConversationRepository` 內。
- **驗收 2**（下層檔案讀寫程式碼未被本次改動觸及，依三項判定）：**pass**。
  1. 時間：`data/ConversationStore.kt` mtime `2026-08-27 16:18:17`，早於基準 2026-09-02。
  2. 內容：檔內找不到搜尋的整形／折疊／比對／查詢欄位規則（`flattenToLine`／`normalizeForSearch`／`withoutLeadingSpace`／`withoutTrailingSpace`／`searchConversations`／`SearchUiState` 一個都沒有）。
  3. 行為：既有單元測試全綠；條文另外點名的兩個既有行為在實機上都成立（空白對話存檔＝刪檔、壞檔容錯，見驗收 6）。
- **驗收 3**（對外只有四件事）：**pass**。`all()` / `find(id)` / `save()` / `delete(id)`，沒有查詢、過濾、Flow、快取欄位。
- **驗收 4**（游標仍由狀態層直接持有）：**pass**。`SessionPreferences` 由 `ChatViewModel` 直接持有，沒有被包進 repository。
- **驗收 5**（非全域單例、沒有全域取得點）：**pass**。`class ConversationRepository(context)`，無 companion / 無 `get()`，唯一實例是 `ChatViewModel` 的欄位。（對照組：`LlmEngine.get(application)` 才是單例，那是刻意的。）
- **驗收 6**（既有行為逐項不變，實機）：**pass**，逐項如下。
  - 冷啟動續接（檔案存在）：`last_thread_id` 指到磁碟上的對話 → 重啟後停在該對話。
  - 冷啟動續接（檔案不存在）：把當前對話換成一段從未存檔的新對話後 force-stop 再啟動 → 落在一段全新的空白對話，且沿用記住的人格與模型。
  - 空白對話不進歷史 / 存空白對話＝刪檔：把一個「只含助理訊息」的對話開啟後切換人格 → 該檔在磁碟上被刪除（22 → 21 個檔），這正是 §E.7 第 1 條。
  - 歷史排序、開啟／刪除／送出後更新：全部照常（刪除以抽屜上的刪除鈕實測，檔案數 22 → 21）。
  - 壞檔容錯：推入一個內容為 `{ this is not valid json at all ]]` 的 `.json` 後重啟 → 歷史仍完整；以同一個查詢比對，命中的對話段數與放入壞檔前完全相同（`12 段對話・33 則訊息`）。
- **驗收 7**（「依 id 找一段對話」收斂為一處）：**pass**。全 main 原始碼只有一處 `firstOrNull { it.id == ... }`，在 `ConversationRepository.find`。
- **驗收 8**（既有測試檔未被本次改動觸及、測試全綠）：**pass**。範圍依 v3.8 的界線＝本功能開工時就存在的那幾個測試檔：`TextRepairTest.kt`(08-27 21:01:57)、`ModelMarkdownTest.kt`(08-27 21:16:49)、`DegenerationTest.kt`(08-27 18:18:22)，三者 mtime 都早於 2026-09-02；內容裡沒有任何搜尋相關符號；`clean testDebugUnitTest` 全綠（TextRepair 8 / ModelMarkdown 9 / Degeneration 5，failures=errors=0）。目錄裡另有 `ReplyEndingTest.kt`（09-08，別的輪次新增）與 `ConversationSearchTest.kt`（本功能自己的），依 v3.8 的界線不在本條範圍內。
- **驗收 9**（這一層的說明把定位寫對了）：**pass**。`ConversationRepository` 的 KDoc 明寫「That layering is the whole reason this class exists. It buys no speed and no new capability… Anyone tempted to justify it by some feature it enables should stop, because there isn't one.」並且有一整段「There is deliberately no search or query method」加上理由（它只知道磁碟上有什麼，看不到未存檔的開啟中對話與串流中的回覆）。冷讀後「可不可以在這裡加一個搜尋方法」有唯一答案且是不行。
- **驗收 10**（依 id 取一筆不得把 id 當檔名信任）：**pass**。`find(id) = all().firstOrNull { it.id == id }` —— 定義上就是「取全部再挑一筆」，不以 id 組路徑；兩者結果必然相同（含「兩邊都找不到」）。（`ConversationStore.fileFor(id)` 只用於 save/delete，不在讀取路徑上。）

## 任務 2：比對與片段的純邏輯

- **驗收 1**（無 Android / Compose / 持久化相依）：**pass**。`ConversationSearch.kt` 完全沒有 import，只用到 `java.lang.Character`。實證：本輪把它連同 `Conversation.kt`／`ModelSpec.kt`／`Persona.kt` 複製到 scratchpad，用純 JVM Kotlin 編譯器編起來並跑完整套測試（48 條）—— 若有 Android 相依這件事做不到。
- **驗收 2**（整形後為空的查詢回空，含 U+3000 / U+00A0）：**pass**，測試 `an empty query is not a search` 的案例逐碼位確認為 `""`、`"   "`、`"\n\t"`、U+3000、U+00A0。
- **驗收 3**（分組新→舊、組內原序）：**pass**，`conversations come back newest first and hits stay in the order they were said`。
- **驗收 4**（最多 3 則、總數為真實值）：**pass**，`a conversation shows at most three hits but reports the true total`（3 / 5）。
- **驗收 5**（唯一判準是至少一則命中、不得有空分組）：**pass**，`a conversation with no matching message is left out entirely` 同時斷言 `results.none { it.hits.isEmpty() }`。
- **驗收 6**（標示落在片段內、遞增、不重疊）：**pass**，`highlights stay inside the snippet and never overlap`。
- **驗收 7**（型別公開、整形與折疊可直接呼叫）：**pass**，測試直接 import 並呼叫 `flattenToLine()` / `normalizeForSearch()`。
- **驗收 8**（三者共用同一份整形；空白定義只有一處，含欄位規則這第四個使用點）：**pass**。全 main 原始碼裡定義空白集合的地方只有 `ConversationSearch.kt` 的 `isFlattenableSpace()`（Z 類三種 + U+0009–U+000D）；四個使用點 `flattenToLine`、`withoutLeadingSpace`、`withoutTrailingSpace`、以及經由 `flattenToLine` 的 `SearchUiState.isActive` 全部指向它。其他檔案裡的 `trim()`／`isBlank()`／`\s` 都屬於文字修復、Markdown 解析、送出前的 prompt 整理，不是第二份搜尋空白定義。
- **驗收 9**（整形符合 §2.1–§2.3 的規範集合）：**pass**，掃描式測試 `every unicode separator is collapsed`（BMP 65536 碼位、字面形式 `"a"+c+"b" == "a b"`、違反數 0）＋ `zero-width characters are not whitespace`（U+200B/C/D、U+FEFF 與 ZWJ 組合 emoji 通過整形後完全不變）。
- **驗收 10**（整形後的字串不得被寫回查詢欄位）：**pass**。`onSearchQueryChange` 只套 `withoutLeadingSpace()`；實機逐碼位確認欄位可以是 `qasum   qasum`（中間三個空白原樣保留）與 `ab  `（尾端兩個空白保留）。
- **驗收 11**（空查詢判斷用整形，不是內建 blank）：**pass**，`SearchUiState.isActive = query.flattenToLine().isNotEmpty()`；測試 `isActive agrees with flattening about what counts as empty` 另做 BMP 掃描，不存在「畫面說在搜尋而整形後為空」的字元。
- **驗收 12**（§C.7 的分工說明寫在折疊那一步）：**pass**。`normalizeForSearch` 的 KDoc 明寫「The full-width space U+3000 is deliberately not part of it: whitespace of every kind is settled earlier, by `flattenToLine`, and handling it in both places would mean two answers to the same question.」
- **驗收 13**（交出去的命中自足、四樣都在）：**pass**，`a hit carries the message itself, not just its text` 斷言拿得到 `message.author` 與 `message.createdAt`、片段、標示、以及 `matchedMessageCount`。

## 任務 3：比對語意的單元測試（26 條）

全部 **pass**。逐條對到的測試方法（判斷依據是條文指名的測資與預期輸出，我逐條讀過斷言內容，不是看方法名）：

| 條 | 對應測試與我核對的內容 |
|---|---|
| 1 | `a message the user wrote is found` / `a message the assistant wrote is found`，各自斷言命中訊息的作者 |
| 2 | `matching ignores case`（`hello` 與 `HELLO` 各一次） |
| 3 | `matching ignores full-width and half-width`（`ABC`→`ＡＢＣ`、`ｈｅｌｌｏ`→`Hello`，兩個方向） |
| 4 | `traditional and simplified are not folded together`（「台」不命中「臺北」，且「臺」必須命中 —— 有正向對照） |
| 5 | `surrounding whitespace in the query is ignored`，同時斷言 `bare.size == 1`（不得為空） |
| 6 | `an empty query is not a search`，五個案例逐碼位確認含 U+3000 與 U+00A0 |
| 7 | `a query that matches nothing returns nothing` |
| 8 | `normalising never changes the length of the text`，直接呼叫折疊步驟並對含 `İ` 的字串斷言長度不變 |
| 9 | `a highlight points at the matched text even when the message had newlines`，取出片段的標示區間恰為「北京」 |
| 10 | `every match inside the window is highlighted`（2 段，各自取出來都是「北京」） |
| 11 | `a hit deep inside a long message is cropped with a leading ellipsis`，斷言 `snippet.length == 62`（60 + 兩個省略號）與標示內容 |
| 12 | `a conversation shows at most three hits but reports the true total` |
| 13 | `the persona opener can be searched`，斷言作者為 Assistant 且 id 等於第 0 則 |
| 14 | `a thread holding nothing but its opener still matches`，顯示 1／總數 1，且不命中時完全不出現 |
| 15 | `a shared opener matches in every thread that has it`，三段全部出現 |
| 16 | `cropping never splits a surrogate pair`，逐字元檢查代理對成對 |
| 17 | 三段都在：下限＝`every unicode separator is collapsed`（BMP 掃描、字面 `"a b"`、違反數 0）；上限＝`zero-width characters are not whitespace`（含 👨‍👩‍👧 通過整形後完全不變）；收合與去除＝`a run of mixed whitespace becomes exactly one space` |
| 18 | `a message written with a full-width space is found with a plain one` |
| 19 | 四個案例都走 `searchConversations` 入口：`a query written with a full-width space finds a plain message`（訊息 `北京\n天氣` × 查詢含 U+3000）、`a run of spaces in the query matches a single space`（三個半形空白）、`a message written with a non-breaking space is found with a plain one`（訊息端逐碼位確認為 U+00A0）、`a query of nothing but whitespace is not a search`（只有 U+3000 → 空） |
| 20 | `a highlight is as long as the flattened query, not what was typed`，標示長度 5、取出來為「北京 天氣」 |
| 21 | `a query with a space inside is one string, not two words to find separately`，命中「我在北京 天氣很好」、不命中「北京很大，天氣很好」 |
| 22 | `isActive agrees with flattening about what counts as empty`，含 BMP 掃描、違反數 0 |
| 23 | `a hit shorter than the window keeps its lead-in`（L=59：`…`+20 甲+40 乙+`…`，且斷言引子恰 20 個字元）、`a hit that exactly fills the window has no lead-in`（L=60：`…`+60 乙+`…`、片段不含「甲」、標示起點 1 長度 60、且**明文斷言與 L=59 的片段不同**）、`a hit longer than the window is clamped to what is shown`（L=61 與 L=60 的片段與標示完全相同）。三個案例的預期值與 §4.2 的三條可判定條文逐字對得上 |
| 24 | `persona and model names are not searched`，人格顯示名「老師」、模型顯示名「Qwen2.5 0.5B」、查「老師」「Qwen」「qwen」皆空，**且**「想不通」必須命中（正向對照在） |
| 25 | `ellipses appear only where text was actually cut`，同一個方法裡同時驗短訊息不得有「…」與長訊息必須有「…」 |
| 26 | `searching finds message text rather than the derived title`：斷言該對話的 `title == "還沒說話"`、查「還沒說話」為空、**且**查開場白裡的「有事沒事」必須非空。**我是照條文指名的這兩句可判定判的，不是照測試方法名字。** 另做突變驗證（讓標題參與比對）→ 這支測試變紅 |

**執行結果**：`./gradlew clean testDebugUnitTest` → `ConversationSearchTest` 48 tests / 0 failures / 0 errors。

## 任務 4：「所有對話」的定義與搜尋子狀態

- **驗收 1**：**pass**。`ChatUiState(conversation = …)` 不指定 `search` 即可建構（`search: SearchUiState = SearchUiState()`），且預設 `query=""` → `isActive=false`。
- **驗收 2**：**pass**，`allThreads contains the open thread even when it is blank`（斷言 `["blank","older"]`，含空白對話且依 updatedAt 新→舊）。
- **驗收 3**：**pass**，`allThreads keeps the in-memory copy when a thread arrives twice`（同 id 只出現一次、留下訊息較多那份）。
- **驗收 4**（說明文字兩件事都寫）：**pass**。`ChatUiState.allThreads` 的 KDoc 同時寫了「今天為什麼不會重複」（refreshHistory 會濾掉開啟中那段，而那只因為兩次狀態更新的先後順序才成立）與「重複時為什麼取記憶體那份」（磁碟永遠不比記憶體新，記憶體那份可能含未存檔訊息或串流中的回覆）。
- **驗收 5**（本功能開工時就存在的那幾個測試檔仍全數通過且未被改動）：**pass**，同任務 1 驗收 8。
- **驗收 6**（§3.5 第二條可判定：歷史那份時間較新時仍取記憶體那份）：**pass**。條文指名的測資與預期輸出逐項核對：測試 `the in-memory copy wins even when the saved one looks newer` 的三段資料為記憶體 `same`／updatedAt 100／兩則會命中、歷史同 id `same`／updatedAt 900／一則、另一段 `other`／updatedAt 500／一則；走 `searchConversations(…, state.allThreads)` 入口；斷言分組 id 清單**恰為** `listOf("other","same")`（排序位置那一半在裡面）與 `results.last().matchedMessageCount == 2`。
  - **我另外自己做了突變驗證**（沒有照單全收任何人的回報）：把 `allThreads` 從「先去重再排序」改成條文點名的「先排序再去重」→ 48 條裡**恰好只有這一條變紅**。實作端 `Conversation.kt` 的順序確實是 `.distinctBy { it.id }` 在 `.sortedByDescending { it.updatedAt }` 之前。
- **驗收 7**（搜尋狀態聚成一組）：**pass**。`ChatUiState` 只因搜尋多一個欄位 `search: SearchUiState`；查詢字串、結果、以及「算不算在搜尋」（`isActive`）三樣都在 `SearchUiState` 裡；`ChatUiState` 上沒有第二個搜尋相關欄位（逐欄位讀過：conversation / history / availableModels / status / isReplying / search）。

## 任務 5：狀態層的搜尋入口與衍生結果

- **驗收 1**（結構：任何改變開啟中對話或歷史的地方都不需要補一行更新結果）：**pass**。結果在 `uiState` 送出的路上算：`_uiState.map { it.withSearchResults() }`。`send`／串流的 `updateMessage`／`refreshHistory`／`deleteThread`／`switchPersona`／`switchModel`／`openThread`／`stop` 全部只改 `_uiState`，沒有任何一處補呼叫搜尋。
- **驗收 2**（盡力而為：串流中命中數自行增加）：**部分觀察到，不 fail**。條文列的「可接受的間接佐證」完整取得：送出一則訊息後立刻進入搜尋，查該訊息裡的字 → 命中（`1 段對話・1 則訊息`），而同一時刻 `grep` 整個對話目錄**找不到任何檔案含該字串**（尚未落盤）。串流回覆的部分：搜尋畫面停留期間，同一段對話的命中則數從只有使用者那則變成含助理回覆那則（`1 段對話・2 則訊息`），但「數字跳動的那一瞬間」沒有攔到，依條文記為未能觀察。
- **驗收 3**（查詢整形後為空時提早返回）：**pass**。`searchConversations` 第一件事就是 `if (needle.isEmpty()) return emptyList()`。
- **驗收 4**（搜尋路徑上沒有任何持久化呼叫）：**pass**。`withSearchResults` → `searchConversations(query, allThreads)`；`allThreads` 只讀 `conversation` 與 `history` 兩個記憶體欄位；`ConversationSearch.kt` 沒有任何持久化相依。
- **驗收 5**（旋轉後仍在搜尋畫面、查詢與結果都在）：**pass**（實機：轉橫再轉直，查詢 `qas` 與 `3 段對話・9 則訊息` 都在，輸入框仍在最上方）。
- **驗收 6**（送出、停止生成、切換人格／模型等既有行為不受影響）：**pass**（實機全部實跑過，見任務 13）。
- **驗收 7**（切換人格後的空白對話可用開場白搜到）：**pass**。切到「孫子」產生空白對話後進搜尋、以中文輸入法打「阿公」→ 該段對話出現，而且排在最前（標頭時間 22:30）。
- **驗收 8**（搜尋全程不觸發推論、不產生檔案讀寫；含正向對照）：**pass**。進入搜尋 → 打到有結果 → 捲動 → 離開，全程：對話目錄 22 個檔案的 md5 逐檔比對完全一致；`logcat` 裡與推論相關的行以 pid 對照後**全部不屬於受測 app**（受測 app 的 pid 沒有任何一行）。正向對照：同一次操作裡查詢 `a` 確實產生 `12 段對話・33 則訊息` 的非空結果。

## 任務 6：抽屜入口與搜尋畫面外殼

- **驗收 1**（抽屜既有內容位置與行為完全不變；上半部固定、只有歷史列表可捲動）：**pass**。實機捲動歷史列表後，「模型」區塊三個模型、「並排比較所有模型」、「歷史紀錄」標題、「開始新的對話」、「搜尋對話內容」全部停在同一組 bounds，只有歷史列滾動。
- **驗收 2**（多一個搜尋入口列，不是輸入框；點擊後抽屜關閉並進入搜尋畫面）：**pass**。該列在 dump 中是 `TextView`（不是 `EditText`），點擊後抽屜關閉且進入搜尋畫面。
- **驗收 3**（進入時已聚焦、鍵盤自動彈出）：**pass**。進入後 `mInputShown=true`，且不必先點欄位就能直接輸入。
- **驗收 4**（輸入框固定頂端）：**pass**。連續捲動結果 8 次，`EditText` 的 bounds 始終為 `[148,178][1058,336]`。
- **驗收 5**（畫面返回鍵與系統返回鍵都回到聊天，不是退出 App）：**pass**。兩種都回到聊天畫面，`pidof` 前後相同（沒有退出）。
- **驗收 6**（搜尋畫面只收資料與回呼、不自行取得狀態來源）：**pass**。`SearchScreen(state, onQueryChange, onFocusLost, onOpen, onBack)`，沒有 `viewModel()` 預設參數，內部沒有任何狀態來源。
- **驗收 7**（比較畫面既有行為完全未變，含兩個刻意不修的缺陷）：**pass**。`CompareScreen` 內沒有 `BackHandler`，`comparing` 用的是 `remember`（不是 `rememberSaveable`）。實機證實：在比較畫面按系統返回 → 直接離開 App（焦點落到 launcher）；在比較畫面旋轉 → 被踢回聊天畫面。
- **驗收 8**（搜尋畫面旋轉仍在搜尋畫面、輸入框仍在頂端；橫向+鍵盤的可見量不在要求範圍內）：**pass**。橫向下輸入框仍在最上方、查詢與結果都在。橫向+鍵盤的可見量**依條文明文排除，不判**。
- **驗收 9**（即時、沒有送出鈕；正負兩半）：**pass**。正向：打「qa」後**不按任何鈕、不收鍵盤**，1.2 秒內出現 `4 段對話・10 則訊息`；接著只多打一個「s」，同樣不做其他動作，結果變成 `3 段對話・9 則訊息`（兩者命中則數不同，符合條文對測資的要求）。否定：畫面上只有返回鍵、輸入框、清除鈕，沒有任何「按了才會搜」的控制項；鍵盤上的動作鍵（✓）不按也已經是對的結果。
- **驗收 10**（不引入導覽框架）：**pass**。`gradle/libs.versions.toml` 與 `app/build.gradle.kts` 的相依清單裡沒有任何 navigation 套件；切換靠 `ChatApp` 裡的 `comparing` / `searching` 兩個旗標（`searching` 是 `rememberSaveable`）。正向對照＝驗收 8。

## 任務 7：結果列表

- **驗收 1**（單一捲動容器、佔滿輸入框以下）：**pass**。`LazyColumn(Modifier.fillMaxSize())`，摘要列、分組標頭、命中列、「還有 N 則符合」全都是它的 item，實機捲動時一起移動。
- **驗收 2**（命中字元視覺明顯不同、位置正確、顏色取自主題）：**pass**。`MaterialTheme.colorScheme.primary` + `FontWeight.Bold`；`SearchScreen.kt` 內沒有任何寫死色碼。實機截圖：命中字為主題綠、加粗。
- **驗收 3**（識別鍵含對話與訊息；多段對話的第 0 則同時命中不錯亂、不崩潰）：**pass**。key 為 `"$threadId#${it.message.id}"`。實機以「今天」查詢命中 10 段對話各自的第 0 則（開場白），捲完全部、無崩潰、無錯位。
- **驗收 4**（超過 3 則顯示 3 則 + 「還有 N 則符合」）：**pass**。實機：5 則命中的分組顯示 3 列 + 「還有 2 則符合」。
- **驗收 5**（命中列單行省略、不破版）：**pass**。`maxLines = 1, overflow = Ellipsis`；截圖確認長中文片段在行尾以「…」截斷、未破版。
- **驗收 6**（中文算繪，手動輸入法；測資含命中位置 20 字內的 emoji）：**pass**。測資為 30 個「測」+ 🌟 + 10 個「試」+「北京」+ 60 個「尾」；用裝置注音輸入法手打「北京」。實機片段逐字元計數為 8 個「測」+🌟+10 個「試」+「北京」+38 個「尾」+ 前後兩個「…」，UTF-16 合計 62（＝視窗 60 + 兩個省略號），emoji 完整未被拆、標示恰好蓋住「北京」。
- **驗收 7**（分組標頭不顯示對話標題；空白對話的標頭不出現「還沒說話」）：**pass**。實機：一段只有開場白＋助理訊息的空白對話（標題為「還沒說話」）在搜尋結果中的標頭是「📘 老師 Qwen2.5 0.5B 9/6」，沒有「還沒說話」。
- **驗收 8**（§7.1 主條文：直向、鍵盤彈出、不捲動，至少 1 標頭 + 4 命中列）：**pass**，而且餘裕很大 —— 1080x2424 / density 420 直向、鍵盤彈出、未捲動，同時完整看到摘要列 + **3 個分組標頭 + 7 個命中列** + 「還有 2 則符合」。
- **驗收 9**（§7.2 小螢幕：至少 1 標頭 + 2 命中列）：**pass**。`wm density 546`（系統顯示大小最大檔位量級）與更嚴苛的 `wm density 606`（≈285x640dp）兩種都測：鍵盤彈出、不捲動，都看得到 1 個標頭 + 3 個命中列 + 「還有 2 則符合」。收尾 `wm density reset`，確認回到 420 且無 override。
- **驗收 10**（畫面端沒有任何比對或整形程式碼）：**pass**。`SearchScreen.kt` 內找不到 `contains` / `lowercase` / `trim` / `Regex` / `indexOf` / `isBlank` / `isWhitespace` 任何一個。
- **驗收 11**（標頭四樣都在，且換對話會跟著變）：**pass**。實機每個分組標頭都同時有人格 emoji、人格名稱、模型名稱、時間（例：🧡 姊姊 Qwen2.5 0.5B 9/6 ／ 💗 情人 Gemma3 1B 9/6 ／ 🙌 朋友 SmolLM 135M 9/6 ／ 🧒 孫子 Gemma3 1B 8/28），四樣隨對話改變。

## 任務 8：空狀態、無結果、點擊與返回

- **驗收 1**（剛進入顯示引導文案，沒有「找不到…」、沒有空列表）：**pass**。
- **驗收 2**（不存在的字串只出現「找不到…」一句、無殘留舊結果）：**pass**（查 `kiwi` 時畫面上只剩那一句）。
- **驗收 3**（點進另一段對話：回聊天、內容是該對話、再次進入搜尋時輸入框是空的）：**pass**。
- **驗收 4**（點目前開啟對話的命中：回聊天、內容不變、不閃爍、不重載模型）：**pass**，且前後對話檔的 md5 完全一致。
- **驗收 5**（切人格產生空白對話後搜其開場白並點擊：回聊天、對話不變、不重載模型、不建立新對話、不把空白對話存檔）：**pass**。實機：切到「孫子」→ 搜「阿公」→ 該段（22:30、排最前）→ 點擊 → 回到聊天且仍是那段；對話檔數量與內容前後完全一致（沒有新增任何檔）；logcat 中受測 app 沒有任何推論/載入紀錄。
- **驗收 6**（命中列沒有刪除鈕）：**pass**。`SearchHitRow` 只有作者標籤與片段；抽屜歷史列才有「刪除這段對話」。
- **驗收 7**（摘要取未截斷總和：5/2/2 → 「3 段對話・9 則訊息」，畫面列 3+2+2）：**pass**，實機逐字確認。
- **驗收 8**（三種離開都清空查詢；旋轉不是離開）：**pass**。畫面返回鍵、系統返回鍵、點擊命中列三種離開後再進入搜尋，欄位逐碼位讀取皆為空並顯示引導文案；旋轉後查詢與結果都在。
- **驗收 9**（行程被殺後還原：停在搜尋畫面、查詢為空、顯示引導文案）：**pass**。`am kill` 後回到前景 → 搜尋畫面、欄位空、引導文案。此為可接受狀態。
- **驗收 10**（`"  kiwi  "` 的完整案例，逐碼位）：**pass**。欄位＝`k i w i U+0020 U+0020`（開頭兩個已移除、尾端兩個還在），文案逐碼位＝`找不到符合「kiwi  」的訊息`。失焦那一半見任務 12 驗收 5（本環境無法觀察）。
- **驗收 11**（結果區只有三種樣子，沒有第四種「載入中」；含正向對照）：**pass**。整輪實機操作（含冷啟動後立刻進搜尋、行程回收後還原、旋轉、換密度）中，結果區只出現過引導文案／結果列表／「找不到…」三種，從未出現進度指示、骨架列或「載入中」字樣。正向對照：同一次執行裡等歷史載入完成後再查同一個字，結果確實包含歷史裡的對話（`12 段對話・33 則訊息`）。
- **驗收 12**（查詢字串不得進跨行程保存的偏好；含正向對照）：**pass**。輸入查詢 `qas` 之後讀 `shared_prefs/session.xml`，內容只有 `last_thread_id` / `last_model` / `last_persona` 三個鍵，找不到 `qas`，鍵的組成沒有增加。正向對照：那組游標確實還在 —— force-stop 後冷啟動續接到同一段對話。
- **驗收 13**（點擊命中列的判準是 id，不是「這段對話是不是空白的」）：**pass**。在磁碟上放一個**只含助理訊息**的對話檔（被讀成空白對話、標題「還沒說話」，且確實進了歷史列表），同時開著另一段**不同 id** 的對話；搜該檔裡的字並點擊那一列 → **切換到了那段舊對話**，聊天畫面顯示的是它的內容，`session.xml` 的 `last_thread_id` 也換成了它。
- **驗收 14**（沒有為空白對話開的特例分支）：**pass**（結構與行為兩半都做）。
  - **結構**：那條路徑指得出來 —— `ChatScreen.ChatApp` 的 `onOpen` 回呼 → `ChatViewModel.openThread(id)` → `repository.find(id)` → `_uiState.update { it.copy(conversation = opened) }`。路徑上的條件只有：`conversationId != state.conversation.id`（畫面端，id 相等與否）、`if (id == _uiState.value.conversation.id) return`（狀態層，同一件事）、`repository.find(id) ?: return@launch`（取不到目標對話的空值防護）、以及同一個回呼裡順手做的 `clearSearch()` 內部那個 `if (query.isEmpty()) return`（與目標對話無關的旁支狀態清理）。**沒有任何一個是以「這段對話是不是空白的／訊息是不是空的／有沒有被存過檔」為判斷依據**，全部落在條文明列的「不是違反」那幾類。
  - **行為**：§6.2（另一段對話）、§6.3（目前這段）、§6.4（空白且未存檔那段）三種情境在實機各走一次，都回到聊天畫面並顯示對應的對話，三者都沒有在磁碟上新增或改寫任何對話檔（每次都做 md5 逐檔比對）。
  - **一個我看到、但判定不違反的東西，寫出來讓下一個人自己判**：若把路徑往下延伸到 `repository.save()` 之下的 `ConversationStore.save()`，那裡有一個 `if (conversation.isBlank) { delete(...) }` —— 字面上是以「這段對話是不是空白的」為依據的分支。我判它**不是本條要禁的特例**，理由有三：(a) 它是 §E.7 第 1 條**明文要求原封不動保留**的既有存檔語意；(b) §6.4 的實作註記與 §6.5 的說明都把「先存檔 → 對空白對話等於刪檔 → 找不到 → 提早返回」描述成這條路徑本來就會走的路，並只把下游那個空值防護拿出來說明，沒有把上游那個 isBlank 當成違反；(c) 它不改變「哪一段對話被開啟」，§6.1–§6.4 仍由同一條路徑處理。**但這代表條文那句「那條路徑的邊界畫到哪裡也不再影響結論」在這個實作上其實不成立** —— 已列進最後一節回給 PM。

## 任務 9：回歸驗證與中文算繪的手動清單

- **驗收 1**（單元測試全綠、本功能開工時就存在的那幾個測試檔未被觸及）：**pass**。三項判定逐項寫在任務 1 驗收 8。全套測試：ConversationSearch 48 / Degeneration 5 / ExampleUnit 1 / ModelMarkdown 9 / ReplyEnding 9 / TextRepair 8，failures=0、errors=0。
- **驗收 2**（文字修復未被觸及）：**pass**。時間：`chat/TextRepair.kt` mtime `2026-08-27 21:01:21`，早於基準。內容：檔內沒有搜尋的整形／折疊，也沒有被它們引用（`ConversationSearch.kt` 沒有 import 任何東西）。行為：`TextRepairTest` 8 條全綠。
- **驗收 3**（「送進模型的上下文」那道過濾未被觸及、也未被抽成與搜尋共用）：**pass**，但時間那一項要說明。
  1. 時間：那道過濾在 `ChatViewModel.send()` 裡（`.map { Turn(...) }.dropWhile { !it.fromUser }`），而 `ChatViewModel.kt` mtime `2026-09-08 17:32:20`，**晚於 2026-09-02 基準**。來歷說得出來：那是 `stop-generation` 那一輪的實作（該輪已完成並通過驗收，其變更說明與 spec 都在 `docs/specs/stop-generation/`），與本功能無關。
  2. 內容：`ChatViewModel.kt` 裡沒有任何搜尋的整形／折疊；它與搜尋的接觸面只有 `onSearchQueryChange` / `onSearchFocusLost` / `clearSearch` / `withSearchResults` 四個入口。
  3. 行為＋條文的後半句：指得出那道過濾在哪裡，而它**不是搜尋也在用的那一份** —— `searchConversations` 對訊息**完全不過濾**（`conversation.messages.mapNotNull { … }`），兩邊沒有共用函式。`ConversationSearch.kt` 的檔頭註解也明寫這是刻意的兩個問題。
- **驗收 4**（檔案讀寫層未被觸及；空白對話刪檔與壞檔容錯仍成立）：**pass**。三項同任務 1 驗收 2 與驗收 6，兩個既有行為都在實機上實測成立。
- **驗收 5**（抽屜除了多一個搜尋入口列之外完全沒變）：**pass**。抽屜結構為「模型（三個）→ 並排比較所有模型 → 歷史紀錄標題與說明 → 開始新的對話 → 搜尋對話內容 → 歷史列表」，與 `plan/architecture.html` 描述的既有結構一致，且「上半部固定、只有歷史列表可捲動」仍然成立（驗收 1 已實測）。
- **驗收 6**（不進入搜尋時既有流程與改動前一致，唯一刻意例外是 §8）：**pass**。送出、串流、停止、刪除、切換人格、切換模型、冷啟動續接、抽屜開關、比較畫面全部照常。時間戳的行為改變不在本條判，歸任務 13。
- **驗收 7**（對話標題與預覽未被觸及）：**pass**，時間那一項要說明。
  1. 時間：標題與預覽在 `chat/Conversation.kt`，mtime `2026-09-09 17:09:34`，**晚於 2026-09-02 基準，但早於本輪（v3.8）開工**（本輪 spec 五份檔案的 mtime 是同日 21:39–21:49）。來歷：`Conversation.kt` 同時也是本功能自己的檔案（`allThreads`、`search` 欄位、`hasSameContentAs` 都在裡面），17:08–17:09 那次改動與 `ConversationSearchTest.kt`（17:08:50）同一批，對得上 spec 自己記錄的「RD 已為任務 4 驗收 6 補上測試」。**這一項我只能說到「誰在什麼時候、為了什麼動的」，說不出「動了哪幾個字元」** —— 專案沒有版本控制，那份證據不存在（見最後一節）。
  2. 內容：`title` 與 `preview` 的實作是 `?.text?.replace('\n', ' ')?.take(26 / 40)`，沒有套用搜尋的整形／折疊。
  3. 行為（條文指名的那一半，直接驗得到）：同一則含 U+3000 的訊息，抽屜歷史列的預覽逐碼位仍是 `甲北京　天氣乙`（U+3000 在），而同一則訊息在搜尋片段裡顯示為 `甲北京 天氣乙`（已被收成一個半形空白）。兩邊確實不同一套規則，且標題／預覽那一套沒有被改成搜尋那一套。
- **驗收 8**（查一個命中多段開場白的詞）：**pass**。用裝置注音輸入法手打「今天」（`rupwu0` → 選候選字）→ `10 段對話・10 則訊息`，10 個分組各出現一則開場白命中，標示蓋在「今天」上、片段未破版、摘要數字與列出的分組數一致（10 = 10）。
- **驗收 9**（長中文訊息中段片語；測資選命中位置 20 字內含 emoji 的那則）：**pass**。手打「北京」（`1o3ru/` → 選「北京」）→ qa-long 那則的片段前後都有「…」，標示恰好蓋住「北京」兩個字不多不少，🌟 落在引子區內且未被拆開。片段長度逐字元核對為 62 個 UTF-16 單位（60 + 兩個省略號）。
- **驗收 10**（「臺」與「台」各打一次，結果不同）：**pass**。同一組注音鍵 `w96` 分別選第 1 個候選「台」與第 2 個候選「臺」：查「台」→ 只命中「這是台南的小吃」；查「臺」→ 只命中「我住在臺中很久了」。兩者結果不同。
- **驗收 11**（訊息端異常空白 + 使用者打得出來的普通空白 → 必須命中）：**pass**。前置測資由檔案推入（不經輸入法）：三則訊息分別在兩個中文詞之間放 U+3000、U+00A0、U+0020（逐碼位確認）。操作用注音輸入法打「北京」，**沒有組字時按一般半形空白鍵**（欄位逐碼位確認為 `北京 U+0020`），再打「天氣」（欄位＝`北京 U+0020 天氣`）。預期成立：三則**全部**命中（`1 段對話・3 則訊息`），標示恰好蓋住整個詞組**含中間那一個空白位置**（截圖：`甲`／`乙` 等前後字未被標示）。
- **驗收 12**（選作：把 U+3000 放進剪貼簿）：**本環境無法執行，依條文不 fail**。`adb shell cmd clipboard` 在這台裝置上不存在（回 `No shell command implementation.`）；走 UI 的路也不通 —— 這台裝置的輸入法在注音版面、英文版面與 `?123` 符號頁上都找不到 U+3000 的鍵，而受測 app 的訊息文字不可選取（沒有 `SelectionContainer`），所以沒有任何欄位能先產出 U+3000 再複製。查詢端 U+3000 的語意權威在任務 3 驗收 19，已通過。
- **驗收 13**（8–11 全部在鍵盤保持彈出的狀態下判讀，同時複驗 §7.1）：**pass**。上面 8–11 每一次判讀時 `mInputShown=true`；§7.1 的可見量同時成立（見任務 7 驗收 8）。
- **驗收 14**（實機判讀前先證明裝置上是本輪的建置）：**pass**。證據見本報告最上方「前置」那一節：重新安裝時間 `2026-09-09 22:09:39` vs 最後一次原始碼變更 `2026-09-09 17:09:34`，所有實機判讀都在其後。

## 任務 10：更新架構文件（`plan/architecture.html`，本輪只讀）

七條全部 **pass**。判定對的是文件現況（我自己 parse 了 `plan/architecture.html` 逐段讀），不是任何人的回報。

- **驗收 1**（「搜尋」那一節補上查詢欄位的空白規則）：**pass**。文件裡有一個名為「輸入框裡留下什麼，跟拿去比對的是兩回事」的段落，位置在「攤平是唯一准許改變長度的一步」之前，內容涵蓋三段規則（開頭空白一打就消失／尾端空白打字時留著、等到失去焦點才移除／中間的空白完全不動），並寫明「拿去比對的一律是整形過的文字，所以這兩次移除都不會改變找到什麼」與「判斷『這是不是空白』用的也是上面那一份定義，不是語言內建的 trim —— 內建那一份幾乎一樣，但會多吃掉 U+001C–U+001F」。
- **驗收 2**（規則對照表那一列補說明）：**pass**。「（只有空白）｜任何｜不算搜尋——停在未搜尋狀態，不是『找不到』。」那一列後面接著「這是比對層的契約：搜尋框本身打不出這種輸入（見下），但比對層仍然必須答對」。
- **驗收 3**（「不變條件」那一條仍在，三件事都要）：**pass**。現況全文為「對話的『最後更新時間』只跟著內容走。新增訊息、訊息文字改變、訊息被移除才算一次更新；切過去看一眼、切走都不算。否則『最近』會變成『最近被看過』，而歷史清單與搜尋結果的排序都靠這個時間。」
  (i) 句首原則與句尾理由與 v3.4 記錄的版本逐字相同；(ii) 「不算」那一側**不含「按下停止」**；(iii) 我全檔搜過「停止」的每一處（共 8 處），談的是「取消生成、跳過 Complete」「停止那一次的落盤必須另外安排」「三條各自留下自己的標記，停止留下的是『…（已停止）』」「SmolLM 不發停止 token」等等，**沒有任何一處宣稱按下停止不算一次更新**。
- **驗收 4**（模組地圖三列被逐項核對，核對日期與逐列結論出現在該節）：**pass**。`spec-tasks.md`／任務 10 的執行紀錄裡有那張三列表格與核對日期（2026-09-07，另有 2026-09-09 的複核）。我自己再對一次文件現況：`畫面｜ui/SearchScreen.kt｜搜尋輸入與命中列表。自己不持有狀態，也不決定什麼算命中`、`純邏輯｜chat/ConversationSearch.kt｜什麼算命中、片段怎麼裁、標示要落在哪幾格`、`資料｜data/ConversationRepository.kt｜對話從哪裡來、到哪裡去。聊天層唯一的入口` —— 三個路徑都存在，職責描述與現況相符。
- **驗收 5**（文件裡的時間概念仍只有一個）：**pass**。全檔搜「最後開啟時間」為 0 筆；沒有任何第二個時間概念的描述。
- **驗收 6**（本節列出實際更動的段落，且與本任務有關的每一處都列到了）：**pass**（判定理由見下）。該節列出五處，我逐處拿去 `plan/architecture.html` 對，**五處的文字都找得到**（第 1、2 處＝驗收 1、2 的內容；第 3 處是 v3.4 的歷史版本，已由第 5 處取代；第 4 處＝片段視窗那句的第二個分支；第 5 處＝「不算」那一側拿掉「按下停止」）。反方向我以本任務其餘六條驗收各自指名的內容為界去掃，文件裡與本任務有關的內容都在清單裡；該節另外明列了「不屬於本任務、刻意不列」的那幾處（`stop-generation` 那一輪自己的文件更新），我逐處讀過，確實不由任務 10 的任何一條要求，也不與任何一條牴觸。**這一條的可判定性有問題，見最後一節。**
- **驗收 7**（片段視窗那句補上第二個分支，且「命中長度剛好 60 時留幾個引子」有唯一答案 0）：**pass**。現況全文為「每則命中只顯示一個 60 個字的視窗：命中前面留 20 個字當引子。**命中本身有 60 個字（含）以上時裝不下引子，視窗就從命中的第一個字開始。**被裁掉的兩端各補一個刪節號。」有「（含）」之後沒有第二種讀法，答案唯一且為 0，與 §4.2 的界線（`≥ 60` 走命中起點分支）與實作的 `if (hitLength >= SNIPPET_WINDOW)` 三者對得起來。

## 任務 11：摘要的兩個數字維持現狀（無程式改動）

- **驗收 1**（比對層那兩個派生的量仍然存在、沒有被刪除）：**pass**。`SearchUiState.matchedConversationCount` 與 `matchedMessageTotal` 都在。
- **驗收 2**（實機 5/2/2 → 「3 段對話・9 則訊息」，畫面列 3+2+2）：**pass**，逐字確認。
- **驗收 3**（空查詢與無命中時摘要列不出現）：**pass**。空查詢時只有引導文案；查 `kiwi` 時只有「找不到…」一句，兩種情況都沒有摘要列。
- **驗收 4**（不得夾帶「把畫面改成呼叫那兩個量」的收斂改動）：**pass**。全專案 grep 那兩個屬性 → **只有定義處、零個呼叫端**；`SearchScreen` 仍是自己算 `results.size` 與 `results.sumOf { it.matchedMessageCount }`。裁決劃的範圍界線沒有被跨過。

## 任務 12：查詢欄位的空白規則

- **驗收 1**（空欄位按空白鍵 → 仍為空、顯示引導文案、不顯示「找不到」）：**pass**。連按三次空白鍵，欄位逐碼位讀取為空，畫面是引導文案。
- **驗收 2**（貼上「(3 個半形空白)北京」→ 欄位為「北京」）：**pass**（**測資做了一處替換，寫出來**）。這台裝置無法用 adb 設定剪貼簿，所以我走 UI：在一個會原樣保留前導空白的輸入欄位打出 `U+0020 U+0020 U+0020 q a s u m`（逐碼位確認），長按 → 全部選取 → 複製，再到搜尋欄位長按 → 貼上 → **欄位逐碼位為 `q a s u m`，三個前導空白被移除**。條文指名的字串是「北京」，我用 ASCII 字串代替（原因：注音輸入法在來源欄位打中文時會另有組字流程，會把「這一次貼上的到底是什麼」弄混）；**本條要守的性質是「貼上的前導空白必須被移除」，與那三個字元是什麼無關**，但這是一次替換，列在最後一節。
- **驗收 3**（欄位為「北京(2 個空白)」時刪掉「北京」→ 欄位為空、回到未搜尋狀態）：**pass**。造出欄位 `a b U+0020 U+0020`，把游標移到 `b` 之後、往前刪掉 `b` 與 `a`：刪到剩 `U+0020 U+0020` 的那一步，欄位**直接變成空**、畫面回到引導文案，**沒有**出現「找不到符合『  』的訊息」。
- **驗收 4**（打字中尾端空白必須留著）：**pass**。用注音輸入法打「北京」→ 按一次半形空白鍵 → 欄位逐碼位＝`5317 4eac 0020`；接著打「天氣」→ 欄位＝`5317 4eac 0020 5929 6c23`，且該查詢命中含「北京 天氣」的訊息（3 則，含 U+3000／U+00A0／U+0020 三種寫法）。另外也確認中間連續空白不被改寫：欄位可以是 `qasum U+0020 U+0020 U+0020 qasum`。
- **驗收 5**（失去焦點後尾端空白被移除、重新聚焦不得加回）：**本環境無法觀察，依條文不 fail**。在這台裝置的這個畫面上，除了離開畫面之外沒有辦法讓輸入框失去焦點：按鍵盤的 IME 動作鍵（✓）只收鍵盤、欄位內容不變（尾端兩個空白仍在）；點畫面上任何非互動區域也不會移走焦點；結果列的每一列點下去都會離開畫面。條文明文規定此時記「本環境無法觀察」，並要求下一條成立 —— 驗收 6 成立。
- **驗收 6**（規則能不經畫面直接驗證，且與 §2.1 同一份空白集合；兩個案例都要）：**pass**。測試 `the field uses our whitespace rule, not the built-in one` 逐碼位確認：`U+00A0` 開頭的輸入**被移除**（下限）、`U+001C` 開頭的輸入**不被移除**（與內建 trim 唯一相異的方向），並且同一支測試把「內建 trim 會移除 U+00A0、也會移除 U+001C」這兩個宣稱各寫成一個實際會執行的斷言。突變驗證：把 `withoutLeadingSpace`／`withoutTrailingSpace` 換成內建 `trimStart()`／`trimEnd()` → **恰好只有這一支測試變紅**。
- **驗收 7**（「找不到…」文案與欄位內容逐字元相同，`"  kiwi  "` 完整案例）：**pass**，同任務 8 驗收 10（逐碼位）。另有第二個案例：欄位 `qasum   qasum` 時文案逐碼位為 `找不到符合「qasum   qasum」的訊息`（三個空白一個不少）。
- **驗收 8**（開頭移除與失焦移除前後，結果列表與摘要數字完全相同）：**pass**（開頭移除那一半）。貼上 `   qasum` → 欄位變 `qasum` → `3 段對話・9 則訊息`；直接打 `qasum` → 同樣 `3 段對話・9 則訊息`、同樣三個分組同樣的命中列。另：欄位 `北京` 與 `北京 `（尾端一個空白）的結果與摘要完全相同（`3 段對話・5 則訊息`）。失焦那一半隨驗收 5 記為本環境無法觀察。
- **驗收 9**（比對層原有的空查詢契約測試仍存在且通過）：**pass**。`an empty query is not a search`（五案例含 U+3000、U+00A0）與 `a query of nothing but whitespace is not a search` 都在，沒有被刪。
- **驗收 10**（畫面端仍然沒有任何比對或整形程式碼）：**pass**。欄位規則在畫面端只是把使用者的輸入轉給 `viewModel::onSearchQueryChange`，規則本身在比對層。
- **驗收 11**（程式碼與測試註解中關於內建 trim 的敘述都與修正後的 §C.8 一致）：**pass**。原始碼裡唯一一處是 `withoutTrailingSpace` 的 KDoc：「Kotlin's trim follows `Char.isWhitespace`, which is very nearly our rule — **it covers every separator we do** — but it also strips U+001C–U+001F」；**沒有宣稱雙向差異，也沒有宣稱內建 trim 不會移除 U+00A0**。測試註解同樣寫「so it does strip U+00A0 — but it also strips the C0 separators」，而且每一句宣稱都配了一個實際會執行的斷言（`assertEquals("北京", " 北京".trim())`、`assertEquals("北京", "北京".trim())`）。

## 任務 13：對話「最後更新時間」的定義

- **驗收 1**（§8.3 排序場景全部成立）：**pass**。前置：A＝一段 updatedAt 較新的對話、B＝較舊，兩段都含共同的字，目前開著 A。操作：從抽屜開啟 B，再從抽屜開回 A。預期：進入搜尋查那個共同的字 → 分組順序與**兩個分組標頭上的時間文字**與操作前**逐行完全相同**（我把操作前後兩份 UI dump 直接 diff，零差異）；抽屜歷史列表的順序也不變（切走 A 之後 A 在抽屜裡仍顯示 9/6，沒有跳到現在）。
- **驗收 2**（§8.4 儲存體裡的時間不動）：**pass**。切換離開一段沒有內容改變的對話後，該對話存檔的 `updatedAt` 仍是原值（`1788700005000`），**整份 JSON parse 後與切換前的內容完全相等**。（注意：檔案的 mtime 會變、位元組可能因重新序列化而不同 —— 這是我推進去的手寫 JSON 的關係；使用者原有的 14 個檔案在整輪操作後**位元組層面完全未變**，我逐檔 sha256 比對過。）
- **驗收 3**（§8.6，兩個預期都要判）：**pass**。前置：在回覆串流途中按下停止，且停止當下畫面上已經有文字（實際留下的是一段中文段落）。操作：停止後**等待 82 秒不做任何事**（用裝置端等待，全程沒有碰 app），然後從抽屜切換到另一段對話。
  - **預期一（文字）**：該對話存檔中那則被停止的回覆，文字結尾是中斷標記 —— 逐字元確認 `text.endswith("…（已停止）") == True`（本輪核對時 `stop-generation` 那一輪的現行定義就是這個字串，我也在 `plan/architecture.html` 裡確認它寫著「停止留下的是『…（已停止）』」）。**pass**
  - **預期二（時間）**：存檔中的 `updatedAt = 1788964426565`，切換的時刻（取裝置時鐘）＝ `1788964508650`，相差 **82.1 秒 ≥ 30 秒**。時間落在按下停止的當下，不是切換的時刻。**pass**
  - 相依已滿足：受測建置包含 `stop-generation` 的修正（停止確實留下部分文字加標記，而不是把整則覆寫成錯誤字串）。
- **驗收 4**（§8.5 不得修過頭）：**pass**。送出一則訊息並收到完整回覆後，該對話存檔的 `updatedAt = 1788964326570`，落在回覆完成的時刻附近（送出時刻約 1788964323，裝置時鐘），且該對話在抽屜歷史與搜尋結果中都排到最前。刪除、切換人格、切換模型、開新對話等既有流程全部實測照常。
- **驗收 5**（§8.7 新對話的起點）：**pass**。切換人格產生的空白對話在搜尋結果中排在**最前**（標頭時間為當下的 22:30，其他分組是 9/6、8/28 等），任務 5 驗收 7 與 §1.8 不受影響。
- **驗收 6**（「什麼算一次更新」在程式碼中只有一個定義處，所有寫回對話的路徑都經過它）：**pass**。全 main 原始碼只有兩處寫入 `updatedAt`：`newThread()`（建立當下，那是 §8.7 的起點）與 `updateThread()`（唯一的定義處，以 `updated.hasSameContentAs(state.conversation)` 決定要不要蓋時間）。所有會改到訊息集合或訊息文字的路徑（`send`、串流的 `updateMessage`、`settle`、`stop`）都走 `updateThread`；其餘把 `conversation` 換掉的地方（啟動還原、`openThread`、`deleteThread` 後開新、切換人格／模型）換的是「另一段對話」，不是同一段的內容變換，因此不該也沒有蓋時間。
- **驗收 7**（存檔時機未被改動）：**pass**。切換對話、切換人格／模型、送出後收尾仍各自照原本的時機存檔（實測：切走一段對話時該對話確實被寫回，只是內容相同）。條文指名的可判定：切走一段**有未存內容**的對話（停止後留下的部分回覆）再切回來，內容仍在（抽屜預覽與聊天畫面都看得到那段被中斷的文字）。
- **驗收 8**（回歸：送出、串流、停止、刪除、切換人格／模型、冷啟動續接全部照常）：**pass**，六項在實機各做一次，全部正常。

## 任務 14：一次性補掃的收尾（13 條新條文各自有東西在驗）

- **驗收 1**（13 條每一條都指得出怎麼被驗的）：**pass**，逐條如下。

| 新條文 | 怎麼被驗的 |
|---|---|
| 任務 1 驗收 9 | 可冷讀的程式碼位置：`data/ConversationRepository.kt` 的類別 KDoc |
| 任務 1 驗收 10 | 可冷讀的程式碼位置：`ConversationRepository.find(id)` |
| 任務 2 驗收 13 | 測試方法 `a hit carries the message itself, not just its text` |
| 任務 3 驗收 25 | 測試方法 `ellipses appear only where text was actually cut` |
| 任務 4 驗收 7 | 可冷讀的程式碼位置：`ChatUiState` 的欄位清單與 `SearchUiState` |
| 任務 5 驗收 8 | 實機步驟：進出搜尋前後對話檔 md5 逐檔比對 ＋ 以 pid 過濾的 logcat ＋ 非空結果的正向對照 |
| 任務 6 驗收 9 | 實機步驟：打「qa」等 1.2 秒讀摘要 → 只多打「s」再讀摘要，判讀點是輸入停止後 |
| 任務 6 驗收 10 | 可冷讀的程式碼位置：`gradle/libs.versions.toml` + `ChatApp` 的 `searching` 旗標 |
| 任務 7 驗收 11 | 實機步驟：讀每個分組標頭的四個欄位，並跨人格／模型不同的分組比較 |
| 任務 8 驗收 11 | 實機步驟：全輪操作中列舉結果區出現過的樣子 ＋ 歷史載入後的正向對照 |
| 任務 8 驗收 12 | 實機步驟：輸入查詢後讀 `shared_prefs/session.xml` ＋ 冷啟動續接的正向對照 |
| 任務 8 驗收 13 | 實機步驟：推入只含助理訊息的對話檔 → 搜其內容 → 點擊 → 讀切換後的畫面與偏好 |
| 任務 8 驗收 14 | 結構＝可冷讀的路徑（`ChatApp.onOpen` → `openThread` → `find` → `update`）；行為＝三種情境的實機步驟＋檔案雜湊 |

- **驗收 2**（可自動化的那幾條確實有測試在執行，且會因為對應的錯誤實作而失敗）：**pass**。我沒有只用嘴巴說「什麼樣的實作會讓它變紅」，而是**實際跑了五次突變**（在 scratchpad 的複本上改，專案原始碼全程唯讀、mtime 未變）：

| 突變 | 變紅的測試 |
|---|---|
| §4.2 界線 `hitLength >= 60` → `> 60`（歷史上那個 off-by-one） | `a hit that exactly fills the window has no lead-in`、`a hit longer than the window is clamped to what is shown`（其餘 46 條全綠） |
| `allThreads` 改成「先排序再去重」 | 只有 `the in-memory copy wins even when the saved one looks newer` |
| 欄位規則改用內建 `trimStart()` / `trimEnd()` | 只有 `the field uses our whitespace rule, not the built-in one` |
| 無條件在片段前後補「…」 | `ellipses appear only where text was actually cut`、`a short message is shown whole, with no ellipsis` 等 4 條 |
| 讓衍生的對話標題參與比對 | `searching finds message text rather than the derived title` 等 4 條 |

  未突變的基準版本：48 條全綠；每次突變後都還原並重跑確認回到全綠。
- **驗收 3**（配了正向對照的那幾條，正負兩半都要被執行到）：**pass**。任務 3 驗收 25（短訊息不得有「…」＋長訊息必須有「…」，同一個測試方法裡兩半都在）、任務 5 驗收 8（無讀寫＋確實有非空結果）、任務 6 驗收 9（結果自己出現＋沒有搜尋鈕）、任務 6 驗收 10（沒有導覽框架＋旋轉仍在搜尋畫面）、任務 7 驗收 11（標頭四樣都在＋換對話會變）、任務 8 驗收 11（沒有載入中畫面＋結果確實含歷史對話）、任務 8 驗收 12（找不到查詢字串＋游標確實還在）—— 七條的兩半我都實際執行了。
- **驗收 4**（這 13 條不得要求任何行為改變；有變紅就回報不改條文）：**pass**。13 條沒有一條變紅，本輪沒有發現需要回報的既有缺陷。
- **驗收 5**（不改動任何規則，也不改動補掃之前既存的驗收條文的要求）：**pass**（這是對 spec 本身的核對）。本輪 spec 的三份受驗檔案裡，我沒有找到任何一條既有驗收的**要求**被放寬或改變 —— `changes-v3.8.md` 宣稱的三類改動（拿掉會腐爛的數量、改寫引用方式、補記判斷依據）我逐處對照 v3.7 的敘述讀過，每一處都只動措辭。

---

# 架構決策符合度

## §A 「什麼算命中」只有一個地方說了算

- **A.1**（集中在不依賴 UI 框架的純邏輯區塊，與既有純邏輯同層，不新增分層）：**符合**。`chat/ConversationSearch.kt` 與 `chat/TextRepair.kt`、`chat/Degeneration.kt`、`chat/ModelMarkdown.kt` 同一層同一個 package；沒有新增分層，也沒有另闢平行區塊。硬性性質（不得依賴 Android / Compose / 持久化）已用「單獨拿它到純 JVM 環境編譯並跑完整套測試」實證。
- **A.2**（畫面端只上色）：**符合**。`SearchScreen.kt` 內沒有任何子字串搜尋、大小寫或全形折疊、去空白、正規表示式。`withHighlights` 只把算好的區間 `addStyle` 上去，KDoc 也寫明「nothing here decides what matched」。
- **A.3**（截斷屬於純邏輯）：**符合**。`hits = matched.take(MAX_HITS_PER_CONVERSATION)` 在比對層，`matchedMessageCount = matched.size` 回報未截斷總數，單元測試可直接觀察到 3 / 5。
- **A.4**（交出去的命中自足，四樣）：**符合**。`MessageHit(message, snippet, highlights)` + `ConversationHits.matchedMessageCount`；訊息本身（不只文字）有測試釘住。
- **A.5**（整形與折疊可被單元測試直接呼叫並單獨斷言）：**符合**。兩者都是 `internal fun String.…`，測試直接 import 呼叫；§2.3 的掃描式斷言就是靠這個做到的。
- **A.6**（摘要的兩個數字知情保留兩份寫法，本輪不動 UI）：**符合**。比對層那兩個派生量存在且**零呼叫端**，畫面仍自己算 —— 正是裁決要的「就只是留著」。唯一必須成立的性質（畫面顯示的值等於 §3.4 的定義）在實機以 5/2/2 → 「3 段對話・9 則訊息」驗過。

## §B 搜尋看的是畫面上有什麼，不是磁碟上有什麼

- **B.1**（輸入是「所有使用者看得到的對話」，由狀態層算出）：**符合**。`ChatUiState.allThreads = (listOf(conversation) + history).distinctBy{id}.sortedByDescending{updatedAt}`。
- **B.2**（存取層不得有查詢能力；搜尋路徑上不得有持久化呼叫）：**符合**。repository 只有四個方法；搜尋路徑（`withSearchResults` → `searchConversations` → `allThreads`）不碰任何持久化。實機佐證：搜尋全程對話檔零變動、無推論紀錄；且剛送出、尚未落盤的訊息搜得到（走記憶體而非磁碟）。
- **B.3**（「所有對話」只有一個定義處，合併順序就是語意；去重在排序之前；並寫下「今天為什麼不會重複」）：**符合**。定義處唯一（`Conversation.kt` 的 `allThreads`）；`distinctBy` 在 `sortedByDescending` **之前**；KDoc 兩件事都寫了。突變驗證證實把兩步對調會被測試抓到。
- **B.4**（結果是衍生值，不是被寫進狀態的欄位）：**符合**。在 `_uiState.map { it.withSearchResults() }` 這條出口路上算；沒有任何狀態變動處補一行更新結果。
- **B.5**（搜尋狀態聚成一組）：**符合**。`ChatUiState` 只多一個 `search` 欄位，三樣東西都在 `SearchUiState` 底下；拔掉搜尋等於拔掉那一個欄位。
- **B.6**（「所有對話」的組裝留在狀態層）：**符合**。組裝在 `ChatUiState`，不在 repository；repository 沒有地方放這個組裝（只有四個方法）。

## §C 文字管線：一個座標系、一份空白定義

- **C.1**（步驟順序是規範的一部分）：**符合**。`hitOrNull` 內的順序是：`text.flattenToLine()` → `.normalizeForSearch()` → `matchRanges` → 依第一個命中裁視窗 → `snippet` 取自**整形後**的 `flat`、標示以 `shift` 換算到片段座標。查詢端同樣是 `query.flattenToLine().normalizeForSearch()`。
- **C.2**（折疊必須長度不變）：**符合**。`normalizeForSearch` 是逐字元 `append`，一進一出；沒有用 `String.lowercase()`（KDoc 明寫理由是 `İ`）；測試對含 `İ` 的字串斷言長度不變。
- **C.3**（查詢端與訊息端進同一個正規形式）：**符合**，且有四個端到端案例覆蓋（任務 3 驗收 19）。§2.8 沒有削弱它：欄位規則只動前導與尾端，中間的異常空白完全不動（實機以 `qasum   qasum` 逐碼位確認）。
- **C.4**（空白定義只有一處，四個使用點共用）：**符合**。唯一定義處 `isFlattenableSpace()`；四個使用點都指向它。採 Unicode 空白類（Z 三種 + U+0009–U+000D）而非字面清單。
- **C.5**（什麼時候該共用、什麼時候不該）：**符合**。必須共用的那組（查詢端／訊息端正規形式、空查詢判斷、欄位規則的空白集合）確實共用；不得共用的那組（搜尋的訊息取捨 vs 送進模型的上下文取捨）確實各自獨立 —— 搜尋完全不過濾，`send()` 的 `dropWhile { !it.fromUser }` 沒有被抽成共用過濾器，`ConversationSearch.kt` 的檔頭註解也寫明這是刻意的。§A.6 那個具名例外仍是例外。
- **C.6**（空查詢判斷用同一份整形，不用內建 blank）：**符合**。`isActive = query.flattenToLine().isNotEmpty()`，KDoc 明寫「rather than `String.isBlank`… sharing the function means they cannot [drift]」。§2.5 的 BMP 掃描斷言也還在。
- **C.7**（U+3000 歸整形不歸全形折疊，且必須寫在折疊那一步的說明裡）：**符合**，見任務 2 驗收 12。
- **C.8**（欄位規則與空白定義同源）：**符合**。`withoutLeadingSpace`／`withoutTrailingSpace` 用 `isFlattenableSpace()`，不用內建 trim；差分測試兩個方向都在；突變驗證證實改用內建 trim 會被抓到。

## §D 搜尋是獨立畫面，狀態留在聊天那一份

- **D.1**（輸入框必須位在其捲動容器頂端）：**符合**。輸入框在 `TopAppBar` 的 title 位置，結果列表是它下面唯一的捲動容器；實機捲動時輸入框 bounds 不動。§7 的可判定條文在實機上成立且餘裕很大。
- **D.2**（沿用既有的全螢幕子畫面切換結構，不引入導覽框架）：**符合**。相依清單裡沒有導覽框架；旗標是 `ChatApp` 裡的 `searching`（`rememberSaveable`）與 `comparing`（`remember`）。
- **D.3**（搜尋畫面不得自己持有狀態來源）：**符合**。
- **D.4**（「現在是不是在搜尋」要撐過設定變更、不必撐過行程死亡）：**符合**。`rememberSaveable` 撐過旋轉（實測）；行程被殺後查詢為空、落在搜尋畫面（實測），正是 §5.7 允許的狀態。
- **D.5**（與比較畫面的差異是刻意的）：**符合**。搜尋畫面有 `BackHandler` 且撐過旋轉；比較畫面兩者都沒有，實機證實它按返回會離開 App、旋轉會被踢回聊天 —— 兩邊差異與條文一致。

## §E 對話存取的分層

- **E.1**（定位是分層整理、要誠實寫在說明裡）：**符合**，見任務 1 驗收 9。
- **E.2**（不包「上次停在哪」那個游標）：**符合**。
- **E.3**（零狀態純轉發：不快取、不合併、不持有推送式資料流）：**符合**。四個方法都是一行轉發；KDoc 明寫「Deliberately kept free of state — no cache, no flow, no invalidation… If caching does arrive later, it arrives with tests; today there are none, because there is no logic to test.」—— 條文要求的那個綁定（沒有邏輯所以不需要測試／有了邏輯就欠一個測試）也寫進去了。
- **E.4**（不得有跨畫面共享的實例語意、不引入 DI 框架）：**符合**。
- **E.5**（對外只保留四件事、不得新增沒有呼叫端的方法）：**符合**。排序留在下層（`ConversationStore.loadAll` 裡的 `sortedByDescending`）。
- **E.6**（依 id 取一筆＝取全部再挑一筆）：**符合**。壞檔容錯實機驗過；id 不當檔名信任由 `find` 的實作直接看得出來。
- **E.7**（兩個必須原封不動保留的既有行為）：**符合**，兩者都在實機上實測成立（空白對話存檔＝刪檔：檔案確實被刪；壞檔容錯：歷史仍完整且命中數與放壞檔前相同）。

## §F 驗證責任分工

- **F.1**（比對語意的權威是 JVM 單元測試，不要求實機重驗）：**遵守**。我沒有因為「沒有實機證據」而 fail 任何一條比對語意條文；U+00A0 / U+001C / ZWJ / BMP 掃描這些都只在單元測試層判。
- **F.2**（實機負責算繪不負責算對；§8 的時間戳條文也歸實機）：**遵守**。實機判的是標示位置、CJK 與 emoji 版面、鍵盤彈出時的可見量、導覽與返回、列表 key 撞號，以及 §8 的搜尋順序／抽屜順序／標頭時間文字／存檔內容。
- **F.3**（實機條文不得預設輸入法能力；動作與情境做不出來時記「本環境無法觀察／無法執行」）：**遵守**。U+3000 進剪貼簿（任務 9 驗收 12）與輸入框失焦（任務 12 驗收 5）都照條文記為本環境做不到，沒有判 fail；相對應的不經過畫面的條文（任務 3 驗收 19、任務 12 驗收 6）我實際跑過且通過。§2.4 與 §3.5 那兩個在畫面上不可達的情境，對應的直接測試也都還在、沒有被刪。
- **F.4**（每一條可自動化的驗收都要有對應的測試）：**遵守並實證**。任務 3 的 26 條逐條指得出測試方法與它斷言的內容；任務 14 驗收 2 用五次突變證明其中最容易退化的幾條確實有鑑別力。
- **F.5**（規則 → 條文的反向核對，範圍限於本輪被改動的章節）：**遵守**。`changes-v3.8.md` 列出了本輪判定為「被改動」的八節（`spec.md` §1、§3、§8；`spec-architecture.md` §A、§C、§E、§F、§G）與明確宣告不需核對的章節，並附了對照表且**沒有把對照表放進 spec**。我逐條核過那張表指名的條文編號都存在且確實守著該規則，本輪 `＋` 為 0 條這個結論成立。
- **F.6**（實機判讀前先證明裝置上是本輪的建置）：**遵守**，證據見報告最上方。
- **F.7**（兩輪之間的衝突由使用者裁決）：條文自己明寫**沒有產品面的可觀察物、不另立驗收條文**，`changes-v3.8.md` 也寫明 QA 不必為它判 pass/fail。我依此**不判**。（附帶一提：本輪碰到的跨輪對象 —— `plan/architecture.html`、`ChatViewModel.kt` —— 兩邊說法對得起來，沒有衝突需要升級。）

## §G 最後更新時間是內容的性質

- **G.1**（只能有一個定義處）：**符合**，見任務 13 驗收 6。
- **G.2**（時間是內容的性質，不是使用者行為的紀錄）：**符合**。`updateThread` 以 `hasSameContentAs` 判斷，KDoc 明寫「Otherwise a thread you merely switched away from would climb to the top… and 'most recent' would come to mean 'most recently glanced at'」。實機 §8.3 場景驗過。
- **G.3**（只改「寫下去的時間值怎麼來」，不改「什麼時候寫檔」）：**符合**。切換對話時仍會存檔（實測檔案 mtime 有變、內容不變），送出後收尾仍在 `settle` 存檔；唯一被另一輪改到的存檔時機是「停止那一次從不寫變成寫」（`stop()` 裡另起一個 coroutine 存檔），與 §G.3 的現行結論一致。
- **G.4**（驗證層級的現實）：**遵守**。§8 的驗收全部以實機與存檔內容判定。

---

# 邊界情況檢查

| spec 列出的邊界情況 | 有沒有被處理 | 怎麼確認的 |
|---|---|---|
| §1.2 人格／模型／標題不參與比對（正向對照不得省略） | 是 | 測試 24、26 各自含否定與正向兩半；突變（讓標題參與比對）會被抓到 |
| §1.2 時間不參與比對 | 是（結構上不可能違反） | 標頭上的時間是畫面把毫秒算繪成文字之後才存在的字串，比對層拿不到；規則旁已明寫不另立條文 |
| §1.3 「北京 天氣」不得命中「北京很大，天氣很好」 | 是 | 測試 21；實機也複驗（查「北京 天氣」時，含「1. 北京 2. 拉萨」的那則不再命中） |
| §1.5 全形／半形兩個方向 | 是 | 測試 3 兩個方向各一 |
| §1.5 註記：U+3000 不屬於這條、歸 §2 | 是 | `normalizeForSearch` 的 KDoc 明寫；測試 17 上限那一段與測試 18/19 分別覆蓋 |
| §1.6 「台」不得命中「臺」 | 是 | 測試 4（含正向對照）；實機以注音輸入法各打一次，結果不同 |
| §1.7 允許 debounce，但不得「按了才搜」 | 是 | 判讀點取輸入停止 1.2 秒後；畫面上沒有任何按下才觸發搜尋的控制項 |
| §1.8 剛切人格、從未存檔的對話必須搜得到且排最前 | 是 | 實機：切「孫子」後查「阿公」，該段排第一 |
| §1.8 串流中的回覆（實機攔截困難，盡力而為） | 部分 | 間接佐證完整取得（尚未落盤的訊息搜得到）；跳動的瞬間未攔到，依條文記未能觀察 |
| §1.9 不得有第四種「載入中」樣子；且結果最終必須含歷史 | 是 | 全輪操作只出現三種樣子；正向對照取得 |
| §1.10 搜尋不觸發推論、不產生檔案讀寫 | 是 | 檔案 md5 逐檔比對 + 以 pid 過濾的 logcat |
| §2.1/§2.2 Cf（含 ZWJ）一律不算空白，組合 emoji 通過整形後完全不變 | 是 | 測試 `zero-width characters are not whitespace`（含 👨‍👩‍👧） |
| §2.3 掃描式斷言必須是 `整形("a"+c+"b") == "a b"` 這個字面形式 | 是 | 測試 17 就是這個字面形式，違反數 0 |
| §2.4 空查詢的五個必測案例（U+3000、U+00A0 不得省略） | 是 | 逐碼位確認五個案例都在 |
| §2.4/§3.5 畫面上不可達的情境，其直接測試不得被刪 | 是 | 兩支測試都在（`an empty query is not a search`、`the in-memory copy wins even when the saved one looks newer`） |
| §2.5 不存在「畫面說在搜尋、整形後為空」的字元 | 是 | BMP 掃描，違反數 0 |
| §2.7 前後空白不影響結果，且結果不得為空 | 是 | 測試 5 兩半都在 |
| §2.8(a) 空欄位按空白／貼上前導空白／游標移到最前按空白／刪到只剩空白 | 是 | 四個子案例實機各做一次（長按空白鍵在這台裝置上會叫出切換鍵盤選單，產不出連續空白，該子案例改以連按三次覆蓋） |
| §2.8(b) 打字中尾端空白必須留著（否則中間空白永遠打不出來） | 是 | 實機逐碼位：`北京 ` → `北京 天氣`，且該查詢確實命中 |
| §2.8(d) 中間空白不得被收合／替換 | 是 | 實機逐碼位：欄位可為 `qasum   qasum`（三個空白原樣） |
| §2.8 不變式：欄位內容永遠不以空白開頭 | 是 | 整輪操作中欄位從未以空白開頭；刪到只剩空白時直接變空 |
| §2.9 回顯逐字元相同（不是整形後的版本） | 是 | 兩個案例逐碼位：`kiwi  `、`qasum   qasum` |
| §2.10 兩次移除都不得改變結果 | 開頭那一半是 | 貼上前導空白前後、以及尾端有無空白，結果與摘要都相同；失焦那一半隨任務 12 驗收 5 記為本環境無法觀察 |
| §3.1 不得出現只有標頭、底下沒有命中列的分組 | 是 | 測試 `a conversation with no matching message is left out entirely` 明文斷言 |
| §3.3 截斷必須發生在比對層 | 是 | `take(3)` 在 `searchConversations`，單元測試直接觀察到 3 / 5 |
| §3.5 排序位置那一半（擋掉「先排序再去重」） | 是 | 測試斷言分組 id 清單恰為 `["other","same"]`；突變驗證證實 |
| §3.6 跨對話訊息編號會撞號 | 是 | key 含 threadId；實機以「今天」命中 10 段對話的第 0 則，捲完無崩潰無錯位 |
| §3.7 開場白造成的大量重複命中是正確行為，不得去重／降權 | 是 | 測試 15；實機「今天」「阿公」兩次查詢都出現多段幾乎一樣的片段，沒有任何去重 |
| §3.8 標頭四樣都在（正向）＋ 不得出現「還沒說話」（否定） | 是 | 實機兩半都驗 |
| §3.9 命中列不提供會改資料的操作 | 是 | 沒有刪除鈕（驗收 6）＋ 點擊前後檔案雜湊一致（驗收 5） |
| §4.2 命中長度＝60 這條界線本身 | 是 | L=59/60/61 三個案例，且 59 與 60 的預期片段明文不同；突變（`>=` → `>`）會被抓到 |
| §4.3 短訊息不得出現「…」 | 是 | 測試 25；突變（無條件補「…」）會被抓到 |
| §4.4 不得從 surrogate pair 中間切開 | 是 | 測試 16；實機 emoji 片段完整 |
| §4.6 視窗內每一個命中都要標示、跨界的夾到顯示範圍 | 是 | 測試 10 與 L=61 案例 |
| §4.7 標示長度等於整形後查詢的長度 | 是 | 測試 20（長度 5）；實機「北京 天氣」的標示恰好含中間那一格空白 |
| §4.8 含換行的訊息標示落在正確的字上 | 是 | 測試 9 |
| §4.9 命中位置 20 字以內必須含 emoji（否則 emoji 等於沒驗） | 是 | 測資特意如此構造，實機片段裡 🌟 落在引子區 |
| §5.2 抽屜其餘內容完全不變（結構，不含排序） | 是 | 實機捲動確認上半部固定 |
| §5.5 系統返回鍵不得退出 App | 是 | 實機：返回後 `pidof` 不變、回到聊天 |
| §5.6 旋轉不算離開 | 是 | 實機橫向／直向來回 |
| §5.7 查詢不得進跨行程偏好（含正向對照） | 是 | 讀 `session.xml`；冷啟動續接作為正向對照 |
| §6.1 判斷依據只能是 id（測資必須是「空白、但不是目前開啟那段」） | 是 | 實機推入只含助理訊息的對話檔，點擊後確實切過去 |
| §6.4 空白且未存檔那段：不得因此新增或寫入任何對話檔 | 是 | 實機檔案雜湊逐檔比對，零變動 |
| §6.5 不得為空白對話新增特例分支 | 是 | 路徑逐條件讀過，沒有踩到禁止項（存取層那個 isBlank 的判定理由已寫在任務 8 驗收 14） |
| §7.3 橫向 + 鍵盤的可見量不在要求範圍內 | 是（沒有被誤判） | 我沒有拿橫向的可見量去判 fail |
| §8.1 停止生成算一次更新（追加中斷標記那一項） | 是 | 任務 13 驗收 3 預期一 |
| §8.2 清單管動作本身、不管它順帶觸發的事 | 是 | 切走一段**沒有在串流**的對話 → 存檔內容完全相同；切走一段**正在串流**的（本輪是先手動停止再等待再切換）→ 文字留下中斷標記，時間停在停止當下而不是切換當下 |
| §8.4 建議判定方式：比對切換前後的存檔內容 | 是 | parse 後完全相等 |
| §8.6 預期一是新的、預期二單獨沒有鑑別力 | 是 | 兩個預期分別判，預期一逐字元比對中斷標記 |
| §8.7 新對話的起點（建立算它自己的第一次更新） | 是 | 實機新空白對話排最前 |

---

# 如果 fail：問題歸屬

本輪沒有 fail。以下四點是**條文品質問題，歸屬 PM**，不影響本輪的 pass，但下一輪最好處理掉，因為它們都屬於這份 spec 自己記過的失效型。

1. **任務 10 驗收 6 的後半句在這個專案裡判不出來（第 6 型：條文要求的證據不存在於產物裡）。** 它要求「該文件裡與本任務有關的**每一處新增／改寫**，本節都列到了」——「新增／改寫」是相對於某個基準的差異，而專案沒有版本控制、也沒有留下 `plan/architecture.html` 的基準版本，冷讀的 QA 拿不到那份證據。我這次是用「以本任務其餘六條驗收各自指名的內容為界去掃」這個替代判準判它 pass 的，但那是我自己畫的線，不是條文寫的。v3.7 已經為「一字未改」類條文定下三項判定基準，**同一型的這一條被漏掉了**。建議比照辦理，換一組拿得到的證據去問同一件事（例如改成「本節列出的每一段都在文件裡找得到，且本任務其餘六條驗收指名的每一處都在本節的清單裡」）。

2. **「未被本次改動觸及」判定基準第 1 項的基準日 2026-09-02 已經被跨輪改動洗掉，而它的補救條款同樣拿不到證據。** 任務 9 驗收 3 指名的過濾在 `ChatViewModel.kt`（mtime 2026-09-08，`stop-generation` 那一輪動的）、驗收 7 指名的標題與預覽在 `Conversation.kt`（mtime 2026-09-09 17:09，前一輪 RD 為任務 4 驗收 6 補測試那一批）。條文說「晚於基準不自動判 fail，但必須說明它為什麼被動過、**動了什麼**；說不出來就是 fail」——「為什麼被動過」說得出來，「動了什麼」在沒有版本控制的專案裡**永遠說不出來**。照字面，這兩條每一輪都會 fail；照精神（第 2、3 項成立、來歷說得出來）則 pass。我判 pass 並把兩項的來歷寫在報告裡，但這條線需要 PM 明寫。順帶一提：`Conversation.kt` 同時是**本功能自己的檔案**（`allThreads`、`search` 欄位、`hasSameContentAs` 都在裡面），拿「未被本次改動觸及」去套整個檔案本來就對不上，條文真正要守的只是那個檔案裡的 `title` / `preview` 兩個屬性。

3. **任務 8 驗收 14 結構那一半的「路徑邊界不影響結論」在本實作上不成立。** v3.7 的改寫理由說「改成只問禁止項之後，『那條路徑的邊界畫到哪裡』也不再影響結論」。實際上不是：把邊界延伸到 `repository.save()` 之下，`ConversationStore.save()` 裡有一個 `if (conversation.isBlank) { delete(...) }`，字面上就是禁止項點名的「這段對話是不是空白的」。它不該算違反（那是 §E.7 明文要求保留的既有存檔語意，而且不決定哪一段對話被開啟），但條文沒有把它排除掉，下一個冷讀的 QA 可能會判 fail。建議在禁止項旁補一句排除：「存取層依 §E.7 保留的『空白對話存檔＝刪檔』不算，它決定的是檔案要不要留，不是哪一段對話被開啟」。

4. **任務 12 驗收 2 指名的操作在本環境只能部分重現。** 條文寫「貼上『(3 個半形空白)北京』」，而這台裝置無法用 adb 設定剪貼簿，只能走 UI 複製；我因此把字串換成 ASCII（性質不變，結果一樣）。這不是條文錯，但它與任務 9 驗收 12（同樣卡在剪貼簿）是同一個環境限制，而 §F.3 只為「字元」與「動作」寫了處置，沒為「剪貼簿這個管道」寫。建議在該條旁補一句：來源字串不重要，重要的是前導空白，環境無法貼中文時可換等效字串並記錄。

---

# 驗證方式說明

**不是靜態閱讀，實際執行了。** 具體如下。

**建置與單元測試（主機）**
- `./gradlew clean testDebugUnitTest`，逐檔讀 `app/build/test-results/testDebugUnitTest/*.xml` 的屬性：ConversationSearch 48、Degeneration 5、ExampleUnit 1、ModelMarkdown 9、ReplyEnding 9、TextRepair 8，failures 與 errors 全部為 0。
- **五次突變驗證**：把 `ConversationSearch.kt` / `Conversation.kt` 等複製到 scratchpad，用 Gradle 快取裡的 Kotlin 編譯器單獨編譯與執行 JUnit，逐一注入錯誤實作並記錄哪幾支測試變紅（清單見任務 14 驗收 2）。**專案原始碼全程唯讀**，事後確認所有 `.kt` / `.html` 的 mtime 與開始前完全相同。

**實機（Pixel 9，1080x2424 / density 420，Android 16）**
- 先重新建置安裝並記下安裝時間（§F.6）。
- 動手前把 app 私有資料完整快照並記 sha256（14 個對話 JSON + `shared_prefs/session.xml`）。
- 推入 8 個自建的測試對話 JSON（前綴 `qa-`）＋ 1 個內容損毀的 JSON，用於覆蓋 5/2/2 摘要、只含助理訊息的空白對話、含 emoji 的長中文訊息、U+3000／U+00A0／U+0020 三種空白、臺/台 對照、壞檔容錯。
- 判讀畫面一律用 `uiautomator dump` 取節點與 bounds，欄位內容一律**逐碼位**讀取（不看截圖判斷空白）；鍵盤與候選列用截圖判讀。
- 中文全部用裝置上的注音輸入法**手動打字**（送按鍵序列 → 從候選列選字），不用 adb 送非 ASCII。
- 涵蓋：進入／離開（三種）、旋轉、行程回收與冷啟動、顯示大小 546 與 606、送出／串流／停止生成／等待 82 秒後切換、刪除對話、切換人格與模型、比較畫面的兩個既有缺陷、剪貼簿複製貼上。
- 檔案是否被動到一律用雜湊比對；是否跑推論一律用 pid 過濾 logcat。

**環境設定的原值與還原**
- `accelerometer_rotation` 原值 **1**、`user_rotation` 原值 **0** —— 兩者都在改動前讀出並記下，收尾已還原並確認。
- `wm density` 原值 **420 且無 override** —— 測完 `wm density reset`，確認回到 420。
- 輸入法版面進場時是「注音」，中途切到英文，收尾已切回注音。

**測試資料的清理與還原驗證**
- 刪除我推入的 9 個檔案，以及測試過程中 app 自己建立的 2 段新對話（一段以抽屜刪除鈕刪、一段直接刪檔）。
- 還原 `session.xml`，清掉 `/data/local/tmp` 下的暫存。
- **逐位元組驗證**：對話目錄的檔案清單與快照完全一致；14 個對話 JSON + `session.xml` 的 sha256 與 baseline **完全相同**；app 重新啟動後再驗一次，仍然完全相同。

---

# 本輪驗證範圍

**全驗。** `spec-tasks.md` 的任務 1–14 每一條驗收、`spec-architecture.md` §A–§G 每一條、`spec.md` §1–§8 列出的每個邊界情況，都在本輪重新判過，沒有沿用任何先前的結論（我沒有讀本目錄下先前的驗收報告，也沒有從 `changes-v3.8.md` 去推測哪些條目上一輪失敗過）。

`spec-decisions.md`（決策 E，1 項待決）與 `spec-appendix.md` 依指示不列為驗收對象；`spec-tasks.md`／「明確不做」列的項目不驗也不判 fail；§F.7 依它自己的條文與 `changes-v3.8.md` 的說明不判 pass/fail。

判為「本環境無法觀察／無法執行」而依條文不 fail 的有三項，已在對應條目寫明：任務 9 驗收 12、任務 12 驗收 5（連帶任務 8 驗收 10 與任務 12 驗收 8 的失焦那一半）、以及 §1.8／任務 5 驗收 2 串流跳動瞬間的即時觀察。

---

# 環境文件的處置

`docs/qa-environment.md` 本輪**先逐條驗證、再修正、再擴充**，最後仍只寫環境、不寫任何產品觀察或 pass/fail。

**驗過而且仍然成立的**：建置工具鏈與 JDK 路徑、測試結果 XML 的位置與 UP-TO-DATE 陷阱、實機連線與解析度／density 的問法、建置證明的兩種做法、`run-as` 快照與還原的整體流程、手寫 JSON 與 app 序列化 JSON 的位元組陷阱、UI dump 的切法與逐碼位讀欄位、dump 不含輸入法視窗、覆蓋式版面下鍵盤蓋住點擊區的坑與 `mInputShown` 的確認法、只有 BACK 收得掉鍵盤、可捲動清單保留捲動位置、Gboard 兩種版面與地球鍵、注音鍵帽上的英數標示、組字未提交的注意事項、`cmd clipboard` 不存在、`wm density` 與旋轉設定的改法與還原、`am kill` vs `force-stop`、頂層按返回會掉出 app、時差量測、以及第 11 節那整套「在 scratchpad 複本上跑改壞的純 Kotlin 邏輯」的流程（三個一定會踩到的 classpath 陷阱一字不差，照著做一次就成功）。

**確認失敗或會誤導、已當場改掉的**：
- 快照那一節原本把資料**子目錄名寫死**在指令裡。那是產品自己的事、會改，違反「記技術不記答案」。改成先 `ls -R files` / `ls shared_prefs` 把目錄樹當場問出來，再照 `find` 出來的清單逐檔快照與比對；套件名與 Activity 名也一併改成當場問。
- 判斷有沒有跑推論那一節原本用**關鍵字**過濾 logcat，而系統服務本來就會印相符的字樣，容易誤判。改成先 `pidof` 拿受測 app 的 pid、再用 pid 過濾；原本那條「看到相符的行先用 pid 確認」的提醒併進新做法。

**新增的（都附了當場可執行的確認方式）**：
- 逐碼位讀欄位的 helper **不要對 text 做 `strip()`**（會把最需要看的尾端空白洗掉）。
- 文字選取的浮動工具列不在 uiautomator dump 裡，只能截圖量座標。
- 注音版面下用 `input text` 送 ASCII 是**無聲失敗**（進組字、欄位完全不變），以及怎麼確認自己踩到了。
- 注音的聲調鍵與非字母韻母鍵也照鍵帽的英數標示送；**不要用空白當第一聲**（組字中的空白是選字）。
- 長按空白鍵會叫出切換鍵盤選單，產不出連續空白。
- 英文版面按空白鍵會提交自動更正的建議字；要放入精確字串（尤其含尾端空白）用單一次 `input text`。
- 鍵盤版面是跨畫面共用的狀態，收尾要切回進場時那一個。
- 新增一節「Compose 畫面的焦點」：IME 動作鍵只收鍵盤不清焦點、點空白區域也不清焦點，以及怎麼判斷焦點還在（送一個字元進去看欄位有沒有變）。
- 剪貼簿改用 UI 的完整步驟（長按 → 全部選取 → 複製 → 目標欄位長按 → 貼上），並註明打不出來的字元就進不了剪貼簿。
- 時間戳那一節補上「兩邊都用裝置時鐘就不必扣時差」與「要空等 N 秒用 `adb shell sleep N`」。
- 顯示大小那一節補上「等效 WxH dp 怎麼反算 density」，以及 `user_rotation` 也要先記原值（本輪兩個都記了）。
- 還原那一節補上「測試過程中 app 自己建的新檔要用 `comm` 挑出來刪掉」，以及「被 app 讀寫過的手寫測試檔不能再拿位元組雜湊當基準」。
- 第 12 節補上「先跑一次未改動的基準、每次突變後還原再做下一個」的流程建議，與「相依鏈要一起複製、讓編譯器告訴你缺什麼」。

**刪掉的越界內容**：沒有。上一輪留下的內容我逐句用「換一個產品還成立嗎」檢查過，沒有任何一句是產品觀察、預期文字或 pass/fail；唯一不通過的是上面那兩處**寫死的產品專屬名稱**（資料子目錄、推論引擎關鍵字），已改成當場問出來的技術，而不是刪掉。
