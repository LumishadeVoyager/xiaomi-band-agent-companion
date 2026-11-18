# PowerProfile 手环监看

2026-11-18 用户新需求优先于历史语音规格。关联 [Issue #23](https://github.com/LumishadeVoyager/xiaomi-band-agent-companion/issues/23)。

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

电脑独立 HTTPS 服务 → 安卓独立配套 APK → 小米 interconnect → 手环前台/缓存。复用当前已存在的电脑 TLS 身份和签名配对信任根；端口独立为 8791。无需 AstroBox 常驻。默认同一可信局域网，未实现外网访问或自动开机注册。

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

## 完成提醒

只读本机 `.codex/sessions/YYYY/MM/DD` 的 `event_msg.task_complete`。首次启动跳过现存历史，不发送旧消息；监听之后持久记录事件 ID/时间和读取位置，不转发提示词/回复/会话标题。事件最多保留 24 小时。该本地日志格式非稳定公开通知 API；已观察本机桌面任务产生此事件，不能据此承诺覆盖未来所有 Codex 客户端。未实现 Windows 通知中心通用镜像。

手机生成单独的“Codex 完成提醒”通知渠道，小米运动健康将其转入手环通知中心。每个事件持久去重，通知权限或渠道被禁用时保留事件待重试，ACK 只表示手机已发布通知，**不代表手环已收到或震动**。勿扰、摘腕、通知过滤等由系统决定。手环应用不额外调用振动，避免双重提醒。当前只显示通用完成提示，不发送回复正文；轮次结束也不等于用户目标已成功。

## 验收边界

- 自动检查：跨北京时间午夜、缺文件/坏文件、账本重叠去重、合并 Token、额度窗口识别/过期、完成日志增量/半行/去重/ACK、历史工程测试、RPK/APK 构建。
- 真数据检查：本机 PowerProfile 白名单聚合读取、Codex 两个真实额度窗口读取、HTTPS 身份验证与响应。真实数据仅在本机检查。
- 待真机：新 APK/RPK 的签名身份通信、页面布局/滚动/额度条、手机锁屏后台/重连、应用关闭时系统通知/震动/勿扰行为、通知权限拒绝后恢复。未完成这些不能称日常可用版。
- 后续：手机后台状态和错误可见性增强、开机自启、断线验收、明确的安装包签名/哈希/兼容矩阵、独立预览发布与 OTA。不能沿用语音版 OTA 状态来宣称新入口已具备 OTA。

官方接口：[interconnect](https://iot.mi.com/vela/quickapp/en/features/network/interconnect.html)、[小米 App 通知设置说明](https://www.mi.com/sg/support/article/KA-42646/)。公开接口说明不代表 10 Pro 当前固件真机验收。
