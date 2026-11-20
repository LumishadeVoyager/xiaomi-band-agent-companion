# PowerProfile 手环监看

2026-11-19 更新：电脑端已集成到 PowerProfile v1.10.0，随托盘启动并自动上传；现有手机/手环已确认刷新恢复。手机/手环公网 OTA 的引导、发布和独立验收见 [OTA 说明](./POWERPROFILE_OTA.md)。下文原独立 Node 启动命令保留作开发备用，不再是本机日常启动要求。

2026-11-18 用户新需求优先于历史语音规格。关联 [Issue #23](https://github.com/LumishadeVoyager/xiaomi-band-agent-companion/issues/23)。

后续新增表盘要求：保留本机时间、日期、手环自身电量，其他统计可留详情应用；最新分层路线与真实交付边界见 [表盘与应用路线](./POWERPROFILE_WATCHFACE.md)。现有 RPK 为应用，不是表盘。

2026-11-18 真机进展：手环版本码 8 的统计/双窗口额度与滚动排版已确认；手机版本码 11 的移动数据同步、拔除 USB 后刷新，以及诊断通知重试送达/震动通过。真实 Codex 完成整条通知链路仍待验证；详细证据、历史版本范围和未通过项见 [真机验收记录](./POWERPROFILE_DEVICE_ACCEPTANCE.md)。

## 目标与难度

独立 PowerProfile RPK（`io.github.lumishadevoyager.bandagent.powerprofile`），不覆盖历史 Codex/Harness。只读监看，不录音、不续聊、不执行电脑命令。整体中等难度；电脑统计和页面较低，手机后台连接/签名身份中等，稳定的系统通知和锁屏重连需要真机验证。手机仍是必要的数据与通知桥接，但不再处理音频、转写或 Agent 控制。

页面黑底、圆角深灰卡片、按项目配色、大数字、纵向滚动，使用 Vela 的 list/progress，贴近系统视觉习惯；非调用私有原生系统页面，显示效果以 10 Pro 真机为准。

## 数据与语义

| 页面 | 来源与含义 |
| --- | --- |
| 今日电脑用时 | 同 PowerProfile Merge，取 `usage.logs.history.json` 与 energy.history 的当天 Seconds 较大值，不叠加重叠覆盖；有效采样覆盖不等于完整开机时长 |
| 今日估算用电 | `energy.history.json` 的当天 KWh，功率积分估算，断线不补值 |
| 今日游戏 | 同 PowerProfile Merge，取两个账本有效 GameSeconds 较大值；未知不显示为 0 |
| 今日 AI Token | `ai.snapshot.json` 的 combinedDaily（已有 day-client-max 合并），不叠加原始来源，不代表订阅额度百分比 |
| Codex 额度 | 独立只读 app-server `account/rateLimits/read`，按真实 300/10080 分钟窗口匹配，显示剩余百分比/北京时间重置时间；缺失显示未知 |

日期按 Asia/Shanghai。快照携带每指标来源/观察时间；超过 3 分钟或跨过重置点的额度显示上次数据。文件损坏、缺失不猜测数字。当前使用文件更新时间近似账本更新时间，尚未读取 PowerProfile 进程内的最新未落盘样本，可能短暂滞后。PowerProfile 本体无需修改或重启。

## 链路与预览运行

日常目标为：电脑主动 HTTPS 上传 → Cloudflare Worker + SQLite Durable Object → 安卓伴侣主动 HTTPS 轮询 → 小米运动健康/interconnect 蓝牙 → 手环。电脑与手机不需要同一 Wi-Fi、USB 或电脑蓝牙；手机到手环继续使用蓝牙。AstroBox 只用于安装与调试。

互联网中继已部署。默认 workers.dev 在手机上超时后，增加 [Cloudflare Pages Function 入口](../pages-relay/README.md)，通过内部服务绑定调用原有 Worker，手机不再访问 workers.dev。无需购买域名，也未升级付费计划。

2026-11-18 手机 versionCode 11、手环 versionCode 8 的移动数据同步通过：Wi-Fi 关闭、移动数据开启、无 ADB reverse，公网链路收到 `transport: internet` 的手环快照应用回执，用户确认刷新后数据显示正常。随后实际拔线、ADB 无连接，仍收到新回执。电脑与手机均使用 Pages HTTPS 入口；长时后台、重连和真实完成提醒仍分别验收。配置与限制见 [互联网中继](../cloud-relay/README.md)。未注册电脑开机服务。

保留局域网备用链路及原有 TLS 证书钉扎，端口独立为 8791。互联网配对使用现有信任根签发独立用途邀请、PC/手机分权令牌，以及 Android 系统 CA/域名校验；不关闭证书验证。

```powershell
npm test
npm run build:powerprofile
# 需要 Node 24 和已初始化的本地电脑 TLS/配对身份
$env:POWERPROFILE_ROOT = 'E:/PowerProfile'
npm run dev:powerprofile
# HOST 为手机可达的电脑局域网地址；邀请仅保存于 .local，不公开
node scripts/create-computer-pairing.cjs https://HOST:8791 powerprofile
```

安卓使用现有官方 SDK 和工具链构建脚本加 `--powerprofile`，其余 `--jdk/--platform/--build-tools/--wearable-aar` 参数同历史探针。产物在 `.local/artifacts`。私有签名不提交，不写 OTA feed。新入口还未加入发布身份/兼容矩阵与签名 OTA 流程，界面禁用其自动更新。

首次安装 APK/RPK 后，在手机完成小米通信授权和签名配对（沿用 `signed_pairing_b64` 本地安装流程），允许通知与后台运行，并在小米运动健康的 App 通知中启用 PowerProfile 伴侣。AstroBox 仅首次侧载/调试。手机服务每 15 秒请求统计，手环打开/点击刷新主动请求；额度电脑每分钟读取。手环缓存上次快照，离线标注缓存。电池优化/后台限制与断线恢复需实机验证。

互联网模式电脑每 10 秒上传，手机每次请求结束后间隔 15 秒重试，并非即时推送。中继仅存最新白名单统计、最多 24 小时的完成事件 ID/时间、通知 ACK 与最新设备回执，不上传会话标题、提示词、回复、原始日志或音频。Cloudflare 能处理这些统计；当前为 TLS 传输保护，非端到端加密。手机断网保留本地快照，电脑心跳超过 90 秒标记离线；额度保留原观察时间。

## 完成提醒

只读本机 `.codex/sessions/YYYY/MM/DD` 的 `event_msg.task_complete`。首次启动跳过现存历史，不发送旧消息；监听之后持久记录事件 ID/时间和读取位置，不转发提示词/回复/会话标题。事件最多保留 24 小时。该本地日志格式非稳定公开通知 API；已观察本机桌面任务产生此事件，不能据此承诺覆盖未来所有 Codex 客户端。未实现 Windows 通知中心通用镜像。

手机生成单独的“Codex 完成提醒”通知渠道，小米运动健康将其转入手环通知中心。每个事件持久去重，通知权限或渠道被禁用时保留事件待重试，ACK 只表示手机已发布通知，**不代表手环已收到或震动**。勿扰、摘腕、通知过滤等由系统决定。手环应用不额外调用振动，避免双重提醒。当前只显示通用完成提示，不发送回复正文；轮次结束也不等于用户目标已成功。

## 验收边界

- 自动检查：跨北京时间午夜、缺文件/坏文件、账本重叠去重、合并 Token、额度窗口识别/过期、完成日志增量/半行/去重/ACK、历史工程测试、RPK/APK 构建。
- 真数据检查：本机 PowerProfile 白名单聚合读取、Codex 两个真实额度窗口读取、HTTPS 身份验证与响应。真实数据仅在本机检查。
- 已验证范围：版本码 8 的通信、统计/额度展示、用户确认的滚动排版及短时息屏同步；手机版本码 9、手环退出应用回到主表盘时的测试通知送达与震动。详见版本和证据记录。
- 待真机：真实 Codex 完成整条通知链路、长时锁屏/重连/重启、勿扰与摘腕行为、通知权限拒绝后恢复，以及后续新包的独立验收。未完成这些不能称完整日常可用版。
- 后续：手机后台状态和错误可见性增强、开机自启、断线验收、明确的安装包签名/哈希/兼容矩阵、独立预览发布与 OTA。不能沿用语音版 OTA 状态来宣称新入口已具备 OTA。

官方接口：[interconnect](https://iot.mi.com/vela/quickapp/en/features/network/interconnect.html)、[小米 App 通知设置说明](https://www.mi.com/sg/support/article/KA-42646/)。公开接口说明不代表 10 Pro 当前固件真机验收。
