# 驗證結果總覽

**fail**（一條）：任務 3 驗收 5 指名的測試只斷言「兩個查詢的命中**段數**相同」，沒有斷言條文要求的「**結果相同**」。產品行為本身是對的（探針比對過完整結果物件相等）—— 這是**測試強度**不足，打回 RD。其餘所有條文 pass，或依條文自己的處置記「未能觀察／本環境無法觀察」（逐條寫在下面）。另有兩件打回 PM 的條文問題、一件條文範圍外的觀察。

- 受測版本：`git rev-parse --short HEAD` = **7b50b16**。驗收開始時 `git status --porcelain` 為空。驗收結束時工作區只有本輪自己的產物：`docs/qa-environment.md`、`docs/qa-random-coverage.md`（修改）與本目錄的 `test-cases.md`（新增）。
- 建置與安裝：從工作區執行 `./gradlew installDebug` **覆蓋安裝**，沒有解除安裝。安裝後 `lastUpdateTime=2026-09-14 19:42:08`，安裝前後私有資料的檔案清單與雜湊逐一相同。
- 裝置：Pixel 9（1080x2424 / density 420），實機驗證。

# 逐項驗收結果

## 任務 1：對話存取的分層抽出

- **驗收 6（實機）：pass**
  - 冷啟動，檔案存在：force-stop 後啟動，續接到 `last_thread_id` 指的那段（兩次，分別是老師/Gemma 與老師/Qwen 那段）。
  - 冷啟動，檔案不存在：切人格產生空白對話，確認磁碟上沒有它的檔，再冷啟動。畫面是記住的人格（朋友）與模型（Qwen）加上一則開場白，抽屜第一列不是它。
  - 歷史排序：整份抽屜清單分步蒐集 23 列，與依 updatedAt 新→舊算出的預期逐列同序。
  - 開啟／刪除／送出後更新：
    - 開啟：抽屜開 A、B 正確。
    - 刪除：刪 qa-z2b，列消失，檔也消失。
    - 送出：送出後 A 排到抽屜第一列。
  - 損毀檔：推入一個內容損毀的 `.json`，其餘 23 列全部顯示。
- **驗收 10（§9.3）：pass（靜態）**。`ConversationRepository.find(id)` 寫成 `all().firstOrNull { it.id == id }`，兩條路徑在定義上是同一個答案，包含找不到的情況。這一層沒有自動化測試，那是「明確不做」裡知情接受的。
- 驗收 1–5、7–9：v3.11 已移除，不驗。

## 任務 2：比對與片段的純邏輯

- **驗收 1：pass**。`ConversationSearch.kt` 沒有任何 import。它依賴的 `Conversation.kt`、`Persona.kt`、`ModelSpec.kt` 也沒有 Android 相依。實證：把這四個檔複製到 scratchpad，只用 kotlin-stdlib 編譯並跑 `ConversationSearchTest`，49 個全綠。
- **驗收 2：pass**。測試 `an empty query is not a search` 含 U+3000 與 U+00A0（逐碼位確認過原始碼裡的字元）。
- **驗收 3：pass**。測試 `conversations come back newest first and hits stay in the order they were said`。
- **驗收 4：pass**。測試 `a conversation shows at most three hits but reports the true total`。
- **驗收 5：pass**。測試 `a conversation with no matching message is left out entirely`。
- **驗收 6：pass**。測試 `highlights stay inside the snippet and never overlap`。
- **驗收 7：pass**。`flattenToLine`、`normalizeForSearch` 是 `internal`，測試直接呼叫並單獨斷言（BMP 掃描、長度不變）。
- **驗收 8（§2.11）：pass**。四個使用點都經過同一個 `isFlattenableSpace`。scratchpad 探針對三個字元×四個使用點的實測：
  - U+0020 與 U+00A0：
    - 訊息端整形成 `0061 0020 0062`。
    - 帶該字元的查詢命中含半形空白的訊息，半形空白查詢也命中含該字元的訊息。
    - 只有該字元的查詢 `isActive=false`。
    - 放在欄位開頭時被移除。
  - U+001C：
    - 兩端都保留（`0061 001c 0062`），與半形空白互不命中。
    - 只有它的查詢 `isActive=true`。
    - 放在欄位開頭時保留（`001c 5317 4eac`）。
- **驗收 9：pass**。測試 `every unicode separator is collapsed`（BMP 掃描）、`zero-width characters are not whitespace`、`a run of mixed whitespace becomes exactly one space`。
- **驗收 10：pass（靜態＋實機）**。
  - 靜態：`onSearchQueryChange` 只套 `withoutLeadingSpace`，失焦只套 `withoutTrailingSpace`，沒有把整形結果寫回欄位。
  - 實機：欄位保留了中間空白與尾端空白（見任務 12）。
- **驗收 11：pass**。`isActive = query.flattenToLine().isNotEmpty()`，測試 `isActive agrees with flattening…` 含 BMP 掃描。
- 驗收 12、13：v3.11 已移除。

## 任務 3：比對語意的單元測試

`clean testDebugUnitTest`：120 個測試、0 failure。其中 `ConversationSearchTest` 49 個。每條都讀過測資與斷言。

- 驗收 1：pass。`a message the user wrote is found`／`a message the assistant wrote is found`。
- 驗收 2：pass。`matching ignores case`。
- 驗收 3：pass。`matching ignores full-width and half-width`（ＡＢＣ 是 U+FF21–23）。
- 驗收 4：pass。`traditional and simplified are not folded together`。
- **驗收 5：fail**
  - 條文：「`"  北京  "` 與 `"北京"` 結果相同，**且兩者都不得為空**（斷言命中筆數 > 0）」。
  - 測試 `surrounding whitespace in the query is ignored` 只做了 `assertEquals(1, bare.size)` 與 `assertEquals(bare.size, padded.size)`。它比的是**分組數**，沒有比「結果」本身（片段、標示、命中則數）。
  - 一個對帶空白查詢算錯標示或片段、但分組數一樣的實作，會通過這個測試。
  - 另一個測試 `removing the padding never changes what matches` 用的是別的查詢，也只比 size 與 matchedMessageCount。
  - 產品行為：scratchpad 探針 `searchConversations("  北京  ", t) == searchConversations("北京", t)` 為 true，行為正確。
- 驗收 6：pass。五個案例齊全，U+3000 與 U+00A0 以逐碼位確認。
- 驗收 7：pass。`a query that matches nothing returns nothing`。
- 驗收 8：pass。`normalising never changes the length of the text`，含 U+0130（逐碼位確認）。
- 驗收 9：pass。`a highlight points at the matched text even when the message had newlines`。
- 驗收 10：pass。`every match inside the window is highlighted`。
- 驗收 11：pass。`a hit deep inside a long message is cropped…`（100 字後命中，長度 62，標示正確）。
- 驗收 12：pass。
- 驗收 13：pass。`the persona opener can be searched`（作者為助理、id 等於第 0 則）。
- 驗收 14：pass。`a thread holding nothing but its opener still matches`。
- 驗收 15：pass。`a shared opener matches in every thread that has it`。
- **驗收 16：pass**
  - 兩個測試的測資形狀與條文一致：15 emoji + 測 + 北京（起點方向）；30 測 + 北京 + 7 尾 + 20 emoji（尾端方向）。
  - 兩個都斷言了「片段含查詢詞」。
  - 條文要求「兩個案例必須各自獨立」，所以在 scratchpad 複本做了兩個定點突變：
    - 讓 `avoidSplittingPairAt` 直接回傳 index：**只有** `cropping never splits a surrogate pair` 變紅（49 中 1 failure）。
    - 讓 `avoidSplittingPairBefore` 直接回傳 index：**只有** `…at the window end` 變紅。
- 驗收 17：pass。BMP 掃描用的是字面形式 `"a${character}b".flattenToLine() != "a b"`；上限含 U+200B/C/D、U+FEFF 與 ZWJ 家庭 emoji；收合案例字元逐碼位確認。
- 驗收 18：pass。`a message written with a full-width space is found with a plain one`。
- 驗收 19：pass。四個案例都經過 `searchConversations`（U+3000 查詢、三個半形空白、U+00A0 訊息、只有 U+3000）。
- 驗收 20：pass。`a highlight is as long as the flattened query…`（長度 5）。
- 驗收 21：pass。`a query with a space inside is one string…`。
- 驗收 22：pass。含 U+3000、U+00A0、`" \t\n"`、`"a"` 與 BMP 掃描。
- 驗收 23：pass。L=59／60／61 三個測試，預期片段與標示位置和 §4.2 一致，並斷言 59 與 60 的片段不同。
- 驗收 24：pass。`persona and model names are not searched`（老師、Qwen、qwen 否定＋「想不通」正向）。
- 驗收 25：pass。`ellipses appear only where text was actually cut`（同一個方法內兩個方向）。
- 驗收 26：pass。`searching finds message text rather than the derived title`。

## 任務 4：「所有對話」的定義與搜尋子狀態

- 驗收 2：pass。`allThreads contains the open thread even when it is blank`（含排序）。
- 驗收 3：pass。`allThreads keeps the in-memory copy when a thread arrives twice`。
- 驗收 6：pass。`the in-memory copy wins even when the saved one looks newer`（ConversationSearchTest 裡的那一個）：
  - 測資：same/100/兩則、same/900/一則、other/500/一則。
  - 走搜尋入口，斷言 `["other","same"]` 且命中數 2。
- 驗收 1、4、5、7：v3.11 已移除。

## 任務 5：狀態層的搜尋入口與衍生結果

- **驗收 2（盡力而為）：未能觀察，不判 fail**。送出請求長回覆的訊息後，回覆約 5 秒就撞到長度上限結束。進入搜尋時串流已經停了，命中數穩定在 2，沒有攔到增加的過程。
- 驗收 5：pass。在搜尋畫面輸入 zebra 後轉橫向：仍在搜尋畫面、欄位 `zebra`、摘要 `3 段對話・9 則訊息`。轉回直向同樣如此。
- 驗收 6：pass。用過搜尋之後，送出、串流、停止、切換人格、切換模型、刪除、冷啟動全部照常（詳見任務 13 驗收 8）。
- 驗收 7：pass。切人格（孫子）產生空白對話後，查「！」（開場白裡的全形驚嘆號），第一個分組就是那段空白對話（孫子・Gemma3 1B・19:59）。
- 驗收 8：pass。
  - 進入搜尋前後各做一次對話檔 sha256：25 檔完全相同。
  - 狀態列前後都是「・離線」。
  - 以 pid 過濾的 logcat 只有 IME 與視窗相關的行，沒有模型載入或推論紀錄。
  - 正向對照：同一次操作裡查詢 onlybot 得到 `1 段對話・1 則訊息`。
- 驗收 1、3、4：v3.11 已移除。

## 任務 6：抽屜入口與搜尋畫面外殼

- **驗收 1：部分 pass，部分無法判定（打回 PM）**
  - 可判定的那一半 pass：
    - 抽屜內容依序是：模型（三個）、並排比較所有模型、歷史紀錄標題與說明、開始新的對話、搜尋對話內容、歷史列表。
    - 在列表區上滑，列表內容改變，上半部六個節點的 bounds 完全不變。
    - 在上半部上滑，什麼都沒動。
  - 「位置與行為**完全不變**」判不出來：專案最早的 commit（706f93d）已經包含搜尋，手邊沒有「改動前的抽屜」可以對照。見「問題歸屬」。
- 驗收 2：pass。入口是按鈕列，dump 裡不是 EditText。點下後抽屜的節點消失，進入搜尋畫面。
- 驗收 3：pass。進入時 EditText `focused=true`，`mInputShown=true`。
- 驗收 4：pass。分步捲動結果列表時，EditText 的 bounds 始終是 `[148,174][1058,340]`。
- 驗收 5：pass。
  - 畫面上的返回鍵：回到聊天（dump 看得到「換人」），焦點仍在本 App。
  - 系統返回鍵：第一次只收鍵盤，第二次回到聊天，沒有退出 App。
  - 兩種都重新進入確認欄位為空、顯示引導文案。
- 驗收 7：pass。比較畫面按系統返回直接回到桌面（launcher 取得焦點）；在比較畫面轉橫向被丟回聊天。兩件既有缺陷維持原樣。
- 驗收 8：pass。橫向時仍在搜尋畫面，輸入框在頂端（`[321,68][2402,226]`）。橫向＋鍵盤的可見量不判。
- 驗收 9：pass（注音輸入法）。
  - 輸入「北」，不按任何鈕、不收鍵盤，1 秒後結果是 `5 段對話・6 則訊息`。
  - 接著輸入「京」，1 秒後變成 `4 段對話・4 則訊息`。
  - 畫面上唯一的控制項是返回與清除（×），沒有任何「按了才搜」的控制項。
- 驗收 6、10：v3.11 已移除。

## 任務 7：結果列表

- 驗收 1：pass。結果佔滿輸入框以下；12 段的結果分步捲完整片。
- 驗收 2：pass。截圖上命中字以主題色加粗；程式碼取 `MaterialTheme.colorScheme.primary`，沒有寫死色碼。標示位置正確（見任務 9）。
- 驗收 3：pass。查「今天」命中 12 段對話的第 0 則（開場白），分步捲完，沒有崩潰。
  - 分組順序與依檔案 updatedAt 算出的預期一致，焦點始終在本 App。
  - 其中兩段標頭文字完全相同（老師・Gemma3 1B・9/2），它們是兩段不同的對話，不是錯位。
- 驗收 4：pass。5 則命中那段顯示 3 列＋「還有 2 則符合」。
- 驗收 5：pass。長訊息命中列是單行，以「…」截斷，沒有破版（截圖）。
- **驗收 6：pass（附偏離說明）**
  - 測資：命中「咖啡店」前 9 個字元內有 🐶。片段是 `…遇到一隻很可愛的小狗🐶牠一直跟著我走到咖啡店門口…`。
  - 標示恰好蓋在咖啡店三字；emoji 算繪正常、沒有破版。
  - 偏離：中文經由裝置上的注音輸入法組字、從候選列選字，但按鍵是用 adb 送的。見「驗證方式說明」。
- 驗收 7：pass。空白對話（孫子）命中時，搜尋畫面上「還沒說話」節點數為 0；標頭是 emoji＋人格＋模型＋時間。
- 驗收 8（§7.1）：pass。
  - 查 zebra 時鍵盤 frame 上緣 y=1459：1 個標頭＋3 列＋第二個標頭＋2 列＋第三個標頭，全部落在上緣以上。
  - 中文查「今天」時上緣 y=1359：4 個標頭與 5 個命中列完整可見。
- 驗收 9（§7.2）：pass。density 606 時鍵盤上緣 y=1033，1 個標頭＋3 個命中列完整可見。之後 density 已 reset（420）。
- 驗收 11：pass。三個分組標頭同時看得到 emoji、人格、模型、時間，而且隨對話改變（📘 老師 Gemma3 1B 18:43／💗 情人 Qwen2.5 0.5B 17:43／🧒 孫子 SmolLM 135M 16:43）。
- 驗收 10：v3.11 已移除。

## 任務 8：空狀態、無結果、點擊與返回

- 驗收 1：pass。只有「搜尋所有對話的訊息內容」，沒有「找不到」、沒有列表。
- 驗收 2：pass。查 `qqzz`，結果區只有 `找不到符合「qqzz」的訊息` 一句。
- 驗收 3：pass。
  - 點另一段對話（咖啡店那段）的命中：回到聊天，內容是該段。
  - 前後 25 個對話檔的雜湊相同。
  - 再次進入搜尋時欄位為空。
- 驗收 4：pass。
  - 點目前開啟那段的命中（我住在臺北）：回到聊天，內容相同。
  - 點擊後 0.4 秒間隔連讀三次，狀態列都是「・離線」，沒有出現載入中。
  - pid 過濾的 logcat 沒有模型紀錄；檔案雜湊相同。
- 驗收 5：pass。切人格產生空白對話 → 搜開場白 → 點擊：回到聊天、同一段空白對話、狀態「離線」、檔案數維持 25、雜湊相同。
- 驗收 6：pass。命中列沒有任何 `刪除這段對話` 節點。
- 驗收 7：pass。`3 段對話・9 則訊息`，列出 3＋2＋2 列。
- 驗收 8：pass。返回鍵、系統返回、點擊命中列三種離開都清空查詢；旋轉後查詢與結果都在。
- 驗收 9：pass。在搜尋畫面輸入字串 → Home → `am kill`（pidof 空）→ `am start`（task brought to front）→ 落在搜尋畫面，欄位為空，顯示引導文案。
- 驗收 10：pass。
  - 輸入 `%s%skiwi%s%s` 後，欄位碼位 `6b 69 77 69 20 20`，文案 `找不到符合「kiwi  」的訊息`。
  - TAB 失焦後欄位是 `kiwi`（focused=false），文案 `找不到符合「kiwi」的訊息`。
- 驗收 11：pass。
  - 整輪任何時刻結果區都只有引導文案、列表、或「找不到」三種樣子，沒看到進度指示、骨架列或載入中字樣。
  - 正向對照：結果裡包含歷史裡的對話（所有 qa- 測資都在歷史中，例如 zebra 的三段）。
  - 「載入尚未完成的那一瞬間」沒有攔到，依條文不影響判定。
- 驗收 12：pass。
  - 輸入 `prefzzq` 時讀 `session.xml`：找不到那個字串，`<string>` 鍵只有 3 個（last_thread_id / last_model / last_persona）。
  - 正向對照：force-stop 後冷啟動仍續接到 `last_thread_id` 那段，偏好內容未變。
- 驗收 13：pass。只含助理訊息的檔（qa-onlybot）開著另一段不同 id 的對話時，搜 onlybot 並點擊，切換到那段（聊天顯示 onlybot secret text，標頭 📘 老師 Qwen2.5 0.5B）。
- 驗收 14：pass。§6.2、§6.3、§6.4 與驗收 13 四種點擊情境走完都回到聊天、顯示對應對話，四次點擊前後對話檔雜湊都相同。

## 任務 9：回歸驗證與中文算繪的手動清單

- 驗收 2：pass。`TextRepair.kt` 沒有出現也沒有引用 `flattenToLine`、`normalizeForSearch`、`isFlattenableSpace`（grep）。
- 驗收 3：pass。模型上下文的過濾在 `ChatViewModel.send` 的 `.dropWhile { !it.fromUser }`；搜尋沒有用它（`searchConversations` 對全部訊息 `mapNotNull`）。
- 驗收 4：pass。實機結果見任務 1 驗收 6 與任務 8 驗收 5。
- **驗收 5：部分 pass，部分無法判定（打回 PM）**。可觀察的結構 pass（見任務 6 驗收 1）；「除了多一個搜尋入口列之外完全沒變」沒有改動前的樣子可以對照。
- 驗收 6：pass。不進搜尋時的送出、串流、停止、刪除、切人格、切模型、冷啟動續接、抽屜開啟都照常。
- 驗收 7：pass。
  - 抽屜預覽逐碼位讀出 `…5317 4eac 3000 5929 6c23…`，U+3000 仍在。
  - 同一則訊息的搜尋片段是 `…5317 4eac 0020 5929 6c23…`。
- 驗收 8：pass。查「今天」：
  - 12 段對話，標示蓋在「今天」上，片段沒有破版。
  - 摘要 `12 段對話・13 則訊息` 與分步蒐集到的分組數 12 一致（依檔案算出的預期也是 12 段 13 則）。
- 驗收 9：pass。「咖啡店」片段前後都有「…」，標示恰好蓋住三字，20 字內含 emoji（見任務 7 驗收 6）。
- 驗收 10：pass。
  - 「台」→ 1 段（台中也不錯，15:43）。
  - 「臺」→ 1 段（我住在臺北，12:43）。
  - 兩者結果不同。
- 驗收 11：pass。
  - 用注音打「北京」＋半形空白鍵（keyevent 62）＋「天氣」，欄位碼位 `5317 4eac 0020 5929 6c23`。
  - 含 U+3000 與含 U+00A0 的兩則訊息都命中，截圖上標示蓋住整個「北京 天氣」，包含中間那一格。
- 驗收 12（選作）：本環境無法執行。`cmd clipboard` 回 `No shell command implementation.`，輸入法也打不出 U+3000。語意權威在任務 3 驗收 19。
- 驗收 13：pass。8–11 都在 `mInputShown=true` 下判讀，§7.1 同時複驗成立（見任務 7 驗收 8）。
- 驗收 1、14：v3.11 已移除。

## 任務 10：更新架構文件（`plan/architecture.html`）

- 驗收 1：pass。「輸入框裡留下什麼，跟拿去比對的是兩回事」涵蓋開頭立即移除、尾端到失焦才移除、中間不動、比對一律用整形後的文字。
- 驗收 2：pass。「（只有空白）→ 不算搜尋」那一列補了「這是比對層的契約：搜尋框本身打不出這種輸入…但比對層仍然必須答對」。
- 驗收 3：pass。
  - (i) 句首原則與句尾理由與任務 10 歷史紀錄 v3.4 第 3 點逐字相同。唯一的差異是引號字形：紀錄那句本身被包在「」裡，所以內層引號寫成『』，文件原文是「」。我不把這個算成字的差異。
  - (ii) 「不算」那一側是「切過去看一眼、切走都不算」，沒有「按下停止」。
  - (iii) 全文另外兩處「按下停止」講的是中斷生成的觸發條件，不是宣稱它不算一次更新。
- 驗收 4：pass。三列的路徑都存在，職責與現況相符：
  - `ui/SearchScreen.kt`：無狀態，只把算好的區間上色。
  - `chat/ConversationSearch.kt`：命中、裁切、標示都在這裡。
  - `data/ConversationRepository.kt`：聊天層唯一的呼叫端是 ChatViewModel，其他 main 程式碼沒有直接用 ConversationStore。
- 驗收 5：pass。沒有「最後開啟時間」或第二個時間概念。
- 驗收 7：pass。「命中本身有 60 個字（含）以上時裝不下引子，視窗就從命中的第一個字開始」—— 長度剛好 60 時引子數的唯一答案是 0。
- 驗收 6：v3.9 已移除。

## 任務 11：摘要的兩個數字維持現狀

- 驗收 2：pass。`3 段對話・9 則訊息`，3＋2＋2 列。
- 驗收 3：pass。空查詢（引導文案）與無命中（qqzz）時都沒有摘要列。
- 驗收 1、4：v3.12 已移除。

## 任務 12：查詢欄位的空白規則

- **驗收 1：pass**
  - 空欄位按一次空白鍵（keyevent 62）：欄位碼位 `[]`，引導文案 1、「找不到」0。
  - **長按空白鍵 → 替代做法**：再連送三次空白鍵事件，結果同上。原因是這台裝置長按空白會叫出切換鍵盤選單。
  - **貼上單一 U+3000 / U+00A0 → 本環境無法觀察**。原因是設不了剪貼簿（`cmd clipboard` 回 No shell command implementation）、`input text` 送不進非 ASCII。承接條文是本任務驗收 6，本輪 pass。
  - 環境是否改善：剪貼簿本輪重新確認仍然不可設，沒有改善可回報。「非 ASCII 會拋例外」這一點本輪沒有重新觸發確認，沿用環境文件的記載。
- **驗收 2：pass（替代做法）**
  - 原因：設不了剪貼簿，「貼上」做不出來。
  - 實際做法：`adb shell input text "%s%s%sabc"` 一次送進整串前導空白＋ASCII。
  - 結果：欄位碼位 `61 62 63`，開頭三個空白沒有留下。
- **驗收 3：pass（附偏離說明）**
  - `input text "ab%s%s"` → 欄位 `61 62 20 20` → MOVE_HOME + 兩次 FORWARD_DEL → 欄位 `[]`，引導文案 1、「找不到」0。
  - 偏離：用 ASCII「ab」取代條文的「北京」，**條文沒有授權這個替代**，寫在「驗證方式說明」。
- 驗收 4：pass（注音輸入法）。
  - 「北京」＋空白鍵後欄位 `5317 4eac 0020`。
  - 再選「天氣」後欄位 `5317 4eac 0020 5929 6c23`，命中「我在北京 天氣很好」。
- 驗收 5：pass。
  - TAB 移走焦點後欄位由 `kiwi  ` 變成 `kiwi`（focused=false）。
  - 以 class 點回欄位後仍是 `kiwi`（focused=true），沒有把空白加回來。
  - 另外 `zebra  ` 失焦後也變成 `zebra`。
- 驗收 6：pass。`the field uses our whitespace rule, not the built-in one`：
  - `" 北京".withoutLeadingSpace() == "北京"`（U+00A0 已逐碼位確認）。
  - `"北京".withoutLeadingSpace() == "北京"`（U+001C 保留，已逐碼位確認）。
- 驗收 7：pass。同任務 8 驗收 10。
- 驗收 8：pass。
  - 輸入 `%s%szebra%s%s`：開頭兩個空白被移除，欄位是 `zebra  `。
  - 記下全部結果節點後 TAB 失焦，欄位變成 `zebra`。
  - 失焦前後，欄位以外的節點（摘要、標頭、命中列、還有 N 則）逐一相同。
- 驗收 9：pass。空查詢契約測試五個案例仍在且通過。
- 驗收 10、11：v3.11 已移除。

## 任務 13：對話「最後更新時間」的定義

- 驗收 1：pass。
  - 開著 A（commonword alpha，較新），從抽屜開 B、開回 A，再開 B、開回 A。
  - 前後搜尋「commonword」都是 A（19:13）在前、B（9/13）在後，標頭時間文字相同。
  - 抽屜頂端列表前後相同。
- 驗收 2：pass。
  - B 的 updatedAt 是昨天（1789292610718）。
  - 第一次與第二次從 B 切走後拉回的存檔**位元組相同**（sha256 082d0811…）。
  - updatedAt 仍是 1789292610718，parse 後內容與推入時相同。
- 驗收 3：pass。
  - 在 A 串流中、畫面已有文字時按停止（裝置時鐘 1789388370637），之後 32 秒不動，再於 1789388407647 切到 B。
  - 預期一：存檔中那則回覆結尾是 `…（已停止）`。
  - 預期二：存檔 updatedAt 1789388370524，比切換時刻早 37123 ms，≥30 秒。
- 驗收 4：pass。
  - 送出（1789387950408）並收到完整回覆後，A 的 updatedAt 前進到 1789387964437（送出後約 14 秒，在收尾之前）。
  - 搜尋結果 A 排第一（20:12）；切走後抽屜第一列是 A。
- 驗收 5：pass。切人格產生的空白對話在搜尋結果裡排第一（見任務 5 驗收 7）。
- 驗收 6：pass（靜態）。
  - `updateThread` 是唯一蓋時間的地方（全檔只有一處 `copy(updatedAt = …)`）。
  - `send`、`updateMessage`、`settle`、`stop` 對訊息內容的改動都經過它。
  - 其餘寫 `conversation =` 的地方（init 續接、switchModel、switchPersona、openThread、deleteThread）是換成另一段對話，不是改寫同一段的內容。
- 驗收 7：pass。在 A 送出後，串流中從抽屜「開始新的對話」切走，再切回 A：那則使用者訊息與已出現的回覆（結尾 `…（已停止）`）都在，訊息數由 6 變 8。
- 驗收 8：pass。送出、串流、停止、刪除、切換人格（孫子、朋友）、切換模型（Qwen）、冷啟動續接全部照常。

## 任務 14：讓補上來的條文各自有東西在驗

- 驗收 1：pass。清單上每一條都指得出驗法：
  - 任務 1 驗收 10：程式碼位置 `ConversationRepository.find`。
  - 任務 3 驗收 25：測試 `ellipses appear only where text was actually cut`。
  - 任務 5 驗收 8、任務 6 驗收 9、任務 7 驗收 11、任務 8 驗收 11–14：實機步驟與判讀點，都寫在本報告與 `test-cases.md`。
- 驗收 2：pass。可自動化的只有任務 3 驗收 25：它在執行（XML 有該 testcase），一個無條件在頭尾補「…」的實作會讓它的前半變紅。
- 驗收 3：pass。任務 3 驗收 25、任務 5 驗收 8、任務 6 驗收 9、任務 7 驗收 11、任務 8 驗收 11、12 的正向與否定兩半，本輪都實際執行了（見各條）。
- 驗收 4、5：已移除。

## 任務 15：`docs/product/features.md` 指向本規格的引用仍然追得到

- 驗收 1：pass。以腳本列出全部含 `docs/specs/conversation-search/` 的引用，共 38 處，含同一格多節號與「引用的寫法」範例那一處。
  - 每一處都到 v3.13 目錄的對應檔案判：節號標頭存在、節名 grep 得到、節名落在該節範圍內。
  - 38 處全部成立（含 `歷史-11` 指向 `spec-architecture.md` §B.3「「所有對話」值得只有一個組裝處」）。
- 驗收 2：pass。`搜尋-02`（§1.7）、`搜尋-13`（§1.2）、`搜尋-20`（§2.8）的欄位仍帶指向本規格的引用。
- 驗收 3：pass。沒有任何一處路徑含版號目錄。

# 邊界情況檢查

spec 沒有一節名叫「已知衝突與邊界情況」；邊界落在行為規格的「可判定」、各「驗收層級」註記與「明確不做」裡。逐項：

- **§2.4、§3.5「畫面上不可達」的輸入**：由直接測試承接，測試都還在且通過（任務 3 驗收 6、任務 4 驗收 6）。
- **§2.11 U+001C（內建 trim 與規範集合唯一相異處）**：測試在（任務 12 驗收 6），scratchpad 探針對四個使用點實測一致。
- **§4.2 命中長度 = 60 的界線、§4.4 surrogate 兩個方向**：測試在；§4.4 以定點突變證明兩案例各自獨立。
- **§1.8 串流中的回覆**：未能攔到（回覆太快結束），依條文記「未能觀察」。
- **§1.9 歷史載入中的那一瞬間**：未能攔到；兩半的判定不依賴它（任務 8 驗收 11 pass）。
- **§3.6 多段對話第 0 則同時命中**：實機 12 段開場白命中，捲完不崩潰。
- **§5.6／§5.7 旋轉不算離開、行程死亡**：旋轉保留查詢與結果；`am kill` 還原為空查詢加引導文案。
- **§6.1 只含助理訊息的舊檔**：點擊依 id 切換成功。另見文末「條文範圍外的觀察」。
- **§7.3 橫向＋鍵盤**：依條文不判可見量，只驗仍在搜尋畫面且輸入框在頂端。
- **§8.2 串流中切走**：切走時留下部分文字加中斷標記、內容在（任務 13 驗收 7）；時間那一半依條文不另判。
- **§9.1 空白對話存檔等於刪檔**：切人格與從搜尋點進空白對話都沒有新增檔案。
- **§9.2 壞檔**：其餘 23 列完整顯示。
- **明確不做「標題/預覽攤平規則不同」**：確認仍是既有差異（任務 9 驗收 7）。

# 如果 fail：問題歸屬

1. **任務 3 驗收 5 → 打回 RD（實作的測試強度不足）**
   - 條文清楚要求「結果相同」，測試只斷言分組數相同。產品行為正確。
   - 修法只在測試：斷言兩個查詢的完整結果相等，並保留「命中筆數 > 0」那一半。
   - 若 PM 的原意其實只是「分組數相同」，那是條文措辭要改；以現行字面判 fail。
2. **任務 6 驗收 1、任務 9 驗收 5 的「完全不變／除了入口列之外完全沒變」→ 打回 PM（條文要求的證據不存在於產物裡）**
   - 專案的第一個 commit 已經含搜尋入口，找不到「改動前的抽屜」可以對照。
   - 本輪只能判「現在的結構符合描述（上半部固定、只有列表可捲、入口是按鈕列）」。
   - 這兩句的「一字不動」那一半，無論多用心都判不了。建議改寫成對現況可判定的結構描述，或指明基準從哪裡取。
3. **任務 7 驗收 6「手動輸入法，不用 adb」→ 提請 PM 注意（不影響判定）**
   - 本輪中文字是由裝置上的注音輸入法組字與選字產生的（經過真實輸入法），但鍵是用 adb 送的。
   - 由 agent 執行的驗收做不到真正的手動按鍵。若這條的用意是「文字必須經過真實輸入法」，本輪滿足；若字面要求人手，那是一條這個環境永遠做不到的條文。

# 驗證方式說明

每一輪都要做的六件事：

1. **標準測試指令：做了。** `./gradlew clean testDebugUnitTest`：log 有 `> Task :app:clean` 與 `> Task :app:testDebugUnitTest`，沒有 UP-TO-DATE／FROM-CACHE。9 個 XML 合計 120 tests、0 failures、0 errors，時間戳是本輪的。
2. **每一條指名了測試的條文：做了。** 任務 2、3、4、12 驗收 6／9、14 驗收 2 的測試逐一讀了測資與斷言；肉眼看不出的字元（U+00A0、U+3000、U+001C、U+200B–D、U+FEFF、U+0130、全形）用逐碼位腳本確認原始碼。另外兩類補判：
   - 任務 3 驗收 16 條文明文要求案例各自獨立，所以在 scratchpad 複本做了兩個定點突變。這是條文本身要求的判定，不是突變全掃。
   - 任務 2 驗收 8、任務 3 驗收 5 用 scratchpad 探針補判。
   - 專案原始碼全程沒有被改動（複本 `cmp` 與專案檔相同）。
3. **每一條邊界情況：做了**（見上節）。
4. **每一條實機條文：在裝置上實際做了。** 只有條文自己允許「未能觀察／本環境無法觀察」的那幾項沒有攔到或做不出（任務 5 驗收 2、任務 12 驗收 1 的貼上案例、任務 9 驗收 12、§1.9 的載入瞬間）。
5. **隨機抽測恰好三項：做了**（見下面「本輪隨機抽測的三項功能」）。
6. **真實資料的快照／還原／逐位元組驗證：做了。**
   - 快照：16 個小檔的檔案清單＋sha256，3 個模型大檔在裝置端就地雜湊。
   - 推入測資時以 `comm` 確認只多了本輪的 11 個 qa- 檔。
   - 結束時 force-stop、刪除多出來的檔、推回位元組不同的檔（profileInstalled、session.xml）。重新抓全部檔案重算雜湊，清單與 16 個雜湊與 baseline 完全相同，3 個大檔雜湊相同。
   - 還原後為了補驗抽屜又啟動一次 App，因此整個還原與驗證**重跑了一次**，結果同樣全部相同。`/data/local/tmp` 沒有殘留。
   - 系統設定已還原並讀回確認：accelerometer_rotation=1、user_rotation=0、density 420（無 override）、airplane_mode_on=0、輸入法版面回到注音（截圖確認）。

**實際執行偏離 `test-cases.md` 的地方：**

- **D2-4（任務 12 驗收 3）**：用 ASCII「ab」取代「北京」。原因是該批次在英文版面下批次執行，而刪字判定與字元種類無關。**條文沒有授權這個替代**，所以寫在這裡。
- **D2-5 與 D4-4 合併**：同一次注音輸入「北京 天氣」同時判任務 12 驗收 4 與任務 9 驗收 11。兩者都要求用一般半形空白鍵，所以共用同一個狀態。
- **D5-3（任務 5 驗收 7）**：搜「！」而不是用注音打開場白的詞。「！」是開場白裡的字元（注音版面下送 `!` 進欄位即為 U+FF01），仍然是「用其開場白搜到」，而且那段空白對話排在第一。
- **D6-3 的冷啟動「檔案不存在」**：放到 D7 之後做（需要一段剛產生的空白對話）。
- **D1-1 抽屜「上半部固定」的直接觀察**：原本以為分步捲動清單時已涵蓋，事後發現蒐集時只看了列表區，於是在第一次還原之後補做一次，再重跑還原與驗證。
- **D3-10**：分組去重的輔助腳本前兩次有 bug（依上緣分桶、從非頂端開始捲）。改用依檔案算出的預期對照摘要數字判定，並把兩個技巧寫進環境文件。

# 本輪驗證範圍

全驗。呼叫者沒有縮小範圍。

關於「請全部讀完」：`spec.md`、`spec-tasks.md`、`spec-architecture.md`、`spec-decisions.md` 全文都讀了；`spec-architecture.md` 裡沒有讀到說得出使用者後果、卻沒搬進行為規格的條文。**`spec-appendix.md` 刻意沒有讀**：它的內容是修訂記錄與「歷次全驗的結論」，也就是上一輪哪幾條沒過，讀了就失去冷讀。它不是驗收對象，本輪也沒有任何一條驗收需要它當證據。沒有讀的還有 `pm-inbox.md`、`changes-v3.*.md`、舊版號目錄與任何舊的 `test-report.md`。

# 環境文件的處置

- **驗過的條目**：§0 各 shell 陷阱、§1 JDK 與 gradle、§2 裝置、§3（改用 (c) 覆蓋安裝）、§4 快照與還原流程、§5 UI dump 與 EditText 以 class 定位、§5a 手勢區、§6 輸入法（注音／英文切換、input text、注音組字選字、剪貼簿仍不可用）、§7 TAB 失焦、§8 density 與方向（套用與還原）、§9 am kill／force-stop 與 BACK、§10 pid 過濾 logcat、§11 時差（本輪約 6 秒，裝置較快）、§12 scratchpad 編譯。都成立。
- **修正**：§3 的最後確認說明改成本輪實際採用的 (c) 覆蓋安裝與前後雜湊比對。
- **新增**（每條都附確認方式）：
  - §0：「指令加參數存進變數後 zsh 不切字」。本輪踩到，守衛自測因此印出看似正常的 ABORT。
  - §6.3 後：注音版面下 `input text` 送 ASCII 標點會變全形；候選列每次仍要截圖確認。
  - §5：同一列節點依下緣分組，不要依上緣。
  - §9：飛航模式的記錄、切換與還原。
- **刪掉的越界內容**：沒有找到換成別的產品就不成立的句子。

# 本輪隨機抽測的三項功能

以腳本從 `features.md` 裡未指向本規格、且從未被挑過的 59 項中隨機抽出三項：

1. **啟動-03**（首次使用模型時的解壓進度條）：**本環境無法觀察。**
   - 三個模型檔（gemma3-1b.task、model.task、smollm.task）都已解壓在私有目錄裡，要重現「第一次」只能刪掉使用者裝置上的模型檔，不做。
   - 本輪切換模型時只看到狀態直接回到「離線」，沒有出現解壓橫幅，這與「已解壓過」一致。不構成與文件的不符。
2. **啟動-02**（完全不需要網路）：**與文件相符。** 開飛航模式（原值 0，已還原為 0）後在 A 送出英文請求，模型照常逐字生成回覆直到長度上限。
3. **聊天-15**（頂端顯示人格 emoji＋名字，下方模型名＋狀態）：**看到的部分與文件相符。**
   - 頂端「🙌 朋友／📘 老師／🧒 孫子…」與「Gemma3 1B・輸入中…」「・離線」都實際看到了。
   - 「解壓中」「載入中」沒有攔到：切換模型後 0.5 秒內已是「離線」。所以文件列出的四種狀態本輪只觀察到兩種。

以上三項**沒有拿去改變本規格任何一條的 pass／fail**。

**條文範圍外的觀察（不影響 pass／fail，交呼叫者判斷是否為新問題）**：
- 推入的「只含助理訊息」的對話檔（qa-onlybot），被從搜尋結果點開、之後再切到別段對話時，**整個檔被刪掉了**。它被讀成空白對話，切走時的存檔依 §9.1「空白對話存檔等於刪檔」處理。
- §6.1 自己寫著這種舊檔「會被讀成空白對話並進入歷史」，但沒有任何規則或驗收說「開啟再離開之後它還在不在」。
- 若真實使用者有這種舊檔，開一次就會消失。本輪只影響測資；使用者原有資料經雜湊確認完全還原。

另外，`plan/architecture.html` 寫著「app/src/test，五支測試各對應一塊」，而現在有 9 支測試類別。這不在任務 10 任何一條驗收的範圍內，僅記錄。

# 隨機挑選記錄的處置

在既有的 `docs/qa-random-coverage.md` **追加**三行（沒有新建），只記編號與日期：

```
2026-09-14  啟動-03
2026-09-14  啟動-02
2026-09-14  聊天-15
```
