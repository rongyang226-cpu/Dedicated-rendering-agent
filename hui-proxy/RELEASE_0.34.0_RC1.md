# 绘 0.34.0 RC1 · 双内核

这是“绘”的首个双内核测试版。现有液态玻璃布局、节点页与整体视觉保持不变，不照搬 CMFA/SFA 的界面。

## 核心
- Box：sing-box 1.14.1，独立 `:box` VPN 进程。
- Meta：Mihomo 1.19.31，参考 ClashMetaForAndroid 2.11.34 的 JNI/Android 运行架构，独立 `:meta` VPN 进程。
- 控制入口：独立 `:control` 进程；磁贴、快捷方式、开机自启统一走 `CoreController`。
- 旧 `:bg` 仅保留兼容能力，不再随首页 `BIND_AUTO_CREATE` 自动启动。

## 本轮重点
- Box/Meta 同时只允许一个 VPN/TUN；切核前统一停止旧内核。
- Box 配置迁移到 sing-box 1.14 TUN 字段，支持 LAN 绕过与分应用代理。
- Meta 支持 YAML 文件/URL 导入，补齐 UID/PROCESS-NAME、底层网络、DNS、时区与应用列表动态同步。
- Wi-Fi/蜂窝切换只跟踪 `NOT_VPN` 底层网络，避免 VPN 自回环。
- 首页、磁贴、快捷方式、设置重载、备份恢复、订阅更新统一识别当前内核状态。
- 保留 RC4 崩溃安全处理：致命异常只保留本地诊断，不再循环弹系统分享页。
- 新内核进程不主动加载旧 Go/Libcore 桥，降低串核风险。

## 已知限制
- ARM64 / Android 7.0+。
- 当前仍为 debug 包名与 debug 签名，仅用于 RC 真机测试。
- Meta 会完整运行导入 YAML 中的策略组、Provider 与规则，但高级管理能力尚未全部映射到“绘”的现有 UI；后续 RC 继续接入，布局保持不变。
- 为兼容旧节点转换/插件链仍保留 legacy `libgojni`，所以 APK 较大；后续逐步移除兼容依赖。
- VPN/TUN、Wi-Fi↔蜂窝切换和不同厂商系统行为仍需真机回归。
