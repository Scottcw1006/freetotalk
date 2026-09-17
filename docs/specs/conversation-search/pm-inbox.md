# PM 收件匣（對話內容搜尋）

## [2026-09-17] 使用者裁決：把指向 history-missing-after-switch 的引用改指 conversation-store-db，之後刪掉那個目錄
- 來源：使用者裁決
- 內容：
  - `history-missing-after-switch` 的正確行為（H1–H4）與決策 D1，已被 `docs/specs/conversation-store-db/` 吸收並驗收通過（該 slug 現行版 v4；吸收對照在它的 `spec-appendix.md`「吸收 history-missing-after-switch 的對照」）。使用者要刪掉 `docs/specs/history-missing-after-switch/`，刪之前本規格現行版裡指向它的地方要先改指 `docs/specs/conversation-store-db/`。
  - **範圍只有改引用。** 使用者明確裁決：**這次不算 conversation-store-db 那一輪 D4 所說的「修訂」**，所以不要改寫本規格裡綁 JSON 檔的條文（那份改寫清單留到真正修訂時），**這一輪也不跑 QA 驗收**。不得改變任何規則或任何驗收要驗的性質。
  - RD 找到的現行引用（v3.24）：`spec-tasks.md` 任務 16 驗收 1 的移除理由（約第 702 行）、「明確不做」裡「任務 16 的行為不要求自動化測試」那一條的理由與觸發條件（約第 845、847 行）；`spec-decisions.md` 與 `spec-appendix.md` 多處「跨輪分辨過」的歷史敘述。請自己再 grep 一次，以現行版全部為準。
  - **RD 觀察（供你判斷）**：上面那條觸發條件（「`history-missing-after-switch` 的決策 D1、或『對話儲存改成資料庫』那一題拍板時，回來補一個自動化測試」）**已經成立**：conversation-store-db 已上線，它的 D1 裁決是「資料庫層不寫自動化測試」，而「存一段沒有使用者訊息的對話要刪掉同 id 那筆」的判斷已由它的任務 1 驗收 4（不碰資料庫的測試）守住。改引用時請把這條寫成現況，不要再指向一個已經刪掉的目錄；若你判斷處理這條觸發會改到任何規則或驗收，停下來寫進回報，不要自己決定。
  - 順帶看到其他因儲存改成資料庫而過期的敘述（例如任務 16 說明裡的「搬遷既有檔」），**不要改**，列進回報即可。
- 相關條目：spec-tasks.md 任務 16、「明確不做」；spec-decisions.md；spec-appendix.md
- 狀態：待處理

---

- **這個檔是意見信箱，不是檔案櫃。** 有事情要交給 PM 時，在這裡加一筆（標題含日期、來源是「使用者裁決」還是「RD 觀察」／「QA 報告」、相關條目、內容）。**PM 處理完就把那一筆刪掉**，所以沒有待處理事項時它就是現在這個樣子。
- **被刪掉的是通知，不是理由。** 每一筆的實質內容在被刪之前都已經落在別的地方：規則進 `spec.md`／`spec-tasks.md`，選項、代價與裁決理由進 `spec-appendix.md`／決策歷程，而「哪一輪處理並移除了哪幾筆、各自落在哪一節」列在該輪的 `changes-v<N>.md`。**原文在版本控制歷史裡**（`git log -p -- docs/specs/conversation-search/pm-inbox.md`）。
- **（2026-09-16）本檔在 v3.24 依新規則清空。** 此前累積的、**全部已標為「已處理」**的那一批一併移除；它們的處理結果落在各輪的 spec 與變更說明裡，原文在版本控制歷史裡。**v3.24 這一輪處理並移除的三筆，列在 `changes-v3.24.md`／「本輪處理並移除的收件匣項目」。**
