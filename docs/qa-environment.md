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
- **`adb exec-out` 不回傳裝置端指令的結束碼（永遠是 0）；`adb shell` 會。** 所以 `adb exec-out run-as $PKG sh -c 'ls 某檔' && echo 在` 不管檔案在不在都印「在」—— 這是無聲失敗，會讓「檔案被刪掉了嗎」的檢查永遠答錯。
  判斷存在與否一律**看輸出內容**（例如 `ls 目錄 | grep -x 檔名`，輸出非空才算在），或改用 `adb shell` 並確認結束碼有傳回來。
  **確認方式**：`adb exec-out sh -c 'exit 3' < /dev/null; echo $?` 印 `0`；`adb shell 'exit 3'; echo $?` 印 `3`。*最後確認：2026-09-16*
- **zsh 的 glob 沒有匹配時會直接報錯並中止那一行**（`no matches found`），例如 `rm -rf dir/*` 在空目錄上。改用 `rm -rf dir; mkdir -p dir`。
  **同一個坑也會咬到沒加引號的參數**：`grep -rn ... --include=*.kt` 會被 zsh 當成 glob 展開而整行中止。參數裡有 `*` 就加引號。
  **確認方式**：`mkdir -p <scratchpad>/empty_x && ls <scratchpad>/empty_x/*` 回 `no matches found`。
- **zsh 會把以 `=` 開頭的字當成指令路徑展開**：`echo ===` 會報 `== not found` 並中止那一行（後面的指令不跑）。分隔線用單引號包起來：`echo '---'`。
  **確認方式**：`echo ===` 回 `(eval):1: == not found`；`echo '==='` 正常印出。
- **macOS 沒有 `timeout` 指令。** 需要限時就靠工具的逾時參數，或把腳本寫成自己會停。
  **確認方式**：`command -v timeout` 無輸出。
- **`set -- $VAR` 在 zsh 裡同樣不切字**：`R="1 2 3"; set -- $R; echo $1` 印出整串 `1 2 3`，接著拿 `$1` 做數字比較會報 `integer expression expected`。要解析多欄輸出，把判斷整段寫進 python，不要在 shell 裡拆。
  **確認方式**：上面那行在 zsh 印出 `1 2 3`。*最後確認：2026-09-16*
- **把「指令加參數」存進變數再呼叫，zsh 不會切字**：`UI="python3 helper.py"; $UI texts` 會回 `no such file or directory: python3 helper.py`。**放在守衛裡時特別危險**：`$UI has X || echo ABORT` 會因為「指令不存在」而印出 ABORT，看起來像守衛正常運作。一律改用 shell 函式：`ui(){ python3 helper.py "$@"; }`。
  **確認方式**：`C="echo hi"; $C` 回 `command not found: echo hi`；`c(){ echo hi; }; c` 印出 `hi`。
- **Bash 工具每一次呼叫都是新的 shell，`export PATH` 不會留到下一次。** 忘了就是一整排 `command not found: adb`，而後面的指令照樣跑完。做法：把 PATH、套件名、helper 函式寫進 scratchpad 的一個 env 檔，每次呼叫開頭 `source` 它。
  **確認方式**：一次呼叫裡 `export FOO=1`，下一次呼叫 `echo "[$FOO]"` 印 `[]`。*最後確認：2026-09-15*
- **Bash 工具會拒絕含控制字元的指令**（例如 heredoc 裡夾著 U+001C 之類的字元）。需要這種測資時，用檔案寫入工具把原始碼寫成檔案（字元以跳脫或數值表示，例如 `0x1C.toChar()`），不要貼進指令。
  **確認方式**：送出一個含字面控制字元的指令，工具在執行前就回 `command contains control characters`。
  （2026-09-15 補記：heredoc 裡寫 Kotlin 的 `''` 跳脫序列也曾被同一條規則擋下；探針原始碼一律先用檔案寫入工具存成檔。）

*最後確認：2026-09-14（前四條的確認方式逐一跑過；`=` 展開與 `--include=*.kt` 兩個本輪各實際踩到一次）*

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

**跑不經過裝置的測試時，是這個 JDK 在執行**，所以「這個環境的 Unicode／斷字行為是哪一版」由它決定（例如 `java.text.BreakIterator` 從 JDK 20 起才依 UAX #29 斷延伸字位叢集）。
**當場問出版本，不要寫死**：`"$JAVA_HOME/bin/java" -version`。*最後確認：2026-09-16（回報 openjdk 25.0.2）*

常用指令：

| 目的 | 指令 |
|---|---|
| 跑 JVM 單元測試 | `./gradlew testDebugUnitTest` |
| 從零重跑（避免 UP-TO-DATE 讓你以為跑過了） | `./gradlew clean testDebugUnitTest` |
| 建置並安裝到實機 | `./gradlew installDebug` |

**注意**：`testDebugUnitTest` 若顯示 `UP-TO-DATE`，代表**這一輪沒有真的執行測試**。要確定測試真的跑過，加 `clean`，或直接讀結果檔。
**確認方式**：log 裡同時出現 `> Task :app:clean` 與 `> Task :app:testDebugUnitTest`（沒有 `UP-TO-DATE` 或 `FROM-CACHE` 字樣），而且結果 XML 的修改時間是剛才。
**`clean` 之後整輪只花幾秒是可能的**（編譯產物由建置快取提供），不代表沒跑；用上面兩個判準判斷，不要用耗時判斷。

**跑很久的腳本會撞到指令逾時。** Bash 工具預設 120 秒、可用參數拉到 600 秒。一邊捲畫面一邊 dump 的腳本很容易超過。
做法：跑測試套件這種不需要盯的丟到背景（`run_in_background`）；裝置操作腳本明確給逾時參數。
**確認方式**：`time <你的腳本>`。

測試結果的機器可讀位置（不依賴 console 輸出）：

```sh
ls -l app/build/test-results/testDebugUnitTest/*.xml
# 每個 XML 的 root 屬性有 tests / failures / errors / skipped
```

*最後確認：2026-09-15（`clean testDebugUnitTest` 跑完，log 只有 `:app:clean` 與 `:app:testDebugUnitTest` 兩行 Task、無 UP-TO-DATE/FROM-CACHE，逐檔讀過 XML 的屬性與 timestamp）*

---

## 2. 驗收裝置（Android 模擬器）

2026-09-16 起驗收裝置是 Android 模擬器（AVD `Pixel_9`），不再用實機。主機上可能同時接著別的裝置，**每條 adb 指令都指定序號**：在 env 檔裡 `export ANDROID_SERIAL=<序號>`（Gradle 的 `installDebug` 也吃這個變數）。

**確認方式**：

```sh
adb devices -l          # 要看到 emulator-XXXX device（不是 unauthorized / offline），序號當場抄
adb shell getprop ro.build.version.release   # Android 版本，抄進當輪報告
adb shell wm size       # 實際解析度
adb shell wm density    # 實際 density；若有 Override density 會一起列出
```

**裝置的資料分割區剩多少，動手前先問**（推大測資、重新解壓 GB 級資產檔之前都要）：

```sh
adb shell df -h /data
```

*最後確認：2026-09-16（`adb devices -l` 回報 emulator-5554、model:sdk_gphone16k_arm64；`wm size` 1080x2424、`wm density` 420 無 override）*。*2026-09-16 補：`df -h /data` 回報 20G 容量、13G 可用 —— 這個數字每台 AVD 不同，每輪自己問。*

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

**覆蓋安裝失敗 `INSTALL_FAILED_INSUFFICIENT_STORAGE`（APK 很大時）**：`installDebug` 會先把 APK 整份複製到 `/data/local/tmp` 再安裝，失敗時那份複本會留在裝置上佔空間。做法：
1. `adb shell df -h /data` 看剩餘空間；`adb shell ls -la /data/local/tmp` 找出**這一輪失敗那次**留下的 APK（時間戳對得上才刪）。
2. 刪掉那份複本後改用 `adb install -r -t <apk>`（串流安裝，不經過 `/data/local/tmp`）。
3. **絕對不要解除安裝來騰空間。**
**確認方式**：輸出含 `Performing Streamed Install` 與 `Success`，且 `dumpsys package <套件名> | grep lastUpdateTime` 變成剛才；另可比對 `adb shell stat -c %s $(adb shell pm path <套件名> | sed 's/package://')` 與本機 APK 大小相同。*最後確認：2026-09-16*

**重新安裝不會清掉 app 的私有資料**（確認方式見第 4 節：安裝前後比對雜湊）。

*最後確認：2026-09-15（(c)：`./gradlew installDebug` 覆蓋安裝後記下 `lastUpdateTime`，晚於 HEAD 的提交時間）*

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

**裝置端的 `ls` 是 toybox 版本**，不支援 GNU 的 `--time-style` 之類參數（會回 `Unknown option`）。要時間就用 `ls -l` 的預設輸出或 `stat`。
**確認方式**：`adb exec-out run-as $PKG sh -c 'ls -l --time-style=+%H files'` 回 `Unknown option`。

**`adb exec-out run-as $PKG ls <目錄>` 的輸出是多欄排版（一行好幾個檔名），不是一行一個。** 拿去 `sort`／`comm` 比清單會全部對不上、看起來像檔案不見。要清單一律用 `ls -1` 或 `find`。
**`ls -t`（依時間排序）同樣是多欄排版**，所以 `ls -t ... | head -1` 拿到的常常不是最新的那個檔，而是同一行的第一個檔名 —— 這個錯會讓你讀到**別的檔**還以為讀對了。要「最新的那一個」一律 `ls -1t`。
**確認方式**：對一個有多個檔的目錄各跑 `ls -t | head -1` 與 `ls -1t | head -1`，兩者不同就是踩到了。*最後確認：2026-09-16*
**確認方式**：對一個有多個檔的目錄各跑一次 `ls` 與 `ls -1`，數輸出行數（`wc -l`），前者遠少於檔案數。*最後確認：2026-09-15*

**使用者已同意清空 App 資料的輪次**：不要用 `pm clear`（會連同幾百 MB 的資產檔一起刪）。先 `ls -la files shared_prefs` 看清楚，`force-stop` 之後只刪使用者資料所在的子目錄與偏好檔，再 `ls` 一次確認資產檔還在。*最後確認：2026-09-15*

**目錄內每個檔案的雜湊清單：`docs/qa-tools/hashdir.sh PKG DIR`**（見第 13 節的工具清單）。操作前後各存一份再 `diff`，就是「檔案數量與內容有沒有變」的判定。

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
ls -a <本機測資目錄>                               # 本機來源目錄也要先確認只有本輪的檔案
adb push <本機檔> <本機檔> ... /data/local/tmp/qa/     # 逐檔列出，不要 push 整個目錄
adb shell "run-as $PKG sh -c 'cp /data/local/tmp/qa/<前綴>*.json <資料目錄>/'"
```

**這兩件事都是踩過的**：(a) `/data/local/tmp/<你的目錄>` 可能還留著**前一輪**的東西，接著那句 `cp *.json` 會把它們一起灌進 app 的資料目錄；(b) scratchpad 目錄同樣可能有前一輪的殘留，`adb push <目錄>/.` 會把整包送上去。
**確認方式**：`cp` 之後立刻把資料目錄的檔案清單與快照的清單 `comm -13` 比一次，多出來的只能是你這一輪自己建的那幾個。

**偏好檔（`shared_prefs/*.xml`）也可以用同一條路推進去**：app 停止時 push 到 `/data/local/tmp`，再 `run-as $PKG sh -c 'cp ... shared_prefs/<檔名>.xml'`。用來造「上次停在某處」之類的冷啟動前提。
**確認方式**：推完之後 `adb exec-out run-as $PKG cat shared_prefs/<檔名>.xml` 讀回的內容與推進去的相同。*最後確認：2026-09-15*

**還原並驗證**：

```sh
adb shell am force-stop $PKG                   # 先停掉，否則它可能會再寫回去
# 1. 挑出「現在有、快照沒有」的檔案（含你推的測資與 app 自己在測試中建的檔），寫進清單檔再逐行刪
adb exec-out run-as $PKG sh -c 'find files shared_prefs -type f ! -name "*.<大檔副檔名>"' | tr -d '\r' | sort > now.txt
comm -13 <(sort "$SNAP/filelist.txt") now.txt > extras.txt; cat extras.txt     # 先用眼睛看一次
while IFS= read -r f; do adb shell -n "run-as $PKG sh -c 'rm -f \"$f\"'"; done < extras.txt   # -n 不可省略（第 0 節）
comm -23 <(sort "$SNAP/filelist.txt") now.txt  # 快照有、現在沒有的：下一步會從快照推回
# 2. 快照裡有、但位元組已不同（或已不存在）的檔案，從快照推回去
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
**還原之後只要再啟動一次 app（哪怕只是補一個確認），就要把還原與步驟 3 整個再跑一次**——啟動本身會改寫下面那一類標記檔。

**有些檔案會因為「app 被啟動過」而改變，不是你動到的。** Android 執行期自己會寫的標記檔（例如 `files/profileInstalled`）
在每次冷啟動後內容可能不同。**比對雜湊時把這一類檔案分開看**，否則會誤判成還原失敗；它也可以照步驟 2 從快照推回去，推回後整份 diff 應為空。
**確認方式**：什麼都不做，只 `am force-stop` 再 `am start` 一次，前後比對該檔的雜湊。

**`adb shell "run-as $PKG rm -f files/xxx-*.json"` 不會展開萬用字元**（run-as 直接把參數交給 rm，沒有 shell 去展開），
指令會「成功」但一個檔案都沒刪掉 —— **這是無聲失敗**。要展開就得自己開一層 shell：`run-as $PKG sh -c 'rm -f ...'`。
**確認方式**：`adb shell "run-as $PKG rm -f files/nonexistent-*.json"; echo $?` 回 0，而同一個 glob 用 `sh -c 'ls ...'` 回 No such file。

**已知的位元組層面陷阱**：手寫的 JSON 與 app 自己序列化出來的 JSON，**內容相同但位元組可能不同**（縮排、鍵之間的空白、非 ASCII 是否跳脫）。比對雜湊發現差異時，先 parse 成物件再比，才分得出「內容真的被改了」與「只是重新序列化」。
→ 推論：**你自己推進去的測試檔，只要被 app 讀寫過一次就不能再拿位元組雜湊當基準**；使用者原本就有的檔案才可以。要做「前後位元組相同」的比對，先讓 app 自己寫過一次，拿那一份當基準。
**確認方式**：推一個手寫 JSON，讓 app 讀寫它一次，再拉回來 `cmp`（不同）與 parse 後比較（相同）。

*最後確認：2026-09-14（快照 → 推測試檔 → 大量操作 → 刪檔 → 還原 → 雜湊與 baseline 完全相同；手寫 JSON 被重新序列化後 `cmp` 不同、parse 後相同）。2026-09-15：推測試檔、`comm` 比對（改用 `ls -1`）、手寫 JSON 重新序列化後 parse 相同，再次確認；「先讓 app 寫一次再取位元組基準」再次照做成立。*

---

## 5. 讀畫面：用 UI tree，不要用肉眼猜座標

**現成工具：`docs/qa-tools/ui.py`**（dump／texts／has／tap／tapclass／field 逐碼位／focus）。**用之前先跑 `python3 docs/qa-tools/ui.py selftest`**，印出 `selftest OK` 才用。它不對 text 做 strip，`tap` 只接受完全相等且唯一的節點。*最後確認：2026-09-15*

```sh
adb exec-out uiautomator dump /dev/tty
```

會吐出一段 XML（前後夾雜一行提示字，parse 前先切掉 `<?xml` 之前與最後一個 `>` 之後）。每個節點有 `text` / `content-desc` / `bounds` / `focused` / `clickable`（raw XML 裡還有 `enabled`，判斷按鈕能不能按要讀它；`ui.py` 的輸出沒有這一欄，直接 `grep -oE '<node[^>]*content-desc="…"[^>]*>'` 取屬性）。

**畫面上任何一個文字節點含有「落單的 UTF-16 代理字元」（例如被截斷切開的 emoji）時，uiautomator dump 會整個崩潰**：終端只印 `Killed`，`ui.py` 報 `ABORT: dump failed: Killed`，存到 `/sdcard` 的檔是 0 bytes。**這不是記憶體不足**，重試也一樣。
- 做法：讓那個節點離開畫面（捲走、關掉面板、換畫面）再 dump；那個畫面上的判讀只能改用截圖。
- **確認方式**：dump 失敗時跑 `adb logcat -d | grep -A2 'FATAL EXCEPTION' | grep 'Bad surrogate pair'`，有輸出就是這個原因；換到別的畫面後 `ui.py selftest` 恢復正常。*最後確認：2026-09-15*

建議做法：**先 dump、從 text 找到目標節點的 bounds、再算中心點去點**，不要把座標寫死在腳本或文件裡 —— 座標會過期，而**錯的座標比沒有座標更糟：它會讓人點到別的東西，然後以為自己驗過了**。

**同一段字同時出現在畫面固定區與清單列裡**（例如側邊面板上方的選項名稱也出現在清單列裡）時，`ui.py tap` 會因為不唯一而中止；改用「文字相等 + y 範圍」挑節點再點。
**確認方式**：在那種畫面上 `ui.py tap <那段字>` 回報 `ABORT: N nodes match`（N>1）。*最後確認：2026-09-16*

**「用文字找節點」會找到別的節點。** 同一段字常常同時出現在輸入框與清單列裡；取「第一個含這段字的節點」時，拿到的可能是清單列，點下去就觸發了那一列的動作。
- 點輸入框一律用 `class="android.widget.EditText"` 找（並確認恰好一個），不要用它裡面的文字找。
- 其他目標用**完全相等**比對，並加上位置條件（例如「在某個區塊的 y 範圍內」）；找到不是恰好一個就中止。
**確認方式**：在一個「輸入框內容也出現在下方清單」的畫面上 dump，數含那段字的節點數，會大於 1。

想逐碼位確認某個欄位的內容（截圖看不出空白與不可見字元）：`python3 docs/qa-tools/ui.py field`。

**寫 helper 時不要對 text 做 `strip()`** —— 尾端空白正是最需要看清楚的東西，一個順手的 strip 會讓你以為欄位是乾淨的。
**把 dump 輸出丟給 `awk '{$1=$1};1'` 整理欄位時，text 裡的連續空白也會被收成一個**——那一份只能拿來找節點，不能拿來判讀欄位內容；欄位內容一律用 `field`。
**確認方式**：欄位內容含兩個連續空白時，`field` 印兩個 `0x20`，而經過那個 awk 的 `texts` 輸出只剩一個。*最後確認：2026-09-15*

**已知限制**：
- dump 只涵蓋前景 app 的視窗，**不包含輸入法的視窗**（候選字列、鍵盤按鍵都不在裡面）。要判讀鍵盤只能用截圖 `adb exec-out screencap -p > x.png`。
- **軟鍵盤佔掉的範圍可以當場問出來**，不必從截圖量：`adb shell dumpsys window | grep "type=ime frame"` 給出鍵盤的 frame，frame 上緣以上才是看得到的內容。判「鍵盤彈出時看得到幾列」時，拿每列 bounds 的下緣與這個上緣比。
  - **同一個輸入法換版面時，鍵盤高度會變**（上緣會移動）。每次判讀前重問一次，不要沿用上一次的數字。
- **文字選取的浮動工具列（全部選取／複製／貼上）也不在 dump 裡**，只能從截圖量座標。
- 有「抽屜 / 側邊面板」這類覆蓋式版面時，**軟鍵盤會蓋在它上面，但 dump 回報的 bounds 是邏輯位置，不會扣掉鍵盤**。照那個座標點下去會點到鍵盤；**在面板上捲動的 swipe 也會落在鍵盤上**。點或捲之前先確認鍵盤狀態（第 6 節），必要時先把清單捲到鍵盤上方再點。
  - **這個坑的症狀是「點了沒反應」**（畫面完全不變），很容易被誤讀成受測程式沒有回應。確認方式：`adb shell dumpsys input_method | grep -m1 mInputShown`，是 `true` 就先收鍵盤再點。
  - **收鍵盤在這台裝置上只有 `input keyevent 4`（BACK）有效**；`input keyevent 111`（ESCAPE）不會收。確認方式：送完之後再 grep 一次 `mInputShown`。
  - **從一個有輸入框的畫面回到另一個畫面後，鍵盤可能仍然開著**。每次打開面板之前都要問，不要假設它已經收了。
- **可捲動清單會保留捲動位置**：面板關掉再打開，第一列不一定是最上面那一列，**要找的列可能根本不在這次 dump 裡**。每次要點之前都重新 dump；找不到就先捲回頂端（連續往回捲到畫面不再變）再找，不要用「第一列就是最新的」這種假設去判讀內容。
  - **「捲回頂端再往下找」很慢**（十幾次 swipe）。需要在時間敏感的時刻切換畫面時，不要走這條路，改用不需要捲動的入口。
- 只想看「哪些節點可以點」時，挑 `clickable="true"` 的節點取 `bounds`，比從 `text` 的 bounds 反推可靠 —— 一列的可點區域通常比它的文字大。
  - **但 `content-desc` 常常掛在可點節點的子節點上，而不是可點節點本身**。用「可點節點的 content-desc」去分辨按鈕種類會分錯；要嘛找帶 desc 的節點再往上找它的可點祖先，要嘛改用截圖判斷。
- **Markdown／富文字算繪出來的一段內容，在 dump 裡會被拆成好幾個文字節點**（每個段落一個）。拿「最後一個文字節點的長度」判斷一則訊息有沒有變長是不可靠的；要判「逐步出現」這類現象，用連續截圖。
  - **而且那幾個節點有時候整段不出現在 dump 裡**（實測：畫面上看得見一整片文字，dump 卻只有它上面那一行標題）。**「dump 裡沒有」不等於「畫面上沒有」** —— 要斷言「某處是空的」之前，一定要再截一張圖確認。*最後確認：2026-09-16*
- **dump 只看得到「已經被組合出來」的節點。** 惰性清單（只算可見範圍的那種）在畫面外的項目根本不在 XML 裡，
  所以「清單裡總共有幾項」**沒辦法一次 dump 問出來**，只能一邊捲一邊蒐集再去重。
  - **捲動的步距必須小於一個項目的高度**，否則會整項跳過去而你不會發現（清單看起來只是「比較短」）。若每一項都帶有彼此不同的欄位（可拿來去重），步距只要小於可視高度即可。
  - **每一輪都要先確定自己在最頂端**（連續往回捲到畫面不再變），不然起點是上一次留下的捲動位置。
  - **清單裡有文字完全相同的項目時，不能靠文字去重**；改用「同一個節點在兩次 dump 之間的 y 位移」算出捲了多少，再用絕對位置判斷新項目。
  - **數「某種列」時，判準要能分辨出只在那種列出現的特徵**（例如位置、寬度），不要只用文字內容——同一段字可能同時出現在標頭與內文列。
  - **捲到一半、只露出一部分的列，分組後會變成少了幾個欄位的「殘列」**；去重後的清單要把殘列（欄位數少於完整列）剔掉再數。
  - **swipe 的起點與終點都不能落在手勢區**（第 5a 節）。
  - **確認方式**：同一份清單用兩種不同步距各蒐集一次，兩次的項數要一樣；不一樣就代表大的那個步距在漏。
  - **把同一列的節點歸成一組時，用節點的下緣（bounds 的第四個數）分組，不要用上緣**：同一列裡字級不同的節點（emoji、名稱、小字）上緣會差幾個像素，按上緣分桶會把一列切成兩列。
  - **但下緣也不保證完全相同**：同一列裡不同字級的節點下緣可能差幾個像素（實測差 2 px）。**分組要給容差**（例如 ±8 px），用「下緣完全相等」去比對會找不到任何一列，而症狀是「這一列不存在」——很容易被誤讀成畫面上真的沒有那一列。
  - **確認方式**：dump 一列含 emoji 與小字的列，印出每個節點的上緣與下緣比對；兩個節點的下緣不相等時，就是要給容差的情形。*最後確認：2026-09-16*
- **把清單捲回頂端，swipe 的距離要夠大。** 距離太短時畫面看起來完全沒動，很容易被讀成「已經在頂端了」，於是你判讀的是清單中段。實測同一份清單：800 px 的 swipe 捲得動，600 px 有時不動。
  - **確認方式**：捲之前記下某一列的 y，捲完再 dump 比對；y 沒變就是沒捲到。*最後確認：2026-09-16*
- **打開覆蓋層的那個入口，文字若在覆蓋層內部也出現，覆蓋層開著時再點它就不是「開/關」** —— 點到的是裡面那個同名節點，畫面不會有任何變化，看起來像點擊沒生效。點之前先問一次覆蓋層在不在（dump 裡有沒有它的關閉節點）。*最後確認：2026-09-16*
- **Compose 把一個控制項設成 disabled 時，uiautomator 的 `enabled` 屬性不一定跟著變**（實測看到 `enabled="true"` 而該鈕按下去沒有任何作用）。要判「按不按得下去」就實際按一次再看畫面有沒有變化，不要只讀那個屬性。*最後確認：2026-09-16*
- **覆蓋層開著時，覆蓋層外面的狀態文字可能不在 dump 裡**（被遮住的節點不一定列出）。拿「某段文字存在」判斷狀態時，先確認沒有覆蓋層蓋著它，並且配合 `mCurrentFocus`。

*最後確認：2026-09-15（EditText 以 class 定位；清單分步蒐集去重；dump 崩潰原因以 logcat 確認）*

---

## 5a. 手勢區：swipe 與 tap 絕對不能落進去

這台裝置用手勢導覽。**從螢幕底緣往上滑 = 回桌面；從左右邊緣往內滑 = 返回。** 任何 `input swipe` 的起點落在這些區域，都會離開受測 app，而且之後的點擊會落在桌面或別的 app 上。

**區域當場問出來，不要寫死**：

```sh
adb shell dumpsys window | grep -E "type=(mandatorySystemGestures|systemGestures|navigationBars) frame"
```

`sideHint=BOTTOM` 的那一條給出底部手勢區上緣、`sideHint=LEFT/RIGHT` 給出左右邊緣的寬度、`sideHint=TOP` 給出頂部。swipe 的**起點與終點**都要落在這些 frame 之外，而且再留一段餘裕。

**確認方式**：跑一次上面的指令看得到四個方向的 frame；每次 swipe 之後立刻 `adb shell dumpsys window | grep -m1 mCurrentFocus`，焦點仍是受測 app 才繼續。

*最後確認：2026-09-15*

---

## 6. 輸入法（這台裝置最花時間的一塊）

裝置上唯一的文字輸入法是 Gboard，且**同時掛著注音與英文兩種版面**（模擬器上同樣如此）。
**確認方式**：`adb shell dumpsys input_method | grep -E "mSubtypeName=.*mLayoutName"` 列出啟用中的版面。*最後確認：2026-09-16*
**切換版面的另一條路**：長按空白鍵會跳出「Change keyboard」選單（兩個版面各一列），點要的那一列。位置用截圖量，不要寫死。

```sh
adb shell ime list -s                                   # 目前可用的輸入法
adb shell settings get secure default_input_method      # 目前使用的那一個
adb shell dumpsys input_method | grep -m1 mInputShown   # 鍵盤是否正在顯示
```

### 6.1 目前是哪一個版面

看**空白鍵上的字**（截圖判讀）：注音版面寫「注音」，英文版面寫「English」。組字中空白鍵上的字可能暫時消失，要在沒有組字時判讀。
切換版面：點鍵盤上的**地球鍵**（在最下排、`?123` 右邊一格；位置用截圖確認，不要寫死）。**點之前先問 `mInputShown` 是 `true`**，點完截圖確認空白鍵上的字變了。

**不截圖也能判斷版面**：焦點在一個空的輸入框時送 `input text 'x'`，逐碼位讀欄位——英文版面欄位變成 `x`，注音版面欄位不變（字母進了組字）。判完刪掉那個字。
**確認方式**：兩種版面各做一次，欄位讀回的結果不同。*最後確認：2026-09-15*

**版面是跨畫面共用的狀態，而且會留在你上次切的那一個。** 收尾時記得切回你進來時的那一個（進來時先截一張圖存證）。

*最後確認：2026-09-15（注音 → 英文 → 注音多次切換，每次截圖或欄位讀回確認）*

### 6.2 打英文 / ASCII

英文版面下 `adb shell input text "..."` 直接可用（空白用 `%s`）。

```sh
adb shell input text "hello%sworld"
```

**字串裡有裝置端 shell 的特殊字元（`;` `&` `|` `*` `(` `)` `!` `?` 引號等）時，外面要再包一層單引號**，否則裝置端 shell 會把它切成兩個指令：前半段照送，後半段被當成指令執行（`;` 之後的鍵沒有送出，另外噴出 `inaccessible or not found`）。

```sh
adb shell "input text 'abc;4'"
```

**確認方式**（不經過輸入框、不會把字打到任何畫面上）：`adb shell "echo a;b"` 印出 `a` 加上 `b: inaccessible or not found`；`adb shell "echo 'a;b'"` 印出 `a;b`。

`input text` **只送得出 ASCII**（模擬器上同樣如此，2026-09-16 再確認）。送任何非 ASCII 字元（全形空白、CJK 皆然）會直接在 shell 端拋例外：
`java.lang.NullPointerException: Attempt to get length of null array`（`InputShellCommand.sendText`），**欄位完全不會變**。
確認方式：焦點在受測 app 的某個輸入框時 `adb shell input text "<一個中文字>"`，預期看到上面那個例外，dump 欄位不變。**不要在焦點不在受測 app 時做這個確認**。

**非 ASCII 字元送不進去這件事，每一輪都要當場確認它現在是「拋例外」還是「無聲失敗」** —— 兩種都出現過，而無聲失敗最容易被當成「送進去了」。
確認方式：焦點在受測 app 的輸入框時，把那個字元用兩種寫法各送一次（`adb shell "input text '<字元>'"` 與 `adb shell input text <字元>`），看結束碼與 stderr，再逐碼位讀欄位。
*2026-09-16 當場量到的結果*：U+00A0 與 U+3000 **兩種寫法都回結束碼 255 並拋上面那個 NPE**，欄位完全不變。（這份文件先前記著「U+00A0 完全不報錯」，那條在這一天已不成立 —— 所以這裡只留做法，不留結論。）*最後確認：2026-09-16*

**`input text` 是一連串很快的按鍵事件，受測欄位若會在輸入途中改寫自己的內容，偶爾會掉字**（整串送進去，讀回少一個字元）。同一個輸入重送幾次、每次都逐碼位讀回；只出現一次、重送重現不了的，記成「未能重現的一次觀察」，不要拿它判定。
**確認方式**：同一個字串連送 N 次（每次清空後重送），把讀回結果列出來比對。*最後確認：2026-09-15*

**注音版面下不要用它送 ASCII 字母當成英文** —— 那些字母會被當成注音符號進入「組字中」的狀態，**欄位內容完全不會變**。
**這個失敗是無聲的**：`input text` 不會報錯，dump 出來的欄位是空的，看起來像受測程式吃掉了輸入。
確認方式：送完之後 dump 欄位；欄位沒變就截一張圖，看鍵盤上方有沒有組字列／候選列。

**鍵盤開著與收起來時，畫面上同一顆按鈕的座標不一樣。** 版面被鍵盤往上擠，照「鍵盤收起來時量到的座標」去點，很可能點在鍵盤上 —— 而鍵盤最右下角常常是 Enter／動作鍵，於是那一次點擊變成「往欄位裡插一個換行」，看起來像受測程式自己多打了一個字。
**確認方式**：同一顆按鈕在 `mInputShown` 為 true 與 false 時各 dump 一次，比對 bounds；每次要點之前重新問一次。*最後確認：2026-09-16*

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

*最後確認：2026-09-15（`adb shell cmd clipboard` 仍無實作；英文版面 `input text` 含 `%s` 前後空白逐碼位讀回相符）*

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
- **組字中空白鍵上印的是「ˉ」（一聲）**：組字時按空白鍵是加一聲，會把候選收窄；沒有組字時按空白鍵（或 `input keyevent 62`）才是插入 U+0020。
- 候選列是等寬的若干格，**每次都從截圖重新量**，不要沿用上一次的座標 —— 選過之後候選的順序可能改變。
- **省截圖的做法（有守衛才可以用）**：同一組鍵序重打、而前一次截圖證明詞在第一格時，可以直接點第一格，**點完立刻逐碼位讀欄位**，不是預期的字就中止。沒有這個讀回守衛就不要省。
  **確認方式**：點完第一格後 `ui.py field` 印出的碼位與預期逐一相同。*最後確認：2026-09-15*
- 送完按鍵序列後字還在「組字中」，**尚未進入欄位**；一定要選完字才算輸入。
- **一次送兩個以上音節的鍵序，候選列通常直接出現整個詞**，可以省一次截圖（四個音節的詞也成立）。
- **聲調鍵與韻母鍵也都在鍵帽的英數標示上**（含 `,` `.` `/` `;` 這幾個非字母鍵）。需要它們時就直接送那個 ASCII 字元。
- **不要用 `%s`（空白）當「第一聲」送**：組字中的空白鍵是「選字」，不是聲調也不是空白。第一聲的音節送完聲母韻母就會出候選。
- 需要在中文詞之間打**半形空白**時：組字狀態下按空白鍵是選字，沒有組字時按空白鍵才是插入空白。所以順序是「先把字選完，再送空白」（`input keyevent 62` 可用）。
- **注音版面下快速連送兩次 `keyevent 62` 會被換成全形句號 U+3002（「。」）**，不是兩個空白。要兩個空白就在兩次之間隔約 2 秒（`adb shell sleep 2`），再逐碼位確認。
  **確認方式**：注音版面、焦點在空的輸入框、選完一個字之後連送兩次 `input keyevent 62`，逐碼位讀欄位看到 `0x3002`；隔 2 秒各送一次則看到兩個 `0x20`。
- **注音版面下 `input text` 送 ASCII 標點會變成全形**（例如 `!` 進欄位是 U+FF01）。需要半形標點就先切英文版面。
  確認方式：注音版面、焦點在任一輸入框時 `adb shell "input text '!'"`，逐碼位讀欄位看到 `0xff01`。
- **同一條的副作用：鍵帽英數標示是標點的那幾個注音鍵，送不進去。** 那個字元會被當成全形標點直接提交，組字會當場結束、還可能把已組好的音節提交成別的字。**需要那幾個鍵時，從截圖量出鍵的中心點用 `input tap` 按它**，不要用 `input text`。
  **確認方式**：注音版面下用 `input text` 送一串含標點鍵的鍵序，逐碼位讀欄位會看到全形標點；同一串改成「字母用 `input text`、標點鍵用 `input tap`」則進入組字列。*最後確認：2026-09-16*
- **候選列的位置在同一次操作裡通常不變，但每次仍要截圖確認**；選完字之後的下一次組字，候選順序可能不同。

*最後確認：2026-09-15（單音節與多音節鍵序各做十餘次，選字後逐碼位讀欄位；兩次 62 隔 2 秒得到兩個 0x20）*

### 6.4 幾個會讓你以為程式壞掉的鍵盤行為

- **長按空白鍵會叫出「切換鍵盤」選單**，不是連續輸入空白。所以「長按空白」這個操作在這台裝置上產不出連續空白。用 `input keyevent 4` 關掉那個選單。需要「連續收到多個空白」時，用重複的 `input keyevent 62`。
  - **確認方式**：`adb shell input swipe <空白鍵中心x> <y> <同座標> <同y> 1500` → 截圖看到「Change keyboard」對話框（列出目前啟用的版面），逐碼位讀欄位**一個字元都沒有進去**；`input keyevent 4` 關掉對話框後欄位仍為原樣。*最後確認：2026-09-16（模擬器）*
- **英文版面按空白鍵會提交自動更正的建議字**（打 `ab` 再按空白可能變成別的字）。要把**一個精確的字串**（尤其含尾端空白）放進欄位，用單一次 `adb shell input text "...%s%s"` 一起送，不要用點空白鍵的方式補。
- **英文版面連按兩次空白鍵會被輸入法換成「句號＋空白」**，不是兩個空白。所以「連續兩個空白」用點鍵的方式做不出來，一樣要用單一次 `input text "...%s%s"`。確認方式：逐碼位讀欄位，看到 `0x2e` 就是踩到了。
- **游標移動與刪除可以用按鍵事件做**：`input keyevent 122`（MOVE_HOME）移到最前、`123`（MOVE_END）移到最後、`112`（FORWARD_DEL）刪游標後一個字、`67`（DEL）刪游標前一個字。
- 確認方式：送完之後逐碼位讀欄位（第 5 節），不要看截圖。

*最後確認：2026-09-15（MOVE_HOME/MOVE_END + 空白、MOVE_HOME + FORWARD_DEL 刪字，逐碼位讀回皆符合送出的內容）*

### 6.5 剪貼簿

`adb shell cmd clipboard ...` 在這台裝置上**不存在**（回 `No shell command implementation.`）。

```sh
adb shell cmd clipboard        # 確認方式：預期看到 No shell command implementation.
```

**2026-09-16 在模擬器上確認：這條 UI 路走得通。** 在一個會原樣保留內容的輸入欄位打好字 → 長按文字 → 截圖量「Select all」→ 點 → 截圖量「Copy」→ 點；到目標欄位長按 → 截圖量「Paste」→ 點。剪貼簿裡到底是什麼，用「貼進一個會原樣保留內容的欄位，再逐碼位讀回」確認，不要看 Gboard 剪貼簿提示條上的字（它會修剪空白）。
**確認方式**：照上面做一次，逐碼位讀回的內容與複製來源相同。*最後確認：2026-09-16*

要把任意字串放進剪貼簿，只能走 UI：找一個**願意原樣保留該字串的輸入欄位**，在那裡打好，長按 → 全部選取 → 複製，再到目標欄位長按 → 貼上。

```sh
adb shell input swipe <x> <y> <x> <y> 1000   # 同一點的長按
adb exec-out screencap -p > menu.png          # 選取工具列只在截圖裡看得到（見第 5 節）
```

限制：**打不出來的字元就進不了剪貼簿**，因為第一步仍然要靠輸入法。這種情況照 spec 的規定記「本環境無法執行」。
- **2026-09-16 逐頁查過**：英文版面與注音版面的**主鍵盤、`?123` 第一頁、`=\<` 第二頁**四張鍵盤圖上，都沒有全形空白 U+3000 的鍵，也沒有不斷行空格 U+00A0 的鍵。
  **確認方式**：每個版面各截一張主鍵盤、一張 `?123`、一張 `=\<`，逐鍵看鍵帽；再配合 6.2 的兩個送字確認（`input text` 送這兩個字元都進不了欄位）。**兩條路都不通，就等於這個字元在這個環境產不出來。**

*最後確認：2026-09-16（`adb shell cmd clipboard` 仍回 `No shell command implementation.`；選取工具列 Select all → Copy →（切到另一個畫面的欄位）長按 → Paste 這條路整條走通，貼進去的內容逐碼位與來源相同，含前導空白）*

---

## 7. Compose 畫面的焦點

用 Jetpack Compose 寫的畫面，判讀「輸入框還有沒有焦點」時要知道：

- 按鍵盤上的 **IME 動作鍵（打勾／Enter）只會收鍵盤，不會清掉 `TextField` 的焦點**。
- **點畫面上非互動的空白區域也不會清焦點**。
- **`adb shell input keyevent 61`（TAB）會把焦點移到下一個可聚焦元件**，`TextField` 因此失去焦點。

**「送一個字元看欄位變不變」不是可靠的焦點判準。** TAB 之後焦點確實移走了，
但 IME 的輸入連線仍可能把後續字元送回原來那個欄位。
**改用這個判準**：TAB 之後 dump 一次（第 5 節），看 `focused="true"` 落在哪個節點。
**而且 TAB 之後焦點通常落在某顆按鈕上**，此時再送含空白的字串會按下它（見第 6.2 節末段）。要重新聚焦欄位，點欄位本身 bounds 的中心（以 class 找欄位，見第 5 節）。

```sh
adb shell dumpsys input_method | grep -m1 mInputShown   # 只說明鍵盤有沒有開，不等於焦點
```

*最後確認：2026-09-15（TAB 後 dump 看到 focused 移到另一個節點；以 class 定位點欄位後 focused 回到欄位）*

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

**改 density（套用或 reset）會讓前景 Activity 重建**（設定變更）。重建之後畫面可能停在和你以為的不同地方；改完之後先 dump 確認現在在哪個畫面，再接下一步。
**確認方式**：改 density 前後各問一次 `mCurrentFocus`，視窗編號會變。

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

*最後確認：2026-09-15（density 420 無 override；accelerometer_rotation=1、user_rotation=0；套用 606 與橫向後都還原成功並讀回確認；改 density 後視窗編號改變）*

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

**覆蓋層（抽屜、面板、底部選單）不保證會攔下 BACK。** 有些 app 的覆蓋層不處理 BACK，按下去是整個 Activity 結束、回到桌面。要關覆蓋層時，**優先點它的遮罩或關閉節點**（在 dump 裡找覆蓋層以外那塊可點區域的 `content-desc`），確定沒有才用 BACK；按錯了用 `am start` 回到該 task。
**確認方式**：dump 裡找得到覆蓋層範圍以外、`clickable` 的遮罩節點；點它之後覆蓋層的節點消失、`mCurrentFocus` 不變。

**不要用「我記得剛才開了抽屜/子畫面」去推現在按返回會退到哪裡。** 那個記憶會過期（有東西自己關掉了、或上一個點擊其實沒生效），而一旦推錯，那一次返回就把你送出 app 了。**每次按返回之前先問一次 `mCurrentFocus` 與 `mInputShown`，按完再問一次。**

在 app 的最上層畫面按返回會離開 app，接著顯示的是**背景堆疊裡的另一個 app 或桌面**。之後的點擊會落在那個 app 上。每次按返回之後，先 dump 或截圖確認自己還在受測 app 裡再繼續點。

**「按兩次返回」的批次特別容易踩到這個**：第一次 BACK 若因為鍵盤其實沒開而直接退了一層，第二次就把你送出 app；接下來整批指令會安靜地打在桌面或別的 app 上，而每一步都「成功」。
做法：**能用畫面上自己的返回控制項就不要用系統 BACK**（它一次只退一層，語意固定），或在每次 BACK 之間都重新問一次 `mInputShown` 與 `mCurrentFocus`。
**確認方式**：連續兩次 `input keyevent 4` 之後 `adb shell dumpsys window | grep -m1 mCurrentFocus`，焦點若不再是受測 app 就是踩到了。*最後確認：2026-09-16*

```sh
adb shell dumpsys window | grep -m1 mCurrentFocus   # 現在焦點在哪個 app 的哪個視窗
```

**飛航模式（需要「沒有網路」的情境時）**：先記原值，做完還原。

```sh
adb shell settings get global airplane_mode_on     # ← 先記下原值（0/1）
adb shell cmd connectivity airplane-mode enable
adb shell cmd connectivity airplane-mode disable   # 收尾：還原成原值
```

**確認方式**：enable 之後 `settings get global airplane_mode_on` 回 `1`，disable 之後回 `0`。

*最後確認：2026-09-15（am kill + am start 從 task 還原；force-stop + am start 冷啟動；遮罩節點關閉面板；BACK 依鍵盤狀態逐次按）*

---

## 10. 判斷「有沒有跑推論 / 有沒有讀寫檔案」

**用 pid 過濾，不要用關鍵字過濾。** 系統本身就有一堆服務在持續輸出機器學習相關字樣（例如 thermal service），照關鍵字撈一定會撈到不是受測 app 的行。

```sh
adb logcat -c                       # 開始前清空
# ...操作...
PID=$(adb shell pidof <套件名> | tr -d '\r')
adb logcat -d | awk -v p="$PID" '$3==p'      # 只留這個 pid 的行
```

檔案有沒有被動到，用 `docs/qa-tools/hashdir.sh` 在操作前後各存一份再 diff，比 logcat 可靠。

**等「某件事做完」時，先找一個便宜的訊號**：例如「某個檔案出現了／它的內容改了」用一次 `run-as ls`（幾十毫秒）就判得出來，比每兩秒 dump 一次畫面便宜得多。**判存在與否要看輸出，不要看結束碼**（第 0 節）。
**確認方式**：`time adb exec-out run-as <套件名> sh -c 'ls files' > /dev/null` 與 `time python3 docs/qa-tools/ui.py texts > /dev/null` 各跑一次比耗時。*最後確認：2026-09-15*

*最後確認：2026-09-15（用 pidof 過濾後讀過；操作前後 hashdir.sh diff）*

---

## 11. 比較時間戳之前：先量裝置與主機的時差

裝置的牆鐘與這台 Mac **不保證同步**，實測可以差到數秒。任何「把 app 寫下的毫秒時間戳拿去和主機上記的操作時刻相減」的判讀，**沒有先扣掉這個差就沒有意義**。

**確認方式**（兩個數字都印出來，相減就是當下的時差；每輪重量一次，不要沿用舊值）：

```sh
echo "device: $(adb shell date +%s.%N)"; echo "host:   $(date +%s.%N)"
```

**更省事的做法**：需要「操作時刻」時直接取裝置的時鐘 `adb shell date +%s%3N`，兩邊都用裝置時間就不必扣時差。
**需要「什麼都不做地等 N 秒」時用 `adb shell sleep N`**，不要在主機端 sleep。

**長時間的驗收可能跨過午夜。** 任何「依今天日期決定顯示方式」的判讀（例如今天顯示時刻、其他日子顯示日期），在跨日前後會得到不同的字串。開始時與每次做這類判讀前都問一次裝置日期，並把跨日時刻寫進報告。
**確認方式**：`adb shell date` 印出的日期與你以為的「今天」相同。*最後確認：2026-09-16（本輪驗收途中跨日）*

*最後確認：2026-09-15（裝置較主機快約 2.7 秒。**這個值每輪都不一樣，不要沿用**；本輪時刻一律取裝置時鐘）*

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

**開始之前先把整個複本目錄刪掉重建**（`rm -rf <目錄>; mkdir -p <目錄>`）。scratchpad 的路徑可能與**先前某一輪**相同，裡面留著上一輪複製或改過的原始碼；同一個類別出現兩份時，編譯器報的是 `overload resolution ambiguity` 之類看起來與你無關的錯，而**更糟的情況是它編得過、你量到的是上一輪改過的那份**。
**確認方式**：複製完之後 `find <來源目錄> -type f` 印出的檔案**只**有你這一輪放進去的那幾個；每個複本再 `cmp` 一次專案裡的原檔。

**只搬得動「不依賴 Android 的檔案」**；被複製的檔案若參照到同 package 或其他 package 的檔案，一起複製。先跑一次編譯讓它告訴你缺什麼最快。

**流程建議**：先在未改動的複本上跑一次當基準，每做一個改動就跑一次，跑完把複本還原再做下一個。**改動用「斷言原字串存在再取代」的腳本做**（找不到就中止），不要用 sed 盲改——盲改失敗時複本沒變，測試全綠，你會以為那個改動沒被抓到。還原後用 `cmp` 對原始檔確認複本已回到原樣。
編譯器啟動時印出的 `WARNING: A restricted method in java.lang.System ...` 幾行是 JDK 的警告，與結果無關。

**確認方式**（跑得起來就算通過）：

```sh
"$JAVA_HOME/bin/java" -cp "$KC:$STD:$CO" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -version
```

*最後確認：2026-09-15（照這個流程編譯並執行一支探針，以 cmp 確認複本與專案原始碼相同）*

---

## 13. 怎麼做得快（同樣的事少花時間做完）

驅動裝置是每一輪最大的一筆固定成本。三個形狀：

1. **結果可預測的連續操作，寫成一條指令一次送出，每一步之後只印出需要判讀的那一行**（欄位碼位、摘要列、`mCurrentFocus`），不要一步一看。
   - **但條文要求觀察中間狀態的地方不能批**；而且批次一定要加**守衛**：每一步之前檢查預期的節點存在，不在就立刻中止整條指令。
   - **沒有守衛的批次會在第一步出錯之後，把後面每一個點擊與輸入送到錯的畫面上**。
   - **helper 自己印出 `ABORT` 還不夠**：批次裡呼叫它的那一行要接 `|| exit 1`，否則 helper 報錯了、批次照樣往下跑。
   - **打字之前的守衛要檢查「焦點在欄位上」**（dump 裡該 EditText 是 `focused="true"`），不只是「欄位存在」。
   - **守衛本身也要先自測一次**：寫一個「應該成立」與一個「應該不成立」的呼叫各跑一次，兩個結果不同才用。一個永遠回同一個答案的守衛（例如第 0 節的 exec-out 結束碼）比沒有守衛更糟。

   ```sh
   need(){ python3 <ui-helper> has "$1" >/dev/null || { echo "ABORT: missing '$1' at $2"; adb shell dumpsys window | grep -m1 mCurrentFocus; exit 1; }; }
   focused(){ python3 <ui-helper> texts | grep -q " F C .*EditText" || { echo "ABORT: field not focused at $1"; exit 1; }; }
   ```
   **確認方式**：故意 `( need "一段不存在的字" test; echo SHOULD-NOT-PRINT )`，應印出 ABORT 且不印後面那行。

2. **能當場問出答案的，不要用眼睛看。** 元件位置、欄位內容、焦點、鍵盤範圍、手勢區 —— 用 uiautomator dump 與 `dumpsys window` / `dumpsys input_method`。
   - **只有眼睛判斷得出來的留給截圖**：輸入法候選列、算繪有沒有破版、標示蓋在哪幾個字上、按鈕長什麼樣子、文字是不是逐步出現。

3. **每輪一模一樣的程序做成腳本，放在 `docs/qa-tools/`**，用之前先跑它的 selftest：
   - `ui.py`：UI dump helper（找節點、以 class 點欄位、逐碼位讀欄位）。`python3 docs/qa-tools/ui.py selftest`
   - `hashdir.sh`：列出 app 私有目錄下每個檔案的 sha256（排序過），操作前後各存一份再 diff。`docs/qa-tools/hashdir.sh selftest <套件名>` —— 會比對「雜湊行數 == 裝置端檔案數且不為 0」。*最後確認：2026-09-15*
   - `jsondir.py`：把 app 私有目錄下每個 JSON 檔 parse 後以排序過的正規形式列出（不能 parse 的改印 sha256）。**用途是回答「內容有沒有變」而不是「位元組有沒有變」** —— app 重新序列化同一份資料，位元組會不同而內容相同（見第 4 節「已知的位元組層面陷阱」）。操作前後各存一份再 `diff`。`python3 docs/qa-tools/jsondir.py selftest <套件名> <目錄>` 印出 `selftest OK: N json files listed` 才用。*最後確認：2026-09-16*
   - `uitext.py`：一次列出畫面上每個文字節點的 `y0,y1`、`repr(text)` 與**逐碼位**（`U+xxxx`）。`ui.py field` 只看得到輸入框，這支看得到所有節點，判「某一列尾端是不是完整的字」「某段文字裡是不是全形空白」時不必再截圖。`python3 docs/qa-tools/uitext.py selftest` 印出 `selftest OK: N nodes` 才用；加一個參數只印含該子字串的節點。*最後確認：2026-09-16*

4. **要在「只存在幾秒的狀態」裡動作時，把整串操作寫進同一個 `adb shell`**：`adb shell "input tap A; sleep 0.3; input tap B; sleep 0.9; input text '…'"`。貴的是主機↔裝置的來回（每次約 0.04 秒起跳，工具呼叫本身的額外開銷更大），裝置端的 `sleep` 幾乎免費。實測：同一串操作拆成 4 次 Bash 呼叫要 6–8 秒才走完，包在一個 `adb shell` 裡約 2.2 秒 —— 差別足以決定攔不攔得到一個短命狀態。
   - **裝置端可以用 `sleep 0.3` 這種小數**（主機的 zsh 沒有 `timeout`，但裝置端的 `sleep` 吃小數）。
   - **這種批次沒有守衛**，所以只在「每一步的目標位置都剛剛確認過」時用；確認方式見上面第 1 點。

5. **跑批次之前先確認「現在在哪個畫面」，不要靠記憶。** 同一組座標在不同畫面上是不同的東西，而點到沒有東西的地方**不會有任何錯誤** —— 批次照樣跑完，你會拿到一份看起來正常、其實做在別的畫面上的結果（實測踩過兩次：以為已經回到主畫面、其實還停在子畫面，於是「開新的東西」那一步整個沒發生，後面的輸入全都落進舊的那一份）。
   **確認方式**：批次的第一行先 `adb shell dumpsys window | grep -m1 mCurrentFocus`，再 dump 一次確認畫面上有你預期的那個節點，兩者都對才往下送。*最後確認：2026-09-16*
   - **不能用「跳過」來省**。腳本改過之後先跑它的確認方式 —— 腳本腐爛時會安靜地做錯事。

*最後確認：2026-09-15（守衛自測印出 ABORT 且未執行後續；hashdir.sh selftest OK）*
