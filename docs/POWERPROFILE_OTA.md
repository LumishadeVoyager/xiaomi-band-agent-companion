# PowerProfile 公网应用 OTA

2026-11-19：手机 v11 禁用了 OTA，已通过一次 ADB 引导安装 v12。其后手机自行通过公网升级至 v13，未用 ADB 安装 v13；手环通过手机下载并安装 v13，用户确认底部 v13、数据显示正常。01:46:12 收到新版本握手与快照回执，待确认安装意图自动清除。下一轮 v13→v14 连续升级正在验收。电脑端已改为 PowerProfile v1.10.0 内置同步，发布于独立 PowerProfile 仓库。

本轮 AstroBox 在手环安装过程中退出，手机一度无法查询队列；更新器保持 unknown，未重复安装，最终由手环 v13 的执行回执核实成功。未宣称修复 AstroBox 原生退出。手环后台无人操作更新、长时锁屏和重启仍待独立验收。

v13 手机更新实际已自动完成，而客户端提交后显示“等待系统确认”的文字导致误报。v14 区分“已提交系统”和系统回调明确返回“需要确认”，确认通知使用独立渠道，并声明系统支持的无用户操作更新权限。是否能免确认仍由 Android 决定。

## 执行与权限

手机服务启动约 20 秒后检查，以后每 15 分钟检查 preview 通道；可关闭自动更新或手动检查。单独更新线程负责下载与安装，不占用统计轮询线程。手机优先升级，再检查手环的最低伴侣版本。安装保留原有包名、签名证书、私有配对、事件去重与设置，不卸载重装。

Android 通过 PackageInstaller 更新自身；系统返回 pending user action 时显示安装确认通知，不能承诺无确认静默安装。已有允许安装来源的权限仍需保留。手环通过手机本地 AstroBox API v2 安装适配器传包，须首次允许 PowerProfile 客户端、设备已连接且 API 可用。AstroBox 不参与日常统计传输。此适配器在后台、锁屏与重启后的可靠性须分别实测，不能把 API 不可用说成 JS 修复完成。

手环页面底部显示版本与更新状态，提供检查更新按钮。设备握手/快照回执报告实际执行版本；安装队列 100% 或 succeeded 不能独立证明新版本已运行。断线时保留 pending 安装意图，查询实际版本，避免重复入队。拒绝/失败按真实结果显示，不改为成功。

## 公网与信任

采用已实测可达的固定 Pages 地址 `/ota/powerprofile-preview.json`。清单是原有信任根签名的 RS256 envelope；验证签名、仓库、通道、有效期、型号/地区、协议区间、反回滚与真实大小/哈希。初期兼容范围仅当前 M2551B1 / CN / 协议 1，其他设备未验收。

清单保留 GitHub Release 的规范包 URL；PowerProfile 客户端将其映射到同一固定 Pages 地址的严格白名单镜像路径。镜像不能更改包哈希、包名、版本或 APK 签名身份。下载使用系统 CA/域名校验，支持断点续传并在安装前完整校验。没有绕过 Android 或手环签名校验，也不替换信任根。

Pages 仅公开已审计安装包及签名 feed。数据接口仍要求独立角色令牌；个人统计、PC/手机令牌、蓝牙密钥和日志不进入静态目录、发布包或 Git。

## 后续发布步骤

1. 更新 package.json 的版本/递增 buildNumber，构建 RPK 与同签名 APK，运行 Node/Java 策略、身份和凭据审计及 CI。
2. `node scripts/create-update-feed.cjs --powerprofile` 从真实产物生成两个组件、SHA-256 和签名清单，输出 `.local/release/VERSION`。此步骤不发布 OTA。
3. 发布该目录真实 APK/RPK 到规范 GitHub Release，核验远端大小/哈希和来源提交。文档预发布不能用作 OTA。
4. `node scripts/stage-powerprofile-ota.cjs` 校验签名和每个实际文件，再将允许公开的文件复制到被 Git 忽略的 Pages 本地发布目录。保留仍被旧清单引用的包。
5. `node scripts/relay-tools.cjs --gateway pages deploy public --project-name powerprofile-band-gateway --branch main` 原子部署；从公网回读 feed 和产物，核验签名/哈希。推送 GitHub 代码本身不会自动提升此设备通道。
6. 分别取得 Android N→N+1、手环 N→N+1、升级后统计/通知和持久配对验收记录。关闭自动更新、拒绝确认、损坏包和断流等失败情形另测。

官方限制：[Android 安装用户操作](https://developer.android.com/reference/android/content/pm/PackageInstaller.SessionParams#setRequireUserAction(int))、[Pages Function 静态资产](https://developers.cloudflare.com/pages/functions/advanced-mode/)。
