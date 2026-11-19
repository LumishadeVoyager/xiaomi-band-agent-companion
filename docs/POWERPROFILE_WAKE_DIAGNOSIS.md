# PowerProfile 电脑来电唤醒排查

2026-11-19 用户要求仔细排查并修复。仍处于定位与受控验收准备，不能写成硬件唤醒已修复。

## 已确认

- 机型为 MECHREVO Jiguang Pro Series GM6PQ0X，AMI BIOS N.1.06MRO16。用户一直合盖，使用外接显示器。
- 当前电源方案的合盖行为：AC 不处理，DC 休眠；低/关键电量阈值为正常的 6%/5%，没有通过设置 100% 低电量触发休眠。
- 系统支持 S0 Modern Standby 和 S4，快速启动已禁用；相关日志确认一次断 AC 后进入 S4。当前普通已启用的设备唤醒来源是 HID 键鼠，不能据此推断 AC 固件唤醒已开启。
- 用户观察恢复供电后约 5W、屏幕一直黑，实体电源键可恢复。手机独立米家控制及插座 on 已通过，电脑固件接受唤醒未通过。
- 用 Windows GetSystemFirmwareTable 只读取得 DSDT/FACP，使用 ACPICA 官方 iASL 反汇编；未覆盖或加载 ACPI 表。AC0 的 _PSR 读取 EC 的交流电状态，AC 事件进行电源/电池状态通知，未找到可直接从标准 Windows 设置配置的 S4 AC 唤醒入口。此结果不证明 EC 固件绝对不支持 S4 唤醒。
- 原厂控制台已有 UEFI/EC 接口，但已核对的公开可识别接口不提供明确的 AC 唤醒设置语义。未执行原厂工具的写入/重置功能或猜测 EC 寄存器。

## 尚待区分

1. 来电选项实际是仅支持 S5/G3 的电源恢复，而不是 S4 的 Wake on AC。
2. 固件对合盖时来电唤醒有额外限制。
3. 重新通电发生在休眠还未完成或适配器未充分掉电的窗口，AC 事件未产生有效唤醒。

目前没有足够证据在这三者中选定根因，不以反复断电作为修复。

## 下一步具体步骤

先保存工作；需要本人在电脑旁，电源键作为已知可恢复入口。进入 UEFI 设置会中断当前电脑与远程调试，因此须确认可安排该中断后再操作。

1. 通过 Windows 高级启动的 UEFI 固件设置进入 BIOS；不刷写 BIOS、不恢复默认、不修改 C-state。
2. 读取并记录现有电源项原文和当前值：Wake on AC / Power On AC / Restore AC Power Loss / After Power Failure / State After G3 等。不存在的项不凭名称相近猜测。先确认作用范围，再做可恢复的单项修改。
3. 受控对照：保持 AC，手动请求一次正常 S4 休眠，等机器完成休眠后，用手机保持断供电约 30 秒再恢复。先开盖再合盖分别测试；每轮只切换一次，失败以实体电源键恢复。30 秒为保守测试间隔，不声称它是已验证的最小硬件要求。
4. 以电脑新心跳、系统恢复日志与本人观察共同确认，而不是只看插座 on。只在已确认能稳定唤醒后再减少等待时间。

若仅开盖能唤醒，需要处理固件的合盖条件；若仅完全关机可来电自启，不能用更改 App 文案或禁用休眠冒充 S4 修复，应明确调整使用方案或增加独立唤醒通路。

## 当前边界

只读诊断、证据和对照步骤已准备。未切换电脑插座、请求休眠/重启、写 EFI 变量、写 EC 寄存器、覆盖 ACPI、修改电源计划或 C-state。固件表和私有运行记录仅留本地，不进入仓库。

参考：[Microsoft 固件表读取](https://learn.microsoft.com/en-us/windows/win32/api/sysinfoapi/nf-sysinfoapi-getsystemfirmwaretable)、[Microsoft 系统电源状态](https://learn.microsoft.com/en-us/windows/win32/power/system-power-states)、[ACPICA 官方发行](https://github.com/open-acpica/acpica/releases)。


## 同平台 AC Auto Boot 线索

后续在 TUXEDO Computers 的 Uniwill 驱动主线发现 AC Auto Boot 的已知 EC 控制（0x0726 的 bit 3），同时该驱动明确按 DMI 白名单启用支持；本机主板 GM6PQ0X 不在已验证名单。不能将同平台地址直接视为本机已验证的设置，更不能猜测写入。查到了对应 GUID 的本机 WMI 类 AcpiTest_MULong，但普通权限查询实例返回拒绝访问，未调用其硬件方法。

来源：[厂商驱动接口定义](https://github.com/tuxedocomputers/tuxedo-drivers/blob/main/src/uniwill_interfaces.h)、[厂商驱动支持名单与 AC Auto Boot 实现](https://github.com/tuxedocomputers/tuxedo-drivers/blob/main/src/uniwill_keyboard.h)。只读基线和进入固件设置辅助脚本已在本地准备，脚本尚未执行。等待用户确认可安排电脑中断后继续 BIOS 核对与受控测试；没有把这一线索写成修复完成。

## 原厂控制台截图与触发条件纠正

用户截图确认设置在机械革命控制台，“来电自动开机”已经开启。原文限定“关机状态”，要求“未充电状态改变为充电状态”；保持充电状态关机不会自动开机。此前将设置入口指向 BIOS 的判断过早，现以本机原厂控制台为准，不再继续盲找 BIOS。

需区分四件事：插座继电器是否开启、EC 是否感知 AC 输入、电池是否正在充电、Windows 是 S4 还是 S5。单次只读观测显示本机 AC 在线、电量 100%、charging 标志未置位，证明接电与电池实际充电不是同一状态。原厂通俗文案不证明硬件按 battery charging 位触发，不能据此断言满电导致失败，也不应先更改充电阈值或放电。

优先对照两个条件：S4 是否在控制台功能支持范围内；以及是否完成关断、休眠和有效 AC 移除后才恢复供电。截图说关机，未证明支持 S4，也未证明不支持。重复写 on 不产生新的 off→on 边沿；过早恢复供电可能错过唤醒触发窗口，但当前尚未确认这是根因。

候选调整应是“确认关断 → 等待休眠/掉电稳定 → 单次恢复 → 等待新电脑心跳”。先用 30 秒作为受控对照间隔，不称为已验证的硬件最小要求；验证有效后再固化参数。不能在开机失败时自动循环切电，以免切断刚恢复运行的电脑。来电自动开机保持开启，关机 USB 供电的影响尚无证据，暂不盲改。

本轮仅单次只读采样，未启动额外常驻观察进程，未执行插座、休眠、关机、重启、EC 或 EFI 写入。受控对照需区分“已断电并完成 S4 后重新来电”和“完整 S5 关机后重新来电”，不能悄悄把保留会话的休眠替换成关机。具体根因及修复仍待验证。原图只留本地。

参考：[Microsoft 接电与充电状态字段](https://learn.microsoft.com/en-us/windows/win32/api/winbase/ns-winbase-system_power_status)、[S4 与 S5 区别](https://learn.microsoft.com/en-us/windows/win32/power/system-power-states)。
