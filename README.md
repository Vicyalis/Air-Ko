# Air-Ko

Android 上的 LLM 聊天客户端，基于 [RikkaHub](https://github.com/rikkahub/rikkahub)（AGPL-3.0）的衍生分支。

工作区设计参考 [TinyContainer](https://github.com/Cateners/tiny_container)，设备控制参考 [Reticle](https://github.com/Bumfuzzle-app/reticle)，统计计价参考 Reasonix。保留上游全部能力，并在此基础上做了这些改造。

## 核心特性

### 对话

- **悬浮式侧边栏**：VS Code 风格浮层，收起后在对话界面右滑即可拉出；面板左滑、点遮罩或按返回键收起；大屏默认展开、窄屏默认收起。
- **树状分支**：重新生成、编辑重发都会产生可切换的候选分支，历史不会丢。
- **临时对话**：一键开始，不写入聊天记录，退出即清理。
- 消息导出、收藏、翻译、朗读、全文搜索。

### 助手与提示词

- 助手系统：系统提示词、生成参数、自定义请求头、正则输出替换、任务模型（标题 / 建议 / 翻译 / OCR / 压缩）。
- 快捷消息、提示词注入 / Lorebook、技能（SKILL.md，按需加载）。
- **应用语言**：跟随系统，或手动选择简体中文、繁體中文、English、日本語、한국어、Русский、العربية。

### 模型与工具

- 多服务商：OpenAI 兼容（Chat Completions / Responses）、Anthropic、Google，支持自定义 Base URL、Header、代理与多 Key 轮换。
- MCP：SSE / Streamable HTTP 传输、OAuth 授权、工具审批与调用。
- 联网搜索：19 家服务商，可自建 SearXNG 或接入自定义 JS 引擎。

### Linux 工作区与桌面

- proot 沙箱 Linux，多发行版（Alpine / Ubuntu / Debian / Arch / Fedora / NixOS），内置终端、文件管理与包管理器。
- **桌面方案**：GNOME、niri、KDE Plasma、Cinnamon、Hyprland，以及 noVNC、code-server、JupyterLab。
- 桌面**默认全屏**、**点击即开**；可设置启动时直接进入桌面 / 终端，并选择具体工作区。
- 工作区文件可**导出到 Android 公共目录**（默认 `/sdcard/Air-Ko!`，目录名与位置可修改）。

### 手机与语音

- 设备控制：无障碍 + Shizuku，默认逐次审批；支持「允许所有权限」与操作审计。
- 语音：5 家 ASR、12 家 TTS，支持语音模式与自动朗读。

### 统计与数据

- 统计与花费：定点金额（1e-9）、缓存节省、覆盖率提示、自定义模型价格（最长通配匹配）。
- 备份 / 恢复，WebDAV 与 S3 同步。

### Harness

- **可调 Harness**：工具循环步数、单次工具输出上限、网络重试次数。默认关闭；开启前会弹出警告，并需等待 5 秒才能确认。

## 下载

到 [Releases](../../releases) 下载 APK：

| 文件 | 适用场景 |
| --- | --- |
| `Air-Ko-<版本>-arm64.apk` | 绝大多数现代手机（推荐，体积小） |
| `Air-Ko-<版本>-universal.apk` | 不确定设备架构时使用（体积大） |

> 当前发布为 **debug 签名构建**（包名 `com.airko.app.debug`），可直接安装体验，与上游 RikkaHub 可共存。
> 自行构建 release 版需要自备签名密钥（见下文）。

## 构建

环境要求：JDK 17+（CI 使用 JetBrains Runtime 21）、Android SDK（compileSdk 37）、Node 22 + pnpm 11（web-ui 前端子构建需要）。

```shell
# Debug 构建（产出 app/build/outputs/apk/debug/）
./gradlew assembleDebug

# Release 构建（需要签名配置）
./gradlew assembleRelease
```

签名配置写在仓库根目录的 `local.properties`（该文件不随仓库分发）：

```properties
storeFile=app.key
storePassword=你的密码
keyAlias=你的别名
keyPassword=你的密码
```

其中 `storeFile` 相对 `app/` 模块目录解析（即 keystore 放在 `app/app.key`）。

## 许可与致谢

- 本项目基于 [RikkaHub](https://github.com/rikkahub/rikkahub)，遵循 **AGPL-3.0**，保留原项目的版权与许可（见 `LICENSE` 与 `LICENSES/`）；
- 衍生版与上游项目无关，问题请在本仓库反馈；
- 本仓库不包含任何 API Key；模型调用的费用与数据边界由你与服务商之间的协议决定。

## 安全提示

设备控制依赖无障碍服务与 Shizuku，可对手机执行真实操作；请只在信任的模型与提示词下开启，
并善用「设备控制 → 审计」页复核每一次操作记录。
