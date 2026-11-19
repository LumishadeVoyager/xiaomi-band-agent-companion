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
