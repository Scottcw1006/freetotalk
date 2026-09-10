# 驗證結果總覽

**fail** —— 產品行為沒有找到任何缺陷（我執行得到的每一條行為條文都通過），但**有三條驗收條文本身判不出它宣稱要守的東西**：任務 3 驗收 16 的測試經突變證明**零鑑別力**（把 §4.4 的保護整個拿掉，它照樣全綠），任務 9 驗收 3、7 在現行「未被本次改動觸及」三項判定基準下**照字面就不可能成立**。三項都是條文層級的問題，打回 PM（其中一項另需 RD 補測試）。

---

# 逐項驗收結果

## 任務 1：對話存取的分層抽出
- **驗收 1（狀態層不再直接持有檔案存取物、所有持久化都走這一層）**：pass。`ChatViewModel` 只持有 `ConversationRepository` 與 `SessionPreferences`；`ConversationStore` 只在 `ConversationRepository` 內被建構，全專案沒有第二個建構點。
- **驗收 2（下層檔案讀寫未被本次改動觸及）**：pass（逐項寫出三項判定）。
  - 時間：`data/ConversationStore.kt` mtime 2026-08-27 16:18:17，**早於基準日 2026-09-02** ✅
  - 內容：檔內沒有整形／折疊／比對、沒有查詢字串、沒有欄位空白規則（全檔只有既有的 `isBlank` 判斷與 JSON 讀寫）✅
  - 行為：既有單元測試全綠；空白對話刪檔與壞檔容錯實機仍成立（見驗收 6）✅
- **驗收 3（對外只有四件事，沒有查詢／過濾／推送流／快取）**：pass。`all()` / `find(id)` / `save()` / `delete()`，無其他公開成員、無欄位快取。
- **驗收 4（游標仍由狀態層直接持有）**：pass。`SessionPreferences` 由 `ChatViewModel` 直接持有，沒有被包進 repository。
- **驗收 5（不是全域單例、沒有全域取得點）**：pass。`class ConversationRepository(context)`，無 companion、無全域取得點。
- **驗收 6（既有行為逐項不變，實機）**：pass。冷啟動續接（檔案存在：續接到指定對話；檔案不存在：以記住的人格／模型開一段空白對話，兩種都實測過）；空白對話不進歷史（切人格後原空白對話的檔案被刪除，19→18 個檔）；歷史排序新→舊；開啟／刪除／送出後更新皆正常（刪除後檔案消失且該列消失）；放一個內容損毀的 `.json` 後歷史仍完整顯示（加入前後列出的對話集合相同，App 未崩潰）。
- **驗收 7（「依 id 找一段對話」收斂為一處）**：pass。只有 `ConversationRepository.find()` 一處，`ChatViewModel` 不再自己找。
- **驗收 8（既有單元測試全綠，且既有測試檔未被觸及）**：pass。
  - 時間：`TextRepairTest.kt` 2026-08-27 21:01、`ModelMarkdownTest.kt` 2026-08-27 21:16、`DegenerationTest.kt` 2026-08-27 18:18，**三個都早於基準日** ✅
  - 內容：三檔內都找不到搜尋引入的東西 ✅
  - 行為：`./gradlew clean testDebugUnitTest` 全綠（80 個測試，0 失敗）✅
- **驗收 9（這一層的說明把定位寫對了）**：pass。KDoc 明寫「That layering is the whole reason this class exists. It buys no speed and no new capability…Anyone tempted to justify it by some feature it enables should stop, because there isn't one.」並另有一段寫「There is deliberately no search or query method」及其理由。讀完之後「可不可以在這裡加搜尋方法」有唯一答案且是不行。
- **驗收 10（`find` 不把 id 當檔名信任）**：pass。`find(id) = all().firstOrNull { it.id == id }`，不以 id 組路徑；與「取全部再挑一筆」定義上就是同一件事（含都找不到的情形）。

## 任務 2：比對與片段的純邏輯
- **驗收 1（無 Android / Compose / 持久化相依）**：pass。`ConversationSearch.kt` 沒有任何 import（只用到 `java.lang.Character`），並實際在沙箱中以純 JVM 編譯執行成功。
- **驗收 2（整形後為空的查詢回空，含 U+3000 / U+00A0）**：pass，測試 `an empty query is not a search`（案例含空字串、三個半形空白、`\n\t`、U+3000、U+00A0，逐碼位確認過）。
- **驗收 3（分組新→舊、組內原序）**：pass，測試 `conversations come back newest first and hits stay in the order they were said`。
- **驗收 4（最多 3 則、總數為實際命中則數）**：pass，測試 `a conversation shows at most three hits but reports the true total`（3 / 5）。
- **驗收 5（唯一判準是至少一則命中、不得有空分組）**：pass，測試 `a conversation with no matching message is left out entirely`（含 `results.none { it.hits.isEmpty() }`）。
- **驗收 6（標示落在片段內、遞增、不重疊）**：pass，測試 `highlights stay inside the snippet and never overlap`。
- **驗收 7（型別公開、整形與折疊可直接呼叫）**：pass。`Highlight` / `MessageHit` / `ConversationHits` / `SearchUiState` 皆為 public；`flattenToLine` / `normalizeForSearch` 為 internal 且測試直接呼叫。
- **驗收 8（三處共用同一份整形；空白定義只有一處，含欄位規則）**：pass。全專案只有 `isFlattenableSpace` 一處定義空白集合；查詢端、訊息端、`SearchUiState.isActive`、`withoutLeadingSpace` / `withoutTrailingSpace` 四個使用點都走它。掃過整個 main 目錄，其餘的 `trim()` / `isBlank()` 都在與搜尋無關的既有路徑（`TextRepair`、送出前的 prompt、比較畫面、`ReplyEnding`）。
- **驗收 9（整形符合 §2.1–§2.3 規範集合）**：pass，測試 `every unicode separator is collapsed`（BMP 65536 掃描，斷言字面為 `"a" + c + "b" == "a b"`，違反數 0）＋ `zero-width characters are not whitespace`。
- **驗收 10（整形後字串不得寫回欄位）**：pass。`onSearchQueryChange` 只套 `withoutLeadingSpace`，中間空白與尾端空白原樣保留（實機逐碼位確認「北京 天氣」與「qz」加兩個空白）。
- **驗收 11（空查詢用整形判斷）**：pass。`isActive = query.flattenToLine().isNotEmpty()`，測試 `isActive agrees with flattening about what counts as empty`（含 BMP 掃描，違反數 0）。
- **驗收 12（§C.7 的分工說明寫在折疊那一步）**：pass。`normalizeForSearch` 的 KDoc 明寫「The full-width space U+3000 is deliberately not part of it: whitespace of every kind is settled earlier, by [flattenToLine], and handling it in both places would mean two answers to the same question.」
- **驗收 13（跨層四樣都在）**：pass，測試 `a hit carries the message itself, not just its text`（斷言拿得到 author 與 createdAt、片段、標示、未截斷總數）。

## 任務 3：比對語意的單元測試
逐條核對測試檔 `ConversationSearchTest.kt`（48 個測試，全綠）。**除驗收 16 外全部 pass**，各條對應的測試方法我都讀過斷言內容（不是只看名字）：

1 ✅ `a message the user wrote is found` / `a message the assistant wrote is found`；2 ✅ `matching ignores case`；3 ✅ `matching ignores full-width and half-width`（兩個方向）；4 ✅ `traditional and simplified are not folded together`；5 ✅ `surrounding whitespace in the query is ignored`（含 `assertEquals(1, bare.size)` 這個「不得為空」的正向對照）；6 ✅ `an empty query is not a search`（五個案例逐碼位確認含 U+3000、U+00A0）；7 ✅ `a query that matches nothing returns nothing`；8 ✅ `normalising never changes the length of the text`（含 U+0130）；9 ✅ `a highlight points at the matched text even when the message had newlines`；10 ✅ `every match inside the window is highlighted`；11 ✅ `a hit deep inside a long message is cropped with a leading ellipsis`（第 100 字、長度 62、標示正確）；12 ✅ `a conversation shows at most three hits but reports the true total`；13 ✅ `the persona opener can be searched`（第 0 則、Assistant）；14 ✅ `a thread holding nothing but its opener still matches`（1 則 / 總數 1，不命中時完全不出現）；15 ✅ `a shared opener matches in every thread that has it`（三段全出現）；**16 ❌ 見下**；17 ✅ `every unicode separator is collapsed` + `zero-width characters are not whitespace` + `a run of mixed whitespace becomes exactly one space`（三段都在，掃描式斷言字面正確）；18 ✅ `a message written with a full-width space is found with a plain one`；19 ✅ 四個案例齊全（`a query written with a full-width space finds a plain message`、`a run of spaces in the query matches a single space`、`a message written with a non-breaking space is found with a plain one`、`a query of nothing but whitespace is not a search`），逐碼位確認過 U+3000 / U+00A0；20 ✅ `a highlight is as long as the flattened query, not what was typed`（長度 5、取出恰為「北京 天氣」）；21 ✅ `a query with a space inside is one string, not two words to find separately`（正反兩半）；22 ✅ `isActive agrees with flattening about what counts as empty`（含 BMP 掃描）；23 ✅ `a hit shorter than the window keeps its lead-in` / `a hit that exactly fills the window has no lead-in` / `a hit longer than the window is clamped to what is shown`（L=59/60/61 三案齊全，且 60 那一條明文 `assertNotEquals(hitForRunOfLength(59).snippet, hit.snippet)` 把界線釘住）；24 ✅ `persona and model names are not searched`（「老師」「Qwen」「qwen」三個否定 + `assertNotNull(searchConversations("想不通", …))` 正向）；25 ✅ `ellipses appear only where text was actually cut`（同一個方法裡兩個方向都驗）；26 ✅ `searching finds message text rather than the derived title`（斷言 title 確實是「還沒說話」、查它不命中、查開場白裡的字必須命中）。

- **驗收 16（片段裁切不會切斷 surrogate pair，§4.4）：fail（條文／測試層級，產品行為本身是對的）。**
  - 條文指名的測試 `cropping never splits a surrogate pair` **存在且通過**，但它**沒有鑑別力**：我把 `avoidSplittingPairAt` / `avoidSplittingPairBefore` 兩個保護整個拿掉（改成直接回傳 index），**48 個測試仍然全綠**。
  - 原因是測資形狀決定了它永遠碰不到邊界：測資是 101 個「測」＋「北京」＋50 個 emoji，emoji 從索引 103 開始成對排列，而視窗尾端固定落在「命中起點 + 40」，與 emoji 起點的距離恆為偶數 —— **這個形狀在任何參數下都切不到 pair 中間**；視窗起點「命中起點 − 20」又永遠落在 BMP 的「測」區間裡。
  - 我另寫了一支探針證明保護是真的有作用：訊息為 15 個 emoji ＋「測」＋「北京」＋60 個「尾」，命中在索引 31、視窗起點 11 恰好落在一個 pair 中間 —— **現行實作**產出的片段沒有孤兒代理對（刪節號後接一個完整的 emoji），**拿掉保護後**片段的第二個碼位是一個孤立的低位代理（孤兒數 1）。也就是說 §4.4 是可以被違反的，只是沒有任何測試會發現。
  - **這是「一條會通過的錯條文」（spec 自己記的第 1 型失效）**：打回 PM 補一條指定測資形狀的可判定（比照驗收 23 對 §4.2 的做法），並由 RD 補上那個案例。

## 任務 4：「所有對話」的定義與搜尋子狀態
- **驗收 1（不指定搜尋子狀態也能建構，預設非搜尋）**：pass。`ChatUiState(search: SearchUiState = SearchUiState())`，`SearchUiState()` 的 `isActive` 為 false。
- **驗收 2（「所有對話」必含開啟中那段含空白對話、排序新→舊）**：pass，測試 `allThreads contains the open thread even when it is blank`（斷言順序為 blank 在前、older 在後）。
- **驗收 3（合併：對 id 去重且開啟中那份優先）**：pass，測試 `allThreads keeps the in-memory copy when a thread arrives twice`（只出現一次、訊息數 2）。**本輪的隨機突變之一**：把合併順序改成歷史在前 → 這條當場變紅。
- **驗收 4（說明同時寫出「今天為什麼不會重複」與「重複時為什麼取記憶體那份」）**：pass。`ChatUiState.allThreads` 的 KDoc 兩件事都寫了（「refreshing history filters the open thread out — but that holds only because of the order two state updates happen in」＋「the right one to keep is the in-memory copy…The disk is never newer」）。
- **驗收 5（既有測試檔全數通過且未被改動）**：pass，同任務 1 驗收 8 的三項判定。
- **驗收 6（歷史那份時間較新時仍取記憶體那份，含排序位置）**：pass。測試 `the in-memory copy wins even when the saved one looks newer` 的測資與斷言與條文逐項相符：記憶體 `same`／時間 100／兩則、歷史同 id `same`／時間 900／一則、另一段 `other`／時間 500／一則；走搜尋入口，斷言分組 id 清單**恰為** `["other", "same"]`、`same` 那組命中數為 **2**。排序那一半確實在裡面。
- **驗收 7（搜尋狀態聚成一組，不是平鋪）**：pass（冷讀結構判定）。指得出那一組：`ChatUiState.search: SearchUiState`，查詢字串、結果、`isActive` 三樣都在 `SearchUiState` 底下；`ChatUiState` 上沒有第二個搜尋相關的欄位（其餘欄位為 conversation / history / availableModels / status / isReplying）。拔掉搜尋等於拔掉那一個欄位。

## 任務 5：狀態層的搜尋入口與衍生結果
- **驗收 1（結構：任何改動對話或歷史的地方都不需要補一行更新結果）**：pass。結果由 `uiState = _uiState.map { it.withSearchResults() }` 在送出去的路上算，`send` / `stop` / `updateMessage` / `refreshHistory` / `deleteThread` / `switchPersona` / `switchModel` / `openThread` 沒有任何一處呼叫搜尋。
- **驗收 2（盡力而為：串流中命中數自行增加）**：**部分未能觀察**。我沒能攔到「同一個查詢的命中數隨串流變大」的畫面（本機模型回覆在 10 秒內就完成，來不及在同一次串流中比較兩次）。**間接佐證取得**：送出後立刻進入搜尋查該訊息的標記字，畫面顯示「1 段對話・1 則訊息」，同一時刻對該對話存檔做關鍵字比對為 **0** —— 搜得到的是尚未落盤的訊息。依 §1.8 記「未能觀察」，不判 fail。
- **驗收 3（整形後為空提早返回）**：pass。`searchConversations` 第一件事就是 `if (needle.isEmpty()) return emptyList()`。
- **驗收 4（搜尋路徑上沒有持久化呼叫）**：pass。`withSearchResults` → `searchConversations(query, allThreads)`，全鏈路不碰 repository。
- **驗收 5（旋轉後仍在搜尋畫面，查詢與結果都在）**：pass（實機，橫向與轉回直向都驗）。
- **驗收 6（搜尋期間既有行為不受影響）**：pass。送出、串流、停止、切換人格、切換模型、刪除全部實測正常。
- **驗收 7（切人格後的空白對話可用開場白搜到）**：pass。切到「孫子」產生空白對話後查「阿公」，該段**排在第一**（時間 06:22，比所有歷史新）。
- **驗收 8（全程不觸發推論、不產生檔案讀寫）**：pass。清空 logcat →（進入搜尋 → 打字到有結果 → 離開）→ 以 `pidof` 過濾出的 31 行全部是 IME／視窗事件，推論相關關鍵字命中 **0** 行；狀態列全程維持「Qwen2.5 0.5B・離線」；20 個對話檔的 md5 **逐檔完全相同**。正向對照：該查詢確實產生了非空結果。

## 任務 6：抽屜入口與搜尋畫面外殼
- **驗收 1（抽屜既有內容位置與行為不變、上半部固定只有歷史可捲）**：pass。捲動歷史列表後「模型」「並排比較所有模型」「歷史紀錄」「開始新的對話」「搜尋對話內容」的 bounds 一格未動，只有歷史列在動。
- **驗收 2（多一個搜尋入口列，不是輸入框；點擊後關抽屜進搜尋）**：pass。它是一個 `TextButton`（圖示＋文字），不是 `EditText`；點下去抽屜關閉並進入搜尋畫面。
- **驗收 3（進入時已聚焦、鍵盤自動彈出）**：pass。進入後 `mInputShown=true`，且不需再點欄位就能直接輸入。
- **驗收 4（輸入框固定頂端，捲動結果時不被捲走）**：pass。捲動 11 組結果全程，欄位 bounds 恆為 `[148,174][1058,340]`。
- **驗收 5（畫面返回鍵與系統返回鍵都回到聊天，不是退出 App）**：pass（兩者都實測；系統返回鍵在鍵盤開著時第一次只收鍵盤，第二次回到聊天，焦點仍在受測 App）。
- **驗收 6（搜尋畫面只收資料與回呼）**：pass。`SearchScreen(state, onQueryChange, onFocusLost, onOpen, onBack)`，內部沒有 `viewModel()`、沒有任何狀態來源。
- **驗收 7（比較畫面既有行為完全未變）**：pass。旋轉時被踢回聊天 ✅；系統返回鍵沒有被接住、直接退出 App 到桌面 ✅ —— 兩個既有缺陷都還在，刻意不修。
- **驗收 8（搜尋畫面旋轉仍在搜尋、輸入框仍在頂端）**：pass。橫向下欄位 bounds `[321,68][2402,226]`（仍在最上方），查詢與結果都在。
- **驗收 9（即時、沒有送出鈕）**：pass。用裝置中文輸入法打「北」，**不按任何鈕、不收鍵盤**，1 秒內出現「3 段對話・5 則訊息」；再打「京」，同樣不做別的動作，結果換成「3 段對話・4 則訊息」（測資確實讓兩者則數不同）。否定那一半：畫面上只有「返回」與「清除搜尋」兩個控制項，沒有任何按下才會搜的東西；鍵盤的動作鍵不按，結果也已經是對的。
- **驗收 10（不引入導覽框架）**：pass。`app/build.gradle.kts` 與 `libs.versions.toml` 內沒有任何 navigation 相依；切換旗標指得出來：`ChatScreen.kt` 的 `var searching by rememberSaveable { mutableStateOf(false) }` 與 `var comparing by remember { … }`。正向對照由驗收 8 提供。

## 任務 7：結果列表
- **驗收 1（單一捲動容器，佔滿輸入框以下所有空間）**：pass。`LazyColumn(Modifier.fillMaxSize())` 放在 `Scaffold` content 的 `Box(fillMaxSize)` 內；實機捲動時只有這一個容器在動。
- **驗收 2（命中字視覺明顯不同、位置正確、顏色取自主題）**：pass。截圖上命中字為主題主色＋粗體，與周圍明顯不同；顏色來自 `MaterialTheme.colorScheme.primary`，程式碼中沒有寫死色碼。
- **驗收 3（識別鍵含對話與訊息，第 0 則同時命中不錯亂）**：pass。key 為「對話 id + # + 訊息 id」；查「今天」時 11 段對話的第 0 則同時命中，捲完全部結果無崩潰、無錯位。
- **驗收 4（超過 3 則顯示 3 則 + 還有 N 則符合）**：pass。第一組顯示 3 則 + 「還有 2 則符合」（5 − 3）。
- **驗收 5（命中列單行省略、不破版）**：pass。長中文訊息以 `maxLines=1` + `TextOverflow.Ellipsis` 呈現，含 emoji 與標點皆未破版。
- **驗收 6（中文算繪，手動輸入法）**：pass。用注音輸入法打「北京」，測資的命中位置前 1 個字元就是 emoji（在 20 字引子內、必定進視窗）；標示恰好蓋住「北京」兩個字不多不少，片段前後各有一個刪節號，標點與 emoji 正常。
- **驗收 7（標頭不顯示標題；空白對話不出現「還沒說話」）**：pass。只含助理訊息的空白對話（標題確實是「還沒說話」，抽屜列上看得到）在搜尋標頭上顯示的是「情人 + Qwen2.5 0.5B + 06:01」，沒有「還沒說話」。
- **驗收 8（§7.1 直向、鍵盤彈出、至少 1 標頭 + 4 命中列）**：pass。1080x2424 / density 420 直向，注音鍵盤彈出（較高的那一種）、不捲動：查「今天」可完整看到 **5 個標頭 + 5 個命中列**；查另一個查詢可完整看到 **3 個標頭 + 7 個命中列 + 1 行「還有 N 則符合」**。
- **驗收 9（§7.2 小螢幕，至少 1 標頭 + 2 命中列）**：pass。`wm density 606`（約 285x640dp，比條文要求的 360x640dp 更嚴苛），鍵盤彈出、不捲動：完整看到 **1 個標頭 + 3 個命中列**。
- **驗收 10（畫面端沒有比對或整形程式碼）**：pass。`SearchScreen.kt` 全檔沒有 `contains` / `indexOf` / `lowercase` / `trim` / 正規表示式；唯一的計算是摘要那兩個數字的加總（§A.2 界線明文允許）。
- **驗收 11（標頭四樣都在，換對話會跟著改）**：pass。每一組標頭都同時有人格 emoji、人格名稱、模型名稱、時間；查「今天」時三組分別是「姊姊 / Qwen2.5 0.5B」「老師 / Gemma3 1B」「姊姊 / SmolLM 135M」—— 人格與模型都跟著換。

## 任務 8：空狀態、無結果、點擊與返回
- **驗收 1（剛進入顯示引導文案，沒有找不到、沒有空列表）**：pass。只有「搜尋所有對話的訊息內容」一句。
- **驗收 2（不存在的字串只出現「找不到…」，無殘留舊結果）**：pass。只有一行「找不到符合…的訊息」，沒有摘要列、沒有殘留。
- **驗收 3（點進另一段對話：回聊天、內容是該對話、再次進入搜尋欄位是空的）**：pass。點另一段對話的命中列 → 聊天顯示該對話（老師 / Gemma3 1B 與它的內容）；再次進入搜尋，欄位為空、顯示引導文案。
- **驗收 4（點目前開啟對話的命中：回聊天、內容不變、不閃爍、不重載模型）**：pass。狀態列維持「Gemma3 1B・離線」，pid 過濾後推論相關記錄 0 行，全部對話檔 md5 不變。
- **驗收 5（空白對話的命中：不重載模型、不建新對話、不存檔）**：pass。切人格產生空白對話 → 查其開場白 → 點該筆 → 回到聊天、對話不變、狀態列不變、推論記錄 0 行、**對話檔數量與內容逐檔 md5 完全一致（19 個檔）**。
- **驗收 6（命中列沒有刪除鈕）**：pass。命中列的 UI tree 內沒有任何可點子節點或「刪除」content-desc（抽屜的歷史列才有）。
- **驗收 7（摘要取未截斷總和：5/2/2 → 「3 段對話・9 則訊息」，畫面列 3+2+2）**：pass，逐字相符。
- **驗收 8（三種離開都清空查詢；旋轉不是離開）**：pass。畫面返回鍵、系統返回鍵、點命中列三種離開後再次進入都是空欄位＋引導文案；旋轉前後查詢與結果都在。
- **驗收 9（行程被殺後還原：停在搜尋畫面、查詢為空、引導文案）**：pass。輸入查詢 → Home → `am kill` → 重啟：落在搜尋畫面、欄位空、顯示引導文案。此為可接受狀態。
- **驗收 10（前後各兩個空白的無命中字串，完整案例）**：pass，逐碼位判定。欄位讀出為 `['0x6b','0x69','0x77','0x69','0x20','0x20']`，即「kiwi」加兩個尾端空白；文案為「找不到符合「kiwi  」的訊息」。使欄位失焦（`keyevent 61` / TAB）後，欄位變成「kiwi」、文案同步變成「找不到符合「kiwi」的訊息」。
- **驗收 11（結果區只有三種樣子，沒有第四種「載入中」）**：pass。冷啟動後立刻進入搜尋、打字、清空、無結果、有結果各種狀態下，畫面上只出現過引導文案／結果列表／「找不到…」三者之一，沒有任何進度指示、骨架列或「載入中」字樣。正向對照：同一次執行裡查「今天」得到 11 段對話，其中 6 段來自歷史（磁碟）—— 歷史確實有被讀進來。
- **驗收 12（查詢字串不得進跨行程偏好）**：pass。輸入多個查詢之後 `shared_prefs/session.xml` 仍只有 `last_thread_id` / `last_model` / `last_persona` 三個鍵，全檔比對不到任何一個我用過的查詢字串。正向對照：那組游標確實還在（冷啟動續接到同一段對話）。
- **驗收 13（點擊判準是 id，不是「是不是空白對話」）**：pass。磁碟上放一個**只含助理訊息**的對話檔（被讀成空白對話並進入歷史），同時開著另一段**不同 id** 的對話；搜該舊檔裡的字並點那一列 → **確實切換到那段舊對話**，聊天畫面顯示的是它的內容。
- **驗收 14（三種點擊情境走完的行為；v3.10 已移除結構那一半，未驗）**：pass。§6.2、§6.3、§6.4 三種情境都回到聊天並顯示對應的對話；三者前後對話檔的**數量與逐檔 md5 都沒有變**（§6.2 那次被切走的對話存檔位元組完全相同）。結構那一半依 v3.10 不驗。

## 任務 9：回歸驗證與中文算繪
- **驗收 1（單元測試全綠，既有測試檔未被觸及）**：pass。三項判定同任務 1 驗收 8（時間三檔皆 2026-08-27、內容乾淨、`clean testDebugUnitTest` 80 測試全綠）。
- **驗收 2（文字修復未被觸及；搜尋的整形／折疊不得加進去）**：pass。時間：`chat/TextRepair.kt` 2026-08-27 21:01 早於基準 ✅；內容：檔內沒有 `flattenToLine` / `normalizeForSearch` / 查詢字串，也沒有被它們引用 ✅；行為：`TextRepairTest` 8 個測試全綠 ✅。
- **驗收 3（送進模型的上下文那道過濾未被觸及、未被抽成與搜尋共用）**：**fail（條文層級，不是實作問題）**。
  - **直接驗得到的那一半 pass**：那道過濾指得出來 —— `ChatViewModel.send()` 的 `.dropWhile { !it.fromUser }`；它**不是**搜尋在用的那一份（搜尋走 `conversation.messages` 全量，`ConversationSearch.kt` 的註解也明寫兩者刻意分開）。
  - **三項判定的第 1 項不成立**：該過濾所在的 `chat/ChatViewModel.kt` mtime 2026-09-08 17:32，晚於基準日 2026-09-02。補救條款要求「說明它為什麼被動過、動了什麼」：**「為什麼」答得出來**（任務 5 要求把搜尋入口 `onSearchQueryChange` / `onSearchFocusLost` / `clearSearch` / `withSearchResults` 放進這個檔，`stop-generation` 那一輪也動過同一個檔），**「動了什麼」在沒有版控的專案裡拿不到證據**，照條文字面「說不出來就是 fail」。
  - **第 2 項也不成立**：判定基準第 2 項要求「該檔裡找不到本功能引入的任何東西 —— 查詢字串、查詢欄位的空白規則一項都不得出現」，而 `ChatViewModel.kt` 依任務 5 與任務 12 的要求，**必須**持有查詢字串並在輸入路徑上套用欄位規則。
  - **這是條文構造的問題**：三項判定是**以整個檔案為單位**寫的，但這一條指名的是**檔案裡的一段程式碼**，而那個檔案是本功能依別的任務**被要求**去改的。照字面判，這一條**不可能通過**，且不論實作寫得多正確都一樣。打回 PM。
- **驗收 4（檔案讀寫層未被觸及；空白對話刪檔與壞檔容錯仍成立）**：pass。三項判定同任務 1 驗收 2；後半句實機成立（切人格時空白對話的檔案被刪除；放入損毀檔後歷史仍完整）。
- **驗收 5（抽屜除了多一個搜尋入口列之外完全沒變）**：pass（可判定的部分）。抽屜結構逐項核對：模型清單三列＋「使用中」標記、「並排比較所有模型」、「歷史紀錄」標題與副標、「開始新的對話」、歷史列（人格 emoji／名稱／模型／時間／標題／預覽／刪除鈕）全部在原位；多出來的只有「搜尋對話內容」一列。**誠實說明**：這一條沒有被列進「未被本次改動觸及」那七條，所以沒有三項判定可用，我能給的最強證據就是上面這份逐項清單。
- **驗收 6（不進入搜尋時既有流程與改動前一致，唯一例外是 §8）**：pass。送出、串流、停止（有文字／零文字兩種）、刪除、切換人格、切換模型、冷啟動續接（有檔／無檔兩種）全部照常。
- **驗收 7（對話標題與預覽未被觸及）**：**fail（條文層級，不是實作問題）**。
  - **直接驗得到的那一半 pass，而且驗得很乾淨**：同一則含 U+3000 的訊息，在抽屜歷史列的預覽上逐碼位讀出來仍是 `0x3000`，而同一則訊息的搜尋片段把它收成一個 `0x20` —— 標題與預覽確實只換掉換行字元（`replace('\n', ' ')`），沒有套用搜尋的整形。
  - **三項判定的第 1 項不成立**：`chat/Conversation.kt` mtime 2026-09-09 17:09:34，晚於基準日。「為什麼被動過」答得出來（`ChatUiState.allThreads` 與 `hasSameContentAs` 都是本功能引入的，就在這個檔），**「動了什麼」同樣拿不到證據**。
  - 第 2 項成立（該檔裡沒有整形／折疊／比對、沒有查詢字串、沒有欄位空白規則）；第 3 項成立。
  - 同樣是條文構造問題：這一條指名的是檔案裡的一段程式碼，而該檔案是本功能必須擴充的。打回 PM。
- **驗收 8（查詢會命中多段開場白的詞）**：pass。用注音輸入法打「今天」→「11 段對話・12 則訊息」，捲完全部結果共 **11 個分組、12 個命中列**，與摘要數字一致；標示都蓋在「今天」兩字上，片段未破版。
- **驗收 9（長中文訊息的中段片語）**：pass。查「北京」命中一則 84 個碼位的長訊息，片段為刪節號 + 原文第 9–68 碼位 + 刪節號（我用同一份整形規則算出預期值逐字比對，完全相符），標示恰好蓋住「北京」兩字不多不少；命中位置前 1 個字元是 emoji，在視窗內正常算繪。
- **驗收 10（「臺」與「台」各打一次，結果不同）**：pass。「台」→「找不到符合「台」的訊息」；「臺」→「1 段對話・1 則訊息」。
- **驗收 11（訊息端異常空白 + 使用者打的半形空白 → 必須命中）**：pass。測資由檔案推入：一則「北京」+ U+00A0 + 「天氣很好啊」、一則「北京」+ U+3000 + 「天氣如何呢」；操作時用注音輸入法打「北京」，**按一般空白鍵**（欄位逐碼位確認為 `0x5317 0x4eac 0x20`），再打「天氣」。預期成立：**兩則都命中**，標示連續蓋住整個「北京 天氣」（含中間那一個空白位置），片段裡那兩個異常空白都被收成 `0x20`。
- **驗收 12（選作：U+3000 進剪貼簿）**：**本環境無法執行**。`adb shell cmd clipboard` 仍回 `No shell command implementation.`（2026-09-10 重跑確認），而裝置的輸入法在注音／英文／符號三個版面都打不出 U+3000，所以走 UI 複製那條路也做不到第一步。查詢端 U+3000 的語意權威在任務 3 驗收 19，已通過。不判 fail。
- **驗收 13（8–11 全部在鍵盤保持彈出的狀態下判讀）**：pass。上述每一次判讀前後都查過 `mInputShown` 為 `true`，同時複驗 §7.1（見任務 7 驗收 8）。
- **驗收 14（實機判讀前先證明裝置上是本輪的建置）**：pass，證據如下：
  - `adb shell dumpsys package com.example.demo | grep lastUpdateTime` → **`lastUpdateTime=2026-09-10 05:53:14`**
  - 專案內最後一次原始碼變更：`app/src/main/java/com/example/demo/chat/Conversation.kt` **2026-09-09 17:09:34**（`app/src/**/*.kt` 全掃的最大值），`plan/architecture.html` 為 2026-09-08 18:49:48
  - 安裝時間晚於最後一次原始碼變更約 12.7 小時 ✅。驗收全程沒有重新建置或安裝，收尾時再查一次 `lastUpdateTime` 仍為同一個值。

## 任務 10：更新架構文件
（我自己讀 `plan/architecture.html` 冷讀判定，未採信 spec 裡登記的舊結論。）
- **驗收 1（「搜尋」那節補上查詢欄位的空白規則）**：pass。文件現況有「開頭的空白一打就消失」「尾端的空白相反，打字時一定要留著…它要等到輸入框失去焦點才移除。中間的空白完全不動。」「而拿去比對的一律是整形過的文字」三句，三件事都在。
- **驗收 2（「（只有空白）→ 不算搜尋」那一列補上比對層契約的說明）**：pass。該列現況為「（只有空白）｜任何｜不算搜尋——停在未搜尋狀態，不是「找不到」。這是比對層的契約：搜尋框本身打不出這種輸入（見下），但比對層仍然必須答對」。
- **驗收 3（「不變條件」那一條，三件事都要）**：pass。
  - (i) 現況全文：「對話的『最後更新時間』只跟著內容走。新增訊息、訊息文字改變、訊息被移除才算一次更新；切過去看一眼、切走都不算。否則『最近』會變成『最近被看過』，而歷史清單與搜尋結果的排序都靠這個時間。」句首原則與句尾理由與本檔登記的 v3.4 版本**逐字相同** ✅
  - (ii)「不算」那一側**不含「按下停止」** ✅
  - (iii) 全檔另外三處提到「停止」的文字講的是「取消生成、跳過 Complete」「與『停止』按鈕共用同一條路徑」「三個觸發條件」，**沒有任何一處宣稱按下停止不算一次更新** ✅
- **驗收 4（模組地圖三列的路徑與職責與現況相符）**：pass，逐列判兩件事。
  - 「畫面｜`ui/SearchScreen.kt`｜搜尋輸入與命中列表。自己不持有狀態，也不決定什麼算命中」→ 路徑存在 ✅；該檔無狀態來源、無比對邏輯 ✅
  - 「純邏輯｜`chat/ConversationSearch.kt`｜什麼算命中、片段怎麼裁、標示要落在哪幾格」→ 路徑存在 ✅；三件事都在該檔 ✅
  - 「資料｜`data/ConversationRepository.kt`｜對話從哪裡來、到哪裡去。聊天層唯一的入口」→ 路徑存在 ✅；`ConversationStore` 只被它建構，聊天層沒有第二個入口 ✅
- **驗收 5（文件裡的時間概念只有一個）**：pass。全檔搜不到「最後開啟時間」或任何第二個時間概念。
- **驗收 6**：已於 v3.9 移除，未驗。
- **驗收 7（片段視窗那一句讀完之後「命中長度剛好 60 時留幾個引子」有唯一答案且為 0）**：pass。現況：「每則命中只顯示一個 60 個字的視窗：命中前面留 20 個字當引子。**命中本身有 60 個字（含）以上時裝不下引子，視窗就從命中的第一個字開始。**被裁掉的兩端各補一個刪節號。」「（含）」讓 60 這個界線沒有第二種讀法。

## 任務 11：摘要的兩個數字維持現狀
- **驗收 1（比對層那兩個派生的量仍然存在）**：pass。`SearchUiState.matchedConversationCount` 與 `matchedMessageTotal` 都還在，且沒有被畫面使用。
- **驗收 2（實機 5/2/2 → 「3 段對話・9 則訊息」，畫面列 3+2+2）**：pass，逐字相符。
- **驗收 3（空查詢與無命中時摘要列不出現）**：pass。兩種狀態下畫面上都只有一行文字，沒有摘要列。
- **驗收 4（不得夾帶把畫面改成呼叫那兩個量的收斂）**：pass。`SearchScreen.kt` 仍是自己 `results.size` 與 `results.sumOf { it.matchedMessageCount }`，兩份寫法都在。

## 任務 12：查詢欄位的空白規則
- **驗收 1（空欄位按空白鍵）**：pass。用**實際的空白鍵**點一下 → 欄位逐碼位讀出為空、顯示引導文案、**沒有**「找不到」。
- **驗收 2（貼上前導空白 + 內容 → 欄位只留內容）**：pass（**走替代做法**）。
  - **本輪用了替代做法，原因：這台裝置／這套工具鏈設不了剪貼簿** —— `adb shell cmd clipboard` 回 `No shell command implementation.`（2026-09-10 當場重跑確認），走 UI 複製的第一步又需要先把字串打進某個欄位，對含前導空白的字串同樣做不到。
  - **實際用的輸入方式與字串**：單一次 `adb shell input text` 送入「兩個空白 + kiwi + 兩個空白」，以及「一至三個空白 + qzt」，字串換成 ASCII。
  - **要驗的性質成立**：一次進來的內容以空白開頭時，**開頭那幾個空白沒有留在欄位裡** —— 送入「兩個空白 + kiwi + 兩個空白」後，欄位逐碼位為「kiwi」加兩個尾端空白（開頭兩個被移除、尾端兩個還在）。
  - **回報環境是否已經能設剪貼簿：仍然不能。** 本輪重新確認過，`cmd clipboard` 這個 shell 指令在這台裝置上不存在。
- **驗收 3（欄位為「內容 + 2 空白」時刪掉內容 → 欄位為空、回到未搜尋狀態）**：pass。欄位為「qz」加兩個空白 → 把游標移到兩個空白之前、刪掉 `z` 再刪掉 `q` → 欄位逐碼位為空、顯示引導文案，**沒有**出現「找不到符合「  」的訊息」。（中間階段「q」加兩個空白也正常顯示結果。）
- **驗收 4（打字中尾端空白必須留著）**：pass，兩種語言都驗過。ASCII：「qz」+ 2 空白 → 欄位 `['0x71','0x7a','0x20','0x20']`。中文（這一條的存在理由）：注音打「北京」→ 按空白鍵 → 欄位 `['0x5317','0x4eac','0x20']`（尾端空白留著）→ 再打「天氣」→ 欄位為「北京 天氣」，且該查詢**命中**含 U+00A0 與 U+3000 的兩則訊息。
- **驗收 5（失去焦點後尾端空白被移除、重新聚焦不得加回）**：pass。`adb shell input keyevent 61`（TAB）可以把焦點移離 Compose 的 `TextField`（環境文件第 7 節原本寫「不一定做得到」，本輪實測可以，已更正）。欄位「kiwi」加兩個空白 → TAB → 「kiwi」；再點回欄位重新取得焦點 → 仍是「kiwi」，沒有把空白加回來。
- **驗收 6（不經過畫面直接驗證，且與 §2.1 同一份空白集合；兩個案例都要）**：pass。測試 `the field uses our whitespace rule, not the built-in one` 逐碼位確認：以 **U+00A0** 開頭 → 被移除 ✅；以 **U+001C** 開頭 → **不**被移除 ✅；同一個方法裡另有對內建 `trim()` 的斷言，證明它在 U+001C 上與我們相異。
- **驗收 7（無命中文案與欄位逐字元相同）**：pass，同任務 8 驗收 10。
- **驗收 8（開頭移除與失焦移除前後，結果列表與摘要完全相同）**：pass。欄位為「qzt」加兩個空白時的整個結果畫面（摘要「3 段對話・9 則訊息」、3+2+2 列、「還有 2 則符合」、每個節點的 bounds）與 TAB 失焦變成「qzt」之後**逐節點完全相同**；帶前導空白與不帶前導空白的查詢結果同樣相同。
- **驗收 9（比對層空查詢契約測試仍存在且通過）**：pass。`an empty query is not a search` 五個案例都在、都綠，沒有被刪。
- **驗收 10（畫面端仍然沒有比對或整形程式碼）**：pass。欄位規則是 `ChatViewModel` 呼叫純邏輯層的 `withoutLeadingSpace()` / `withoutTrailingSpace()`，`SearchScreen.kt` 自己沒有實作任何一段。
- **驗收 11（註解不得再宣稱雙向差異、不得再宣稱內建 trim 不移除 U+00A0）**：pass。原始碼側 `withoutTrailingSpace` 的 KDoc 現況是「Kotlin's trim follows `Char.isWhitespace`, which is very nearly our rule — it covers every separator we do — but it also strips U+001C–U+001F」，方向正確；測試側的註解同樣只宣稱「it does strip U+00A0 … and the C0 delimiters too」，而且**每一句宣稱都配了一個會執行的斷言**。全專案找不到第三處提到內建 trim 的敘述。

## 任務 13：對話「最後更新時間」的定義
- **驗收 1（§8.3 排序不因為看一眼而改變）**：pass。開著 A → 從抽屜開 B → 再開回 A：搜尋分組順序、三個分組標頭上的時間文字、抽屜歷史順序**與操作前逐節點完全相同**，而且全部 20 個對話檔的 md5 也完全相同。
- **驗收 2（§8.4 儲存體裡的時間不動）**：pass。A 的存檔在切換前後**位元組完全相同**，`updatedAt` 維持 1788991293132，比切換時刻 1788992425415 早 1132 秒。
- **驗收 3（§8.6，兩個預期都判）**：pass。
  - 前置：串流途中按停止，且停止當下那則回覆畫面上**已經有文字**（我先做了兩次零文字的停止，確認那種情況整則不留下，再把停止時機延後到 5 秒才取得有文字的場景）。
  - **預期一（文字）**：該對話存檔中那則被停止的回覆，其文字結尾為 **「…（已停止）」** ✅（程式判定 `endswith` 為 True；本節核對時 `stop-generation` 那一輪的現行標記就是這個字串，見 `chat/ReplyEnding.kt`）
  - **預期二（時間）**：等待 ≥30 秒不做任何事後切換到另一段對話，存檔中的 `updatedAt` = 1788993468984，切換時刻 = 1788993534946 → **早 65.96 秒**（≥30）✅。（後來為了確認切換真的發生，我又做了一次乾淨的切換，該次差距 208.8 秒，且切換前後該對話存檔位元組完全相同。）
  - 兩個時間都取自裝置時鐘，不受主機時差影響。
- **驗收 4（§8.5 不得修過頭）**：pass。送出一則訊息並收到完整回覆後，該對話 `updatedAt` 前進到 1788993071613（送出時刻 1788993070040 之後 1.6 秒），分組標頭時間變成 06:31，且在搜尋結果與抽屜歷史中都排到**最前**。刪除、切換人格、切換模型、開新對話全部照常運作。
- **驗收 5（§8.7 新對話的起點）**：pass。切人格產生的空白對話查其開場白時排在**第一**（時間 06:22，比所有歷史都新）。
- **驗收 6（「什麼算一次更新」只有一個定義處）**：pass。`ChatViewModel.updateThread()` 是唯一蓋時間的地方（`if (updated.hasSameContentAs(state.conversation)) updated else updated.copy(updatedAt = now)`）；`send` / `stop` / `updateMessage` / `settle` 全部經過它，`openThread` / `switchPersona` / `switchModel` / `refreshHistory` 只換 `conversation` 欄位、不蓋時間。全檔沒有第二處寫 `updatedAt =`。
- **驗收 7（存檔時機未被改動）**：pass。在另一段對話送出並收到回覆後切走再切回，內容仍在（存檔與畫面都有）；切換對話、切換人格／模型、送出後收尾都還是各自照原本的時機存檔。
- **驗收 8（回歸）**：pass。送出、串流、停止（有文字／零文字）、刪除、切換人格、切換模型、冷啟動續接全部照常。

## 任務 14：一次性補掃的收尾
- **驗收 1（13 條每一條都指得出怎麼被驗的）**：pass。任務 1 驗收 9（讀 `ConversationRepository` 的 KDoc）、10（讀 `find()` 那條路徑）；任務 2 驗收 13（測試 `a hit carries the message itself, not just its text`）；任務 3 驗收 25（測試 `ellipses appear only where text was actually cut`）；任務 4 驗收 7（讀 `ChatUiState` 的欄位組成）；任務 5 驗收 8（實機：清 logcat → 進出搜尋 → pid 過濾 + 檔案 md5 比對）；任務 6 驗收 9（實機：注音打「北」再打「京」，判讀點在輸入停止 1 秒後）、10（讀相依清單與 `searching` 旗標）；任務 7 驗收 11（實機：讀分組標頭的四個文字節點）；任務 8 驗收 11（實機：四種狀態下看結果區的樣子 + 歷史載入後複查）、12（`run-as cat shared_prefs/session.xml`）、13（實機：推一個只含助理訊息的舊檔後點該筆命中）、14（實機：三種點擊 + 前後檔案 md5）。
- **驗收 2（可自動化的那幾條確實有測試在跑，且說得出什麼樣的實作會讓它變紅）**：pass，但**任務 3 驗收 16 是這一條原則的反例**（它不在這 13 條裡，但同型；見任務 3 驗收 16）。這 13 條裡可自動化的三條我都說得出讓它變紅的錯誤實作，其中任務 3 驗收 25 本輪**實際做了突變並確認變紅**。
- **驗收 3（配了正向對照的，兩半都要被執行到）**：pass。任務 3 驗收 25（短訊息無省略號＋長訊息有省略號，同一個測試方法內）、任務 5 驗收 8（沒有讀寫＋該查詢確實有非空結果）、任務 6 驗收 9（沒有搜尋鈕＋結果確實隨輸入改變）、任務 6 驗收 10（沒有導覽框架＋旋轉後仍在搜尋畫面）、任務 7 驗收 11（沒有「還沒說話」＋四樣都在且會跟著換）、任務 8 驗收 11（沒有載入中＋結果含歷史對話）、任務 8 驗收 12（找不到查詢字串＋游標確實還在）—— 每一條的兩半我都執行了。
- **驗收 4（這 13 條不得要求任何行為改變；變紅就回報不要改條文）**：pass。13 條全部通過，沒有要求任何行為改變。本輪另外發現的問題（任務 3 驗收 16）我照這一條的精神回報，沒有去動條文。
- **驗收 5（本任務不改動任何規則與舊條文的要求）**：pass（不涉及實作）。

---

# 邊界情況檢查

spec 沒有一節叫「已知衝突與邊界情況」，我把散在各處被點名的邊界情況逐一查了：

| 邊界情況 | 出處 | 有沒有被處理 | 怎麼確認的 |
|---|---|---|---|
| 命中長度恰為 60（§4.2 的分支界線） | §4.2、任務 3 驗收 23 | ✅ | L=59/60/61 三個測試都在且斷言不同；讀 `hitOrNull` 的 `hitLength >= SNIPPET_WINDOW` 確認界線在 60 |
| 命中長度 61（落在視窗外的部分要被夾住） | §4.6、§4.2 | ✅ | 測試斷言 L=61 的片段與標示與 L=60 完全相同 |
| surrogate pair 不得被切開 | §4.4 | ⚠️ **行為對、守不住** | 實作有兩個保護且我用探針證明它們有作用；但條文指名的測試沒有鑑別力（見任務 3 驗收 16） |
| 全形空白 U+3000 歸整形不歸折疊 | §C.7 | ✅ | 讀 `normalizeForSearch` 的 KDoc（明文寫了）＋掃描式測試把 U+3000 算進整形 |
| 零寬字元 / ZWJ 組合 emoji 不得被折 | §2.2 | ✅ | 測試 `zero-width characters are not whitespace`（含三人組合 emoji 整串不變） |
| U+001C–U+001F：內建 trim 會吃、我們不能吃 | §C.8 | ✅ | 測試逐碼位斷言以 U+001C 開頭不被移除，且對比內建 `trim()` 會移除 |
| U+00A0：只認 ASCII 空白的實作會漏 | §C.8 | ✅ | 測試逐碼位斷言以 U+00A0 開頭被移除；實機另有一則含 U+00A0 的訊息被半形空白查詢命中 |
| 空欄位按空白鍵（欄位不得以空白開頭） | §2.8a | ✅ | 實機按實際空白鍵，欄位逐碼位為空 |
| 游標移到最前面按空白鍵 | §2.8a | ✅ | 實機 `keyevent 122` 移到行首後按空白，欄位內容一字未變。**註：這條可判定沒有被任何任務驗收指名**（見下方規格問題） |
| 欄位剩下全是空白 → 回到未搜尋而不是「找不到『  』」 | §2.8a、§2.4 | ✅ | 實機刪到只剩兩個空白，畫面回到引導文案 |
| 中間空白打不出來的那個陷阱（「北京 天氣」） | §2.8b/d | ✅ | 實機用注音輸入法真的打出來了，並命中含異常空白的兩則訊息 |
| 失焦時移除尾端空白，重新聚焦不得加回 | §2.8c | ✅ | TAB 失焦後變「kiwi」，點回來仍是「kiwi」 |
| 兩次移除都不得改變結果 | §2.10 | ✅ | 結果畫面逐節點比對完全相同 |
| 同一段對話出現兩次（畫面不可達） | §3.5 | ✅ | 兩個不經過畫面的測試，含「歷史那份時間較新」與排序位置那一半 |
| 跨對話的訊息編號撞號（第 0 則） | §3.6 | ✅ | 實機查「今天」讓 11 段對話的第 0 則同時命中，捲完不崩潰、不錯位；key 含對話 id |
| 開場白造成的大量重複命中 | §3.7 | ✅ | 11 段對話的開場白全部各出現一次，沒有任何去重／降權 |
| 只含助理訊息的舊檔會被讀成空白對話並進歷史 | §6.1 | ✅ | 實機推了這樣一個檔，點它的命中列確實切過去 |
| 空白對話存檔等於刪檔（走錯分支時看起來剛好無害） | §6.4、§E.7 | ✅ | 切人格時該檔確實被刪除；點空白對話的命中列時檔案數與內容一字未動 |
| 串流中的回覆搜得到（尚未落盤） | §1.8 | ⚠️ 部分 | 「搜得到尚未落盤的訊息」已證實；「命中數隨串流增加」未能觀察（spec 允許） |
| 歷史尚未載入完成就搜尋 | §1.9 | ✅ | 沒有第四種「載入中」的樣子；正向對照（載入完成後結果含歷史對話）成立 |
| 按下停止：有文字 vs 零文字兩種 | §8.1 | ✅ | 兩種都實機做到：有文字→追加「…（已停止）」；零文字→那則回覆整則不留下 |
| 串流中切走（切換不蓋時間，但停止會改內容） | §8.2 | ✅ | 切換前後該對話存檔位元組完全相同，時間停在停止當下 |
| 行程被回收後還原 | §5.7 | ✅ | `am kill` 後還原落在搜尋畫面、查詢為空 |
| 壞掉的對話檔不得讓整份歷史消失 | §E.7 | ✅ | 放入一個截斷的 JSON，歷史列出的對話集合與放入前相同 |
| 橫向 + 鍵盤的可見量不在要求範圍內 | §7.3 | ✅ | 我沒有拿橫向可見量去判 fail |
| 環境打不出 U+3000 / 設不了剪貼簿 | §F.3 | ✅ | 依規定記「本環境無法執行」，並改驗使用者做得到的方向 |

---

# 如果 fail：問題歸屬

**三條都是規格問題，打回 PM**（其中一條另需 RD 補測試）。**沒有任何一條是實作問題** —— 我沒有找到任何產品行為缺陷。

1. **任務 3 驗收 16 沒有鑑別力 → PM（條文）+ RD（測試）。**
   條文只寫「片段裁切不會切斷 surrogate pair」，沒有像驗收 23 對 §4.2 那樣**指定會碰到邊界的測資形狀**；RD 照字面寫出來的測資在數學上永遠碰不到那個邊界。我已用突變證明：拿掉兩個保護，測試照樣全綠；另用探針證明保護真的在防一件會發生的事。**建議的修法**：條文補一句可判定（例如「訊息必須讓視窗的**起點**落在一個 surrogate pair 中間」），RD 照著補案例。

2. **任務 9 驗收 3 照字面不可能通過 → PM（條文與判定基準的顆粒度不合）。**
   「未被本次改動觸及」的三項判定是**以檔案為單位**寫的（第 1 項是檔案 mtime、第 2 項是「該檔裡找不到本功能引入的任何東西」），但這一條指名的是 `ChatViewModel.kt` **裡面的一段程式碼**，而那個檔案正是任務 5 與任務 12 **要求**本功能去擴充的地方。於是第 1 項必然不成立、第 2 項必然不成立，**不論實作寫得多正確**。這一型與 spec 自己記的第 6 型（條文要求的證據不存在於產物裡）相鄰但不同：這裡的問題不是拿不到證據，是**判定的單位選錯了**。

3. **任務 9 驗收 7 的第 1 項不成立 → PM（同上）。**
   `Conversation.kt` 同樣是本功能必須擴充的檔（`allThreads`、`hasSameContentAs` 都在裡面），mtime 必然晚於基準日；補救條款的「動了什麼」在沒有版控時拿不到證據。第 2、3 項與直接驗得到的那一半都成立。

**另外三件事只回報、不判 fail，但建議 PM 處理：**

- **§2.8a 的第三條可判定（把游標移到欄位最前面按空白鍵 → 欄位內容不變）沒有被任何任務驗收指名。** 任務 12 的驗收 1、2、3 分別覆蓋「空欄位按空白」「一次進來的內容以空白開頭」「刪到只剩空白」，就是沒有這一條。這是 §F.5 要防的那一型缺口（規則有寫、條文沒覆蓋）。本輪我照規則自己驗了，行為正確。
- **任務 9 驗收 5（抽屜除了多一個搜尋入口列之外完全沒變）沒有被列進「未被本次改動觸及」那七條，因此沒有判定基準可用。** 它跟那七條是同一型的要求，卻各判各的。冷讀的人只能像我一樣交一份逐項清單，判不出「完全沒變」。
- **一個沒能重現的異常，誠實記下來**：驗收初期有兩次，用單一次 `adb shell input text` 送出**以空白開頭**的字串時，緊接在開頭空白後面的那一個字元被吞掉（送入「空白 + qzt」，欄位只剩「zt」）。同一次注入送進聊天畫面那個**不套規則**的輸入框則四個字元完整保留，所以當下看起來像是欄位規則造成的狀態失步。**但後續 3/3 次重跑都沒有再現**，條文指名的那個輸入（前後各兩個空白的 kiwi）也一次就正確，人手速度的逐字輸入從未掉字。我因此不把它算成缺陷，只留在這裡供下一輪注意。

---

# 驗證方式說明

**深度：與定義中的「每一輪都要做的六件事」逐項對照，六項全做。**

1. **跑專案的標準測試指令** ✅ `./gradlew clean testDebugUnitTest`（開頭與收尾各一次，都加 `clean` 避免 UP-TO-DATE）。逐檔讀 `app/build/test-results/testDebugUnitTest/*.xml` 的 root 屬性：`ConversationSearchTest` 48、`DegenerationTest` 5、`ExampleUnitTest` 1、`ModelMarkdownTest` 9、`ReplyEndingTest` 9、`TextRepairTest` 8，**共 80 個測試，failures=0 errors=0**。
2. **每一條指名了測試的驗收條文** ✅ 任務 2、任務 3（26 條）、任務 4、任務 12 驗收 6/9 逐條打開 `ConversationSearchTest.kt` 讀**斷言內容**，不是看方法名。含 U+3000 / U+00A0 / U+001C / ZWJ 這幾處我用程式逐碼位印出原始碼那幾行，確認字面是規範要求的那個字元。
3. **每一條邊界情況** ✅ 見上方表格，逐列主動查過，沒有假設「應該有處理」。
4. **每一條實機條文** ✅ 在 Pixel 9（Android 16、1080x2424、density 420）上實際操作，包含用**裝置上的注音輸入法手動打中文**（北、京、天、氣、臺、台、今、天、阿、公）。判讀一律用 `uiautomator dump` 逐碼位讀，不看截圖猜；鍵盤與候選列用截圖判讀。沒有任何一條降級成靜態閱讀。
5. **突變驗證恰好三條** ✅ 見下。
6. **真實資料的快照 / 還原 / 逐位元組驗證** ✅ 見下方「對使用者資料與裝置設定做過什麼」。

## 這次隨機挑到的三條

從**有測試在守**的驗收條文裡隨機抽樣（`docs/qa-random-coverage.md` 本輪才建立，沒有歷史可避開，所以純隨機）。突變一律做在 scratchpad 的**複本**上，用 Gradle 快取裡的 Kotlin 編譯器單獨編譯執行，**專案原始碼全程唯讀**（收尾核對三個檔的 mtime 與開始時完全相同）。基準：複本未改動時 48 測試全綠。

| 條文 | 改成什麼錯法 | 有沒有變紅 |
|---|---|---|
| **任務 3 驗收 25**（§4.3，該有與不該有省略號兩個方向） | `val prefix = ELLIPSIS` / `val suffix = ELLIPSIS`（不看起訖點，無條件在頭尾補刪節號） | ✅ **變紅**，4 個測試失敗，其中包含條文指名的 `ellipses appear only where text was actually cut`（expected `我要去北京`，實際多了頭尾兩個刪節號） |
| **任務 3 驗收 16**（§4.4，不得切開 surrogate pair） | `avoidSplittingPairAt` / `avoidSplittingPairBefore` 兩個都改成直接回傳 index（保護整個拿掉） | ❌ **沒有變紅**，48 測試仍全綠。**這是一個真的發現**，已寫進「問題歸屬」第 1 項，並附了一支證明保護確有作用的探針（同一段邏輯在另一組測資下，拿掉保護會產出孤兒代理對） |
| **任務 4 驗收 3**（§B.3，去重時記憶體那份優先） | `allThreads` 的合併順序改成歷史在前（讓磁碟那份贏） | ✅ **變紅**，2 個測試失敗，其中包含條文指名的 `allThreads keeps the in-memory copy when a thread arrives twice`（expected 2 but was 1），以及 `the in-memory copy wins even when the saved one looks newer`（順序反過來） |

## 對使用者資料與裝置設定做過什麼

- **對話資料**：開工前完整快照 `files/conversations`（14 個使用者對話）與 `shared_prefs/session.xml`，記下 SHA-256。全程**只用我自己新建的 `qatest-*` 檔**做測試，並在開工時把 `last_thread_id` 指到我自己的測試對話，讓所有「切換時存檔」都落在我的檔案上、不碰使用者的檔案。收尾刪掉全部 `qatest-*`、還原偏好檔，**重新抓一次逐檔比對 SHA-256：與 baseline 完全相同（15 個檔全中）**，檔案清單也與 baseline 一致。
- **裝置設定（都先記原值、收尾都還原並複查）**：

  | 項目 | 原值 | 收尾複查 |
  |---|---|---|
  | `accelerometer_rotation` | `1` | `1` ✅ |
  | `user_rotation` | `0` | `0` ✅ |
  | `wm density` | `420`，無 override | `420`，無 override ✅ |
  | 輸入法版面（Gboard） | 注音 | 注音 ✅（收尾截圖確認空白鍵上寫「注音」） |
  | `screen_off_timeout` | `1800000` | **沒有動過**，複查仍為 `1800000` |
  | `default_input_method` | Gboard | **沒有動過** |

- **裝置暫存**：`/data/local/tmp/qa` 與我推過去的偏好檔都已刪除，收尾 `ls /data/local/tmp` 只剩系統自己的三個目錄。
- **受測建置**：全程沒有重新建置或安裝，`lastUpdateTime` 收尾仍是 `2026-09-10 05:53:14`。

## 兩件意外，立刻回報

1. **推測試資料時多推了東西進 App 的私有目錄。** 我用 `adb push <目錄>/.` 推 scratchpad，而那個目錄裡有**前一輪留下的 78 個檔案**；接著那句 `cp /data/local/tmp/qa/*.json` 把其中 11 個不屬於本輪的 `.json` 一起複製進 `files/conversations`。**發現時 App 還是 force-stop 狀態，從未讀到它們**，我立刻用 `comm -23` 比對快照清單把 11 個全部刪除，並改成逐檔 push。收尾的逐位元組比對確認使用者資料未受影響。已把這個坑寫進環境文件。
2. **在受測 App 以外的地方輸入過文字（沒有送出）。** 有一次我以為還在受測 App 裡（實際上連按兩次返回已經掉到桌面），把一句測試 prompt 打進了 **Google 搜尋列**。**沒有按下搜尋、沒有產生任何搜尋紀錄**（畫面只到建議列），我立刻按該欄位的「清除」鈕清空並回到主畫面，確認欄位恢復成佔位字。除此之外沒有在任何其他 App 產生東西：沒有送出訊息、沒有建立行事曆事件、沒有觸碰通知。

---

# 本輪驗證範圍

**全驗。** spec 入口檔地圖指到的檔案都讀完了（`spec.md`、`spec-architecture.md`、`spec-tasks.md`）；`spec-decisions.md`（本輪 0 項）與 `spec-appendix.md` 依定義**不作為驗收對象**，我只在想確認某條規則的來歷時去查。任務 1–14 的每一條驗收標準、每一條可判定條文、每一個邊界情況都執行過。

**架構我不判。** `spec-architecture.md` §A–§G 我讀完了，但只驗其中**說得出使用者看得見後果**的那些（它們都已經以驗收條文的形式出現，我驗的是那些條文）；純結構偏好不列入 pass/fail。

未執行的只有兩項，兩項都是 spec 明文允許的：

- 任務 5 驗收 2 的「命中數隨串流增加」→ **未能觀察**（已取得 spec 列出的間接佐證）。
- 任務 9 驗收 12（選作，U+3000 進剪貼簿）→ **本環境無法執行**。

---

# 環境文件的處置

`docs/qa-environment.md` 已存在，**依定義先驗再用**。逐節跑過它列出的確認方式：

- **驗過並成立**：第 1 節（JDK 與 Gradle 指令、測試結果 XML 位置）、第 2 節（`adb devices -l` / `wm size` / `wm density`）、第 3 節（用 `lastUpdateTime` 對照原始碼 mtime）、第 5 節（uiautomator dump 的切法、逐碼位讀欄位、鍵盤蓋住覆蓋式版面、只有 BACK 收得掉鍵盤、可捲動清單保留捲動位置 —— **最後這一條我照樣踩了一次**，抽屜歷史列表看起來少了一段對話，往回捲到頂才發現它一直都在）、第 6.1–6.3 節（版面判讀、`input text` 在注音版面下無聲失敗、標準注音鍵盤配置＋候選列選字）、第 6.5 節（剪貼簿指令不存在）、第 8 節（density override 與強制旋轉）、第 9 節（`am kill` vs `force-stop`、返回鍵會掉到桌面）、第 10 節（用 pid 過濾 logcat）、第 11 節（時差要每輪重量）、第 12 節（在複本上跑改壞的純 Kotlin 邏輯）。
- **確認失敗、已當場改成現在成立的樣子**：
  1. **第 4 節的快照指令用了 `xargs -a`，macOS 的 xargs 沒有這個選項**，照抄會直接失敗、產出一個 0 行的 baseline。改成 `shasum -a 256 $(cat filelist.txt)` 並註明原因。
  2. **第 7 節寫「讓輸入框失去焦點但不離開畫面在這個環境不一定做得到」，實測不成立** —— `adb shell input keyevent 61`（TAB）可以把焦點移離 Compose 的 `TextField`。已改寫成「先試 TAB，TAB 也無效時才記本環境無法觀察」，並加上判斷焦點是否真的走了的確認方式，同時標明這是 2026-09-10 的更正。
- **新增**：
  - 第 4 節加上推資料前的衛生規則（先清 `/data/local/tmp/<你的目錄>`、逐檔 push 不要 push 整個目錄）與確認方式（`cp` 後立刻 `comm -23` 比一次），來源是我這一輪踩到的那次誤推。
  - 第 6.4 節加上「連按兩次空白鍵會被輸入法換成句號加空白」與它的確認方式（逐碼位看到 `0x2e` 就是踩到了）。
  - 第 9 節把返回鍵那一段改成「軟鍵盤開著時第一次 BACK 只收鍵盤」，並要求每次按之前先問一次鍵盤狀態，不要用固定次數。
  - 第 12 節加上「編譯時要把所有相依的來源目錄一起列在命令列上」，少列一個會得到與真正原因無關的 `ClassNotFoundException`。
- **刪掉的越界內容：沒有。** 我用「換成一個完全不同的產品還成立嗎」逐句掃過全文，沒有找到產品觀察、預期文字或 pass/fail 混進去。
- **所有條目的最後確認日期已更新為 2026-09-10**，時差那一條另外註明「這個值每輪都不一樣，不要沿用」。

---

# 隨機挑選記錄的處置

`docs/qa-random-coverage.md` **本輪新建**（先前不存在）。檔頭寫明它只記「哪一條、什麼時候」、永遠不記結果、挑選時優先挑最久沒被挑過的。本輪追加三行：

```
2026-09-10  任務 3 驗收 25
2026-09-10  任務 3 驗收 16
2026-09-10  任務 4 驗收 3
```

結果寫在上面「驗證方式說明」裡，那份檔案本身一個字都沒有記結果。
