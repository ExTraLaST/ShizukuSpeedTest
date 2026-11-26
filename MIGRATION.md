# MIGRATION.md — Shizuku测速 接手与演进说明

> 本文件作为项目事实基线；若与 `README.md` 存在描述差异，以本文件和源码为准。

## 1. 当前基线状态

- **应用名称**：Shizuku测速
- **包名**：`com.shizuku.speedtest`
- **当前版本**：`versionCode = 12` / `versionName = "1.9.2"`
- **技术栈**：Kotlin 1.9.24 + Jetpack Compose 1.6.8 + Material Design 3 (`1.2.1`)，AGP `8.5.2`，Gradle `8.8`，`minSdk 24` / `targetSdk 34` / `compileSdk 34`，JDK 17
- **当前发布包（GitHub Release `v1.9.2`）**：
  - 文件名：`ShizukuSpeedTest-v1.9.2.apk`
  - APK SHA-256：`bd1e18a5737fb1df569e12f73596d55fdbe4dd851932a1c423f7809ffce450a4`
  - Release 证书（`v1.9.2+` 新证书）：`CN=Shizuku, OU=Personal, O=Shizuku, C=CN`
  - Release 证书 SHA-256：`73ba0db584909f7588552e4e40d6bca2f64377cefc0626f9094b35a4d5c1fbee`
- **历史发布包（GitHub Release `v1.9.1`，旧证书）**：
  - 文件名：`ShizukuSpeedTest-v1.9.1.apk`
  - APK SHA-256：`509cbdedd4a529e66f63136c1f1dbf280db5d0aad1463e729654dc9992178fec`
  - 旧证书 SHA-256：`85255c644990ab26bd14d1a88496283882ceb2a8f4313208e15f0c45a551cf30`（因原 keystore 遗失，自 `v1.9.2` 起切换新证书；从 `v1.9.1` 升级需先在 App 内备份 JSON，卸载旧版再安装 `v1.9.2` 后恢复）
- **源码重建来源**：由于历史仓库仅保留了 `ShizukuSpeedTest-v1.9.1.apk`（业务类未混淆），当前仓库源码、图标矢量与各分辨率 mipmap 均从该 APK 1:1 还原并验证编译通过，数据库与 JSON 备份格式完全兼容。

## 2. 签名与发布约束

1. **Keystore 绝不入库**：`*.jks` / `*.keystore` / `*.p12` 已在 `.gitignore` 中排除。
2. **Release 签名注入方式**：通过环境变量注入（详见 `app/build.gradle.kts`）：
   - `SHIZUKU_RELEASE_STORE_FILE`
   - `SHIZUKU_RELEASE_STORE_PASSWORD`
   - `SHIZUKU_RELEASE_KEY_ALIAS`
   - `SHIZUKU_RELEASE_KEY_PASSWORD`
3. **覆盖安装校验**：发布可覆盖 `v1.9.2` 及之后版本的正式包前，必须用 `apksigner verify --print-certs` 核对证书 SHA-256 为 `73ba0db584909f7588552e4e40d6bca2f64377cefc0626f9094b35a4d5c1fbee`；更换证书的包无法覆盖安装。
4. **交付规范**：视觉改动需先提供预览确认后再打包；交付 APK 时使用准确版本号文件名（如 `ShizukuSpeedTest-vX.Y.Z.apk`）并附带 SHA-256 校验值；真机行为由项目维护者在手机上实测验证。

## 3. 核心架构与不可回退约束

### 3.1 下行与上传测速引擎（流式按时长测速，禁止回退）
- 实现位置：`app/src/main/java/com/shizuku/speedtest/net/SpeedTestEngine.kt`（`measureDownload`、`measureUpload`）
- **下载测速（按时长流式并发）**：
  - 档位并发与时长：轻量 `2 并发 / 5 秒`、标准 `4 并发 / 10 秒`、完整 `6 并发 / 15 秒`；连接循环拉取大块数据直至到达时限，避免单连接慢尾拖垮整体吞吐，附带 `Accept-Encoding: identity`、`useCaches = false` 及随机防缓存参数 `r`。
  - 以收到首字节（`firstByteNs`）为基准跳过握手与 TCP Slow Start 预热期（1.2s / 1.8s / 2.0s），采样采用 1 秒滑动窗口平滑，最终下载速率取稳态窗口净字节 / 时长。
- **上传测速（v1.8+ 流式上传，禁止回退）**：
  - 保持按档位时长流式分块上传（`setChunkedStreamingMode(16384)`，单次 POST 满 32 MB 自动轮换新请求以防高速网络或服务端单请求上限导致并发跌落），到达时限通过 watchdog 立即 `disconnect()`，不等待服务端响应。
  - 优先采用 Android 进程级网络层发送字节计数 `TrafficStats.getUidTxBytes(Process.myUid())`（不支持时自动回退应用层字节），以首字节发送时刻（TLS 握手完成后）起算预热期（1.5s / 2.0s / 2.2s）并采用 1 秒滑动窗口，彻底消除 `SO_SNDBUF` / 路由器缓冲引发的倒数函数（`1/x`）曲线；最终上传速度对稳态采样按中位数剔除 >2.2× 尖峰后取均值。
  - **严禁回退为小流量固定 payload 上传**。

### 3.2 内置节点与网络可达性说明
- 原内置的南京航空航天大学节点（`https://speed.nuaa.edu.cn`）经核查 443 端口拒绝连接，HTTP 端 PHP-FPM 已失效（`GET /backend/garbage.php` 返回 1.5 KB 源码、`POST /backend/empty.php` 返回 HTTP 405），属于服务端故障无法修复，已在数据库 v3 迁移中移除。
- `AppDatabase.BUILTIN_NODES`（v3）：
  1. `上海大学（上海）` — `https://speedtest4.cc.shu.edu.cn` (`librespeed`)
  2. `南京大学（南京）` — `https://fs.nju.edu.cn/speed` (`librespeed_root`，根路径 `/empty.php` & `/garbage.php`)
  3. `同济大学（上海）` — `https://dev.tongji.edu.cn/speedtest` (`librespeed`)
  4. `江苏大学（电信）` — `http://speed.ujs.edu.cn` (`librespeed`)
  5. `云南师范大学（昆明）` — `https://speedtest.ynnu.edu.cn` (`librespeed`)
  6. `香港 BGP` — `https://hkbgp.zgocloud.org` (`librespeed`)
  7. `东京 BGP` — `https://jpbgp.zgocloud.org` (`librespeed`)
  8. `Cloudflare（国际）` — `https://speed.cloudflare.com` (`cloudflare`)
- **重要环境事实**：教育网 / 电信等大陆节点在不同家庭宽带运营商下的路由可达性与带宽表现存在差异，香港 BGP 已确认在维护者家庭网络可用；评估节点可用性时不能仅以云端环境连通性为准，需以真机实测为准。

### 3.3 数据持久化与兼容性
- **SQLite 数据库**：`speedtest.db`，`version = 3`，三张表：`networks`、`nodes`、`results`（含 `idx_results_network_time`、`idx_results_time` 索引）。v2 → v3 迁移会删除原内置南京航空航天大学节点、清理指向已删节点的 `networks.default_node_id`（历史测速记录完整保留），并补充新的内置大陆节点。升级表结构时务必递增版本号并在 `onUpgrade` 做好无损迁移。
- **SharedPreferences**：`speedtest_settings`，键包括 `retention_days`（默认 90）、`last_node_id`、`traffic_tier`（默认 `STANDARD`）、`theme_mode`（默认 `SYSTEM`）、`dynamic_color`（默认 `true`）。
- **数据导出与备份**：支持带 UTF-8 BOM 的 CSV 导出以及完整 JSON（`app = "shizuku-speedtest"`, `version = 1`）备份与去重恢复。

### 3.4 本地局域网测速
- 电脑端运行根目录 `local_speed_server.py`（默认监听 `0.0.0.0:8899`），提供 `/ping`、`/down?bytes=N`、`/up` 接口（支持 `Transfer-Encoding: chunked` 与客户端超时直接断开）。
- 手机端在「设置 -> 测速节点」添加「本地服务」类型节点（如 `http://192.168.6.100:8899`）。
- `AndroidManifest.xml` 已开启 `android:usesCleartextTraffic="true"` 以兼容 Android 9+ 局域网 HTTP 本地测速服务（注：原 `v1.9.1` APK 清单中未显式声明该属性，重建时已显式补齐以避免局域网 HTTP 被系统拦截）。

### 3.5 图标设计规范（v1.9.1）
- 极简仪表矢量图标：`ic_launcher_foreground.xml`（深蓝 `#2B4663` 弧线 + 指针 + 空心圆环，无刻度，弧线两端为 `round` 圆弧头）+ 纯白背景 `ic_launcher_background.xml`（`#FFFFFF`）。
- 各分辨率 `mipmap-*/ic_launcher*.png` 与 `v1.9.1` APK 保持逐像素一致。

## 4. 版本演进备注
- `v1.9.2`（`versionCode = 12`）：优化下行按时长流式测速与滑动窗口平滑、修复上行 `1/x` 曲线（引入 `TrafficStats` 网络层计数 + 握手后预热 + 中位数去尖峰，保留 v1.8+ 流式上传架构）、移除失效的南京航空航天大学节点并新增 5 个中国大陆节点（DB `v2 → v3` 无损迁移），同时将「关于」卡片版本号改为动态读取 `packageManager` 的 `versionName`。
