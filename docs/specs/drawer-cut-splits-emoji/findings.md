# 現象

- **使用者原話**：無（這一筆不是使用者回報的；是 QA 在 v3.18 全驗時撞到、RD 轉交，使用者同意交給 PM）。
- **QA 實際重現到的結果**（`docs/specs/conversation-search/v3.18/test-report.md`「邊界情況檢查」，受測 commit `39d6418`，Pixel 9 實機）：
  - 測資：一段對話（QA 標為 G1）最後一則訊息，**第 40 個 UTF-16 code unit 是 🌧 的高位代理**。
  - 抽屜那一列的預覽節點內容是**落單的 U+D83C 接「…」**。
  - `uiautomator dump` 在該列出現在畫面上時崩潰，logcat：`IllegalArgumentException: Bad surrogate pair (U+d83c U+2026)`。
  - 使用者看得到的後果：預覽尾端一個壞掉的字（方框或問號）再接「…」；另外所有依賴 UI dump 的自動化查詢在那一列可見時全部失效。

# 重現步驟

1. 準備一段對話，讓**最後一則訊息**長度超過 40 個 UTF-16 code unit，而且**第 40 個 code unit（索引 39）是某個 emoji 的高位代理**。例如：39 個 BMP 字元（如「字」）之後接 🌧，再接任意文字。
2. 打開抽屜，讓那段對話出現在歷史列表上（它不能是目前開著的那段 —— 開著的那段不在列表上，`spec.md` §5.2）。
3. 看那一列的預覽：尾端是一個壞掉的字接「…」。或跑 `adb shell uiautomator dump`，會崩潰並在 logcat 留下上面那行。

標題同理：**第一則使用者訊息**超過 26 個 code unit，且索引 25 是高位代理。

# 診斷

`app/src/main/java/com/example/demo/chat/Conversation.kt:43-44`：

```kotlin
private fun String.cutTo(limit: Int): String =
    if (length > limit) take(limit) + "…" else this
```

`length` 與 `take(limit)` 都以 UTF-16 code unit 計。截點落在一個 surrogate pair 的高、低位之間時，`take` 留下落單的高位代理，後面接上 `…`（U+2026），組成一個不合法的 UTF-16 序列。`Conversation.title`（:33，上限 26）與 `preview`（:37，上限 40）都走這裡。

- **這不是 v3.16 修省略號時才出現的**：修之前是 `take(26)`／`take(40)`，同樣會留下落單高位代理，只是後面沒有 `…`。修省略號讓它變成「落單高位 + U+2026」，uiautomator 對這個組合會丟例外。
- 同型的規則在搜尋片段那一邊已經有：`spec.md` §4.4「不得從 surrogate pair 中間切開」，片段那邊有保護與測試（任務 3 驗收 16、23）；抽屜這邊沒有。

# 要不要 PM

**要。** 理由：

- 這個 bug 的正確行為由 `conversation-search` 的 spec 管轄：`spec.md` §5.2（標題與預覽單行、超出以省略號截斷），以及 `v3.18/spec-tasks.md`「明確不做」裡「抽屜標題／預覽截短可能切開 emoji」那一條（它寫著沒有實機重現過、附觸發條件）。所以**併進 `conversation-search` 的收件匣，不開新一輪**；本檔只是重現記錄。
- 「什麼算對」有要決定的地方：截點落在 pair 中間時，是往前退一格（少一個 code unit）還是往後包進整個 emoji（多一個）；上限要不要改成以使用者看得到的字（grapheme）計 —— 組合 emoji（例如帶膚色、ZWJ 家庭）不只兩個 code unit，只處理 surrogate pair 仍可能切開一個看起來是一個字的東西。這些 §5.2 都沒寫過。
- 是否達到「明確不做」的觸發條件，也由 PM 判斷。

# 我還沒做的事

- **沒有修。** 修法依 PM 定義的正確行為決定。
- **沒有寫測試。** 流程要求重現案例必須進測試套件，這要等 spec 有條文再照「先紅後綠」補，現在補等於照一個還沒定義的行為寫測試。
- **沒有在 JVM 上另外重跑一次。** 重現證據是 QA 的實機結果與 logcat；診斷是讀程式碼（`take` 以 code unit 計是 Kotlin 標準行為）。
- **沒有動「截短時補『…』」那個行為**（v3.16 修的）—— 修這個 bug 時它不得被改壞：真的截掉字時仍要有「…」、沒截到時仍不得有。
