# 手机伴侣后台运行

## 当前实现

手机页面采用原生 ScrollView、状态卡片和折叠维护区域。页面只展示状态、打开手环和设置权限；BridgeService 独立负责蓝牙 SDK 监听、统计轮询、米家控制和 OTA。即使打开时手环未连接，PowerProfile 服务仍立即启动。每 30 秒重新核对设备连接，断开后移除失效监听，重连后重新注册。网络恢复时触发新轮询。保持 START_STICKY、BOOT_COMPLETED 与 MY_PACKAGE_REPLACED 恢复，无周期 Alarm 强拉起。v17 在已配对的 PowerProfile 前台服务内维持有超时的 CPU 唤醒锁，见下文；这会增加耗电。

前台服务采用 Android connectedDevice 类型和低打扰持续通知，任务使用独立串行执行器。手机端回执不依赖电脑在线；私有中继每次仅保留最新运行状态。连接状态与最近手环实际消息时间分别展示，避免只凭 SDK 已连接就说统计已经送达。

## 用户设置

打开 PowerProfile 的“允许后台运行”并确认系统电池优化豁免。然后在 realme 系统应用设置允许后台活动、自启动，并锁定最近任务中的本应用；“通知权限”允许完成提醒。页面会显示系统可查询的电池豁免和通知状态，但不把它们当作厂商自启动已经开启的证明。安装确认、指纹与系统授权由本人操作。

Android 仍允许用户从系统停止前台服务或强制停止应用，此时需重新打开。本项目不声称绝对不会被系统结束；长期锁屏、厂商省电、断网/蓝牙恢复和手机重启必须分别取得真实回执。测试不切换电脑插座。

参考：[Android 前台服务类型](https://developer.android.com/develop/background-work/services/fgs/service-types)、[Doze 与电池优化](https://developer.android.com/training/monitoring-device-state/doze-standby)。

## v17 后台可靠性修复（2026-11-20）

用户报告 v16 必须保持 Activity 前台才同步。未接手机调试，具体厂商杀进程原因不能据此确认；代码核查发现通知投递前同步调用手环 SDK、健康回执与同步共用队列、部分周期任务会因未捕获运行异常停止，以及缺少息屏 CPU 执行保障。

- 云端轮询/手机通知不调用手环 SDK；手环发送使用独立、有界队列，积压时丢弃最旧待发送消息，周期最新状态补齐。SDK 阻塞不会阻塞手机通知队列；不宣称能修复 SDK 内部死锁。健康回执独立于同步与 OTA。
- 周期 SDK 查询、米家轮询、OTA、健康维护隔离运行异常；记录错误类与时间，后续周期继续。同步错误与健康执行时间在手机页可见。
- 已配对且前台服务运行期间，PARTIAL_WAKE_LOCK 每 45 秒续租，单次超时 120 秒；服务销毁或取消配对释放。持续续租会阻止正常 CPU 深睡并增加耗电；健康线程停止则租约到期。不是永久免死或 Doze 网络豁免，仍需用户已授权忽略电池优化。
- 系统持久 JobScheduler 每 15 分钟请求检查服务；系统可能延期，并非 15 分钟恢复承诺。后台启动被拒则记录原因并提供可点击恢复通知。系统强制停止后不会绕过用户选择。
- 网络恢复、亮屏、省电/Doze 状态变化触发重新同步/查连接。保留开机、应用升级恢复。未使用假音频、无障碍滥用、精确闹钟循环或改变手环日常中枢。

验证：真实 ScheduledExecutor 注入一次异常后仍继续周期执行；实际 PowerProfileBridge 在不加载小米 SDK 的 JVM 中验证无通知权限时保存快照且不 ACK、发布后 ACK 网络失败重试不重复提醒、渠道禁用不 ACK。Android 系统用测试替身，仅验证代码语义；不证明省电模式、realme 杀后台、全天锁屏或真实震动已通过。新包签名构建与 OTA 发布另记，手机新版本运行回执及长期验收仍待取得。

若旧版本已被系统停止，云端无法凭空启动它来下载新 APK；需用户打开一次旧应用检查更新，或从公网安装包入口更新。Android 安装确认与厂商后台授权仍可能需要本人操作；无需 USB。

资料：[Android 唤醒锁](https://developer.android.com/develop/background-work/background-tasks/awake/wakelock)、[Doze 网络与豁免](https://developer.android.com/training/monitoring-device-state/doze-standby)、[后台启动前台服务的例外](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)。
