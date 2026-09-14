# QA 環境筆記

> **這份文件只回答一個問題：下一個人要怎麼把環境弄到可以開始驗收的狀態。**
> 這裡**不寫**任何產品觀察、預期畫面、預期文字、pass/fail 或缺陷 —— 那些一律寫進該輪的 `test-report.md`。
> 判準：**這句話換成一個完全不同的產品，還成立嗎？** 不成立就不該寫在這裡。
>
> **每一條都附一個當場可以跑的確認方式。用它之前先跑那一條。** 確認失敗就當場改成現在成立的樣子，不要略過、也不要刪掉。
> 每條註明最後確認日期。
>
> **優先記錄「怎麼當場問出來」，而不是「答案是什麼」。** 座標、子目錄名、鍵位、版本號都會過期，而錯的答案比沒有答案更糟。

---

## 0. 主機端 shell 的陷阱（每一個都會讓腳本「看起來成功」）

主機的互動 shell 是 **zsh**，Bash 工具送出的指令也由它執行。

- **zsh 不會把未加引號的變數依空白/換行切成多個字。** `for f in $LIST` 在 zsh 裡只跑**一次**，整串當一個字。
  要逐行處理，先把清單寫進檔案，再用 `while IFS= read -r f; do ...; done < 清單檔`。
  **確認方式**：`L=$'a\nb'; n=0; for x in $L; do n=$((n+1)); done; echo $n` —— zsh 印出 `1`。
- **`adb shell` 會吃掉標準輸入。** 放在 `while read` 迴圈裡時，第一次呼叫就把剩下的清單讀光，迴圈只跑一圈，**而且不報錯**。
  迴圈內一律用 `adb shell -n ...`；`adb exec-out` 建議在行尾加 `< /dev/null`。
  **確認方式**：`printf 'a\nb\nc\n' | while read x; do adb shell echo "got-$x"; done` 只印出 `got-a`；把 `adb shell` 換成 `adb shell -n` 會印三行。
- **zsh 的 glob 沒有匹配時會直接報錯並中止那一行**（`no matches found`），例如 `rm -rf dir/*` 在空目錄上。改用 `rm -rf dir; mkdir -p dir`。
  **確認方式**：`mkdir -p <scratchpad>/empty_x && ls <scratchpad>/empty_x/*` 回 `no matches found`。
- **macOS 沒有 `timeout` 指令。** 需要限時就靠工具的逾時參數，或把腳本寫成自己會停。
  **確認方式**：`command -v timeout` 無輸出。
- **Bash 工具會拒絕含控制字元的指令**（例如 heredoc 裡夾著 U+001C 之類的字元）。需要這種測資時，用檔案寫入工具把原始碼寫成檔案（字元以跳脫或數值表示，例如 `0x1C.toChar()`），不要貼進指令。
  **確認方式**：送出一個含字面控制字元的指令，工具在執行前就回 `command contains control characters`。

*最後確認：2026-09-14（前三條的確認方式逐一跑過；前兩條本輪清理時各實際踩到一次）*

---

## 1. 專案與建置工具鏈

專案根：`/Users/scotthuang/AndroidStudioProjects/demo`（Gradle wrapper 在根目錄）。

Gradle 需要指定 JDK，否則指令會失敗：

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$HOME/Library/Android/sdk/platform-tools:$PATH"
```

**確認方式**（跑得起來就算通過）：

```sh
"$JAVA_HOME/bin/java" -version && ./gradlew -v | head -20
```

常用指令：

| 目的 | 指令 |
|---|---|
| 跑 JVM 單元測試 | `./gradlew testDebugUnitTest` |
| 從零重跑（避免 UP-TO-DATE 讓你以為跑過了） | `./gradlew clean testDebugUnitTest` |
| 建置並安裝到實機 | `./gradlew installDebug` |

**注意**：`testDebugUnitTest` 若顯示 `UP-TO-DATE`，代表**這一輪沒有真的執行測試**。要確定測試真的跑過，加 `clean`，或直接讀結果檔。
**確認方式**：log 裡同時出現 `> Task :app:clean` 與 `> Task :app:testDebugUnitTest`（沒有 `UP-TO-DATE` 字樣）。

**跑很久的腳本會撞到指令逾時。** Bash 工具預設 120 秒、可用參數拉到 600 秒。一邊捲畫面一邊 dump 的腳本很容易超過。
做法：跑測試套件這種不需要盯的丟到背景（`run_in_background`）；裝置操作腳本明確給逾時參數。
**確認方式**：`time <你的腳本>`。

測試結果的機器可讀位置（不依賴 console 輸出）：

```sh
ls app/build/test-results/testDebugUnitTest/*.xml
# 每個 XML 的 root 屬性有 tests / failures / errors / skipped
```

*最後確認：2026-09-14（`clean testDebugUnitTest` 實際跑完，逐檔讀過 XML 的屬性）*

---

## 2. 實機

Pixel 9，USB 連線。

**確認方式**：

```sh
adb devices -l          # 要看到一台 device（不是 unauthorized / offline）
adb shell wm size       # 實際解析度
adb shell wm density    # 實際 density；若有 Override density 會一起列出
```

*最後確認：2026-09-14（`adb devices -l` 回報 model:Pixel_9；`wm size` 1080x2424、`wm density` 420 無 override）*

---

## 3. 實機判讀之前：先證明裝置上跑的是哪一份建置

**這是每一輪都要付的成本，不要跳過。** 對著過期的成品驗，會產出一整份看起來很正常的假結果。

做法（擇一即可，重點是**留下可查證的證據**）：

```sh
# (a) 安裝時間 vs 產品程式碼最後一次變動（專案有 git 時優先用這個）
adb shell dumpsys package <套件名> | grep lastUpdateTime
git log -1 --format='%h %ad' --date=iso -- app/src/main app/build.gradle.kts gradle
git status --porcelain app/src        # 工作區必須沒有未提交的產品程式碼改動

# (b) 沒有 git 時：原始碼檔案的修改時間
find <原始碼目錄> -type f -name '*.kt' -print0 | xargs -0 stat -f "%Sm %N" -t "%Y-%m-%d %H:%M:%S" | sort | tail -5

# (c) 直接重新建置並安裝，然後記下安裝時間
./gradlew installDebug && adb shell dumpsys package <套件名> | grep lastUpdateTime
```

套件名當場問出來，不要寫死在腳本裡：

```sh
adb shell pm list packages -3 | grep -i <你認得的字>
```

判準：安裝時間必須**晚於**最後一次產品程式碼變更時間，且工作區乾淨。把實際數字抄進當輪報告。

**重新安裝不會清掉 app 的私有資料**（確認方式見第 4 節：安裝前後比對雜湊）。

*最後確認：2026-09-14（(a)：`lastUpdateTime` 晚於 `git log -- app/src/main` 的最後一筆，`git status` 乾淨；本輪沒有重新安裝）*

---

## 4. App 私有資料的快照與還原（實機上有使用者的真實資料）

這台是使用者本人的裝置。**動手前先完整快照並記下雜湊，測完逐位元組驗證還原。**

App 是 debuggable 的，所以可以用 `run-as` 讀寫它的私有目錄。
**先把目錄樹問出來，不要假設資料放在哪個子目錄**（子目錄名是產品自己的事，會改）：

```sh
PKG=<套件名>
adb exec-out run-as $PKG ls -lR files
adb exec-out run-as $PKG ls -l shared_prefs
```

**先看一眼檔案大小再決定要不要整包拉下來。** app 的私有目錄裡可能躺著幾百 MB 到 GB 的資產檔，
逐檔 `cat` 出來既慢又沒必要。**大檔改用裝置端就地算雜湊當基準**，只有小檔才拉回主機。

```sh
adb exec-out run-as $PKG ls -l files            # 先看大小
adb exec-out run-as $PKG sh -c 'sha256sum files/*.<大檔副檔名>' > big.sha256   # 就地留基準
```

**快照**（放在本輪自己的 scratchpad 子目錄，**不要沿用 scratchpad 根目錄或別輪留下的目錄**；用 `exec-out` 而不是 `shell`，否則二進位/換行會被改寫）：

```sh
SNAP=<scratchpad>/<本輪子目錄>/snap; rm -rf "$SNAP"; mkdir -p "$SNAP"
adb exec-out run-as $PKG sh -c 'find files shared_prefs -type f ! -name "*.<大檔副檔名>"' | tr -d '\r' > "$SNAP/filelist.txt"
while IFS= read -r f; do
  mkdir -p "$SNAP/$(dirname "$f")"
  adb exec-out run-as $PKG cat "$f" < /dev/null > "$SNAP/$f"
done < "$SNAP/filelist.txt"
(cd "$SNAP" && while IFS= read -r f; do shasum -a 256 "$f"; done < filelist.txt) > "$SNAP/baseline.sha256"
wc -l < "$SNAP/filelist.txt"; wc -l < "$SNAP/baseline.sha256"     # 兩個數字必須相同且不為 0
```

**確認方式**：最後一行印出的兩個數字相等、且等於 `adb exec-out run-as $PKG sh -c 'find files shared_prefs -type f ! -name "*.<大檔副檔名>"' | wc -l`。**0 行 baseline 會讓整個還原協定靜靜失效。**

**推測試資料進去**（不要動既有檔案，用自己認得出來的檔名前綴）：

```sh
adb shell ls -a /data/local/tmp/                  # 先看有沒有別輪殘留
adb shell "rm -rf /data/local/tmp/qa && mkdir -p /data/local/tmp/qa"
ls <本機測資目錄>                                  # 本機來源目錄也要先確認只有本輪的檔案
adb push <本機檔> <本機檔> ... /data/local/tmp/qa/     # 逐檔列出，不要 push 整個目錄
adb shell "run-as $PKG sh -c 'cp /data/local/tmp/qa/<前綴>*.json <資料目錄>/'"
```

**這兩件事都是踩過的**：(a) `/data/local/tmp/<你的目錄>` 可能還留著**前一輪**的東西，接著那句 `cp *.json` 會把它們一起灌進 app 的資料目錄；(b) scratchpad 目錄同樣可能有前一輪的殘留，`adb push <目錄>/.` 會把整包送上去。
**確認方式**：`cp` 之後立刻把資料目錄的檔案清單與快照的清單 `comm -13` 比一次，多出來的只能是你這一輪自己建的那幾個。

**還原並驗證**：

```sh
adb shell am force-stop $PKG                   # 先停掉，否則它可能會再寫回去
# 1. 挑出「現在有、快照沒有」的檔案（含你推的測資與 app 自己在測試中建的檔），寫進清單檔再逐行刪
adb exec-out run-as $PKG sh -c 'find files shared_prefs -type f ! -name "*.<大檔副檔名>"' | tr -d '\r' | sort > now.txt
comm -13 <(sort "$SNAP/filelist.txt") now.txt > extras.txt; cat extras.txt     # 先用眼睛看一次
while IFS= read -r f; do adb shell -n "run-as $PKG sh -c 'rm -f \"$f\"'"; done < extras.txt   # -n 不可省略（第 0 節）
# 2. 快照裡有、但位元組已不同的檔案，從快照推回去
adb shell -n "mkdir -p /data/local/tmp/qa"
while IFS= read -r f; do
  adb exec-out run-as $PKG cat "$f" < /dev/null > cur.bin
  cmp -s cur.bin "$SNAP/$f" || { adb push "$SNAP/$f" /data/local/tmp/qa/restore.bin < /dev/null; \
    adb shell -n "run-as $PKG sh -c 'cp /data/local/tmp/qa/restore.bin $f'"; echo "restored $f"; }
done < "$SNAP/filelist.txt"
adb shell -n "rm -rf /data/local/tmp/qa"
# 3. 重新抓一次全部檔案、重算雜湊，與 baseline 做 diff；大檔用裝置端 sha256sum 與 big.sha256 做 diff
```

**確認方式**：步驟 3 的兩個 diff 都沒有輸出，而且檔案清單與 `$SNAP/filelist.txt` 完全相同。**只看畫面正常不算還原成功。**

**有些檔案會因為「app 被啟動過」而改變，不是你動到的。** Android 執行期自己會寫的標記檔（例如 `files/profileInstalled`）
在每次冷啟動後內容可能不同。**比對雜湊時把這一類檔案分開看**，否則會誤判成還原失敗；它也可以照步驟 2 從快照推回去，推回後整份 diff 應為空。
**確認方式**：什麼都不做，只 `am force-stop` 再 `am start` 一次，前後比對該檔的雜湊。

**`adb shell "run-as $PKG rm -f files/xxx-*.json"` 不會展開萬用字元**（run-as 直接把參數交給 rm，沒有 shell 去展開），
指令會「成功」但一個檔案都沒刪掉 —— **這是無聲失敗**。要展開就得自己開一層 shell：`run-as $PKG sh -c 'rm -f ...'`。
**確認方式**：`adb shell "run-as $PKG rm -f files/nonexistent-*.json"; echo $?` 回 0，而同一個 glob 用 `sh -c 'ls ...'` 回 No such file。

**已知的位元組層面陷阱**：手寫的 JSON 與 app 自己序列化出來的 JSON，**內容相同但位元組可能不同**（縮排、鍵之間的空白、非 ASCII 是否跳脫）。比對雜湊發現差異時，先 parse 成物件再比，才分得出「內容真的被改了」與「只是重新序列化」。
→ 推論：**你自己推進去的測試檔，只要被 app 讀寫過一次就不能再拿位元組雜湊當基準**；使用者原本就有的檔案才可以。
**確認方式**：推一個手寫 JSON，讓 app 讀寫它一次，再拉回來 `cmp`（不同）與 parse 後比較（相同）。

*最後確認：2026-09-14（快照 16 檔 → 推測試檔 → 大量操作 → 刪檔 → 還原偏好與標記檔 → 16 檔雜湊與 baseline 完全相同、大檔雜湊相同；「adb shell 吃 stdin」與 zsh 不切字兩個坑都在本輪清理時實際發生，已改寫進上面的腳本）*

---

## 5. 讀畫面：用 UI tree，不要用肉眼猜座標

```sh
adb exec-out uiautomator dump /dev/tty
```

會吐出一段 XML（前後夾雜一行提示字，parse 前先切掉 `<?xml` 之前與最後一個 `>` 之後）。每個節點有 `text` / `content-desc` / `bounds` / `focused` / `clickable`。

建議做法：**先 dump、從 text 找到目標節點的 bounds、再算中心點去點**，不要把座標寫死在腳本或文件裡 —— 座標會過期，而**錯的座標比沒有座標更糟：它會讓人點到別的東西，然後以為自己驗過了**。

想逐碼位確認某個欄位的內容（截圖看不出空白與不可見字元）：

```sh
# 把 dump 存成 raw.xml 之後
python3 -c "
import xml.etree.ElementTree as ET
d=open('raw.xml',encoding='utf-8').read(); d=d[d.find('<?xml'):]; d=d[:d.rfind('>')+1]
for n in ET.fromstring(d).iter():
    t=n.get('text') or ''
    if t: print(n.get('class'), [hex(ord(c)) for c in t], repr(t))
"
```

**寫 helper 時不要對 text 做 `strip()`** —— 尾端空白正是最需要看清楚的東西，一個順手的 strip 會讓你以為欄位是乾淨的。

**已知限制**：
- dump 只涵蓋前景 app 的視窗，**不包含輸入法的視窗**（候選字列、鍵盤按鍵都不在裡面）。要判讀鍵盤只能用截圖 `adb exec-out screencap -p > x.png`。
- **軟鍵盤佔掉的範圍可以當場問出來**，不必從截圖量：`adb shell dumpsys window | grep "type=ime frame"` 給出鍵盤的 frame，frame 上緣以上才是看得到的內容。判「鍵盤彈出時看得到幾列」時，拿每列 bounds 的下緣與這個上緣比。
- **文字選取的浮動工具列（全部選取／複製／貼上）也不在 dump 裡**，只能從截圖量座標。
- 有「抽屜 / 側邊面板」這類覆蓋式版面時，**軟鍵盤會蓋在它上面，但 dump 回報的 bounds 是邏輯位置，不會扣掉鍵盤**。照那個座標點下去會點到鍵盤。點之前先確認鍵盤狀態（第 6 節），必要時先把清單捲到鍵盤上方再點。
  - **這個坑的症狀是「點了沒反應」**（畫面完全不變），很容易被誤讀成受測程式沒有回應。確認方式：`adb shell dumpsys input_method | grep -m1 mInputShown`，是 `true` 就先收鍵盤再點。
  - **收鍵盤在這台裝置上只有 `input keyevent 4`（BACK）有效**；`input keyevent 111`（ESCAPE）不會收。確認方式：送完之後再 grep 一次 `mInputShown`。
- **可捲動清單會保留捲動位置**：面板關掉再打開，第一列不一定是最上面那一列，**要找的列可能根本不在這次 dump 裡**。每次要點之前都重新 dump；找不到就先捲回頂端（連續往回捲到畫面不再變）再找，不要用「第一列就是最新的」這種假設去判讀內容。
- 只想看「哪些節點可以點」時，挑 `clickable="true"` 的節點取 `bounds`，比從 `text` 的 bounds 反推可靠 —— 一列的可點區域通常比它的文字大。
- **dump 只看得到「已經被組合出來」的節點。** 惰性清單（只算可見範圍的那種）在畫面外的項目根本不在 XML 裡，
  所以「清單裡總共有幾項」**沒辦法一次 dump 問出來**，只能一邊捲一邊蒐集再去重。
  - **捲動的步距必須小於一個項目的高度**，否則會整項跳過去而你不會發現（清單看起來只是「比較短」）。
  - **每一輪都要先確定自己在最頂端**（連續往回捲到畫面不再變），不然起點是上一次留下的捲動位置。
  - **swipe 的起點與終點都不能落在手勢區**（第 5a 節）。
  - **確認方式**：同一份清單用兩種不同步距各蒐集一次，兩次的項數要一樣；不一樣就代表大的那個步距在漏。
- **覆蓋層開著時，覆蓋層外面的狀態文字可能不在 dump 裡**（被遮住的節點不一定列出）。拿「某段文字存在」判斷狀態時，先確認沒有覆蓋層蓋著它，並且配合 `mCurrentFocus`。

*最後確認：2026-09-14*

---

## 5a. 手勢區：swipe 與 tap 絕對不能落進去

這台裝置用手勢導覽。**從螢幕底緣往上滑 = 回桌面；從左右邊緣往內滑 = 返回。** 任何 `input swipe` 的起點落在這些區域，都會離開受測 app，而且之後的點擊會落在桌面或別的 app 上。**本輪踩到一次**：一支捲清單的腳本把起點設在清單底部、剛好落在底部手勢區內，第一下就回到桌面。

**區域當場問出來，不要寫死**：

```sh
adb shell dumpsys window | grep -E "type=(mandatorySystemGestures|systemGestures|navigationBars) frame"
```

`sideHint=BOTTOM` 的那一條給出底部手勢區上緣、`sideHint=LEFT/RIGHT` 給出左右邊緣的寬度、`sideHint=TOP` 給出頂部。swipe 的**起點與終點**都要落在這些 frame 之外，而且再留一段餘裕。

**確認方式**：跑一次上面的指令看得到四個方向的 frame；每次 swipe 之後立刻 `adb shell dumpsys window | grep -m1 mCurrentFocus`，焦點仍是受測 app 才繼續。

*最後確認：2026-09-14*

---

## 6. 輸入法（這台裝置最花時間的一塊）

裝置上唯一的文字輸入法是 Gboard，且**同時掛著注音與英文兩種版面**。

```sh
adb shell ime list -s                                   # 目前可用的輸入法
adb shell settings get secure default_input_method      # 目前使用的那一個
adb shell dumpsys input_method | grep -m1 mInputShown   # 鍵盤是否正在顯示
```

### 6.1 目前是哪一個版面

看**空白鍵上的字**（截圖判讀）：注音版面寫「注音」，英文版面寫「English」。組字中空白鍵上的字可能暫時消失，要在沒有組字時判讀。
切換版面：點鍵盤上的**地球鍵**（在最下排、`?123` 右邊；位置用截圖確認，不要寫死）。

**版面是跨畫面共用的狀態，而且會留在你上次切的那一個。** 收尾時記得切回你進來時的那一個（進來時先截一張圖存證）。

*最後確認：2026-09-14*

### 6.2 打英文 / ASCII

英文版面下 `adb shell input text "..."` 直接可用（空白用 `%s`）。

```sh
adb shell input text "hello%sworld"
```

**字串裡有裝置端 shell 的特殊字元（`;` `&` `|` `*` `(` `)` 引號等）時，外面要再包一層單引號**，否則裝置端 shell 會把它切成兩個指令：前半段照送，後半段被當成指令執行（**本輪踩到**：`;` 之後的鍵沒有送出，另外噴出 `inaccessible or not found`）。

```sh
adb shell "input text 'abc;4'"
```

**確認方式**（不經過輸入框、不會把字打到任何畫面上）：`adb shell "echo a;b"` 印出 `a` 加上 `b: inaccessible or not found`；`adb shell "echo 'a;b'"` 印出 `a;b`。

`input text` **只送得出 ASCII**。送任何非 ASCII 字元（全形空白、CJK 皆然）會直接在 shell 端拋例外：
`java.lang.NullPointerException: Attempt to get length of null array`（`InputShellCommand.sendText`），**欄位完全不會變**。
確認方式：焦點在某個輸入框時 `adb shell input text "<一個中文字>"`，預期看到上面那個例外。

**注音版面下不要用它送 ASCII** —— 那些字母會被當成注音符號進入「組字中」的狀態，**欄位內容完全不會變**。
**這個失敗是無聲的**：`input text` 不會報錯，dump 出來的欄位是空的，看起來像受測程式吃掉了輸入。
確認方式：送完之後 dump 欄位；欄位沒變就截一張圖，看鍵盤上方有沒有組字列／候選列。

**鍵盤沒有真的顯示時，不要照鍵盤座標點下去。** 軟鍵盤佔的是畫面下半部，鍵盤一收起來，
同一個座標就落在系統的導覽手勢區或別的元件上。
規矩是：**每次要點鍵盤上的東西之前，先問一次 `mInputShown`，再重新截一張圖取座標**（第 5 節的理由同樣適用：座標會過期）。

```sh
adb shell dumpsys input_method | grep -m1 mInputShown   # 要是 true 才動手
adb shell dumpsys window | grep -m1 mCurrentFocus       # 點完再確認自己還在受測 app 裡
```

**焦點不在輸入欄位時，`input text` 會退化成一連串按鍵事件**，落在當下持有焦點的元件上。
其中 `%s`（空白）在**焦點是按鈕**時等於「按下那顆按鈕」—— 也就是說一句看起來只是打字的指令，
可能觸發一次導覽。送字串之前先確認焦點真的在欄位裡（dump 裡該欄位的 `focused="true"`）。

*最後確認：2026-09-14*

### 6.3 打中文（注音版面）

Gboard 的注音版面用的是**標準注音鍵盤配置**：每個鍵右上角印著它對應的英數鍵。因此可以用
`input text` 送出一個音節的那幾個英數鍵，再從候選列選字。

```sh
adb exec-out screencap -p > kb.png        # 先看鍵帽右上角的英數標示
adb shell "input text '<那幾個鍵>'"         # 含 ; , . / 時一定要包單引號（6.2）
adb exec-out screencap -p > cand.png      # 看候選列有哪些字、在第幾格
adb shell input tap <該候選格中心>         # 從截圖量出來
```

要點：
- 候選列與組字列**不在 uiautomator dump 裡**，只能截圖判讀。
- 截圖給你看的尺寸可能被縮小過；**從截圖量座標時要換算回裝置實際像素**（例如縮圖寬 891、實際寬 1080，就乘 1.21）。
- 候選列是等寬的若干格，**每次都從截圖重新量**，不要沿用上一次的座標 —— 選過之後候選的順序可能改變。
- 送完按鍵序列後字還在「組字中」，**尚未進入欄位**；一定要選完字才算輸入。
- **聲調鍵與韻母鍵也都在鍵帽的英數標示上**（含 `,` `.` `/` `;` 這幾個非字母鍵）。需要它們時就直接送那個 ASCII 字元。
- **不要用 `%s`（空白）當「第一聲」送**：組字中的空白鍵是「選字」，不是聲調也不是空白。第一聲的音節送完聲母韻母就會出候選。
- 需要在中文詞之間打**半形空白**時：組字狀態下按空白鍵是選字，沒有組字時按空白鍵才是插入空白。所以順序是「先把字選完，再送空白」（`input keyevent 62` 可用）。

*最後確認：2026-09-14*

### 6.4 幾個會讓你以為程式壞掉的鍵盤行為

- **長按空白鍵會叫出「切換鍵盤」選單**，不是連續輸入空白。所以「長按空白」這個操作在這台裝置上產不出連續空白。用 `input keyevent 4` 關掉那個選單。需要「連續收到多個空白」時，用重複的 `input keyevent 62`。
- **英文版面按空白鍵會提交自動更正的建議字**（打 `ab` 再按空白可能變成別的字）。要把**一個精確的字串**（尤其含尾端空白）放進欄位，用單一次 `adb shell input text "...%s%s"` 一起送，不要用點空白鍵的方式補。
- **連按兩次空白鍵會被輸入法換成「句號＋空白」**，不是兩個空白。所以「連續兩個空白」用點鍵的方式做不出來，一樣要用單一次 `input text "...%s%s"`。確認方式：逐碼位讀欄位，看到 `0x2e` 就是踩到了。
- 確認方式：送完之後逐碼位讀欄位（第 5 節），不要看截圖。

*最後確認：2026-09-14（`input text` 送前後空白、`keyevent 62` 送單一空白，逐碼位讀回皆符合送出的內容）*

### 6.5 剪貼簿

`adb shell cmd clipboard ...` 在這台裝置上**不存在**（回 `No shell command implementation.`）。

```sh
adb shell cmd clipboard        # 確認方式：預期看到 No shell command implementation.
```

要把任意字串放進剪貼簿，只能走 UI：找一個**願意原樣保留該字串的輸入欄位**，在那裡打好，長按 → 全部選取 → 複製，再到目標欄位長按 → 貼上。

```sh
adb shell input swipe <x> <y> <x> <y> 1000   # 同一點的長按
adb exec-out screencap -p > menu.png          # 選取工具列只在截圖裡看得到（見第 5 節）
```

限制：**打不出來的字元就進不了剪貼簿**，因為第一步仍然要靠輸入法。這種情況照 spec 的規定記「本環境無法執行」。
（先前確認過：這台裝置的輸入法在注音版面、英文版面與 `?123` 符號頁上都找不到全形空白 U+3000 的鍵。）

*最後確認：2026-09-14（`adb shell cmd clipboard` 仍回 `No shell command implementation.`）*

---

## 7. Compose 畫面的焦點

用 Jetpack Compose 寫的畫面，判讀「輸入框還有沒有焦點」時要知道：

- 按鍵盤上的 **IME 動作鍵（打勾／Enter）只會收鍵盤，不會清掉 `TextField` 的焦點**。
- **點畫面上非互動的空白區域也不會清焦點**。
- **`adb shell input keyevent 61`（TAB）會把焦點移到下一個可聚焦元件**，`TextField` 因此失去焦點。

**「送一個字元看欄位變不變」不是可靠的焦點判準。** TAB 之後焦點確實移走了，
但 IME 的輸入連線仍可能把後續字元送回原來那個欄位。
**改用這個判準**：TAB 之後 dump 一次（第 5 節），看 `focused="true"` 落在哪個節點。
**而且 TAB 之後焦點通常落在某顆按鈕上**，此時再送含空白的字串會按下它（見第 6.2 節末段）。要重新聚焦欄位，點欄位本身 bounds 的中心。

```sh
adb shell dumpsys input_method | grep -m1 mInputShown   # 只說明鍵盤有沒有開，不等於焦點
```

*最後確認：2026-09-14（TAB 後 dump 看到 focused 移到另一個節點；點欄位後 focused 回到欄位）*

---

## 8. 顯示大小與螢幕方向（改之前先記下原值）

```sh
# 顯示大小（density override）
adb shell wm density              # 先看原值與有沒有 override
adb shell wm density 606          # 套用
adb shell wm density reset        # 還原
```

換算：`dp 寬 = 實際像素寬 / density * 160`。想要「等效 WxH dp」就反算：`density = 實際像素 / dp * 160`，寬高各算一次取較大的那個。
**照這個算法在這台裝置上要拿到 640dp 高會得到 density 606**（`2424/640*160`），而同一個值換算出來的寬只有 285dp —— 也就是比「360x640dp」更小、更嚴格。
需要「至少某個尺寸還能看到什麼」這類判定時，這個方向是安全的；若條文要的是**剛好**某個尺寸，就得寬高分開套，這台裝置做不到（只有一個 density 可調）。

```sh
# 螢幕方向：要先關掉自動旋轉才能強制
adb shell settings get system accelerometer_rotation   # ← 先記下原值再改
adb shell settings get system user_rotation            # ← 這個也先記下來
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1          # 1 = 橫向, 0 = 直向
# 收尾：兩個都改回你記下的原值（先還 user_rotation，再還 accelerometer_rotation）
```

**這幾項都會改到使用者的系統設定，收尾一定要還原**，並把「原值是什麼」寫進當輪報告。
**確認方式**：還原後再各 `get` 一次，與記下的原值相同。

*最後確認：2026-09-14（density 420 無 override；accelerometer_rotation=1、user_rotation=0；套用 606 與橫向後都還原成功）*

---

## 9. 啟動、停止、行程被回收、返回鍵

```sh
PKG=<套件名>
adb shell dumpsys package $PKG | grep -A1 "android.intent.action.MAIN"   # 問出啟動 Activity
adb shell am start -n $PKG/<Activity 名>     # 啟動
adb shell am force-stop $PKG                 # 完全終止（不保留 saved instance state）
adb shell am kill $PKG                       # 模擬「被系統回收」（保留 task 與 saved state）
adb shell pidof $PKG                         # 確認行程在不在
```

要驗「行程死亡後還原」用 `am kill`（**先按 Home 讓它進背景**，前景行程不會被殺），**不是** `force-stop`。
**確認方式**：`am kill` 後 `pidof` 無輸出；再 `am start` 會看到 `Activity not started, its current task has been brought to the front`，代表是從保存的 task 還原。

**系統返回鍵的注意事項**：**軟鍵盤開著時，第一次 BACK 只收鍵盤，不會退一層畫面**；鍵盤沒開時 BACK 才是退一層。所以「按兩次返回」在這兩種狀態下的結果完全不同 —— **每次按之前先問一次鍵盤狀態**，不要用固定次數。

**覆蓋層（抽屜、面板、底部選單）不保證會攔下 BACK。** 有些 app 的覆蓋層不處理 BACK，按下去是整個 Activity 結束、回到桌面（**本輪踩到一次**）。要關覆蓋層時，**優先點它的遮罩或關閉節點**（在 dump 裡找覆蓋層以外那塊可點區域的 `content-desc`），確定沒有才用 BACK；按錯了用 `am start` 回到該 task。
**確認方式**：dump 裡找得到覆蓋層範圍以外、`clickable` 的遮罩節點；點它之後覆蓋層的節點消失、`mCurrentFocus` 不變。

**不要用「我記得剛才開了抽屜/子畫面」去推現在按返回會退到哪裡。** 那個記憶會過期（有東西自己關掉了、或上一個點擊其實沒生效），而一旦推錯，那一次返回就把你送出 app 了。**每次按返回之前先問一次 `mCurrentFocus` 與 `mInputShown`，按完再問一次。**

在 app 的最上層畫面按返回會離開 app，接著顯示的是**背景堆疊裡的另一個 app 或桌面**。之後的點擊會落在那個 app 上 —— 曾經因此在別的 app 裡誤觸出一個編輯草稿。每次按返回之後，先 dump 或截圖確認自己還在受測 app 裡再繼續點。

```sh
adb shell dumpsys window | grep -m1 mCurrentFocus   # 現在焦點在哪個 app 的哪個視窗
```

*最後確認：2026-09-14（am kill + am start 從 task 還原；force-stop + am start 冷啟動；覆蓋層上按 BACK 直接回桌面踩到一次）*

---

## 10. 判斷「有沒有跑推論 / 有沒有讀寫檔案」

**用 pid 過濾，不要用關鍵字過濾。** 系統本身就有一堆服務在持續輸出機器學習相關字樣（例如 thermal service），照關鍵字撈一定會撈到不是受測 app 的行。

```sh
adb logcat -c                       # 開始前清空
# ...操作...
PID=$(adb shell pidof <套件名> | tr -d '\r')
adb logcat -d | awk -v p="$PID" '$3==p'      # 只留這個 pid 的行
```

檔案有沒有被動到，用第 4 節的雜湊比對（操作前後各 `sha256sum` 一次再 diff），比 logcat 可靠。

*最後確認：2026-09-14（用 pidof 過濾後讀過；操作前後 sha256sum diff）*

---

## 11. 比較時間戳之前：先量裝置與主機的時差

裝置的牆鐘與這台 Mac **不保證同步**，實測可以差到數秒。任何「把 app 寫下的毫秒時間戳拿去和主機上記的操作時刻相減」的判讀，**沒有先扣掉這個差就沒有意義**。

**確認方式**（兩個數字都印出來，相減就是當下的時差；每輪重量一次，不要沿用舊值）：

```sh
echo "device: $(adb shell date +%s.%N)"; echo "host:   $(date +%s.%N)"
```

**更省事的做法**：需要「操作時刻」時直接取裝置的時鐘 `adb shell date +%s%3N`，兩邊都用裝置時間就不必扣時差。
**需要「什麼都不做地等 N 秒」時用 `adb shell sleep N`**，不要在主機端 sleep。

*最後確認：2026-09-14（本輪時差約 5 秒，裝置較快。**這個值每輪都不一樣，不要沿用**；本輪時刻一律取裝置時鐘）*

---

## 12. 不改動專案原始碼，就地跑一份純 Kotlin 邏輯（探針或突變）

需要直接呼叫某段純邏輯（證明某個性質、或證明某支測試真的釘住了某個行為）時，**受測專案的原始碼不該被 QA 改**。

做法：**把要用的檔案複製到 scratchpad，在複本上改或加一支探針，用 Gradle 快取裡的 Kotlin 編譯器單獨編譯與執行。** 專案原始碼全程唯讀。

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
# 這幾個 jar 都在 ~/.gradle/caches 裡；路徑含雜湊，用 find 當場問，不要寫死
KC=$(find ~/.gradle/caches -name 'kotlin-compiler-embeddable-*.jar' | head -1)
STD=$(find ~/.gradle/caches -name 'kotlin-stdlib-2*.jar' | grep -v sources | head -1)
CO=$(find ~/.gradle/caches -name 'kotlinx-coroutines-core-jvm-*.jar' | grep -v sources | head -1)
AN=$(find ~/.gradle/caches -name 'annotations-13*.jar' | grep -v sources | head -1)
JU=$(find ~/.gradle/caches -name 'junit-4*.jar' | grep -vE 'sources|javadoc' | head -1)
HC=$(find ~/.gradle/caches -name 'hamcrest-core-*.jar' | grep -vE 'sources|javadoc' | head -1)

# 編譯（來源目錄是 scratchpad 裡的複本）
"$JAVA_HOME/bin/java" -cp "$KC:$STD:$CO:$AN" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -nowarn -no-stdlib -jvm-target 11 -cp "$STD:$JU:$HC" -d <輸出目錄> <來源目錄>

# 執行單一測試類別 / 探針的 main
"$JAVA_HOME/bin/java" -cp "<輸出目錄>:$STD:$JU:$HC" org.junit.runner.JUnitCore <測試類別全名>
"$JAVA_HOME/bin/java" -cp "<輸出目錄>:$STD" <套件>.<檔名>Kt
```

三個一定會踩到的點（少一個就會失敗，而錯誤訊息都看不出真正原因）：

1. `kotlin-stdlib` 與 `kotlinx-coroutines-core-jvm` 要放在**執行編譯器的那個 `-cp`**（不是只放在給被編譯程式的 `-cp`）。少了會報 `遺漏 JavaFX 執行元件` 或 `NoClassDefFoundError: kotlinx/coroutines/CoroutineScope`，兩個都與真正的原因無關。
2. `annotations-13.0.jar` 也要放在編譯器的 `-cp`。少了會在產碼階段丟 `Exception while generating code for: FUN ...`，看起來像編譯器 bug。
3. 加 `-no-stdlib`，否則它會去找不存在的 kotlin home。

**編譯時要把所有相依的來源檔一起放進來源目錄**（不是只放被測或探針那一個）。少放時錯誤是一整排 `unresolved reference`，**而清單很長，只看最後幾行會以為是型別推論的問題**。看錯誤一律從第一行讀起。

**只搬得動「不依賴 Android 的檔案」**；被複製的檔案若參照到同 package 或其他 package 的檔案，一起複製。先跑一次編譯讓它告訴你缺什麼最快。

**流程建議**：先在未改動的複本上跑一次當基準，每做一個改動就跑一次，跑完把複本還原再做下一個。

**確認方式**（跑得起來就算通過）：

```sh
"$JAVA_HOME/bin/java" -cp "$KC:$STD:$CO" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -version
```

*最後確認：2026-09-14（照這個流程編譯並執行一支探針成功，專案原始碼未被改動）*

---

## 13. 怎麼做得快（同樣的事少花時間做完）

驅動裝置是每一輪最大的一筆固定成本。三個形狀：

1. **結果可預測的連續操作，寫成一條指令一次送出，每一步之後只印出需要判讀的那一行**（欄位碼位、摘要列、`mCurrentFocus`），不要一步一看。
   - **但條文要求觀察中間狀態的地方不能批**；而且批次一定要加**守衛**：每一步之前檢查預期的節點存在，不在就立刻中止整條指令。
   - **沒有守衛的批次會在第一步出錯之後，把後面每一個點擊與輸入送到錯的畫面上**（本輪踩到一次：找不到目標列之後照樣繼續打字、點按鈕，事後花了一整次唯讀稽核才確認沒有弄壞資料）。

   ```sh
   need(){ python3 <ui-helper> has "$1" >/dev/null || { echo "ABORT: missing '$1' at $2"; adb shell dumpsys window | grep -m1 mCurrentFocus; exit 1; }; }
   ```
   **確認方式**：故意 `need "一段不存在的字" test`，應印出 ABORT 並停止，後面的步驟不執行。

2. **能當場問出答案的，不要用眼睛看。** 元件位置、欄位內容、焦點、鍵盤範圍、手勢區 —— 用 uiautomator dump 與 `dumpsys window` / `dumpsys input_method`。
   - **只有眼睛判斷得出來的留給截圖**：輸入法候選列、算繪有沒有破版、標示蓋在哪幾個字上。

3. **每輪一模一樣的程序做成腳本**：快照、推測資、還原、逐位元組驗證（第 4 節的片段）、UI dump helper（找節點、點中心、逐碼位讀欄位）、分步捲動蒐集清單。
   - **不能用「跳過」來省**。腳本改過之後先跑它的確認方式 —— 腳本腐爛時會安靜地做錯事（第 0 節那兩個 shell 陷阱都會讓清理腳本只做一部分卻不報錯）。

*最後確認：2026-09-14*
