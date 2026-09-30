# 绘

「绘」是基于 **FlClash** 成熟客户端改造的 Android 代理客户端。

核心路线固定：

- FlClash 作为客户端底座
- Mihomo / Clash.Meta 作为唯一代理内核
- 保留成熟的订阅、节点、规则、日志与 VPN 能力
- 在稳定功能上重新设计「绘」的界面与交互

## 📦 直接下载 APK

[**下载「绘」0.35.0 RC3（arm64）**](https://github.com/rongyang226-cpu/yingbao/releases/download/v0.35.0-rc3/Hui-0.35.0-arm64-release.apk)

当前为真机测试版，仅提供 Android arm64 构建。

SHA256：

`2831434a37405a1a7668722d6ec25c0a99f1f3147b49d3cbe249b86890d16529`

## 当前版本

- 版本：0.35.0 RC3
- 包名：`com.yingbao.hui.flclash`
- 最低 Android：API 24
- Target SDK：36
- 架构：arm64-v8a

## 已保留的 FlClash 能力

- URL / 剪贴板导入订阅
- 节点搜索、延迟测试与分组切换
- 规则 / 全局 / 直连模式
- Fake-IP / DNS 配置
- 应用分流与访问控制
- 日志查看、筛选、导出
- Mihomo 原生核心与 VPN 服务

## 「绘」界面

- 绘梨衣风格背景
- 自定义背景
- 透明液态玻璃卡片与导航
- 可调背景亮度、遮罩、模糊和玻璃强度
- 樱花粒子动画，可关闭并调节密度
- 保留连接暂停、恢复、运行时间等成熟状态逻辑

## 旧莹宝 Android 客户端

旧「莹宝」Android 客户端已停止继续开发，不再作为仓库首页与下载入口。

历史源码保留在 `legacy-yingbao-app` 分支，便于需要时回看；VPS、Telegram 与 AI 后端项目不受这次迁移影响。
