<div align="center">
  <img src="docs/icon.png" alt="App Icon" width="100" />
  <h1>Air-Ko</h1>

Android 端 LLM 聊天客户端，基于 <a href="https://github.com/rikkahub/rikkahub">RikkaHub</a>（AGPL-3.0）的衍生分支。
</div>

## 这是什么

Air-Ko 保留上游的全部能力（多服务商接入、助手系统、MCP、技能、工作区、桌面容器、设备控制、语音等），
并在其上做了这些改动：

- **悬浮侧边栏**：VS Code 风格浮层，浮在对话之上；☰ 打开，收起后在对话界面右滑拉出，左滑 / 点遮罩 / 返回键收起；
- **统计与花费**：定点金额（10⁻⁹）、缓存节省、覆盖率提示、模型价格自定义（最长通配匹配），统计页内可进价格编辑页；
- **引导页体验修复**：权限步骤自适应布局（小屏 / 大字体不再裁切错位）；设备控制自检加固（隐藏输入法、自动重试、失败时输出计数诊断）；
- 移除了内置使用文档与「关于」页（Debug 页隐藏入口改为长按「设置」页标题）。

## 下载

到 [Releases](../../releases) 下载 APK：

| 文件 | 适用场景 |
| --- | --- |
| `Air-Ko-<版本>-arm64.apk` | 绝大多数现代手机（推荐，体积小） |
| `Air-Ko-<版本>-universal.apk` | 不确定设备架构时使用（体积大） |

> 当前发布为 **debug 签名构建**（包名 `com.airko.app.debug`），可直接安装体验，与上游 RikkaHub 可共存。
> 自行构建 release 版需要自备签名密钥（见下文）。

## 构建

环境要求：JDK 17+（CI 使用 JetBrains Runtime 21）、Android SDK（compileSdk 37）、
Node 22 + pnpm 11（web-ui 前端子构建需要）。

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

## 发布自己的版本

1. 构建完成后，把 APK 上传到 GitHub Releases。
   注意：**Git 仓库单文件上限 100 MB**，APK 不要提交进仓库；Releases 单文件支持到 2 GB。
2. 想让 GitHub Actions 自动出包，在仓库 `Settings → Secrets and variables → Actions` 配置：
   - `KEY_BASE64`：keystore 的 Base64（Linux/macOS：`base64 -w0 your.keystore`；Windows：`certutil -encode` 或 Git Bash）；
   - `SIGNING_CONFIG`：上面 `local.properties` 的完整内容；
   - `GOOGLE_SERVICES_JSON`：留空即可（本项目未使用 Firebase）。
3. 仓库自带 `.github/workflows/daily-build.yml`（每日构建 → 覆盖 `nightly` 预发布标签）；
   不需要自动构建的话，在 Actions 页面把该 workflow 禁用即可。

## 许可与致谢

- 本项目基于 [RikkaHub](https://github.com/rikkahub/rikkahub)，遵循 **AGPL-3.0**，保留原项目的版权与许可（见 `LICENSE` 与 `LICENSES/`）；
- 衍生版与上游项目无关，问题请在本仓库反馈；
- 本仓库不包含任何 API Key；模型调用的费用与数据边界由你与服务商之间的协议决定。

## 安全提示

设备控制依赖无障碍服务与 Shizuku，可对手机执行真实操作；请只在信任的模型与提示词下开启，
并善用「设备控制 → 审计」页复核每一次操作记录。
