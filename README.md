# VNLingo

<p align="center">
  <strong>多引擎视觉小说（Galgame）聚合启动器 · 游戏内提取与制卡</strong>
</p>

VNLingo 基于 **Tyranor Next**（多引擎 Galgame 聚合启动器）衍生，在本仓库中新增了完整的**游戏内提取与制卡**功能线：游戏运行时实时提取当前对话文本、配对语音与画面截图，提供查词典、双轨翻译、TTS 朗读、听力模式与 AnkiDroid 一键制卡，配合右缘固定按键组与虚拟鼠标操控。

## 功能总览

### 启动器基座（源自 Tyranor Next）

- 多引擎识别与启动：KiriKiri / ONScripter / Tyrano / Artemis / Siglus / RealLive / AVG32 / UK2 / FVP，Ren'Py 与 RPG Maker XP/VX/VX Ace 外置模块，RPG Maker MV/MZ 网页运行环境
- 外置跳转：PSP（PPSSPP）/ Nintendo Switch（Eden）/ Windows（Winlator）
- 游戏库管理、封面获取、存档镜像、引擎参数调节、后台更新

### 游戏内提取与制卡（本仓库新增）

- **提取面板（剧情文本框）**：当前句栏、配对语音 ♪、翻译行、历史回放、听力模式、暗色圆钮风格
- **查词典**：Yomichan zip / MDX jsonl 导入，多词典管理，最长前缀 + 词形还原
- **翻译双轨**：OpenAI 兼容 API 与 ML Kit 离线翻译（zh/en/ja/ko，模型按需下载），内置翻译测试
- **TTS 朗读**：系统 TextToSpeech 与 MultiTTS HTTP 合成双路径，自动朗读、语速调节
- **AnkiDroid 制卡**：词卡/句卡一键入库（vendored 官方 API）
- **截图**：PixelCopy 直取游戏 Surface（纯游戏画面），主页截图管理页
- **游玩统计**：总/周/月时长三卡 + 每游戏列表
- **右缘按键组**：文本框/截图/设置/音量（百分比）/点击模式/回主页 + 顶部折叠键，虚拟鼠标（D-pad/摇杆 + A 点击）与触摸直传切换

详细设计见 [docs/说明/游戏内提取制卡功能方案.md](docs/说明/游戏内提取制卡功能方案.md)。

## 模块结构

| 模块 | 职责 |
|---|---|
| `app`    | 应用壳：Compose UI、游戏库/扫描/启动编排、设置、封面、更新 |
| `engine` | 引擎层：各引擎宿主 Activity、提取面板与事件桥、词典/翻译/制卡/TTS、Native 插件 |

## 构建

- Android Studio 或命令行：`./gradlew :app:assembleDebug`（JDK 17+，NDK 28）
- 产物：`app/build/outputs/apk/debug/app-debug.apk`

## 文档

- [docs/开发/PROGRESS.md](docs/开发/PROGRESS.md) — 进度记录（会话级）
- [docs/开发/MEMORY.md](docs/开发/MEMORY.md) — 环境事实 / 踩坑 / 决策 / 续作指南
- [docs/说明/游戏内提取制卡功能方案.md](docs/说明/游戏内提取制卡功能方案.md) — 提取功能线方案与实施记录
- `待办.txt` — 后续产品调整计划（未跟踪，本地）

## 致谢

- [Tyranor Next](https://github.com/Weiss-UltimateSavior/Tyranor-Next)（GPL-2.0）：本项目的基座
- 提取功能线参考了 web 端提取实践与 AnkiDroid 官方 API

## 许可

本项目沿用上游 **GPL-2.0** 许可（见 [LICENSE](LICENSE)）。
