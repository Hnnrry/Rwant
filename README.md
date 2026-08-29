# Rwant · 给每一个 AI 的嘴

一个安卓**悬浮窗 AI 嘴**——独立的 App，不依赖任何"手"。它接的是**任何会自己说话的 AI**（比如你 PC 端的 DeepSeek 应用），只负责：

- 把 AI 说的话，用**悬浮球气泡 + TTS 朗读**播出来（它就是 AI 的"嘴"）
- 把用户说的话，用**语音识别（ASR）**收回来，回传给 AI（它就是 AI 的"耳朵"）

与 Ridea（"手"）同构但完全独立：各自安装、各自端口、各自令牌。Rwant 不思考，只传声。

---

## 一、安装

1. 打开仓库 **Actions** 页面：https://github.com/Hnnrry/Rwant/actions
2. 进入最近一次绿色 ✅ 的 `build` 运行记录
3. 在 **Artifacts** 区下载 `Rwant-debug-apk`（zip，内含 `app-debug.apk`）
4. 手机允许「未知来源」安装，装好打开
5. 首次打开会引导授权（见下文权限）

> 当前为 debug 签名，未上架应用商店。每次 push 到 `main` 都会自动云端重编译并更新 artifact。

---

## 二、权限（六件套）

| 权限 | 用途 | 必须 |
|------|------|------|
| 悬浮窗 `SYSTEM_ALERT_WINDOW` | 在所有 App 上方显示悬浮球/气泡 | ✅ 运行时申请 |
| 麦克风 `RECORD_AUDIO` | 按住说话 / 自动倾听 | ✅ 运行时申请 |
| 通知 `POST_NOTIFICATIONS` | 前台服务常驻 + 连接请求弹窗 | ✅ 运行时申请 |
| 网络 | 本机 MCP 通道（连 AI） | ✅ |
| 自启动 `RECEIVE_BOOT_COMPLETED` | 开机恢复通道 | 可选 |
| 后台弹出界面 | 部分 ROM 后台显示 | 可选 |

> 提示：部分国产 ROM 对后台悬浮窗限制较严，建议把 Rwant 加入**电池白名单 / 自启动管理**。

---

## 三、AI 怎么接进来（MCP 通道）

Rwant 在手机**本机**起一个 MCP Server，协议是标准 **JSON-RPC 2.0 + SSE**。

1. 手机和跑 AI 的设备在**同一局域网**
2. Rwant 设置页打开「接收 AI 连接」，会显示：
   - 通道地址：`http://<手机局域网IP>:8766/mcp`
   - 访问令牌（Bearer Token，设置页可见）
3. AI 端（如 PC 端 Python 应用）按标准 MCP 流程连：
   - `POST /mcp` + `Authorization: Bearer <token>` 发 `initialize` → `tools/list` → `tools/call`
   - 会话头 `Mcp-Session-Id` 由 `initialize` 响应返回
   - `GET /mcp?stream`（带 `Accept: text/event-stream`）订阅 SSE，**用户说的话实时推回 AI**
4. **首次连接**，手机弹「XX 请求连接」确认框，授权默认到**当天 24:00**；也可在设置页手动撤销

### 9 个工具（AI 调这些"说话/听"）

| 工具 | 作用 |
|------|------|
| `speak` | AI 说话：朗读 + 弹气泡（参数 `text`） |
| `speak_quiet` | 只弹气泡、不发声（静音模式，参数 `text`） |
| `listen_start` | 开始听（参数 `mode`: `push` 按住说 / `auto` 自动续听） |
| `listen_stop` | 停止听 |
| `get_transcript` | 取最近一次用户说的话 |
| `clear_bubble` | 清空对话气泡 |
| `set_mood` | 设悬浮球状态色：`idle` / `thinking` / `speaking` |
| `emergency_stop` | 急停（立即停 TTS/ASR，持久化+震动+通知） |
| `get_logs` | 取本地对话日志（外加 `get_status` 查运行状态） |

---

## 四、日常长什么样

- 任意 App 上方挂一个紫色悬浮球：空闲半透明、AI 思考呼吸闪烁、说话高亮
- 点开悬浮球展开深色对话面板，翻最近 50 条，可清空
- 球旁小圆钮 = 麦克风，按住说话
- 设置页：连接总开关、通道地址/令牌、权限六件套状态、自动倾听、音量语速、清记录

---

## 五、技术栈

- 原生 Kotlin 单模块，零三方依赖（HTTP/SSE/JSON 全手写）
- 包名 `com.hnnrry.rwant`，`minSdk 26` / `targetSdk 34`
- Material3 深色主题；前台服务保活；端口 **8766**
- 编译：`GitHub Actions` 云端出 APK（artifact 名 `Rwant-debug-apk`）

## 六、已知限制

- debug 签名，未上架商店
- TTS/ASR 用**系统引擎**：中文需手机已安装中文 TTS 语音包
- 悬浮窗在部分 ROM 后台可能被回收，需电池白名单
