# 水面／水下机器人 Android 操作端

原生 Kotlin / Jetpack Compose 平板 App，分别通过 `f1-operator-v1` 水面网关和 `under-operator-v1` 水下服务连接 Nano。主要适配 11.5 英寸横屏；清洗与手动页提供独立水下控制区，两台可同时操作，较窄窗口采用响应式布局。

当前版本 **v0.11.0**（版本码 11）。核对基线为 [`surface_robot/feature/op-2` 的 `fedb6a1`](https://github.com/YYYKblue/surface_robot/commit/fedb6a1768962f8dd7570c4500cf03330718a26e)。本仓库维护 Android app；Windows `operator_app`、水下 `under_control` 与 ROS 仍由上游仓库维护。

## 安装与使用

交付文件：

- APK：`dist/SurfaceOperator-0.11.0-debug.apk`
- 源码：`dist/SurfaceOperator-0.11.0-source.zip`
- 校验：`dist/SurfaceOperator-0.11.0-SHA256.txt`

`dist/` 为本地交付目录，不纳入源码版本管理。APK 使用开发调试签名；同包名、同签名且版本码更高时可覆盖升级，Android 通常不允许直接降级。Gradle 原始产物为 `app/build/outputs/apk/debug/app-debug.apk`。

1. 将 APK 安装到 Android 8.0 或以上设备，按系统提示允许该来源安装。
2. 启动“水面水下机器人”，进入“连接设置”。默认不会自动连接机器人。
3. 离线使用“进入模拟模式”，可演示双机手动、定位、矩形/开放多边任务、提速、建图与地图库。模拟不连接网络或驱动硬件。
4. 实机使用时让平板和 Nano 接入同一局域网，输入 `ws://机器人IP:8765`，连接后核对地图和船头。
5. 手动页可直接按住方向键或开启辅助输出，无需点击申请或启用。自动任务需加载地图、设置初始位姿并等待机器人许可。
6. 水下单独连接 `ws://机器人IP:8766`，也可修改为独立地址；不要求水面在线。清洗作业与手动驾驶页均有水下控制区。两台连接、输出设置和控制权各自独立。

## 水下 H7 与双机控制

- 水下方向键为前进、后退、左转、右转，按住运行、松开停止全部水下输出；0～100% 整数调节，初值 25%。调到 0% 后再提高必须重新按方向。多方向冲突、移出按键和触摸取消清除旧手势。
- 上游 H7 V6 固件定义前进联动前滚刷、所有运动联动顶升目标 40%，换向轮可能先等待 1 秒。App 没有独立水下滚刷或第五电推开关；百分比和回报兼容值不代表实测速度或实际 PWM。
- 水面自动任务、人工暂停、连接中断只影响水面控制；水下断线、串口缺席或遥控接管只影响水下。各设备的本机停止互不影响；顶部“两台手动停止”清除两台手动意图，不取消水面自动任务，也不能覆盖 H7 遥控优先权。
- 切页、失焦、退后台、日志导出及退出清除两台手动意图。重新进入控制页或恢复连接不会恢复旧动作。水下日志归入“水下 H7”来源，可独立筛选和随完整日志导出。
- 水下使用独立 WebSocket、收包处理与约 50 ms 后台发送节拍。GUI 意图期限为 250 ms；H7 `report_age_sec` 加上网络回调到当前的本机等待时间必须严格小于 250 ms。水面的 800 ms 遥测门槛不用于水下。
- 每条水下请求携带协议和设备标识；运动还带当前 `control_generation` 和递增 `seq`。连接、控制代次、板端 epoch 或来源变化，权限／反馈失效、发送积压均撤销旧输入。恢复后需新按键，不重放旧指令。板端反馈与软件停止不等于机械已停稳。

完整水下功能要求 Nano 运行独立 `under_control` 服务并连接配套 H7 原电机适配版 V6；APK 不部署服务或固件。设备缺席时水下区显示不可用，水面仍可使用。上游部署文档中的历史快照不代表当前设备在线状态。本轮适配和验证见 [v0.11.0 水下接入](docs/OP2_UNDERWATER_UPGRADE_2026-11-12.md)。

## 水面功能与操作语义

- 主驱动支持 0～100% 整数 PWM：滑条、±1 微调和 25/50/75% 快捷选择；保留 FRONT/BOTH/BACK 轮组。旧网关回退为 25/50/75 三档。滚刷、第五电推独立设置为 -100～100%，第五电推受机器人许可控制。PWM 不是实测航速。
- 矩形与开放多边清洗的开始、人工暂停／继续、取消；两任务分别配置主电机提速，等待机器人确认后显示配置。继续携带原任务编号，等待回复或同一任务的恢复序号递增。故障停机需取消后排障，不能继续；重连会核对唯一活动／暂停任务。
- 压缩栅格地图、实时船体、待发布初始位姿和分段实际历史轨迹；缩放、平移、全图、船体回中及左墙距离。与新版 Windows 一致，不显示雷达点、预期轨迹、起始范围框或停车点。
- 建图、保存快照、保存并结束、确认放弃、地图列表／预览／加载、失败导出重试、卸载当前地图和确认删除。当前地图需先卸载；待导出快照按名称与快照编号精确删除，区分同名已发布地图。地图文件保存在 Nano，App 不下载 pbstream。
- 诊断日志支持来源筛选、关键词搜索、展开原始详情与完整文本导出；本地保留最近 2000 条，网关可补读的历史受其保留上限约束。

水面松开方向键只停止主驱动；“水面手动停止”清除全部水面手动意图，自动任务使用清洗页的“暂停任务”或“取消任务”。新网关不显示独立“停止任务”按钮；旧网关保留旧入口。触摸取消、移出按键、失焦、退后台、切页、断线或遥测年龄达到 800 ms 时清除水面旧手动意图，恢复后重新按键或开启辅助输出即可。第五电推许可撤销也会清除它的旧意图，恢复许可不会自动恢复旧开关。主驱动调到 0% 会清除方向；再次调高后需重新按方向键，滚刷和第五电推独立。

遥测年龄包括网络回调收包后的等待和网关 `server_queue_ms`，不以解码完成时刻刷新，不直接相减 Nano 与平板墙钟。手动输入由专用后台发送器按约 50 ms 节拍发送，界面只更新当前意图快照与刷新时间；无输出时不持续发 STOP 占用控制端。序号由发送器生成，断线不重放旧运动或离散命令。

界面意图快照达到 250 ms 未刷新时，后台撤销并锁住旧意图；界面恢复后新按键即可继续，刷新旧快照不会复活旧动作。失焦、触摸取消、切页、退后台、日志导出和离散操作均撤销后台意图；重连需完成握手并重新操作。这个 250 ms 期限用于约束后台使用旧意图，不改变 App 800 ms 遥测门槛、网关默认 250 ms 手动输入看门狗或串口桥默认 250 ms 指令有效期。

新协议还检查下位机控制反馈：CTRL/ACK 累计年龄均需小于 400 ms，串口已连接、Nano 控制源、READY/ACTIVE、已解锁且结果 OK。串口连接标识、控制源或 epoch 改变会停止并清除旧动作。新版网关要求控制恢复后先收到中性输入；App 在下一次明确操作时自动先发中性帧，再按发送节拍发送新意图，观察状态本身不占用控制端。

发送 `manual_input`、`manual_stop` 或任务暂停/停止/取消时，若发现仍有待发送积压则立即断开，防止旧帧继续滞留；同步断连回调前先清除本地手动意图，避免旧状态覆盖断开状态。取消连接无法撤回已发到系统或网络中的字节，重连后仍需核对机器人任务状态。

自动任务可能在 App 断线后继续。新版人工暂停保留路线、覆盖记录、墙段、换边目标和提速设置，仍占用任务，禁止手动、另一任务、位姿重设、换图及提速修改。恢复以机器人 `can_resume` 和原始阻断原因判定；位姿漂移、控制源或会话改变等可能使断点失效。只支持控制器持续运行期间的内存断点，旧网关仍需取消后重新开始。

操作超时表示结果待确认，不表示未执行；断线不自动重发继续、删除或其他写命令。取消响应不明超过 5 秒时可再次明确请求取消；其他待确认提示在核对机器人状态后清除。路线执行结束与覆盖完整分别显示，软件暂停或取消反馈不代表船体物理停稳。

地图库“卸载当前地图／返回待机”结束当前定位并清空底图、位姿及轨迹，保留文件；“删除所选地图”需二次确认，允许删除残缺文件和有有效编号的待导出快照。清洗运行、人工暂停、故障未取消或操作待确认时禁止写操作。删除失败也会刷新列表，不能把错误回复当作文件一定未变化。

## 机器人端与兼容性

全功能需要受管入口，独立网关不提供建图与地图管理：

```bash
ros2 launch f1_operator_gateway operator_system.launch.py \
  workspace_root:=/home/nvidia/surface_ws bind_address:=0.0.0.0
```

按上游默认关闭硬件输出进行联调，实机输出遵循 [Nano 部署说明](https://github.com/YYYKblue/surface_robot/blob/fedb6a1768962f8dd7570c4500cf03330718a26e/DEPLOY_NANO.md)。APK 不修改 ROS 参数，也不能将 preview 模式提升为输出许可。

新版接收 `indexed8_zlib` 地图和独立 `tracks`、`diagnostics`，同时兼容旧 JSON 栅格、内嵌轨迹及诊断。提速仅在网关声明 `forward_boost` 能力且控制器状态有效时可用；旧网关缺少能力时禁用相应入口。协议升级和验证见 [op-2 适配记录](docs/OP2_UPGRADE_2026-11-11.md)。

暂停续跑、删除和卸载分别通过 `task_resume`、`map_delete`、`map_unload` 能力协商。需要部署配套 Nano 网关及清洗控制器；本轮这些新增功能不要求改变 F103 协议。360° 雷达和定位参数的更新属于机器人端，不由 APK 修改。详见 [v0.10.0 适配记录](docs/OP2_TASK_MAP_UPGRADE_2026-11-11.md)。

连续输出通过 `continuous_drive_percent_v1` 协商，并动态核对遥测 `drive_output` 的 `min=0/max=100/step=1/firmware_protocol_version=3/available=true`。声明新能力却缺少有效数据或固件未就绪时禁止手动输出；未声明能力的旧网关保留三档。完整功能需要相应 Nano 网关/串口桥与 F103 v3 固件，更新 APK 不会更新机器人或下位机。

v3 的 25/50/75% 对应相对中位 PWM 偏移 100/200/300 μs，100% 对应 400 μs；物理遥控器仍用旧三档的 125/250/375 μs 标度，不能把相同数字视为相同输出。界面分别显示 App 请求和新鲜 F103 回报，RC 回报标为独立档位。手动页调速不修改自动任务参数。

“定位已稳定”不等于所选任务可启动：以该任务的 `can_start` 和原始阻断原因为准。稳定版修复了 Nano 受管启动时空控制器配置的继承问题，需部署相应 ROS 版本；更新 APK 无法替代该修复。

## 构建与检查

环境：JDK 17、Android SDK Platform 36、Build Tools 35.0.0、Gradle 8.13、AGP 8.13.2、Kotlin 2.3.20；min API 26，target API 36。设置 `ANDROID_HOME`，或在未入库的 `local.properties` 中设置 `sdk.dir`。

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

首次构建需访问 Gradle、Google Maven 与 Maven Central。报告位置：

- 单元测试：`app/build/reports/tests/testDebugUnitTest/index.html`
- Lint：`app/build/reports/lint-results-debug.html`

协议检查和回放数据生成使用上游纯 Python 实现，不启动 ROS 或硬件：

```powershell
git clone --depth 1 --branch feature/op-2 https://github.com/YYYKblue/surface_robot.git reference/surface_robot_op2_underwater_20261112
git -C reference/surface_robot_op2_underwater_20261112 rev-parse HEAD
python tools/check_gateway_contract.py --reference reference/surface_robot_op2_underwater_20261112 --forward-boost --continuous-drive --task-map-management
python tools/check_underwater_contract.py --reference reference/surface_robot_op2_underwater_20261112 --captured app/build/reports/contracts/underwater-wire.json
python tools/generate_stream_fixture.py
python tools/generate_diagnostic_fixture.py
python tools/generate_track_fixture.py
```

`feature/op-2` 会继续更新，复现本次协议检查时需使用上述 `fedb6a1` 完整提交；若分支已前进，可在参考仓库 fetch 该提交后切换到它。脚本均支持 `--reference` 指定源码目录。水下脚本只用于包含 `under_control` 的新版参考源码。水面 v0.9.0 基线省略 `--task-map-management`；更旧网关还需省略 `--continuous-drive`，提速前网关还需省略 `--forward-boost`。回放生成器仍默认使用历史 `develop` 参考目录；轨迹几何回放用于模拟几何回归，不会重新启用已移除的预期轨迹图层。

## 工程与文档

```text
app/src/main/java/cn/surface/operator/
  core/                   协议、地图、状态门控与归约
  connection/             WebSocket、手动发送器、有界收包和后台地图解码
  mock/                   无硬件模拟网关
  ui/                     页面、地图与触摸交互
  OperatorViewModel.kt    会话、生命周期、请求与诊断
  MainActivity.kt         Android 入口
app/src/test/             单元、WebSocket、模拟流程与 UI 回归
tools/                   上游契约校验和回放生成
docs/                    审查、部署说明与历史版本记录
```

- [v0.11.0 水下 H7 与独立双机控制](docs/OP2_UNDERWATER_UPGRADE_2026-11-12.md)
- [v0.10.0 暂停续跑与地图删除／卸载](docs/OP2_TASK_MAP_UPGRADE_2026-11-11.md)
- [v0.9.0 连续主驱动输出与直接手动操作](docs/OP2_UPGRADE_2026-11-11.md)
- [v0.8.0 Windows 优化同步与验证记录](docs/WINDOWS_SYNC_2026-11-10.md)
- [v0.7.0 稳定版差异、部署与验证记录](docs/DEVELOP_REVIEW_2026-11-09.md)
- [版本变更历史](docs/CHANGELOG.md)
- [最初设计方案（历史资料）](ANDROID_APP_PLAN.md)

地图最多 400 万格，单条文本接收限制为 32 MiB；压缩地图严格校验尺寸、索引和解压完整性。平板上限低于 Windows，实际大地图内存和时延仍需真机测量。当前为可信局域网 `ws://` 调试版本，TLS 使用系统证书信任。

本版尚未进行平板真机和实船验收。现场仍需验证 Wi-Fi 时延、地图坐标与船头、触摸/失焦/锁屏、Windows 与 Android 控制权交接、重连停止、输出归零时间和惯性漂移。测试渲染图属于本机软件验证，不是设备截图。
