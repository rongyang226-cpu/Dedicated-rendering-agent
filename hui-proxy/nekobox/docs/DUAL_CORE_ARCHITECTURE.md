# 绘 · 双内核架构

## 目标

绘的 UI、订阅、节点数据库和视觉层保持统一；Box 与 Meta 是可替换、可独立升级的运行时。任何一个内核失败都不应把另一个内核或主 UI 一起拉死。

## 进程边界

| 进程 | 作用 | 原生运行时 |
|---|---|---|
| 主进程 | UI、节点数据库、配置生成 | 不启动新内核 |
| `:bg` | NekoBox 旧兼容辅助层 | legacy `libgojni.so` |
| `:box` | Box / SFA | sing-box `libbox.so` |
| `:meta` | Meta | CMFA `libbridge.so` + Mihomo `libclash.so` |

**硬规则：同一个 Android 进程只能装载一个 Go runtime。** `:box` 禁止引用 `libcore.Libcore`；主进程和 `:bg` 禁止直接调用 `io.nekohasekai.libbox.Libbox`。Meta JNI 只能位于 `bg/meta` 内。

## 代码目录

- `bg/core/`：公共接口、状态模型、内核选择器。UI 只应该依赖这里。
- `bg/box/`：SFA 1.14.1 的 Android 平台桥、VPN 服务、状态和配置边界。
- `bg/meta/`：CMFA/Mihomo 的 JNI 桥、VPN 服务、状态和配置边界。
- `go/Seq.java`：按进程选择 `gojni` 或 `box` 的唯一 gomobile Java runtime。
- `app/libs/libcore.aar`：旧配置转换兼容层，不再代表新版 Box 运行时。
- `app/libs/libbox-sfa-1.14.1.aar`：新版 Box 运行时。
- `app/src/main/jniLibs/arm64-v8a/`：Meta 原生库。

## UI 约束

现有节点页、分组、路由、设置、工具、日志和液态玻璃布局不因内核不同而复制页面。公共 UI 通过 `CoreController` 获取状态、启动、停止。内核专属功能放入现有页面的对应功能区。

## Box 升级流程

1. 更新 `SagerNet/sing-box` 稳定版源码与 SFA 参考源码。
2. 使用目标版本要求的 Go/NDK 构建 arm64 `libbox.aar`。
3. 检查 gomobile `go.Seq` 变化；不要把新 AAR 自带 `go/*` 与旧 libcore 重复打包。
4. 更新 `BoxPlatform` / `BoxVpnService` 的 API 适配。
5. 更新 `core-versions.properties` 和 `CoreBuildInfo`。
6. 执行 `scripts/verify-dual-core.sh` 与完整 APK 构建。

## Meta 升级流程

1. 以最新稳定 CMFA 对应的 Mihomo 提交为基准。
2. 更新 ARM64 `libclash.so` 与 `libbridge.so`，桥 Java 接口有变化时同步 `com/github/kr328`。
3. 只在 `bg/meta/` 中调用 JNI。
4. 更新版本清单、边界检查并完整构建。

## 发布前必须验证

- Box/Meta 切换时先释放旧 TUN。
- 两个内核不能同时 RUNNING。
- VPN 授权拒绝不会崩溃。
- 配置错误只显示错误，不终止 UI。
- 断网/换 Wi-Fi/移动网络后能恢复。
- 实时速度、累计流量、延迟测试可用。
- 导入超大配置、损坏 YAML/JSON 不造成 OOM。
- 崩溃日志不自动弹分享页，不写入订阅 Token/密码。
- `assembleOssDebug`、资源解析、Manifest、原生库架构和签名检查全部通过。
