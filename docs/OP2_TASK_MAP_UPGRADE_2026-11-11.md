# v0.10.0 人工暂停续跑与地图管理适配

日期：2026-11-11。Android 版本码 10。上游固定基线为 [`surface_robot/feature/op-2` 的 `8e8f2df146366b7cd32c997d0f64c6df358484bc`](https://github.com/YYYKblue/surface_robot/commit/8e8f2df146366b7cd32c997d0f64c6df358484bc)，交付前再次核对远端仍为该提交。上一版 Android 基线为 `f33743c`。

## 上游变化与适配范围

`e2b597b` 更新 360° 雷达和机器人端定位／感知配置；`8e8f2df` 新增人工暂停续跑、地图删除和卸载。本轮依据实际网关协议、状态机及 Windows 界面同步 Android，不修改或部署 Nano、Windows、雷达参数或 F103。

上游依据：[任务与地图管理说明](https://github.com/YYYKblue/surface_robot/blob/8e8f2df146366b7cd32c997d0f64c6df358484bc/docs/TASK_PAUSE_AND_MAP_MANAGEMENT.md)、[协议解码器](https://github.com/YYYKblue/surface_robot/blob/8e8f2df146366b7cd32c997d0f64c6df358484bc/src/f1_operator_gateway/operator_gateway/protocol.py)、[任务状态机](https://github.com/YYYKblue/surface_robot/blob/8e8f2df146366b7cd32c997d0f64c6df358484bc/src/f1_operator_gateway/operator_gateway/task_control.py)、[地图运行管理](https://github.com/YYYKblue/surface_robot/blob/8e8f2df146366b7cd32c997d0f64c6df358484bc/src/f1_operator_gateway/operator_gateway/runtime.py)。

## 清洗控制

- `task_resume` 能力控制新界面。矩形和开放多边任务均显示开始、暂停／继续及取消，新网关不再显示独立停止按钮；顶部“停止手动输出”只清除手动意图。旧网关保留旧停止入口，不能发送继续命令。
- 继续发送 `task/resume`，包含唯一 `request_id`、当前 `task_type` 和原 `run_id`。必须是新鲜的人工暂停，机器人明确 `can_resume=true`，定位就绪且地图匹配，没有其他任务或待确认操作。界面不自行判定漂移是否可接受。
- `pause_kind=fault` 显示故障停机，禁止继续和重复暂停；人工暂停显示原阶段与恢复阻断原因。保留原文，对漂移超限、定位／感知变化、缺少暂停后墙观测等提供中文解释。
- 人工暂停保留任务进度和提速配置，禁止手动、另一任务、重设位姿、提速修改与地图写操作。即使新版遥测的 `task_busy` 意外未置位，本地看到暂停任务仍保持占用。
- 不乐观改变任务阶段。最终回复或同一 `run_id` 的有效整数 `resume_sequence` 递增才清除继续请求；`ACQUIRING`、相同序号、其他任务编号、畸形序号不确认。超时、重连不重放。继续等待期间仍可明确取消。
- 原有 0～100% 手动 PWM、50 ms 后台发送节拍、250 ms GUI 意图期限、800 ms 遥测门槛、CTRL/ACK 检查及积压断开继续生效。

## 地图卸载与删除

| 操作 | Android 行为 |
|---|---|
| 卸载 | 需要 `map_unload`；仅定位／异常模式、无任务或待确认写操作时可用；发送 `mapping/unload`。机器人确认待机后清空底图、位姿选择和轨迹缓存，文件保留 |
| 删除普通地图 | 需要 `map_delete`；允许删除残缺文件；当前地图必须先卸载；二次确认后发送 `mapping/delete`，含名称、`confirm=true`、空 `snapshot_id` |
| 删除待导出快照 | 列表选择同时绑定名称与 `snapshot_id`，保留同名已发布地图；快照编号必须为 32 位小写十六进制，缺失或无效时禁用 |
| 确认失效 | 选择改变、切页、退后台、断连／重新握手撤销未提交的确认；提交前重新检查时效、权限、任务占用和目标存在性 |
| 操作结果 | 不提前移除列表项；确认成功后移除对应项并刷新，关闭预览且使迟到解码失效。删除失败也刷新列表，不自动重试 |
| 回复丢失 | 保留待确认请求；重连后可由匹配 `runtime.last_operation.request_id` 解决并刷新列表，不重新发送删除 |

上游删除使用文件暂存事务，并非三个文件原子删除；失败或中断可能需要在 Nano 检查 `.delete-*` 恢复清单。App 显示机器人原始错误，不承诺失败意味着文件未变。建图时仍需先保存结束或放弃，才能卸载。没有连接真实机器人执行删除。

## 验证

最终执行 `assembleDebug testDebugUnitTest lintDebug` 成功。

| 检查 | 结果 |
|---|---|
| Android 回归 | 32 个测试类、131 项通过，0 失败／错误／跳过；比 v0.9.0 增加 16 项 |
| 任务状态 | 两种任务人工／故障暂停、权限／定位／地图／时效门控、恢复序号格式与原任务匹配、取消通道、旧能力回退 |
| 地图状态 | 当前图保护、残缺地图、同名已发布图与快照、缺失编号、重复目录身份、确认失效、卸载清图及拒绝旧会话底图 |
| 本地真实 WebSocket | 恢复拒绝后显式重试、超时及重连不重发、等待恢复序号；精确快照删除、取消确认、目标切换、拒绝刷新、丢失回复通过状态恢复及卸载 |
| 模拟流程 | 定位、提速、暂停、继续同一任务、取消、建图保存、预览／加载、重试导出、卸载、确认删除、日志导出；暂停 20 秒不累计执行进度、不记录轨迹，继续后轨迹分段 |
| Lint | 0 错误、13 条既有警告；未关闭检查或压制告警 |
| 最新上游契约 | 31 组固定出站报文通过真实 Python 解码器；另校验从 Android 本地 WebSocket 捕获的继续／取消、删除／列表／卸载报文 |
| 非法值 | 连续主驱动 101 个整数全部接受、8 种非法值拒绝；恢复／快照身份的 10 组非法值拒绝；非布尔真值不构成删除确认 |
| 旧网关 | v0.9.0 基线 `f33743c` 接受原有 26 组；develop `8b8a46c` 接受原有 21 组，不向旧网关发送新增命令 |
| 上游纯协议 | 原始 `test_protocol.py` 的 39 项测试通过；未运行完整 ROS 节点集成或硬件测试 |
| 界面 | Compose 验证继续／故障／旧网关入口、地图卸载与快照确认弹窗；已检查窄控制面板与地图库测试渲染图 |
| APK | `apksigner verify` 通过；包名 `cn.surface.operator`，版本码 10；证书 SHA-256 与 v0.9.0 相同，可覆盖升级 |

复核命令：

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest lintDebug
python tools/check_gateway_contract.py --reference reference/surface_robot_op2_cleaning_20261111 --forward-boost --continuous-drive --task-map-management
python tools/check_gateway_contract.py --reference reference/surface_robot_op2_cleaning_20261111 --task-map-management --captured app/build/reports/contracts/resume-wire.json
python tools/check_gateway_contract.py --reference reference/surface_robot_op2_cleaning_20261111 --task-map-management --captured app/build/reports/contracts/map-management-wire.json
```

## 交付与现场边界

- `dist/SurfaceOperator-0.10.0-debug.apk`
- `dist/SurfaceOperator-0.10.0-source.zip`：从 [`v0.10.0` 标签](https://github.com/YYYKblue/surface_robot_app/tree/v0.10.0) 对应 Git 提交导出的源码，不含 SDK 路径、缓存、日志、参考仓库、APK 或密钥。
- `dist/SurfaceOperator-0.10.0-SHA256.txt`
- `dist/screenshots-v0.10.0/`：本机测试渲染，非设备截图。

源码版本标识为 `v0.10.0`。GitHub 保存源码、测试和文档；APK、源码包、校验文件及测试截图保留在本地 `dist/`。Nano 需更新配套网关、受管运行管理与两个清洗控制器，APK 不会部署它们；本轮新增功能不要求改变 F103 协议。尚未进行平板真机或实船验收。

现场需验证沿墙／换边途中暂停、输出归零与漂移、允许及拒绝恢复、RC 接管和串口重连造成断点失效、App 重连后的原任务识别，以及先卸载再删除当前地图。软件状态确认不能替代物理停稳验证。
