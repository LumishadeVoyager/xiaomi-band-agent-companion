# Xiaomi Band Agent Companion

小米手环上的语音 Agent 入口：手环录音，经手机桥接，在电脑上继续已有会话并查看结果。

**当前阶段：技术验证。已构建两个独立 RPK 和对应 Android 测试伴侣，具备 USB 音频接收与本地转写代码。尚未完成 Agent 语音闭环和 OTA。**

## 产品形态

- P0：手环应用列表中有独立的 **Codex** 与 **DeepSeek Harness** 应用，各走对应后端流程。
- 两个应用共享录音、转写、通信、界面组件、请求去重和更新底层；手机统一管理、Windows 共用服务。
- P1：独立 Claude Code 入口，以及仅通过安卓手机飞书 App 给多个角色智能体发消息的飞书入口；不直接连接公司电脑或公司侧 Lark Bridge。
- P2：小米手环 9 Pro、10、11 等候选系列；按具体型号、地区、固件和音频能力验证，不预先宣称支持。
- P0：公开 GitHub 开发记录及 Windows、Android、各独立手环应用的 OTA 流程；平台必要的安装确认如实呈现。

**手环自身麦克风输入是核心要求。** 手机/电脑录音或纯文字版本不算完成语音首版。无麦克风或接口受限的扩展型号可提供明确标注的文本/看板等级。

## 文档

- [需求规格说明书 v0.4](./需求规格说明书.md)
- [上游项目调研与融合方案](./项目调研与融合方案.md)（历史调研；需求以 v0.4 为准）
- [并行工作线与进度](./docs/ROADMAP.md)
- [设备兼容矩阵](./docs/DEVICE_MATRIX.md)
- [后端及独立应用身份](./docs/BACKENDS.md)
- [GitHub 开发与 OTA 规范](./docs/GITHUB_AND_OTA.md)
- [变更记录](./CHANGELOG.md) 与 [贡献流程](./CONTRIBUTING.md)
- [开发探针运行说明](./docs/DEV_PROBE.md)

## 开发状态

开发探针可构建安装，尚不是日常 Agent 客户端。10 Pro 已实测应用安装和手机通信；当前固件的第三方 `record.start` 不可用。系统录音经小米运动健康同步、ADB 取出及电脑本地转写已验证；免手机操作和无线自动取音仍未解决。各项进度以 GitHub Issues、PR、检查结果和 Releases 为准，不能把探针作为完整语音版或 OTA 更新源。

当前核心验证目标是小米手环 10 Pro。初始验证环境使用 Android/realme UI 与 Windows；其他手机及手环只能在验证后标支持。

## 上游与许可

项目自主编写部分采用 [MIT](./LICENSE)。主要调研 [Codex Quota Band](https://github.com/Vincent-hechuan/codex-quota-band)（MIT）和 [Xiaomi Band Codex Notify](https://github.com/xDylanLong/xiaomi-band-codex-notify)（本次未见明确许可证，暂不复制代码）。小米 Wearable SDK 需从官方渠道另行取得，不能由本项目 MIT 许可替代。

本项目不是 Xiaomi、OpenAI、DeepSeek、Anthropic 或飞书的官方产品。公司实例的凭据、内部角色提示词、聊天、音频和私有地址不进入公开仓库。
