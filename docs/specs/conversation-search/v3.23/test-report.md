# 驗證結果總覽

**pass** —— `spec.md` 行為規格 §1–§9 與 `spec-tasks.md` 任務 1–19 的每一條驗收條文全部驗過，沒有任何一條判定為 fail；兩條依條文明文「不判 fail」的環境限制記為「本環境無法觀察／無法執行」（任務 12 驗收 1 的「貼上單一 U+3000 / U+00A0」、任務 9 驗收 12），各自的失敗步驟已指名。

- **受測版本**：HEAD `8f3ca0d`；驗收開始時 `git status --porcelain` 為空（工作區乾淨）。
- **建置與安裝**：從工作區 `./gradlew installDebug` 覆蓋安裝（未解除安裝）。`dumpsys package com.example.demo` 的 `lastUpdateTime = 2026-09-16 17:20:51`，裝置當下時鐘 `17:21:07`；產品程式碼最後一次提交 `0afd2a6 2026-09-16 02:17:27`。裝置上 APK 大小 `1857203015` 與本機 `app/build/outputs/apk/debug/app-debug.apk` 相同。
- **裝置**：Android 模擬器 `emulator-5554`（AVD Pixel_9），Android 17，1080x2424，density 420（無 override），`/data` 可用 12G。
- **使用者資料**：呼叫者明講「使用者已同意清空驗收裝置上的 App 資料」，因此驗收開始前清空 `files/conversations/` 與 `shared_prefs/session.xml`，**保留**三個模型資產檔（`gemma3-1b.task`、`model.task`、`smollm.task`）。未做快照／還原／逐位元組驗證。
- **工作區的一處非我造成的改動**：`docs/specs/conversation-search/pm-inbox.md` 在驗收途中（mtime 17:24）被加了 12 行。**不是我寫的**（我依角色定義沒有讀它、也沒有寫它），列在這裡讓呼叫者知道。它不影響受測建置。

---

# 逐項驗收結果

## 任務 1：對話存取的分層抽出

- **驗收 6（既有行為逐項不變，在裝置上）**：**pass**
  - 冷啟動續接／檔案不存在：清空資料後冷啟動 → 開一段 🙌 朋友 + Gemma3 1B 的新對話（只有開場白）。另以 `last_thread_id=no-such-id-12345` 冷啟動 → 一樣開新對話、不崩潰、對話檔數量不變（20 → 20）、logcat 無 FATAL。
  - 冷啟動續接／檔案存在：以 `last_thread_id=qa-x` 冷啟動 → 續接到該段；另一次冷啟動續接到 qa-long、到 c14e3d23，畫面內容與存檔相符。
  - 空白對話不進歷史：換人格產生的空白對話不出現在抽屜列表，對話檔數量前後相同（見任務 16 驗收 4）。
  - 歷史排序：15 段測資依 `updatedAt` 新→舊列出，與我推入的時間順序完全一致。
  - 開啟／刪除／送出後更新：點列開啟 ✓；垃圾桶刪除 ✓（16 → 15 檔）；送出並收到完整回覆後 `updatedAt` 由 `1789550965076` 前進到 `1789553201019` ✓。
  - 壞檔容錯：`qa-corrupt.json`（非 JSON）在目錄裡時，其餘 15 段對話仍完整列出（逐列點名核對過）。
- **驗收 10（依 id 取一筆 == 取全部再挑同一個 id）**：**pass**
  - 程式碼冷讀：`ConversationRepository.find(id)` 的實作就是 `all().firstOrNull { it.id == id }`，兩條路徑是同一段程式，因此在「找得到」與「兩邊都找不到」上必然同答案。
  - 裝置佐證：存在的 id（`qa-x`）冷啟動開得起來；不存在的 id（`no-such-id-12345`）兩邊都答「沒有」→ 開新對話、不崩潰。

## 任務 2：比對與片段的純邏輯

- **驗收 1**：**pass**。`chat/ConversationSearch.kt` 全檔無 `import android.*` / Compose / 持久化相依（只用到 `java.text.BreakIterator`），`ConversationSearchTest` 直接呼叫它跑在 JVM 上。
- **驗收 2**：**pass**（`an empty query is not a search`，案例含 U+3000、U+00A0）。
- **驗收 3**：**pass**（`conversations come back newest first and hits stay in the order they were said`）。
- **驗收 4**：**pass**（`a conversation shows at most three hits but reports the true total`：hits=3、matchedMessageCount=5）。
- **驗收 5**：**pass**（`a conversation with no matching message is left out entirely`，並斷言 `results.none { it.hits.isEmpty() }`）。
- **驗收 6**：**pass**（`highlights stay inside the snippet and never overlap`）。
- **驗收 7**：**pass**。`flattenToLine()` 與 `normalizeForSearch()` 都是 `internal`，測試直接呼叫並單獨斷言（`every unicode separator is collapsed`、`normalising never changes the length of the text`）。
- **驗收 8**：**pass**。四個使用點都走同一個 `Char.isFlattenableSpace()`：訊息端／查詢端整形（`flattenToLine`）、`SearchUiState.isActive`（`query.flattenToLine().isNotEmpty()`）、欄位規則（`withoutLeadingSpace` / `withoutTrailingSpace`）。U+0020／U+00A0 四處皆算空白、U+001C 四處皆不算，由測試 `isActive agrees...`、`the field uses our whitespace rule, not the built-in one`、BMP 掃描共同釘住。
- **驗收 9**：**pass**（`every unicode separator is collapsed` 的 BMP 掃描 + `zero-width characters are not whitespace` + `a run of mixed whitespace becomes exactly one space`）。
- **驗收 10**：**pass**。`ChatViewModel.onSearchQueryChange` 只套用 `withoutLeadingSpace()`，沒有把整形後的字串寫回欄位；裝置上實測「北京 天氣」中間空白留得住（任務 12 驗收 4）。
- **驗收 11**：**pass**（`isActive` 用 `flattenToLine()` 判，不是 `isBlank()`；BMP 掃描斷言不存在分歧字元）。

## 任務 3：比對語意的單元測試

**全部 26 條 pass。** 整套 `./gradlew clean testDebugUnitTest` 由 clean 跑起（log 有 `:app:clean` 與 `:app:testDebugUnitTest`，無 UP-TO-DATE／FROM-CACHE），9 個測試類別共 136 個測試，`failures=0 errors=0 skipped=0`；`ConversationSearchTest` 54 個、`ConversationTest` 32 個。逐條指到的測試：

| 條 | 測試 | 判定 |
|---|---|---|
| 1 | `a message the user wrote is found` / `a message the assistant wrote is found` | pass |
| 2 | `matching ignores case`（`hello` 與 `HELLO` 兩種寫法都斷言） | pass |
| 3 | `matching ignores full-width and half-width`（兩個方向） | pass |
| 4 | `traditional and simplified are not folded together` | pass |
| 5 | `surrounding whitespace in the query is ignored`（`assertEquals(1, bare.size)` 正向 + `assertEquals(bare, padded)` 比對完整結果） | pass |
| 6 | `an empty query is not a search`（`""`、`"   "`、`"\n\t"`、U+3000、U+00A0 五個案例，逐碼位核對過原始碼裡確實是那兩個字元） | pass |
| 7 | `a query that matches nothing returns nothing` | pass |
| 8 | `normalising never changes the length of the text`（含 `assertEquals(1, "İ".normalizeForSearch().length)`） | pass |
| 9 | `a highlight points at the matched text even when the message had newlines` | pass |
| 10 | `every match inside the window is highlighted` | pass |
| 11 | `a hit deep inside a long message is cropped with a leading ellipsis`（長度 62＝60＋兩個刪節號） | pass |
| 12 | `a conversation shows at most three hits but reports the true total` | pass |
| 13 | `the persona opener can be searched`（斷言 author=Assistant 且 id 等於第 0 則） | pass |
| 14 | `a thread holding nothing but its opener still matches`（1／1，且不命中時完全不出現） | pass |
| 15 | `a shared opener matches in every thread that has it`（三段全部出現） | pass |
| 16 | `cropping never splits a surrogate pair` + `...at the window end`；測資形狀與條文指定相同（15 個 😀＋1 BMP＋查詢詞＋尾巴／30 BMP＋查詢詞＋7 BMP＋20 個 😀），兩案例各自獨立，且都斷言「片段仍完整含有查詢詞」 | pass |
| 17 | `every unicode separator is collapsed`（BMP 65536 掃描，斷言字面就是 `整形("a"+c+"b") == "a b"`，違反數 0）＋`zero-width characters are not whitespace`（含 ZWJ 家庭不變）＋`a run of mixed whitespace becomes exactly one space` | pass |
| 18 | `a message written with a full-width space is found with a plain one` | pass |
| 19 | 四案例齊全：`a query written with a full-width space finds a plain message`、`a run of spaces in the query matches a single space`、`a message written with a non-breaking space is found with a plain one`（原始碼確認是 U+00A0）、`a query of nothing but whitespace is not a search`（只有 U+3000 → 空） | pass |
| 20 | `a highlight is as long as the flattened query, not what was typed`（長度 5，取出「北京 天氣」） | pass |
| 21 | `a query with a space inside is one string, not two words to find separately`（正反兩半） | pass |
| 22 | `isActive agrees with flattening about what counts as empty`（U+3000／U+0020／U+00A0／`" \t\n"` 皆非搜尋、`"a"` 是搜尋、BMP 掃描違反數 0） | pass |
| 23 | `a hit shorter than the window keeps its lead-in`(59)／`a hit that exactly fills the window has no lead-in`(60，且 `assertNotEquals` 兩側片段不同、`assertFalse contains 甲`)／`a hit longer than the window is clamped to what is shown`(61) | pass |
| 24 | `persona and model names are not searched`（「老師」「Qwen」「qwen」皆不命中，正向對照「想不通」必須命中） | pass |
| 25 | `ellipses appear only where text was actually cut`（短訊息無「…」與長訊息有「…」在同一個方法裡） | pass |
| 26 | `searching finds message text rather than the derived title`（斷言 title=="還沒說話"、查它不命中、查開場白裡的字必須命中） | pass |

## 任務 4：「所有對話」的定義與搜尋子狀態

- **驗收 2**：**pass**（`all threads includes the open one`、`all threads are ordered newest content first`、`allThreads contains the open thread even when it is blank`）。
- **驗收 3**：**pass**（`the same thread arriving twice appears once` / `allThreads keeps the in-memory copy when a thread arrives twice`：id 只出現一次且是訊息較多那份）。
- **驗收 6**：**pass**。`the in-memory copy wins even when the saved one looks newer`（兩個測試檔各一份）用的正是 §3.5 第二條可判定的測資（記憶體 `same`/100/兩則、歷史 `same`/900/一則、`other`/500/一則），**排序位置那一半也有斷言**：`assertEquals(listOf("other","same"), results.map{...})` 且 `matchedMessageCount == 2`。

## 任務 5：狀態層的搜尋入口與衍生結果

- **驗收 2（串流中的回覆搜得到，盡力而為）**：**pass（有攔到）**
  - 用的模型與提示：**Gemma3 1B**；提示 `please write an extremely long detailed story about the sea and the ships and the sailors, at least 2000 words`。
  - 串流大約持續：10–20 秒（撞到 800 字上限或自行結束）。
  - 做法：送出後在同一個 `adb shell` 批次裡 0.5s → 收鍵盤 → 開抽屜 → 進搜尋 → 打 `sea`，約 2.8 秒完成。
  - 攔到的證據：摘要列在 `18:28:46` 是「5 段對話・16 則訊息」，`18:28:49` 變成「5 段對話・17 則訊息」，之後穩定在 17 —— 命中數在串流期間自行增加。
  - 另有一次獨立的間接佐證：送出含唯一字串 `zzqqy` 的訊息後立刻搜 `zzqqy`，搜到（1 段對話・1 則訊息），而同一時刻磁碟上 `grep -c zzqqy` 為 **0** —— 搜得到的是還沒落盤的記憶體內容。
  - 先前兩次沒攔到的做法也記下來，免得下一輪重試：(a) 送出後改查詢詞（要先清空欄位再打字，約 3–5 秒）時回覆已結束；(b) 用較短的提示時回覆在導航完成前就結束。
- **驗收 5**：**pass**（見任務 6 驗收 8）。
- **驗收 6**：**pass**。送出、停止生成、切換人格、切換模型在本輪全部實際做過且正常（詳見任務 13 驗收 8）。
- **驗收 7**：**pass**。切人格產生的空白 老師 對話，用它的開場白「慢」查得到，且排在第一組（📘 老師 Gemma3 1B 17:51）。
- **驗收 8**：**pass**。乾淨的一趟：進搜尋 → 打 `story` → 得到「5 段對話・14 則訊息」（正向對照，結果非空）→ 離開。前後 `jsondir` 內容 **完全相同**，`hashdir` 位元組也完全相同；該 pid 的 logcat 沒有任何 `llm|inference|gemma|qwen|smol|mediapipe` 字樣；狀態列前後都是「Qwen2.5 0.5B・離線」。

## 任務 6：抽屜入口與搜尋畫面外殼

- **驗收 1**：**pass**（逐項）
  - (i) 上半部由上而下：模型（Gemma3 1B／Qwen2.5 0.5B／SmolLM 135M，目前用的標「使用中」）→「並排比較所有模型」(y755) →「歷史紀錄」(y910) 與「每段對話各自獨立，不會互相影響」(y984) →「開始新的對話」(y1088) →「搜尋對話內容」(y1214)。捲動歷史列表 6 次後，上半部這幾個節點的 bounds **一個像素都沒變**；只有歷史列表捲動（列表夠長：15 段）。
  - (ii) 三個入口都是點下去就動作的列：「並排比較所有模型」→ 比較畫面 ✓；「開始新的對話」→ 開新對話且人格與模型不變（朋友 + Gemma3 1B → 朋友 + Gemma3 1B）✓；「搜尋對話內容」→ 搜尋畫面 ✓。三者點下去抽屜都關閉 ✓。
  - (iii) 每一列有人格 emoji、人格名稱、模型名稱、時間、標題、預覽、刪除鈕（`content-desc="刪除這段對話"`，可見的 5 列各一個）。**兩種時間寫法都看到**：今天更新的顯示 `17:29`／`17:28`…，昨天更新的 `qa-old` 顯示 `9/15`。省略號兩個方向：長英文句子那段（`this is a very long first sentence...`）標題顯示 `this is a very long first …`、預覽 `this is a very long last message used as…`，**尾端都看得到「…」且都看不到整句**；短句那段（你好／你好）標題與預覽都是整句、**尾端沒有「…」**。標題與預覽都單行。
  - (iv) 點一列 → 開啟那段對話、抽屜關閉 ✓；按刪除鈕 → 那一列立刻從列表消失、**沒有任何確認步驟** ✓。
  - (v) 沒有其他對話時顯示「還沒有其他對話。」✓（清空資料後的第一次冷啟動）。
  - (vi) 開著 qa-long 時列表上沒有它、有 qa-short；點 qa-short 之後再開抽屜，列表上有 qa-long、沒有 qa-short ✓（正反兩半都看了）。
- **驗收 2**：**pass**（搜尋入口是一個列不是輸入框；點擊後抽屜節點消失、進入搜尋畫面）。
- **驗收 3**：**pass**（進入時 dump 裡該 `EditText` 是 `focused="true"`，`mInputShown=true`）。
- **驗收 4**：**pass**（捲動結果後摘要列由 y342 移到 y327，而 `EditText` 的 bounds 仍是 `[148,143,1058,309]`）。
- **驗收 5**：**pass**。畫面上的返回箭頭 → 回到聊天畫面；系統返回鍵在鍵盤開著時第一次只收鍵盤、第二次回到聊天畫面，`mCurrentFocus` 全程是 `com.example.demo`（**沒有退出 App**）。
- **驗收 7（比較畫面跑得出每個模型的回答）**：**pass**。從抽屜進入比較畫面 → 模型勾選維持預設（三個全勾）→ 輸入 `hello` → 按「執行」→ 三張卡片依序跑完，每張右上角都是「完成」，都有回答文字（Gemma3 1B「你好！很高興能為你服務…」18 tokens、Qwen2.5 0.5B「Hello! How can I assist you today?」9 tokens、SmolLM 135M 一段重複迴圈但確實有文字）。按左上角箭頭 → 回到聊天畫面。沒有卡片顯示「失敗」。
- **驗收 8**：**pass**。搜尋畫面旋轉到橫向後仍在搜尋畫面、查詢「台」還在、結果「1 段對話・1 則訊息」還在、輸入框仍在最上方（橫向 bounds `[290,138,2402,304]`）；轉回直向一切照舊。（橫向 + 鍵盤的可見量依條文不判。）
- **驗收 9（即時、沒有送出鈕）**：**pass**。用注音輸入法打「北」，不按任何鈕、不收鍵盤，2 秒內出現「5 段對話・7 則訊息」；接著打「京」，同樣不做別的事，結果換成「4 段對話・5 則訊息」。同時斷言：該畫面上只有「返回」箭頭、輸入框、「清除搜尋」X 三個控制項，**沒有任何按下才會搜的控制項**（結果在不按鍵盤動作鍵的情況下就已經是對的）。

## 任務 7：結果列表

- **驗收 1**：**pass**（結果列表佔滿輸入框以下、可整片捲動；見驗收 4 的觀察）。
- **驗收 2**：**pass**。截圖可見命中的「北」「北京」「北京 天氣」以主題主色（藍）加粗，與周圍文字明顯不同、位置正確。顏色取自主題（`MaterialTheme.colorScheme.primary`，未寫死色碼，程式碼冷讀）。
- **驗收 3**：**pass**。查「哈拉」→ 6 段對話的**第 0 則**（開場白）同時命中並同時列出，捲動不錯亂、不崩潰。
- **驗收 4**：**pass**。qa-h5 命中 5 則 → 顯示 3 列 +「還有 2 則符合」。
- **驗收 5**：**pass**。qa-cjk 的長中文片段在命中列上單行顯示、尾端被畫面自己的省略號截斷，沒有破版。
- **驗收 6（中文算繪，查詢經過裝置上的中文輸入法產生）**：**pass**
  - **實際用的輸入法與送鍵方式**：裝置上的 **Gboard 注音版面**；鍵由 `adb shell input tap` 打在注音鍵帽上（組字），再從候選列點選字。**沒有**用繞過輸入法直接塞字串的方式。
  - 測資 `qa-cjk`：45 個「測」＋🌧＋「，測試標點。」＋5 個「測」＋北京＋60 個「測」，**命中位置與 emoji 距離在 20 字以內**，片段視窗確實涵蓋到 🌧。
  - 觀察：標示恰好蓋在「北京」兩個字上；片段沒破版；標點「，」「。」正常；🌧 完整算繪。
- **驗收 7**：**pass**。分組標頭只有人格 emoji／人格名稱／模型名稱／時間，**沒有對話標題**；空白對話（只有開場白那段、以及推入的只含助理訊息那段）的標頭**都沒有出現「還沒說話」**。
- **驗收 8（§7.1 主條文）**：**pass**。直向、鍵盤彈出（`mInputShown=true`）、不捲動、不收鍵盤，IME frame 上緣 y=1452；完全在 1452 以上的有 **4 個對話標頭 + 5 個命中列**（標頭 431–475／609–653／865–909／1043–1087，命中列 515–561／693–731／771–817／949–995／1127–1173），超過「1 標頭 + 4 命中列」的要求。
- **驗收 9（§7.2 小螢幕）**：**pass**。`wm density 606`（等效 285x640dp，比 360x640 更嚴格），IME frame 上緣 y=1246；完全可見 **2 個標頭 + 2 個命中列**（標頭 609–673／864–928，命中列 731–796／986–1051），達到「1 標頭 + 2 命中列」。做完 `wm density reset`，讀回 `Physical density: 420` 無 override。
- **驗收 11**：**pass**。任一有命中的分組標頭同時看得到人格 emoji、人格名稱、模型名稱、時間；換對話時那幾個字跟著改（🙌 朋友 Qwen2.5 0.5B 17:23／📘 老師 Gemma3 1B 17:23／💗 情人 SmolLM 135M 17:23）。

## 任務 8：空狀態、無結果、點擊與返回

- **驗收 1**：**pass**（剛進入只有引導文案「搜尋所有對話的訊息內容」，沒有「找不到…」、沒有空的結果列表）。
- **驗收 2**：**pass**（打 `zzzqqq` → 畫面上只有「找不到符合「zzzqqq」的訊息」，沒有殘留結果、沒有摘要列）。
- **驗收 3**：**pass**（點另一段對話的命中列 → 回到聊天、內容是 qa-frag；再次進入搜尋時欄位為空）。
- **驗收 4**：**pass**（點目前開啟對話的命中列 → 回到聊天、內容不變、沒有新建對話；該 pid 的 logcat 沒有模型載入紀錄）。
- **驗收 5**：**pass**（切人格產生空白對話 → 搜其開場白「慢」→ 點該筆 → 回到聊天、對話不變、沒有重載模型、沒有新建對話；對話檔數量 15 → 15、`jsondir` 內容完全相同 → **沒有把空白對話存檔**）。
- **驗收 6**：**pass**（命中列上沒有任何刪除鈕；整個搜尋畫面的 `content-desc` 只有「返回」與「清除搜尋」）。
- **驗收 7**：**pass**（qa-h5／qa-h2a／qa-h2b 分別命中 5／2／2 → 摘要「3 段對話・9 則訊息」，畫面上列出 3 + 2 + 2 筆）。
- **驗收 8**：**pass**（三種離開都清空查詢：畫面返回鍵 ✓、系統返回鍵 ✓、點命中列 ✓，再次進入都是空欄位 + 引導文案；旋轉不是離開，查詢與結果都保留 ✓）。
- **驗收 9**：**pass**（Home → `am kill` → `pidof` 無輸出 → `am start` 回報 `its current task has been brought to the front` → 停在搜尋畫面、查詢為空、顯示引導文案。依條文為可接受狀態）。
- **驗收 10**：**pass**（逐碼位判定）。輸入 `"  kiwi  "` → 欄位 `['0x6b','0x69','0x77','0x69','0x20','0x20']`＝`"kiwi  "`；文案逐碼位為 `找不到符合「kiwi(U+0020)(U+0020)」的訊息`。TAB 失焦後欄位 `"kiwi"`、文案同步變成 `找不到符合「kiwi」的訊息`。（為了讓 kiwi 保證無命中，先把含 kiwi 的測資檔移除並冷啟動。）
- **驗收 11**：**pass**。整輪所有 dump 裡結果區只出現過三種樣子（引導文案／結果列表／找不到…），**沒有**任何進度指示、骨架列或「載入中」字樣。正向對照：同一次執行裡歷史載入完成後的查詢結果確實包含歷史裡的對話（例如「哈拉」6 段、「西瓜」3 段）。
- **驗收 12**：**pass**。欄位裡有查詢字串（「臺」）時，`shared_prefs/session.xml` 的內容只有 `last_thread_id` / `last_model` / `last_persona` 三個鍵，**找不到任何查詢字串**，鍵的組成沒有增加。正向對照：那組游標確實還在（冷啟動續接到 `last_thread_id` 指的那段）。
- **驗收 13**：**pass**。磁碟上放一個只含助理訊息的檔 `qa-x`（訊息「zebra only assistant said this」），同時開著**不同 id** 的 qa-long。搜 `zebra` → 出現一組（🧡 姊姊 Qwen2.5 0.5B 17:53）→ 點它 → **切換到那段舊對話**，聊天畫面顯示的是它的內容、頂端換成 🧡 姊姊 + Qwen2.5 0.5B。
- **驗收 14**：**pass**
  - 四種點擊情境各做一次，每一次都在**前置擺設完成之後**取基準線，且從基準線到操作結束之間沒有送出訊息或以任何方式改動內容：
    1. §6.2（另一段對話，被切走的是 qa-h5→qa-long 這類含使用者訊息的對話）→ `DISK CONTENT IDENTICAL`
    2. §6.3（目前這段）→ `DISK CONTENT IDENTICAL`
    3. §6.4（空白且未存檔那段；前置是先開 qa-long 再切人格，基準線在切人格之後才取）→ 15 檔 → 15 檔、`DISK CONTENT IDENTICAL`
    4. 驗收 13 那一次（被切走的是含使用者訊息的 qa-long）→ 16 檔 → 16 檔、`DISK CONTENT IDENTICAL`
  - 比對手法：`docs/qa-tools/jsondir.py`（把每個 JSON parse 後以排序過的正規形式列出再 diff），判的是**內容**不是位元組 —— 避開手寫 JSON 與 App 序列化結果位元組不同的陷阱。訊息集合、各則訊息的文字與作者、`updatedAt` 都在比對範圍內。
  - 結構那一半依 v3.10 已移除，本輪沒有驗它。

## 任務 9：回歸驗證與中文算繪的手動清單

- **驗收 2**：**pass**。`chat/TextRepair.kt` 全檔沒有 `flattenToLine` / `normalizeForSearch`，也沒有被它們引用（全專案 grep：這兩個函式的使用點只有 `ConversationSearch.kt`、`Conversation.kt`（截短用 `characterBoundaryAt*`）與 `ChatViewModel.kt` 的欄位規則）。
- **驗收 3**：**pass**。「送進模型的上下文」那道過濾在 `ChatViewModel.kt:244` 的 `.dropWhile { !it.fromUser }`（`send` 裡的行內寫法），**不是**搜尋也在用的那一份（搜尋走 `searchConversations` + `flattenToLine`），兩者沒有共用函式。
- **驗收 4**：**pass**，由任務 1 驗收 6 驗（見上）。
- **驗收 5**：**pass**，由任務 6 驗收 1 驗（見上）。
- **驗收 7**：**pass**（逐碼位）。同一則含 U+3000 的訊息：抽屜歷史列預覽讀到 `U+5317 U+4EAC **U+3000** U+5929 U+6C23 U+5982 U+4F55`（原樣保留 U+3000）；搜尋片段讀到 `U+5317 U+4EAC **U+0020** U+5929 U+6C23 U+5982 U+4F55`（收成一個半形空白）。兩份攤平規則確實不同。
- **驗收 8**：**pass**，由任務 7 驗收 2／6（標示）、任務 7 驗收 5／6（不破版）、任務 11 驗收 2（摘要數字與列出的筆數）、任務 7 驗收 3（多段同時出現）承接；本條未另外跑一次，被指過去的那幾條全部 pass。
- **驗收 9**：**pass**（與任務 7 驗收 6 同一次觀察）。qa-cjk 那一列的片段**前後兩端都看得到「…」**：`…測測測測測測測🌧，測試標點。測測測測測北京測測…（後略）…`。
- **驗收 10**：**pass**。用注音輸入法打同一組鍵序（ㄊㄞˊ）分別選「台」與「臺」：「台」（U+53F0）只命中「這裡有一個平台」，「臺」（U+81FA）只命中「我住在臺北市」，兩者結果不同 —— 經過輸入法之後它們仍是兩個不同的字。
- **驗收 11**：**pass**。測資由檔案推入（不經過輸入法）：一則「測資一：北京(U+3000)天氣很好」、一則「測資二：北京(U+00A0)天氣很好」。操作時用注音輸入法打「北京」，中間按**一般半形空白鍵**（`input keyevent 62`，欄位逐碼位確認是 `0x20`），再打「天氣」。預期成立：兩則都命中（摘要「2 段對話・3 則訊息」），截圖可見標示**恰好蓋住整個「北京 天氣」詞組，含中間那一個空白位置**。
- **驗收 12（選作）**：**本環境無法執行**，不 fail。與任務 12 驗收 1 的「貼上單一 U+3000 / U+00A0」卡在同一件事上，一起試的：見下方任務 12 驗收 1 的失敗步驟說明。查詢端 U+3000 的語意權威在任務 3 驗收 19（pass）。
- **驗收 13**：**pass**。上面 8–11 的判讀全部在 `mInputShown=true`（鍵盤保持彈出）的狀態下做，沒有為了看清楚而先收鍵盤；同時複驗 §7.1（見任務 7 驗收 8）。

## 任務 10：更新架構文件

（`plan/architecture.html`，冷讀核對。）

- **驗收 1**：**pass**。「搜尋」那一節有 note「輸入框裡留下什麼，跟拿去比對的是兩回事」，寫到：開頭空白一打就消失、尾端空白要等失去焦點才移除、中間完全不動、拿去比對的一律是整形過的文字。
- **驗收 2**：**pass**。規則對照表「（只有空白）→ 不算搜尋」那一列補著「這是比對層的契約：搜尋框本身打不出這種輸入（見下），但比對層仍然必須答對」。
- **驗收 3**：**pass**（三件事都在）。(i)「不變條件」那一條現況是「對話的『最後更新時間』只跟著內容走。新增訊息、訊息文字改變、訊息被移除才算一次更新；切過去看一眼、切走都不算。否則『最近』會變成『最近被看過』，而歷史清單與搜尋結果的排序都靠這個時間。」—— 原則、不算的那一側、理由三件齊全（以意思判）。(ii)「不算」那一側**沒有「按下停止」四個字**。(iii) 全檔 grep：沒有另一處宣稱按下停止不算一次更新（「停止」只出現在訊息生命週期那一節描述提早中斷的分支與標記）。
- **驗收 4**：**pass**（三列逐列判）。`ui/SearchScreen.kt`「搜尋輸入與命中列表。自己不持有狀態，也不決定什麼算命中」→ 路徑存在；該檔是無狀態 composable（只收 state 與回呼）、不含任何比對或整形程式碼 → 相符。`chat/ConversationSearch.kt`「什麼算命中、片段怎麼裁、標示要落在哪幾格」→ 路徑存在；該檔正是這三件事的所在 → 相符（欄位空白規則也在同一檔，與空白定義同源，不牴觸這句職責描述）。`data/ConversationRepository.kt`「對話從哪裡來、到哪裡去。聊天層唯一的入口」→ 路徑存在；全專案只有 `ChatViewModel` 引用它，`ConversationStore` 只在它裡面被建構 → 相符。
- **驗收 5**：**pass**。全檔 grep 不到「最後開啟時間」或任何第二個時間概念的描述。
- **驗收 7**：**pass**。描述片段視窗那一句現況含第二個分支：「每則命中只顯示一個 60 個字的視窗：命中前面留 20 個字當引子。**命中本身有 60 個字（含）以上時裝不下引子，視窗就從命中的第一個字開始。**被裁掉的兩端各補一個刪節號。」→「命中長度剛好 60 時前面留幾個引子字元」有唯一答案，答案是 **0**。

## 任務 11：摘要的兩個數字維持現狀

- **驗收 2**：**pass**（在裝置上：5／2／2 → 「3 段對話・9 則訊息」，畫面列出 3 + 2 + 2 筆）。
- **驗收 3**：**pass**（空查詢時只有引導文案、無摘要列；無命中時只有「找不到…」、無摘要列）。

## 任務 12：查詢欄位的空白規則

- **驗收 1**：**pass**
  - 主條文：空欄位按一次空白鍵（`input keyevent 62`）→ 逐碼位讀回欄位仍為空 `[]`、仍顯示引導文案、**沒有**「找不到」。
  - **長按空白鍵：先試了真正的動作。** 做法：`adb shell input swipe <空白鍵中心> <同座標> 1500`。**失敗發生在哪一步**：長按之後畫面上跳出 Gboard 的「Change keyboard」對話框（列出 `English (US) / QWERTY` 與 `繁體中文 (台灣) / 注音` 兩列），**欄位一個字元都沒有收到**（對話框蓋住欄位，dump 當下讀不到 EditText；`input keyevent 4` 關掉對話框後欄位仍為空）。→ 走條文允許的替代做法：改用「空欄位連續收到多個空白字元輸入」，兩種都做了 —— (a) 三次獨立的 `input keyevent 62`（間隔 1 秒），(b) 單一次 `input text "%s%s%s"`。兩者的結果都是：欄位仍為空 `[]`、仍顯示引導文案、仍不顯示「找不到」。**不判 fail。**
  - **貼上單一 U+3000 / U+00A0：先試了真正的動作，做不出來，記「本環境無法觀察」，不判 fail。** **失敗發生在哪一步**：卡在「那兩個字元根本產生不出來」，因此連進不了剪貼簿。這一輪實際試過的方式與各自卡住的地方：
    1. `adb shell "input text '<字元>'"`（裝置端加引號）與 `adb shell input text <字元>`（argv 直送）兩種寫法，對 U+00A0 與 U+3000 各試一次 → **四次全部回結束碼 255 並拋 `java.lang.NullPointerException: Attempt to get length of null array`（`InputShellCommand.sendText`）**，逐碼位讀欄位完全沒變。
    2. 找鍵盤上的鍵 → 這一輪逐頁看過**六張鍵盤圖**：英文版面的主鍵盤、`?123`、`=\<`，以及注音版面的主鍵盤、`?123`、`=\<` —— **沒有任何一鍵是 U+3000 或 U+00A0**。
    3. `adb shell cmd clipboard` → `No shell command implementation.`（沒有這條路可以直接設剪貼簿）。
    - 這件事另有一條不經過畫面的驗收在守：本任務驗收 6（以 U+00A0 開頭的輸入必須被移除），該條 pass。
  - **回報給 PM**：這一輪環境對「長按空白鍵」與「貼上單一 U+3000 / U+00A0」都**仍然做不到**，成因如上（與 v3.20 那一輪記的成因一致：字元生不出來，不是剪貼簿設不了）。
- **驗收 2**：**pass，而且走的是真正的「貼上」，沒有用替代做法。**
  - 做法：在聊天輸入框（會原樣保留內容的欄位）用注音輸入法打出「(3 個半形空白)北京」，逐碼位確認是 `['0x20','0x20','0x20','0x5317','0x4eac']` → 長按 → 文字選取工具列的「Select all」→「Copy」→ 清空該欄位 → 進搜尋畫面 → 長按空的搜尋框 → 點「Paste」。
  - 結果：搜尋欄位逐碼位讀回 `['0x5317','0x4eac']`＝「北京」，開頭三個空白**沒有留下**；同時結果列變成「4 段對話・5 則訊息」。
- **驗收 3**：**pass**。欄位為「北京(2 個空白)」（逐碼位 `0x5317 0x4eac 0x20 0x20`）時，把游標移到最前面連按兩次 `FORWARD_DEL` 刪掉「北京」→ 欄位變成空 `[]`、回到未搜尋狀態（引導文案），**沒有**顯示「找不到符合『  』的訊息」。
- **驗收 4**：**pass**。輸入「北京」→ 按一次空白鍵 → 欄位 `0x5317 0x4eac 0x20`（尾端空白留著）→ 再輸入「天氣」→ 欄位 `0x5317 0x4eac 0x20 0x5929 0x6c23`＝「北京 天氣」，且該查詢命中含「北京 天氣」的訊息（qa-u3000 的 U+3000 版、qa-space 的 U+3000 與 U+00A0 版，摘要「2 段對話・3 則訊息」）。
- **驗收 5**：**pass**（環境做得到讓輸入框失焦，用 `input keyevent 61`(TAB)）。失焦後欄位由「北京  」變成「北京」；重新點欄位取得焦點後仍是「北京」，**沒有把尾端空白加回來**。
- **驗收 6**：**pass**。`the field uses our whitespace rule, not the built-in one`：`assertEquals("北京", " 北京".withoutLeadingSpace())`（U+00A0 必須被移除）；`assertNotEquals("北京".trim(), "北京".withoutLeadingSpace())`，而同一個方法斷言 `"北京".trim() == "北京"` → 合起來等於「U+001C 開頭沒有被移除」。
- **驗收 7**：**pass**（同任務 8 驗收 10，逐碼位）。
- **驗收 8**：**pass**。欄位為「北京  」時記下結果與摘要（27 行節點），使輸入框失焦後再取一次，`diff` **完全相同**；同一輪裡把游標移到最前面按空白鍵（開頭移除）前後摘要也維持「4 段對話・5 則訊息」。
- **驗收 9**：**pass**。比對層的空查詢契約測試（`""`、`"   "`、`"\n\t"`、U+3000、U+00A0）仍存在且通過（`an empty query is not a search`、`a query of nothing but whitespace is not a search`），沒有被刪除。
- **驗收 12**：**pass**（兩半都做了）。畫面那一層：欄位為「北京」時 `MOVE_HOME` + 空白鍵 → 逐碼位仍是 `0x5317 0x4eac`；正向對照 `MOVE_END` + 空白鍵 → 變成 `0x5317 0x4eac 0x20`。不經過畫面那一層：`a leading space never survives an edit` 斷言 `"   北京".withoutLeadingSpace() == "北京"`（見下方「問題歸屬」對這條測資的說明）。

## 任務 13：對話「最後更新時間」的定義

- **驗收 1（§8.3）**：**pass**。A=qa-long（剛送出訊息、18:06）、B=qa-u3000（17:24），共同的字「哈拉」。從抽屜開 B 再開回 A 之後：搜尋分組順序與每個標頭的時間文字 `diff` **完全相同**；抽屜歷史列表的順序與時間文字 `diff` **完全相同**；磁碟內容也完全相同。
- **驗收 2（§8.4）**：**pass**。切換離開 A（沒有內容改變）之後，`jsondir` 前後 `DISK CONTENT IDENTICAL`（A 的訊息集合、文字、作者與 `updatedAt` 都沒變）。
- **驗收 3（§8.6）**：**pass，兩個預期都判了。**
  - 前置：Gemma3 1B，提示 `Write a very long detailed story about the sea, at least 600 words`；送出後 1.5 秒按停止，停止當下畫面上那則回覆**已經有文字**。
  - 預期一（文字）：存檔中那則被停止的回覆結尾是 `'’s about…（已停止）'` —— 結尾就是中斷標記。（本輪核對時 `chat/ReplyEnding.kt` 的現行定義是 `streamed + "…（已停止）"`。）
  - 預期二（時間）：等待 35 秒不做任何事後切換到另一段對話。存檔 `updatedAt = 1789553498505`，切換時刻（取裝置時鐘）`1789553632931`，**早 134.4 秒**，遠超過 30 秒門檻。
- **驗收 4（§8.5，不得修過頭）**：**pass**。在 qa-long 送出「hi」並收到完整回覆後：`updatedAt` 由 `1789550965076` 前進到 `1789553201019`（約當回覆完成時刻）；該對話在搜尋結果排到最前（標頭 18:06，第一組），在抽屜歷史列表也排到最前（捲到頂端後第一列是 18:06）。刪除、切換人格、切換模型、開新對話等既有流程本輪全部照常運作。
- **驗收 5（§8.7）**：**pass**。B = 老師人格的一段對話，**剛剛更新過**（送出「hello there」並等回覆結束，`updatedAt` 18:31:18）。回覆結束後**等待 70 秒**，再用「開始新的對話」產生只有開場白的 N。進入搜尋查「慢」（老師開場白裡的字）→ N 與 B 都出現；**N 的分組排在 B 前面**；N 標頭時間 `18:33`，**晚於** B 的 `18:31`。
- **驗收 6**：**pass**（程式碼冷讀）。全專案只有一處會寫 `updatedAt = 現在`：`ChatViewModel.updateThread`（第 391–401 行）裡 `if (updated.hasSameContentAs(state.conversation)) updated else updated.copy(updatedAt = System.currentTimeMillis())`。所有會改動內容的路徑都經過它：`send`(248)、`settle` 的 Drop 分支(314)、`stop`(344)、`updateMessage`(374)。另一處 `updatedAt = now` 在 `newThread`(227)，那是 §8.7 的「建立就是它自己的第一次更新」，不是第二個「什麼算一次更新」的判斷處；其餘 `copy(conversation = ...)` 的地方（冷啟動還原、開新對話、開啟對話）都是整段換掉，不蓋時間。
- **驗收 7**：**pass，兩條可判定都成立。**
  - **可判定一**：模型 **Gemma3 1B**，提示 `Write a very long detailed story about the sea, at least 600 words`；每一次切走時回覆大約已出現 20–60 個字（送出後約 1.3–1.5 秒）。四種離開各用**一段新的 S**，每一次切走之後**當場讀存檔**：
    | 離開方式 | S | 存檔內容 |
    |---|---|---|
    | 從抽屜開另一段 | `c14e3d23…` | 使用者訊息在；回覆結尾 `'。我記得…（已停止）'` |
    | 開始新的對話 | `263ebf8b…` | 使用者訊息在；回覆結尾 `'幕上模糊…（已停止）'` |
    | 切換人格 | `15430f4e…` | 使用者訊息在；回覆結尾 `'貝殼，還…（已停止）'` |
    | 切換模型 | `c978d883…` | 使用者訊息在；回覆結尾 `'，他說畫…（已停止）'` |
    四次的結尾都是中斷標記 → 四次都確實是在串流中切走的，**沒有一次要重做**。四種做完之後 `am force-stop` + 冷啟動一次（**只做這一次**），從抽屜依預覽把四段 S 各打開一次 → 每一段畫面上都有使用者那則訊息與以中斷標記結尾的回覆。
  - **可判定二**：在一段對話送出「pineapple river」、等回覆結束、**不做任何其他操作**，直接 `am force-stop` 再冷啟動 → 續接到那段對話；畫面上看得到使用者那則訊息（捲上去可見的 `pineapple river` 泡泡）與完整的回覆（結尾不是中斷標記），存檔也相符。
- **驗收 8（回歸）**：**pass**（六項逐項判，全部做得完並出現該操作的直接結果）
  - 送出：使用者訊息與回覆都出現 ✓
  - 串流：回覆逐步增加直到結束 ✓（連續觀察到回覆長度增加，且搜尋命中數在串流期間由 16 變 17）
  - 停止：按下後回覆不再增加、結尾補上中斷標記 ✓
  - 刪除：那一列從抽屜消失、檔案少一個 ✓
  - 切換人格／模型：各開了一段新的對話，頂端換成選的人格（🧡 姊姊）或模型（SmolLM 135M・離線）✓
  - 冷啟動續接：回到上次停的那段對話 ✓（多次）
  - 沒有任何一項出錯或崩潰。

## 任務 14：讓補上來的條文各自有東西在驗

- **驗收 1**：**pass**（九條逐條指得出怎麼驗的）

| 條文 | 怎麼被驗的 |
|---|---|
| 任務 1 驗收 10 | 程式碼位置 `ConversationRepository.find` + 裝置步驟（存在／不存在的 id 各一次冷啟動） |
| 任務 3 驗收 25 | 測試方法 `ellipses appear only where text was actually cut` |
| 任務 5 驗收 8 | 裝置步驟：進搜尋→打字到有結果→離開，前後 `jsondir`/`hashdir` diff + pid 過濾的 logcat + 狀態列 |
| 任務 6 驗收 9 | 裝置步驟：注音打「北」→讀摘要→打「京」→讀摘要；同一次 dump 清點畫面上的控制項 |
| 任務 7 驗收 11 | 裝置步驟：一次搜尋的 dump 裡逐組讀標頭四樣並比較不同人格／模型的組 |
| 任務 8 驗收 11 | 裝置步驟：整輪每一次 dump 都檢查結果區只有三種樣子；正向對照是歷史裡的對話有出現在結果中 |
| 任務 8 驗收 12 | 裝置步驟：欄位有查詢字串時 `cat shared_prefs/session.xml`；正向對照是冷啟動續接 |
| 任務 8 驗收 13 | 裝置步驟：推 `qa-x`（只含助理訊息）+ 開著不同 id → 搜 `zebra` → 點擊 |
| 任務 8 驗收 14 | 裝置步驟：四種點擊情境，各自在前置之後取 `jsondir` 基準線再 diff |

- **驗收 2**：**pass**。可自動化的只有任務 3 驗收 25，它說得出什麼樣的實作會讓它變紅：**無條件在片段頭尾補「…」**的實作會讓前半（短訊息 `assertEquals("我要去北京", snippet)` / `assertFalse(contains("…"))`）變紅；**從不補「…」**的實作會讓後半（長訊息 `assertTrue(startsWith("…"))`／`endsWith("…")`）變紅。
- **驗收 3**：**pass**。配了正向對照的六條，正向與否定兩半本輪都實際執行到：任務 3 驗收 25（短／長兩半同一個測試方法內）✓；任務 5 驗收 8（沒有讀寫／查詢確實非空）✓；任務 6 驗收 9（結果有出現並改變／沒有按了才搜的控制項）✓；任務 7 驗收 11（標頭四樣都在／換對話時跟著變）✓；任務 8 驗收 11（沒有第四種樣子／結果包含歷史對話）✓；任務 8 驗收 12（找不到查詢字串／游標確實還在）✓。

## 任務 15：`docs/product/features.md` 指向本規格的引用仍然追得到

- **驗收 1**：**pass**。列出 `features.md` 裡每一處路徑含 `docs/specs/conversation-search/` 的引用（同一格多個節號逐個算），共 **41 處**，逐處到 `v3.23/`（版號最大的目錄）底下該引用寫出的檔案裡判三件事：節號存在、節名 grep 得到、節號與節名落在同一節 —— **41 處全部三件都成立，0 處對不上**。
- **驗收 2**：**pass**。`搜尋-02`（→ §1.7「即時，沒有送出鈕」）、`搜尋-13`（→ §1.2「只有訊息本文參與比對」）、`搜尋-20`（→ §2.8「查詢欄位裡留下什麼」）三列都仍然帶著指向本規格的引用，沒有被改成「尚無 spec」也沒有留空。
- **驗收 3**：**pass**。41 處引用的路徑都是 `docs/specs/conversation-search/<檔名>`，**沒有任何一處含版號目錄**。

## 任務 16：只含助理訊息的既有對話檔，打開再離開就清掉

- **驗收 2**：**pass**（全部子項）
  - X（只含助理訊息、id 與開著那段不同）出現在歷史清單上，標題是「還沒說話」；點它 → 畫面換成 X 的內容。
  - 四種離開各走一次（每一次重新放一個 X）：開另一段 → X 的檔不在 ✓；開始新的對話 → 不在 ✓；換人格 → 不在 ✓；換模型 → 不在 ✓。
  - 冷啟動入口：把 `last_thread_id` 改成 `qa-x`、冷啟動 → 續接到 X（X 的檔在冷啟動後仍在）→ 不點任何東西直接從抽屜開另一段 → X 的檔不在 ✓。
  - 訊息清單為空的檔（`qa-empty`，`messages: []`）：放一個、點開（聊天區是空的）、再開另一段 → 它的檔不在 ✓。
  - 冷啟動後歷史清單與搜尋都找不到 X：冷啟動 + 捲完整個歷史列表 → 沒有「還沒說話」那一列；搜 `zebra` → 「找不到符合「zebra」的訊息」✓。
  - **正向對照**：同一次操作裡，含使用者訊息的對話檔 Y（`qa-short`，外部放進去的）點開再開另一段 → Y 的檔仍在，訊息與放進去時相同 ✓。
- **驗收 3**：**pass**。A=qa-short（開著）、B=qa-old（有內容）。刪掉 B → 對話檔由 16 個變 15 個、`qa-old.json` 不在；`jsondir` 的 diff 只有 qa-old 那一行消失，**A 的檔仍在、內容與刪除前完全相同**（其餘 14 段也都相同）。冷啟動後歷史清單裡沒有 B、搜「昨」→「找不到符合「昨」的訊息」。
- **驗收 4**：**pass**。換人格產生只有開場白的對話，分別用四種方式離開（開另一段／開始新的對話／換人格／換模型），每一次離開前後 `jsondir` 都 `IDENTICAL`（檔案數量 19→19，內容完全相同）。**正向對照**：在一段新開的對話送出「hello there」、等回覆結束再離開 → 對話檔由 19 變 20，那段對話的檔確實存在。
- **驗收 5**：**pass**。`features.md` 保存-02 寫著「一句話都沒說過的對話**不會被存起來**，也不會出現在歷史清單裡。**例外**：從 App 以外放進手機、裡面沒有任何一句你說的話的對話檔（只有助理的話，或完全沒有訊息），會出現在歷史清單裡（標題「還沒說話」），**但打開再離開就會被清掉，不另外提示** —— 這種檔被當成髒資料。」兩件事都寫到了；那一格指向本規格的引用（§9.1、§6.4）依任務 15 三條判定全部通過。

## 任務 17：抽屜標題／預覽截短不得把一個字切成兩半

- **驗收 1**：**pass**。`a title cut in the middle of an emoji keeps no half of it`（25 個 BMP 字 ＋ 🌧 ＋ 尾巴）與 `a preview cut in the middle of an emoji keeps no half of it`（39 ＋ 🌧 ＋ 尾巴），都斷言：沒有落單代理字元（`hasLoneSurrogate()` 為 false）、尾端是「…」、「…」前面那段是原文開頭（`first.startsWith(kept)`）、**那個 emoji 之前的 25／39 個字全部都在**（`kept.startsWith("字".repeat(25/39))`）。
- **驗收 2**：**pass**。`an emoji that exactly fills the limit is kept whole with no ellipsis`（24 字＋🌧＝26 units；38 字＋🌧＝40 units）→ 標題／預覽都是整句、emoji 完整、沒有「…」。
- **驗收 3**：**pass**。`guarding emoji leaves the rest of the title and preview rules as they were`：長一般文字有「…」、剛好等於上限的一般文字整句沒有「…」、換行變空白、**U+3000 原樣保留沒有被收成半形空白**、沒有使用者訊息的對話標題是「還沒說話」。
- **驗收 4（在裝置上）**：**pass**（逐碼位）。推入 `qa-rain`（標題＝25 字＋🌧＋尾巴、預覽＝39 字＋🌧＋尾巴），打開抽屜那一列讀到：標題 `U+5B57 ×25 U+2026`、預覽 `U+5B57 ×39 U+2026` —— 尾端是完整的字接「…」，**沒有落單代理、沒有方框或問號**，🌧 之前的 25／39 個字全部都在。**uiautomator dump 在那一列可見時完全正常，沒有崩潰**（logcat 無 `Bad surrogate pair`）。
- **驗收 5（在裝置上）**：**pass**。同一次操作裡：`qa-long`（第一句與最後一則都是很長的英文句子）→ 標題 `this is a very long first …`、預覽 `this is a very long last message used as…`，**尾端都看得到「…」**；`qa-short`（都是「你好」）→ 標題與預覽都是整句、**尾端都沒有「…」**。
- **驗收 6（組合 emoji，不經過裝置）**：**pass**。`a title cut inside a combined emoji keeps all of it or none of it` / `a preview cut inside...`：三類字（👨‍👩‍👧、🇹🇼、👍🏽）各一個案例，標題用 24 個 BMP 字、預覽用 38 個，斷言結果只能是 `lead…` 或 `lead<完整的那個字>…`（**不得只剩 👨、🇹、或沒有膚色的 👍**），預期直接寫在測試裡。「前面那個字也要在」：`stepping out of a combined emoji keeps the character before it`（21 字＋👍🏽＋👨‍👩‍👧）。「剛好放得下」：`a combined emoji that exactly fills the limit is kept whole with no ellipsis`（18／32 字＋👨‍👩‍👧）。「極端輸入」：`a single character longer than the limit is not cut down to a bare ellipsis`（`e` ＋ 40／60 個 U+0301，斷言 `startsWith(longForTitle)`，**不是只剩「…」**）。
- **驗收 7（在裝置上，組合 emoji）**：**pass**（逐碼位）。推入兩段對話：`qa-zwj`（第一句＝24 字＋👨‍👩‍👧＋尾巴）與 `qa-flag`（最後一則＝38 字＋🇹🇼＋尾巴）。抽屜讀到：qa-zwj 標題 `U+5B57 ×24 U+2026`（「…」前面是完整的一般字，**不是落單的 👨**）；qa-flag 預覽 `U+5B57 ×38 U+2026`（**不是落單的 🇹**）。兩者都落在驗收 6 預期的其中一種（「只有那 24／38 個 BMP 字」那一種），與驗收 6 的預期一致。另外 qa-zwj 的預覽（24 字＋👨‍👩‍👧＋5 字＝37 units，未達上限）整串完整顯示，讀到 `U+1F468 U+200D U+1F469 U+200D U+1F467` —— 家庭 emoji 五個碼位齊全。

## 任務 18：搜尋片段不得把一個字切成兩半

- **驗收 1（起點，不經過裝置）**：**pass**。`a window starting inside a combined emoji starts at a whole character`：三類字各一個案例，形狀與條文指定相同（5 個「甲」＋字＋14／18 個「乙」＋`kiwi`＋50 個「丙」），斷言片段以「…」開頭、之後是**完整的那個字接那串「乙」或直接從第一個「乙」開始**（兩種可接受的字串都寫在測試裡），並斷言 `snippet.contains("kiwi")` 與標示恰好蓋住 `kiwi`。
- **驗收 2（終點，不經過裝置）**：**pass**。`a window ending inside a combined emoji ends at a whole character`：30 個「甲」＋`kiwi`＋34 個「乙」＋字＋30 個「丙」，斷言片段以「…」結尾、之前是完整的那個字或它前面的最後一個「乙」，並斷言 kiwi 在片段裡且標示正確。
- **驗收 3（不得修過頭）**：**pass**。`a window already between two characters is not moved`（3 甲＋👍🏽＋👍🏽… 起點不動、30 甲＋kiwi＋32 乙＋🇹🇼＋🇯🇵 終點落在兩面國旗之間不動）；`whole-character cropping leaves the plain window rules as they were`（§4.2 三條界線 L=59/60/61 的片段與 §4.3 短訊息不補「…」都再驗一次）；下限由 `cropping never splits a surrogate pair`（連續 15 個單一 emoji）與 `...at the window end`（連續 20 個）守著，兩者都斷言片段完整含有查詢詞。
- **驗收 4（一個字長到視窗裝不下）**：**pass**。`a character longer than the window still leaves the hit on screen`：100 個「甲」＋`e`＋80 個 U+0301＋`kiwi`＋30 個「丙」，斷言片段**不為空**、**含有命中起點那個字「k」**，而且沒有把那個長字切成兩半（`contains(long) || !contains(accent)`）。
- **驗收 5（在裝置上）**：**pass**（逐碼位讀命中列）。推入 `qa-frag`（兩則訊息分別是驗收 1 的 👨‍👩‍👧 形狀與驗收 2 的 🇹🇼 形狀），進搜尋查 `kiwi`：
  - 起點形狀那一列讀到 `U+2026 U+4E59×14 U+006B U+0069 U+0077 U+0069 U+4E19…`（「…」之後是第一個「乙」，**不是 ZWJ 也不是 👩**）；
  - 終點形狀那一列讀到 `U+2026 U+7532×20 U+006B U+0069 U+0077 U+0069 U+4E59×34 U+2026`（「…」之前是「乙」，**不是落單的 🇹**）。
  - 兩者都落在驗收 1、2 預期的其中一種，與不經過裝置的那一層一致。

## 任務 19：`docs/product/features.md` 的「未經查證」不綁死在某一種驗收裝置上

- **驗收 1**：**pass**。冷讀「「※未經查證」是什麼意思」那一節，對「某一項如果有人在模擬器上開 App 確認過，它算不算已經查證？」該節給得出答案，而且答案是「**算**」——「**「查證」看的是有沒有人實際開 App 確認過，不看那是什麼裝置。** 實體手機、模擬器都算 —— 只要那一項真的被操作過、被看過，它就不是「未經查證」。」
- **驗收 2**：**pass**。該節仍然存在，而且兩件事都說了：(i)「**看到這個標記，就表示那一項還需要有人實際開 App 確認過。**」；(ii)「這份文件是讀程式碼與既有文件寫出來的」。

---

# 邊界情況檢查

`spec-tasks.md`／「明確不做」列出的每一條，逐條說明本輪怎麼處理：

| 明確不做 | 本輪怎麼處理 |
|---|---|
| 正在看的那段對話不能直接刪，本規格不提供那個入口 | **確認現況相符**：刪除鈕只出現在抽屜歷史列上（`content-desc="刪除這段對話"` 只在歷史列裡），而歷史列表不含開著的那段（任務 6 驗收 1 (vi) 已驗）→ 畫面上確實沒有刪除正在看那段的入口。依 v3.18 裁決不判它。 |
| 任務 16 的行為不要求自動化測試 | 沒有去找那個測試；改為每輪在裝置上走一次（任務 16 驗收 2，本輪走完五個入口＋正向對照）。 |
| 抽屜標題／預覽截短時切開 emoji（已觸發，改由任務 17 處理） | 依現行結論由任務 17 驗，本輪驗收 1–7 全部 pass。 |
| 驗收裝置不認得的、或各版本斷法不同的較新 emoji 不判 | 本輪只用 🌧(U+1F327)、👨‍👩‍👧、🇹🇼、👍🏽、`e`+U+0301 這幾種斷法穩定的序列；裝置上逐碼位讀到的都與不經過裝置的預期一致，**沒有遇到需要動用這條豁免的情形**。 |
| 命中落在一個字中間時，標示只蓋住命中的那一部分 | 本輪沒有拿這種查詢去判 fail（也沒有造這種輸入）。 |
| 片段的 60 與 20 仍以 code unit 計 | 任務 18 驗收 3 的 §4.2 三條界線測資正是以 code unit 計的，判定照舊。 |
| 對話檔的檔名與檔裡的 id 不一致 | 本輪推入的每個測資檔，檔名與檔裡的 id 都一致（例如 `qa-x.json` / `"id":"qa-x"`），**刻意沒有造出不一致的檔**，所以沒有碰到這個洞。 |
| 依 id 取一筆會讀完並解析全部檔案 | 這是既有低效，不判；任務 1 驗收 10 驗的是兩條路徑答案一致，本輪 pass。 |
| 沒有索引、沒有快取 | 15–20 段對話下邊打邊篩沒有可觀察的卡頓（每次結果在 2 秒內出現）。 |
| 旗標式導覽，不引入導覽框架 | 依 v3.11 已無驗收條文，不判。 |
| 點擊命中後不會捲動到命中的那一則訊息 | 觀察到的行為與此一致（點擊後停在那段對話，沒有捲到命中那一則），**不判為缺陷**。 |
| 篩選、多詞 AND、正規表示式、繁簡互搜、拼音 | 任務 3 驗收 21（不是 AND）與驗收 4／任務 9 驗收 10（不做繁簡）本輪都 pass。 |
| 比較畫面的輸出不可搜尋 | 比較畫面跑完的結果沒有進歷史、沒有產生對話檔（比較之後的搜尋結果沒有多出任何分組）。 |
| 比較畫面的兩個既有缺陷（系統返回鍵、旋轉）不修 | 依 v3.20 **不再有條文判它們還在不在**，本輪沒有去測，也沒有拿它們判 fail。 |
| 橫向 + 鍵盤彈出時結果區幾乎沒有空間 | 任務 6 驗收 8 明文排除，本輪橫向只判「仍在搜尋畫面、查詢與結果都在、輸入框在最上方」。 |
| 對話標題／預覽的攤平規則與搜尋不同，不統一 | 任務 9 驗收 7 正是驗這個已知差異，本輪逐碼位確認差異仍在（U+3000 vs U+0020）。 |
| 對話存取層沒有自動化測試 | 沒有去找它的測試；它的行為由任務 1 驗收 6／10 在裝置上驗。 |
| 摘要的兩個數字與「還有 N 則符合」留在畫面上自己算 | 任務 11 驗收 2、3 只判畫面顯示的值，本輪 pass。 |
| 比對層那兩個派生的量：留著，也不要順手改用它們 | **依條文沒有任何驗收每輪檢查它**，本輪照樣不判。（順帶冷讀到 `SearchUiState.matchedConversationCount` / `matchedMessageTotal` 仍然存在、畫面仍自己從 `results` 算 —— 只是記錄，不構成判定。） |
| 任務 14 的核對不得要求任何行為改變 | 本輪任務 14 的核對沒有要求任何實作改動，也沒有任何一條因此變紅。 |
| 不回頭替 spec 內部每一處引用補節名 | 不判。 |
| `features.md` 裡指向 `stop-generation` 的引用不由本規格的驗收守 | 任務 15 的 41 處只取路徑含 `conversation-search/` 的，**沒有**把指向別輪的引用算進來。 |
| 不新增「最後開啟時間」第二個時間概念 | 任務 10 驗收 5 全檔 grep 確認沒有第二個時間概念；程式碼裡也只有一個 `updatedAt`。 |
| 尾端空白的移除只綁在失去焦點 | 任務 12 驗收 5 觀察到的正是失焦才移除；打字過程中（含按 IME 動作鍵之外的任何操作）都留著。 |
| 時間戳只改「寫下去的時間值怎麼來」，不改「什麼時候寫檔」 | 任務 13 驗收 7 守這條邊界，本輪兩條可判定都 pass（切走與回覆結束的存檔時機都沒有壞）。 |
| 不把「規格裡引用既有函式／環境行為的宣稱都要有測試釘住」升格 | 不判。 |
| 這份 spec 不自己登記檔案的基準雜湊 | 不判；本輪的建置佐證用的是 git + `lastUpdateTime` + APK 大小。 |
| §1.7 與 §6.5 的覆蓋缺口 | §1.7 由任務 6 驗收 9 守（pass）；**§6.5 依 v3.10 裁決沒有任何驗收，本輪沒有判它**。 |
| 不把 `spec-architecture.md` 剩下的建議刪掉 | 我不驗架構決策。 |

---

# 如果 fail：問題歸屬

**本輪沒有 fail。** 以下兩件事不是 fail，但要讓 PM 知道：

1. **（給 PM，條文的鑑別力，不是 fail）任務 12 驗收 12 的「不經過畫面那一層」指名的測資，測試裡沒有逐字對應。** 條文寫「對輸入「**(1 個空白)北京**」必須給出「北京」」，而 `a leading space never survives an edit` 斷言的是 `"   北京"`（**3 個空白**）與 `" "`、`"　"`、`"  "`（空欄位的情形）。性質相同，但**一個只在連續 ≥2 個空白時才移除開頭空白的實作，會通過現有測試而違反條文指名的那一個案例**。本輪判 pass 的理由是：畫面那一半我在裝置上**就是用一個空白**做的（游標移到最前面按一次空白鍵 → 欄位仍是「北京」），所以那個案例端到端被覆蓋了。**建議 PM 讓 RD 在測試裡補一個 `assertEquals("北京", " 北京".withoutLeadingSpace())`**，不然這條的不經過畫面那一層會靠畫面那一層撐著。

2. **（給 PM／RD，同上類型）任務 12 驗收 6 的 U+001C 方向用的是 `assertNotEquals`。** 現行斷言是 `assertNotEquals("北京".trim(), "北京".withoutLeadingSpace())`，配合同一方法裡 `assertEquals("北京", "北京".trim())`，確實抓得到「U+001C 被移除」這個錯。但它抓不到「移除了別的東西」（例如回傳 `"京"` 的實作照樣通過）。條文要驗的性質（必須不被移除）有被守住，所以判 pass；**改成 `assertEquals("北京", "北京".withoutLeadingSpace())` 會更緊**，供 PM 斟酌。

**另外兩件依條文明文處理、不判 fail 的**：任務 12 驗收 1 的「貼上單一 U+3000 / U+00A0」記「本環境無法觀察」（失敗的那一步已在條文要求的細度上指名，見該條），由任務 12 驗收 6 承接；任務 9 驗收 12 記「本環境無法執行」，語意權威在任務 3 驗收 19。

**規格端沒有發現的問題**：我沒有在 `spec.md` 或 `spec-tasks.md` 找到驗錯對象、不可達、或「規則沒有條文守」的條文。**架構那一份我不驗**；為了確認規格有沒有搬乾淨，我抽讀了 `spec-architecture.md` 的卷首與 §A–§B，讀到的每一條都明寫「為什麼它只是建議」並指出它的可觀察面已經在行為規格或驗收條文裡（例如 §A.2 指向標示蓋錯字與該命中沒命中、§B.2 指向 §1.8）——**沒有讀到夾帶使用者看得見後果的硬性規則**。

---

# 驗證方式說明

「每一輪都要做的六件事」逐項：

1. **跑專案的標準測試指令**：**做了**。`./gradlew clean testDebugUnitTest`，log 同時出現 `> Task :app:clean` 與 `> Task :app:testDebugUnitTest`、無 `UP-TO-DATE`／`FROM-CACHE`（25 actionable tasks: 25 executed），逐檔讀 XML：9 個類別 136 個測試，`failures=0 errors=0 skipped=0`，timestamp 是當下。
2. **每一條指名了測試的驗收條文**：**做了**。任務 3 的 26 條、任務 2 的 11 條、任務 4 的 3 條、任務 12 的驗收 6／9／12、任務 17 的驗收 1／2／3／6、任務 18 的驗收 1–4，每一條都到測試檔裡找到對應方法並**讀斷言本身**（不是看方法名字），測資形狀有指定的（任務 3 驗收 16／17／23、任務 17 驗收 1／6、任務 18 驗收 1／2／3／4）都逐項核對過形狀與預期值。
3. **每一條邊界情況**：**做了**，逐條寫在上面「邊界情況檢查」，包含明確標示哪幾條本輪依裁決不判、哪幾條本輪沒有碰到。
4. **每一條實機條文**：**做了**，全部在模擬器 `emulator-5554` 上實際操作。沒有任何一條降級成靜態閱讀。唯二沒有在裝置上觀察到的是任務 12 驗收 1 的「貼上單一 U+3000 / U+00A0」與任務 9 驗收 12，兩條都依條文記「本環境無法觀察／無法執行」並指名失敗步驟。
5. **隨機抽測恰好三項**：**做了**（見下節），三項都在本輪 spec 的範圍以外。
6. **真實資料的快照／還原／逐位元組驗證**：**走的是「已同意清空」那一種**。呼叫者在 prompt 裡明講「使用者已同意清空驗收裝置上的 App 資料」，所以驗收開始前清空 App 的使用者資料，不做快照、不做還原、不做逐位元組驗證。
   - **清掉了什麼**：`files/conversations/`（28 個對話檔）與 `shared_prefs/session.xml`。
   - **保留了什麼**：`files/gemma3-1b.task`（1.05 GB）、`files/model.task`（547 MB）、`files/smollm.task`（167 MB）三個重新取得代價很高的模型資產檔，以及 `files/profileInstalled`。做法是 `force-stop` 之後只刪對話目錄與偏好檔（**沒有用 `pm clear`**），刪完 `ls -l files` 確認三個資產檔還在。

**實際執行偏離 `test-cases.md` 的地方**：

- **情境 E 的 E-2（`"  kiwi  "` 回顯）從情境 E 中抽出來，移到後面單獨做。** 原因：條文要求 `kiwi` 保證無命中，而情境 E 的另一條（E-8／任務 18 驗收 5）需要一段訊息裡含 `kiwi`。兩者不能共存，所以先做 E-8，再把 `qa-frag.json` 移除並冷啟動，才做 E-2。
- **情境 E 的 E-14（點擊命中列與磁碟內容）做之前，額外插入一段前置**：先從抽屜開一段**含使用者訊息**的對話當「被切走的那一段」，才取基準線。原因是任務 8 驗收 14 的前置條件 1；`test-cases.md` 只寫了「沿用情境 D 的測資」，沒有寫這一步。
- **情境 E 的 E-11（小螢幕）做法調整**：先在 density 420 打完查詢，再改 density，以免鍵盤鍵位隨 density 改變而要重新量座標。要驗的東西沒有變。
- **情境 H 的 H-1 與 H-8 的順序對調**：先送出一則短訊息確認送出／串流可用，再做需要長回覆的 H-8，因為要先量出這個模型的串流大約多久才有辦法設計批次的時序。
- **情境 A 多做了一件 `test-cases.md` 沒寫的事**：為了任務 8 驗收 14 的「內容相同」比對，新增了一支產品無關的工具 `docs/qa-tools/jsondir.py`（parse 後正規化再 diff），因為位元組雜湊會被「手寫 JSON 被 App 重新序列化」誤判。
- 其餘情境與觀察點都照 `test-cases.md` 的分組執行。

---

# 本輪驗證範圍

**全驗。** 呼叫者給的範圍是「conversation-search 這個功能的全部，照 spec 完整驗收」，本輪把 `spec.md` §1–§9 與 `spec-tasks.md` 任務 1–19 的**每一條現行驗收條文**都驗過（已移除的編號不驗，§6.5 依裁決不判）。沒有任何條目本輪未驗。

---

# 環境文件的處置

`docs/qa-environment.md`（已存在，本輪驗過並修正）：

- **用之前驗過、確認仍成立的**：§0 的 zsh 不切字（印 `1`）、`adb exec-out` 不回傳結束碼（`0` vs `3`）；§1 的 JAVA_HOME／PATH 與 `clean testDebugUnitTest` 的判準；§2 的裝置查詢（`adb devices -l`／`getprop`／`wm size`／`wm density`／`df -h /data`）；§3 的建置佐證做法 (c)；§4 的 `run-as` 讀寫、`ls -1` 多欄陷阱、推測資與偏好檔的路徑、手寫 JSON 與序列化結果位元組不同的陷阱（本輪再次踩到並用新工具繞開）；§5 的 `ui.py`／`uitext.py` selftest、以 class 定位 EditText、清單保留捲動位置、同名節點導致 `tap` 中止；§5a 的手勢區查詢；§6 的版面判斷、`input text` 只送得出 ASCII、注音鍵盤配置與候選列做法、連按兩次空白會變全形句號、`keyevent 122/123/112/67`；§6.4 的長按空白會叫出切換鍵盤選單；§6.5 的剪貼簿 UI 路徑（本輪整條走通）；§7 的 TAB 清焦點；§8 的 density 與旋轉設定（含還原讀回）；§9 的 `am kill` + `am start` 從 task 還原、BACK 依鍵盤狀態逐次按；§11 的取裝置時鐘；§13 的批次與守衛做法。
- **確認失敗、已當場改成現在成立的樣子**：§6.2「U+00A0 送出時完全不報錯、欄位不變」**不再成立** —— 本輪量到 U+00A0 與 U+3000 在兩種寫法（裝置端加引號／argv 直送）下**都**回結束碼 255 並拋 `NullPointerException`，欄位不變。已改寫成「每一輪當場確認它現在是拋例外還是無聲失敗」的做法＋當天量到的結果，不再留一個會腐爛的結論。
- **新增的**：§5 三條（清單捲回頂端 swipe 距離要夠大、覆蓋層開著時再點同名入口不是開／關、Compose 的 disabled 不一定反映在 uiautomator 的 `enabled` 屬性上）；§13 工具清單新增 `jsondir.py`。
- **新增的工具**：`docs/qa-tools/jsondir.py`（附 `selftest`，本輪跑過 `selftest OK: 16 json files listed`）。它通過「換成完全不同的產品還成立嗎」判準：它只知道「某個 app 私有目錄裡有一些 JSON 檔」，不知道任何本產品的欄位或流程。
- **刪掉的越界內容**：沒有。通讀全檔，沒有讀到不通過「換一個產品還成立嗎」判準的句子（唯一接近的是那條 U+00A0 的結論，那是環境行為不是產品觀察，已依上面的方式修正）。
- 本輪沒有把任何產品觀察、預期畫面文字或 pass/fail 寫進那份文件。

---

# 本輪隨機抽測的三項功能

從 `docs/product/features.md` 挑，三項都不指向本輪在驗的 spec（都標「尚無 spec」），並優先挑從來沒被挑過的：

1. **聊天-02**「輸入框是空的時候，右邊的送出鈕按不下去。」
   - 做了什麼：欄位為空（逐碼位 `[]`）時，實際點下去送出鈕的座標。
   - 看到什麼：畫面完全沒有變化（節點數 7 → 7），沒有送出任何訊息。**與描述相符。**
   - 附帶一則工具面的觀察（不是產品問題）：uiautomator 對該按鈕回報 `enabled="true"`，但按下去確實沒有作用 —— 已寫進環境文件，判定以實際按一次為準。
2. **人格-03**「現在用的那一個底色不一樣，右邊標著「目前」。」
   - 做了什麼：在 老師 人格下按「換人」開出人格清單。
   - 看到什麼：五個人格（姊姊／情人／老師／孫子／朋友）都在；**老師那一列底色是淺紫、與其他四列不同，該列右邊有「目前」**。**與描述相符。**
3. **比較-02**「上面是三個模型的勾選鈕，**預設全部勾起來**，可以自己取消不想比的。」
   - 做了什麼：進比較畫面讀三個勾選節點的 `checked`，再取消第一個、又重新勾回來。
   - 看到什麼：預設三個都是 `checked="true"`；點第一個後變 `checked="false"`（另外兩個不受影響）；再點一次回到 `checked="true"`。**與描述相符。**

**三項都與功能總覽的描述相符，沒有不符之處要回報。** 本節的觀察沒有被用來改變上面任何一條驗收的 pass / fail。

---

# 隨機挑選記錄的處置

`docs/qa-random-coverage.md` 已存在，本輪**追加**三行（不是新建），只記編號與日期、不記結果：

```
2026-09-16  聊天-02
2026-09-16  人格-03
2026-09-16  比較-02
```
