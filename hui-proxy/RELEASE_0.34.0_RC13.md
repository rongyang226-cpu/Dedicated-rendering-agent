# 绘 0.34.0 RC13 · 内核自更新与玻璃修复

RC13 继续处理 RC12 真机反馈，重点把内核维护、Box 启动链和界面裁切一起收口。

## 最新稳定内核
- Box 内置核心升级到官方 sing-box 1.14.2（arm64-v8a）。
- Meta 保持官方最新 Mihomo 1.19.31 / ClashMetaForAndroid 2.11.34；已核对项目内 `libclash.so` 与 `libbridge.so` 和官方 arm64 发布包 SHA-256 一致。
- Box 与 Meta 继续运行在独立 Android 进程，避免两个 Go/JNI 运行时互相污染。

## 软件内核自主更新
- 设置新增“自动更新内核”和“检查内核更新”。
- 默认每天检查一次官方稳定发布；自动下载只在非计费/Wi-Fi 网络进行，手动检查不受此限制。
- 更新包直接取自 SagerNet/sing-box 和 MetaCubeX/ClashMetaForAndroid 官方 Release。
- 下载后校验 GitHub 提供的 SHA-256 digest、文件大小和 ELF 头，再原子安装到应用私有目录。
- 更新核心加载失败会自动撤销 active manifest 并回退 APK 内置核心，避免更新后反复崩溃。
- Box 1.14.x / CMFA 2.11.x 可独立热更新；跨 JNI 接口大版本时会提示“需要更新绘的绑定层”，不会冒险加载不兼容核心。

## Box 启动与 DNS
- DNS 迁移现在同时处理字符串 server、旧 `address` 对象和已经 typed 但残留旧字段的 server。
- 在交给 libbox 前增加硬校验，禁止任何 `dns.servers[]` 继续携带旧 `address` 字段。
- Box 运行状态增加独立心跳，即使流量回调暂时没有变化，也不会再被前台误判为“Box 状态已失联”。
- 状态失联窗口也适当放宽，减少进程调度造成的误报。

## Meta
- 对照官方 CMFA 2.11.34 APK 核查 Bridge JNI 方法签名，当前 `nativeStartTun` / `nativeLoad` / traffic / provider 等接口与官方保持一致。
- Meta 更新使用 `libclash.so + libbridge.so` 成对切换，失败时整组回退，避免桥接库和内核版本错配。

## 底栏、圆角与玻璃
- 主容器、底栏和分页容器关闭子 View 裁切，并给底栏增加真实的动画余量；选中气泡抬升/放大时不再缺边。
- 仪表盘和节点卡片启用真实 outline 裁切，圆角加大，不再出现“里面圆、外层还是方”的效果。
- Drawer 从偏灰乳白玻璃改为与主界面同一套中性透明玻璃；选中项去掉粉色/异色块。
- 底栏选中动画改为轻微抬升与缩放，取消明显的发灰和廉价按钮感。
- 节点分组栏改成更紧凑的横向玻璃标签，去掉整块大白底，减轻 YY CLOUD 一类长名称的挤压感。

## 安全边界
- 内核下载仅接受官方 GitHub Release 的 arm64-v8a 稳定包。
- 不把远程下载的核心覆盖 APK 本体，始终保留可回退的内置版本。
