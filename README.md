# BiliTV

給 Android TV / 電視盒子用的嗶哩嗶哩第三方用戶端，全程用遙控器操作。

## 功能
- **首頁**：推薦、熱門、排行榜；登入後還有關注動態、歷史紀錄（顯示觀看進度）
- **搜尋**：熱門搜尋關鍵字，或用電視輸入法輸入
- **影片詳情**：簡介、數據、選集（分P）、相關推薦
- **播放器**（ExoPlayer，DASH 音視訊分離串流）
  - 彈幕（滾動／頂部／底部），可開關
  - 畫質切換、倍速 0.5x–2x、選集
  - 登入後會從上次的位置繼續播放，並回報觀看紀錄
  - HEVC / AV1 解不了時會自動退回 AVC
- **掃碼登入**：用手機上的 B 站 App 掃描「我的」頁面的二維碼

## 遙控器操作（播放中）
| 按鍵 | 功能 |
|---|---|
| OK / 播放暫停鍵 | 暫停／播放 |
| ← → | 快退／快進 10 秒，長按會加速 |
| ↑ / 選單鍵 | 播放設定（畫質、倍速、彈幕、選集） |
| ↓ | 顯示／隱藏進度列 |
| 上一曲／下一曲、頻道 +/- | 上一集／下一集 |
| 返回 ×2 | 離開播放 |

在列表頁按返回會先回到左側選單，在選單上再按一次返回才會離開 App。

## 建置與安裝
```bash
./gradlew assembleDebug
```
APK 位置：`app/build/outputs/apk/debug/app-debug.apk`

用 adb 安裝到電視（電視要先開啟「開發人員選項 → 網路偵錯 / USB 偵錯」）：
```bash
adb connect 電視的IP:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
也可以把 APK 複製到隨身碟，在電視上用檔案管理員安裝。

## 技術
Kotlin、Jetpack Compose for TV（`androidx.tv:tv-material`）、Media3 ExoPlayer、OkHttp、Coil、ZXing。
Web API 有 WBI 簽名（`net/Wbi.kt`），並會自動取得 `buvid3` Cookie 避免被風控（HTTP 412 / -352）。

## 注意
- 這是非官方用戶端，使用的是 B 站網頁版的公開介面，介面變更或風控調整都可能讓部分功能失效。
- 未登入時畫質較低；1080P 高碼率、4K 等需要大會員。
- 僅供個人學習使用。
