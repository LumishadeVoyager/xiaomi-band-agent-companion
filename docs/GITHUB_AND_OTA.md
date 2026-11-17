# GitHub 开发与 OTA 规范

## GitHub 记录

每个任务用 Issue 记录需求 ID、优先级、工作线、依赖和验收；开工、阻塞、关键结果、收尾更新状态。分支/PR 关联任务；CI 检查和设备验收分开写。M0/M1/M2/M3 为里程碑，不以无依据百分比代替进度。

当前 CI 只检查文档/仓库结构。实现进入仓库后，为变更增加真实的协议、适配器、音频、更新器与三端构建检查。无签名密钥或 SDK 时明确标构建限制，不能跳过后显示完整发布通过。

文档预发布可自动生成 Release Notes 和附文档，但不得携带虚假安装包或可消费 OTA feed。实际客户端 Release 必须有构建产物、SHA-256、签名/身份、兼容矩阵、验收范围和已知限制。

## OTA 组件

Windows、Android 主应用/必要桥接包、Codex RPK、Harness RPK 分别有 componentId/appId/entryId。未来 Claude Code/飞书 RPK 沿用同一框架。按具体设备/地区匹配包，不能只按项目版本找一个通用 RPK。

版本化、签名更新清单声明：产品版本、组件版本、协议区间、平台/架构、设备/地区、最低版本、包 URL/大小/哈希、签名、发布通道、说明和兼容升级顺序。签名清单与信任根独立验证；只有下载 URL 和哈希不是完整发布身份验证。

- 自动检查 stable，preview 可选；按用户配置自动下载。
- 录音/转写/执行中延期切换，保存草稿、配对和去重记录。
- Windows 空闲安全更新/重启，失败可恢复旧版本。
- Android 使用系统允许的安装/自更新流程，处理 pending user action。
- 手环通过实际验证的通路无线传包并升级，必要确认可交给用户；只有把文件复制到手机不是完成 OTA。
- 校验失败、型号/地区不符、协议不兼容时拒绝安装；报告每个组件实际版本。
- Android/手环若系统禁止降级，提供真实恢复方案，不承诺自动降级。

实际客户端及 OTA 更新器尚未实现。首次进入 stable 前，完成 N→N+1、断流重试、签名/包损坏、升级延期、兼容拒绝和数据保留测试。

参考：[Android 安装用户操作要求](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int))。
