# KRKR 提取失效与钩子开关缺失：修改优化方案

> 状态标记（纯文本）：已完成 ｜ 待完善 ｜ 未开始。
> 用途：本方案用于交给其他 agent 执行，自包含（含证据、定位树、改动点、验收）。
> 案例游戏：`tsukikage no simulacre`（Tsukikage no Simulacre，KRKR 引擎）。
> 相关实现：
> [krkr_extract_hook.cpp](../../engine/src/main/cpp/krkr_extract_hook.cpp)、
> [krkr_bridge.cpp](../../engine/src/main/cpp/krkr_bridge.cpp)、
> [NativeBridge.kt](../../engine/src/main/java/bridge/NativeBridge.kt)、
> [KirikiroidLauncherBaseActivity.java](../../engine/src/main/java/com/akira/tyranoemu/remote/KirikiroidLauncherBaseActivity.java)、
> [EngineLauncher.kt](../../app/src/main/java/com/tyranor/next/core/game/launch/EngineLauncher.kt)、
> [PerGameSettingsScreen.kt](../../app/src/main/java/com/tyranor/next/ui/settings/PerGameSettingsScreen.kt)、
> [EngineSettingsStore.kt](../../app/src/main/java/com/tyranor/next/core/settings/EngineSettingsStore.kt)。

---

# 1. 问题描述

| 编号 | 现象 | 用户原话归纳 |
|---|---|---|
| P1 | 案例游戏（KRKR）游戏内提取面板拿不到对白文本 | 「tsukikage 这个 krkr 游戏没有 hook 到文本」 |
| P2 | 游戏条目设置与设置页里看不到「文本提取钩子」开关（ONS / KRKR / Artemis 这类支持提取的引擎） | 「游戏条目和设置里没有出现 hook 开关」 |

# 2. 已确认的现场证据

## 2.1 案例游戏确实按 KIRIKIRI 引擎启动

- 日志：`EngineSettingsResolver: resolve ... engine=KIRIKIRI`（10-08 11:19:52），
  游戏目录 `/storage/emulated/0/0.外来文件存储/game/krkr/tsukikage no simulacre/tsukikage no simulacre/`
  含 `data.xp3 / evimage.xp3 / fgimage.xp3 / voice.xp3 / video.xp3 / plugin / patch.tjs`。
- 该游戏自带 `patch.tjs` 仅一行 `System.setArgument("-debugwin", "no");`，
  未包含本项目的托管块（overlay 走 app 私有目录只读重定向）。

## 2.2 钩子实际上「武装成功」了（关键反直觉证据）

用户本轮启动（10-08 11:18:19，进程内 `KrkrExtract` TAG）日志：

```
W KrkrExtract: extract hook: shadowhook crashed with signal 6 in init; degrade to vtable patch
W KrkrExtract: extract hook: inline hook unavailable; falling back to vtable patch
I KrkrExtract: extract hook: Label vtable patched slotIndex=173 (text extraction armed)
I KrkrBridge : GOT hook library=.../kirikiroid2/current/arm64-v8a/libgame.so symbol=FT_Get_Char_Index patched=1
I KrkrExtract: ft probe GOT hooked (FT_Get_Char_Index)
```

即：`Label::setString` 虚表补丁成功、`FT_Get_Char_Index` GOT 补丁成功，
**没有** `extract hook: ... not found` / `mprotect failed` / `slot not found` 之类的失败日志。

## 2.3 但整条上行链路「一个字符都没产出」

- 全量 logcat（24620 行）内 `KrkrExtract` 只有上述 4 行；
  `krkr_install_ft_probe` 之后的字符流累积没有任何 `ft line:` 日志
  （该日志只在 `deriveAndMaybeEmitLocked` 真正产出整页时打印，
  见 [krkr_extract_hook.cpp:418](../../engine/src/main/cpp/krkr_extract_hook.cpp#L418)）。
- 也没有 `NativeBridge: krkr text:`（即 `onKrkrText` 从未收到 `[FTLN]`/`[TNEXT]` 行）。
- 结论：**不是钩子没装上，而是装上之后没有任何字符流过、或流过了但被页推导/过滤全部丢弃。**

## 2.4 随包内核符号核对（四个内核）

用 NDK `llvm-nm -D --defined-only` 核对：

| 内核 | `cocos2d::Label::setString` | `_ZTVN7cocos2d5LabelE` | `FT_Get_Char_Index` | 备注 |
|---|---|---|---|---|
| `libgame.so`（1.3.9） | 有 | 有 | 有 | 钩子当前走这个 |
| `libgame134.so` | 有 | 有 | 有 | 可用 |
| `libgame126.so` | 有 | 有 | 有 | 可用 |
| `libkrkrsdl3.so`（SDL3 内核） | **无** | **无** | 无 | cocos2d Label 相关符号为 0，**当前无提取路径** |

## 2.5 开关链路源码事实

- 单游戏覆盖开关**已实现**：KIRIKIRI 在
  [PerGameSettingsScreen.kt:449](../../app/src/main/java/com/tyranor/next/ui/settings/PerGameSettingsScreen.kt#L449)、
  ONS 在 553 行、Artemis 在 567 行，均写入
  `PerGameSettingsStore.F_EXTRACT_HOOK`，经 `EngineSettingsResolver` 合并成
  `LaunchContract.EXTRACT_HOOK` 下发三宿主。
- 但**全局默认没有任何 UI 入口**：
  `EngineSettingsStore.setKrExtractHook` / `setOnsExtractHook` / `setArtExtractHook`
  三个 setter **零调用方**（全仓检索仅定义处），
  全局值恒为默认 `true`。设置页
  [SettingsScreen.kt](../../app/src/main/java/com/tyranor/next/ui/settings/SettingsScreen.kt)
  的「引擎设置」卡片只有引擎管理 / 翻译 / Anki / TTS / 词典页 / 云同步 / MCP 等入口，
  **没有提取钩子分区**。
- SDL3 内核下，单游戏设置页**故意隐藏**该开关
  （`if (!isSdl3)` 包裹，见 503 行结构），因为该内核无提取能力。

# 3. P1（提取失效）定位树与改动点

## 3.1 定位树（按概率排序，每步都有明确判据）

### H1：对白渲染不经 `FT_Get_Char_Index`（首要嫌疑）

`FT_Get_Char_Index` 已 GOT 打桩但 0 字符流，最可能是该游戏的文字绘制走了别的路径：

- cocos2d 字体图集/字形缓存命中时不查字形索引；
- 或该游戏用位图字体（KAG 的 `font` 图像字体）、或先经 `FT_Load_Glyph`
  （代码里保留的 `hookedFTLoadChar` 从未接线，见
  [krkr_extract_hook.cpp:243](../../engine/src/main/cpp/krkr_extract_hook.cpp#L243)）；
- 或 `libgame.so` 内部另有一份静态 FreeType（GOT 槽指向的不是我们 dlsym 的
  那个 `FT_Get_Char_Index`）。

**判据**：给 FT 钩子加「被调用计数 + 前若干次字符码点」即时日志后复测——
若计数为 0，H1 成立。

### H2：字符流有，但页推导永不产出（次要嫌疑）

`deriveAndMaybeEmitLocked` 要求 `ftDerivePage()` 在原始链里找到「最长重复后缀」
（即整窗重绘两次以上）才产出，见
[krkr_extract_hook.cpp:383-389](../../engine/src/main/cpp/krkr_extract_hook.cpp#L383)。
若该游戏是**一次性整页绘制**（不做打字机逐字重绘），原始链永远只有一份快照 →
推导恒为空 → 永不上行。日志表现为「有字符流、无 `ft line:`」。

**判据**：同一批复测里看字符计数 > 0 但无 `ft line:` 即成立。

### H3：上行被 Java 侧过滤丢弃

`onKrkrText` 只接受以 `[TNEXT]` / `[FTLN]` / `[FTRAW]` 开头的消息，且要求
`hasCjk()` 命中（见 [NativeBridge.kt:79-98](../../engine/src/main/java/bridge/NativeBridge.kt#L79)）。
`Label::setString` 虚表通道发出的**裸控制台文本**没有前缀 → 一律丢弃。
若该游戏的文本只出现在 Label/控制台通道（例如 TJS 发射器未注入），则必然无文本。

**判据**：给 `onKrkrText` 加一条「收到但未通过前缀/`hasCjk` 过滤」的调试日志，
或在虚表钩子内给文本打上 `[LBL]` 前缀做诊断通道。

### H4：TJS 发射器通道在本游戏上被显式放弃（已知架构空档）

`prepareKrPatchOverlay` 注释明确写着「TJS 发射器默认停用……KAG3.32 内核
（kazurauta）引导期段错误，**KAGParserEx（tsukikage）注册前不可写且延迟手段本身致命**」
（见 [EngineLauncher.kt:1350-1353](../../app/src/main/java/com/tyranor/next/core/game/launch/EngineLauncher.kt#L1350)）。
即本案例游戏正是当初判定「引擎自身输出通道不可用」的那一个。

**判据**：即使把「文本输出方式」切到「引擎自身输出（TJS）」，
`includeExtractTjs` 也只在 `kr_extract_tjs` 全局开关为真时注入，
而该全局开关同样**没有 UI 入口**（与 P2 同源问题）。

### H5：vtable slot 命中但目标非对白 Label

`slotIndex=173` 是扫描 512 槽后与 `dlsym` 地址相等的第一个槽。若该版本 vtable
布局有多个同函数指针槽，命中槽可能不是对白用的那一个（风险较低，但复测时应打印
命中的槽号与调用次数做确认）。

## 3.2 复测协议（先做这一步，10 分钟内可定分支）

不需要改逻辑，只加**诊断埋点**（默认关闭、只在 `DiagLog` 调试开关打开时生效）：

1. `krkr_extract_hook.cpp`：`hookedFTGetCharIndex` 内加
   `static std::atomic<uint64_t> gFtCalls;` 计数，每 500 次或首次 50 个码点打印一次
   `LOGI("ft char: calls=%llu code=U+%04lX", ...)`（限频，避免 ARM 转译下刷屏 ANR）。
2. `krkr_extract_hook.cpp`：`hookedLabelSetString` 内加同款计数与前 20 条文本长度日志
   （不打全文，只打长度与首码点，避免控制台噪音与编码问题）。
3. `NativeBridge.kt`：`onKrkrText` 入口加一条
   `Log.d("NativeBridge", "krkr text raw: len=${text.length} head=${text.take(24)}")`，
   用于区分「没收到」与「收到了被过滤」。
4. 启动游戏 → 进入有对白的场景 → 推进 20 句 →
   `adb logcat -d | grep -E "KrkrExtract|NativeBridge"`。

判据表：

| 观察 | 结论 | 对应改动 |
|---|---|---|
| `ft char: calls=0` 且 Label 计数 > 0 | 对白不走 FT 字形索引 | 走 §3.3 的 A1/A2 分支 |
| `ft char: calls>0` 但无 `ft line:` | 页推导门槛不满足 | 走 §3.3 的 B 分支 |
| `krkr text raw:` 有行但无 `krkr text:` | Java 侧过滤过严 | 走 §3.3 的 C 分支 |
| 三处都无 | 钩子未真正生效（vtable 槽非目标） | 加槽号/调用次数确认，必要时换 inline hook 或换目标函数 |

## 3.3 改动点（按分支）

### A1：补 `FT_Load_Glyph` / 字形缓存路径钩子（H1）

- 现状：`hookedFTLoadChar` 已写好但**从未安装**（
  [krkr_extract_hook.cpp:243](../../engine/src/main/cpp/krkr_extract_hook.cpp#L243) 无调用方）。
- 改动：在 `krkr_install_ft_probe` 内按 `FT_Get_Char_Index → FT_Load_Glyph →
  FT_Load_Char` 顺序尽力安装（同一套 GOT 补丁 helper，幂等），互为兜底；
  字符流按「码点首次出现」语义去重，避免同一字符被两条路径重复喂入。
- 验收：`ft char: calls` > 0 且面板句栏能看到对白（允许先出原始候选）。

### A2：若内核确实有第二份 FreeType / 不走 GOT（H1 变体）

- 先确认：运行期读 `/proc/<pid>/maps` 看是否有第二份包含 FT 符号的 so；
  在 `krkr_install_ft_probe` 内打印 `dlsym` 得到的目标地址与 GOT 槽原值是否一致。
- 改动：`krkr_bridge_got_hook` 已支持按库名+符号打洞；若命中在别的库，
  把库名参数从 `gGame.library`（libgame.so）扩展为「扫描所有已加载 so」。
- 风险：GOT 补丁跨库扩展会放大崩溃面，必须保留现有失败降级路径。

### B：页推导增加「一次性绘制」兜底（H2）

- 现状：`ftDerivePage` 只认「最长重复后缀」，一次性整页绘制永不产出。
- 改动：在 `deriveAndMaybeEmitLocked` 增加兜底条件——
  当 `gRawBuf` 连续 ≥N（建议 8）个码点且在 `idleMs`（建议 1200ms）内无新字符时，
  直接把 `gRawBuf` 当作整页候选上行（打 `[FTRAW]`，面板按其既有候选机制显示），
  同时把该段并入 `gPage` 供增量模型使用。
- 保留原有「重复后缀」主路径，兜底只在主路径空结果时触发，避免污染既有正确行为。
- 验收：一次性绘制的游戏也能出句；打字机型游戏行为不回归
  （kazurauta / 既有实测游戏回归）。

### C：放宽 Java 上行过滤（H3）

- 现状：无 `[TNEXT]`/`[FTLN]`/`[FTRAW]` 前缀的文本一律丢。
- 改动：新增诊断前缀 `[LBL]`（仅调试开关下由 native 侧加），Java 侧在调试模式下
  接受并只落调试日志（不进提取桥），用于判定「有没有文本流过 Label」；
  正式语义不改，避免原版内核下控制台噪音刷屏。
- 验收：能看到 `[LBL]` 诊断行且不污染面板/制卡数据。

### D：TJS 发射器通道解锁（H4，产品面）

- 现状：`kr_extract_tjs` 全局开关无 UI（同 P2 根因），且该游戏被注释标记为
  「延迟手段致命、注册前不可写」。
- 改动（择机）：
  1. 先补全局开关 UI（见 §4），让用户能自行尝试该通道；
  2. 对 KAGParserEx 类游戏，探索「启动后一次性延时注入」的可行窗口
     （在 KAGParserEx 注册完成后、第一句对白前），失败必须可回滚且不崩游戏；
  3. 若 2 不可行，明确在 UI 上把该游戏的「文本输出方式」标注为不可用，
     避免用户误以为切了就有用。

### E：SDL3 内核明确不支持（与 §4.3 同源）

`libkrkrsdl3.so` 无 `cocos2d::Label` / `FT_Get_Char_Index` 符号，
当前**不存在**可用提取路径。改动点：
- 单游戏设置页在 SDL3 内核下用**禁用态说明行**替代「静默隐藏」，
  文案明确「该内核暂不支持文本提取」；
- 若后续要支持，需走内核源码级方案（对齐 Artemis 的带桥内核路线），
  不在本方案范围内。

# 4. P2（钩子开关缺失）定位与改动点

## 4.1 全局开关未接线（确认缺陷，必须修）

| 项 | 现状 |
|---|---|
| `EngineSettingsStore.isKrExtractHook/isOnsExtractHook/isArtExtractHook` | 已实现，默认 true |
| `setKrExtractHook/setOnsExtractHook/setArtExtractHook` | 已实现，**零调用方** |
| 设置页 UI | 无任何入口 |
| 单游戏页「跟随全局」 | 显示的就是恒为 true 的全局值，用户无法改动 |

**改动点（建议合并为一次改动）**：

1. 在 [SettingsScreen.kt](../../app/src/main/java/com/tyranor/next/ui/settings/SettingsScreen.kt)
   「引擎设置」卡片内新增「文本提取」分区（或独立子页），三项：
   文本提取钩子（ONS）/（KRKR）/（Artemis），开关默认开，读写
   `EngineSettingsStore` 对应 key；
2. 同步把 KRKR 的「文本输出方式」全局默认（`kr_extract_tjs`）一并暴露，
   消除 §3.3-D 的不可达问题；
3. 文案复用 `R.string.settings_extract_hook`，新增 summary 说明
   「关闭后不武装任何 native 钩子，面板保留但不产出文本」；
4. 设置页三条纪律：入口必须经 `AppLocaleController.findActivity(ctx)` 解包
   （禁止 `ctx as Activity`，历史闪退根因）；i18n 三 locale 同步补键；
   改动后补 PROGRESS/MEMORY/待办三件套。

**验收**：设置页开关切换后，`adb shell run-as com.tyranor.next cat shared_prefs/*.xml`
能看到对应 key 落盘；关闭后启动某 ONS/KRKR 游戏，日志出现
`setExtractHookEnabled(false)` 语义（KRKR）或面板不出文本（ONS/Artemis）。

## 4.2 单游戏开关「看不到」的核对（多为入口认知问题）

代码事实：单游戏开关存在于**游戏条目 → 该游戏的设置（每游戏引擎设置页）**，
KIRIKIRI/ONS/ARTEMIS 三个分支各有；KRKR 分支在 `SectionCard("KRKR")` 内。

需要其他 agent 先做一次**界面核对**再决定是否要改：

1. 游戏库 → 长按/点游戏条目 → 进入该游戏设置页 → 截图；
2. 若开关在：更新用户指引（不改代码）；
3. 若不在：检查 `game.engine` 实际枚举值（`EngineSettingsResolver` 日志已打印
   `engine=KIRIKIRI`，说明枚举正确），逐项核对 `OverrideSwitch` 的渲染条件
   （例如 `isSdl3` 分支、`SectionCard` 是否被折叠）。

## 4.3 SDL3 内核下的隐藏行为

`if (!isSdl3)` 包裹导致该内核下开关**静默消失**，用户无从得知原因。
改动点见 §3.3-E（禁用态说明行）。

# 5. 实施顺序与预估

| 批次 | 内容 | 依赖 | 预估 |
|---|---|---|---|
| B1 | §3.2 诊断埋点 + 复测取判据（不改行为） | 无 | 0.3 会话 |
| B2 | §4.1 全局提取钩子设置项（含 KRKR 输出方式全局开关） | 无 | 0.5 会话 |
| B3 | 按 B1 判据执行 §3.3-A1/A2 或 B 或 C（取实际命中分支） | B1 | 0.5 - 1 会话 |
| B4 | §3.3-E / §4.3 SDL3 禁用态说明行 + 单游戏页核对修正 | 无 | 0.3 会话 |
| B5 | §3.3-D TJS 通道解锁探索（可选，失败即回滚） | B2 | 按需 |

# 6. 回归与风险

| 项 | 要求 |
|---|---|
| 既有 KRKR 游戏回归 | kazurauta（KRKR，已验证）文本提取不得回归：句栏、说话人行、候选切换、点词查词、制卡 |
| ANR 风险 | FT 钩子在 ARM 转译/真机高频命中，新增日志必须限频；锁内不得做重字符串运算（既有 400ms 轮询 + 512B 快照设计不得破坏） |
| 崩溃面 | shadowhook 信号护栏必须保留；GOT/vtable 补丁失败一律降级不中断游戏 |
| 设置页 | 新入口禁止 `ctx as Activity`；i18n 三 locale 同步；保存后重进页面状态一致 |
| 文档 | 改动后补 PROGRESS（会话级）、MEMORY（踩坑/环境事实）、待办（未完成项） |
| 提交 | `类型(范围): 摘要`，不主动 push；内核相关改动需 `pluginVersion` +1（若涉及随包 .so 替换） |

# 6.5 执行记录（2026-10-08 会话四十五，真机 alioth / Android 11）

批次状态：B1 已完成 ｜ B2 已完成 ｜ B3 判据已出、修复路径需换线（见下） ｜ B4 已完成 ｜ B5 未开始（待用户确认）。

## 6.5.1 B1 埋点落地（已完成）

- `krkr_extract_hook.cpp`：新增 `krkr_set_extract_diag(bool)` 诊断开关（默认关，
  由 Java 侧按 `DiagLog.isEnabled()` 经 `NativeBridge.setExtractDiagEnabled`
  在 `onLoadNativeLibraries` 下发）；埋点全部限频：
  `ft char:`（前 50 次 + 每 500 次，码点与字形索引）、`ft glyph:`（同款）、
  `label set:`（前 20 次 + 每 200 次，长度与首码点）、每 2s 一条
  `ft state:`（raw/known/idx_calls/glyph_calls/nextchar/label 计数）与
  `ft raw:`（尾部 120 字节原文，用于辨认流经通道的文本）。
- `krkr_install_ft_probe` 现在按 `FT_Get_Char_Index → FT_Load_Glyph → FT_Load_Char`
  顺序尽力安装（幂等、失败不致命），并打印每个符号的 GOT 打洞结果；
  诊断模式下额外打印 `dlsym` 地址与 GOT 槽原值（A2 判据）。
- 新增 `FT_Load_Glyph` 反向映射通道：`glyph_index → 码点` 惰性构建
  （对 BMP 逐码点问原 `FT_Get_Char_Index`，取首次命中），构建后原子发布、
  读侧无锁；两条通道共用 `ftOnCode`，按相邻去重避免重复喂入。
- `NativeBridge.onKrkrText` 入口新增 `krkr text raw: len/head` 原始日志（仅
  调试开关打开时），用于区分「没收到」与「收到后被过滤」。

## 6.5.2 B1 复测判据（真机实测，tsukikage vs kazurauta 对照）

| 观察 | kazurauta（KAG3.32 普通 KAGParser） | tsukikage（KAGParserEx + textrender/layerExDraw/PreRenderFontEx） |
|---|---|---|
| 钩子武装 | 全部成功（Label 虚表 slot 173、FT 三个 GOT 槽 patched=1、反向映射 42387 项） | 同样全部成功（日志逐条一致） |
| 启动期计数 | idx=168 glyph=168 label=187 | idx=144 glyph=144 nextchar=0 label=217 |
| 标题菜单/对白期 | idx/glyph 持续增长（595 → 909），`ft line:` 正常产出（「だってわたしたち、友達だもん」等） | **计数完全冻结**（144/144/217，持续数十秒不变），全程 0 条 `ft line:` |
| 上行走廊 | `NativeBridge: krkr text: …` 正常 | **0 条**（raw 日志同样为 0） |

结论（推翻/修正原方案假设）：

- **H1 比原判据更强地成立**：tsukikage 的正文渲染**根本不经过** libgame.so 的
  FreeType GOT 槽——不是「不走码点→字形映射」，而是连 `FT_Get_Char_Index` /
  `FT_Load_Glyph` / `FT_Load_Char` / `FT_Get_Next_Char` 全部零调用（标题菜单、
  18 禁告知、对白都算在内）。因此原方案 §3.3-A1（补 FT_Load_Glyph 钩子）与
  §3.3-A2（换库打洞）在本游戏上**都不会有任何效果**（两个通道已实现并验证：
  对 kazurauta 生效，对 tsukikage 无字符可截）。
- **H2、H3 被排除**：页推导门槛与 Java 前缀/`hasCjk` 过滤都不是原因——
  tsukikage 连原始字符流都没有（`ft state: raw=0` 常驻），过滤链路上游就断了。
- Label 通道同样不承载正文：kazurauta 进入正片后 label 计数也停在 187，
  说明控制台文本之外的正文本另走一条渲染路径；tsukikage 的正文连该路径也不走。
- 现场旁证：该游戏自带 `textrender.dll / layerExDraw.dll / extNagano.dll /
  PreRenderFontEx`（启动日志出现 `PreRenderFontEx`、`embFontLoader.tjs`、
  `font, face: 源ノ角ゴシック`），与「文本由游戏侧字体预渲染/自绘路径产出」一致。

**可提取的唯一剩余通道 = 解析器层（TJS 发射器）或带钩内核**，即原方案 §3.3-D
与 MEMORY 里「对白提取唯一可靠路径 = krkr2-main 源码带钩内核」的结论。
注意本游戏使用 `KAGParserEx`，现有发射器（包装 `global.KAGParser`）对
KAGParserEx 游戏大概率无效，且 §3.3-D 记录的「注册前不可写 / 延迟即致命」
尚未证伪——推进前需要用户确认可接受的崩溃实验窗口。

## 6.5.3 B2 全局设置项（已完成）

`SettingsScreen.kt` 引擎设置详情页新增三个全局开关（原先三个 setter 零调用方）：

- KRKR 卡片：`文本提取钩子`（`kr_extract_hook`，含 summary）；SDL3 内核下改为
  禁用态说明行（`该内核（krkrsdl3）暂不支持文本提取`）。
- ONS 卡片：`文本提取钩子`（`ons_extract_hook`）。
- Artemis 卡片：`文本提取钩子`（`art_extract_hook`）。
- KRKR 的 `文本输出方式` 全局默认原已存在（`剧情文本框提取（实验性）` 开关，
  `kr_extract_tjs`），本轮保留并在文档中明确其与单游戏页 `文本输出方式` 的对应关系。
- i18n：三 locale 同步新增 `settings_extract_hook_summary`、
  `settings_extract_hook_unsupported`。

真机验证：KRKR 全局设置页可见 `剧情文本框提取（实验性）` + `文本提取钩子`
（截图为证）。

## 6.5.4 B4 单游戏页与 SDL3（已完成）

- §4.2 的「看不到开关」经真机核对为**入口认知问题**：游戏条目 → 引擎设置 →
  KRKR 卡片中 `文本提取钩子`（跟随全局（开））与 `文本输出方式`
  （跟随全局·运行时文本流）均在正常渲染（tsukikage 实测截图）。原方案
  §2.5 记的「SDL3 下该开关被 `if (!isSdl3)` 隐藏」与代码事实不符：503 行的
  `if (!isSdl3)` 只包住「操作」卡片，提取钩子行本就在外层。
- 仍按 §3.3-E 落地了 SDL3 说明行：单游戏页 KRKR 分支在 SDL3 内核下用
  禁用态说明替代 `提取钩子` + `文本输出方式` 两行（原先二者恒显示，
  对无提取能力的内核属误导）。

## 6.5.5 回归（kazurauta）

- 提取链路无回归：`ft line: 「だってわたしたち、友達だもん」`、
  `踵をかえしたふたりのあとに、わたしも続く。` 连续正确产出，
  `NativeBridge: krkr text:` 同步到达。
- 新增通道不产生重复文本：`FT_Get_Char_Index` 与 `FT_Load_Glyph` 双通道
  由 `ftOnCode` 相邻去重合并（kazurauta 实测无重复行）。
- 性能：反向映射 42387 项一次构建在 200ms 内完成，之后读侧无锁；
  `ft state` 2s 一条、`ft char`/`ft glyph` 500 次一条，未见 ANR/卡顿。

## 6.5.6 B5 零风险探测（用户选择「先做零风险探测」，已完成）

新增 `kr_extract_probe` 全局开关（默认关；`EngineSettingsStore.KEY_KR_EXTRACT_PROBE`），
开启后向 patch.tjs overlay 追加**纯 ASCII 只读探针**（`krExtractProbeScript()`，
不替换任何类），输出经 `Debug.message` → logcat（tag `cocos2d-x debug info`）。

探测结论（tsukikage 真机，逐条为实测日志）：

| 事实 | 证据 |
|---|---|
| patch.tjs 阶段 `global.KAGParser` 已存在、`global.KAGParserEx` 不存在 | `[probe] KAGParserPresent=1`；V4 `KAGParser=1 KAGParserEx=0` |
| `System.addContinuousHandler` 与 `Timer` 都存在 | V4 `addContinuousHandler=1 Timer=1` |
| **`System.addContinuousHandler` 非致命**（推翻「延迟手段本身致命」的旧结论——致命的是 `Timer`） | V4 handler 连续跑满 901 帧、V5 跑满 1800 帧，游戏正常渲染/可点击 |
| `global.KAGParser` 在 patch.tjs 之后**第 1 帧**被换掉（KAG 的 Config/Init 阶段） | V8/V9 `swapped frames=1` |
| 换掉之后 `global.KAGParserEx` **仍不是全局**（类没有暴露成全局名） | V9 `swapToEx=threw`（缺失全局访问抛异常） |
| 探针可安全注入并回滚：关闭 pref 后 overlay 即不再生成 | `KRKR patch overlay disabled target=null overlay=null`，控制台恢复 632 行基线 |

TJS 方言事实（本内核，踩坑，供后续脚本避雷）：

- **不支持 `for (var k in obj)` 与 `in` 运算符**（均直接 syntax error，整份 patch.tjs
  不执行 → 游戏引导失败）；
- **访问不存在的全局会抛异常**，且 TJS 会把异常连同**反汇编转储**打进控制台
  （单次约 2 万行日志，足以把 logcat 冲掉）——所以「轮询某个尚未注册的全局」
  不能靠 try/catch 试错，只能比较**已存在**的对象（如 `global.KAGParser` 是否被换）；
- 探测/注入脚本必须**纯 ASCII**（Kirikiroid2 按内容探测 patch.tjs 编码，
  混入 UTF-8 日文会毁掉后续 SJIS 脚本解码）。

**据此得到的可行修复路线（下一步，尚未实施）**：patch.tjs 阶段
`System.addContinuousHandler` 注册一个逐帧钩子，在第 1 帧检测到
`global.KAGParser` 被替换后，**立即包一层**（子类化新的 `global.KAGParser`，
沿用 `krExtractEmitterScriptV8` 的 `getNextTag` 缓冲逻辑）——这样既避开了
「注册前不可写」的引导期崩溃（不在 patch.tjs 顶层替换），也不使用致命的 Timer。
该步骤会真正替换解析器类，属「可能崩游戏、可回滚」的实验，需用户单独确认。

## 6.5.7 B5 换类瞬间包装（用户确认后实施，实验已跑通）

按 6.5.6 的路线实施（`kr_extract_probe` 开启时注入 `krExtractProbeScript()`，
脚本标识 `TYRANOR_NEXT_KRKR_EXTRACT_WRAP_V*`），逐轮实测结论：

| 尝试 | 结果 |
|---|---|
| 帧内声明子类（`function` 里 `class X extends KAGParser`） | ✗ `armDeclare=threw`——TJS2 不允许在函数体内声明类 |
| 顶层声明子类（patch.tjs 阶段 `extends KAGParser`） | ✓ `topClass=ok`（合法；但此时基类是**换类前**的旧类） |
| 第 1 帧给**换后**的类覆盖 `getNextTag` | ✓ `readMethod=ok` / `writeMethod=ok`（类方法可读可写） |
| 从被替换的方法里调用原方法（`&(orig incontextof this)` / `orig.call(this)` / `orig()` / 临时还原类槽后 `this.getNextTag()`） | ✗ 四种调用约定全部抛异常（`modeFail` / `callViaInstanceFailed`） |
| 第 1 帧把**顶层子类**写回 `global.KAGParser` | ✓ `assignClass=ok` |

**跑通的结果**（tsukikage 真机）：包装后游戏**正常运行**并显示真实对白
（截图「俺こと卯月誠一が交わした、大切な契約の言葉。」），
提取桥收到文本：`NativeBridge: krkr text: せいちせいちせいち` / `のものもの`
——即 `[TNEXT]` → 控制台 → Label 钩子 → `onKrkrText` → 提取桥的**整条链路打通**。

**尚未完成**：文本仍**偏碎且稀少**（推进 10 次只到 1 条），原因是按标签切分的
缓冲逻辑对 Ex 游戏的 tag 流不适用（`ch` 片段被反复累积成 `せいちせいちせいち`
这种重复），需要按 KAGParserEx 的实际 tag 形态重做重建/切分；本轮只证明
**hook 点可行、游戏不崩、通道可达**。

实验开关默认关；真机验证后已把 `kr_extract_probe` 从 prefs 移除（回滚到默认行为，
`KRKR patch overlay disabled`）。继续推进时把该 pref 置 true 即可复现。

> **⚠️ 更正（2026-10-08 会话四十六，以
> [KRKR文本碎片与选项误提取方案.md](KRKR文本碎片与选项误提取方案.md) §10.1 为准）**：
> 本节「整条链路打通」的结论**是误判**。第二轮加入包装类构造函数计数与
> `getNextTag` 调用计数后实测：`wrapCtor=0`、`gt=0`、`parserChange` 仅 1 次
> ——**包装类从未被实例化**，该游戏构造解析器根本不经过 `global.KAGParser`；
> 当时看到的 `せいちせいちせいち` / `のものもの` 全部来自 **FT 字形通道**
> （`onKrkrText` 对 `[FTLN]`/`[TNEXT]` 打同一条日志，无法区分来源）。
> 正确表述：**换类赋值成功但通道对 tsukikage 无效**，tag 取证/重建均无从下手。

# 7. 需要用户提供的材料

| 材料 | 用途 |
|---|---|
| 案例游戏对白场景截图（进入游戏后有对白的画面） | 判定面板是否武装、句栏是否为空 |
| 复测后的 `adb logcat -d > x.txt` 全量日志 | 判定 H1/H2/H3 分支 |
| 单游戏设置页截图（tsukikage 的引擎设置页） | 核对 P2 的「看不到」是入口问题还是渲染问题 |
| 是否接受把「文本输出方式」切到 TJS 通道做实验（可能崩游戏，可回滚） | §3.3-D 是否推进 |

# 8. 附：本次核对的现有实现清单（供执行者快速索引）

| 环节 | 位置 | 说明 |
|---|---|---|
| 钩子安装入口 | [krkr_bridge.cpp:808-812](../../engine/src/main/cpp/krkr_bridge.cpp#L808) | 内核就绪后按 `gExtractHookEnabled` 调 `krkr_install_extract_hook` + `krkr_install_ft_probe` |
| 开关下发 | [KirikiroidLauncherBaseActivity.java:536-538](../../engine/src/main/java/com/akira/tyranoemu/remote/KirikiroidLauncherBaseActivity.java#L536) | `LaunchContract.EXTRACT_HOOK` → `NativeBridge.setExtractHookEnabled` |
| Label 虚表补丁 | [krkr_extract_hook.cpp:575-604](../../engine/src/main/cpp/krkr_extract_hook.cpp#L575) | 512 槽扫描 + mprotect 改槽 |
| FT 字符流钩子 | [krkr_extract_hook.cpp:488-521](../../engine/src/main/cpp/krkr_extract_hook.cpp#L488) | 码点累积 + 原始链 |
| 页推导与上行 | [krkr_extract_hook.cpp:383-427](../../engine/src/main/cpp/krkr_extract_hook.cpp#L383) | 重复后缀推导 + 300ms 节流 + `[FTLN]`/`[FTRAW]` |
| Java 过滤与转发 | [NativeBridge.kt:64-111](../../engine/src/main/java/bridge/NativeBridge.kt#L64) | 前缀白名单 + `hasCjk` + 提取桥 dialogue 事件 |
| TJS 发射器 | [EngineLauncher.kt:1556-1570](../../app/src/main/java/com/tyranor/next/core/game/launch/EngineLauncher.kt#L1556) | `kr_extract_tjs` 开启时随 patch.tjs overlay 注入 |
| 每游戏设置项 | [PerGameSettingsScreen.kt:449/553/567](../../app/src/main/java/com/tyranor/next/ui/settings/PerGameSettingsScreen.kt#L449) | KRKR / ONS / Artemis 三处 |
| 全局设置项 | 无 | §4.1 需新增 |
