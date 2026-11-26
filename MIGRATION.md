# MIGRATION.md — Shizuku测速 接手与演进说明

> 本文件作为项目事实基线；若与 `README.md` 存在描述差异，以本文件和源码为准。

## 1. 当前基线状态

- **应用名称**：Shizuku测速
- **包名**：`com.shizuku.speedtest`
- **当前版本**：`versionCode = 11` / `versionName = "1.9.1"`
- **技术栈**：Kotlin 1.9.24 + Jetpack Compose 1.6.8 + Material Design 3 (`1.2.1`)，AGP `8.5.2`，Gradle `8.8`，`minSdk 24` / `targetSdk 34` / `compileSdk 34`，JDK 17
- **历史发布包（GitHub Release `v1.9.1`）**：
  - 文件名：`ShizukuSpeedTest-v1.9.1.apk`
  - APK SHA-256：`509cbdedd4a529e66f63136c1f1dbf280db5d0aad1463e729654dc9992178fec`
  - Release 证书：`CN=Shizuku, OU=Personal, O=Shizuku, C=CN`
  - Release 证书 SHA-256：`85255c644990ab26bd14d1a88496283882ceb2a8f4313208e15f0c45a551cf30`
- **源码重建来源**：由于历史仓库仅保留了 `ShizukuSpeedTest-v1.9.1.apk`（业务类未混淆），当前仓库源码、图标矢量与各分辨率 mipmap 均从该 APK 1:1 还原并验证编译通过，确保升级覆盖安装后数据库、偏好设置与功能完全兼容。

## 2. 签名与发布约束

1. **Keystore 绝不入库**：`*.jks` / `*.keystore` / `*.p12` 已在 `.gitignore` 中排除。
2. **Release 签名注入方式**：通过环境变量注入（详见 `app/build.gradle.kts`）：
   - `SHIZUKU_RELEASE_STORE_FILE`
   - `SHIZUKU_RELEASE_STORE_PASSWORD`
   - `SHIZUKU_RELEASE_KEY_ALIAS`
   - `SHIZUKU_RELEASE_KEY_PASSWORD`
3. **覆盖安装校验**：发布可覆盖 `v1.9.1` 的正式包前，必须用 `apksigner verify --print-certs` 核对证书 SHA-256 为 `85255c644990ab26bd14d1a88496283882ceb2a8f4313208e15f0c45a551cf30`；更换证书的包无法覆盖安装。
4. **交付规范**：视觉改动需先提供预览确认后再打包；交付 APK 时使用准确版本号文件名（如 `ShizukuSpeedTest-vX.Y.Z.apk`）并附带 SHA-256 校验值；真机行为由项目维护者在手机上实测验证。

## 3. 核心架构与不可回退约束

### 3.1 上传测速（v1.8+ 流式上传，禁止回退）
- 实现位置：`app/src/main/java/com/shizuku/speedtest/net/SpeedTestEngine.kt` (`measureUpload`)
- 策略：按档位时长流式分块上传（`setChunkedStreamingMode(65536)`）：
  - 轻量（`LIGHT`）：2 并发，5 秒，单连接上限 32 MB
  - 标准（`STANDARD`）：4 并发，10 秒，单连接上限 64 MB
  - 完整（`FULL`）：6 并发，15 秒，单连接上限 128 MB
- 到达时限直接 `flush` 并 `disconnect()`，不等待服务端慢响应；跳过前 1 秒预热期，每 200ms 采样瞬时速率，剔除 >3 倍均值的异常尖峰后取稳态采样均值作为最终上传速率。
- **严禁回退为小流量固定 payload 上传**。

### 3.2 内置节点与网络可达性说明
- `AppDatabase.BUILTIN_NODES`：
  1. `Cloudflare（国际）` — `https://speed.cloudflare.com` (`cloudflare`)
  2. `南京航空航天大学` — `https://speed.nuaa.edu.cn` (`librespeed`)
  3. `香港 BGP` — `https://hkbgp.zgocloud.org` (`librespeed`)
  4. `东京 BGP` — `https://jpbgp.zgocloud.org` (`librespeed`)
- **重要环境事实**：南京航空航天大学节点在维护者家庭网络环境下不可达，香港 BGP 是实际可用节点；评估节点可用性时不能仅以云端环境连通性为准。

### 3.3 数据持久化与兼容性
- **SQLite 数据库**：`speedtest.db`，`version = 2`，三张表：`networks`、`nodes`、`results`（含 `idx_results_network_time`、`idx_results_time` 索引）。升级表结构时务必递增版本号并在 `onUpgrade` 做好无损迁移。
- **SharedPreferences**：`speedtest_settings`，键包括 `retention_days`（默认 90）、`last_node_id`、`traffic_tier`（默认 `STANDARD`）、`theme_mode`（默认 `SYSTEM`）、`dynamic_color`（默认 `true`）。
- **数据导出与备份**：支持带 UTF-8 BOM 的 CSV 导出以及完整 JSON（`app = "shizuku-speedtest"`, `version = 1`）备份与去重恢复。

### 3.4 本地局域网测速
- 电脑端运行根目录 `local_speed_server.py`（默认监听 `0.0.0.0:8899`），提供 `/ping`、`/down?bytes=N`、`/up` 接口（支持 `Transfer-Encoding: chunked` 与客户端超时直接断开）。
- 手机端在「设置 -> 测速节点」添加「本地服务」类型节点（如 `http://192.168.6.100:8899`）。
- `AndroidManifest.xml` 已开启 `android:usesCleartextTraffic="true"` 以兼容 Android 9+ 局域网 HTTP 本地测速服务（注：原 `v1.9.1` APK 清单中未显式声明该属性，重建时已显式补齐以避免局域网 HTTP 被系统拦截）。

### 3.5 图标设计规范（v1.9.1）
- 极简仪表矢量图标：`ic_launcher_foreground.xml`（深蓝 `#2B4663` 弧线 + 指针 + 空心圆环，无刻度，弧线两端为 `round` 圆弧头）+ 纯白背景 `ic_launcher_background.xml`（`#FFFFFF`）。
- 各分辨率 `mipmap-*/ic_launcher*.png` 与 `v1.9.1` APK 保持逐像素一致。

## 4. 已知历史小项（供后续迭代参考）
- `SettingsScreen.kt`「关于」卡片中版本号文案在 `v1.9.1` APK 内仍硬编码为 `Shizuku测速 v1.7`，重建基线暂按原样保留，后续发版时可统一改为自动读取版本名。
