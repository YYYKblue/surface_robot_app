# 水面机器人 Android 操作端

原生 Kotlin / Jetpack Compose 平板 App，通过 `f1-operator-v1` WebSocket 网关连接 Nano。主要适配 11.5 英寸横屏；可用宽度 ≥900 dp 时采用导航、地图、操作面板三列布局，较窄窗口采用上下布局。

当前版本 **v0.8.0**（版本码 8）。ROS 与 Windows 核对基线为 [`surface_robot/develop` 的 `8b8a46c`](https://github.com/YYYKblue/surface_robot/commit/8b8a46c19721ef0aa41afa4b36193690c69655ae)。本仓库维护 Android app；Windows `operator_app` 与 ROS 仍由上游仓库维护。

## 安装与使用

交付文件：

- APK：`dist/SurfaceOperator-0.8.0-debug.apk`
- 源码：`dist/SurfaceOperator-0.8.0-source.zip`
- 校验：`dist/SurfaceOperator-0.8.0-SHA256.txt`

`dist/` 为本地交付目录，不纳入源码版本管理。APK 使用开发调试签名；同包名、同签名且版本码更高时可覆盖升级，Android 通常不允许直接降级。Gradle 原始产物为 `app/build/outputs/apk/debug/app-debug.apk`。

1. 将 APK 安装到 Android 8.0 或以上设备，按系统提示允许该来源安装。
2. 启动“水面机器人”，进入“连接设置”。默认不会自动连接机器人。
3. 离线使用“进入模拟模式”，可演示定位、手动、矩形/开放多边任务、提速、建图与地图库。模拟不连接网络或驱动硬件。
4. 实机使用时让平板和 Nano 接入同一局域网，输入 `ws://机器人IP:8765`，连接后核对地图和船头。
5. 手动页需先点击“启用手动驾驶”。自动任务需加载地图、设置初始位姿并等待机器人许可。

## 当前功能与操作语义

- 手动方向、25/50/75 PWM 档位和 FRONT/BOTH/BACK 轮组；滚刷、第五电推独立设置为 -100～100%，第五电推受机器人许可控制。PWM 不是实测航速。
- 矩形与开放多边清洗的开始、暂停、停止、取消；两任务分别配置主电机提速，等待机器人确认后显示配置。重连会核对唯一活动/暂停任务，停止入口对应实际任务。
- 压缩栅格地图、实时船体、待发布初始位姿和分段实际历史轨迹；缩放、平移、全图、船体回中及左墙距离。与新版 Windows 一致，不显示雷达点、预期轨迹、起始范围框或停车点。
- 建图、保存快照、保存并结束、确认放弃、地图列表/预览/加载、失败导出重试。地图文件保存在 Nano，App 不下载 pbstream。
- 诊断日志支持来源筛选、关键词搜索、展开原始详情与完整文本导出；本地保留最近 2000 条，网关可补读的历史受其保留上限约束。

松开方向键只停止主驱动；“停止手动输出”清除全部手动意图，自动任务使用“停止任务”。触摸取消、移出按键、失焦、退后台、切页、断线或遥测年龄达到 800 ms 时取消手动使能，恢复后需重新启用。第五电推许可撤销也会清除它的旧意图，恢复许可不会自动恢复旧开关。

遥测年龄包括网络回调收包后的等待和网关 `server_queue_ms`，不以解码完成时刻刷新，不直接相减 Nano 与平板墙钟。手动输入由专用后台发送器按约 50 ms 节拍发送，界面只更新当前意图快照与刷新时间；无输出时不持续发 STOP 占用控制端。序号由发送器生成，断线不重放旧运动或离散命令。

界面意图快照达到 250 ms 未刷新时，后台撤销手动意图并锁住该次驾驶；界面恢复后需重新明确点击“启用手动驾驶”，刷新旧快照不会复活旧动作。失焦、触摸取消、切页、退后台、日志导出和离散操作均撤销后台意图；重连需完成握手并重新启用。这个 250 ms 期限用于约束后台使用旧意图，不改变 App 800 ms 遥测门槛、网关默认 250 ms 手动输入看门狗或串口桥默认 250 ms 指令有效期。

发送 `manual_input`、`manual_stop` 或任务暂停/停止/取消时，若发现仍有待发送积压则立即断开，防止旧帧继续滞留；同步断连回调前先清除本地手动意图，避免旧状态覆盖断开状态。取消连接无法撤回已发到系统或网络中的字节，重连后仍需核对机器人任务状态。

自动任务可能在 App 断线后继续；暂停不支持断点恢复，需取消后重新开始。操作超时表示结果待确认，不表示未执行。停止响应不明超过 5 秒时可再次明确请求停止；其他待确认提示在核对机器人状态后清除。路线执行结束与覆盖完整分别显示，软件停止反馈不代表船体物理停稳。

## 机器人端与兼容性

全功能需要受管入口，独立网关不提供建图与地图管理：

```bash
ros2 launch f1_operator_gateway operator_system.launch.py \
  workspace_root:=/home/nvidia/surface_ws bind_address:=0.0.0.0
```

按上游默认关闭硬件输出进行联调，实机输出遵循 [Nano 部署说明](https://github.com/YYYKblue/surface_robot/blob/8b8a46c19721ef0aa41afa4b36193690c69655ae/DEPLOY_NANO.md)。APK 不修改 ROS 参数，也不能将 preview 模式提升为输出许可。

新版接收 `indexed8_zlib` 地图和独立 `tracks`、`diagnostics`，同时兼容旧 JSON 栅格、内嵌轨迹及诊断。提速仅在网关声明 `forward_boost` 能力且控制器状态有效时可用；旧网关缺少能力时禁用相应入口。本轮仅上游 Windows 发送调度发生变化，ROS 接口未变；对照和验证见 [Windows 优化同步记录](docs/WINDOWS_SYNC_2026-11-10.md)，此前接口基线见 [稳定版审查记录](docs/DEVELOP_REVIEW_2026-11-09.md)。

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
git clone --depth 1 --branch develop https://github.com/YYYKblue/surface_robot.git reference/surface_robot_develop_20261109
git -C reference/surface_robot_develop_20261109 rev-parse HEAD
python tools/check_gateway_contract.py --forward-boost
python tools/generate_stream_fixture.py
python tools/generate_diagnostic_fixture.py
python tools/generate_track_fixture.py
```

`develop` 会继续更新，复现本次结果时需使用上述 `8b8a46c` 完整提交；若分支已前进，可在参考仓库 fetch 该提交后切换到它。参考目录名仍沿用 `_20261109`，实际版本以 `rev-parse HEAD` 为准。四个脚本均支持 `--reference` 指定源码目录。提速前网关的契约检查省略 `--forward-boost`。轨迹几何回放用于模拟几何回归，不会重新启用已移除的预期轨迹图层。

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

- [v0.8.0 Windows 优化同步与验证记录](docs/WINDOWS_SYNC_2026-11-10.md)
- [v0.7.0 稳定版差异、部署与验证记录](docs/DEVELOP_REVIEW_2026-11-09.md)
- [版本变更历史](docs/CHANGELOG.md)
- [最初设计方案（历史资料）](ANDROID_APP_PLAN.md)

地图最多 400 万格，单条文本接收限制为 32 MiB；压缩地图严格校验尺寸、索引和解压完整性。平板上限低于 Windows，实际大地图内存和时延仍需真机测量。当前为可信局域网 `ws://` 调试版本，TLS 使用系统证书信任。

本版尚未进行平板真机和实船验收。现场仍需验证 Wi-Fi 时延、地图坐标与船头、触摸/失焦/锁屏、Windows 与 Android 控制权交接、重连停止、输出归零时间和惯性漂移。测试渲染图属于本机软件验证，不是设备截图。
