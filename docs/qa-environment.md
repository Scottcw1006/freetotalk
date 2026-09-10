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

測試結果的機器可讀位置（不依賴 console 輸出）：

```sh
ls app/build/test-results/testDebugUnitTest/*.xml
# 每個 XML 的 root 屬性有 tests / failures / errors / skipped
```

*最後確認：2026-09-10（`clean testDebugUnitTest` 實際跑完，逐檔讀過 XML 的屬性）*

---

## 2. 實機

Pixel 9，USB 連線。

**確認方式**：

```sh
adb devices -l          # 要看到一台 device（不是 unauthorized / offline）
adb shell wm size       # 實際解析度
adb shell wm density    # 實際 density；若有 Override density 會一起列出
```

*最後確認：2026-09-10（`adb devices -l` 回報 model:Pixel_9，Android 16；`wm size` 1080x2424、`wm density` 420 無 override）*

---

## 3. 實機判讀之前：先證明裝置上跑的是哪一份建置

**這是每一輪都要付的成本，不要跳過。** 對著過期的成品驗，會產出一整份看起來很正常的假結果。

做法（擇一即可，重點是**留下可查證的證據**）：

```sh
# (a) 安裝時間 vs 原始碼最後修改時間
adb shell dumpsys package <套件名> | grep lastUpdateTime
find <原始碼與文件目錄> -type f \( -name '*.kt' -o -name '*.html' \) -print0 \
  | xargs -0 stat -f "%Sm %N" -t "%Y-%m-%d %H:%M:%S" | sort | tail -5

# (b) 直接重新建置並安裝，然後記下安裝時間
./gradlew installDebug && adb shell dumpsys package <套件名> | grep lastUpdateTime
```

套件名當場問出來，不要寫死在腳本裡：

```sh
adb shell pm list packages -3 | grep -i <你認得的字>
```

判準：安裝時間必須**晚於**最後一次原始碼變更時間。把實際數字抄進當輪報告。

**重新安裝不會清掉 app 的私有資料**（確認方式見第 4 節：安裝前後比對雜湊）。

*最後確認：2026-09-10（用 `dumpsys package | grep lastUpdateTime` 對照 `find` 出來的原始碼最後修改時間，安裝時間確實較晚；本輪沒有重新安裝）*

---

## 4. App 私有資料的快照與還原（實機上有使用者的真實資料）

這台是使用者本人的裝置。**動手前先完整快照並記下雜湊，測完逐位元組驗證還原。**

App 是 debuggable 的，所以可以用 `run-as` 讀寫它的私有目錄。
**先把目錄樹問出來，不要假設資料放在哪個子目錄**（子目錄名是產品自己的事，會改）：

```sh
PKG=<套件名>
adb exec-out run-as $PKG ls -R files
adb exec-out run-as $PKG ls shared_prefs
```

**快照**（用 `exec-out` 而不是 `shell`，否則二進位/換行會被改寫）：

```sh
SNAP=/tmp/qa-snapshot; rm -rf "$SNAP"; mkdir -p "$SNAP"
# 先列出所有檔案的相對路徑，再逐檔 cat 出來
adb exec-out run-as $PKG sh -c 'find files shared_prefs -type f' | tr -d '\r' > "$SNAP/filelist.txt"
while read -r f; do
  mkdir -p "$SNAP/$(dirname "$f")"
  adb exec-out run-as $PKG cat "$f" > "$SNAP/$f"
done < "$SNAP/filelist.txt"
(cd "$SNAP" && shasum -a 256 $(cat filelist.txt) > baseline.sha256)   # macOS 的 xargs 沒有 -a
```

**推測試資料進去**（不要動既有檔案，用自己認得出來的檔名前綴）：

```sh
adb shell rm -rf /data/local/tmp/qa && adb shell mkdir -p /data/local/tmp/qa   # 先清乾淨
adb push <本機檔> <本機檔> ... /data/local/tmp/qa/     # 逐檔列出，不要 push 整個目錄
adb shell "run-as $PKG sh -c 'cp /data/local/tmp/qa/*.json <資料目錄>/'"
```

**這兩件事都是踩過的**：(a) `/data/local/tmp/<你的目錄>` 可能還留著**前一輪**的東西，接著那句 `cp *.json` 會把它們一起灌進 app 的資料目錄；(b) scratchpad 目錄同樣可能有前一輪的殘留，`adb push <目錄>/.` 會把整包送上去。
**確認方式**：`cp` 之後立刻把資料目錄的檔案清單與快照的清單 `comm -23` 比一次，多出來的只能是你這一輪自己建的那幾個。

**還原並驗證**：

```sh
adb shell am force-stop $PKG                   # 先停掉，否則它可能會再寫回去
adb shell "run-as $PKG sh -c 'rm -f <資料目錄>/<你的前綴>*'"
# 測試過程若讓 app 自己建了新檔，用 comm 把「現在有、快照沒有」的檔案挑出來刪掉：
#   comm -23 <(現在的檔案清單) <(快照的檔案清單)
adb push "$SNAP/shared_prefs/<偏好檔>" /data/local/tmp/ && \
  adb shell "run-as $PKG sh -c 'cp /data/local/tmp/<偏好檔> shared_prefs/'"
adb shell "rm -rf /data/local/tmp/qa /data/local/tmp/<偏好檔>"
# 重新抓一次再比對雜湊，只看畫面正常不算還原成功
```

**已知的位元組層面陷阱**：手寫的 JSON 與 app 自己序列化出來的 JSON，**內容相同但位元組可能不同**（縮排、鍵之間的空白）。比對雜湊發現差異時，先 parse 成物件再比，才分得出「內容真的被改了」與「只是重新序列化」。
→ 推論：**你自己推進去的測試檔，只要被 app 讀寫過一次就不能再拿位元組雜湊當基準**；使用者原本就有的檔案才可以。

*最後確認：2026-09-10（快照 → 推測試檔 → 大量操作 → 刪測試檔 → 還原偏好 → 雜湊與 baseline 完全相同；本輪再跑一次同樣成立）*

---

## 5. 讀畫面：用 UI tree，不要用肉眼猜座標

```sh
adb exec-out uiautomator dump /dev/tty
```

會吐出一段 XML（前後夾雜一行提示字，parse 前先切掉 `<?xml` 之前與最後一個 `>` 之後）。每個節點有 `text` / `content-desc` / `bounds`。

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
- **文字選取的浮動工具列（全部選取／複製／貼上）也不在 dump 裡**，只能從截圖量座標。
- 有「抽屜 / 側邊面板」這類覆蓋式版面時，**軟鍵盤會蓋在它上面，但 dump 回報的 bounds 是邏輯位置，不會扣掉鍵盤**。照那個座標點下去會點到鍵盤。點之前先確認鍵盤狀態（第 6 節），必要時先把清單捲到鍵盤上方再點。
  - **這個坑的症狀是「點了沒反應」**（畫面完全不變），很容易被誤讀成受測程式沒有回應。確認方式：`adb shell dumpsys input_method | grep -m1 mInputShown`，是 `true` 就先收鍵盤再點。
  - **收鍵盤在這台裝置上只有 `input keyevent 4`（BACK）有效**；`input keyevent 111`（ESCAPE）不會收。確認方式：送完之後再 grep 一次 `mInputShown`。
  - **面板開著時按一次 BACK 只收鍵盤，面板留著**（2026-09-10 實測）。所以「面板開著但鍵盤擋住下半部」的解法是：BACK 一次 → 再 grep `mInputShown` 確認變成 false → 重新 dump 取座標再點。**座標一定要重新取**，收鍵盤前後的 bounds 可能不同。
- **可捲動清單會保留捲動位置**：面板關掉再打開，第一列不一定是最上面那一列。**每次要點之前都重新 dump**，不要沿用上一次算好的座標，也不要用「第一列就是最新的」這種假設去判讀內容。
- 只想看「哪些節點可以點」時，挑 `clickable="true"` 的節點取 `bounds`，比從 `text` 的 bounds 反推可靠 —— 一列的可點區域通常比它的文字大。

*最後確認：2026-09-10*

---

## 6. 輸入法（這台裝置最花時間的一塊）

裝置上唯一的文字輸入法是 Gboard，且**同時掛著注音與英文兩種版面**。

```sh
adb shell ime list -s                                   # 目前可用的輸入法
adb shell settings get secure default_input_method      # 目前使用的那一個
adb shell dumpsys input_method | grep -m1 mInputShown   # 鍵盤是否正在顯示
```

### 6.1 目前是哪一個版面

看**空白鍵上的字**（截圖判讀）：注音版面寫「注音」，英文版面寫「English」。
切換版面：點鍵盤上的**地球鍵**（在最下排、`?123` 右邊；位置用截圖確認，不要寫死）。

**版面是跨畫面共用的狀態，而且會留在你上次切的那一個。** 收尾時記得切回你進來時的那一個（進來時先截一張圖存證）。

### 6.2 打英文 / ASCII

英文版面下 `adb shell input text "..."` 直接可用（空白用 `%s`）。

```sh
adb shell input text "hello%sworld"
```

`input text` **只送得出 ASCII**。送任何非 ASCII 字元（全形空白、CJK 皆然）會直接在 shell 端拋例外：
`java.lang.NullPointerException: Attempt to get length of null array`（`InputShellCommand.sendText`），**欄位完全不會變**。
確認方式：`adb shell input text "<一個中文字>"`，預期看到上面那個例外。

**注音版面下不要用它送 ASCII** —— 那些字母會被當成注音符號進入「組字中」的狀態，**欄位內容完全不會變**。
**這個失敗是無聲的**：`input text` 不會報錯，dump 出來的欄位是空的，看起來像受測程式吃掉了輸入。
確認方式：送完之後 dump 欄位；欄位沒變就截一張圖，看鍵盤上方有沒有組字列／候選列。

**焦點不在輸入欄位時，`input text` 會退化成一連串按鍵事件**，落在當下持有焦點的元件上。
其中 `%s`（空白）在**焦點是按鈕**時等於「按下那顆按鈕」—— 也就是說一句看起來只是打字的指令，
可能觸發一次導覽。**踩過兩次。** 送字串之前先確認焦點真的在欄位裡（第 7 節）。

### 6.3 打中文（注音版面）

Gboard 的注音版面用的是**標準注音鍵盤配置**：每個鍵右上角印著它對應的英數鍵。因此可以用
`adb shell input text "<那幾個英數鍵>"` 送出一個音節，再從候選列選字。

```sh
# 例：送出一個音節的按鍵序列（實際字母請照鍵帽右上角的標示，用截圖確認）
adb exec-out screencap -p > kb.png        # 先看鍵帽右上角的英數標示
adb shell input text "<那幾個鍵>"
adb exec-out screencap -p > cand.png      # 看候選列有哪些字、在第幾格
adb shell input tap <該候選格中心>         # 從截圖量出來
```

要點：
- 候選列與組字列**不在 uiautomator dump 裡**，只能截圖判讀。
- 候選列是等寬的若干格，**每次都從截圖重新量**，不要沿用上一輪的座標。
- 送完按鍵序列後字還在「組字中」，**尚未進入欄位**；一定要選完字才算輸入。
- **聲調鍵與韻母鍵也都在鍵帽的英數標示上**（含 `,` `.` `/` `;` 這幾個非字母鍵）。需要它們時就直接送那個 ASCII 字元。
- **不要用 `%s`（空白）當「第一聲」送**：組字中的空白鍵是「選字」，不是聲調也不是空白。
- 需要在中文詞之間打**半形空白**時：組字狀態下按空白鍵是選字，沒有組字時按空白鍵才是插入空白。所以順序是「先把字選完，再按空白鍵」。

### 6.4 幾個會讓你以為程式壞掉的鍵盤行為

- **長按空白鍵會叫出「切換鍵盤」選單**，不是連續輸入空白。所以「長按空白」這個操作在這台裝置上產不出連續空白。用 `input keyevent 4` 關掉那個選單。
- **英文版面按空白鍵會提交自動更正的建議字**（打 `ab` 再按空白可能變成別的字）。要把**一個精確的字串**（尤其含尾端空白）放進欄位，用單一次 `adb shell input text "...%s%s"` 一起送，不要用點空白鍵的方式補。
- **連按兩次空白鍵會被輸入法換成「句號＋空白」**，不是兩個空白。所以「連續兩個空白」用點鍵的方式做不出來，一樣要用單一次 `input text "...%s%s"`。確認方式：逐碼位讀欄位，看到 `0x2e` 就是踩到了。
- 確認方式：送完之後逐碼位讀欄位（第 5 節），不要看截圖。

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
（先前確認過：這台裝置的輸入法在注音版面、英文版面與 `?123` 符號頁上都找不到全形空白 U+3000 的鍵。2026-09-10 重跑 `adb shell cmd clipboard`，仍然回 `No shell command implementation.`）

*最後確認：2026-09-10*

---

## 7. Compose 畫面的焦點

受測 app 用 Jetpack Compose。判讀「輸入框還有沒有焦點」時要知道兩件事：

- 按鍵盤上的 **IME 動作鍵（打勾／Enter）只會收鍵盤，不會清掉 `TextField` 的焦點**。
- **點畫面上非互動的空白區域也不會清焦點**。

- 但 **`adb shell input keyevent 61`（TAB）會把焦點移到下一個可聚焦元件**，`TextField` 因此失去焦點。**（2026-09-10 更正：這一節原本寫「不一定做得到」，實測 TAB 可以。）**

所以「讓某個輸入框失去焦點但不離開畫面」在這個環境**做得到**，先試 TAB；若某個畫面上 TAB 也無效，才照 spec 對「本環境無法觀察」的規定處理。

**（2026-09-10 更正）「送一個字元看欄位變不變」不是可靠的焦點判準。** 實測：TAB 之後焦點確實移走了，
但 IME 的輸入連線仍可能把後續字元送回原來那個欄位，於是欄位還是會變 —— 用它判會得到「焦點沒走」的錯誤結論。
**改用這個判準**：TAB 之後 dump 一次（第 5 節），看焦點跑到哪個元件；再確認你要觀察的效果是否發生。
**而且 TAB 之後焦點通常落在某顆按鈕上**，此時再送含空白的字串會按下它（見第 6.2 節末段）。

```sh
adb shell dumpsys input_method | grep -m1 mInputShown   # 只說明鍵盤有沒有開，不等於焦點
```

*最後確認：2026-09-10（TAB 可用；「送字元判焦點」那一條當輪實測推翻並改寫）*

---

## 8. 顯示大小與螢幕方向（改之前先記下原值）

```sh
# 顯示大小（density override）
adb shell wm density              # 先看原值與有沒有 override
adb shell wm density 546          # 套用
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

*最後確認：2026-09-10（density 420 無 override；accelerometer_rotation=1、user_rotation=0；套用 606 與橫向後都還原成功）*

---

## 9. 啟動、停止、行程被回收

```sh
PKG=<套件名>
adb shell am start -n $PKG/.MainActivity     # 啟動（Activity 名用 dumpsys package 問）
adb shell am force-stop $PKG                 # 完全終止（不保留 saved instance state）
adb shell am kill $PKG                       # 模擬「被系統回收」（保留 task 與 saved state）
adb shell pidof $PKG                         # 確認行程在不在
```

要驗「行程死亡後還原」用 `am kill`（先按 Home 讓它進背景），**不是** `force-stop`。

**系統返回鍵的注意事項**：**軟鍵盤開著時，第一次 BACK 只收鍵盤，不會退一層畫面**；鍵盤沒開時 BACK 才是退一層。所以「按兩次返回」在這兩種狀態下的結果完全不同 —— **每次按之前先問一次鍵盤狀態**（`adb shell dumpsys input_method | grep -m1 mInputShown`），不要用固定次數。
在 app 的最上層畫面按返回會離開 app，接著顯示的是**背景堆疊裡的另一個 app 或桌面**。之後的點擊會落在那個 app 上 —— 曾經因此在別的 app 裡誤觸出一個編輯草稿。每次按返回之後，先 dump 或截圖確認自己還在受測 app 裡再繼續點。

```sh
adb shell dumpsys window | grep -m1 mCurrentFocus   # 現在焦點在哪個 app 的哪個視窗
```

*最後確認：2026-09-10（本輪又踩到兩次：以為在受測 app 裡，其實已經掉到桌面，接下來的點擊與輸入都落在別的 app 上）*

---

## 10. 判斷「有沒有跑推論 / 有沒有讀寫檔案」

**用 pid 過濾，不要用關鍵字過濾。** 系統本身就有一堆服務在持續輸出機器學習相關字樣（例如 thermal service），照關鍵字撈一定會撈到不是受測 app 的行。

```sh
adb logcat -c                       # 開始前清空
# ...操作...
PID=$(adb shell pidof <套件名>)
adb logcat -d | awk -v p="$PID" '$3==p'      # 只留這個 pid 的行
```

檔案有沒有被動到，用第 4 節的雜湊比對，比 logcat 可靠。

*最後確認：2026-09-10（用 pidof 過濾後逐行讀過）*

---

## 11. 比較時間戳之前：先量裝置與主機的時差

裝置的牆鐘與這台 Mac **不保證同步**，實測可以差到數秒。任何「把 app 寫下的毫秒時間戳拿去和主機上記的操作時刻相減」的判讀，**沒有先扣掉這個差就沒有意義**。

**確認方式**（兩個數字都印出來，相減就是當下的時差；每輪重量一次，不要沿用舊值）：

```sh
echo "device: $(adb shell date +%s.%N)"; echo "host:   $(date +%s.%N)"
```

**更省事的做法**：需要「操作時刻」時直接取裝置的時鐘 `adb shell date +%s%3N`，兩邊都用裝置時間就不必扣時差。
**需要「什麼都不做地等 N 秒」時用 `adb shell sleep N`**，不要在主機端 sleep。

*最後確認：2026-09-10（本輪重量：兩邊相差不到 0.3 秒。**這個值每輪都不一樣，不要沿用**）*

---

## 12. 不改動專案原始碼，就地跑一份「改壞的」純 Kotlin 邏輯

需要證明「某支測試真的釘住了某個行為」時，做法是把被測邏輯改成錯的、看測試會不會變紅。**但受測專案的原始碼不該被 QA 改**（改了忘記還原，下一輪就在驗一份不存在的東西；沙箱也可能直接擋下寫入）。

做法：**把要改的檔案複製到 scratchpad，在複本上改，用 Gradle 快取裡的 Kotlin 編譯器單獨編譯與執行。** 專案原始碼全程唯讀。

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
# 這幾個 jar 都在 ~/.gradle/caches 裡；路徑含雜湊，用 find 當場問，不要寫死
KC=$(find ~/.gradle/caches -name 'kotlin-compiler-embeddable-*.jar' | head -1)
STD=$(find ~/.gradle/caches -name 'kotlin-stdlib-2*.jar' | grep -v sources | head -1)
CO=$(find ~/.gradle/caches -name 'kotlinx-coroutines-core-jvm-*.jar' | grep -v sources | head -1)
AN=$(find ~/.gradle/caches -name 'annotations-13*.jar' | grep -v sources | head -1)
JU=$(find ~/.gradle/caches -name 'junit-4*.jar' | grep -vE 'sources|javadoc' | head -1)
HC=$(find ~/.gradle/caches -name 'hamcrest-core-*.jar' | grep -vE 'sources|javadoc' | head -1)

# 編譯（來源目錄是 scratchpad 裡的複本，套件結構要保留）
"$JAVA_HOME/bin/java" -cp "$KC:$STD:$CO:$AN" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -nowarn -no-stdlib -jvm-target 11 -cp "$STD:$JU:$HC" -d <輸出目錄> <來源目錄>

# 執行單一測試類別
"$JAVA_HOME/bin/java" -cp "<輸出目錄>:$STD:$JU:$HC" org.junit.runner.JUnitCore <測試類別全名>
```

三個一定會踩到的點（少一個就會失敗，而錯誤訊息都看不出真正原因）：

1. `kotlin-stdlib` 與 `kotlinx-coroutines-core-jvm` 要放在**執行編譯器的那個 `-cp`**（不是只放在給被編譯程式的 `-cp`）。少了會報 `遺漏 JavaFX 執行元件` 或 `NoClassDefFoundError: kotlinx/coroutines/CoroutineScope`，兩個都與真正的原因無關。
2. `annotations-13.0.jar` 也要放在編譯器的 `-cp`。少了會在產碼階段丟 `Exception while generating code for: FUN ...`，看起來像編譯器 bug。
3. 加 `-no-stdlib`，否則它會去找不存在的 kotlin home。

**編譯時要把所有相依的來源目錄一起列在命令列上**（不是只列被測的那一個）。少列一個時，編譯器不會報錯，但產出的目錄裡不會有你要跑的類別，執行時只看到 `ClassNotFoundException`，看起來像是類別名打錯。

**只搬得動「不依賴 Android 的檔案」**；被複製的檔案若參照到同 package 的其他函式，一起複製或就地補一份最小定義。實務上會需要把被測檔案的**相依鏈**一起複製（資料模型、enum 等），先跑一次編譯讓它告訴你缺什麼最快。

**流程建議**：先在未改動的複本上跑一次當基準（應該全綠），每做一個突變就跑一次，跑完把複本還原再做下一個。

**確認方式**（跑得起來就算通過）：

```sh
"$JAVA_HOME/bin/java" -cp "$KC:$STD:$CO" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -version
```

*最後確認：2026-09-10（照這個流程編譯成功並跑完一次基準 + 五次突變 + 一支自寫的 main 探針，專案原始碼的 mtime 全程未變）*
