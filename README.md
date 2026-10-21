# BiliTV

給 Android TV / 電視盒子用的嗶哩嗶哩第三方用戶端，全程用遙控器操作。

## 功能
- **首頁**：推薦、熱門、排行榜；登入後還有關注動態、歷史紀錄（顯示觀看進度）
- **搜尋**：熱門搜尋關鍵字，或用電視輸入法輸入
- **影片詳情**：簡介、數據、選集（分P）、**合集播放清單**（標出目前這集）、相關推薦
- **UP 主頁**：在影片詳情點 UP 主進入，看粉絲數、簡介、全部投稿；登入後可關注／取消關注，開播中可直接進直播間
- **直播**：推薦直播、我關注的直播（登入後），HLS 播放、可切換畫質，斷線自動換線路
- **播放器**（ExoPlayer，DASH 音視訊分離串流）
  - 彈幕（滾動／頂部／底部），可開關
  - 畫質切換、倍速 0.5x–2x、選集、合集換集
  - 分P／合集播完自動播下一集
  - 登入後會從上次的位置繼續播放，並回報觀看紀錄
  - HEVC / AV1 解不了時會自動退回 AVC
- **掃碼登入**：用手機上的 B 站 App 掃描「我的」頁面的二維碼
- **App 內更新**：開啟時自動檢查新版本，也可以在「我的」手動檢查
- **開啟連結**：`bilibili.com/video/BV…`、`space.bilibili.com/<UID>`、`live.bilibili.com/<房間號>` 可以直接用 BiliTV 開啟

## 遙控器操作（播放中）
| 按鍵 | 功能 |
|---|---|
| OK / 播放暫停鍵 | 暫停／播放 |
| 長按 OK | 按住期間 2 倍速播放，放開恢復原本速度 |
| ← → | 快退／快進 10 秒，長按會加速 |
| ↑ / 選單鍵 | 播放設定（畫質、倍速、彈幕、選集、合集） |
| ↓ | 顯示／隱藏進度列 |
| 上一曲／下一曲、頻道 +/- | 上一集／下一集（分P 播完會接合集的下一集） |
| 返回 ×2 | 離開播放 |

直播間：OK 暫停、↑ 切換畫質、↓ 顯示資訊、返回 ×2 離開。

在列表頁按返回會先回到左側選單，在選單上再按一次返回才會離開 App。

## 下載安裝（電視上）

### Downloader 下載碼：`5925092`

用電視上的 **Downloader**（AFTVnews 出的 App）安裝：
1. 在 Google Play / Amazon 商店安裝 Downloader。
2. 在「設定 → 隱私權與安全性 → 安裝不明應用程式」允許 Downloader 安裝 App。
3. 打開 Downloader，在網址欄輸入 **`5925092`**，按 Go，下載完成後按「安裝」。

下載碼永遠指向最新版，之後有新版本時用同一組碼再裝一次就會直接覆蓋更新（簽章固定，登入狀態會保留）。

也可以直接輸入完整網址（每次推送到 `main` 都會由 GitHub Actions 自動打包並發布）：

```
https://github.com/DLIN131/BiliTV/releases/latest/download/BiliTV.apk
```

或用短網址：<https://aftv.news/5925092>

## 自己建置
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
