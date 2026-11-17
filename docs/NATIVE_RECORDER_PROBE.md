# 系统录音调试通路

关联：[#2 音频出口](https://github.com/LumishadeVoyager/xiaomi-band-agent-companion/issues/2)、[#21 录音体验](https://github.com/LumishadeVoyager/xiaomi-band-agent-companion/issues/21)。

当前是 ADB 开发原型：手环按钮 → 手机 RFCOMM/SPP → 电脑协议驱动 → 手环系统录音机 → reverse MASS 音频 → 本地转写 → 手环草稿。没有提交 Agent 任务，尚未接入独立 Android 后台服务。

真机已验证音频传输、转写和草稿消息回传：RPK build 7 点击 → 系统录音约 10 秒 → 手机蓝牙 → 电脑收到 40,882 字节 Ogg/Opus 并验证长度/CRC32 → 本地转写 4.422 秒 → 手环返回 draft_ack。用户实际看到的前台仍是系统录音机同步提示，前台回显与连续录音未通过，不能视为完整使用闭环。草稿保持 unsubmitted，未调用 Agent。另有独立 5 秒录音验证：20,497 字节、6 个分块。音频与转写内容只保存在本机。

协议实现：`host/xiaomi_native.py`；开发驱动：`host/native_recorder_bridge.py`；安卓 ADB 传输：`tools/android/BandRecordTransport.java`。Java 文件编译为 DEX 后通过 ADB app_process 运行，设备地址作为参数；通过 `adb forward tcp:18791 localabstract:band_record_probe` 连接。驱动需要 `pycryptodome==3.23.0`。

驱动使用私有 JSON 配置，字段为 authkey、fingerprint（本项目证书 SHA1）、python（faster-whisper 解释器）、output（私有音频目录）。配置和音频不提交。启动方式：

```text
python host/native_recorder_bridge.py <private-config.json> --launch codex
```

普通录音会切换到系统录音机并保存文件。RPK 页面退出不再自动取消外部录音，显式取消按钮仍有效。无界面采集、临时文件清理和连续录音由 #21 跟进。REALTIME=1 虽返回 code=0，但仍提供文件标识，5 秒测试没有实时音频流；不视为已支持实时采集。本分支不能作为完整手机语音客户端发布到 OTA feed。

最终录音交互为按住开始、松手停止，替代固定 10 秒测试。须先解决系统页面抢占前台和触摸结束事件丢失。一次性测试脚本在 draft_ack 后退出并恢复运动健康；随后重试出现 1006，不能要求用户通过重连运动健康恢复这个已退出的独立调试中枢。

拔掉 USB 会中断该调试通道。另观测到 Android CDM 会在 force-stop 运动健康约 10 秒后重启 ConnectivityCompanionDeviceService；重连 USB 后也复现了该服务启动后通道关闭。临时隔离运动健康后，连接保持并完成上述草稿闭环。测试结束已恢复运动健康原启用状态并重新打开；正常共存仍待解决。

协议参考：[minstall](https://github.com/HyperionD/minstall)、[AstroBox protobuf](https://github.com/AstralSightStudios/AstroBox-NG-Module-Pb)、[AstroBox Core](https://github.com/AstralSightStudios/AstroBox-NG-Module-Core)。此固件取音使用 MEDIA 18 的标识列表；单文件 MEDIA 17 没有成功。
