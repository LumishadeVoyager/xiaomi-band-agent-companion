# 后端、入口和路由

| 手环独立应用 | 后端/渠道 | 优先级 | 状态 |
| --- | --- | --- | --- |
| Codex | 本机/已配对主机 Codex App Server | P0 | 待实现 |
| DeepSeek Harness | 对应 Harness 实例的官方可用集成接口 | P0 | 待接口验证 |
| Claude Code | 对应主机 Claude Code | P1 | 待实现 |
| 飞书智能体 | 手机已登录的飞书 App → 已绑定角色会话 | P1 | App 发送/回包方式待验证，不直连公司电脑/Bridge |

两个 P0 应用不能合成一个应用内的切换器。它们独立包名、显示名、图标、固定 backendId 与数据命名空间，复用公共模块构建。手机可统一管理；SDK 若强制 APK/RPK 一一绑定，需要验证多个最小桥接包的部署方式，不能假定单 APK 可承载所有身份。

统一接口覆盖 listSessions、readHistory、getStatus、submit、steer、cancel、subscribe 和 queryRequest；这些是本项目适配层职责名，不是已经存在的上游 API。适配器如实报告能力；不支持的接口明确报错。

直接 Agent 身份由 backendId、instanceId/hostId、entryId、原生 sessionId 组合。飞书使用手机账号上下文、已绑定会话和 agentRoleId，不保存公司主机/Bridge 配置。目标不可在异步回调中用“当前选中值”替换，已提交请求不因切入口改目标。

语音提供方与执行后端独立。Harness 不依赖 Codex 运行；企业角色预设、凭据、聊天和内网地址只在授权私有配置中维护，不进入示例或诊断。

## DeepSeek Harness 调研依据

官方项目有插件化 Host、API Gateway 与 Remote 方法机制；仅声明并导出的 Remote 方法才可调用。这是候选技术基础，不是本项目已建立桌面会话读写连接。应按实际安装版本验证原桌面会话、身份、权限、流事件和取消。

- [官方项目](https://github.com/deepseek-ai/deepseek-harness)
- [API Gateway](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/api-gateway.md)
- [Remote API 开发说明](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/cookbook/adding-a-remote-api.md)

## 飞书仅经 Android App

飞书智能体虽在公司侧通过 Lark Bridge 驱动，本项目不调用该 Bridge，也不连接公司电脑。用户在手机绑定角色别名和真实飞书会话，手环选择后发送转写文字。

优先测试受支持的 Intent/分享/深链；若只能预填，不能显示已发送。受控 UI 自动化属于后续明确启用的可行性选项，界面或目标不匹配则失败。回复以能可靠归属的通知/获授权 App 界面为限，不能把通知当完整历史或执行状态。具体实现未验证，不读取 App 私有数据或企业凭据，不实际发送消息。
