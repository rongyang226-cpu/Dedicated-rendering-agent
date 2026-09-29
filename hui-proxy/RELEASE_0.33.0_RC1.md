# 绘 0.33.0 · 完整功能 RC1

这一版把绘的 Android 代理端迁移到成熟 NekoBox / sing-box 底座，并完成第一轮完整审计与维护。

- 透明液态玻璃主界面、卡片、选项卡和轻量按压动画
- 2160×3840 默认高清背景；支持用户自定义背景和一键恢复
- 固定高清头像；保留轻量樱花花瓣与环境光动画
- 真实订阅导入、节点管理、VPN、TUN、FakeDNS、DNS 劫持和分应用代理
- 支持 VLESS、VMess、Trojan、Shadowsocks、Hysteria/Hysteria2、TUIC、WireGuard、AnyTLS 等底座能力
- WebView 安全收紧，关闭文件/内容访问和混合内容
- 关闭系统云备份，避免代理数据库被系统备份带走
- 修复 TG/QQ 长期记忆数据库升级字段缺失问题
- 降低 4K 背景解码内存和液态卡片空闲动画开销
- 清理上游推广入口，同时保留 NekoBox 上游项目致谢

当前 RC APK 使用独立调试包名 `com.yingbao.hui.next.debug`，用于安全测试完整功能，可与旧版并存。正式稳定包会使用 `com.yingbao.hui.next` 和固定发布签名。