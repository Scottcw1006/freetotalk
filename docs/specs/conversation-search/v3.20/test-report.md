# conversation-search v3.20 驗收報告

- 受測版本：HEAD `a95f757`（開始時 `git status --porcelain` 為空）。本輪開始時 v3.20 目錄沒有既有的 `test-cases.md` / `test-report.md`，未刪除任何檔案。
- 驗收裝置：Android 模擬器 AVD `Pixel_9`，`emulator-5554`，Android 17，1080x2424 / density 420。
- 單元測試 JVM：Android Studio JBR `openjdk 25.0.2`。
- 驗收日期：2026-09-16（裝置時鐘 02:21–03:35，未跨日）。

# 驗證結果總覽

**pass。** 任務 1–18 的每一條現行驗收標準都判 pass，或依條文明文記「本環境無法觀察／未能觀察」（皆不判 fail，細節見各條）。沒有發現實作缺陷，也沒有需要打回 PM 的條文錯誤。有一件依條文**必須回報**的環境變化：這個環境**已經能經由 UI 設定剪貼簿**（任務 12 驗收 1、2，見下）。

# 逐項驗收結果

## 任務 1：對話存取的分層抽出
- 驗收 6（既有行為逐項不變，在裝置上）— **pass**
  - 冷啟動續接，檔案不存在：清空資料後啟動，開一段新對話、不崩潰。檔案存在：偏好指向 `qa-apple2a`，force-stop 再冷啟動，續接到那段。
  - 空白對話不進歷史：新開、換人格產生的空白對話在抽屜與檔案清單中都不出現（E-6 每次離開前後 hashdir 相同）。
  - 歷史排序：推入 17 段，抽屜依 updatedAt 新→舊，9/14 那段在最後。
  - 開啟、刪除、送出後更新：點列開啟；刪 B 後列表與檔案少一；送出後那段排到最前。
  - 損毀檔：推入內容截斷的 `qa-corrupt.json`，其餘 17 段全數顯示。
- 驗收 10（§9.3）— **pass**（靜態）。`ConversationRepository.find(id) = all().firstOrNull { it.id == id }`，兩條路徑同一個來源、同一個比對，存在與不存在的 id 答案必然相同。

## 任務 2：比對與片段的純邏輯
- 驗收 1 — **pass**。`ConversationSearch.kt` 沒有任何 import，用到的 `java.text.BreakIterator` 是 JVM 類別。它相依的 `Conversation.kt`、`ModelSpec.kt`、`Persona.kt` 也沒有 Android import。以 Kotlin 編譯器在主機上單獨編譯並執行成功（探針，見驗證方式）。
- 驗收 2 — **pass**。測試 `an empty query is not a search` 的案例含 `""`、`"   "`、`"\n\t"`、U+3000、U+00A0，以碼位讀原始碼確認。
- 驗收 3 — **pass**。測試 `conversations come back newest first and hits stay in the order they were said`。
- 驗收 4 — **pass**。測試 `a conversation shows at most three hits but reports the true total`（3 則／總數 5）。
- 驗收 5 — **pass**。測試 `a conversation with no matching message is left out entirely`。
- 驗收 6 — **pass**。測試 `highlights stay inside the snippet and never overlap`。
- 驗收 7 — **pass**。`flattenToLine`、`normalizeForSearch` 皆為 `internal`，並被測試直接呼叫（`every unicode separator is collapsed`、`normalising never changes the length of the text`）。
- 驗收 8（§2.11）— **pass**。探針以原專案檔案的複本（`cmp` 確認相同）逐項驗三個字元 × 四個使用點，結果如下：

  | 字元 | 訊息端整形 | 查詢端整形（走完整入口） | 算不算空查詢 | 欄位開頭 |
  |---|---|---|---|---|
  | U+0020 | 收成 U+0020 | 收成 U+0020 | 未搜尋 | 移除 |
  | U+00A0 | 收成 U+0020 | 收成 U+0020 | 未搜尋 | 移除 |
  | U+001C | 不收合 | 不收合 | 算在搜尋 | 保留 |

- 驗收 9 — **pass**。三支測試：`every unicode separator is collapsed`（BMP 掃描、字面形式 `"a${c}b" == "a b"`）、`zero-width characters are not whitespace`（U+200B/C/D、U+FEFF，以碼位確認）、`a run of mixed whitespace becomes exactly one space`。
- 驗收 10 — **pass**。`onSearchQueryChange` 只寫回 `withoutLeadingSpace()`，失焦時只寫回 `withoutTrailingSpace()`，整形結果從不寫回欄位。裝置上的「北京 天氣」「北京(2 空白)」逐碼位讀回相符。
- 驗收 11 — **pass**。`SearchUiState.isActive = query.flattenToLine().isNotEmpty()`；測試 `isActive agrees with flattening...` 含 BMP 掃描。

## 任務 3：比對語意的單元測試（每條對到測試並讀過斷言；`clean testDebugUnitTest` 全綠）
- 1 — **pass**。`a message the user wrote is found`、`a message the assistant wrote is found`。
- 2 — **pass**。`matching ignores case`。
- 3 — **pass**。`matching ignores full-width and half-width`（兩個方向）。
- 4 — **pass**。`traditional and simplified are not folded together`。
- 5 — **pass**。`surrounding whitespace in the query is ignored`：size 1，且 `assertEquals(bare, padded)` 比對完整結果。
- 6 — **pass**。`an empty query is not a search`，五個案例皆在。
- 7 — **pass**。`a query that matches nothing returns nothing`。
- 8 — **pass**。`normalising never changes the length of the text`（U+0130，以碼位確認）。
- 9 — **pass**。`a highlight points at the matched text even when the message had newlines`。
- 10 — **pass**。`every match inside the window is highlighted`。
- 11 — **pass**。`a hit deep inside a long message is cropped with a leading ellipsis`（命中在索引 100，長度 62，標示正確）。
- 12 — **pass**。同任務 2 驗收 4。
- 13 — **pass**。`the persona opener can be searched`（作者 Assistant、id 等於第 0 則）。
- 14 — **pass**。`a thread holding nothing but its opener still matches`（1 則／總數 1，不命中時為空）。
- 15 — **pass**。`a shared opener matches in every thread that has it`。
- 16 — **pass**。兩個案例各自獨立：
  - `cropping never splits a surrogate pair`：15 個 😀＋測＋北京，起點想落在索引 11。
  - `...at the window end`：30 BMP＋北京＋7＋20 個 😀，終點想落在索引 70。
  - 兩案例都斷言頭尾無落單代理，且片段含查詢詞；emoji 為單一字、未用國旗。
  - 靜態推演：起點案例的終點落在 BMP 區、終點案例的起點落在 BMP 區，只讓一側保護失效時只有對應案例變紅。
- 17 — **pass**。
  - 下限：BMP 掃描，字面形式。
  - 上限：四個零寬字元與 👨‍👩‍👧 不變。
  - 收合：`"北京\r\n\t (U+0020)(U+3000)天氣"` → `"北京 天氣"`；前後去除另有一行斷言。
- 18 — **pass**。`a message written with a full-width space is found with a plain one`。
- 19 — **pass**。四支測試都走 `searchConversations`：U+3000 查詢、3 個空白、U+00A0 訊息、只有 U+3000。
- 20 — **pass**。`a highlight is as long as the flattened query, not what was typed`（長度 5）。
- 21 — **pass**。`a query with a space inside is one string...`。
- 22 — **pass**。`isActive agrees with flattening...`（U+3000、U+00A0、`" \t\n"` 未搜尋，`"a"` 在搜尋，BMP 掃描）。
- 23 — **pass**。`a hit shorter than the window keeps its lead-in`、`...exactly fills...`、`...longer than the window is clamped...`：L=59 與 L=60 預期不同，並有 `assertNotEquals`。
- 24 — **pass**。`persona and model names are not searched`（老師、Qwen、qwen，含正向對照）。
- 25 — **pass**。`ellipses appear only where text was actually cut`：同一方法內兩個方向。
- 26 — **pass**。`searching finds message text rather than the derived title`（「還沒說話」不命中，開場白字命中）。

## 任務 4：「所有對話」的定義與搜尋子狀態
- 驗收 2 — **pass**。`allThreads contains the open thread even when it is blank`、`all threads are ordered newest content first`。
- 驗收 3 — **pass**。`allThreads keeps the in-memory copy when a thread arrives twice`、`the same thread arriving twice appears once`。
- 驗收 6 — **pass**。`the in-memory copy wins even when the saved one looks newer`（ConversationSearchTest）：`same` 100／`same` 900／`other` 500，斷言順序 `other`、`same` 且命中數 2。

## 任務 5：狀態層的搜尋入口與衍生結果
- 驗收 2（盡力而為）— **未能觀察直接現象；取得條文允許的間接佐證**。
  - 模型與提示：Gemma3 1B，「Write a very long detailed story about the sea, at least 600 words」。
  - 串流時長：送出到落盤約 37 秒（撞到長度上限）。送出後約 10 秒進入搜尋，查「,」，每 3–4 秒 dump 一次共 7 次。
  - 攔到的：串流中那則回覆（「Okay, here's a long, detailed story about the sea,」）在檔案落盤之前已出現在結果裡，這是條文列的間接佐證。
  - 沒攔到的：「命中數自行增加」。進入時該回覆已含逗號，而摘要數字是以「則」計，同一則訊息只會加一次；片段又以第一個命中為窗，後續串流不改變片段。
- 驗收 5 — **pass**。搜尋「applepie」後設為橫向：仍在搜尋畫面，欄位 `applepie`、摘要「3 段對話・9 則訊息」，輸入框 bounds 在頂端；轉回直向亦同。
- 驗收 6 — **pass**。多次搜尋之後，送出、串流、停止、切換人格與模型皆照常（E-1、E-8、E-9）。
- 驗收 7 — **pass**。換人格為孫子產生空白對話，查「!」（折疊到「！」），第一組就是那段 SmolLM 空白對話，片段為其開場白。
- 驗收 8 — **pass**。
  - 模型就緒後 hashdir、`logcat -c`，進搜尋打「applepie」（正向：3 段 9 則）再離開，前後 hashdir 相同。
  - 狀態列前後都是「Gemma3 1B・離線」。
  - 該 pid 的 33 行 log 全是 IME／Insets／Autofill，沒有模型載入或推論紀錄。

## 任務 6：抽屜入口與搜尋畫面外殼
- 驗收 1 — **pass**，逐項：
  - (i) 上半部依序：模型（三個）、並排比較所有模型、歷史紀錄、「每段對話各自獨立，不會互相影響」、開始新的對話、搜尋對話內容。連續 5 次捲動歷史列表，「模型」節點 bounds 始終為 `[53,184,139,247]`。
  - (ii) 三個入口：
    - 「並排比較所有模型」→ 模型比較畫面，抽屜關閉。
    - 「開始新的對話」→ 新對話，標頭前後都是 🙌 朋友 / Gemma3 1B，抽屜關閉。
    - 「搜尋對話內容」→ 搜尋畫面。
  - (iii) 每列有 emoji、人格、模型、時間、標題、預覽、刪除鈕。時間：今天「02:16」等，非今天「9/14」。
    - 長英文句：標題 `this is a very long first …`、預覽 `this is a very long last message used as…`。
    - 「你好」：標題與預覽整句、無「…」。
  - (iv) 點列開啟並關閉抽屜；按刪除鈕即刪，無任何確認文字。
  - (v) 清空資料後顯示「還沒有其他對話。」。
  - (vi) 開著 A（applepie一號）時列表有 B、沒有 A；開 B 後列表有 A、沒有 B。
- 驗收 2 — **pass**。入口是列、非輸入框，點擊後抽屜關閉並進入搜尋。
- 驗收 3 — **pass**。進入時 EditText `focused`，`mInputShown=true`。
- 驗收 4 — **pass**。查「,」（12 段 14 則）連續捲動 8 次，EditText bounds 始終 `[148,147,1058,305]`。
- 驗收 5 — **pass**。
  - 畫面返回鍵：回聊天。
  - 系統返回鍵：鍵盤開著時第一次只收鍵盤、仍在搜尋；第二次回聊天，焦點仍在 App，未退出。
- 驗收 7 — **pass**。模型勾選預設全開，問「Say hello in one sentence」，Gemma3 1B／Qwen2.5 0.5B／SmolLM 135M 三張卡依序「完成」且有回答文字，左上箭頭回聊天。
- 驗收 8 — **pass**。同任務 5 驗收 5（橫向＋鍵盤的可見量不判）。
- 驗收 9 — **pass**。
  - 注音輸入「北」，選字後 1 秒內出現「1 段對話・3 則訊息」。
  - 接著輸入「京」，1 秒內變成「1 段對話・2 則訊息」。
  - 可點節點只有命中列、返回、欄位、清除搜尋，沒有任何搜尋鈕。

## 任務 7：結果列表
- 驗收 1 — **pass**。結果區自摘要列延伸到畫面底（截圖），可整片捲動。
- 驗收 2 — **pass**。
  - 截圖：命中字加粗、改色，位置正確（applepie、湖邊、今天、高雄 港口）。
  - 靜態：顏色取自 `MaterialTheme.colorScheme.primary`，無寫死色碼。
- 驗收 3 — **pass**。查「,」命中多段開場白（第 0 則）與其他訊息，12 段捲到底無錯亂、無崩潰。
- 驗收 4 — **pass**。applepie 5 則那組顯示 3 則＋「還有 2 則符合」。
- 驗收 5 — **pass**。長訊息命中列單行、以「…」省略、未破版（截圖）。
- 驗收 6 — **pass**。
  - 輸入：Gboard「繁體中文（台灣）注音」版面，按鍵以 `adb shell input text` 送注音鍵位，再點候選字；未繞過輸入法。
  - 查「湖邊」：訊息中 😀 在命中前 7 個字元內，片段 `…跑步運動，大家都很有精神😀，後來我們在湖邊找到…`。標示恰在「湖邊」，emoji 與全形標點算繪正常、未破版。
- 驗收 7 — **pass**。
  - 各組標頭只有 emoji／人格／模型／時間，無標題。
  - 空白對話（孫子 SmolLM、只有開場白）的標頭不含「還沒說話」。
- 驗收 8（§7.1）— **pass**。直向、鍵盤彈出（IME frame 上緣 y=1541）、不捲動：applepie 可見 3 個標頭與 7 列命中；「今天」可見 6 標頭＋6 列（截圖）。
- 驗收 9（§7.2）— **pass**。`wm density 606`（依環境文件的等效最嚴格值），鍵盤彈出：可見「朋友」標頭＋3 列命中（截圖），之後 `wm density reset`。
- 驗收 11 — **pass**。三組標頭依序為 🙌 朋友 Gemma3 1B 02:26 / 📘 老師 Qwen2.5 0.5B 02:16 / 💗 情人 SmolLM 135M 02:06，隨對話改變。

## 任務 8：空狀態、無結果、點擊與返回
- 驗收 1 — **pass**。只有「搜尋所有對話的訊息內容」。
- 驗收 2 — **pass**。「zzqqxx」只有「找不到符合「zzqqxx」的訊息」。
- 驗收 3 — **pass**。
  - 開著 A 時點「老師我想吃applepie」列 → 聊天顯示該對話（Qwen2.5 0.5B）。
  - 再進搜尋，欄位為空、顯示引導文案。
  - 前後 hashdir 相同。
- 驗收 4 — **pass**。點目前開啟那段的命中 → 內容不變，點擊後 0.3 秒與 2 秒皆無「正在載入」橫幅，狀態列維持「離線」，hashdir 相同。
- 驗收 5 — **pass**。
  - 換人格為孫子 → 查「!」→ 點其開場白列 → 回聊天、仍是孫子 SmolLM 空白對話。
  - 無載入橫幅；對話檔 27→27，hashdir 相同。
- 驗收 6 — **pass**。命中列節點沒有任何「刪除」content-desc。
- 驗收 7 — **pass**。「3 段對話・9 則訊息」，列出 3＋2＋2。
- 驗收 8 — **pass**。三種離開都清空查詢（畫面返回、系統返回、點命中列後再進入皆為空）；旋轉保留查詢與結果。
- 驗收 9 — **pass**。搜尋中 Home → `am kill`（pidof 為空）→ `am start` 顯示 task 從背景帶回：停在搜尋畫面、欄位空、引導文案。
- 驗收 10 — **pass**。
  - 輸入後欄位 `kiwi  `（6b 69 77 69 20 20），文案 `找不到符合「kiwi  」的訊息`。
  - TAB 失焦後欄位 `kiwi`、文案 `找不到符合「kiwi」的訊息`。
- 驗收 11 — **pass**。
  - 冷啟動後 1 秒內進搜尋打「applepie」：已是「3 段對話・9 則訊息」，無 ProgressBar／載入字樣。「載入尚未完成的瞬間」未能攔到，條文明文不影響判定。
  - 正向：結果含歷史對話。
- 驗收 12 — **pass**。
  - 欄位為 `applepiequeryzz` 時，`session.xml` 只有 last_thread_id／last_model／last_persona 三鍵，`grep -c applepie` = 0。
  - 正向：force-stop 冷啟動仍續接 `qa-apple2a`。
- 驗收 13 — **pass**。推入只含助理訊息的 `qa-x1`，開著另一段。搜「xonlyassistant1」點該列 → 聊天顯示 X1 內容。（之後離開 X1 被清掉，為 §9.1 行為，不算 fail。）
- 驗收 14 — **pass**。三種點擊情境（驗收 3、4、5）都回聊天並顯示對應對話，各自前後 hashdir 相同。

## 任務 9：回歸驗證與中文算繪
- 驗收 2 — **pass**。`TextRepair.kt`（及 ModelMarkdown）grep 不到 `flattenToLine`、`normalizeForSearch`、`searchConversations`。
- 驗收 3 — **pass**。
  - 上下文過濾在 `ChatViewModel.send` 的 `.dropWhile { !it.fromUser }`。
  - 搜尋在 `ConversationSearch.searchConversations`，對所有訊息 `mapNotNull`。
  - 兩者沒有共用函式。
- 驗收 4 — **pass**。§9.1：E-6 四種離開與換人格產生的空白對話前後檔案數量與 hashdir 相同。§9.2：同任務 1 驗收 6。
- 驗收 5 — **pass**。逐項同任務 6 驗收 1 (i)–(vi)；截短切字那項依條文不在範圍。
- 驗收 7 — **pass**。
  - 同一則「高雄(U+3000)港口很美」：抽屜預覽節點逐碼位為 `9AD8 96C4 3000 6E2F…`。
  - 搜尋片段逐碼位為 `9AD8 96C4 20 6E2F…`。
- 驗收 8 — **pass**。注音輸入「今天」：
  - 摘要「12 段對話・13 則訊息」，捲動收集得 12 個不同分組標頭，與摘要一致。
  - 標示蓋在「今天」，片段未破版（截圖）。
- 驗收 9 — **pass**。「湖邊」片段前後皆有「…」，標示恰蓋「湖邊」；測資命中前 20 字內含 😀。
- 驗收 10 — **pass**。注音「台」→ 只有「台中很熱」；「臺」→ 只有「我住在臺南」，兩者結果不同。
- 驗收 11 — **pass**。
  - 測資：兩則訊息分別為「高雄(U+3000)港口很美」「高雄(U+00A0)港口很美」，由檔案推入。
  - 操作：注音選「高雄」、按空白鍵（欄位 `9AD8 96C4 20`）、再選「港口」。
  - 結果：「2 段對話・2 則訊息」兩則都命中；截圖中兩列標示都蓋住「高雄 港口」含中間空白位置。
- 驗收 12（選作）— **本環境無法執行**。U+3000 打不出來（`input text` 送非 ASCII 會拋例外，注音與英文版面都沒有全形空白鍵），因此放不進剪貼簿。語意由任務 3 驗收 19 承接（pass）。
- 驗收 13 — **pass**。8–11 全在鍵盤彈出（`mInputShown=true`，IME 上緣 y=1452）時判讀；「今天」查詢時複驗 §7.1，可見 6 標頭＋6 命中列。

## 任務 10：更新架構文件（讀 `plan/architecture.html`）
- 驗收 1 — **pass**。「輸入框裡留下什麼，跟拿去比對的是兩回事」含開頭立即移除、尾端到失焦才移除、比對用整形後文字。
- 驗收 2 — **pass**。「（只有空白）→ 不算搜尋」列含「這是比對層的契約：搜尋框本身打不出這種輸入」。
- 驗收 3 — **pass**。
  - (i)「不變條件」第一條三件事俱在：只跟內容走（新增／改文字／移除）、切過去看一眼或切走不算、「最近被看過」的理由。
  - (ii)「不算」一側不含「按下停止」。
  - (iii) 其他提到停止的段落都是停止路徑與標記的描述，沒有宣稱它不算更新。
- 驗收 4 — **pass**。三列路徑都存在：
  - `ui/SearchScreen.kt` 無 ViewModel、只收 state 與回呼，標示只套用傳入的區間。
  - `chat/ConversationSearch.kt` 負責命中、裁切、標示。
  - `data/ConversationRepository.kt` 是 ChatViewModel 唯一使用的對話存取入口（ConversationStore 只被它使用）。
- 驗收 5 — **pass**。全文找不到「最後開啟」等第二個時間概念。
- 驗收 7 — **pass**。「命中本身有 60 個字（含）以上時裝不下引子，視窗就從命中的第一個字開始」→ 剛好 60 時引子為 0。

## 任務 11：摘要的兩個數字維持現狀
- 驗收 2 — **pass**（同任務 8 驗收 7）。
- 驗收 3 — **pass**。空查詢與「zzqqxx」「kiwi」無命中時都沒有摘要列。

## 任務 12：查詢欄位的空白規則
- 驗收 1 — **pass**。
  - 空欄位按空白（軟鍵盤空白鍵、`keyevent 62`）：欄位逐碼位為空，顯示引導文案，無「找不到」。
  - **長按空白鍵**：先試真的——長按軟鍵盤空白鍵叫出「Change keyboard」選單，不會連續輸入空白，做不出來。改用替代做法：連續送 3 次空白鍵事件，欄位仍為空、仍顯示引導文案。
  - **貼上單一 U+3000／U+00A0**：本環境**能**經由 UI 複製貼上（見驗收 2），但這兩個字元無法產生——`input text` 送非 ASCII 會拋 `NullPointerException`，輸入法版面上沒有這兩個鍵。因此記「**本環境無法觀察**」，由驗收 6 承接（pass）。
- 驗收 2 — **pass，走的是真正的貼上，不是替代做法**。
  - 在聊天輸入框以注音打出「(3 個半形空白)北京」，Select all → Copy。
  - 到搜尋欄位長按 → Paste，欄位逐碼位為 `5317 4EAC`，並出現結果。
  - 為證明剪貼簿內容確實含前導空白，同一份剪貼簿貼進聊天輸入框，逐碼位讀回 `20 20 20 5317 4EAC`。
  - **依條文回報：這個環境已經能設剪貼簿**（經由 UI 的選取工具列；`adb shell cmd clipboard` 仍不存在）。條文裡「設不了剪貼簿時的替代做法」依條文所述可以移除，請 PM 處理。
- 驗收 3 — **pass**。欄位 `北京  `（5317 4EAC 20 20），游標移到最前後刪兩次，欄位為空、顯示引導文案。
- 驗收 4 — **pass**。「北京」＋空白 → `5317 4EAC 20`；再選「天氣」→ `北京 天氣`，結果為「我在北京 天氣很好」（1 段 1 則）。
- 驗收 5 — **pass**。TAB 失焦後欄位去掉尾端空白，重新點回欄位仍為 `kiwi`。
- 驗收 6 — **pass**。測試 `the field uses our whitespace rule, not the built-in one`：
  - U+00A0 開頭被移除；U+001C 開頭保留（碼位確認）。
  - 並斷言內建 `trim()` 在 U+001C 上答案不同。
- 驗收 7 — **pass**（同任務 8 驗收 10）。
- 驗收 8 — **pass**。「北京(2 空白)」時記錄結果（1 段 2 則、兩列），TAB 失焦後欄位 `北京`，結果清單逐項相同（diff 無差異）。
- 驗收 9 — **pass**。空查詢契約測試仍在且通過。
- 驗收 12 — **pass**。
  - 畫面：欄位「北京」，`keyevent 122` 移到最前再按空白 → 仍 `5317 4EAC`；正向：`keyevent 123` 移到尾端按空白 → `5317 4EAC 20`。
  - 不經過畫面：探針 `" 北京".withoutLeadingSpace()` → `北京`。

## 任務 13：對話「最後更新時間」的定義
- 驗收 1（§8.3）— **pass**。開著 A（applepie一號，較新），從抽屜開 B 再開回 A 之後：
  - 搜尋「applepie」分組順序相同，標頭時間 02:26／02:16／02:06 相同。
  - 抽屜順序與時間 02:16／02:06／02:01／01:56… 相同。
- 驗收 2（§8.4）— **pass**。
  - `qa-old` 推入時 updatedAt = 1789324567097（9/14）。
  - 開啟並切走一次後仍為同值，以該 App 寫出的檔為基準；再開啟、切走，檔案 `cmp` 完全相同。
- 驗收 3（§8.6）— **pass**。
  - 在 Y 串流中、畫面已有 170 字時按停止。
  - 預期一：存檔最後一則助理回覆以「…（已停止）」結尾。
  - 預期二：updatedAt 比停止時刻（裝置時鐘）晚 95 ms，比切換時刻早 179 秒（≥30 秒）。
  - 註：第一次嘗試時停止鈕按在回覆出現文字之前（零文字停止，回覆整則不留），不符前置條件，已重做。
- 驗收 4（§8.5）— **pass**。在 Y 送出，完整回覆後 updatedAt 前進到回覆完成時刻；搜尋「,」第一組為 Y（03:08），切到別段後抽屜第一列為 Y。
- 驗收 5（§8.7）— **pass**。
  - B（孫子 Qwen `qa-old`）送出「update B say hi」，回覆結束 03:33:27；等 65 秒後「開始新的對話」產生 N（03:34:42）。
  - 查「!」：第一組 N（標頭 03:34）、第二組 B（03:33），N 在前且時間晚於 B。
- 驗收 6 — **pass**（靜態）。整個 main 原始碼設定 `updatedAt` 的地方只有兩處：`newThread`（建立）與 `updateThread`（以 `hasSameContentAs` 判斷）。所有訊息新增、改文字、移除都經 `updateThread`；存檔路徑只存不蓋時間；讀檔是從 JSON 讀出。
- 驗收 7 — **pass**。
  - 可判定一：四種離開各用一段新的 S，都在回覆出現文字且仍在增加時切走。
    - S1 開另一段（切走時約 42 字）、S2 開始新的對話（71 字）、S3 換人格（36 字）、S5 換模型（Qwen→Gemma，70 字）。
    - 每次存檔都有使用者訊息，回覆以「…（已停止）」結尾。
    - force-stop 冷啟動後從抽屜打開 S1/S2/S3/S5，畫面都顯示提示與以「（已停止）」結尾的回覆。
    - 提示「S* write 600 words on owls」；模型 S1 Gemma3 1B，S2、S3、S5 為 Qwen2.5 0.5B。
    - 另有一次 S4 無效：換成的模型與目前相同，未發生離開，回覆照常結束，不計。
  - 可判定二：Y 送出「Reply with one short sentence about tea」，回覆結束（檔案更新）後立即 force-stop 冷啟動 → 續接 Y，使用者訊息與完整回覆「今天天气不错，想喝杯热茶。」都在。
- 驗收 8 — **pass**。
  - 送出：訊息與回覆出現。串流：回覆逐步增加直到結束。停止：回覆不再增加。
  - 刪除：那一列消失。換人格或模型：新對話、頂端換成所選。冷啟動：回到上次那段。

## 任務 14：讓補上來的條文各自有東西在驗
- 驗收 1 — **pass**。每條都指得出驗法：
  - 任務 1 驗收 10：程式碼位置 `ConversationRepository.find`。
  - 任務 3 驗收 25：測試 `ellipses appear only where text was actually cut`。
  - 任務 5 驗收 8、任務 6 驗收 9、任務 7 驗收 11、任務 8 驗收 11–14：本報告所列的裝置步驟與判讀點。
- 驗收 2 — **pass**。任務 3 驗收 25 是可自動化的那一條：無條件補「…」的實作會讓 `assertFalse(whole.snippet.contains("…"))` 變紅，只在有截時才補的正確實作不會。
- 驗收 3 — **pass**。正向對照兩半皆有執行：
  - 3-25：同方法兩方向。
  - 5-8：非空結果。
  - 6-9：「北」「北京」結果改變。
  - 7-11：換人格／模型標頭改變。
  - 8-11：歷史對話在結果內。
  - 8-12：冷啟動續接。

## 任務 15：`features.md` 指向本規格的引用
- 驗收 1 — **pass**。共 40 處引用，逐處檢查節號存在、節名 grep 得到、兩者同一節。腳本初判 §5.2（歷史-09、-11）與 §2.8（搜尋-20）不一致，原因是它先匹配到卷首修訂記錄裡的「**§5.2 補上一項」；人工複核 `spec.md` 第 279、411 行的節標題正是那兩個節名，確認相符。
- 驗收 2 — **pass**。搜尋-02、-13、-20 仍帶引用。
- 驗收 3 — **pass**。無任何 `conversation-search/v…` 版號目錄。

## 任務 16：只含助理訊息的既有對話檔
- 驗收 2 — **pass**。
  - X 在歷史清單上，標題「還沒說話」；點開顯示其內容。
  - 四種離開（開 Y、開始新的對話、換人格為孫子、換模型為 SmolLM）各用一個新 X，離開後各自的檔都不在。
  - 冷啟動後捲完抽屜 0 列、搜「xonlyassistant」為找不到。
  - 冷啟動入口：推 `session.xml` 指向 `qa-xcold`，冷啟動續接到它，不點其他東西直接開 Y → 檔不在。
  - 訊息清單為空的檔：點開再開 Y → 檔不在。
  - 正向：Y 點開再離開後檔仍在，訊息（作者＋文字）與推入時相同。
- 驗收 3 — **pass**。
  - 開著 A（deletetestA），刪 B：無確認、對話檔 22→21、B 的檔不在、A 的檔 `cmp` 相同。
  - 冷啟動後抽屜 0 列 deletetestB，搜尋為找不到。
- 驗收 4 — **pass**。
  - 換人格產生的空白對話，以開另一段、開始新的對話、換人格、換模型四種方式離開，每次前後檔案數 25→25、hashdir 相同。
  - 正向：新對話送出「positivecheck say hi」、回覆結束後離開 → 檔案 25→26，含該訊息的檔存在。
- 驗收 5 — **pass**。保存-02 寫明一句話都沒說過的不存、不進歷史；以及從 App 以外放入、無使用者訊息的檔會出現在歷史、打開再離開被清掉。引用 §9.1、§6.4 皆追得到。

## 任務 17：抽屜標題／預覽截短不得把一個字切成兩半
- 驗收 1 — **pass**。
  - 測試 `a title cut in the middle of an emoji keeps no half of it`、`a preview ...`（25＋🌧、39＋🌧）。
  - 斷言：無落單代理、以「…」結尾、是原文前綴、emoji 前的字都在。
  - 靜態推演：直接 `take(26)` 會留下 U+D83C，`hasLoneSurrogate` 為真，測試變紅。
- 驗收 2 — **pass**。`an emoji that exactly fills the limit is kept whole with no ellipsis`（24＋🌧、38＋🌧）。
- 驗收 3 — **pass**。`guarding emoji leaves the rest ... as they were`：一般長句「…」、剛好 26／40 無「…」、換行變空白、U+3000 保留（碼位確認）、「還沒說話」。
- 驗收 4 — **pass**。
  - 抽屜 `qa-cut26` 列：標題 26 個 code point（25 個「字」＋「…」），預覽 39 個「字」＋「…」，無落單代理。
  - uiautomator dump 正常；截短結果屬「…前是 emoji 之前的字全在」。
- 驗收 5 — **pass**（同任務 6 驗收 1 (iii)）。
- 驗收 6 — **pass**。
  - `a title/preview cut inside a combined emoji keeps all of it or none of it`：👨‍👩‍👧、🇹🇼、👍🏽 各一，24／38 BMP，預期字串直接寫在測試。
  - `stepping out of a combined emoji keeps the character before it`（21＋👍🏽＋👨‍👩‍👧）。
  - `a combined emoji that exactly fills the limit...`（18／32）。
  - `a single character longer than the limit...`（e＋40／60 個 U+0301）。
  - 靜態推演：只保護代理字元的做法會在 26 處留下 👨／🇹／👍，三類案例皆變紅。
- 驗收 7 — **pass**。
  - `qa-famtitle` 標題：24 個「字」＋「…」（尾碼 `5B57 2026`），不是落單 👨，屬驗收 6「只有那 24 個 BMP 字」。
  - `qa-flagprev` 預覽：38 個「字」＋「…」，不是落單 🇹，屬「只有那 38 個 BMP 字」。
  - 皆為 dump 逐碼位讀取。

## 任務 18：搜尋片段不得把一個字切成兩半
- 驗收 1 — **pass**。`a window starting inside a combined emoji starts at a whole character`：三類字、查 kiwi，兩種可接受開頭直接寫成字串，並斷言標示恰為 kiwi。
- 驗收 2 — **pass**。`a window ending inside a combined emoji ends at a whole character`（30 甲＋kiwi＋34 乙＋字＋30 丙）。
- 驗收 3 — **pass**。
  - `a window already between two characters is not moved`：👍🏽👍🏽 起點不動、🇹🇼🇯🇵 終點不動。
  - `whole-character cropping leaves the plain window rules as they were`：L=59/60/61 與短訊息。
  - 下限由任務 3 驗收 16 兩支測試（同一測試類別）承接。
- 驗收 4 — **pass**。`a character longer than the window still leaves the hit on screen`（片段含「k」，且完整含或完全不含那個長字）。
- 驗收 5 — **pass**。推入 `qa-kiwi` 後查「kiwi」：
  - 起點形狀那列開頭碼位為 `2026 4E59 4E59…`，即「…」之後直接是「乙」，不是 ZWJ 或 👩，屬驗收 1「直接從第一個乙開始」。
  - 終點形狀那列結尾為 `…4E59 4E59 2026`，即「…」前是「乙」，不是落單 🇹，屬驗收 2「它前面的最後一個乙」。
  - 兩列都完整含 kiwi。

# 邊界情況檢查
- §1.2 標題不參與比對：任務 3 驗收 26 測試。
- §1.8 串流中搜得到：任務 5 驗收 2 取得間接佐證；切人格空白對話搜得到且在最前（任務 5 驗收 7）。
- §1.9 無第四種樣子：任務 8 驗收 11。
- §3.5 合併：任務 4 驗收 3、6 測試。
- §3.6 跨對話第 0 則：「,」與「今天」皆命中多段開場白，捲動無錯亂。
- §3.7 同人格開場白全部出現：「今天」出現 4 段姊姊開場白分組。
- §4.9 emoji 算繪：湖邊案例。
- §5.7：任務 8 驗收 9、12。
- §6.1：任務 8 驗收 13。
- §7.3：橫向＋鍵盤可見量未判，依條文。
- §8.2 串流中切走：任務 13 驗收 7 可判定一，留下文字加中斷標記並落盤。
- §9.4：任務 16 驗收 3。
- 空查詢在畫面上不可達（§2.4 驗收層級）：由測試承接。

# 如果 fail：問題歸屬
無 fail。**需要 PM 知道的一件事**（非缺陷）：任務 12 驗收 1、2 的「環境改善時回報」已觸發——本模擬器環境能經由 UI 選取工具列設定剪貼簿，任務 12 驗收 2 本輪已照真正的貼上驗過。任務 12 驗收 1 的「貼上單一 U+3000／U+00A0」仍卡在字元產生不出來，而不是剪貼簿，所以那一段替代說明的**原因描述**需要 PM 更新。

# 驗證方式說明
1. **標準測試指令** — 做了。`./gradlew clean testDebugUnitTest`，log 只有 `:app:clean` 與 `:app:testDebugUnitTest`、無 UP-TO-DATE；9 個 XML 共 136 tests、0 failures／errors／skipped，timestamp 為本輪。
2. **指名測試的條文** — 做了。任務 2、3、4、12（6、9）、17（1–3、6）、18（1–4）逐條對到測試方法並讀斷言；關鍵字元以腳本輸出碼位確認，不是看編輯器顯示。
3. **邊界情況** — 做了，見上節。
4. **裝置上的條文** — 在模擬器上實際操作完成。受測建置證明：
   - `installDebug` 因空間不足失敗，刪掉該次失敗留下的暫存 APK 後以 `adb install -r -t` 串流覆蓋安裝成功，未解除安裝。
   - `lastUpdateTime=2026-09-16 02:28:54`，晚於 HEAD 提交時間 02:20:09；裝置上 base.apk 大小 1857203015 與本機建置相同。
5. **隨機抽測三項** — 做了，見下節。
6. **真實資料處置** — 依 prompt 明講「使用者已同意清空驗收裝置上的 App 資料」，走清空路線，不做快照、還原與逐位元組驗證。清掉 `files/conversations/`（1 個既有檔）與 `shared_prefs/session.xml`；保留模型檔 `files/gemma3-1b.task` 與 `profileInstalled`。

**其他主機端驗證**：以環境文件 §12 的方式，把四個原始檔複製到 scratchpad（`cmp` 確認與專案相同），加一支探針編譯執行，驗任務 2 驗收 8 與任務 12 驗收 12 的不經過畫面那一層。專案原始碼未被修改。

**系統設定還原**：`accelerometer_rotation` 原值 1、`user_rotation` 原值 0，已還原；density 原值 420、無 override，已 reset；輸入法版面進場時為 English (US)，收尾時為 English (US)。

**偏離 `test-cases.md` 的地方**：
- `test-cases.md` 寫於主機端讀取、唯讀 adb 查詢（`devices`、`df`、`ime list` 等）與第一次 `installDebug` 失敗之後，但在任何 App 操作與覆蓋安裝成功之前。
- 情境 B 的測資改過一次：原本 5/2/2 與 §8.3 用中文「蘋果派」，發現 `input text` 送不進中文後，改成 ASCII「applepie」重推，並重新冷啟動；抽屜相關觀察（B1-1〜B1-6）是在改之前做的，與改的內容無關。
- B2-12（U+3000 訊息的搜尋片段）併進 C-5，用注音打「高雄 港口」一起判讀。
- B2-4 查詢改用「,」（折疊到全形逗號），讓多段開場白第 0 則同時命中。
- kiwi 測資在 A-8（無命中）做完之後才於情境 B 中段推入。
- E-8 第一次嘗試無效（零文字停止）、E-9 的 S4 無效（換到相同模型），各自重做一次。
- D-5 的點擊前後沒有另做 hashdir：該檔依 §9.1 離開後本來就會被清掉，任務 8 驗收 14 的檔案判定以驗收 3、4、5 的三次 hashdir 為準。
- 任務 12 驗收 2 原計畫走替代做法，實際改走真正的貼上（條文要求先試真的）。

整輪不是只到靜態閱讀：裝置條文皆在模擬器實際操作；少數條文以靜態閱讀為主（任務 1 驗收 10、任務 2 驗收 1／10、任務 9 驗收 2／3、任務 13 驗收 6、任務 7 驗收 2 的色碼那半），已在各條註明。

# 本輪驗證範圍
全驗。呼叫者給的範圍即「conversation-search 的全部，照 spec 完整驗收」。

# 環境文件的處置
對 `docs/qa-environment.md`：
- **驗過**：§0（zsh 陷阱；本輪又踩到一次 `set --`）、§1（JDK 與 clean test）、§4 的清空資料作法、§5（`ui.py selftest` OK）、§5a（手勢區 frame 當場查詢）、§6（注音鍵位選字、`keyevent 62` 在無組字時插入 U+0020、快速兩次空白的間隔作法）、§7（TAB 失焦）、§8（density 與旋轉的套用／還原）、§9（`am kill` 還原 task）、§10（`hashdir.sh selftest` OK）、§12（主機端編譯探針）。
- **修正**：§2 從「實機 Pixel 9」改成模擬器，確認方式加 `ANDROID_SERIAL` 與 Android 版本。
- **新增**：
  - §3：大型 APK 覆蓋安裝空間不足時的處理（刪自己那次失敗的暫存 APK、改用串流安裝、禁止解除安裝），附確認方式。
  - §0：`set -- $VAR` 在 zsh 不切字，附確認方式。
  - §5：固定區與清單列文字相同時要加 y 範圍挑節點。
  - §6：模擬器同樣掛注音與英文、長按空白鍵叫出版面選單、組字中空白鍵是一聲。
  - §6.2：模擬器同樣只送得出 ASCII。
  - §6.5：模擬器上經由選取工具列複製貼上走得通，以及驗剪貼簿內容的方法。
- **刪除**：未發現越界的產品觀察內容，沒有刪除。

# 本輪隨機抽測的三項功能
（從 `features.md` 中「對應 spec」不指向本規格、且從未被挑過的 48 項裡隨機抽出。以下觀察**沒有**拿來改變上面的 pass／fail。）
- **比較-10**「執行中不能更改模型的勾選」：按「執行」後立即 dump，三個勾選節點都是 `enabled="false"`，當時卡片狀態為完成／完成／生成中、按鈕為「停止」。**與文件描述相符。**
- **比較-16**「按下停止之後，還沒輪到的模型也會被標成失敗，卡片上是同一段英文錯誤訊息」：按「執行」後 0.4 秒按「停止」，三張卡片都變「失敗」，文字皆為 `StandaloneCoroutine was cancelled`。**與文件描述相符。**
- **聊天-13**「模型什麼都沒產生就結束時，泡泡裡是『（這次沒有產生回覆，再說一次試試）』」：用 SmolLM 135M 送出「.」，模型產生了長回覆並以「…（已達長度上限）」結束，**沒有觸發「什麼都沒產生」的情形**，該文案未能觀察。沒有發現與描述不符之處。

# 隨機挑選記錄的處置
在 `docs/qa-random-coverage.md` 追加三行（既有檔案，追加）：`2026-09-16  比較-10`、`2026-09-16  比較-16`、`2026-09-16  聊天-13`。
