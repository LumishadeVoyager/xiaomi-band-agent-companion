# 手机伴侣后台运行

## 当前实现

手机页面采用原生 ScrollView、状态卡片和折叠维护区域。页面只展示状态、打开手环和设置权限；BridgeService 独立负责蓝牙 SDK 监听、统计轮询、米家控制和 OTA。即使打开时手环未连接，PowerProfile 服务仍立即启动。每 30 秒重新核对设备连接，断开后移除失效监听，重连后重新注册。网络恢复时触发新轮询。保持 START_STICKY、BOOT_COMPLETED 与 MY_PACKAGE_REPLACED 恢复，无周期 Alarm 强拉起或无限唤醒锁。

前台服务采用 Android connectedDevice 类型和低打扰持续通知，任务使用独立串行执行器。手机端回执不依赖电脑在线；私有中继每次仅保留最新运行状态。连接状态与最近手环实际消息时间分别展示，避免只凭 SDK 已连接就说统计已经送达。

## 用户设置

打开 PowerProfile 的“允许后台运行”并确认系统电池优化豁免。然后在 realme 系统应用设置允许后台活动、自启动，并锁定最近任务中的本应用；“通知权限”允许完成提醒。页面会显示系统可查询的电池豁免和通知状态，但不把它们当作厂商自启动已经开启的证明。安装确认、指纹与系统授权由本人操作。

Android 仍允许用户从系统停止前台服务或强制停止应用，此时需重新打开。本项目不声称绝对不会被系统结束；长期锁屏、厂商省电、断网/蓝牙恢复和手机重启必须分别取得真实回执。测试不切换电脑插座。

参考：[Android 前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)、[Doze 与电池优化](https://developer.android.com/training/monitoring-device-state/doze-standby)。
