# 绘 · 基于莹现有仓库的改造方案

## 现有代码保留
- VPS 上正在运行的机器人、Live2D 服务和后台 API 保留；旧莹宝 Android 源码、APK 已从本分支删除。
- 绘已有的资产、页面骨架、本地配置存储、签名身份，以及 ProxyPolicy 的安全约束。
- 服务端 sing-box 和 Mihomo 订阅继续运行；不把 VPS 服务端二进制当作手机内核。

## 逐项重构
1. 将绘 Android 模块纳入本仓库的 hui-proxy/，分支 hui-proxy 隔离 main 未提交头像改动。
2. 将页面显示改为由实际 CoreManager/VpnService 状态驱动，去除尚未接通的选项或明确标记不可用。
3. 配置文件 parse、normalize、validate、生成内核配置、调用内核 checker 后才允许启动；原子保存最后有效配置。
4. VpnService 单独负责系统授权、TUN/FD 保护、应用分流及网络变化；核心适配器单实例启停、重载、统计和日志。
5. 先验证 Mihomo 单核心实连及 DNS/路由，再接入 sing-box；Xray 在完成两核心闭环后加入。
6. 订阅从本地缓存可用、远程更新原子替换；现有后台只用于账号、设备、节点同步，不参与本地 VPN 启停。
7. 真机针对 Android 10/12/13/14/15/16、OnePlus 与 vivo 验证兼容和渲染，再发布标为可用的绘 APK。

## 发布门槛
0.32 的 VPNService 和内核调用已在开发；必须以真机证明 TUN、真实出口和 DNS 路径可靠，再把 Release 标为可用代理。任何编译成功都不能替代联网实测。
