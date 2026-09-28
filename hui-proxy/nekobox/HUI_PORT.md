# 绘 · NekoBox 底座改造（进行中）

本目录从 [MatsuriDayo/NekoBoxForAndroid](https://github.com/MatsuriDayo/NekoBoxForAndroid) 的 `5768494` 导入，在现有绘项目的 `hui-nekobox` 分支内修改。

- 保留上游 GPL-3.0-or-later 许可证与原作者署名。发布 APK 时应同时提供相应源码，包括 `sing-box`、`libneko` 的固定版本以及本项目改动。
- 目前底座使用 sing-box；NekoBox 订阅解析支持常用分享链接、ClashMeta 和 v2rayN 等格式，但只导入节点，不保留远程分流规则。
- 当前视觉改动是第一轮：名称、头像、背景、列表卡片和工具栏的半透明基础样式。动态玻璃模糊、设置页统一视觉、自定义背景和旧版配置迁移尚未完成。
- 为防止预览版覆盖原有 `com.yingbao.app` 数据，本分支暂用独立包名。正式替换前要实现数据迁移并完成 Android 真机连通性、DNS、网络切换和 UI 性能核验。

不要把此目录中尚未构建验证的代码作为正式版 APK 发布。
