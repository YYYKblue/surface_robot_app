# 水面机器人 Android 操作端

原生 Kotlin / Jetpack Compose App。主要按 **11.5 英寸横屏**设计，在可用宽度 ≥900 dp 时显示导航、地图和右侧操作面板，较窄窗口采用上下布局。物理英寸不写死为像素尺寸。

## 运行

交付安装包：`dist/SurfaceOperator-0.1.0-debug.apk`；源码包：`dist/SurfaceOperator-0.1.0-source.zip`。
Gradle 原始构建产物：`app/build/outputs/apk/debug/app-debug.apk`。这是开发调试签名 APK，不是正式发布签名版本。

1. 将 APK 传到 Android 8.0 或以上设备，按系统提示允许该来源安装。
2. 启动“水面机器人”，默认不连接任何服务器。进入“连接设置”。
3. 选择“进入模拟模式”，可以离线操作演示地图、手动输入、初始位姿、两种任务、建图保存与地图库；模拟模式不创建网络连接，也不驱动硬件。
4. 实机时，将平板接入 Nano 所在局域网，填写实际 `ws://机器人IP:8765` 并连接。默认地址只作输入示例，不会自动连接。
5. 先核对地图、船头方向和状态；手动页点“启用手动驾驶”后才能发送手动输入。

模拟清洗：设置初始位姿（地图按住并拖出方向 → 发布）→ 等待模拟定位稳定 → 开始任务 → 约 18 秒后显示路线完成及覆盖缺口。地图库含一个可重试导出的故障样例。模拟中的路线和传感器数据不代表真实控制效果。

## 功能

- WebSocket `f1-operator-v1`，能力协商、单活动端提示、自动重连；不重发旧运动或离散命令。
- 手动方向、25/50/75 PWM 档位、FRONT/BOTH/BACK 轮组、独立滚刷和第五电推（-100～100%，后者受网关许可门控）。
- 初始位姿、定位门控、矩形/L 任务开始、暂停、停止、取消；阶段、原因、路线结束与覆盖结果分开显示。
- 栅格地图、船头、雷达、起始区域、目标段与分段历史轨迹；缩放、平移、全图、船体回中；左墙距离。
- 建图开始、保存快照、保存并结束、确认放弃；地图列表、独立预览、加载、重试导出及机器人端保存路径。
- 遥测过期与操作待确认提示、诊断日志分享导出（单次最多 48,000 字符，超长遥测标明截断）。
- 全功能内置 Mock，默认不驱动硬件。

## 机器人端

接口核对基线：`YYYKblue/surface_robot` / `feature/op`，提交 `53692ef391fd22bf20ec5b51622ac3b7e92574d4`（本次拉取分支仍为该提交）。

本次没有修改 ROS 工作空间。全功能需要受管入口，普通独立网关不提供建图与地图管理：

```bash
ros2 launch f1_operator_gateway operator_system.launch.py \
  workspace_root:=/home/nvidia/surface_ws bind_address:=0.0.0.0
```

按原项目默认保持硬件输出关闭进行联调。App 本身不能把机器人的 preview 模式提升为实际输出许可。

## 操作语义

- 松开方向键仅停止主驱动，滚刷和第五电推独立运行。“停止手动输出”清除所有手动意图，不停止自动任务。
- 触摸取消/移出方向键、失焦、退后台、切换页面、断线或遥测超过 0.8 秒未更新，会取消手动使能。重新进入不会恢复旧输出。
- 按住输入按约 50 ms 周期发送；无运动和无辅助输出时不持续发送 STOP 占用控制端。网络拥塞时停止驾驶，后端原有 0.25 s 输入超时仍负责兜底。
- 自动任务断线后可能继续；App 不把断线显示成任务停止。软件停止与船体因惯性停止不是同一个时刻。
- 暂停不支持断点恢复；取消后重新开始。停止后再次开始产生新任务。
- 操作超时保持“待确认”，不会自动重发。停止响应不明超过 5 秒时允许再次明确请求停止；其余操作可在诊断页、确认网关已不忙且已核对状态后清除本地提示。
- 当前清洗靠近逻辑不做路径搜索/环境碰撞检查；地图目标线不是已验证的避障路线。路线完成不等于覆盖完整。
- 地图保存于机器人端，App 不下载 pbstream；地图预览不叠加当前船体位置。
- 摄像头在原 Windows 端只是占位，本版未加入未经实现的视频功能。

## 构建与检查

环境：JDK 17、Android SDK Platform 36、Build Tools 35.0.0、Gradle 8.13（Wrapper）、AGP 8.13.2、Kotlin 2.3.20。最低 Android API 26，target API 36。当前为局域网自用调试版本，没有宣称完成应用商店发布或 Android 17 target 适配。

设置 `ANDROID_HOME`，或在未纳入版本管理的 `local.properties` 配置 `sdk.dir`。首次构建需要访问 Google Maven / Maven Central / Gradle 分发站点。

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
```

可选：拉取原仓库到被忽略的 `reference/surface_robot`，校验出站报文合同：

```powershell
git clone --depth 1 --branch feature/op https://github.com/YYYKblue/surface_robot.git reference/surface_robot
python tools/check_gateway_contract.py
```

`tools/check_gateway_contract.py` 只调用原项目纯 Python 解码器，不启动 ROS 或连接硬件。

单元测试报告：`app/build/reports/tests/testDebugUnitTest/index.html`。
Lint 报告：`app/build/reports/lint-results-debug.html`。

## 本次验证（2026-11-02）

- `assembleDebug`、`testDebugUnitTest`、`lintDebug` 执行成功。
- 22 项测试通过：18 项协议/坐标/状态门控测试，1 项实际本地 WebSocket 握手及收发测试，1 项 Android 生命周期测试，1 项模拟业务流程测试，1 项 Compose 触控测试。
- 模拟流程涵盖初始位姿与定位等待、矩形暂停/取消、L 任务停止、建图快照/结束、地图预览/加载、失败导出重试；触控测试区分松开方向键保留独立滚刷、取消触摸关闭全部手动意图。
- 17 组出站 JSON 经 `feature/op` 原始 Python 解码器接受。没有启动 ROS 或连接机器人。
- Lint：0 错误、12 警告；包含依赖升级建议、局域网明文协议配置、备份兼容属性和 Kotlin 扩展写法提示。未将警告隐藏或视作零警告。
- Robolectric 原生绘图检查图位于 `app/build/reports/screenshots/`，交付副本位于 `dist/screenshots/`。它们属于本机测试渲染，不能代替真实平板显示及触摸验收。
- ADB 未发现已连接设备；未完成 APK 真机安装、实船联调或现场验收。本次未改动 ROS 工作空间。

## 工程结构

```text
app/src/main/java/cn/surface/operator/
  core/                   协议辅助、地图变换、状态门控与归约
  connection/             WebSocket，禁止旧命令重放与发送积压
  mock/                   纯内存演示网关
  ui/                     平板页面、地图绘制与触摸交互
  OperatorViewModel.kt    会话、生命周期、请求结果和后台解析
  MainActivity.kt         Android 入口与前后台/焦点事件
```

地图最大支持 400 万栅格、单条文本接收最多 32 MiB，超限明确报错。原协议地图为 JSON，实际最大地图需真机做内存与更新延迟测试；后台解析过慢会触发过期门控，不放宽驾驶期限。TLS 使用系统证书信任，现有任意 LAN IP 的 `ws://` 通过网络安全配置放行；当前网关无登录认证，使用可信局域网。

## 尚需现场验收

真机 Wi-Fi 时延和长时间稳定性、真实地图坐标与雷达安装方向、触摸手感、不同 DPI/字体大小、锁屏及进程强杀、Windows/Android 控制权交接、启动与停止竞态、实船输出归零时间和惯性漂移距离。没有这些记录前，不把 APK 构建成功当作实船验收通过。
