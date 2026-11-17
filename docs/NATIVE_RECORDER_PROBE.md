# 系统录音调试通路

关联：[#2 音频出口](https://github.com/LumishadeVoyager/xiaomi-band-agent-companion/issues/2)、[#21 录音体验](https://github.com/LumishadeVoyager/xiaomi-band-agent-companion/issues/21)。

当前是 ADB 开发原型：手环按钮 → 手机 RFCOMM/SPP → 电脑协议驱动 → 手环系统录音机 → reverse MASS 音频 → 本地转写 → 手环草稿。没有提交 Agent 任务，尚未接入独立 Android 后台服务。

真机已验证系统 START/STOP、状态 1→0→1，以及新生成的 5 秒 Ogg/Opus 文件：20,497 字节、6 个分块，长度和 CRC32 一致。用户确认按钮能打开系统录音机。新语音的完整按钮闭环尚待验收。

协议实现：`host/xiaomi_native.py`；开发驱动：`host/native_recorder_bridge.py`；安卓 ADB 传输：`tools/android/BandRecordTransport.java`。Java 文件编译为 DEX 后通过 ADB app_process 运行，设备地址作为参数；通过 `adb forward tcp:18791 localabstract:band_record_probe` 连接。驱动需要 `pycryptodome==3.23.0`。

驱动使用私有 JSON 配置，字段为 authkey、fingerprint（本项目证书 SHA1）、python（faster-whisper 解释器）、output（私有音频目录）。配置和音频不提交。启动方式：

```text
python host/native_recorder_bridge.py <private-config.json> --launch codex
```

普通录音会切换到系统录音机并保存文件。RPK 页面退出不再自动取消外部录音，显式取消按钮仍有效。无界面采集、临时文件清理和连续录音由 #21 跟进。REALTIME=1 虽返回 code=0，但仍提供文件标识，5 秒测试没有实时音频流；不视为已支持实时采集。本分支不能作为完整手机语音客户端发布到 OTA feed。

拔掉 USB 会中断该调试通道。另观测到 Android CDM 会在 force-stop 运动健康约 10 秒后重启 ConnectivityCompanionDeviceService；是否发生连接争用需与拔线分别验证，不能直接认定为断链原因。

协议参考：[minstall](https://github.com/HyperionD/minstall)、[AstroBox protobuf](https://github.com/AstralSightStudios/AstroBox-NG-Module-Pb)、[AstroBox Core](https://github.com/AstralSightStudios/AstroBox-NG-Module-Core)。此固件取音使用 MEDIA 18 的标识列表；单文件 MEDIA 17 没有成功。
