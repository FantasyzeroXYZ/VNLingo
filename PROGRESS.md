# VNLingo 会话进度记录

> 本文件由 AI 会话维护，记录本仓库功能线的实施进度。配合 `MEMORY.md`（环境/踩坑/决策）与
> `docs/游戏内提取制卡功能方案.md`（提取功能线方案）阅读。
> 更新时间：2026-10-05

## 2026-10-05 会话（六）：KRKR 纯运行时 hook 对白提取（用户方向：不走内部输出）

- **推翻旧结论**：此前记录「层文本渲染符号未导出、调用不走 PLT、运行时不可拦截」
  有误——`llvm-nm -D` 实测 libgame*.so **导出全套 FreeType API**（FT_* 150+ 符号），
  且 JUMP_SLOT 重定位经自身 GOT（158 个 FT_ 槽），GOT 补丁直接可拦。
- **对白渲染路径**（krkr2-main FreeType.cpp:651/670 源码证实）：
  `FT_Get_Char_Index(face, unicode)` → `FT_Load_Glyph(face, index)`；
  `FT_Load_Char` 从不被调（首版探针挂它因此零命中）。每个渲染字符的 Unicode 码点
  全部流经 FT_Get_Char_Index——LunaTranslator「GetGlyphOutline 通用卡点」的 KRKR 等价物。
- **打字机重绘状态机**：KAG 每加一字整行重绘，码点流呈前缀链；known 句 + 重绘轮
  位置匹配，300ms 停顿结算。kazurauta 实测：旁白行『ずっと一緒にいようね』与
  叙述行完全干净；说话人行（【詩折】开头）因名字层+正文层双层重绘含重复段，
  还原精化待续（ft raw 原始流日志已备，离线调试即可）。
- 通道：`[FTLN]` 前缀经 NativeBridge.onKrkrText 双通道分发（与 [TNEXT] 并列）。
- 提交 fc94f92。遗留：重绘链去重精化；FT_Load_Char 钩子保留但不再记录。

## 2026-10-05 会话（六追加）：候选选择悬浮框 + 主动结算

- **候选选择（LunaTranslator 式「钩子后选最适配」）**：FT 钩子 flush 同时上行
  [FTLN]（状态机还原）与 [FTRAW]（原始重绘链）；OnsExtractBridge 新增 candidates
  事件并派生「最长重复后缀」候选（≈当前句真身）；面板点 ♪ 状态行弹单选悬浮框
  （自动 / 原始流 / 派生句），选择按游戏持久化，状态行 ⇄候选 前缀提示。
  kazurauta 实测：三候选正确，派生句与游戏当前句一致。
- **主动结算线程**：原惰性 flush 要等下一字符才触发，点击推进后面板不刷新——
  100ms 轮询 + 300ms 停顿即结算，实测推进后面板自动更新。
- **跨内核泛型验证（意外）**：tsukikage（libgame134 内核、中文文本）同一钩子
  全部命中，面板正确显示中文对白——KRKR 三内核通用成立。
- 遗留：说话人行重绘链的自动还原仍有重复段（用户可切「最长重复后缀」候选绕过）；
  面板不透明度是全局持久化（跨引擎共享），可点 40% 芯片调整。

## 2026-10-05 会话（六追加三）：页缓冲模型与已知边界

- **页缓冲增量模型**：gPage 跨周期累计（raw rfind 已知页拼增量，翻页自动重置，
  raw 3KB 封顶）；双阈值结算（300ms~3s = 换行间歇 force 上行、页继续累计；
  >3s = 点击推进翻页）。
- **已知边界（实测）**：KAG 多行页的后续行字符**只查询一次**（不随打字机重绘
  重复），字符流里无法回溯上几行——自动还原可能只含末段。完整内容的替代路径：
  候选切换「原始流」、历史悬浮窗（部分行被完整行覆盖合并）。
- KRKR 监听转发修复（KrkrExtractFacade → OnsExtractBridge）已实测：推进对白
  面板即时更新，无需重开。

## 2026-10-05 会话（六追加二）：历史悬浮窗 + 整页推导 + 行距（用户反馈三连修）

- **历史独立悬浮窗**（OnsHistoryOverlay）：深色圆角窗、标题条拖动、✕ 关闭、
  清空按钮（回调面板清空数据）；面板历史键开/关，新句增量追加不重建。
  启动期 UI 乱序串会混入——NativeBridge FT 通道加 CJK/假名+长度过滤挡大半，
  残留可一键清空。
- **整页推导**：FT flush 主上行改为原始链推导（最长重复后缀 = 最后一次整窗
  重绘快照 + 尾部补差），两行窗口不再只显示最后一行；状态机降为回退。
  实测两行折行句完整捕获（人の流れに逆らうようにどんどん歩き続けてる。）；
  打字中途停顿仍会产生部分行 flush，已加「部分行被完整行覆盖」逻辑。
- **行距**：句栏 setLineSpacing 2x → 1.25x，折行不再有额外空行感。

## 2026-10-05 会话（五）：品牌更名 VNLingo + 图标重绘

- 应用名全部改为 **VNLingo**（values / values-en / values-ja 的 app_name，
  以及更新渠道说明、自动补丁说明等用户可见文案；applicationId 仍为
  com.tyranor.next，与上游包名兼容不变）。
- 图标重绘为程序生成的简易图：深靛蓝渐变底 + 白色粗体 V + 青色横条
  （#2DD4BF，词典/语言意象）——PIL 生成，替换自适应图标背景
  ic_launcher_art.png、单色层 ic_launcher_monochrome.png 及五档 mipmap
  （方/圆各一）；Android 12+ 启动画面直接用图标，开场旧图随换。
- 删除无引用的开场旧图 `drawable-nodpi/engine_logo.png`。
- 模拟器抽屉里仍会显示一个 "Tyranor Next" 条目：那是宿主脚本装的
  `org.scummvm.scummvm.debug`（ScummVM debug 版，launcher 标签被改成
  "Tyranor Next"），与本项目无关，VNLingo 本体已正确显示新名新图标。

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

### 放大镜（会话四追加，用户需求）

- 新增 `OnsMagnifier`（com.core.ons）：可拖动悬浮放大窗，持续（~10fps）PixelCopy 抓取
  游戏渲染并把**镜头下方 1/zoom 区域**放大显示（2x/3x/4x，＋/－ 切换），拖动整窗
  自主选位，✕ 关闭；连续失败 5 次自动关闭。
- 入口：OnsSideButtons 新增「放大镜」键（🔍，截图与设置之间），ONS/KRKR/Artemis/Web
  各宿主通用（侧键组由 OnsExtractPanel 统一安装）。
- 关键实现点：**渲染源优先游戏的 SurfaceView**——SDL/GL surface 由 SurfaceFlinger
  单独合成，窗口 PixelCopy 拿不到其内容（实测黑屏）；PixelCopy 会把整个 surface
  缩放填进目标位图，故抓全幅后手动 createBitmap 裁剪；镜头取景区 = 镜头屏幕矩形与
  surface 屏幕矩形交集（各自 getLocationOnScreen，1:1 映射）。无 SurfaceView 的宿主
  （Artemis/NativeActivity）退回窗口抓取（可能黑屏，连续失败自动关闭，未实测成功）。
- 模拟器实测（esg）：开关/拖动到菜单文字上方 3x 放大清晰可读/变焦/关闭全部通过。

### 左缘引擎适配按键（会话四追加，用户需求）

- 新增 `EngineLeftButtons` 共享组件（engine com.core.engine）：左缘竖排 40dp 圆形按键列，
  样式对齐 OnsSideButtons；顶部折叠键状态持久化；两种形态——宿主视图树安装（KRKR）/
  独立小悬浮窗（Artemis，TYPE_APPLICATION 必须显式 `lp.gravity = TOP|START`，缺省
  gravity 会让 x/y 不按左上锚定——实测踩坑）。
- KRKR：ESC（KR2 nativeKeyAction BACK，内核映射 ESC）/ OK（回车）/ 方向键×4 /
  SKIP（按住 Ctrl）——kazurauta 实测 OK 推进对白 ✓。
- Artemis：NEXT（EmulateKeyEvent 13 回车推进）/ SKIP（Ctrl 140 按住）/ 方向键×4——
  blossom 实测 NEXT 推进对白 ✓（`input keyevent 66` 同路径交叉验证）。

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

1. **面板读不到文本/语音（会话三彻底修复）**：真正根因是随包 libartemis-clean.so 是
   无桥的旧构建——内核源码仓 `D:\Desktoprtemis-compat` 自带提取桥
   （TagPrint/TagAudio 发射 + native_activity.cpp 自注册 + 语音副本落盘缓存），
   AGENT.md 明确「宿主将 libartemis.so 打包为 libartemis-clean.so」。修复：
   `llvm-strip --strip-debug` 后的带桥构建替换随包内核，`pluginVersion` 29→30
   触发 EnginePluginBootstrap 自动重装（实测 re-provision 日志确认）。
   `artemis_extract_hook.cpp` 保留为无桥内核的 GOT 兜底（DispatchTag/SetMessageLayered/
   AudioChannels::Play），检测到 `SetExtractEmitter` 导出即整体跳过防双发射；
   shadowhook inline 路线废弃（本环境 API 34+ linker `shadowhook_init` 即 errno=12）。
   facade 语音配对改「只认纯语音事件 + 消费即清 + 同句粘滞（前缀延伸）」——旁白行
   不再继承上一句语音，同句打字机分段不再冲掉 ♪ 标注。blossom 实测：对白文本
   （寝坊した～っ！等）+ 语音 fem_him_00281/fem_rim_00238 配对正确、语音副本落盘可重播。
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

### 五.六、左缘按键统一抽象 + Artemis 按键修复（会话四）

- **Artemis 左缘按键无效的根因在内核**：clean 内核 `EmulateKeyEvent` JNI 是日志桩
  （jni_bridge.cpp 只打日志）。已在内核源码仓实装 `InjectHostKey`（与物理键同管线
  EnqueueInput 入队 → 引擎线程 PushKeyDown/Up 排空；key=官方 key id，
  status 对齐 Android action 0=down/1=up），随带桥构建入仓（pluginVersion 31）。
  宿主 `artTap`（down + 120ms up）替代原「action=2」误用语义；blossom 实测
  NEXT 推进对白、新行 ♪ 语音标注正确。
- **左缘按键统一抽象**：`EngineLeftButtons` 扩展为三语义（点按/按住 hold/开关高亮
  toggled）+ ONS 同款 chevron 折叠键（在顶、折叠态持久化）；形状统一圆角矩形
  radius 10dp（原圆形），规格对齐 ONS（40dp、间距 5、距顶 12、贴缘 2+inset）。
  ONScripter 左列（原自制 toggleButton+buildControlColumn 约 150 行）迁移至该抽象，
  ESC/SKIP(按住 Ctrl)/AUTO(高亮)/MENU/OK/NEXT 语义保留；KRKR/Artemis 同款渲染。
- 面板底部按键排水平居中（Gravity.CENTER，超出仍可滚动）。

### 五.五、面板 UI 改版（用户反馈，会话三）

- 按键排移到面板最下方并缩小（40dp→32dp，含 toggle/透明度按钮）；
- 面板横屏不占全宽：`preferredWindowWidthPx()` 屏宽两侧各留 dp(64) 给左右按键列、
  水平居中（Artemis 窗口宿主直接按此设窗宽）；正文分词 span 颜色 TEXT_BODY（近黑）
  在深色面板上不可读 → 改纯白 TEXT_ON_DARK；
- Artemis 左缘引擎按键（NEXT/SKIP/方向键）恢复：`installEngineLeftButtons()` 此前
  重构后从未被调用，现接入 installPanels。

### 六、验证状态

- ✅ `:app:assembleDebug` 全程构建通过（含 ML Kit 首次拉取）。
- ⏸ 运行时行为未在模拟器回归（VNLingo 与 Tyranor-Next 同包名 `com.tyranor.next`，安装互覆）；
  功能行为以来源仓库的实测为准（ONS 面板/查词/制卡/翻译/TTS/截图/点击模式均已实测）。

## 进行中 ⏸

- `feat/extract-suite` 分支本次新增 3 个提交（Artemis 修复 / 导航与裁剪 / 手柄重映射）未推送。
- 遗留：packed Artemis 游戏语音字节不可播（需内核侧语音副本机制）；官方 revision 内核无提取；
  外置模拟器（PPSSPP/Eden/Winlator）基础设施代码保留但无入口。
