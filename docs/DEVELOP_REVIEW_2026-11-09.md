# develop 稳定版审查与 v0.7.0 交付记录

日期：2026-11-09。审查范围为 Android app、ROS 网关/控制器接口与上游 Windows `operator_app`；本次修改和发布对象为 `surface_robot_app`，未修改或部署 Nano/Windows 代码。

## 核对基线与结论

| 对象 | 本次核对版本 |
|---|---|
| ROS / Windows 稳定版 | [`develop`，`f1d712617408acf0a9469e5fedb5763469ba2145`](https://github.com/YYYKblue/surface_robot/commit/f1d712617408acf0a9469e5fedb5763469ba2145) |
| Android v0.6.0 的上游基线 | `feature/Lplus`，`975646a7b448cdf1576a4d80d3706348c95bc132` |
| Android 源码仓库 | [YYYKblue/surface_robot_app](https://github.com/YYYKblue/surface_robot_app) |
| 本次版本 | v0.7.0，versionCode 7 |

对上述两份上游快照按文件比较并规范化换行：`operator_app` 下 12 个 Python 文件、`operator_gateway` 包下 11 个 Python 文件内容一致。因此 v0.6.0 已实现的压缩地图、独立轨迹/诊断消息及原出站控制命令继续适用于该稳定版，无需另造协议或放宽控制期限。

稳定版的重要差异在 ROS 启动链：`operator_system → runtime → operator_mode → operator_bringup` 传入空 `rectangle_config` / `l_config` 时，明确回退各控制器包默认 YAML；指定路径不存在或是目录则报错。源代码见 [operator_bringup.launch.py](https://github.com/YYYKblue/surface_robot/blob/f1d712617408acf0a9469e5fedb5763469ba2145/src/f1_operator_gateway/launch/operator_bringup.launch.py)，上游回归见 [test_launch_config.py](https://github.com/YYYKblue/surface_robot/blob/f1d712617408acf0a9469e5fedb5763469ba2145/src/f1_operator_gateway/test/test_launch_config.py)。这是机器人端配置加载修复，安装新 APK 不会替代部署该修复。

## Android 修复与优化

| 问题 | v0.7.0 处理 | 保留的约束 |
|---|---|---|
| 重新打开/连接 app 默认选择矩形；开放多边任务运行中又锁住任务类型，可能无法在当前页面停止它 | 根据有效遥测选择唯一活动或暂停任务，使操作面板与任务类型对应 | 不自动发送停止、启动或恢复命令；权限、数据新鲜度和请求确认规则仍生效 |
| 第五电推已开启后机器人撤销许可，旧本地意图可能残留 | 撤销许可时清除第五电推意图，恢复许可后需重新明确开启 | 网关仍独立校验许可；客户端不提升输出权限 |
| 手动输入/停止或任务暂停/停止/取消仍可能排在旧发送帧之后；同步断连回调还可能被旧本地状态覆盖 | `manual_input`、`manual_stop`、任务 `pause/stop/cancel` 发现待发送积压时立即断开；回调前先清除本地手动意图 | 不重放旧指令；不能撤回已经发到系统或网络的字节，重连后需核对任务状态 |
| 共享定位已就绪，但所选任务仍不可启动，原因难以辨别 | 启动条件给出中文解释并保留机器人原文 | 不将共享 `localization.ready` 代替任务 `can_start`，不增加本地倒计时放行 |
| README 混合当前说明与旧图层/版本记录 | 分开现行 README、CHANGELOG 和本审查记录；最初方案标为历史 | 历史测试结果只属于其对应版本，不混同本轮验收 |

继承 v0.6.0 的链路处理：遥测/轨迹/地图分别合并待处理快照，操作回复和诊断使用有界队列，地图解析/解压/位图生成在后台进行。800 ms 门槛从实际收包时刻及网关排队年龄计算，迟到解码不恢复新鲜度；地图/轨迹按会话、地图、坐标系与任务编号隔离。详细历史说明见 [CHANGELOG](CHANGELOG.md)。

## 启动不可用的排查顺序

1. 看 app 遥测是否新鲜、是否被另一操作端占用、是否仍有待确认请求。
2. 核对已加载地图、初始位姿和共享定位状态；地图保存成功不代表两类路线都能推导。
3. 查看当前任务自己的 `fresh`、`can_start` 和原始阻断原因。矩形和开放多边控制器各自检查反馈，定位显示稳定时它们的启动状态仍可能不同。
4. 对照实际 Nano 参数和日志，不把所有位姿错误统称为网络延迟。上游标准配置对位姿源时间和最近收到新位姿的时间均有严格期限；不能在平板端放宽门槛解决。
5. 若受管地图加载后控制器配置异常，核对 Nano 是否已部署该稳定版并重新构建/启动。

| 机器人原文 | 含义 |
|---|---|
| `pose receive stream stopped` | 控制器没有持续收到新位姿 |
| `fresh fixed-map base_link pose required` | 控制器没有可用的固定地图船体位姿 |
| `pose stale or future stamped` | 已有位姿时间戳过旧、无效或在未来 |

来源与完整操作说明：[Windows README](https://github.com/YYYKblue/surface_robot/blob/f1d712617408acf0a9469e5fedb5763469ba2145/operator_app/README.md)、[Nano 部署与定位排查](https://github.com/YYYKblue/surface_robot/blob/f1d712617408acf0a9469e5fedb5763469ba2145/DEPLOY_NANO.md)。

## 兼容与部署

- `f1-operator-v1` 保持不变；能力缺失时禁用相应功能。提速通过 `forward_boost` 协商，旧网关不发送新命令。
- 新版 `indexed8_zlib` 地图、独立 `tracks` 与 `diagnostics` 均支持；兼容旧 JSON 栅格、内嵌轨迹和诊断。地图最多 400 万格，单条接收文本限制为 32 MiB。
- 历史控制接口基线包括 `feature/op` 的 `53692ef391fd22bf20ec5b51622ac3b7e92574d4`、`feature/upspeed` 的 `7598964f169a7c21d3e92621431fd92891fc73c2`；本轮实际执行的契约检查以验证记录为准。
- Nano 使用受管 `operator_system.launch.py` 才提供建图及地图库。App 不改写地图文件或机器人参数，不部署 ROS，也不重新打包 Windows exe。
- 固定地图底图在地图会话中保持静态，建图地图默认最多每 2 秒更新一次；位姿独立刷新。地图仅显示底图、实时船体、待发布位姿和实际历史轨迹，移除的雷达/预期图层不在本版本恢复。
- 安装 `SurfaceOperator-0.7.0-debug.apk` 前保留所需诊断日志；本地日志主要保留在进程内。版本码递增，是否能直接覆盖升级仍取决于设备现有包名与签名匹配。

## 验证记录

最终代码执行 `assembleDebug testDebugUnitTest lintDebug` 成功：

| 检查 | 结果 |
|---|---|
| APK 构建 | `cn.surface.operator`，v0.7.0，versionCode 7，min API 26 |
| 全量回归 | 24 个测试类、81 项测试，0 失败、0 错误、0 跳过 |
| Lint | 0 错误、13 警告；包含依赖升级、现有网络/备份配置及 KTX 写法建议，未隐藏警告 |
| 最新网关契约 | `f1d7126` 的真实解码器接受 21 组出站报文 |
| 旧版网关契约 | `1076747` 接受 21 组；`53692ef` 接受原有 17 组 |
| 上游回放 | 由最新上游生成 5 条流消息、5 帧诊断、4 组轨迹几何案例，纳入上述测试 |
| APK 签名 | `apksigner verify` 通过，签名与 v0.6.0 相同；版本码从 6 升至 7 |
| 界面 | 已检查横屏、窄屏和实际轨迹测试渲染；副本保存在 `dist/screenshots-v0.7.0/` |

新增 15 项回归覆盖：活动/暂停任务恢复、实际 WebSocket 停止目标、重连不重放手动输入、第五电推撤权恢复、发送队列阻塞及同步断连、定位与任务启动分离、未知原因和地图不支持原因。任务状态过期时仍保留网关阻断原文，并明确其中可能含控制器上次报告。

交付 APK：`dist/SurfaceOperator-0.7.0-debug.apk`。SHA-256：

```text
29d7c77d0d2e07c9b56c43b105804a23629559ae6969f83133b4ec38a8ba5934
```

源码以 Git 版本标签 [`v0.7.0`](https://github.com/YYYKblue/surface_robot_app/tree/v0.7.0) 定位；本地源码包 `dist/SurfaceOperator-0.7.0-source.zip` 从该提交导出，不含 `local.properties`、签名私钥、日志、构建缓存或 ROS 参考克隆。APK 与源码包的校验文件为 `dist/SurfaceOperator-0.7.0-SHA256.txt`。安装包和测试图保留在本地交付目录；云端保存源码、测试、文档与版本标签。

本记录不能把构建成功、纯 Python 契约通过、Robolectric 测试渲染或 Mock 操作当作设备/实船测试。上游 launch 配置测试需要 ROS 环境；仅阅读其源码不等于已在本机执行。

复现自动检查的命令和报告目录见 [README](../README.md#构建与检查)。回放脚本默认使用 `reference/surface_robot_develop_20261109`，生成数据由该快照的实际地图编码器、轨迹记录器及诊断记录器产生，不连接硬件。

## 现场验收仍需完成

| 项目 | 可核验结果 |
|---|---|
| 重连到开放多边/矩形运行及暂停任务 | 页面显示实际任务；有权限且遥测有效时可发对应停止/取消；不自动发任何任务命令 |
| 第五电推许可撤销、恢复 | 撤销后开关意图清除；许可恢复后不会自动开启 |
| 定位正常但某任务不可启动 | 展示任务原始原因，与 Nano 控制器日志一致；不绕过 `can_start` |
| 地图更新和网络拥塞 | 船体/控制状态能独立更新；过期时停用手动；迟到地图不覆盖新会话 |
| 生命周期和多端 | 抬指、移出、失焦、锁屏、后台、进程强杀及 Windows/Android 交接均不恢复旧运动 |
| 停止和实际运动 | 分别记录请求响应、输出归零、船体停止时间/漂移距离；路线结束与覆盖完整分开确认 |

本版本尚无上述平板真机与实船验收记录。
