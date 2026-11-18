# 手机直连米家云端

用户批准电脑卡片右侧无文字电源图标；电脑已设置来电自启、断电休眠。控制路线为手环 → 手机伴侣 → 小米 HTTPS 云端 → 指定插座，电脑 PowerProfile 只负责统计，无需保持电脑在线。插座恢复供电不等于操作系统已经启动。手环到手机仍使用蓝牙。

沿用电脑现有米家授权会话，引导工具只读取空气净化器并取出必要 service session，不传密码、passToken 或 refresh token。配置用固定更新信任根签名，再使用手机配对密钥派生的 AES-GCM 加密，私有中继只保存密文。手机验签后用 Android Keystore 加密保存，备份关闭；设备标识和凭据不写入 APK/RPK、GitHub 或公开 OTA 清单。

本实现兼容 PowerProfile 当前安装的 mijiaAPI 5.0.0 云协议，不是新申请的官方 OAuth 应用。协议来源：[mijiaAPI](https://github.com/Do1e/mijia-api)、[micloud MIT encryption implementation](https://github.com/Squachen/micloud/blob/master/micloud/miutils.py)。手机请求固定 `https://api.io.mi.com/app/miotspec/prop/get` 和 `prop/set`，保持平台 TLS 校验，不向替代 API 域名传凭据。会话可能提前过期，30 天只是本地配置有效期上限；过期需要从电脑现有授权重新配对，不承诺永久有效。

配置只允许电脑 cuco.plug.v3 与唯一空气净化器的已验证 bool 开关属性，手环只能操作当前激活目标，不能指定任意设备或属性。UI 显示真实读回值，状态过期或连接失败变灰；重复点击请求去重，不自动重试写命令，云端确认后仍要读回。按钮表示插座供电，不用它推断电脑已完成启动或休眠。

## 本轮验收约束

仅空气净化器参与切换。`scripts/pair-mijia-cloud.py --publish --active purifier --test-purifier` 发布一次短期有效、根签名的测试请求，手机保存初始状态后反转并读回，再恢复并读回；手机重启后先恢复未完成的测试，不重复反转。任何断点遗留恢复失败必须标注，不能报告测试通过。测试代码固定选择 purifier，不能指向电脑插座。

完成后发布不带测试请求的 computer 配置，只读取电脑插座，不切换其开关。用 `--status` 查询私有中继收到的手机版本、目标、读回状态和测试回执。真机结果另行记录；构建/模拟测试不代表真实云端调用成功。
