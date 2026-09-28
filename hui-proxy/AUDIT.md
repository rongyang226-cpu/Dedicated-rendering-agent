# 绘代理现状审查（2026-09-28）

范围：现有 GitHub 仓库 `yingbao` 的 Android 应用、`hui-proxy` 分支预览模块，以及 VPS 运行代码 `/opt/ying/app/live2d/`。以下是开始改造时的基线问题；末尾注明本轮已处理部分。此报告基于源码、构建包和服务端检查，尚无 Android 真机与出口测试。

## 项目结构与真实可用能力

- `android/src/com/yingbao/app/`：原有莹宝桌宠应用，包括 MainActivity、OverlayService、AnimatedCharacterView；属于独立包，不是代理客户端。
- `hui-proxy/android/`：绘的 Android WebView 界面、头像壁纸、本地配置草稿；APK 可以安装与导入文件。
- `hui-proxy/core/src/com/yingproxy/policy/`：ProxyPolicy 安全约束和 CoreAdapter 接口，没有移动端核心实现。
- `/opt/ying/app/live2d/proxy_config.py`：服务端为设备生成 Mihomo YAML/节点 URI；`proxy_manager.py` 管理设备。
- `/opt/ying/app/live2d/admin_panel.py`：已授权的管理接口及带随机令牌的订阅地址；VPS sing-box 服务运行，不能代表手机已接入代理内核。

## 严重问题

| 位置 / 函数 | 触发条件 | 影响 | 修复 |
| --- | --- | --- | --- |
| `hui-proxy/android/AndroidManifest.xml`; `MainActivity.java` | 点击连接或尝试启动 VPN | 未声明 VpnService、无 VPN 授权、无 TUN 处理；手机无法代理 | 引入真实前台 VpnService，准备授权、建立 TUN、保护核心 socket、处理撤销和恢复；实机验证后开放连接。 |
| `hui-proxy/core/src/com/yingproxy/policy/CoreAdapter.java` | 选 sing-box/Mihomo | 只有接口，没有移动端本地核心或配置检查；所有核心选项不能工作 | 基于可验证来源的内核构建和适配器实现，能力驱动设置；仅当核心自检和启动回报成功才显示连接。 |
| `hui-proxy/android/src/com/yingproxy/app/MainActivity.java` 的 `Bridge.setOption`、`state`; `android/assets/index.html` | 更改 DNS、路由、UDP、内核 | 偏好设置与底层没有闭环，若启用开关会造成错误安全感 | 在接通配置生成、核心、TUN、运行回执前保持禁用，运行状态由内核与 VPN 提供。 |
| `MainActivity.java:importSelected`、`ProfileInspector.java` | 导入 JSON/YAML 配置 | 目前仅 JSON 语法初检，YAML 仍待解析，未运行核心配置检查；配置不可启动 | 节点和订阅格式分层解析、字段与引用验证、最终配置生成、调用对应内核 checker，失败阻止 VPN。 |
## 中等问题

| 位置 / 函数 | 触发条件 | 影响 | 修复 |
| --- | --- | --- | --- |
| `/opt/ying/app/live2d/proxy_config.py:flclash` 与绘导入逻辑 | 用户导入后台生成的 YAML | 后台输出 Mihomo YAML，Android 目前只能当草稿，无法同步离线配置 | 复用现有设备授权接口，加入本地订阅缓存、YAML 解析、原子更新及断网保留最后有效配置。 |
| `/opt/ying/app/live2d/admin_panel.py:_proxy_connections` | sing-box API 断开或响应异常 | 返回零会被误认为真实的 0 条连接 | 返回 unknown/错误态并记录受控日志，不把监控故障显示成零流量。 |
| `hui-proxy/android/assets/index.html` 的固定背景、重复玻璃与花瓣动画 | 低端手机滑动、WebView 失去图层或系统导航栏变色 | 可能出现掉帧或底部黑块；未经目标设备验证 | Window/WebView/根背景保持同一不透明底层；限制模糊层，测滚动、旋转、导航栏和低端机。 |
| `hui-proxy/android/src/com/yingproxy/app/MainActivity.java:onActivityResult`（原基线） | 导入大文件、异常或重复导入 | 原实现在主线程读文件，没检查内容、直接标为 active；异常可能只提示而留下错误状态 | 已改为工作线程、限长、语法检查和临时文件保存；仍需 YAML 解析和核心 checker。 |
| `hui-proxy/android/AndroidManifest.xml` 与网络模块缺失 | Wi-Fi/移动数据切换、系统停止 VPN、锁屏后台 | 无 ConnectivityManager 监控、重绑、通知或恢复策略 | 在真实 VpnService 生命周期中监测默认网络变化，串行重连并记录原因，实机回归。 |
| `hui-proxy/android/assets/index.html` 的节点、流量与测速占位 | 用户查看节点或统计 | 没有订阅解析、真实测速、核心流量和连接事件 | 先实现导入与内核连接；仅展示核心真实回执、失败类别和时间戳。 |

## 轻微问题与冗余

- `hui-proxy/android/assets/index.html` 中预设了 Xray 和多种高级分流文案，但没有能力协商；已禁用，后续应按当前核心能力生成选项，不承诺未来必定支持。
- `hui-proxy/README.md` 原文字把规划中的三核心、DNS 和规则写得像现成能力；已改为明确区分当前功能与计划。
- `.github/workflows/release-apk.yml` 只打包原莹宝应用及固定版本；绘预览包目前由模块脚本手工构建签名。可在核心与验收成熟后增加独立发布工作流，保留原应用发布流程。

## 模块改造与验证边界

本轮仅修复配置草稿导入并构建签名 v0.1.1：编译通过，APK v2/v3 签名校验通过，GitHub 下载链接返回 200；未完成真机安装、VPN 授权、实际出口、DNS/IPv6/UDP 和网络切换测试。连接入口保持禁用。下个独立模块先实现解析/内核校验，再做 VPN/TUN；每次完成后构建并执行对应实际功能测试。
