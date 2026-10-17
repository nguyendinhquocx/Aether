# Agent 的电脑 · 实现设计

2026-10-14。对应 Pencil 03A、03B、03C；03D 为未来 Artifact。本文是设计，没有实现或验证应用运行代码。

## 适用范围

仅 iPhone Duo 启用。普通 iPhone、iPad 与 Android 保留原版 UI、浏览器后端和工具事件路径；观察协议默认关闭，由确认 Duo 能力后的 iOS 主 bridge 显式设置 `observe_computer=true`。宽度变化不构成功能启用条件。

## 产品契约

会话与观察面板 1:1。初始为空终端与提示符，不启动等待用户输入的 shell。每条命令和输出追加，后续命令不覆盖前面的记录。仅半折叠态以 SwiftUI 自动展示，不提供打开、关闭、标签或自动跟随切换按钮。用户可查看历史，不能输入、Ctrl-C 或操作网页；控制仍由 Agent 和会话原有停止按钮完成。06 保留用户可操作的原版终端，与观察视图分开。

## 接入实际执行路径

当前 read/bash/edit/write 由 Pi 提供。`SharedRuntimeHostTools.kt` 注明旧 `RuntimeHostToolExecutor` 不再暴露为模型 Host tool；只在旧 executor 加监听不会覆盖真实 Agent 调用。

`pi-bridge/src/bridge.ts` 的 `nativeToolDefinitions` 使用 `createBashToolDefinition` 与 `dynamicBashOperations`。后者选择 local bash 或 Termux；Termux 已把 `options.onData` 接入 runtime operation 的 `onChunk`。`emitAgentSessionEvent` 将工具 start/update/end 转为 tool_call_start/delta/end，`SharedPiChatClient.kt` 接收为 `SharedPiToolEvent`。原工具消息继续使用该路径。

partialResult 不保证是未截断的原始增量。不能把每次累积 outputJson 当作新 chunk 追加，也不能从最终响应伪造直播。

应在实际 bash operations 的 **onData** 边界 tee 数据，原回调继续原样接收，另一路进入观察事件。由 tool definition 的 execute 包装层捕获 toolCallId，建立每次调用独立的 operations 闭包；不能用全局“当前工具 ID”，并发时会串线。命令只执行一次。

| 事件 | 字段 | 处理 |
| --- | --- | --- |
| process_start | sessionId、requestId、toolCallId、executionId、command、cwd、startedAt | 追加命令区块。 |
| process_chunk | executionId、sequence、stream、bytesBase64 | 顺序追加字节，增量解码；stream 标记 stdout/stderr/merged。 |
| process_end | executionId、exitCode、signal、completedAt | 记录结束、取消或非零退出，不删除历史。 |

Alpine local bash 在 Pi Node 进程内执行，不能把承载 bridge 的 Node stdout 当成 shell 输出；应在 bash operations 的 onData tee。Termux 在对应 runtime operation chunk 路径处理。保留原工具响应、超时、取消与退出语义；仅 Duo 会话发送新增观察事件。

观察事件经独立 bridge 协议送到共享会话状态，携带会话与执行归属。绘制可以合并到 30-60Hz，字节按顺序保存。绘制卡顿不阻塞执行；用有界内存和磁盘追加日志，超限按保留策略归档并提供文字提示。

日志按会话保存，恢复面板不丢 scrollback。取消、异常也记录结束。UTF-8 跨 chunk 增量解码，ANSI 状态连续；重连以 sequence 补取，不重复追加。用户停留底部时跟随新输出；向上查看时保持锚点并提示有新输出，滚动不传给远端。

## PTY：按需接入

已有 `RuntimeProcessSpec.interactiveTerminal`、`RuntimeProcess.stdout/stderr: Flow<ByteArray>`、resize/signal，以及 `IosAlpineRuntime` → `AetherRuntimeHost.startProcess(... pseudoTerminal: interactiveTerminal)` 回调。

PTY 会改变 isatty、缓冲、颜色及 stdout/stderr 行为，不能为观察画面把所有 bash 改成 PTY。先接原 pipe 实时流；需要 TTY 的命令再通过实际执行适配器启用 PTY，Agent 超时、取消、退出码和结果截断语义保持。

每条命令使用独立 ANSI 状态与终端区块。clear、光标移动、alternate screen 只影响当前命令 canvas，不能清空会话历史；完成后冻结最终画面与 transcript，下一条追加。用户不能向 PTY 输入或发送 signal。

视口变化优先重排本地 transcript，仅在执行适配器支持安全 resize 时调整真实 PTY 行列。不能因分栏变化重启进程。PTY 必须接实际 bash 子进程，不能给承载 Pi RPC 的 Node 进程开 PTY。

## Chrome：同实例持续只读观测

`SharedChromeManager.kt` 已有 Chromium、Xvnc、noVNC、websockify 启动路径及本地 6080 viewer；Android `AlpineChromeScreen.kt` 已使用 noVNC WebView。

现有 `SharedBrowserDisplayState` 包含截图、URL、标题、光标与更新时间，但画面主要随工具动作刷新，不能视为持续直播。

建议对同一个 Xvnc framebuffer 建立只读 RFB 观察连接，用 noVNC viewOnly 或 framebuffer renderer 持续显示。Agent 继续通过原 CDP 控制；观察端拒绝 keyboard、pointer、剪贴板和 resize 请求，不能只遮透明 UI。实施时核对随 Alpine 安装的 noVNC 版本及 API，不依赖未经核对的 query 参数。

也可复用 CDP screencast 并及时确认帧；需避免与控制请求互相阻塞，不启动第二个 browser。Agent 光标来自已有状态/控制事件，本地触摸不改变远端光标。

浏览器初始启动时确定逻辑 viewport；折叠时先缩放已有画面，不频繁改变 Agent 坐标系。真实 viewport 调整由 controller 串行完成并更新尺寸版本。必要时允许本地缩放/平移，仅供观察。

当前 iOS 的 createPlatformBrowserBackend 返回 IosBrowserBackend，实际为 WKWebView、viewerUrl 为空。要交付 Alpine Chrome，需明确启用并验证对应 Chromium/CDP/RFB 后端。沿用 WK 时应标记为原生浏览器，不能只改标题成 Chrome，也不能把 WK 截图称为 noVNC 直播。这是当前实现差距，本次没有替换后端。

## 前台状态与动效

| 状态 / 触发 | 行为 |
| --- | --- |
| 初始无命令、无浏览器 | 空终端提示符；Alpine 未就绪时说明实际状态，不隐式安装。 |
| 新前台 bash | 追加命令与输出，不清空历史。 |
| Agent browser 操作，获得首帧 | 终端向左滑出，Chrome 从右滑入。 |
| Chrome 前台，后台 stdout 到达 | 只保存日志，不抢画面。 |
| 下一条前台 bash | Chrome 向右滑出，终端从左滑入并定位新命令。 |
| Agent 完成 / 等待用户 | 保留最近画面和历史。 |
| 显式打开未来 Artifact | 进入预览；关闭恢复电脑状态。当前尚无此功能。 |

动画约 240ms，面板内容裁切；标题、折线、安全区与主会话稳定。Reduce Motion 改为短淡入。快速连续操作合并到最新目标，不排队播放多次动画。Chrome 首帧未到时保留终端，活动状态显示连接中，不能切到整块空白。

按 sessionId、requestId 和 toolCallId 维护活动归属。并发后台工具不触发切换。会话切换解绑旧订阅、恢复目标日志；离开半折叠态只解绑 renderer，不停止 Agent、PTY、Chrome 或 Alpine。

断线保留最后一帧/历史并提示连接中断，重试仅恢复观察连接；执行本身失败用真实退出状态，原工具错误行保持。浏览器画面刷新不覆盖终端历史。

## 实现与验证范围

已有 Pi 数据回调、工具更新、Alpine noVNC 和 native PTY 基础；仍需 per-call 输出事件、会话日志、只读 controller、平台后端接入和布局状态管理。不能只改 Compose 就宣称完成。

验证多命令历史、partialResult 不重复追加、ANSI clear 局限当前命令、Unicode、取消和失败、观察慢于执行不阻塞、后台不抢前台、断线不重复执行、无 Artifact 空态、Chrome 持续帧和观察端拒绝输入。Duo 验证新交互；普通 iPhone、iPad 和 Android 分别核对实际入口与原版行为一致。
