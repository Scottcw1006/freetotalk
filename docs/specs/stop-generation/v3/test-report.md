# 驗證結果總覽

**pass** —— B1（停止後部分回覆被錯誤訊息取代）與 B2（停止那一次不落盤）在實機上都不再重現；R1–R11 全數成立，任務 1–5 的每一條驗收標準都通過，「明確不做」的範圍沒有被越界。

驗證方式：`./gradlew clean testDebugUnitTest installDebug`（測試 80 項全綠）＋ Pixel 9 實機操作 ＋ 一次由我自己執行的 mutation 驗證（在 scratchpad 複本上改壞，不動專案原始碼）。

---

# 逐項驗收結果

## 任務 1：把「這則回覆最後留下什麼」收到一個地方，並讓它能被 JVM 測試直接呼叫

- **驗收標準 1**（存在「部分文字 + 使用者停止」的完整字串相等測試，且被標準測試指令執行到）
  - **結果：pass**
  - 說明：`app/src/test/java/com/example/demo/ReplyEndingTest.kt` 的 `a stopped reply keeps exactly what was on screen and says it was cut short` 以 `assertEquals(ReplyOutcome.Keep("…完整字串…"), finalReply(onScreen, ReplyEnding.UserStopped))` 斷言**完整相等**（不是 contains、不是非 null）。`./gradlew clean testDebugUnitTest` 的結果檔 `app/build/test-results/testDebugUnitTest/com.example.demo.ReplyEndingTest.xml` 顯示 `tests=9 failures=0 errors=0 skipped=0`，確認它真的被執行（用 `clean` 排除 UP-TO-DATE）。

- **驗收標準 2**（重現案例成為永久測試，且以註解指回 `findings.md`）
  - **結果：pass**
  - 說明：該測試的輸入就是 `findings.md` 重現情境（`tell me a long story about a cat`、串流 3–5 秒後停止，已顯示的那段貓的故事）。檔案有兩處指回：類別註解「Reproduction steps, the failing output and the diagnosis are in docs/specs/stop-generation/findings.md.」與測試內註解「The reproduction from findings.md: ...」。

- **驗收標準 3**（同一組測試涵蓋另外四種結束方式，其中「失敗」斷言仍含錯誤字樣）
  - **結果：pass**
  - 說明：`Complete`（含空字串走佔位文字）、`LengthCap`、`RepetitionLoop`、`Failed` 各有測試；`a real failure still reads as an error` 斷言 `ReplyOutcome.Keep("出錯了：out of memory")`，守住 R7。

- **驗收標準 4**（不依賴 Android 環境）
  - **結果：pass**
  - 說明：`ReplyEnding.kt` **完全沒有 import**；`ReplyEndingTest.kt` 只 import `com.example.demo.chat.*` 與 JUnit。`app/build.gradle.kts` 的 `testImplementation` 仍只有 `libs.junit`（沒有新增協程測試工具、Robolectric 或 mock 框架）。

- **驗收標準 5**（把「使用者停止」改回舊行為，驗收 1 的測試必須變紅）
  - **結果：pass（由我自己重跑一次，不採信任何回報）**
  - 說明：把 `ReplyEnding.kt` **複製到 scratchpad**，在複本上把 `UserStopped` 分支改成 `ReplyOutcome.Keep("出錯了：StandaloneCoroutine was cancelled")`，用 Gradle 快取裡的 kotlin-compiler-embeddable 單獨編譯並跑 `ReplyEndingTest`：`Tests run: 9, Failures: 4`，變紅的四支是
    `a stopped reply keeps exactly what was on screen and says it was cut short`（＝驗收 1 那一支）、`stopping appends the mark and changes nothing else`、`a stopped reply is not an error`、`stopping before anything arrived leaves nothing behind`。
    **專案原始碼全程未被修改**（沙箱也擋下了對 `app/src` 的寫入），做法已寫進 `docs/qa-environment.md` 第 11 節。

## 任務 2：使用者停止不再走錯誤那條路，且停止後狀態收斂

- **驗收標準 1**（照重現步驟 1–4，不再變成「出錯了：…」，滿足 R1）
  - **結果：pass**
  - 說明：新對話送出 `tell me a long story about a cat`，串流 3 秒後按停止。停止後那則回覆為
    `故事開始的時候，我們看到的是一只名叫“絮”的小猫，它出生在一個破舊的小屋裡，生活簡簡單暮。絮很小，毛…（已停止）`
    不以「出錯了」開頭，全文不含 `cancel` / `Cancel` / `Exception` / `Job` / `Coroutine`。

- **驗收標準 2**（R2 三點全判）
  - **結果：pass**
  - (a) 結尾字元逐碼位確認為 `['0x2026','0xff08','0x5df2','0x505c','0x6b62','0xff09']` —— 一個刪節號字元 U+2026 ＋ 全形括號，共 6 個字元。並確認 U+2026 與 `…（已達長度上限）`、`…（模型卡在重複迴圈，已中斷）` 開頭是**同一個字元**（三個字串在原始碼中的第一個非 ASCII 碼位都是 0x2026）。
  - (b) 停止前一刻的 UI dump 與停止後的文字以程式比對：`post.endswith(mark)` 為真、`post[:-6].startswith(pre)` 為真、`post[:-6]` 中標記出現 **0** 次（擋掉重複追加）。兩者差 25 個字元，是「我 dump 完到我按下停止」之間繼續串流進來的字，**不是被改寫**；除此之外前文一字未動、未換行、未去尾端空白。
    （限制：無法取得「按下停止那一奈秒」的畫面，前綴相符是這個環境能給的最強判定；`stop()` 取的是畫面上那則訊息本身的文字，且 `repairModelText()` 在串流時就已套用，狀態與畫面同一份。）
  - (c) 標記與前文之間沒有空白或換行（`…毛…（已停止）` 直接相接）。

- **驗收標準 3**（R3：5 秒內文字不變、打字指示器消失、回到送出鈕）
  - **結果：pass**
  - 說明：停止後 t+1s 與 t+6s 兩次 dump 的回覆文字**逐字相同**；輸入列右側 content-desc 從無變回 `送出`；標題列狀態由 `Gemma3 1B・輸入中…` 變回 `Gemma3 1B・離線`。**沒有出現「停止後又多一段文字」或「永遠停在還在生成」**。本輪共觸發六次停止（手動 2 次，含 1 次零文字；由切換隱含觸發 4 次），其餘五次以停止後的畫面與存檔確認同樣已收斂：按鈕回到送出、狀態不再是「輸入中」、文字含且**只含一次**中斷標記 —— 已知衝突第 2 條所擔心的新故障沒有出現。

- **驗收標準 4**（R4：出現任何字之前停止）
  - **結果：pass**
  - 說明：送出 `say something long please` 後立刻連點停止。畫面上使用者那則訊息還在，**其後沒有任何助理泡泡**（既沒有空白泡泡，也沒有「只有 `…（已停止）`」的泡泡）。存檔內容同步確認：訊息只到 `3 You 'say something long please'`，那則空回覆整則不留。

- **驗收標準 5**（R9：停止後立刻可以再說話）
  - **結果：pass**
  - 說明：緊接著送出 `hi`，正常收到完整回覆（不需等待、不需切換、不需重開）。順帶驗到已知衝突第 4 條：此時上下文出現兩個連續使用者發言（id 3 與 id 4），**回覆品質沒有可見異常**，不需要回頭改 R4。

- **驗收標準 6**（R7：非取消的失敗仍顯示錯誤訊息）
  - **結果：pass**
  - 說明：以任務 1 驗收 3 的測試為準（`出錯了：out of memory` 完整相等）。程式面核對：`send()` 的 `catch (e: Exception) { ending = ReplyEnding.Failed(e.message ?: e.toString()) }` 與 `finalReply` 的 `"出錯了：${ending.message}"` 合起來與修正前的 `"出錯了：${e.message ?: e.toString()}"` **文案與格式完全相同**。

## 任務 3：停止那一次必須落盤，而且寫的是被停止的那一段

- **驗收標準 1**（重現步驟 5：檔案數比送出前多 1）
  - **結果：pass**
  - 說明：送出前 `files/conversations` 為 **14** 個檔；按下停止後**不做任何其他操作**立刻再數，為 **15**（+1）。原本的失敗現象是「完全沒有增加」。存檔內容含那則部分回覆與標記。

- **驗收標準 2**（R5 預期二：force-stop 後再開，文字相同）
  - **結果：pass**
  - 說明：`adb shell am force-stop com.example.demo` 後重開，App 回到該段對話，那則部分回覆**連標記一起**與停止時逐字相同。

- **驗收標準 3**（R6：切走那段含部分回覆；切入那段位元組完全相同）
  - **結果：pass**
  - 說明：串流途中從抽屜切到既有對話 `4795c7d1…`。被切走那段的檔案新增 `10 You / 11 Assistant …（已停止）`；被切入那段的檔案**前後 SHA-256 相同、`cmp` 逐位元組相同**。已知衝突第 1 條的隱蔽形式（把磁碟讀出、經文字修復的舊對話原樣寫回導致位元組變動）也**沒有發生**：另外在非串流狀態下切走 `4795c7d1…`，檔案同樣逐位元組不變。

- **驗收標準 4**（R10：切換人格／切換模型／開新對話各驗一次）
  - **結果：pass**
  - 說明：三種各做一次，被切走的那段都留下部分文字（非錯誤訊息）且已落盤：
    - 切換人格（老師→情人）：`1ce9f3d2…` 訊息 2 為 `…真的是超重要的東西！跟我們生活…水就像是地球…（已停止）`
    - 切換模型（Gemma3 1B→Qwen2.5 0.5B）：`7e891b4f…` 訊息 2 為 `好的，這是一篇關於雲的長篇Essay…一場無垠的詩…（已停止）`
    - 開新對話：`173c8aeb…` 訊息 2 為 `In the vast expanse of th…ace of…（已停止）`

- **驗收標準 5**（若做法讓停止可能在沒有使用者訊息時觸發，必須回報）
  - **結果：pass（不需回報，我自己核對過）**
  - 說明：`stop()` 在 `messages.lastOrNull { it.streaming } ?: return` 早退，**早退發生在那次存檔之前**，所以沒有串流中的回覆時不會寫任何檔；而串流中的回覆只可能由 `send()` 建立，`send()` 必然先加入一則使用者訊息。實測也一致：反覆開新對話而不送訊息時，檔案數不增加（空白對話仍不落檔）。

## 任務 4：回歸驗證

- **驗收標準 1**（正常送出到自然結束：文字正確、落盤、抽屜排最前）
  - **結果：pass**
  - 說明：`hi` 得到完整回覆並寫檔；抽屜歷史中該段排在最前（清單有保留捲動位置的特性，捲回頂端後確認）。

- **驗收標準 2**（R8：兩個自動中斷標記一字未改，且兩者仍會觸發）
  - **結果：pass**
  - 說明：**長度上限**在實機自然觸發，存檔文字長度 809＝800 上限＋9 字標記，結尾碼位 `[0x2026,0xff08,0x5df2,0x9054,0x9577,0x5ea6,0x4e0a,0x9650,0xff09]`＝`…（已達長度上限）`。**重複迴圈**以 SmolLM 135M 觸發，畫面結尾為 `…（模型卡在重複迴圈，已中斷）`，且重複的尾巴確實被收短。兩個字串在原始碼與畫面上都一字未改。

- **驗收標準 3**（非串流狀態下切換人格／模型／對話／開新對話／刪除，全部照常）
  - **結果：pass**
  - 說明：五種操作都在非串流狀態各做過至少一次，行為正常；刪除後檔案數 20→19、抽屜對應列消失、其餘列不受影響。

- **驗收標準 4**（冷啟動回到上次那段；上次是從未存檔的空白對話時以記住的模型開新的）
  - **結果：pass**
  - 說明：(a) force-stop 後重開，回到停止前那段對話。(b) 另做一次：開新對話但不送任何訊息（`session.xml` 的 `last_thread_id=9e91c3a2…`，磁碟上沒有這個檔），force-stop 後重開 → 開出一段新的空白對話，模型是記住的 Gemma3 1B、人格是記住的姊姊。

- **驗收標準 5**（搜尋不受影響；停止後的部分回覆必須搜得到）
  - **結果：pass**
  - 說明：(a) 在一段**尚未落盤**的新對話串流途中進搜尋查 `zebra` → 命中「1 段對話・2 則訊息」，其中一則就是**正在串流的那則回覆**（該對話的檔案在回覆結束後才出現，檔案數 19→20，證明搜尋當下它確實還沒存檔）。(b) 查 `expanse`（取自某則被停止的部分回覆）→ 命中該對話該則訊息。

- **驗收標準 6**（`conversation-search` §8.3、§8.4 仍通過）
  - **結果：pass**
  - 說明：§8.3 —— 開著 A，記下查 `long` 的分組順序與各組時間文字、以及抽屜順序；從抽屜開 B 再開回 A 之後重查，**整份結果逐行 diff 完全相同**，抽屜順序也相同。§8.4 —— 切走一段沒有在串流的對話 A，A 的存檔**逐位元組不變**。
    （依已知衝突第 5 條，**§8.6 不列入本輪判定**。附帶記錄：`conversation-search` 的 spec 已自行改到 v3.6，§8.2 那一項已移除、§8.6 已換成驗中斷標記的版本，與本輪 R11 一致，沒有留下相反宣稱。）

- **驗收標準 7**（既有的四支 JVM 測試全部通過）
  - **結果：pass**
  - 說明：`clean testDebugUnitTest` 全綠：ConversationSearchTest 48、DegenerationTest 5、ExampleUnitTest 1、ModelMarkdownTest 9、TextRepairTest 8、ReplyEndingTest 9，failures/errors 皆 0。（「四支」是 spec 寫作當時的數字，`conversation-search` 那一輪之後既有測試已是五支；本輪新增第六支。）

- **驗收標準 8**（比較畫面：記錄現況，不判 fail）
  - **結果：pass（記錄已完成）**
  - 說明：比較畫面送出 `tell me a story`，在 Qwen2.5 0.5B 正在生成、SmolLM 135M 尚未輪到時按下停止。畫面實際出現的字串**原樣抄錄**如下，請 PM 補進 `spec-appendix.md`：
    - Gemma3 1B（已完成）：狀態 `完成`，內容不受影響，計時列保留。
    - Qwen2.5 0.5B（正在生成，已有部分文字 `在遥远的未来，银河系的一角，有一个名为光之谷的地方。在这个`）：狀態變成 `失敗`，內容整則被覆寫成 `StandaloneCoroutine was cancelled`。
    - SmolLM 135M（**還沒輪到**）：狀態 `失敗`，內容 `StandaloneCoroutine was cancelled`。
    - 截圖：`/private/tmp/claude-501/-Users-scotthuang-AndroidStudioProjects/9ae440a4-4314-4830-922d-41a257e18608/scratchpad/qa/compare_after_stop.png`
    - 這與 `spec-appendix.md`／查證事實第 6 點「讀出來」的推論一致（含「還沒輪到的模型也會各自被標成失敗」），現在是量出來的事實。**本輪不修，不判 fail。**

## 任務 5：更新架構文件

- **驗收標準 1**（圖說補上第三條分支並說明差別）
  - **結果：pass**
  - 說明：`plan/architecture.html` 圖說已寫「有三個觸發條件：…或使用者按下停止」，並另起一句說明機制差異：「前兩條是讓串流自己收束，流程正常走完；停止是取消整個工作。差別會露在收尾上——被取消的工作裡，掛起的存檔呼叫不會執行，所以停止那一次的落盤必須另外安排」。SVG 內的 `aria-label` 與中斷分支文字也一併更新。

- **驗收標準 2**（`awaitClose` 那行逐字核對仍成立與否）
  - **結果：pass**
  - 說明：該行維持原文「02 至 06 全程持有 Mutex；Flow 收束時透過 awaitClose 取消生成，與『停止』按鈕共用同一條路徑」。我自己核對過它**現在仍然成立**：`stop()` → `replyJob?.cancel()` → collector 收束 → `LlmEngine.reply` 的 `callbackFlow` 走 `awaitClose { … cancelGenerateResponseAsync() }`，與 Flow 自行收束（長度上限／重複迴圈）走的是同一段。維持原文是正確處置。

- **驗收標準 3**（「幾條刻意維持的規則」第一條：把「按下停止」從「不算」那一側移除，其餘一字不動）
  - **結果：pass**
  - 說明：現行句子為
    「**對話的「最後更新時間」只跟著內容走。**新增訊息、訊息文字改變、訊息被移除才算一次更新；切過去看一眼、切走都不算。否則「最近」會變成「最近被看過」，而歷史清單與搜尋結果的排序都靠這個時間。」
    對照 `pm-inbox.md` 抄錄的修改前原句，差異**只有刪掉「、按下停止」五個字**。逐條判定：(i) 已不含「按下停止」；(ii) 句首原則與句尾理由與修改前**逐字相同**；(iii)「不算」那一側只剩「切過去看一眼、切走」兩項。另外全檔搜過「不算」，**沒有**另外補一句「按下停止算一次更新」，也沒有任何其他地方仍宣稱停止不算一次更新。

- **驗收標準 4**（模組地圖 `ChatViewModel.kt` 那列補上使用者停止與停止當下的存檔）
  - **結果：pass**
  - 說明：該列現為「對話狀態、人格與模型切換、長度上限與迴圈中斷、**使用者停止與停止當下的存檔**、存檔、啟動還原、查詢字串」。

- **驗收標準 5**（新增純邏輯列，且「四支測試各對應一塊」的數字跟著改）
  - **結果：pass**
  - 說明：模組地圖新增「純邏輯 / `chat/ReplyEnding.kt` / 一則回覆有五種結束方式（正常、長度上限、重複迴圈、使用者停止、失敗），各自留下什麼文字，以及哪一種不留下回覆」；表下那句已改為「**五支測試各對應一塊**」，與現況（TextRepair、Degeneration、ModelMarkdown、ConversationSearch、ReplyEnding 五塊純邏輯 ↔ 五支對應測試）相符。

- **驗收標準 6**（發現文件其他地方與程式碼不符要列出回報）
  - **結果：pass（我自己掃過一遍，列在下方「附帶回報給 PM」）**
  - 說明：本輪改到的四處都正確；我另外掃到一處**與本輪無關的既有小出入**（見文末），不影響本輪判定。

---

# 架構決策符合度

spec 沒有獨立的「架構決策」章節，硬性約束分散在「修正範圍」、各任務的「對應功能模組」與「明確不做」。逐條核對：

| 約束 | 結果 | 依據 |
|---|---|---|
| 「留下什麼」由**一個地方**決定，且認得五種結束方式 | **符合** | `chat/ReplyEnding.kt` 的 `finalReply(streamed, ending)` 是唯一決定處；`ReplyEnding` 是**封閉**的 sealed interface，五個成員各對應一種結束方式，`when` 沒有 `else` 分支（新增第六種會編譯失敗）。`settle()` 與 `stop()` 都呼叫它，沒有第二份文案。 |
| 任務 1 對應**純邏輯層**（與文字修復、重複偵測、Markdown 解析、搜尋比對同層） | **符合** | `ReplyEnding.kt` 放在 `chat/` 與其他四塊純邏輯同一層、**零 import**、不碰 Android/Compose/持久化；架構文件的模組地圖也把它列在「純邏輯」列。 |
| 任務 2 對應**聊天狀態層**（`ChatViewModel` 的送出／停止路徑） | **符合** | 改動只在 `send()` 與 `stop()`；停止不再落進 `catch (e: Exception)`，改為 `catch (e: CancellationException) { throw e }` 讓取消原樣往外拋，狀態由 `stop()` 自己收斂。 |
| 任務 3 走**既有的那條存檔路徑，不新增第二條** | **符合** | `stop()` 用的是既有的 `repository.save(...)`（`ConversationRepository` 是聊天層唯一入口），沒有新增第二條寫檔路徑、沒有直接碰 `ConversationStore`、沒有改存檔格式。差別只在「從一個被取消的工作裡呼叫」變成「從 `viewModelScope` 另起的工作裡呼叫」。 |
| 「必須能被不依賴 Android 的測試直接呼叫」 | **符合** | 見任務 1 驗收 4、5。 |
| **不改真正的錯誤怎麼呈現**（R7） | **符合** | 錯誤文案與格式與修正前逐字相同（`出錯了：` ＋ `e.message ?: e.toString()`）。 |
| **不改兩種自動中斷的任何一個字**（R8） | **符合** | 兩個字串在 `ReplyEnding.kt` 中與 `spec-appendix.md` 記錄的原形一致（含 `withoutRepeatingTail()` 仍先套用於重複迴圈那一支），實機也仍會觸發。 |
| **不改切換對話／人格／模型時既有的那次存檔** | **符合** | `switchPersona` / `switchModel` / `openThread` 三者各自的 `repository.save(...)` 都還在原位，沒有被搬走或合併；R6／§8.4 的位元組比對證實那條路徑行為不變。 |
| **不為了測試把引擎或儲存改成可注入、不引入 mock 框架、不新增 UI 測試** | **符合** | `app/build.gradle.kts` 的 `testImplementation` 仍只有 JUnit；`LlmEngine`／`ConversationRepository` 的建構方式沒有改；`app/src/androidTest` 沒有新增。 |
| **不改比較畫面** | **符合** | `compare/CompareViewModel.kt` 沒有引用 `ReplyEnding`／`finalReply`，仍保有自己的 `catch (e: Exception)` 與 `"已取消"`；實機上比較畫面仍是舊行為（見任務 4 驗收 8），正是「沒被動過」的證據。 |
| **不動 `conversation-search` 目錄下的檔案**（本輪只產出結論） | **符合** | 本輪的產物只有 `chat/ReplyEnding.kt`、`chat/ChatViewModel.kt`、`ReplyEndingTest.kt`、`plan/architecture.html`。 |

**一點架構上的觀察（不構成 fail）**：`finalReply` 的**結果如何套用**（`Drop` → 移除該則訊息、`Keep` → 覆寫文字並關掉串流旗標）在 `settle()` 與 `stop()` 各寫了一次，是兩份形狀相同的 `when`。spec 要求的是「**留下什麼文字**由一個地方決定」，這一點成立（文案與 Drop/Keep 的判斷都只在 `finalReply` 裡）；重複的只是套用動作。之所以不判 fail，還因為 `stop()` 的註解已說明它**必須**在 `stop()` 裡就地套用（被取消的工作稍後才收尾，屆時開著的可能已不是原本那段對話）—— 這正是 R6 要防的那件事，合併反而會把它打破。

---

# 邊界情況檢查

| # | 已知衝突／邊界情況 | 對應驗收 | 結果 | 怎麼確認的 |
|---|---|---|---|---|
| 1 | 停止後補寫的那次檔可能寫到別段對話上（含「把讀出後修復過的舊對話原樣寫回導致位元組不同」的隱蔽形式） | R6／任務 3 驗收 3 | **已處理** | `stop()` 在更新完狀態後先把 `val stopped = _uiState.value.conversation` **抓在手上**，再 `viewModelScope.launch { repository.save(stopped) }`，寫入對象固定成被停止的那一段。實測：串流中切到既有對話，被切入那段 `cmp` 逐位元組相同；另外在非串流狀態切走一段真實舊對話，其檔案同樣逐位元組不變。整輪結束後 14 個使用者對話檔 ＋ `session.xml` 全部通過 `shasum -c`。 |
| 2 | 停止之後可能還有最後一個片段落地（文字又長一段，或永遠停在「還在生成」） | R3／任務 2 驗收 3 | **已處理** | 六次停止（1 次手動＋3 次切換隱含＋1 次零文字＋1 次比較畫面外的重跑）之後，文字在 t+1s 與 t+6s 完全相同，打字指示器都有消失、按鈕都有回到送出。程式面：`send()` 的 collector 與 `stop()` 同在主執行緒，且取消後的 channel 續接會以取消結束，不會再寫回。 |
| 3 | 收尾區塊其他動作跟著恢復執行 → 停止後多一次抽屜刷新；切換時會前後刷新兩次 | 任務 4 | **已處理** | 每次停止／切換後都 dump 過抽屜：項目**不重複、不缺項**（檔案數與列數一致，扣掉當下開著的那一段），排序仍照最後更新時間遞減。唯一要注意的是清單會保留捲動位置（環境特性，已寫進 `qa-environment.md`），不是內容錯誤。 |
| 4 | R4 移除空回覆會讓下一則訊息的上下文出現兩個連續使用者發言，回答品質可能變化 | R9／任務 2 驗收 5 | **已處理，無異常** | 零文字停止把那則空回覆移除後，該段對話的最後一則是使用者訊息（id 3）；緊接著再送一則（id 4）就形成兩個連續的使用者回合 —— 存檔實際長成 `… / 3 You / 4 You / 5 Assistant`，這個形狀確實出現了。那一則**正常得到切題回覆**，沒有語法或品質異常。不需要回頭改 R4。 |
| 5 | `conversation-search` §8.6 通過不代表 R11 成立；§8.6 節名在 S1 拍板後是假的 | 不列入本輪判定 | **已依規定處置** | 本輪**沒有**拿 §8.6 去判 fail。R11 改以可觀察的文字驗證（存檔中的 `…（已停止）`）。附帶量測：停止那則的 `updatedAt`（裝置時鐘）換算回主機時刻後落在按下停止的當下 —— 也再次印證「以秒為單位分不出來」。另記：對方的 spec 已改到 v3.6，§8.2 那一項已移除、§8.6 已換條文，與本輪 R11 一致。 |
| 6 | 比較畫面很可能有同型缺陷，但沒有實測 | 任務 4 驗收 8（只記錄，不判 fail） | **已量出事實** | 見任務 4 驗收 8 的逐字記錄與截圖。結論與 PM 讀程式碼的推論完全一致，包含「還沒輪到的模型也被標成失敗」。**本輪不修，不判 fail。** |

---

# 如果 fail：問題歸屬

本輪**沒有 fail 項目**，沒有要打回 RD 或 PM 的東西。以下兩點是附帶回報，不影響判定：

**附帶回報給 PM（任務 5 驗收 6 的「其他不符」）**
- `plan/architecture.html` 圖說寫「偵測到之後會把重複的尾巴**收成兩輪**」，`chat/Degeneration.kt` 的 `withoutRepeatingTail()` 註解也寫「Collapses a repeated tail down to **two rounds**」，但實作與既有測試都是**保留三輪**（`abababab` → `ababab`）。這是**本輪之前就存在**的敘述誤差，R8 明令這一段一字不得改，所以本輪**不應該**動它 —— 記在這裡供 PM 決定要不要另開一輪修文字敘述。

**附帶回報給 PM（規格用語的小瑕疵，不影響本輪任何判定）**
- 任務 4 驗收 7 寫「既有的**四支** JVM 測試全部通過」，但 `conversation-search` 那一輪之後既有測試已是五支（多了 `ConversationSearchTest`）。同一份 spec 的任務 5 驗收 5 又要求把架構文件的數字「跟著改」，實作寫成「五支」是對的。**條文寫死的數字與現況不一致**，下一輪冷讀時可能被誤判；建議 PM 把這類數字寫成「既有的全部 JVM 測試」。本輪我以「全部既有測試皆通過」判定 pass。

---

# 驗證方式說明

**有實際執行，不是靜態閱讀。** 具體做了：

1. **建置與安裝**：`./gradlew clean testDebugUnitTest installDebug`（BUILD SUCCESSFUL）。安裝後 `lastUpdateTime` 晚於全部原始碼的最後修改時間，確認實機上跑的是本輪原始碼建置出來的成品（進場時裝置上那份也已經是新的，但我仍重建重裝一次，不靠既有安裝下判斷）。
2. **JVM 測試**：讀 `app/build/test-results/testDebugUnitTest/*.xml` 的機器可讀結果，不只看 console。六個測試類別、共 80 個測試，failures/errors 皆 0。
3. **Mutation 驗證**：在 scratchpad 的**複本**上把 `UserStopped` 改回舊行為，用 Gradle 快取的 kotlin-compiler-embeddable 編譯並執行 `ReplyEndingTest`，確認 9 支中 4 支變紅（含驗收 1 指名的那一支）。專案原始碼未被修改。
4. **實機（Pixel 9，Android 16）**：新建測試對話，執行手動停止 ×2、零文字停止 ×1、切換對話／人格／模型／開新對話各 ×1 的隱含停止、force-stop 還原 ×2、冷啟動 ×2、長度上限與重複迴圈各 ×1、搜尋 ×3、§8.3／§8.4、刪除對話 ×1、比較畫面停止 ×1。判讀一律用 `uiautomator dump` 的節點文字與 `run-as` 讀出的存檔 JSON，**不靠肉眼看截圖**；標記字串逐碼位比對。
5. **未執行**：R7 的實機重現（spec 明文「實機不強制重現」，以 JVM 測試為準）；`conversation-search` §8.6（依已知衝突第 5 條不列入本輪）。

**使用者資料的處置**：動手前把 14 個對話 JSON 與 `shared_prefs/session.xml` 完整快照並記 SHA-256；測試資料一律新建（本輪共產生 7 段測試對話，其中 1 段用來驗刪除功能）。收尾時先 force-stop，刪掉全部 7 段測試對話、還原 `session.xml`，再重新抓一次全部檔案與 `shasum -a 256 -c` 比對 baseline：**15 個項目全部 OK**。之後又重啟一次 App（為了把輸入法版面切回原本的注音）並再驗一次雜湊，**仍然全部 OK**。系統設定方面：本輪**只讀不改**（進場時記下 `accelerometer_rotation=1`、`user_rotation=0`、`density=420`、`size=1080x2424`，全程未變更，收尾時無需還原）。唯一改到的裝置狀態是 Gboard 的輸入版面（注音→英文），**已切回注音並以截圖確認**。

---

# 本輪驗證範圍

**全驗。** spec 的 R1–R11、任務 1–5 的每一條驗收標準、「已知衝突與邊界情況」六項，全部逐條驗過。

呼叫者給的「變更涉及的範圍」我當成下限而非上限：除了那四個檔案，我也核對了 `compare/CompareViewModel.kt`、`data/ConversationStore.kt`、`llm/LlmEngine.kt`、`app/build.gradle.kts` 有沒有被順手動到（沒有），並跑完整套回歸。

依呼叫者指示，`plan/architecture.html` 與 `ConversationSearchTest.kt` 中屬於 `conversation-search` 那一輪的改動**不以本輪條文判定**；本輪只判 spec 任務 5 指名的四處。「明確不做」表列的六項**沒有拿來判 fail**。

---

# 環境文件的處置

`docs/qa-environment.md` 本輪**沿用前一輪建立的版本，逐條驗過後修訂**：

- **驗過且仍成立（未改動）**：§1 建置工具鏈（`java -version` ＋ `gradlew -v` 通過，JDK 25 / Gradle 9.5）、§2 實機（`adb devices -l` 見到 Pixel 9）、§3 證明裝置上是哪一份建置（兩種做法都用了）、§4 私有資料快照／推檔／還原（整輪就是照它做的，最後 `shasum -c` 全 OK）、§6.1–6.2 輸入法版面判讀與英文輸入、§6.4 剪貼簿（`adb shell cmd clipboard` 仍回 `No shell command implementation.`）、§8 啟動／停止。
- **驗過但只用到讀取的部分**：§7 顯示大小與方向 —— 讀原值的三條指令都成立；本輪沒有套用 `wm density` / `user_rotation`，所以那兩條的「套用與還原」我沒有實際驗，維持原文與原日期。§9 本輪沒有依賴，未動。§6.3 中文輸入本輪沒用到，未動。
- **修訂／補強**：§5「讀畫面」的已知限制原本只說「鍵盤會蓋在覆蓋式版面上」，實際踩下去的症狀是**點了完全沒反應**，很容易被誤讀成受測程式沒回應 —— 補上症狀描述、`mInputShown` 的當場確認方式、以及「這台裝置只有 BACK 收得掉鍵盤、ESCAPE 不行」的實測結果；另補上「可捲動清單會保留捲動位置，每次點之前重新 dump」與「用 `clickable=true` 的節點取 bounds 比從 text 反推可靠」。
- **新增**：§10「比較時間戳之前先量裝置與主機的時差」（附當場可跑的指令，只記技術不記數值，因為時差會變）；§11「不改動專案原始碼，就地跑一份改壞的純 Kotlin 邏輯」（kotlin-compiler-embeddable 的完整叫法、三個一定會踩到的 classpath 坑、jar 路徑一律用 `find` 當場問而不寫死，附 `-version` 當確認方式）。
- **刪除的越界內容**：**沒有**。逐句套過「換成一個完全不同的產品還成立嗎」，前一輪留下的內容沒有出現產品觀察、預期畫面、預期文字或 pass/fail，不需要清理。
- 本輪所有 pass/fail、實際字串、缺陷與對規格的意見**只寫在這份 `test-report.md`**，一個字都沒有進環境文件。
