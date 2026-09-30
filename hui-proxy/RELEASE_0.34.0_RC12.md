# 绘 0.34.0 RC12 · 稳定切页与代理修复

RC12 针对 RC11 真机反馈继续修复，不再只调整观感。

## 设置页崩溃
- 修复 SettingsPreferenceFragment 错用父 FragmentManager 导致的 `No view found for id/settings`。
- 设置页现在由 childFragmentManager 管理，手动切换 Box / Meta 后重建页面不再撞掉 ViewPager2。

## 配置编辑页
- 二级配置编辑页打开时完全隐藏主分页和底栏，不再能点到上一页。
- 顶栏加入明确关闭按钮；存在未保存修改时先确认。
- 配置操作按钮缩短为“保存 / 应用 / 更新”，避免窄屏换行和文字重叠。
- 二级页返回主页面时恢复原来的分页位置。

## 页面切换与开屏
- 主分页回到 ViewPager2 原生平移，不再做缩放/透明度叠加。
- 预加载相邻两页，减轻左右滑动时的临时构建卡顿。
- 二级页和主分页彻底隔离触摸。
- 开屏背景与主界面使用相同缩放，缩短转场并去掉突兀的大幅缩放。

## 玻璃与焦点框
- 设置卡片降低乳白填充和硬白边，移除持续流动高光。
- 普通 Preference 的默认态不再额外叠一层按钮底。
- 全局抑制触摸场景的系统焦点高亮，节点卡片 ripple 也进一步减弱。

## Box / sing-box 1.14 兼容
- 不只迁移 FakeIP：旧 DNS server `address` 格式会转换为 typed local/udp/tcp/tls/https/quic/h3/fakeip。
- 删除已废弃的 outbound DNS rule matcher，改用 route.default_domain_resolver。
- `rcode://success` 规则改为现代 predefined NOERROR 动作。
- 旧 inbound sniff/domain_strategy/udp_disable_domain_unmapping/detour 迁移为 route actions。
- outbound `domain_strategy` 迁移为 `domain_resolver`。
- 继续迁移旧 TUN address/route 字段并移除 endpoint_independent_nat/gso。
- 使用 sing-box 1.14.2 对代表性旧配置迁移结果执行 `check`，已通过。

## 节点稳定性
- 延续 RC11 的 stable-id、payload 局部刷新和禁止后台延迟排序。
- 自动测速频率与界面说明统一为 5 分钟，不再显示错误的 60 秒。
