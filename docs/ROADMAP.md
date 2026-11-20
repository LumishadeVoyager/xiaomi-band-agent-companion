# 并行工作线与进度

## 2026-11-18：当前开发方向

用户暂停语音 Agent，转向独立 PowerProfile 手环监看。当前工作见 [#23](https://github.com/LumishadeVoyager/xiaomi-band-agent-companion/issues/23) 与 [规格/验收](./POWERPROFILE.md)。阶段一为只读数据适配、原生风格 RPK、手机同步和 Codex 完成通知；阶段二为真机显示、手机锁屏、通知中心震动验收。历史语音/双后端/OTA 未完成项保留历史状态，不作为新监看首版前提，也不宣称已完成。

同日新增表盘：采用“基础原生表盘 + 详情应用 + 系统通知”，表盘必须有时间、日期、手环电量；其他统计可不在表盘显示。[路线与 G0–G6 验收](./POWERPROFILE_WATCHFACE.md) 已补充，当前仅设计预览，尚无 PowerProfile 原生表盘安装包。

同日真机进展：应用 versionCode 8 已有真实快照回执；用户确认数据、Token、双窗口额度及滚动排版正常。停止 AstroBox、手机短时息屏后仍收到新快照。手机 versionCode 9 的测试通知已由用户确认送达手环并震动。真实 Codex 完成整条链路、长时后台、重连和原生表盘分别继续验收，详见 [真机验收记录](./POWERPROFILE_DEVICE_ACCEPTANCE.md)。

同日新增互联网要求：电脑→手机走互联网，手机→手环允许蓝牙。默认 workers.dev 超时后改用免费 Pages Function + 内部服务绑定/SQLite Durable Object。手机 v11 关闭 Wi-Fi，使用移动数据并实际拔除 USB 后仍收到手环 v8 新回执；用户确认数据显示正常，23:33:16 确认诊断通知重试送达且震动。两端均使用新 HTTPS 入口，无自购域名。真实 Codex 完成链路、长时运行及重连另验。见 [中继部署与限制](../cloud-relay/README.md) 与 [免费入口](../pages-relay/README.md)。

以下为方向变更前的历史工作线。

当前进入开发探针阶段；尚无完整 Agent 客户端。GitHub Issues/PR 是逐项进度来源。

任务索引：[#1 公开开发基线](https://github.com/LumishadeVoyager/xiaomi-band-agent-companion/issues/1)、[#2 音频与转写](https://github.com/LumishadeVoyager/xiaomi-band-agent-companion/issues/2)、[#3 双 RPK 身份](https://github.com/LumishadeVoyager/xiaomi-band-agent-companion/issues/3)、[#4 Codex](https://github.com/LumishadeVoyager/xiaomi-band-agent-companion/issues/4)、[#5 Harness](https://github.com/LumishadeVoyager/xiaomi-band-agent-companion/issues/5)、[#6 OTA](https://github.com/LumishadeVoyager/xiaomi-band-agent-companion/issues/6)、[#7 Claude Code](https://github.com/LumishadeVoyager/xiaomi-band-agent-companion/issues/7)、[#8 手机飞书](https://github.com/LumishadeVoyager/xiaomi-band-agent-companion/issues/8)、[#9 多型号](https://github.com/LumishadeVoyager/xiaomi-band-agent-companion/issues/9)、[#10 共享协议](https://github.com/LumishadeVoyager/xiaomi-band-agent-companion/issues/10)、[#11 额度与偏好](https://github.com/LumishadeVoyager/xiaomi-band-agent-companion/issues/11)。

| 工作线 | 优先级 | 当前状态 | 交付门槛 |
| --- | --- | --- | --- |
| 共享音频与转写 | P0 | 共享协议/接收器/本地转写已实现；系统录音取音验证通过；第三方 record 不可用 | 手环音源、完整传输、确认前不执行 |
| Codex 适配 | P0 | 协议线索已查，未调用验证 | 原会话语音续聊和结果回传 |
| DeepSeek Harness 适配 | P0 | 官方项目已查，环境由其他会话安装 | 不依赖 Codex 的原会话完整闭环 |
| 独立双应用与身份 | P0 | 两个 RPK/APK 同证书构建；Codex 安装通信已测，双应用共存待验收 | 两个 RPK 共存、不串会话、自有签名桥接 |
| GitHub 与发布基础 | P0 | 本轮建立 | Issue/PR/CI/Release 可追踪 |
| 三端 OTA | P0 | 需求已定义，未实现 | N→N+1 实测及错误/中断/版本兼容处理 |
| Claude Code | P1 | 待实现 | 独立入口及原会话控制 |
| 安卓飞书多角色入口 | P1 | 需求已确认，App 发送/回复方式待验证 | 仅手机飞书 App，角色/会话隔离，不直连公司电脑/Bridge |
| 额度与个人偏好 | P1 | 上游方案已查，未实现 | 真数据、快捷指令、通知和设置 |
| 多型号设备 | P2 | 候选矩阵建立 | 每型号/地区/固件分别验收 |

## 里程碑

- M0 核心可行性：音频出口、转写、双后端旧会话、双 RPK 包身份与 OTA 通路。
- M1 P0 语音版本：Codex/Harness 两应用完整闭环、可靠性与 OTA 验收。
- M2 P1 扩展：Claude Code、安卓飞书 App 多角色消息、额度与偏好。
- M3 P2 设备：9 Pro/10/11 等候选按能力适配。

并行指接口、设备、后端与更新工作可各自推进；不代表所有 P2 功能必须与 P0 同期发布，也不把未完成的 P0 留给后续版本后仍称首版完成。
