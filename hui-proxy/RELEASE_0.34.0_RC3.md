# 绘 0.34.0 RC3 · 颜色资源崩溃热修

RC3 针对 RC2 启动保护捕获到的真实异常进行最小修复。

## 修复
- 修复 `getColorAttr()` 在主题属性最终解析为直接颜色值时错误读取 `resourceId=0`，导致 `Resources$NotFoundException: Resource ID #0x0`。
- 颜色属性现在正确支持：资源颜色、直接 ARGB 色值、属性链和安全回退。
- 该修复同时覆盖节点协议颜色、通知颜色、设置页等所有共用 `getColorAttr()` 的调用。
- 保留 RC2 启动保护页；若仍有新的启动异常，会继续给出真实异常栈。

## 双内核
- Box / sing-box 1.14.1 保持不变。
- Meta / Mihomo 1.19.31 保持不变。
- 布局和双内核进程隔离保持不变。

仍为 ARM64 / Android 7.0+ / debug 签名测试版。
