# 绘 0.34.0 RC2 · 启动热修

RC2 不继续增加双内核功能，专门处理 RC1 的启动秒退。

## 启动修复
- 将 Application 的早期启动路径恢复到 RC3 的保守顺序；Go runtime 选择延后到 Application 完整 attach 之后。
- 默认背景资源回退逻辑恢复 RC3 方式，减少启动期 AppCompat 资源链变量。
- 新增独立 BootstrapActivity：正常情况直接进入主界面；如果上一次 Java 启动异常，则停在“启动保护”页显示保存的异常栈，不再无限秒退。
- 启动保护支持复制异常信息和手动重试。

## 双内核
- 保留 RC1 的 Box / sing-box 1.14.1。
- 保留 RC1 的 Meta / Mihomo 1.19.31。
- 保留 :control / :box / :meta / :bg 进程隔离和统一 CoreController。

## 测试重点
1. 覆盖安装 RC2 后直接启动。
2. 如果出现“绘 · 启动保护”，复制异常信息或截图发回。
3. 如果能正常进入，再测试 Box、Meta 和切核。

仍为 ARM64 / Android 7.0+ / debug 包名与 debug 签名的 RC 测试版。
