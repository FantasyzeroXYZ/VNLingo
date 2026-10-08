# 与 web game text 的交互差异对比（剧情文本框 / 手柄 / Anki 方案）

> 状态标记（纯文本）：已完成 ｜ 待完善 ｜ 未开始。
> 用途：对照参考项目 `D:\code\test-JavaScript\web game text` 的三块实现，给出差异清单，
> 作为后续对齐改造的输入。
> 参考项目形态：浏览器扩展 + 网页版模拟器（React + WXT），运行在网页 DOM 上；
> 本项目形态：Android 原生（Compose 壳 + engine 原生 View 覆盖层），运行在原生引擎进程上。
> **因此差异分两类：平台必然差异（不做对齐）与设计可对齐差异（值得跟进）。**

---

# 1. 对照范围与两侧实现位置

| 维度 | web game text（参考） | VNLingo（本项目） |
|---|---|---|
| 剧情文本框 | `src/ui/pages/emulator/components/TextOverlay.tsx`（563 行）、`SidePanel.tsx`（3622 行）、`DictionarySidebar.tsx`（1938 行）、`src/ui/bottom-bar.ts`（761 行）、`src/ui/sidebar.ts`（587 行）、`src/application/emulator/overlayBar.ts` | `engine/src/main/java/com/core/ons/OnsExtractPanel.java`（2997 行）、`OnsDictOverlay.java`（183 行）、`EngineVirtualMouse.kt`（256 行）、宿主 `KirikiroidLauncherBaseActivity` / `ONScripter` / `ArtemisLauncherBaseActivity` |
| 手柄交互 | `src/application/input/gamepad.ts`（591 行）、`gamepad-block.ts`、`useGamepadCapture.ts`、`useReaderGamepad.ts`、`src/ui/.../ShortcutsTab.tsx`、共享库 `@trackreader/domain/src/input/shortcut-presets.ts` | 宿主 `dispatchKeyEvent` + `engine/src/main/java/com/core/engine/GamepadRemap.kt`（110 行）+ `VirtualMouseBindings.kt`（136 行）+ `app/.../ui/gamepad/GamepadSettingsActivity.kt` |
| Anki 方案 | `src/application/ankiService.ts`（222 行）、`src/domain/anki/field-mapper.ts`、共享库 `@trackreader/domain/src/anki/field-mapper.ts`（190 行，17 要素 + 方案表）、`background.ts` 方案解析 | `engine/src/main/java/com/core/anki/AnkiCardConfig.kt`（114 行）、`AnkiCardSchemes.kt`（104 行）、`AnkiDroidHelper.kt`（290 行）、`OnsExtractSettingsDialogs.showAnkiCardSettings()` |

# 2. 剧情文本框交互

## 2.1 交互与能力对照

| 能力 | 参考实现 | VNLingo | 判定 |
|---|---|---|---|
| 面板形态 | 覆盖层 TextOverlay + 底部条 + 侧栏，React 组件 | 原生 View 覆盖层；右缘按键组 + 剧情文本框面板，可独立窗口承载（`setSideButtonsWindowMode` / `installDetached`） | 平台差异，不做对齐 |
| 呼出方式 | 扩展悬浮球 / 快捷键 `Shift+D`；手柄 X（`srDictionary`） | 右缘按键组点击；**手柄 RB（`KEYCODE_BUTTON_R1`）呼出**（`handleKey` 收起态仅认 RB） | 设计等价 |
| 关闭语义 | `Esc` / 手柄 B 直接关面板 | **两步退出**：先收释义区 → 再收听力文本 → 最后收面板（`exitStep`） | VNLingo 更细，保留 |
| 词元导航 | 词典框内 D-pad 四向（`sdUp/Down/Left/Right`），上下移动选词、左右滚动 | 面板展开：**←→ 选词元、↑↓ 滚动内容**；A 查词/播放、B 退出、X/T 翻译、Y/START 制卡、C 复制、R1/L1 滚动、Space 播放 | 等价，键位不同 |
| 鼠标点击查词 | 网页 `document.elementFromPoint` 原生命中 | 触屏点选 + 手柄虚拟鼠标（D-pad 移动光标 + A 点击）；**光标点击优先命中覆盖层最深可点击子控件** | 平台差异 |
| 面板宽度自适应 | CSS 弹性 | 按左右缘按键列量测给出居中宽度（`preferredWindowWidthPx` / `applyPanelWidthValue`），适配不遮按键 | 平台差异 |
| 面板高度自适应 | CSS 弹性 | `applyAdaptiveHeight` | 平台差异 |
| 透明度 | 未见（参考项目无面板透明度设定） | **循环透明度**（`cycleOpacity` / `makeOpacityButton`） | VNLingo 独有 |
| 历史回放 | 未见独立历史面板（阅读模式有翻页/前后句） | **历史悬浮窗 + historyMode**：键盘/手柄可切换，↑↓ 滚动历史 | VNLingo 独有 |
| 候选切换（原始流/最适后缀） | 无 | `showCandidateDialog()` + `[FTRAW]` 原始链候选 | VNLingo 独有 |
| 听力模式 | 未见（阅读模式有听书） | **listeningMode**：隐藏正文只听语音，两步退出中先「揭示听力文本」 | VNLingo 独有 |
| 复制/播放/翻译/制卡动作排 | 底部条按钮 + 词典框动作键（`sdFlashcard` X、`sdTranslate` Y） | 面板顶部图标排：播放/复制/翻译/制卡/AI 助手 + Toggle：历史/听力/MultiTTS/TTS | 等价 |
| 引擎文本差异 | 每引擎独立提取器（`extractors/*`：renpy / rpgmaker / tyrano / gamemaker / phaser） | 各引擎宿主事件源 + 统一面板（`ExtractFacade`），提取通道见 `krkr_extract_hook.cpp` 等 | 架构差异 |

## 2.2 值得跟进的设计差异

1. **键位语义化 vs 硬编码**：参考的词典框键位是**语义动作名**（`sdConfirm`/`sdFlashcard`/`sdTranslate`/`sdExit`/`sdUp…`）经 `SHORTCUT_DICT_MAP` 映射到按钮索引，**用户可自定义**；VNLingo 面板按键（`handleKey` 的 switch，2298-2355 行）是**硬编码 keyCode**，只有虚拟鼠标五动作可绑定。
   → 可对齐点：把面板动作也纳入 `VirtualMouseBindings` 同款「动作 → keyCode 集合」模型。
2. **翻译/制卡键的语义**：参考 `sdTranslate` 默认 Y、`sdFlashcard` 默认 X；VNLingo 是 X/T=翻译、Y=制卡（按钮 A/B/X/Y 与键盘混用）。
   → 可对齐点：统一语义命名，避免同一物理键在不同引擎走不同语义。
3. **`sdScrollUp/sdScrollDown`** 独立绑定（默认 LB/RB，50ms 连发）：VNLingo 用 R1 长按/短按区分方向（`scrollFocused(event.isLongPress() ? -1 : 1)`）。
   → 可对齐点：拆成两个动作更直观，长按短按双语义对手柄不友好。

# 3. 手柄交互

## 3.1 机制对照

| 能力 | 参考实现 | VNLingo | 判定 |
|---|---|---|---|
| 事件获取 | **50ms 轮询 `navigator.getGamepads()`** + `gamepadconnected/disconnected`（`preventDefault` 阻止浏览器默认映射） | Android `dispatchKeyEvent`（事件驱动）+ `InputDevice.SOURCE_GAMEPAD/JOYSTICK` 判定 | 平台差异 |
| 键位可配置 | 四组映射表：general / nav / reading / dict，**键盘与手柄各一套**，用户可在 ShortcutsTab 重映射；`useGamepadCapture` 捕获式 | **两类**：`GamepadRemap`（源手柄键 → 目标键，可映射到键盘或其他手柄键）、`VirtualMouseBindings`（五动作 → keyCode 集合，一动作可多键） | 覆盖面接近；参考的「语义动作分组」更清晰 |
| 厂商预设 | **多厂商预设**：`GAMEPAD_BUTTON_MAPS` / `NAV_BUTTON_MAPS` / … = `vendorMaps(...)`，设置页可选手柄厂商标识 | 无厂商预设，按标准 Android keyCode 统一处理（`mappableButtons` 16 项） | 平台差异（Android 已统一映射为标准 keyCode） |
| 面板导航 | 「nav 组」四向 + confirm/cancel/exit/special，弹窗统一 `_dispatchModalByNavMap` | 面板 `handleKey` 硬编码；弹窗（设置/候选/历史）各自处理 | 可对齐：抽一层「面板导航动作」 |
| 游戏推进（面板关闭） | A → **按引擎配置**确认：Tyrano=光标点击、RPG Maker/RenPy=合成 Enter；B → Tyrano 合成 Space；Y → Tyrano 合成 Ctrl 快进（按住态）；D-pad → 按引擎映射 WASD/方向键（按住态） | 面板关闭时 except RB 全透传；游戏推进靠**虚拟鼠标 A 点击注入** + `GamepadRemap` 键替换；无「按引擎配置确认方式」概念 | **设计差异**：参考按引擎定制推进方式，VNLingo 统一走鼠标注入 |
| 按键卡死防护 | `_releaseGameKeys()` / `_releaseCtrl()`：词典打开、销毁、引擎切走时松开按住键 | 虚拟鼠标模式切换即时显示光标；`GamepadRemap` 纯内存替换无按住态概念 | 参考更完备（按住态语义在 Android 侧由游戏自身处理） |
| 重复触发防护 | 边沿检测（`_g_<i>`）+ 导航冷却（80/120ms）+ 一次性动作冷却（500ms） | `EngineVirtualMouse` **250ms 点击冷却去重**（防手柄双发/抖动连点） | 等价 |
| 按键屏蔽 | `gamepad-block.ts`：词典打开期间在 MAIN world 拦截游戏手柄 | 面板展开时消费全部按键（不透传游戏）；音量键放行 | 等价 |
| 手柄捕获式设置 | ShortcutsTab + `useGamepadCapture`（手柄录制写入扁平设置键） | `GamepadSettingsActivity`：五动作捕获式绑定 + 手柄重映射 | 等价 |

## 3.2 值得跟进的设计差异

1. **「按引擎配置游戏推进方式」缺失**：参考对 Tyrano 用「跟随引擎光标点击」、对 RPG Maker/RenPy 用「合成 Enter」，并允许每引擎选择 `enter/click/none` 与 D-pad 模式（`wasd/arrows/none`）。
   VNLingo 只有统一的虚拟鼠标点击注入；对「只认键盘、不接受注入触点的引擎」（历史踩坑：senhana MV / monque ONS 菜单对注入无响应）缺少「合成键盘事件」这一路。
   → 可对齐点：为引擎宿主增加「推进方式」设置（触点注入 / 合成键盘 Enter / 合成方向键），默认按引擎类型给建议值。
2. **D-pad → WASD/方向键（按住态）**：参考会把 D-pad 映射成键盘方向键/WASD 并保持按住；VNLingo 用 D-pad 移动虚拟光标。
   → 两者目的不同（参考适配"只认键盘"的网页游戏），在 Android 侧已有厂商手柄→键盘的 `GamepadRemap`，可把「引擎级 D-pad 语义」作为可选项补齐。
3. **语义动作分组**：参考把动作分 general / nav / reading / dict 四组，UI 与运行时同源；VNLingo 的手柄能力分散在 `GamepadRemap` + `VirtualMouseBindings` + 面板硬编码三处。
   → 可对齐点：新增「面板/游戏动作」统一注册表，UI 与 `handleKey` 同源读取。

# 4. Anki 方案管理

## 4.1 方案模型对照

| 能力 | 参考实现 | VNLingo | 判定 |
|---|---|---|---|
| 方案标识 | 方案 **id**（`AnkiMappingProfileLite { id, scenario, deckName, modelName, fieldMap, serverProfileId }`） | 方案 **名**（`AnkiCardSchemes`：`scheme_<名>` 整包 JSON 快照） | 设计差异：id 更稳（改名不破引用） |
| 多方案集合 | `ankiProfiles`（JSON 数组）+ `ankiActiveProfileId` + `ankiEnabledProfileIds`（**启用集合**） | 方案列表 + `active_scheme`（**无启用集合**） | 参考多一层「启用集合」 |
| 激活回退链 | `resolveActiveProfile`：activeId 命中 → 首个 enabled → 第一个（保证 runtime 恒有值） | `activeName` 命中快照，否则 = 默认活动配置 | 参考更健壮（防悬空引用） |
| 内置预设方案 | `ANKI_PROFILE_SPECS` 四预设：单词(basic) / 句子(basic) / 填空(cloze) / 图片遮挡(image-occlusion) | 无内置命名预设；只有「当前配置」+ 用户自存方案 | **参考更完整** |
| 方案与卡片类型绑定 | 每方案带 `cardType: basic / cloze / image-occlusion`，`CARD_TYPE_ELEMENTS` 决定该类型可映射要素 | **无卡片类型概念**；只有 11 个固定槽位 | **参考更完整** |
| 要素表 | `WORD_FIELDS` **17 项**（core/sentence/extra/media 分组），并区分 `BASIC_ELEMENT_KEYS`(13) | `AnkiCardConfig` 槽位 **11 项**（word/reading/meaning/sentence/page/translation/screenshot/sentence_audio/word_audio/word_tags/source） | 参考多出 cloze/遮挡专用要素与 video/translatedImage |
| 字段自动匹配 | `smartMatchField`：按**别名表**（中英日文别名 + 归一化）模糊匹配模型字段 | 无自动匹配；字段行由用户在弹窗内**逐行点选循环**槽位 | **参考更省事**，VNLingo 更可控 |
| 服务器方案 | `ankiServerProfiles`（host/port 列表，与映射方案分离，可各自启用） | 无（AnkiDroid 本机 API，无 host/port 概念） | 平台必然差异 |
| 方案创建 UI | 设置页方案区：「新建方案」弹窗（勾选要素 + 卡片类型） | 制卡设置弹窗内「存为方案」：快照当前全部配置（`saveCurrent`） | 参考可组合要素，VNLingo 只能整体快照 |
| 方案启停 | `enabledProfileIds` 决定哪些参与回退 | 无（方案不参与自动选择，需手动加载） | 可对齐 |

## 4.2 制卡链路对照

| 能力 | 参考实现 | VNLingo | 判定 |
|---|---|---|---|
| 连接方式 | **AnkiConnect（HTTP 127.0.0.1:8765）** → Anki 桌面；扩展环境经 background worker 规避 CORS | **AnkiDroid ContentProvider API**（`com.ichi2.anki.permission.READ_WRITE_DATABASE`），含未装/未授权引导 | 平台必然差异 |
| 牌组/模板选择 | 自由输入（host/port/deck/model 文本） | 从 AnkiDroid **实时枚举**选择（`getDeckNames` / `getModelNames`）+ 可手输 | VNLingo 更贴合移动端 |
| 模板缺失策略 | 契约要求：**未创建/未启用 → 提示创建，禁止自建模型/推模板/捏造默认字段** | 同契约（`getOrCreateModel` 存在能力但不静默兜底，制卡时报错引导到设置页） | 已对齐（提交 `daae430`） |
| 媒体：单词音频 | 仅外部 TTS（TTS Server / MultiTTS）可用时生成；WebSpeech 跳过 | `word_audio` 槽位（TTS 生成，`SLOT_WORD_AUDIO`） | 等价 |
| 媒体：句子音频 | 音频播放器上下文用 MediaRecorder 录制；其他上下文外部 TTS 生成 | `sentence_audio` 槽位：**游戏配对语音优先，回退 TTS**（`VOICE_AUTO/GAME/TTS/OFF`） | VNLingo 多一路「游戏内配对语音」，更强 |
| 媒体：截图 | `image` 槽位；扩展 `captureVisibleTab`（background 期间隐藏自身 UI 保证纯游戏画面）；TM 走 content `captureScreen` | `screenshot` 槽位：`captureOnCard` 制卡时 PixelCopy 取游戏 Surface（纯游戏画面），可用最近一次手动截图 | 等价（实现路径不同） |
| 媒体：翻译图片 / 视频 / 遮挡标记 | `translatedImage` / `video` / `occlusions` 要素存在 | 无 | 参考更全（视频在游戏内无录制能力，本项目已记待办） |
| 制卡重试/超时 | `withTimeout(…, 6000)` + UI 恢复兜底（防悬浮球永久隐藏） | 制卡在 IO 线程串行，无显式超时；失败 toast | 可对齐（加超时与状态恢复） |
| 标签 | `fm._tags`（默认 `tts-reader`） | 词卡标签 = 命中词典名 | 差异小 |

# 5. 差异汇总（按是否值得对齐分类）

## 5.1 平台必然差异（不对齐）

- 面板/底栏/侧栏的 Web DOM/CSS 实现 ↔ Android 原生 View 覆盖层与窗口承载。
- 手柄轮询 `navigator.getGamepads()` ↔ Android `dispatchKeyEvent`。
- AnkiConnect HTTP ↔ AnkiDroid ContentProvider API；服务器方案(host/port) ↔ 无。
- 厂商手柄预设表 ↔ Android 标准 keyCode 统一。

## 5.2 值得跟进的设计差异（按投入产出排序）

| 优先 | 差异 | 参考做法 | 建议改法 | 预估 |
|---|---|---|---|---|
| P1 | 面板/弹窗按键硬编码，不可配置 | 语义动作 + 映射表（nav/dict 组） | 抽「面板动作」注册表，复用 `VirtualMouseBindings` 的绑定存储与捕获式 UI；`handleKey` 改为查表 | 0.5 会话 |
| P1 | 缺少「按引擎配置游戏推进方式」 | Tyrano=光标点击 / RPGM+RenPy=合成 Enter，可选 `enter/click/none` | 引擎宿主增加推进方式设置 + 合成键盘事件路径（补「只认键盘」引擎的推进） | 1 会话 |
| P2 | 制卡方案是「整包快照」，无要素可组合与卡片类型 | 方案=要素子集 + cardType（basic/cloze/image-occlusion），四内置预设 | 方案模型升级为「要素子集 + 类型」；新增填空/遮挡要素槽位 | 1 - 1.5 会话 |
| P2 | 字段映射需逐行手点，无自动匹配 | `smartMatchField` 别名表（中英日 + 归一化） | 在字段行旁加「自动匹配」按钮，复用别名表（含中英日别名，移动端更实用） | 0.5 会话 |
| P3 | 方案无启用集合与回退链 | `enabledProfileIds` + `resolveActiveProfile` 回退 | 方案存储改为 id + 启用集合 + 激活 id，加载失败按链回退 | 0.5 会话 |
| P3 | 缺 `sdScrollUp/sdScrollDown` 独立绑定 | LB/RB 独立滚动动作（50ms 连发） | 面板动作注册表里拆出滚动上下两项，去掉 R1 长按双语义 | 0.3 会话 |
| P4 | 制卡无超时兜底 | `withTimeout` + UI 恢复 | 制卡链路加超时与状态恢复 | 0.3 会话 |

# 6. 结论

- **剧情文本框**：本项目在交互深度上**不弱于参考**（透明度、历史回放、候选切换、听力模式、两步退出均为独有），主要差距在**键位可配置性**（硬编码 vs 语义动作表）。
- **手柄交互**：覆盖能力接近（捕获式绑定、去重冷却、面板拦截都有），差在两点——**面板动作不可配置**，以及**缺少「按引擎切换游戏推进方式」**（这正是历史踩坑里「注入触点对部分引擎无效」的解法方向）。
- **Anki 方案**：差距最明确——参考是「**方案 = 要素子集 + 卡片类型 + 启用集合 + 自动字段匹配 + 内置四预设**」，本项目是「**方案 = 当前配置整包快照**」，并缺少填空/遮挡两类卡片要素。若后续要支持填空卡/遮挡卡，需要先升级方案模型。
