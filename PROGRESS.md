# VNLingo 会话进度记录

> 本文件由 AI 会话维护，记录本仓库功能线的实施进度。配合 `MEMORY.md`（环境/踩坑/决策）与
> `docs/游戏内提取制卡功能方案.md`（提取功能线方案）阅读。
> 更新时间：2026-10-05

## 2026-10-05 会话（三）：KRKR 文本提取基建（分支 feat/extract-suite）

目标「让 krkr 所有版本支持文本读取」。结论：**接收端全链路就绪，对白数据源仍需带钩内核**
（krkr2-main 源码构建；本仓与本地 Tyranor-Next 仓的 libgame*.so 均无提取导出）。

### 已落地

- **接收端补全**：KRKR 宿主（KirikiroidLauncherBaseActivity，覆盖 1.3.9/1.3.4/1.2.6）此前只建了
  facade 没装面板——现于游戏揭晓后把 OnsExtractPanel + 右缘按键组装入 mFrameLayout 顶层
  （主线程，遮罩动画结束后），dispatchKeyEvent 前置 GamepadRemap + 面板按键路由。
  模拟器实测：kazurauta（KAG3.32）游戏内按键组/设置弹窗/面板本体均正常。
- **Label::setString 钩子**（krkr_extract_hook.cpp，随 libkrkr_bridge_v2 构建）：三版本导出
  同名符号；shadowhook inline hook 在 :kirikiri2 进程对插件目录 dlopen 的内核不可用
  （errno=12 "Init linker mod failed"），退 **Label 虚表补丁**（mprotect 改 RELRO 槽，
  slotIndex=173 实测），控制台文本→NativeBridge.onKrkrText→OnsExtractBridge 全链路实测通过。
- **TJS 发射器 v8**（krExtractEmitterScriptV8，设置 kr_extract_tjs 开启时随 patch.tjs 注入）：
  包装 KAGParser 把对白以 [TNEXT] 标记发往 Debug.message 控制台；Java 侧 onKrkrText 仅放行
  [TNEXT] 行。**实测 kazurauta 开启后引导期 SIGSEGV（KAGParser 全局替换引发，与历史结论
  一致）——默认关闭，标注实验性**；Ex 插件游戏（KAGParserEx）不受替换影响（无提取亦无风险）。
- 探针结论（诊断代码保留）：GdipDrawString 零调用（GOT 钩子验证）；层文本渲染完全走内部
  FreeType 管线（符号未导出、调用不走 PLT），运行时不可拦截。

### 三引擎实测（会话四，模拟器同一构建）

- **ONS（esg＝エルフの守護者）**：游戏运行 ✓、左右按键组 ✓、提取面板打开 ✓、
  **文本提取实测 ✓**（面板显示正确日文对白——游戏画面因 gbk 编码乱码，提取桥按脚本
  字节嗅探反而正确）；esg 开场卡姓名输入框未达语音行，语音配对沿用源仓实测结论。
- **Artemis（blossom，clean 内核）**：游戏运行 ✓、面板/按键组 ✓；GOT 补丁钩子安装成功
  （不再依赖 shadowhook inline——重启模拟器后 shadowhook_init/hook 全面 errno=12），
  已拦截到部分调用（频道号"2"、内部标签"znotify"）并加了质量过滤（音频名取路径样参数、
  文本仅放行含 CJK 行），但**对白正文与语音名在本环境未持续捕获**——重启前同一机制
  （shadowhook inline + ANativeActivity 早装）曾完整捕获文本+♪ fem_him_00281.ogg，
  疑与环境状态相关（见 MEMORY errno=12 条目）。ym（夜明前的琉璃色）开场制作名单
  循环回标题，不适合快速自动化。
- **KRKR（kazurauta）**：游戏运行 ✓、面板/按键组/设置弹窗 ✓；对白需带钩内核（基建已备）。

### 未解（下次会话从这里看）

- 对白数据源 = 带 extract_sink 的 krkr2 内核（krkr2-main 源码仓构建后替换 nativeplugins/
  kirikiroid2 的 libgame*.so 并 bump pluginVersion），Java 侧零改动即生效（KR2Activity
  .setExtractListener 已武装）。krkrsdl3 同理需要其内核带钩。
- Artemis 对白/语音在部分环境未持续捕获：shadowhook inline（errno=12 环境相关）与
  GOT 补丁（已拦截内部调用但对白主通道未过 GOT）均未完全命中，需定位 clean 内核
  对白/语音的实际调用点（可用 krkr 式全符号 GOT 表或内核源码）。
- 模拟器干扰记录：宿主侧 root 脚本会周期拉起 com.vnlingo.app（用户自己的 VNLingo 悬浮字幕
  应用，与本项目同名相关）与 ScummVM，且会 `pm disable` 本应用——测试前
  `pm default-state com.tyranor.next` + force-stop 干扰应用。

## 2026-10-05 会话（二）：产品调整四项 + Artemis 两修复（分支 feat/extract-suite）

执行 `待办.txt` 产品调整，按用户补充保留 Artemis 并修复其两处缺陷。全部在模拟器
（Pixel_6，`--abi arm64-v8a` 安装）实测通过。

### 一、导航重构（待办①）

- 底部导航 **游戏 / 词典 / 设置** 三栏：删「首页」Tab（`HomeScreen.kt` 移除），
  删「引擎」Tab；新增 `ui/dict/DictionaryScreen.kt` 词典页（多词典启停/删除/设为当前 +
  SAF 导入 Yomichan zip / MDX jsonl，复用 engine `OnsDictStore`，数据与游戏内查词同库）。
- 引擎页并入设置页：设置页「引擎」条目 → 新 `EngineManageActivity` 合并页
  （顶部引擎细分设置入口卡片 + 引擎管理列表，`EngineScreen` 增加 headerItem 插槽）；
  删 `EngineSettingsMenuActivity`（`EngineSettingsKind` 枚举迁至 EngineManageActivity.kt）。

### 二、引擎支持裁剪（待办①）

只保留 krkr / ons / rpgmaker 系列 / tyrano / renpy / web（VN、WebOther）/ artemis：

- `EngineLauncher.supportedEngines` 收敛；删 Siglus / Framebuffer(RealLive/AVG32/UK2) / FVP /
  Winlator(YU-RIS、PC) 启动分支与 `YurisLaunchFiles.kt`；历史库残留条目 buildIntent 走 error 分支。
- `EngineScanner`：删除上述引擎与 PSP/Switch ROM 的特征识别与 ROM 入库分支（含 readHead 管线）。
- UI：引擎页删「主机」分类与外置跳转（PPSSPP/Eden/Winlator）入口与弹窗；删 PC 手动添加
  （`PcGameAddDialog.kt` 移除、GameScreen 顶栏入口移除）；设置种类删 SIGLUS / FRAMEBUFFER /
  FVP / PPSSPP / WINLATOR（详情页对应分支一并移除）。外置模拟器基础设施类保留（死代码，不再有入口）。

### 三、设置归位（待办②）

- 游戏内面板设置弹窗只留「朗读 + 显示」，加「已移至应用设置」提示（`engine_ons_settings_moved_hint`）。
- 新 `OnsExtractSettingsDialogs.java`（engine）：翻译设置弹窗宿主无关版（引擎切换 / API 配置 /
  ML Kit 模型管理 / 翻译测试，与原面板同款样式同数据源）；应用设置页新增「翻译设置」「云同步」条目。
- `OnsTranslateClient.prefs` 改 `MODE_MULTI_PROCESS`：应用主进程写入、引擎进程每次读取重载。

### 四、手柄按键重映射（待办③）

- engine `GamepadRemap`（@JvmStatic）：prefs `gamepad_remap` 持久化 + 内存快照，
  `apply()` 在 dispatchKeyEvent 最前端把手柄源按键替换为目标（键盘或其他手柄键）。
- 宿主接线五处：ONScripter / TyranoActivity / RpgMakerActivity / ArtemisActivity / KR2Activity
  （dispatchKeyEvent 前置 remap + onResume refresh）。
- app `ui/gamepad/GamepadSettingsActivity`：16 个标准手柄键列表，捕获式配置
  （点「映射」→ 按下目标键即存，BACK 取消，支持删除）；设置页「手柄设置」入口。

### 五、Artemis 两修复（用户补充）

1. **面板读不到文本/语音**：根因是随包 Artemis 内核（含 clean 内核 .so）均无 extract_bridge
   发射器（符号表验证，外部内核源码仓的带桥构建未随仓分发）。修复：新
   `artemis_extract_hook.cpp`（并入 artemis_loader 库），dlopen shadowhook（jniLibs 真库，1.1.1）
   对 clean 内核 `artc::Compositor::SetMessageLayered`（文本）与
   `artc::AudioChannels::Play`（语音名）做 entry inline hook，经 `onArtemisExtract` 上行；
   facade 增加 BGM 名过滤与游戏目录语音兜底探测。**限制**：语音名可配对显示，
   packed 游戏语音字节暂不可播（语音副本落盘机制未随内核分发）；官方 revision 内核不挂钩。
2. **关闭面板后触摸失灵**：根因是 `WindowOverlayHost` 触摸放行只靠 OnLayoutChangeListener，
   VISIBLE→GONE 时 bounds 不变、回调不触发，全屏覆盖窗停留在可触摸态吃掉全部输入。
   修复：`OnsExtractPanel.setPanelVisibilityHook`，togglePanel 后显式同步（布局监听保留兜底）。

### 六、模拟器回归（全部通过）

- 导航三栏 / 词典页空态 / 设置页新条目 / 手柄捕获式映射（A→键盘·U）+ 删除 + prefs 落盘。
- blossom（Artemis clean 内核）真机流程：启动 → 面板显示当前句 + ♪ fem_him_00281.ogg →
  关闭面板后点按正常推进剧情 → 主页键确认弹窗正常。
- 注意：该 AVD 有宿主侧遗留 root 脚本周期性拉起 ScummVM 干扰前台（`am_proc_start from uid 0`），
  与本项目无关；ARM 转译镜像必须 `adb install -r --abi arm64-v8a` 否则 :artemis.clean 进程缺
  libartemis_audio_bridge.so 崩溃。

## 2026-10-05 会话（一）：提取/制卡功能线整体移植（分支 feat/extract-suite，4+1 提交）

背景：把本地仓库 Tyranor-Next（同源项目）的「游戏内提取 + 制卡」功能线整体移植到 VNLingo
（上游最新 fork，基线在提取功能线之前）。原则：**只增不删**（上游既有功能全保留）、
重复处先对比、每次修改按仓库文档规范补充说明。

### 一、移植前判定

- 加速克隆：SSH 不通，走 `gh-proxy.com` 镜像浅克隆（后续 `fetch --depth 100` 补历史）。
- 基线判定：VNLingo = Tyranor Next 上游最新 fork（含 PSP/NS 外置跳转、krkrsdl3 等本地没有的演进）；
  与本地仓库**无共同祖先**（本地是基线快照 + 功能提交）→ 改用文件级 diff + 我方提交史
  （b28feb1..HEAD，58 个提交 95 文件）分类搬运方向。
- `krkr_bridge.cpp`（KRKR Gdip 钩子）两边内容已一致 → 上游已含，无需搬。

### 二、提交清单

| 提交 | 内容 |
|---|---|
| `5dd5ded feat(engine)` | 引擎层全套：提取面板/事件桥/词典/还原器/翻译双轨/MultiTTS/截图/存档面板/右缘按键组/虚拟鼠标/编码探测/运行时可拆卸/语言回退 + Anki vendored API + 25 图标 + `__tn_extract.js` + 带钩子 `libonsyuri.so`（pluginVersion 3→4）+ 宿主接线 + 三语言文案 + ML Kit 依赖 |
| `7b923c4 feat(app)` | 截图管理页/游玩统计页/运行时管理接线/编码 auto 设置项/主页截图入口/清单与 FileProvider/三语言文案 |
| `008bb8f docs` | `docs/游戏内提取制卡功能方案.md` + CONTEXT.md 术语节（后随文档重构移除，术语并入方案文档） |
| `e507259 refactor` | 评审调整：移除游戏内存档面板（条目管理页已有存档导入导出）与 ONS 编码 auto（保持 gbk/sjis/utf8 原设置），删 24 条三语言文案与未用图标 |
| `docs: 建立自有文档` | 删上游继承文档（README/AGENT/CONTEXT/CONTRIBUTING/docs 方案×30/skills），建立本 README + PROGRESS + MEMORY，术语并入方案文档 |

### 三、搬运方法（复用价值高）

1. 独有文件（63 源文件 + 资产 + so）直接复制；
2. 共有文件按「我方改过 ∩ 与 VNLingo 不同」圈出 30 个 → 生成 `git diff b28feb1 -- <file>` 补丁 →
   在 VNLingo `patch -p1 --fuzz=3` 应用（23/26 一次通过，2 个路径还原笔误手工补，1 个拆分提交）；
3. 按只增不删策略跳过三类删减类改动：App 图标替换、设置页「加入群组」入口移除、卡片占位文字简化。

### 四、评审调整（e507259）

- 存档导入导出已由条目管理页承担 → 删游戏内 `OnsSavePanel` + 右缘存档键 + 五处宿主安装；
  保留 `OnsSaveCloud`（提取面板设置分区云同步）与 facade 存档数据通道（SaveZipUtil/WebSaveArchive）。
- ONS 编码保持原 `gbk/sjis/utf8` 三选项，不引入 auto 探测；`OnsEncodingDetect` 未移植。

### 五、样式调整

- 提取面板（剧情文本框）改为**暗色圆钮**配色（对齐参考样式）：面板底 `0xE6101010`、
  按钮正圆 `0xFF2E2E2E` 白图标、语音行青色 `0xFF2DD4BF`、正文白、翻译浅灰、
  释义区暗色 chip、历史分隔线半透明白；弹窗（设置/词典/翻译测试）维持浅色。

### 六、验证状态

- ✅ `:app:assembleDebug` 全程构建通过（含 ML Kit 首次拉取）。
- ⏸ 运行时行为未在模拟器回归（VNLingo 与 Tyranor-Next 同包名 `com.tyranor.next`，安装互覆）；
  功能行为以来源仓库的实测为准（ONS 面板/查词/制卡/翻译/TTS/截图/点击模式均已实测）。

## 进行中 ⏸

- `feat/extract-suite` 分支本次新增 3 个提交（Artemis 修复 / 导航与裁剪 / 手柄重映射）未推送。
- 遗留：packed Artemis 游戏语音字节不可播（需内核侧语音副本机制）；官方 revision 内核无提取；
  外置模拟器（PPSSPP/Eden/Winlator）基础设施代码保留但无入口。
