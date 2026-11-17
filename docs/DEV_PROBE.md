# 开发探针

当前只做能力验证，不执行 Agent、不使用手机麦克风、不上传音频到云端。调试音频、转写、SDK 和签名均留在被忽略的本地目录。

- `wearable/src/`：公共录音/接口诊断界面，生成 Codex/Harness 两个包。
- `android-probe/`：官方 Wearable SDK 桥接，独立包名，同证书签名；仅开发版允许 loopback HTTP 和测试文本 Intent。
- `shared/audio-wire.cjs`、`host/audio-session.cjs`：消息、分块、完整性、重复包和 WAV 封装。
- `host/audio-receiver.cjs`：只监听 `127.0.0.1:8789`，随机开发 token，用 `adb reverse tcp:8789 tcp:8789` 连手机。
- `host/transcribe.py`：显式本地转写为 `unsubmitted` 草稿，永不启动 Agent。

```sh
npm test
npm run dev:receiver
```

将 `aiot-toolkit@2.0.5` 和 `@aiot-toolkit/jsc@1.0.3` 安装到 `BAND_AGENT_TOOLS`（Windows 默认 LocalAppData/BandAgentDev），运行 `npm run build:wearable`。

Android 使用 `scripts/build-android-probe.py --help` 指定 JDK 17、Android 平台/build-tools 和自行从小米官方 Demo 取得的 AAR。先运行接收器以生成本地开发 token；Android 构建会生成/复用本地开发证书，之后用同一证书构建 RPK。APK/RPK 在 `.local/artifacts/`，不提交仓库。探针不是生产签名和生产联网方案。

本地转写环境安装 `host/requirements-transcription.txt`，运行：

```sh
python host/transcribe.py INPUT_AUDIO --model small --output .local/draft.json --allow-model-download
```

首次仅下载公开模型，录音在本机识别；已有模型时去掉下载选项。开发版限制 60 秒，不支持后台自动提交。

本轮真机发现：标准 `record.start` 不可调用；系统录音输出 Ogg/Opus，48 kHz 单声道，可经运动健康同步后由 ADB 导入电脑，12 秒样本已完成中文转写。此路线尚依赖手机同步操作及开发 ADB，不能宣称满足最终免手机操作/无线闭环。

## dev.3 录音故障检查

dev.2 诊断有空值解引用：模块返回 `undefined` 后读取 `raw.start`，报 `Cannot read property start of undefined`。dev.3 在读取任何方法前检查空值，区分 `module_missing`、`start_missing`、`module_load_error` 和 `method_available`；同时查询官方 `app.canIUse('@system.record')` / `app.canIUse('@system.record.start')`。方法存在只代表可尝试调用，不代表已录到音频，也不把错误码 202 自动解释成权限问题。

应用显示版本，连接后自动回传能力报告；报告只含接口形状和查询结果。测试执行实际 `.ux` 页面脚本，覆盖缺失模块、异常、导出包装、诊断协议及入口隔离。RPK 构建还检查当前目标文件确实生成，拒绝构建工具“报错但退出码为零”导致的旧包误用。

已核对的源码和文档：

- [小米官方 record](https://iot.mi.com/vela/quickapp/zh/features/system/record.html)：允许 `require('@system.record')`；支持表未列出 10 Pro，不能据此推断其支持或完全不支持。
- [小米官方 app.canIUse](https://iot.mi.com/vela/quickapp/zh/features/basic/app.html)：查询模块/方法能力。
- [Codex Quota Band](https://github.com/Vincent-hechuan/codex-quota-band) 与 [Xiaomi Band Codex Notify](https://github.com/xDylanLong/xiaomi-band-codex-notify)：未找到手环采音实现，不能当作录音成功案例。
- [TimeW 录音诊断](https://github.com/deprav1/TimeW/blob/main/watch/src/common/runtime-diag.js) 及其 [问题记录](https://github.com/deprav1/TimeW/blob/main/docs/problems.md)：面向 Watch S5，仍记录录音失败，不能移植后宣称已解决。
- [AstroBox 媒体组件](https://github.com/AstralSightStudios/AstroBox-NG-Module-Core/blob/main/src/device/xiaomi/components/media.rs) 有媒体列表/取文件能力，[媒体协议](https://github.com/AstralSightStudios/AstroBox-NG-Module-Pb/blob/main/protos/xiaomi/wear_media.proto) 有录音请求/状态消息。此为系统录音桥接候选，不代表 10 Pro 已支持这些控制命令；`action` 语义、文件传输、插件接口和主连接共存均待真机验证，不盲发猜测命令。
