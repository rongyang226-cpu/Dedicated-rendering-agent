# 绘的内核与省电设计（2026-09-28）

资料核对：Fluxora 仓库 `Tiam9173/fluxora` 的 README、`lib/state.dart`、Android `FluxoraVpnService.kt`；SFA、CMFA、Hiddify、v2rayNG 官方仓库与 Android VPN 文档。仅参考职责划分和使用逻辑，不移植 UI 或代码。Fluxora 代码为 GPL-3.0，若未来实际引入其代码，必须另行履行许可证义务。

| 参考 | 可吸收的设计 | 绘的决定 |
| --- | --- | --- |
| Fluxora（Mihomo + Flutter） | 生成最终配置时显式处理 Android TUN 路由、VPN 服务独立于界面；熄屏时不反复刷新速度通知 | 后台已有 Mihomo YAML，先做 Mihomo 的导入与内核检查；由本机 VPN 服务持有 TUN；UI 关闭后不运行绘图和轮询 |
| SFA（sing-box） | Android VPN 生命周期和核心适配分开，保护内核套接字 | 保留第二内核接入点，完成单核心实连后再接 sing-box |
| CMFA（Mihomo） | 代理组、订阅、规则与实际 Mihomo 运行状态闭环 | 节点状态从内核取，订阅先缓存后验证，更新失败保留最后有效版 |
| Hiddify | 把连接与配置导入做得容易找到 | 首页一键连接，复杂 DNS/分流选项折叠 |
| v2rayNG | 应用分流接 Android VPN、错误与后台服务分离 | 应用选择映射 VPNService allowed/disallowedApplications，不放只改 UI 的开关 |

## P0 执行顺序

1. 解析后台实际生成的 YAML 与常见 JSON，校验 DNS、路由、代理组引用，再调用目标核心自己的配置检查。
2. Android VpnService 获取权限、建立 IPv4/IPv6 路由与 DNS、保护内核 socket、前台通知与撤销处理。
3. 核心确认启动后才显示“已连接”；网络切换按事件重绑，失败状态不改成“直连成功”。
4. 真机检查 Wi-Fi/移动网络切换、出口、DNS、IPv6、UDP 与应用分流；之后发布可代理版本。

## 省电与界面验收

- 同一时刻最多运行一个内核；界面退出不保留 WebView 绘制，VPN 服务独立常驻。
- 速度与连接数只在界面可见时按需刷新；熄屏不更新速度通知；订阅和批量测速不按秒轮询。
- 花瓣在页面隐藏时暂停，系统选择减少动画时关闭；统一窗口、WebView、页面底色，低性能模式降低模糊。
- 省电效果需用 Android 电池统计与长时间联网测试核验，不从代码结构推断具体耗电百分比。
