# ShizukuSpeedTest (Shizuku测速)

一个基于 Kotlin + Jetpack Compose + Material Design 3 开发的 Android 本地网络测速工具，所有测速历史仅保存在本机 SQLite 数据库中，不上传云端。

> 详细架构约束、签名配置与演进说明请参阅 [MIGRATION.md](./MIGRATION.md)。

## 核心功能

- **实时测速**：测量下载速度（按时长多路流式下载 + 预热剔除 + 滑动窗口）、上传速度（v1.8+ 按时长流式上传 + 网络层字节统计 + 稳态均值）、延迟、抖动、丢包率、路由器网关延迟，以及 Wi-Fi 信号（dBm）/ 频段 / 协商速率；测速过程展示实时仪表盘与折线曲线，测完展示上下行对比曲线。
- **多协议节点管理**：内置上海大学、南京大学、同济大学、江苏大学（电信）、云南师范大学、香港 BGP、东京 BGP 与 Cloudflare（国际）；支持添加 / 编辑 / 删除自定义节点（Cloudflare 兼容 / LibreSpeed / 本地服务）、节点连通性探测，以及为每个网络单独记忆默认测速节点和别名。
- **局域网本地测速**：在电脑上运行 [`local_speed_server.py`](./local_speed_server.py)，手机端添加 `http://<电脑局域网IP>:8899` 的「本地服务」节点，即可测量手机 Wi-Fi 到局域网/路由器的真实吞吐。
- **多网络历史对比**：同一时间轴折线对比多网络历史表现，支持切换下载 / 上传 / 延迟 / 抖动指标、点击图例隐藏/显示曲线，并展示平均 / 最高 / 最低统计。
- **数据管理与主题**：本地 SQLite 存储（保存时长可配置或自定义天数），支持带 UTF-8 BOM 的 CSV 导出和 JSON 全量备份 / 去重恢复；支持跟随系统 / 白天 / 黑夜模式与 Android 12+ 动态取色。

## 构建说明

- **环境要求**：JDK 17、Android SDK 34（`minSdk 24` / `targetSdk 34`）
- **Debug 构建**：
  ```bash
  ./gradlew assembleDebug
  ```
- **Release 构建**（需通过环境变量注入签名信息，keystore 严禁提交至仓库）：
  ```bash
  export SHIZUKU_RELEASE_STORE_FILE=/path/to/release.jks
  export SHIZUKU_RELEASE_STORE_PASSWORD=...
  export SHIZUKU_RELEASE_KEY_ALIAS=...
  export SHIZUKU_RELEASE_KEY_PASSWORD=...
  ./gradlew assembleRelease
  ```

## 本地测速服务

```bash
python3 local_speed_server.py --port 8899
```

启动后在手机 App「设置 -> 测速节点」添加「本地服务」节点，地址填写脚本输出的局域网 URL（如 `http://192.168.6.100:8899`）。

## 发布产物

- 安装包与大体积产物托管在 **GitHub Releases**（仓库 `.gitignore` 默认排除 `*.apk` 及构建缓存）。
- **v1.9.2**：`ShizukuSpeedTest-v1.9.2.apk`（SHA-256: `b2e36c2ecddad2ca6ab45df602852dd5b621b0a055eeaa03b5a1dc99298af36b`）
- **v1.9.1**：`ShizukuSpeedTest-v1.9.1.apk`（SHA-256: `509cbdedd4a529e66f63136c1f1dbf280db5d0aad1463e729654dc9992178fec`）
