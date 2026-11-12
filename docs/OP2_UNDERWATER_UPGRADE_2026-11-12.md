# v0.11.0 水下 H7 与双机控制适配

日期：2026-11-12。Android 版本码 11。上游固定基线为 [`surface_robot/feature/op-2` 的 `fedb6a1768962f8dd7570c4500cf03330718a26e`](https://github.com/YYYKblue/surface_robot/commit/fedb6a1768962f8dd7570c4500cf03330718a26e)，上一版基线为 `8e8f2df`。交付前通过 `git ls-remote` 再次核对分支仍为该提交。

## 协议与界面

上游新增独立 `under_control` 服务，并将 Windows 控制端扩展为双机操作。Android 分别连接水面 `:8765` 与水下 `:8766`，不把 H7 指令发给水面 ROS 网关，不共用任务锁或控制身份。

依据：[服务说明](https://github.com/YYYKblue/surface_robot/blob/fedb6a1768962f8dd7570c4500cf03330718a26e/under_control/README.md)、[严格请求解码器](https://github.com/YYYKblue/surface_robot/blob/fedb6a1768962f8dd7570c4500cf03330718a26e/under_control/protocol.py)、[会话状态机](https://github.com/YYYKblue/surface_robot/blob/fedb6a1768962f8dd7570c4500cf03330718a26e/under_control/session.py)、[WebSocket 服务](https://github.com/YYYKblue/surface_robot/blob/fedb6a1768962f8dd7570c4500cf03330718a26e/under_control/server.py)、[Windows 水下控制面板](https://github.com/YYYKblue/surface_robot/blob/fedb6a1768962f8dd7570c4500cf03330718a26e/operator_app/underwater.py)。

| 项目 | Android 行为 |
|---|---|
| 连接 | 设置中独立水下地址、连接和断开；默认水面同主机 8766，支持独立主机和 IPv6；启动不自动连接 |
| 双机操作 | 清洗页和手动页显示水下控制区；水面任务／人工暂停不锁水下，任一连接断开不清另一台意图 |
| 水下方向 | 前进、后退、左转、右转；按住运行、松开停止全部水下输出；同台多指冲突保持停止直到全部松开 |
| 水下幅度 | 0～100% 整数，默认 25%，滑条、±1 和 25/50/75 快捷选择；调到 0 清方向，调高不恢复旧动作 |
| 停止 | 水面／水下各自停止，顶部“两台手动停止”；不取消自动任务，不覆盖 H7 遥控源 |
| 生命周期 | 切页、失焦、退后台、导出日志、退出撤销两台旧手动操作；恢复后需新按键 |
| 反馈／日志 | App 请求与新鲜 H7 回报分开；独立“水下 H7”日志来源、地址和状态可筛选导出 |
| 模拟 | 内存双链路演示，不打开网络或串口，不包含电机／船体动力学 |

H7 V6 固件定义前进随动前滚刷，所有运动联动顶升目标 40%；换向可能先等待 1 秒。Android 不发送独立水下辅助电机控制。输出百分比不是实测航速，兼容回报不是实测 PWM。

## 时序与失效处理

水下每条请求包含 `protocol=under-operator-v1`、`device=h7_under_original_v6`；hello 带 `version=1`，必须收到匹配能力和 V6 输出描述后才接受水下状态。遥测只接受当前 WebSocket 代次、同一 `session_id`、严格递增整数 `sequence` 和有效收包时刻。

`UnderwaterSender` 在独立线程约每 50 ms 发送当前意图。Main 界面刷新旧意图的期限为 250 ms；H7 反馈期限为 `网络回调收包时刻 + 250 ms - report_age_sec`，从而把 GUI 排队时间计入反馈年龄，不能用更新界面或解码完成时间延长期限。水面原有 800 ms 遥测门槛保持不变。

控制要求 H7 已连接、新鲜、Nano 来源、READY/ACTIVE、armed、OK、motion_enabled、available，且 owner 为空或本客户端。`connection_id/control_generation/epoch/source` 改变清旧方向。发送器只在明确新触摸后重新启动；收包、GUI 心跳、调速和重连不能恢复旧按住动作。

收包采用有界握手队列与最新遥测槽；替换遥测时保留中间失权／身份变化的撤销标记，避免短暂失权被后续 READY 覆盖。发送积压或底层拒绝发送时关闭水下连接，禁止旧帧继续排队；取消连接不能撤回已经进入系统或网络的字节。

正常松手与调到 0% 由同一发送器发送一次中性 `manual_input STOP/0`，保留控制代次；快速再次按下仍先发送中性帧再发送新动作，序号递增并遵守发送节拍。没有新操作时不持续发送中性帧。

显式停止、触摸取消、失权及生命周期撤销使用 `manual_stop`，由服务撤销代次并重新停止握手；同一次生命周期撤销产生的重复回调不重复排队停止。停止确认以新鲜 H7 状态为准。App 的局部期限、服务 250 ms 输入期限、150 ms 串口回包超时和固件 300 ms 看门狗不是任意故障下统一的物理停稳时限。

## 验证命令

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
python tools/check_gateway_contract.py --reference reference/surface_robot_op2_underwater_20261112 --forward-boost --continuous-drive --task-map-management
python tools/check_underwater_contract.py --reference reference/surface_robot_op2_underwater_20261112 --captured app/build/reports/contracts/underwater-wire.json
python tools/check_underwater_contract.py --reference reference/surface_robot_op2_underwater_20261112 --captured app/build/reports/contracts/underwater-release-wire.json
```

## 验证结果

| 检查 | 结果 |
|---|---|
| Android 构建与回归 | `assembleDebug testDebugUnitTest lintDebug` 成功；39 个测试类、171 项通过，0 失败／错误／跳过；较 v0.10.0 增加 40 项 |
| 水下状态／发送器 | 21 项：协议与字段类型、250 ms 累计边界、身份与来源变化、过期锁存、50 ms 节拍、中性帧次序、快速松开重按及 0% |
| 本地 WebSocket | 9 项：独立协议、代次与序号、RC／owner 变更、自动重连、GUI 排队、暂时失权合并、幂等生命周期停止、真实捕获的中性释放顺序 |
| 发送积压 | 3 项：运动／停止遇积压立即断开；底层拒绝发送；同步失败回调不得覆盖新绑定 |
| 双机接入与 UI | 4 项 ViewModel 与 3 项新增 Compose 测试：独立连接／停止、水面任务不锁水下、全局停止不取消任务、生命周期；同一触屏双指分别操作与交错松手、多指冲突、身份变化、移出和 0% |
| 水面兼容 | 原有任务、地图、暂停续跑、滚刷／第五电推与触摸回归保留；当前网关 31 组静态报文通过，另验证实际捕获的恢复／取消和地图管理报文；旧 develop 21 组、v0.9.0 基线 26 组通过 |
| 水下契约 | 7 组静态报文与两组真实 WebSocket 捕获由上游 `decode_request` 接受；全部 101 个整数输出接受，46 种非法或畸形报文拒绝 |
| 上游纯协议／会话 | 水面协议 39 项、水下协议／会话 24 项通过；没有运行 ROS 节点、真实串口或 H7 C 固件联测 |
| Lint | 0 错误、13 条既有警告；没有关闭检查或压制告警 |
| 界面 | 已检查 1024 dp 横屏、800 dp 竖屏、任务页和窄水下区测试渲染；两台方向盘及各自停止按钮可同屏操作 |
| APK | `apksigner verify` 通过；包名 `cn.surface.operator`、版本码 11、版本 0.11.0，签名证书与 v0.10.0 相同 |

测试中真实网络队列可能在失权停止时触发预期的积压断连，模拟服务接受自动重连后继续验证旧输入不恢复，没有放宽任何控制时效门槛。上述本地验证不等同于平板或机器人现场验证。

## 交付与现场边界

- `dist/SurfaceOperator-0.11.0-debug.apk`
- `dist/SurfaceOperator-0.11.0-source.zip`：本轮工作区源码快照，不含 SDK 路径、缓存、日志、参考仓库、APK 或签名密钥。
- `dist/SurfaceOperator-0.11.0-SHA256.txt`
- `dist/screenshots-v0.11.0/`：本机测试渲染，不是平板设备截图。

本轮不连接真实机器人，不部署 Nano 或固件。水下需独立 `under_control` 服务和配套 H7 原电机适配版 V6；服务未配置、H7 缺席或遥控器接管时水下不可操作，水面仍可使用。上游部署记录只表示其记录时刻的状态，本次未核实现场当前状态。

现场需分别验收两台同时按住／松开、方向与联动、0% 后重新按键、H7 换向等待、Wi-Fi 中断、串口拔插、RC 接管／归还、App 失焦／锁屏／关闭、各自与全局手动停止，以及水面自动任务期间独立操作水下。软件停止反馈不能替代输出归零与机械停稳检查。
