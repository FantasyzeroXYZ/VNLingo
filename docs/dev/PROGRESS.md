# VNLingo 会话进度记录

> 本文件由 AI 会话维护，记录本仓库功能线的实施进度。配合 `MEMORY.md`（环境/踩坑/决策，同目录）与
> `docs/说明/游戏内提取制卡功能方案.md`（提取功能线方案）阅读。
> 更新时间：2026-10-09

## 2026-10-09 会话（六十四）：手柄查词方式设置 + 未查到不弹提示；虚拟鼠标点击排查

**用户要求三条**：① 手柄在文本框选词查不到词不要弹「未查到」提示；② 新增「移动到即查词 /
按确认键查词」设置（放设置页的手柄设置里）；③ 虚拟鼠标的点击没效果。

**①② 已完成**：

- 新增 `engine/.../com/core/engine/PanelOptions.kt`（prefs `panel_options`，`MODE_MULTI_PROCESS`）：
  `LOOKUP_CONFIRM`（默认，与旧行为一致）/ `LOOKUP_AUTO`；
- `OnsExtractPanel`：`selectUnit(idx, start, silentMiss)` 与 `scanFrom(start, silentMiss)` 增加
  静默未命中分支（只刷新面板状态行 + `DiagLog.debug("dict","miss(silent,gamepad)")`，不弹 toast；
  「未导入词典」仍提示）；新增 `highlightUnit(idx)` 只换高亮不查词；
- 手柄动作路径：`moveTokenSelection` 在「按确认键」模式下只高亮、在「移动即查」模式下高亮+查词
  （静默）；`PANEL_CONFIRM` 命中时以静默方式查词——即**手柄侧查不到词一律不弹窗**；
- 设置页（手柄设置 → 面板动作上方）新增下拉「剧情文本框查词方式（手柄）」+ 说明；三语言各 4 条字符串。

**③ 虚拟鼠标点击排查结论**：

- **Artemis（官方内核）点击不可能生效**：日志实测 `injectHostTouch down failed (official kernel?)`
  + `UnsatisfiedLinkError: injectHostTouch`；代码里的兜底（钩子捕获的 `CArtemisTouch`
  上合成 OnBegin/OnTouch/OnEnd）也走不通，因为 `artemisTouchState()` 拿不到对象（该内核没有钩子）。
  出路：换用 **artemis-compat 内核**（导出 `injectHostTouch`，pluginVersion ≥ 32）。
- **KRKR**：光标移动正常（截图可见光标随 D-pad 移动），合成的 down/up 触摸走
  `surface.dispatchTouchEvent`；本轮多次尝试点击标题菜单未观察到反应，但**无法确认光标是否正好
  落在菜单项文字上**（adb 只能逐格推动光标，步进 ~38px，难以精确对位），因此**点击是否可用
  仍需人工用手柄/鼠标模式实测确认**。

## 2026-10-09 会话（六十三）：手柄链路真机验收（ONS 通过）+ Artemis 缺口定位

**验收手段突破**：脚本点击驱动游戏极不稳（通知栏/SAF 抢焦点），改用**导出的快捷方式蹦床**
直接起游戏：
```
adb shell am start -n com.tyranor.next/com.tyranor.next.ui.game.GameShortcutActivity \
  --es extra_shortcut_id game_<sha256(游戏 uri)>
```
（`shortcut_id` = `"game_" + sha256(uri)`，见 `GameShortcutManager.shortcutId`；uri 从
`databases/game_library.db` 取。这样完全绕过 UI 点击。）

**ONS（ESG-13556）验收结果**：

| 项 | 证据 |
|---|---|
| 呼出 | `input keyevent 103`（RB，绑定表默认值）→ 面板打开 |
| 动作分发 | logcat 逐条对齐：`token_next(22)`、`token_prev(21)`、`confirm(96)`、`nav_down(20)`、`card(100)`、`exit(4)` |
| 焦点独占 | 面板展开时连发 3 次未绑定键 F1(131)，前后截图 `Get-FileHash` **完全相等** → 面板吞键、游戏零推进 |
| 退出 | BACK → `exit` → 面板关闭、游戏恢复 |

**Artemis 缺口（需用户决策）**：面板收起时 RB 到不了 Java（NativeActivity 直读输入、焦点在
native），「手柄呼出」在 Artemis 上不成立（面板窗展开后才可聚焦 → 先有鸡还是先有蛋）。
两条路：① 常驻键捕获窗（全按键改走游戏映射链转发，影响所有 Artemis 玩家，虚拟鼠标模式已验证
可行）；② 保持现状（Artemis 用触摸开面板，开后面板快捷键可用）。**未拍板前不改**。

**未验证**：KRKR（与 ONS 同宿主代码路径，未单独跑）；摇杆/轴事件（adb 无法注入，宿主侧
`handleMotion` 已前置消费）。

## 2026-10-09 会话（六十二）：执行《剧情文本框手柄链路复刻方案》P1–P4

用户要求：按 `docs/dev/剧情文本框手柄链路复刻方案.md` 执行（参考 webgametxt 的四段链路）。

**P1 顺序修正 + 呼出键动作化**
- Artemis `dispatchKeyEvent` / `routeMouseKey` 改为**面板优先**（此前 Artemis 的按键**从不进面板**，
  与 KRKR/ONS 不一致）；
- 呼出键由硬编码 `KEYCODE_BUTTON_R1` 改为 `PanelActionBindings.PANEL_TOGGLE`（默认仍是 RB）；
- 关闭态只查呼出键即 `return false`，不触碰其它键的边沿状态（本实现无 prev 表，天然满足参考
  明确记录的踩坑）。

**P2 面板动作表 + 查表分发**
- 新增 `engine/.../com/core/engine/PanelActionBindings.kt`：15 个动作（呼出/选词/上下/确认/翻译/
  词卡/句卡/滚动/退出/复制/播放/历史），prefs `panel_action_bindings` → `action_<id>` StringSet，
  `MODE_MULTI_PROCESS`，快照式（与 `VirtualMouseBindings` 完全同款，含 bindings/add/remove/reset）；
- `OnsExtractPanel.handleKey` 由 `switch(keyCode)` 改为查表分发；默认绑定与旧行为逐键一致，
  音量键仍放行；命中动作打一条 `panel action …` 日志便于真机取证；
- 面板构造时 `refresh` 两张绑定表；`isExpanded()` / `toggleHistoryOverlay()` 公开给宿主。

**P3 焦点独占（引擎侧完成）**
- KRKR / ONS / Artemis 三个宿主的 `dispatchGenericMotionEvent` 前置 `panel.handleMotion(event)`：
  面板展开时摇杆与轴事件全吞（不驱动游戏、不驱动虚拟光标）；
- Artemis（NativeActivity 直读输入）用方案 §3.2 手段 B：面板窗外包一层键捕获外壳
  `panelKeyCatcher`（`dispatchKeyEvent → routePanelKey`），**面板展开时去掉窗的
  `FLAG_NOT_FOCUSABLE` 让其持焦**（按键于是进 Java 面板），收起时恢复；虚拟鼠标光标窗在面板
  展开时让出焦点；`setKeepNativeFocused(expanded || virtualMouseMode)` 保渲染不停。

**P4 设置页「面板动作」区**
- `GamepadSettingsActivity` 增加与虚拟鼠标同款的捕获式绑定区（15 行、重新绑定、× 移除、
  恢复默认），抽出共用 `BindingActionRow`；三语言各新增 17 条字符串。

**未完成（如实记录）**：P5（手段 C 兜底）按需；**P6 真机验收未完成**——手上没有实体手柄，
无法产生真实手柄事件，且脚本化 `input tap` 驱动「启动游戏 / 打开设置页」反复失手（本轮多次被
通知栏与 SAF 选择器抢焦点）。已做到：编译通过（`:app:assembleDebug` SUCCESS）、三个宿主的
按键与轴事件路径全部接上、动作表与旧行为逐键一致。验收步骤与失败信号已写入方案文档
（`adb logcat | grep "panel action"`）。

**有意行为差异**（来自方案 §3.3 默认表）：`PANEL_CONFIRM` 默认纳入 `ENTER` / `DPAD_CENTER`
（旧实现吞掉且不做事）；`PANEL_HISTORY` 新增动作、默认不绑键。

## 2026-10-09 会话（六十一）：Artemis 存档导出把截图/语音打进 ZIP —— 根因与修复

用户反馈：条目的「存档管理 → 导出 ZIP」里似乎带了音频和图片文件夹。

**取证（解开用户已有的导出包 `Japanese Version_saves.zip`，18.26 MB）**：

```
artemis_extract/voice/*.ogg          ← 11 个：提取面板/内核的语音副本
screenshots/Japanese Version/*.png   ←  8 个：截图
saveg.dat / system.dat / autosave.dat ← 只有这 3 个是真存档
```

根因：**Artemis 的「存档目录」被解析成游戏根目录**，而这个目录里同时躺着
`screenshots/`、`extract/`、`artemis_extract/voice/`（提取面板与内核写的）以及
`root.pfs`/`movie`/`system` 等游戏本体。导出/计数/云同步都把整目录算作存档。

**修复**（`GameSaveManager`）：

1. `excludeFor(ARTEMIS)` 改为**白名单**：只认游戏根下的 `*.dat`（saveg/autosave/system…）。
   目录名不以 `.dat` 结尾 ⇒ 递归不会进入 `screenshots/`、`artemis_extract/`、`extract/`、
   `movie/`、`system/` 等任何子目录；资源包与非存档文件也一律排除。
2. 删掉原 `isArtemisResourceName`（逐条列举 root.pfs/*.pfs/*.xp3/*.arc/system/movie）——
   白名单已覆盖，无需列举。
3. 新增**安全兜底**：除 AVG32/UK2/ARTEMIS 这三个「存档目录=游戏根（靠白名单过滤）」的引擎外，
   任何引擎解析出的存档目录若等于游戏根，直接判为不可管理并提示
   （`save_error_save_dir_is_game_root`）——宁可不可管理，也不要再导出一个装着整个游戏的 ZIP。
4. 存档位置描述改为「Artemis 存档（游戏目录内 *.dat，媒体与资源不计入）」。

**真机验证**：Artemis 游戏「Japanese Version」的存档管理页由「整目录计数」变为
**当前存档文件：3 个**（＝autosave.dat / saveg.dat / system.dat）；导出/导入/删除/云同步
与列表共用同一个 `excludeFor` 谓词（`writeZipContents`/`collectFiles` 对目录同样过滤），
因此 ZIP 里只会有这 3 个 `.dat`。
（SAF 文件选择器不接受脚本点击，最后一步「点保存」需人工确认；ZIP 预期 ~25 KB。）

**给用户的提醒**：已有的 `Japanese Version_saves.zip`（18.26 MB）与解压出的
`Japanese Version_saves/` 目录都含截图与语音，属于旧版本产物，可自行删除重导。

## 2026-10-09 会话（六十）：媒体与存档分家（截图独立入口、音频不保留）

用户要求：不要把图片、音频放进存档管理；音频不用保留；图片在**条目选项**里另开一个截图管理。

**改动**：

1. **条目选项新增「截图管理」**（游戏详情页抽屉，位于「存档管理」与「引擎设置」之间，
   图标用引擎的 `ic_camera`）：打开既有 `ScreenshotManagerActivity`，
   带 `EXTRA_GAME` 只显示该游戏的截图（`GameScreenshots.safeGameName` 与目录名对齐）；
   不传游戏名时显示全部。原来这个页面**没有任何入口**（孤立页面），现在接上了。
2. **音频不再保留**：删掉历史回放的语音落盘缓存（`persistHistoryVoice` /
   `historyVoiceFile` / `trimHistoryVoiceDir`），历史回放改为「档案按名补读 → TTS」；
   新增 `GameMediaCleanup`（engine）在**应用启动**与**面板安装**时清理旧版本遗留的
   语音缓存目录 `cacheDir/ons_extract_history`。
3. **制卡临时媒体即用即删**：截图/语音交给 AnkiDroid 复制后立刻删除
   `externalFilesDir/extract/anki_*.png|wav` 本地副本；启动时也会清理残留
   （`GameMediaCleanup.purgeStaleCardMedia`）——图片归「截图管理」，音频不留。
4. **存档管理不再把媒体算作存档**：`GameSaveManager.excludeFor` 增加媒体扩展名过滤
   （png/jpg/jpeg/webp/gif/avif/wav/mp3/ogg/oga/opus/m4a/aac/flac/mp4/webm/mkv），
   列表计数、导出 ZIP、云同步、删除都不会碰这些文件；**不含 `.bmp`**（ONS 的
   `save*.bmp` 是存档缩略图，属于存档本体）。

**真机验证**：

- 游戏「English Version」条目选项 → 截图管理：显示「暂无截图」（该游戏确实没有）；
- 游戏「ESG-13556」条目选项 → 截图管理：显示「共 3 张」，三张都属于 ESG-13556（过滤生效）；
- 安装新版本后启动 App：`cache/ons_extract_history` 已消失、`files/extract/` 已清空
  （旧语音缓存与制卡临时媒体都被清掉）。

## 2026-10-08 会话（五十九）：修「制卡图片进不了 AnkiDroid 媒体库」

用户反馈：制卡设置里映射了图片字段，制卡后卡片没有图片，疑似图片没被截取、也没进 AnkiDroid 媒体库。

**真机取证**（logcat + 直接看两边媒体目录）：

| 现象 | 证据 |
|---|---|
| 截图本身没问题 | `word card frame captured=true`、`screenshot file=…/extract/anki_shot_2026…png bytes=1685728` |
| 走 `addMediaFromUri(file://)` 失败 | 无 `addMedia(file) ok` 日志；AnkiDroid 进程读不到我们私有目录的 `file://`（Android 11+ scoped storage） |
| 兜底「直拷」写进了**废目录** | `/sdcard/AnkiDroid/collection.media/` 里躺着 3 个今天的 `anki_shot_*.png`；而 AnkiDroid 的真实媒体目录是 `/sdcard/Android/data/com.ichi2.anki/files/AnkiDroid/collection.media/`（自 AnkiDroid 2.16 起） |

即：**直拷兜底「假装成功」**——返回 `<img>` 标记写进字段，但文件不在 AnkiDroid 的媒体目录里，
所以卡片缺图且无从排查。

**修复**：

1. `AnkiDroidHelper.addMedia` 改为**三步降级**且每步记日志：
   `content://`（`FileProvider` + 显式 `grantUriPermission(com.ichi2.anki)`）→ `file://`（老版本）→ 失败返回 null；
2. **删除「直拷 collection.media」兜底**（会伪装成功，比失败更难查）；
3. `preferredName` 去掉扩展名（API 约定不带扩展名），并记录最终标记；
4. 词卡/句卡补日志：图片槽位字段名、是否现截成功、文件字节数、media 标记、最终字段数组；
5. **媒体失败时给用户提示**：词卡 toast 改为「词卡已添加，但图片/语音未能写入 AnkiDroid 媒体库…」；
6. 句卡也按「制卡时自动截图」现截（原先只用最近一次手动截图），顺带去掉词卡里硬编码的
   `TyranorNext` 标签。

**修复后真机验证**（kazurauta，映射 单词→Word、图片→Image）：
```
word card screenshot file=…/extract/anki_shot_20261008_234251.png bytes=1685728
AnkiDroid: addMedia(content) ok: <img src="anki_shot_20261008_234251_3899741127135303637.png" />
word card fields: [Word,…] → [と, , , , <img src="anki_shot_…png" />, ]
```
且该文件确实出现在 AnkiDroid 的真实媒体目录里（`ls` 已确认）。

**顺带修复用户此前生成的卡片**：把旧目录里那 3 张孤儿截图 `adb push` 进 AnkiDroid 的真实
媒体目录（文件名不变），使之前缺图的卡片恢复显示（AnkiDroid 下次打开/检查媒体时收录）。

## 2026-10-08 会话（五十八）：翻译/制卡/TTS 独立页重做为与既有设置页同款样式

用户要求：新增的独立页外观风格必须与原来的设置独立页统一（并写入记忆）。

**问题**：会话五十六那版是「engine 侧 Activity + 自绘顶栏 + 纯 Android View 内容」，
即把一个悬浮弹窗塞进整页，背景、卡片、行的样式都与「应用设置 / 引擎管理 / 云同步」等
二级页完全不同。

**重做**（app 侧 Compose，与既有页同一套壳）：

- `EngineSettingsPageActivity`（app）继承 `AppScreenActivity` + `setAppScreenContent`，
  按 kind 分发到三个 Compose 正文；engine 侧那个 Activity 与 manifest 项删除。
- 新增 `EngineSettingsScreens.kt`：`EngineSettingsPage`（`MiuixSettingsTheme` +
  `MiuixScaffold` + `AppTopBar` + `LazyColumn`，含 `BottomInsetSpacer`）、
  `SettingsCard`（`MiuixCard(glassShadow().glassBorder(), AppComponentCornerRadius)`）、
  `SettingsHint`，以及三个页面正文：
  - `TranslateSettingsScreen`：翻译引擎（下拉）/ 引擎配置（→ engine 弹窗）/ 翻译测试（→ 弹窗）；
  - `TtsSettingsScreen`：语速/音高/音量/引擎/循环次数（下拉）、发音人（→ 弹窗）、
    MultiTTS 服务器（Compose 输入弹窗）、HTTP 模板与试听文本（OutlinedTextField）、
    自动朗读/句读（开关）、试听（→ engine `runTtsTest`）；
  - `AnkiCardSettingsScreen`：牌组/模板（下拉，**只列 AnkiDroid 现有项**，首项「请选择…」）、
    方案（→ 方案管理弹窗）、例句语音源（下拉）、自动截图（开关）、
    要素 → 模型字段（每个要素一行下拉，选项＝「未映射」+ 该模板真实字段）。
  **所有改动即时保存**（与其它设置页一致，不再有「保存」按钮）。
- engine 侧为页面新开三个公共入口：`showEngineConfigDialog` / `showSchemeManagerDialog` /
  `showVoicePickerDialog`，并把 TTS 试听逻辑抽成公共 `runTtsTest`（弹窗与页面共用）。
- engine 的 `buildXxxPage`（View 版）**只保留给游戏内/面板弹窗**用。

**真机验证**：三个页面与既有二级页同款（标题栏、卡片圆角/玻璃描边、Miuix 行控件、页面背景一致）——
翻译页 `.tmp/v14_translate.png`、TTS 页 `.tmp/v14_tts.png`、制卡页 `.tmp/v14_anki.png`；
测试中我误设的牌组/模板（测试 / jidoujisho Kinomoto）与字段映射已从设备 prefs 清回「未选择」。

## 2026-10-08 会话（五十七）：所有「点击循环」的设置项改成下拉选择

用户要求：凡是靠「点一次换下一个值」才能选设置的行，全部改成下拉框。

**新增共享控件层**（engine）：`com.core.ons.SettingsWidgets`
- `dropdownRow(activity, iconRes, label, options, selectedIndex, onSelect)`：图标（可选）+ 标题 + Spinner；
- `switchRow(activity, iconRes, label, checked, onChange)`：布尔项用开关，不用文字行点按切换。

**engine 侧改下拉**（`OnsExtractSettingsDialogs` / `OnsExtractPanel`）：

| 设置项 | 之前 | 现在 |
|---|---|---|
| 翻译引擎（设置页 / 面板） | 8 引擎点按循环 | 下拉（API/本地/Google/Bing/DeepL/百度/Gemini/Claude） |
| 翻译测试 原文/目标语言（两个入口） | 点按循环 | 下拉 |
| TTS 语速 / 音高 / 音量 | 点按循环 0→25→50→75→100 | 下拉（五档） |
| TTS 引擎 | 点按循环 | 下拉（MultiTTS / HTTP / 系统） |
| 朗读循环次数 | 点按循环 0→1→2→3 | 下拉（不循环 / 1 / 2 / 3） |
| TTS 自动朗读 / 句读模式 | 文字行点按切换 | 开关（Switch） |
| 面板 语速 | 点按循环档位 | 下拉 |
| 面板 透明度 | 点按循环 100/85/70/55/40 | 下拉 |

顺带删掉随之无用的循环辅助（`nextStep` / `nextCode` / `cycleIndex`），避免以后又被拿来写循环；
右缘面板的「透明度」快捷按钮仍保留点按循环（它是工具栏快捷键，不是设置行）。

**词典页改下拉**（`DictionaryScreen`）：「查询语言」「汉字优先」「词典语言范围」由 `CycleRow`
（点按切换）改为 `ChoiceRow`。

**踩坑**：最初用 Miuix 的 `OverlayDropdownPreference` 做 `ChoiceRow`，在**词典管理这个 Compose
Dialog 里点不开**（连续两次点击截图完全一致）；改用 Material3 `ExposedDropdownMenuBox`
（同一窗口内的 Popup）后正常。

**真机验证**：TTS 页 语速/音高/音量/引擎/循环次数 均显示下拉箭头、自动朗读与句读为开关；
翻译页 翻译引擎 下拉展开列出 8 个引擎；词典管理 查询语言下拉展开列出 6 项，选「中文」后
`shared_prefs` 写入 `dict_query_lang=zh`，「汉字优先」按条件隐藏，改回「自动」后写回 `auto`。

## 2026-10-08 会话（五十六）：翻译/Anki/TTS 设置改独立页（不再悬浮弹窗）

用户要求：设置页的「翻译设置 / Anki 设置 / TTS 设置」入口点击后进入**独立页**，
与「引擎」等其它入口一致，不要弹悬浮窗。

做法（复用既有内容，避免重写一套 UI）：

- `OnsExtractSettingsDialogs` 把三个弹窗的**内容构建**与**保存动作**拆出来：
  `buildTranslatePage / buildAnkiCardPage / buildTtsPage` 返回 `Page{content, titleRes, save, hasSave}`；
  原 `showXxx()` 改为「用同一份内容弹窗」，游戏内/面板入口行为不变。
- 新增 `EngineSettingsPageActivity`（engine 模块 + manifest 注册）：顶栏「‹ 返回 / 标题 / 保存」
  + 内容滚动区，保存后 toast 并结束；翻译设置这类点了就生效的页**不显示保存按钮**
  （`hasSave=false`）。状态栏/导航栏与其它页一致地设成浅色。
- 设置页三个入口改为 `startActivityWithPageTransition(EngineSettingsPageActivity.createIntent(kind))`。

**真机验证**：三个入口分别打开独立页——翻译（翻译引擎 / 引擎配置 / 翻译测试，无保存按钮）、
TTS（语速/音高/音量/发音人/MultiTTS 服务器/引擎/HTTP 模板/自动朗读/句读/循环/试听）、
制卡设置（牌组/模板下拉「请选择…」、语音源下拉、自动截图开关、要素→模型字段映射）；
点制卡设置的「保存」写入配置并返回上一页。

## 2026-10-08 会话（五十五）：制卡要素名对齐参考（不再自造）

用户指出：字段映射的内容不要乱加，对照 web game text 有的映射名称即可。

核对参考：`web game text` 的 `src/domain/anki/field-mapper.ts` 只是 re-export
`@trackreader/domain/anki/field-mapper`，源头在 TrackReader
`packages/domain/src/anki/field-mapper.ts` 的 **`WORD_FIELDS`**（+ basic 方案用
`BASIC_ELEMENT_KEYS`）。据此把我们的要素表改成**同名同标签**：

| 参考 key / label | 我们之前 | 现在 |
|---|---|---|
| word 单词 | word 单词 | 同 |
| reading 读音 | reading 读音 | 同 |
| **definition 释义** | meaning 释义（自造 key） | 改 `definition` |
| **audio 单词音频** | word_audio 单词语音 | 改 `audio` |
| **sentence 句子** | sentence 例句 | label 改「句子」 |
| **translation 句子翻译** | translation 译文 | label 改「句子翻译」 |
| **sentenceAudio 句子音频** | sentence_audio 例句语音 | 改 `sentenceAudio` |
| source 来源 | source 来源（游戏） | label 改「来源」 |
| wordTags 单词标签 | word_tags | 改 `wordTags` |
| **deinflection 词形还原** | deinflection 还原路径 | 同 key，label 改「词形还原」 |
| **image 图片** | screenshot 截图 | 改 `image` |
| — | page 整页（自造） | **删除** |
| — | match_type 命中方式（自造） | **删除** |

其它对齐：默认映射改为参考初值 `{word: 'Front', definition: 'Back'}`；读取时把旧 key
（meaning/screenshot/sentence_audio/word_audio/word_tags）**迁移**到新 key，page/match_type
丢弃；句卡不再把「截图+语音+整页」硬塞进前两个字段，改为与词卡同一套映射（句子/句子翻译/
句子音频/图片/来源），一个要素都没映射时提示先配置。

**真机验证**：制卡设置里要素行依次为 单词/读音/释义/单词音频/句子/句子翻译/句子音频/来源/
单词标签/词形还原/图片（截图见 `.tmp/v11_elements3.png`）；牌组/模板下拉首项仍是「请选择…」。

## 2026-10-08 会话（五十四）：彻底去掉预设牌组/模板名

用户指出：不要再预设自定义的牌组名/模板名（TyranorNext 之类）。根因是**默认值即隐式预设**：
`AnkiCardConfig.deck()/model()` 用 `getString(key, DEFAULT_DECK)` 兜底，界面于是显示一个
用户从未选过的名字；用户在弹窗里点确定后这个「预设」就被真正写进配置，再往后制卡就落到
那个牌组/模板上；更早的代码还有 `getOrCreateWordModel()/getOrCreateDeck()` 会自动在用户的
AnkiDroid 里**创建** "TyranorNext Word" / "TyranorNext"。

改动（提交见下）：

| 位置 | 之前 | 现在 |
|---|---|---|
| `AnkiCardConfig` | `DEFAULT_DECK="TyranorNext"` / `DEFAULT_MODEL="TyranorNext Word"` 兜底 | 常量删除；未配置返回**空串** |
| `AnkiDroidHelper` | `DEFAULT_DECK/DEFAULT_MODEL/DEFAULT_WORD_MODEL` + `getOrCreateDeck/getOrCreateModel/getOrCreateWordModel`（自动建） | 全部删除；新增 `findDeck/findModel/getWordModel`（**只查不建**） |
| 制卡设置弹窗 | 下拉里预先选好配置值；未配置时显示预设名 | 下拉首项「请选择…」（空=清除配置）；未选模板时字段区只显示「尚未选择模板…」提示，不再显示伪造的默认字段行 |
| 词卡/句卡制卡 | `getOrCreateDeck()` 兜底建牌组；句卡用硬编码 `DEFAULT_MODEL` 建模板 | 未配置 → toast「请先在「设置 → Anki 设置」里选择牌组与模板」；只认 AnkiDroid 里已存在的牌组/模板，找不到就提示重选；句卡按所选模板的字段顺序填（首字段=当前句，次字段=截图/语音/整页） |
| 句卡标签 | 固定写 `TyranorNext` 标签 | 不再写标签 |

**真机验证**：清空设备配置后打开制卡设置 → 牌组/模板均显示「请选择…」，字段区显示
「尚未选择模板…（本应用不预设、不自动创建模板）」；此时点词典页「直接制卡」→ toast
「请先在「设置 → Anki 设置」里选择牌组与模板」；在下拉里选真实牌组/模板后字段行按该模板
重建（并自动剔除该模板里不存在的旧映射），按取消不写任何配置。

## 2026-10-08 会话（五十三）：词典页右上角「直接制卡」

用户要求：导航词典页右上角加一个直接输入制卡的入口，点开显示设置好的模板字段，
手输/粘贴内容就能制卡。

- **入口**：`DictionaryScreen` 顶部栏 `trailing` 增加 `TopBarIcon`（图标用 engine 的
  `ic_card`，在「词典管理」左侧）；点击 → `OnsExtractSettingsDialogs.showManualCardDialog()`。
- **弹窗**（engine，`直接制卡`）：读当前配置的牌组/模板 → `findModelId` +
  `getModelFieldNames` 取**真实模板字段**逐个渲染输入框（多行，可长按粘贴）；
  字段名后标注它接的要素（如 `Word（← 单词）`），未映射字段不标注；
  顶部显示目标「牌组 X · 模板 Y」；底部提示写入说明。
- **制卡**：走与游戏内词卡同一条链路——`getOrCreateDeck(deck)` + `addNote(modelId, deckId,
  按模型字段顺序的 values, null)`（后台线程 + 主线程 toast）；空表单拒绝并提示；
  AnkiDroid 未装/未授权/模板缺失各有明确提示。
- **验证**（真机 alioth）：点入口 → 弹窗列出 TyranorNext Word 的 6 个真实字段
  （Word/Reading/Meaning/Sentence/Image/Audio，前四个带要素标注）→ 填 `VNLingo-third-try`
  → 点「直接制卡」→ toast「已制卡」；随后在 AnkiDroid 卡片浏览器确认该笔记存在
  （Sort Field=VNLingo-third-try、Deck=TyranorNext、Note Type=TyranorNext Word、
  Created 21:08）。**测试卡已删除**，TyranorNext 回到 0 张。
- 三 locale 文案：直接制卡 / 目标 / 字段标注 / 提示 / 空表单 / 成功 / 失败。

## 2026-10-08 会话（五十二）：制卡设置改下拉框（S1：字段映射「要素 → 模型字段」）

用户要求：制卡设置项不要点行循环，改成下拉选择；牌组/模型只能从 AnkiDroid 里有的内容选，
不允许自定义手输。对应 `docs/dev/设置项补全与交互改造方案.md` §4.1/§4.2 的 S1 批次。

- **牌组/模型**：`EditText` + 「选择」按钮 → **下拉框**，选项来自 `getDeckNames()` /
  `getModelNames()` 的实时结果；**移除手输入口**（按用户要求偏离方案原文的「可手输」）；
  配置值在 AnkiDroid 里已不存在时提示重新选择，读取失败时保持原配置不动（不写脏值）。
- **字段映射方向反转**（方案 §4.2 核心改动）：原来是「遍历模型字段 → 点行循环 12 档槽位」，
  现在每行一个**要素（内容槽位）**，右侧下拉＝「未映射」＋该模板真实字段；内部状态从
  `字段→要素` 改为 **`要素→字段`**（与 `AnkiCardConfig` 存储格式一致）。
- **冲突提示 + 一键解除**（§4.2 细节 3）：同一字段被多个要素选中时，要素行上方出现
  「多个要素指向同一字段：X」+「解除占用」；提示是常驻控件、选择时实时刷新（不重建行）。
- **离线可编辑**（§4.2 细节 4/5）：读取成功后按模型名缓存字段列表
  （`AnkiCardConfig.lastModelFields`），AnkiDroid 未装/未授权时沿用缓存并提示，
  无缓存才退默认四字段。
- **例句语音源**改下拉（4 档）；**自动截图**改 `Switch`。
- 保存语义：`slot→field` 只保留当前模板真实存在的字段（换模板不残留旧字段）。

**真机验证**：牌组下拉列出真实牌组（TyranorNext / 数学::线性代数 / 网络::… 等）、
模型下拉列出真实模板（TyranorNext Word / 图片遮挡 / Simple Model-c0123 等）；
选「关闭」并确定 → `anki_card_config.xml` 写入 `voice_source=off`（随后已还原为 auto）；
把「还原路径」映射到 `Audio` → `field_map` 写入 `"deinflection":"Audio"`（回滚后恢复）；
制造冲突（单词与译文都选 Word）→ 警示行出现 → 点「解除占用」→ 译文回到「无」，
且按「取消」时落盘配置不变。截图：`.tmp/v8_ankidlg2.png`、`v8_conflict4.png`、
`v8_conflict_fixed.png`。

## 2026-10-08 会话（五十一）：方案逐条复查补漏 + 布局修复

用户指出「不全，布局乱」，于是按方案 §3/§5/§6 **逐条**复查，补了原先被当成「遗留」的四项：

1. **§3.5 工具函数移植**：新增 `JapaneseUtil`（`isCodePointKanji/Kana/Japanese`、
   `isStringEntirelyKana`、`convertHiraganaToKatakana`、`convertKatakanaToHiragana`、
   `convertHalfWidthKanaToFullWidth`、`getKanaMoraCount`），与参考 `japanese-util.ts`
   打包导出**逐例对比 66/66 一致**（过程中发现我按常识多加了半角カナ/片假名拡張范围，
   与参考差 3 个码点 → 改回严格对齐，另开 `isCodePointKanaExtended` 供语言分派用）。
2. **§5「中文模式全角/NFKC 变体可命中」**：参考会丢弃 `score=0` 自匹配，zh 又无 transforms，
   变体到不了查词层 → 在查询层补 ⑥ 变体兜底（term + reading 各查一次），
   `lookupDetailed` 与面板扫描都接上。
3. **§3.4 词典页 chips 读 scope**：`Group` 新增 `scope`（查询时 LEFT JOIN `dicts` 取回），
   chips 优先读 scope，universal 退回脚本启发式。
4. **§6 文档同步**：README 功能总览、`docs/guide/游戏内提取制卡功能方案.md` 词条、MEMORY
   语言分派踩坑都已更新。

**顺带修掉的真 bug**：旧预处理把半角浊点判成 `\uFF8E`（应为 `\uFF9E`），
`ﾂﾂﾞｸ` 永远还原不出 `つづく`；现改由 `JapaneseUtil` 承担（顺带删掉重复实现）。

**新增/复跑 harness**：`ProbeLookup`（真实词典库 31MB/66545 条）**14/14 PASS**——
term / reading（つづく→続く）/ 规则还原（食べちゃった→食べる score=2）/
变体兜底（ﾂﾂﾞｸ→つづく→続く）/ 全角英数变体 / scope 过滤（universal 恒可见、scope=ja 在 ko 下不可见）；
`ProbeJpu` **66/66 一致**；`ProbeDeinflect` 复测 **15/15 一致**。

**布局修复**：词典管理弹窗改为「已导入词典（N，纯文本小标题）→ 词典卡 → 导入词典 →
查词设置 → 查询测试 → 词形还原语言包」，去掉卡中卡（`DictRow` 自身是卡片），
真机截图确认（`.tmp/v7_layout3.png` / `v7_layout4.png`）。

## 2026-10-08 会话（五十）：词汇处理移植 V6（面板/制卡对接）+ 方案收尾

- **链路打通**：`CandidateMatch.deinflectionPath` 新增 → `ScanHit` 带
  `matchType/path/lemma` → 面板 `defMatchType/defPath/defLemma`（命中写入、翻句与
  关闭释义清空）→ ①释义悬浮窗标题 ②制卡槽位。
- **释义悬浮窗**：`OnsDictOverlay.show(..., detail)` 新增可选 detail——查表还原显示
  「查表还原 <lemma>」、规则还原显示「词形还原 <lemma> · <path>」，普通命中不追加。
- **制卡**：`AnkiCardConfig` 新增 `SLOT_DEINFLECTION`（还原路径）与
  `SLOT_MATCH_TYPE`（命中方式），`makeWordCard` 填值；制卡设置弹窗槽位循环表
  同步加两项 + 三 locale 文案（还原路径 / 命中方式）。
- **补齐**：`scanByWordTwoPhase` 原用 `deinflect()` 丢路径，改 `deinflectDetailed()`
  并把「还原句车队」与 `Result` 并行记录；`DiagLog` 查词命中行追加 `match=`/`path=`。
- **真机验证**：kazurauta 点选「見て」→ `hit: 見て groups=1 in 19ms
  match=deinflect-term path=v1`；释义悬浮窗标题实测「見る  词形还原 見る · v1」
  （截图）；「あれ」这类命中为 `match=term` 且无后缀。
- **方案收尾**：V1（规则 0 差异）、V2（M1+M6）、V3（M4）、V4/M3（全语言预处理引擎侧）、
  V5（M2 语言包 + 查表还原）、V6（面板/制卡）全部落地；遗留项（词典页 chips 未按
  scope 过滤、真实 simplemma 包未端到端实测）记入待办。

## 2026-10-08 会话（四十九）：词汇处理移植 V5（语言包 + 查表还原 M2）

- **双型包模型**（对齐参考 `packs/types.ts`）：`rules`（ja/en/ko，规则在 assets，
  可启停、可卸载=打标记、可恢复）+ `table`（用户导入的反查表，可启停/删除）。
- **新增 `engine/.../ons/deinflect/`**：`DeinflectPackStore`（独立 SQLite
  `deinflect_packs.db`：`pack_meta` + `pack_entries`；列表/启停/卸载恢复/删除/
  覆盖式事务导入/单查/**批量查**；`initAt(path)` 供无 Context 的 harness 验证）、
  `DeinflectPackImporter`（zip `lemma_pack_v1.json`+`index.json` / 裸 JSON 三种形态 /
  JSONL；清洗非法项、lemma 去重、空 inflected 丢弃）。
- **候选顺序接入 ③**：查表还原**先于**规则还原——`lookupDetailed` 在 reading 之后、
  规则还原之前查表并标记 `table-deinflect`；面板 `tryMatchCandidate` 用
  `lookupTableLemmasBatch` + 同一次 batch term 查询，`CandidateMatch` 增 `matchType`。
- **失败语义**：语言包未安装/停用/未命中一律 null/空表，整段 try/catch，绝不影响查词；
  导入是覆盖式单事务（失败不留残库，带进度回调）。
- **UI**：词典管理弹窗新增「词形还原语言包」区（导入 + 三内置包启停/卸载/恢复 +
  table 包条数/启停/删除）；**顺带修掉引入的布局问题**——弹窗内容原本不可滚动，
  新卡片会把词典行挤出可视区（改 `verticalScroll`，词典列表 LazyColumn→forEach，
  避免同轴嵌套滚动崩溃）。
- **验证**：真机 `ProbePack` harness **33/33 PASS**（默认启用/导入覆盖/命中与未命中/
  批量/停用/删除/内置卸载恢复/导入器 JSON·JSONL·zip）；UI 截图验证三包渲染与
  卸载→「已卸载」+恢复→启用；`deinflect_packs.db` 已生成。
- **未完成**：V6（面板展示 matchType/还原路径、制卡 `deinflection` 要素填充）。

## 2026-10-08 会话（四十八）：词汇处理移植 V3（查询语言设置 M4）

- **设置层**：`EngineSettingsStore` 新增 `dict_query_lang` / `dict_reading` /
  `dict_han_priority`（白名单规范化，默认 auto/true/ja）；`PerGameSettingsStore`
  新增 `F_DICT_QUERY_LANG` / `F_DICT_READING` 每游戏覆盖；
  `EngineSettingsResolver` 合并为 `dictQueryLang` / `dictReading`。
- **下发链**：`EngineLauncher.buildIntent` 给所有引擎 intent 加
  `LaunchContract.DICT_QUERY_LANG` / `DICT_READING`；`OnsExtractPanel` 构造时读取，
  汉字归属读全局多进程 prefs。真机日志
  `OnsExtractPanel: dict query lang=ja reading=true han=ja` ✓。
- **语言判定统一**：新增 `OnsDeinflector.detectLanguage(q, hanPriority)`
  （假名→ja、谚文→ko、汉字→hanPriority 默认 ja、其余→en），`selectLang` 改走它。
- **消费点**：面板 `tryMatchCandidate` 改用 `deinflectDetailed(cand, 生效语言)`
  （候选**按 score 升序**，替换字典序）+ 读音门控改
  `dictReading && 语言==ja && 长度>1`（对齐参考 `skipReading`，汉字词条也能按读音命中）；
  `searchMatchedInner` 同语言；`OnsDictStore.setQueryLanguage` 让**非 auto 时查询范围
  只保留 universal + 同语言词典**（范围缓存键含语言）。
- **词典 scope（G8）**：`dicts.scope` 列（存量库探测后 ALTER 补列，默认 universal；
  读不到一律 universal，不会因缺 scope 查不到）+ 词典行「语言范围」循环按钮。
- **验证**：查词设置区渲染 ✓；点「查询语言」→ 落盘 `dict_query_lang=ja` ✓；
  非 auto 时「汉字优先」隐藏 ✓；面板读到 extra ✓；V2 的参考对照 **15/15 复测仍 0 差异**。
- **遗留**：词典页结果语言 chips 仍是启发式（未按 scope 过滤结果，需 Group 带 dict_id）；
  V5（语言包 + 查表还原）、V6（面板/制卡对接）未开始。

## 2026-10-08 会话（四十七）：词汇处理移植 V1/V2（规则核对 + 还原结构 + 查询测试）

执行 `docs/dev/词汇处理完整移植方案.md`；参考实现 `D:\code\test-JavaScript\TrackReader`
（`packages/domain/src/yomitan/deinflection`，注意是「`.ts` 后缀 + 纯 JS/JSDoc」）。

- **V1 规则完整性（已完成，0 差异）**：参考描述符用 esbuild 打包导出（Node 直接跑会因
  `./transforms.js` 说明符找不到 `.ts` 而失败），与现网 assets 做**语义 diff**：
  ja 22 条件/36 组/**439 规则**、en 5/6/**40**、ko 6/17/**124**、zh 1/0/0、vi 1/0/0，
  **逐字段一致**，无需修正。
- **V2 M1 还原结果结构（已完成）**：`OnsDeinflector.Result{term, rawSource,
  conditions, path, score}` + `deinflectDetailed(q[, lang])`，按 score 升序、同分按
  term 字典序；`path` 是完整条件链（参考只有单终止条件 + 未实现的 trace），
  `pathString()` 供面板/制卡显示 `-e→v1`；旧 `deinflect()` 保留；缓存改存 detailed
  且键含语言。
  **对照验证 15/15**：参考侧（esbuild 打包 `deinflectWithPreprocess`）与 Java 侧
  （javac + d8 → 真机 `app_process` 跑，喂同 5 份 JSON）的 `term:score` 集合完全一致，
  含方案验收用例 `食べちゃった → 食べる:2`。
- **V2 M6 查询测试（已完成）**：`OnsDictStore.lookupDetailed(...)`（① term ② reading
  ③ 查表还原[待 V5] ④ 还原→term ⑤ 还原→reading + trace）与 `DetailedMatch`；
  词典管理弹窗新增「查询测试」区（输入/测试/诊断轨迹/结果预览），真机截图确认渲染；
  日文输入 adb 无法注入，语义验证走 harness。
- **M3 全语言预处理（引擎侧已落地）**：预处理管线改为**按 JSON 名字**驱动，
  补 zh 的「全角英数→半角」「繁体→简体（实为 NFKC）」，未知名保留原文。
  局限（与参考一致）：zh 无 transforms，预处理变体不进候选；若要让中文全角/NFKC
  真正命中，需在查询侧把变体当字面 term 试查（记为后续增强）。
- **未完成**：V3（查询语言设置/读音开关/词典 scope）、V5（语言包 + 查表还原）、
  V6（面板/制卡对接）。

## 2026-10-08 会话（四十六）：KRKR 文本碎片/重复/选项误提取（第二轮）

执行 `docs/dev/KRKR文本碎片与选项误提取方案.md` 的 B1–B4，并**否证**了第一轮的
「换类瞬间包装跑通」结论。用户报告：提取文本偏碎片、大量重复、像选项而非对白。

- **B1 tag 取证：观测点不可达（关键否证）**。按方案升级探针为取证版
  （`WRAP_V5`：顶层子类 + 逐帧守望 `global.KAGParser` + 前 64 个 tag 打
  `[probe] tag=<hex> len/cp/elm`）。真机实测：
  - `parserChange` 只有 **1 次**（frames=1），此后游戏**再也不动** `global.KAGParser`；
  - 我们写回的类 **从未被实例化**（`wrapCtor=0`）、其 `getNextTag` **从未被调用**（`gt=0`）
    → 游戏构造解析器**不经过 `global.KAGParser`**，tag 取证无法进行；
  - 顺带修正第一轮记录：`せいちせいちせいち` / `のものもの` **来自 FT 字形通道**，
    不是解析器通道（`onKrkrText` 对 `[FTLN]`/`[TNEXT]` 打同一条日志，此前无法区分）。
  结论：解析器包装路线对 KAGParserEx 类游戏**不成立**；B3（按 tag 实证重写）失去前提。
- **B2 字形通道过滤与降级（已完成并验证）**：①长度下限（<4 码点不上行）
  ②同页去重（最近 8 条环形集合）③堆叠串判定（串首同一 1–3 码点单元重复 ≥3 次）
  ④`glyph-frozen` 降级标记（字形请求 ≥10s 无增长）。全部锁内 O(1)/短串比较，
  防 ANR 设计未动。
  - **自曝自修**：首版按方案写的「派生结果连续 N 次相同即冻结停发」**误杀正常对白**
    ——打字机停顿时派生与 raw 都不变，正是该上行的时刻（kazurauta 从 10 条掉到 0 条）。
    修正为「冻结只做诊断、不参与门控」。
  - 验证：kazurauta 10 条完整句无回归（「ねえねえ！　あれ見て！」、
    `キッチンカーではタピオカミルクティーが大人気。`…）；tsukikage 过滤前 4 条碎片 +
    `きさらぎさらぎさらぎさらぎきさらぎれいれい` 类堆叠串进句栏，过滤后**仅 1 条**。
- **B4 开关接线（已完成）**：三个引擎提取钩子开关与 KRKR 文本输出方式**第一轮已补**
  （方案 §4.4 该条过时）；本轮补上唯一缺的 `kr_extract_probe`（解析器层提取·实验性）
  开关 + 三 locale 文案（明示可能引导失败、可关闭回滚）。
- **后续**：真正可用通道仍是内核级（krkr2-main 源码钩子），或从游戏脚本侧找真正的
  解析器入口（该游戏 XP3 被 PackinOne 类插件加密，未解出）——记入待办。

## 2026-10-08 会话（四十五）：KRKR 提取失效定位（真机判据）+ 全局提取钩子开关

- **用户反馈**：①tsukikage（KRKR）hook 不到文本；②游戏条目与设置页里没有
  「文本提取钩子」开关。执行 `docs/dev/KRKR提取失效与钩子开关方案.md` 的 B1–B4。
- **B1 诊断埋点（已落地）**：native 新增 `krkr_set_extract_diag(bool)` 开关
  （经 `NativeBridge.setExtractDiagEnabled` 按 `DiagLog.isEnabled()` 在启动期下发），
  限频埋点：`ft char`（码点/字形索引）、`ft glyph`、`label set`（长度+首码点）、
  每 2s `ft state`（raw/known/idx/glyph/nextchar/label 计数）与 `ft raw`（尾部原文）；
  `onKrkrText` 入口加 `krkr text raw` 原始日志。另外把 `FT_Load_Glyph` 也接上——
  以「逐码点问原 `FT_Get_Char_Index`」惰性建立 `glyph_index → 码点` 反向映射
  （42387 项，200ms 内一次建成，读侧无锁），两条通道共用相邻去重。
- **B1 判据（真机 alioth，两游戏对照）**：
  - kazurauta（普通 KAGParser）：进入正片后 idx/glyph 计数从 168 涨到 909，
    `ft line:` 正常（「だってわたしたち、友達だもん」…）→ 通道可用、无回归；
  - tsukikage（KAGParserEx + textrender/layerExDraw/PreRenderFontEx）：
    钩子全部武装成功（虚表 slot 173、三个 FT GOT 槽 patched=1），但
    **idx/glyph/label 计数在标题菜单、18 禁告知、对白全程完全冻结**
    （144/144/217），`ft line`、`krkr text raw`、`krkr text` 全为 0 条。
  - 结论：**H1 成立且强于原判据**——该游戏正文根本不调用 libgame.so 的
    FreeType（Get_Char_Index / Load_Glyph / Load_Char / Get_Next_Char 全零调用），
    因此 A1/A2 对本游戏无解；**H2（页推导门槛）、H3（Java 过滤）被排除**
    （原始链恒为 0，上游就断了）。剩余可行通道 = 解析器层 TJS 发射器或带钩内核。
- **B2 全局开关（已落地）**：`SettingsScreen` 引擎设置详情页新增三个全局开关
  `文本提取钩子`（KRKR/ONS/Artemis 各一，原先三个 setter 零调用方）；
  三 locale 补 `settings_extract_hook_summary` / `..._unsupported`。
  KRKR 的 `文本输出方式` 全局默认（`kr_extract_tjs` 开关）原已存在，保留。
- **B4 单游戏页核对（真机截图）**：tsukikage 的「游戏条目 → 引擎设置 → KRKR」
  卡片中 `文本提取钩子`（跟随全局（开））与 `文本输出方式`（运行时文本流）
  **都在正常渲染**——P2 的「看不到」是入口认知问题，不是渲染缺陷；原方案
  §2.5 记的「SDL3 下被 if (!isSdl3) 隐藏」与代码事实不符（该 if 只包住操作卡片）。
  另按 §3.3-E 在 SDL3 内核下把两行换成禁用态说明行。
- **产物**：`krkr_extract_hook.cpp` / `krkr_bridge.cpp` / `NativeBridge.kt` /
  `KirikiroidLauncherBaseActivity.java` / `SettingsScreen.kt` /
  `PerGameSettingsScreen.kt` / 三 locale strings；方案文档新增 §6.5 执行记录。
- **B5 零风险探测（用户选「先做零风险探测」，已完成）**：新增 `kr_extract_probe`
  全局开关（默认关），开启后向 patch.tjs overlay 追加纯 ASCII **只读**探针
  （不替换任何类），输出走 `Debug.message` → logcat（tag `cocos2d-x debug info`）。
  实测结论：
  - patch.tjs 阶段 `global.KAGParser` 已存在、`global.KAGParserEx` **不存在**；
  - `System.addContinuousHandler` 存在且**非致命**（连续跑满 901/1800 帧游戏正常）
    ——推翻旧结论「延迟手段本身致命」：致命的是 `Timer`；
  - `global.KAGParser` 在 patch.tjs 之后**第 1 帧**被换掉，但换掉后
    `global.KAGParserEx` 仍不是全局名（类未暴露为全局）。
  - TJS 方言坑：不支持 `for (var k in obj)` 与 `in`；访问不存在的全局会抛异常并
    打出约 2 万行反汇编转储（不能靠 try/catch 轮询未注册全局）；探针必须纯 ASCII。
  - 关闭 pref 后 overlay 不再生成（`KRKR patch overlay disabled`），控制台恢复
    632 行基线——**可安全回滚**。
- **B5 换类瞬间包装（用户确认后实施，实验跑通）**：把探针升级为
  `TYRANOR_NEXT_KRKR_EXTRACT_WRAP_V*`——patch.tjs 注册 continuous handler，
  第 1 帧检测到 `global.KAGParser` 被替换时把**顶层子类**写回。逐轮实测：
  - 帧内声明子类 ✗（TJS2 不允许函数体内声明类）；顶层子类 ✓；换后类的方法
    **可读可写** ✓；但从被替换方法里调用原方法（`&(orig incontextof this)` /
    `.call(this)` / `orig()` / 临时还原类槽后 `this.getNextTag()`）**四种约定全抛** ✗；
  - 第 1 帧写回顶层子类 ✓ `assignClass=ok`：**游戏正常运行并显示真实对白**
    （「俺こと卯月誠一が交わした、大切な契約の言葉。」），提取桥收到
    `krkr text: せいちせいちせいち` / `のものもの`——整条通道（`[TNEXT]` → 控制台
    → Label 钩子 → `onKrkrText` → 提取桥）**打通**；
  - **未完成**：文本偏碎且稀少（推进 10 次仅 1 条），按标签切分的缓冲逻辑对 Ex 的
    tag 流不适用，需按 KAGParserEx 实际 tag 形态重做重建/切分。
  - 真机验证后已把 `kr_extract_probe` 从 prefs 移除，回滚到默认行为（overlay 不再生成）。

## 2026-10-08 会话（四十四）：查词性能优化 B1–B4 实施 + 真机实测

- **B1 索引**：`OnsDictStore.migrate()` 补 `idx_entries_reading(reading, dict_id)`
  （老库启动即自动补建，无需重导）；删掉实际未被任何查询使用的单列
  `idx_entries_term`；导入收尾与建索引后各跑一次 `ANALYZE`（此前 `sqlite_stat1`
  为空，规划器只能「碰巧」选对计划）。真机存量 66545 词条库首次打开
  **同步建索引 297 ms**（日志 `reading index ready (inline): 297ms`），
  库文件 31.08 MB → 31.17 MB。
- **B2 收窄与缓存**：面板读音查询收窄为「含假名且长度 > 1」；`OnsDeinflector`
  加 512 条 LRU 还原缓存（同一句反复点选重复率高）；`OnsDictStore` 查询范围
  （scope）加 3s TTL 缓存，本进程词典增删/启停/切当前即时失效；连接参数补
  `PRAGMA cache_size = -16384`（16 MB）与 `temp_store = MEMORY`。
- **B3 批量 IN 查询**：新增 `batchTermLookup` / `batchReadingLookup`——一次
  `IN` 取回「原形 + 全部还原形」，面板 `tryMatchCandidate` 由
  「1 + 1 + 2N 次串行查询」降为「term、reading 各一次」；命中优先级
  （原形 term → 原形 reading → 各还原形 term/reading）保持不变，单值路径与批量
  共用同一 SQL 与分行实现，避免两套逻辑走偏。
- **B4 后台建索引**：存量库词条数 > 10 万时**另开一条连接**在后台建索引
  （同连接做 DDL 会把查询一起堵住，「后台」名存实亡）；主连接照常查词，
  状态经 `isReadingIndexBuilding()` 暴露，词典页显示「正在优化词典库索引…」；
  导入路径复用既有 Progress 回调提示。
- **真机实测（红米 K40 / Android 11 / SQLite 3.28 / 真实 6.7 万词条库，
  新增 `.tmp/perf/droid/perf/Probe5.java` 同库四组对照）**：
  - 面板整句逐字扫描：无索引 374.6 / 350.8 ms → 补索引 **5.88 / 5.72 ms**
    （同一份库上的旧串行路径：无索引 603 / 537 ms、有索引 9.7 / 7.3 ms）；
  - 查询计划：`SCAN TABLE entries + USE TEMP B-TREE FOR ORDER BY` →
    `SEARCH TABLE entries USING INDEX idx_entries_reading (reading=? AND dict_id=?)`；
  - 正确性：整句 41 个候选逐一对照新旧路径，**命中零不一致**；
  - 应用内端到端（真机 Kazura Uta / KRKR，点选查词）：`dict.log`
    实测 **8–31 ms/次**（`hit: かえした groups=5 in 28ms`、`続く in 8ms` 等），
    释义卡与词形还原命中（見て → る）与优化前一致。
- 面板查词补耗时日志（`hit: <词> groups=N in Mms` / `miss in Mms`），后续回归可直接看数值。
- 设备 `/data/local/tmp` 测试产物按方案清理：794 MB → 80 MB（保留
  `bench5.dex` + deinflect assets + `s_app.db`/`s_idx.db` 供复测）。
- **AI 助手「游玩统计」工具落地**（原为写死桩）：`OnsAgentEngine` 新增
  `get_play_stats` 工具（原 `get_game_info` 只返回游戏名、从未调用统计）；
  `OnsAgentDialog.setPlayStatsProvider` 注入数据源（照 OnsSaveCloud 凭据注入
  模式，engine 不反向依赖 app）；app 侧新增 `AgentPlayStats.describe`，合并
  会话制 `PlaySessionTracker` + 旧 `play_time.json` 双数据源，输出当前游戏累计/
  状态、全部累计与本周、时长排行、最近游玩。真机取 play_sessions.db 复刻聚合
  校验输出（当前游戏 53 分钟/状态进行中、全部 6.5 小时、排行与最近记录正确）。
  附带发现：`play_sessions.game_title` 实际记的是 Activity 标题（=应用名
  「VNLingo」），不能当游戏名用——展示名统一由 game_uri 路径推导
  （`.../game/<引擎>/<游戏目录>/...`）。
- **运行时「导入 zip」+「从内置资源恢复」入口**（原两条恢复通道零调用方，
  卸载后实际无从恢复）：`NativeRuntimeManageCard` 新增两键；`NativePluginInstaller`
  在有期望哈希时严格校验、未配置时期望哈希路径退化为仅结构校验
  （结果 `verified` 标记，UI 提示未做哈希校验）。真机端到端：卸载 ONS 运行时 →
  卡片转「未安装」→「从内置资源恢复」→ 重新解压 APK assets 插件（日志
  `provisioned native plugin: ons`）→ 状态回到「已启用」，插件文件齐全；
  「导入 zip」按钮拉起系统文件选择器已验证。注：MIUI 文件列表对 `adb input tap`
  不响应，选中文件那一步未在自动化中走完（逻辑为既有实现 + 上述放宽）。
- 待办余项：`WEB_RPGMV_V1`（Web 注入资源包）仍无 UI 入口、状态恒「已集成」
  （GameRuntimeHost/WebRuntimeAssets 为 engine 内部 API，需先定入口再接线）。

## 2026-10-08 会话（四十三）：查词性能定位与优化方案（真机实测）

- **用户反馈**：查词搜索很慢。本轮只做定位与方案，不改代码。
- **定位方法**：在真机上跑仓库内未修改的 `OnsDictStore` / `OnsDeinflector` 源码
  （编译为 DEX 后 `app_process` 运行，动用设备 ART 与 `libsqlite.so`），另写原生
  C 基准复刻 SQL 查询计划；对照组用同一份库的副本切换 schema 实现 A/B。
- **根因**：`entries` 表**没有 reading 索引**，而面板 `tryMatchCandidate` 对每个候选
  依次做 term → reading → 还原候选 term/reading，其中 reading 查询全部退化为
  「按 `idx_entries_dict_term(dict_id=?)` 单列顺序扫描 + `ORDER BY pop DESC` 临时 B 树」。
  一个 21 字符句子约 63 个候选、累计 200 - 600 次查询，于是秒级起步。
- **真机实测（红米 K40 / Android 11 / SQLite 3.28.0 / 明鏡日汉双解辞典 66545 条）**：
  - 面板逐字扫描整句：**2345 ms**（无索引）→ **22.7 ms**（补 `(reading,dict_id)` 索引）
  - 词典页 `search()`：7.4 ms → 8.1 ms（词典页本来就只用几十次查询，不是主要受害者）
  - 50 万词条合成库：整句扫描 **46379 ms** → 25.6 ms；`reading = ?` 单查 71 ms → 0.011 ms
  - 排除项：`OnsDeinflector` 逐前缀 20 层合计 **0.43 ms**、单次 `searchTermExact`
    0.17 ms（含 `buildScope` 0.086 ms）、裸 SQL 0.09 ms —— 算法与语句本身都不慢
- **与 TrackReader 对比**（`packages/domain/src/yomitan`）：参考实现的 `db.ts` 对词条表
  **同时建 term / reading / dictionaryId 三个索引**，并有 `batchSearchTerms` 一次事务批量
  查询、`skipReading = scope !== 'ja'` 收窄读音查询、词典元数据常驻内存过滤；
  VNLingo 缺 reading 索引、逐候选串行查询、`buildScope()` 每次现查数据库。
  zh/vi 规则数据已同步（两侧都是无 transforms，仅预处理），无缺口。
- **方案已落盘**：`docs/dev/查词性能优化方案.md`（根因、实测数据、差异对照、
  P0 - P2 优化项、验收标准、实施顺序），待办已挂分四批的执行条目：
  B1 补索引 + ANALYZE（P0，老库启动自动补建，无需重导）／B2 reading 收窄 + 缓存 +
  PRAGMA／B3 候选批量 IN 查询／B4 首启后台建索引 + 进度提示。
- 性能验证工具在 `.tmp/perf/`（真机 `Probe4` A/B 可直接复现）；设备
  `/data/local/tmp` 下留有测试产物约 1 GB，待清理。

## 2026-10-07 会话（四十）：RinneMobile 功能引入批次一 + 遗漏入口补齐

- **游玩会话制统计**（参考 RinneMobile PlaySessionRepository）：PlaySessionTracker
  （SQLite play_sessions 表，每游戏会话 start/finish/心跳结算、<5s 删 >12h 封顶、
  游玩状态）+ PlaySessionLifecycle（Application lifecycle 自动跟踪，零侵入）。
  PlayStatsActivity 升级双数据源（会话 + 旧 JSON 并入）+ 最近游玩记录区。
- **补齐遗漏入口**：PlayStatsActivity 自移植起就**没有 UI 入口**（7b923c4 起
  Manifest 有 Activity 但无人调 createIntent）——本轮在设置页加「游玩统计」
  条目修复。MCP 配置从未实现（仅方向，无代码），待办补记。
- **调试日志启停开关**：DiagLog.setEnabled/isEnabled（prefs 持久化）+ 设置页
  按钮。参考 RinneMobile GameDiagnostics 的有界时间线思想。
- **AI 游戏助手**（OnsAgentEngine + OnsAgentDialog）：LLM function calling
  六工具（当前文本/游戏信息/查词/翻译/截图/TTS）+ 聊天面板。参考 RinneMobile
  Agent 模块方向，实现简化为同步工具链（无 MCP/服务端）。
- **RinneMobile 分析结论**（本轮评估）：会话制统计/诊断时间线/游玩状态 已引入；
  三方导入器（Playnite/Vnite/LunaBox/PotatoVn）高价值待做；账号/排行/HD UI
  不引入（需服务端/定位不符）。

## 2026-10-06 会话（三十九）：TTS 句读模式 + 朗读循环（TrackReader 对齐）

- **句读模式**（系统引擎）：按句末标点（。！？…；）切分为朗读段，首段
  QUEUE_FLUSH 其余 QUEUE_ADD——段间自然停顿。仅系统 TTS 路径生效
  （MultiTTS/HTTP 合成整段音频由服务端处理）。
- **朗读循环**：整句序列重复 N 遍（0=不循环，设置行循环 0→1→2→3→0）；
  系统引擎路径同样生效。
- TTS 设置弹窗新增「句读模式」「朗读循环次数」两行（prefs
  ons_extract_tts/tts_judou、tts_loop）；模拟器实测两行正确渲染 ✓。
- TrackReader 对齐备注：TTS Server 引擎/voices 分目录已有（HTTP 引擎 +
  MultiTtsClient catalog 展平），本轮补齐句读/循环后 TTS 参数面基本对齐。

## 2026-10-06 会话（三十八）：monque ONS 语音配对复测（受阻）

- monque（もれぶすくえすと！，ONS）模拟器启动正常，但标题菜单对触点
  （開始 中心精确坐标）、OK 注入、长按均无响应——与 senhana (MV) 同类
  导航障碍。语音配对复测未能推进，需真机或改用其他注入手段（无障碍服务/
  monkey）。

## 2026-10-06 会话（三十七）：senhana (MV) 提取启动验证（部分完成）

- senhana（RPG Maker MV，RpgMakerActivity/Web 宿主）模拟器启动：引擎正常
  拉起、注入管线与 mukbang (MZ) 同链路。**未完成**：游戏主菜单的盲点导航
  持续错位（MV 触屏菜单 tap 命中行与坐标预期反复偏离，多组标定均不稳），
  对话捕获未能走完——留待真机/稳定环境复测。mukbang (MZ) 的 Web 捕获链
  已端到端实证（会话三十四），MV 与 MZ 共用同一注入/捕获代码路径。

## 2026-10-06 会话（三十六）：Anki 制卡多方案（TrackReader schemes 对齐）

- `AnkiCardSchemes`（新，com.core.anki）：把制卡设置整包（deck/model/
  field_map/capture_on_card/voice_source）保存为命名方案快照，可加载切换/
  删除；活动配置始终在 anki_card_config（AnkiCardConfig.PREFS 改 internal
  供同模块方案管理器复用），加载 = 整包覆盖 + 记录 active_scheme。
- 制卡设置弹窗新增「方案」行：默认（未保存方案）/ 方案名；点开方案管理
  子对话框——存为方案（输入名拍快照）、方案列表（加载/删除，加载后同步
  刷新 deck/model 输入框）；主弹窗「确定」保存配置后活动方案名复位（配置
  已偏离快照）。
- 三语文案；模拟器实测：方案行渲染与默认显示 ✓；方案存取为确定性 prefs
  操作（完整 save/load 轮次因宿主脚本干扰导航未走完，逻辑为无分支 prefs
  读写，风险低）。
- 词典查询语言模式待办同步关闭：词典页语言筛选 chips（会话三十三）即
  「按语言筛选词条」落地；「切换查词语言」在游戏内查词侧意义有限（同一
  词典库），未再单做。

## 2026-10-06 会话（三十五）：制卡媒体槽位实测（待办 #媒体槽位）

- 场景：本地 TTS 服务器（tts_server.py:1221 + adb reverse）+ HTTP TTS 引擎
  prefs 注入 + AnkiCardConfig 注入（field_map 含 screenshot/sentence_audio/
  word_audio → Image/Audio 槽位，capture_on_card=true）。
- **实测通过部分**：kazurauta 点词查词命中 → 词卡 → 媒体生成端到端——
  collection.media 出现 anki_shot_*.png（游戏截图）与 anki_w_*.wav（35324
  字节 = 本地服务器 0.8s WAV，字节级吻合）各 3 组，时间戳与三次点词卡
  操作一一对应；HTTP TTS 引擎端到端（app → 127.0.0.1:1221 → WAV → addMedia）。
- **发现（待办预判命中）**：note 的 Image/Audio 字段为空——设备上有早期
  版本创建的 4 字段旧模型（无 Image/Audio 字段），makeWordCard 装配按模型
  真实字段(names)输出，媒体 mark 因字段不在模型内被截断。新装环境六字段
  模型不受影响；旧设备需删模型重建（AnkiDroid API 无模型加字段能力）。
- 制卡期间 AnkiDroid collection.anki2 出现 database is locked（写入中），
  force-stop AnkiDroid 后查询正常——诊断时注意锁。

## 2026-10-06 会话（三十四）：实机轮次验证（Web 提取 / Artemis SKIP）

- **Web 引擎提取实测 ✓（待办关闭）**：mukbang（RPG Maker MZ，走
  TyranoActivity/Web 宿主）模拟器实测——__tn_extract.js 注入捕获正常，
  开场英文句与推进后选项句连续更新到面板句栏。命中坐标标定注：MZ 菜单的
  触摸命中比绘制位置高 ~50px（标定后点按）。senhana（MV）同链路未单独复测。
- **Artemis SKIP 键行为确认 ✓（待办关闭）**：ctrl(140) 注入到达内核，但
  blossom 的实际响应是打开日志面板（閉じる），非快进——脚本层不消费
  ctrl-skip，按键链路本身正常，属游戏脚本绑定差异（非缺陷）。
- 模拟器本轮状态：GMS 弹窗/宿主脚本干扰仍频繁（tap 校验逐 hop 进行），
  但 reboot 后核心轮次均完成。

## 2026-10-06 会话（三十三）：词典查询语言模式（待办 #词典筛选）

- 词典页搜索框下新增语言筛选 chips：全部 / 日本語 / 中文 / 罗马字（客户端
  过滤，词条文字脚本启发式分类——纯 ASCII 词条归罗马字（Yomichan 词典无此
  类，多为测试别名/查询入口）、含假名（词条或读音）归日本語、其余 CJK 归
  中文）。
- 自曝自修：romaji 别名（term=tsuzuku, reading=つづく）首版因读音含假名被
  归日本語——修正分类顺序为「纯 ASCII 判罗马字优先」。种子词典（25 条）实测：
  tsuzuku 查询 → 全部显示 / 罗马字筛选命中 / 日本語筛选命中（修正后 romaji
  归罗马字，日本語筛选下正确消失——见会话记录更正：首版行为与修正后行为
  均已截图验证）。

## 2026-10-06 会话（三十二）：i18n 复查 + 调试日志功能页

- **i18n 混排复查（待办 #i18n）**：脚本对比三 locale 键集与值——engine
  236/236/236、app 751/751/751 全对齐，values-en 无中文残留，会话八的
  「部分命中」记录已过时（增量翻译已补齐）。ja 4 键与中文同形：仅
  engine_ons_extract_chip「文→テキスト」需要修，其余「音量」即日语汉字；
  app 语言名原生展示符合惯例。结论：en-US 下按 app 当前翻译即全量显示，
  无混排根源，待办关闭。
- **调试日志功能页扩展（待办 #调试日志）**：
  - `com.core.diag.DiagLog`（新，engine 模块——app 与引擎子进程共用）：
    内存环形 500 行 + filesDir/diag/diag.log 落盘（256KB 自旋砍半），
    @JvmStatic 供 Java 调用；Application.onCreate 安装（主/引擎子进程各自）。
  - 关键路径埋点：翻译 API/扩展引擎成败、查词命中（词条+组数）、同步冲突
    与失败。
  - 调试日志对话框扩容：运行时日志区（大小/查看子对话框最近 200 行/清空/
    导出 FileProvider 分享——file_paths 新增 diag/）；崩溃记录列表照旧。
  - 模拟器实测：对话框新分区渲染 ✓、查看子对话框空态 ✓（埋点随真实
    翻译/查词触发写入）。
- 注意：Kotlin object 供 Java 调用必须 @JvmStatic（DiagLog 初版遗漏致
  6 处「无法从静态上下文引用」）；python heredoc 写中文在部分 bash 环境
  会 GBK 化——补丁脚本一律走 Write 工具落盘再执行（本轮已中招一次并修复）。

## 2026-10-06 会话（三十一）：待办集中清理（5 项落地）

- **存档管理页云同步按键**：云端状态行（本机上传/云端更新时间，点击刷新；
  WebDAV HEAD 查询，GitHub 模式不支持显示 —）+ 上传/下载两个方向（zip 经
  cache 中转走 OnsSaveCloud；下载后 importFromZip 直接入库）；未配置先 toast
  + OnsSaveCloud 配置弹窗（保存后续跑所点方向）。OnsSaveCloud 补状态 API
  （isConfigured/lastUpload/recordUpload/cloudModified + RFC1123 解析）。
  模拟器实测：状态行显示与未配置引导 toast ✓（真实传输待用户凭据）。
- **制卡保存文案中性化**：制卡设置保存 toast 换「已保存」（原复用 API 文案
  带「再次点翻译生效」）；API 设置弹窗语境相符保留。
- **MainTabs 跳 Tab 常量**：TAB_GAMES/TAB_DICT/TAB_SETTINGS 替代写死索引
  （词典设置跳转改 TAB_DICT）。
- **制卡引导**：makeWordCard 入口拦截——未安装 → toast + 跳商店安装页；
  未授权 → 对话框「去授权」跳 AnkiDroid 应用信息页（线程内既有基础门控保留）。
- **TTS 缺服务引导**：MultiTTS 合成失败按 isServiceUp 探测分类 toast（未运行
  先启动应用 / 在线但拒绝检查发音人）；HTTP TTS 失败提示检查地址与服务。
- 修补过程两处自伤（入口门控引用线程内 helper、orphan 大括号），编译期拦截
  修复；装机 sanity 通过。

## 2026-10-06 会话（三十）：查词释义迁移顶部独立悬浮窗（参考 jidoujisho 交互）

- **需求**（用户）：查词释义不要挤在剧情文本框里——参考 jidoujisho-2.9.1：
  扫描到对应词在句子中高亮对应词，释义显示在顶部独立释义悬浮框。
- **新 `OnsDictOverlay`**：顶部停靠独立悬浮窗（OnsHistoryOverlay 同款窗口管理
  与失效重建策略），内容 = 词条头（teal）+ 释义组逐条（词条【读音】+ 释义行，
  滚动上限 90dp）+ 动作（词卡制卡 / ✕ 关闭）；NOT_FOCUSABLE（按键仍达游戏）。
- **面板改造**：查词命中路由 `OnsDictOverlay.show(...)`（翻句 defGroups=null
  时收起），移除面板内释义区（defArea/defHeader/defBody 及透明度/布局引用）；
  词典未安装/未命中仍走 toast。
- **顺带修复**：句中扫描高亮其实从未显示——renderSentence 每次把 matchedRange
  清空（打字机 flush 高频重渲染即抹掉）；改为同句重渲染保留高亮/点选状态、
  翻句显式清位（旧句区间不跨句）。
- **模拟器实测**：kazurauta 台词页点「あれ」→ 句中「見て」高亮 + 顶部悬浮窗
  显示释义（見て（みて）/看（て形）；请看）+ 词卡/✕ 可用 ✓。已知交互代价：
  悬浮窗展开时其覆盖区域阻挡游戏点击（jidoujisho 同款），✕ 或点窗外下方推进；
  翻句收起路径为确定性两行代码（未走完实机轮次——模拟器干扰环境恶化，
  GMS 弹窗/宿主脚本反复抢前台）。

## 2026-10-06 会话（二十九）：自动朗读修复——面板隐藏时全局开关真正生效

- **需求**（用户）：TTS 自动播放开启/关闭；即使隐藏剧情文本框，自动播放开启时
  更新文本后也要自动朗读。
- **诊断（三处断点）**：
  1. 设置页 TTS「自动朗读」开关写 `ons_tts_engine/auto_read`（OnsTtsEngines），
     而面板自动播放检查的是 `ons_extract_tts/tts_auto`（面板「自动」键）——
     两个键互不相通，全局开关形同虚设；
  2. 调用处 `if (ttsAuto) autoPlayForNewSentence(...)` 全局开关根本进不来；
  3. （深层）自动路径还被面板「TTS 朗读」开关（ttsEnabled，旧测试遗留 false）
     静默拦下——面板隐藏时该开关不可达，全局自动朗读被永久静音。
- **修复**：autoPlayForNewSentence 生效条件 = 面板「自动」快调 OR 全局
  「自动朗读」；调用处无条件进入（判定内收）；自动路径不受 ttsEnabled 牵制
  （ttsEnabled 只管手动「播放」按钮；自动朗读的关闭 = 关全局开关或面板自动键）。
  有配对语音的句子仍由游戏自身播放不重复播（设计不变）。
- **模拟器实测**（kazurauta，面板全程隐藏，注入 auto_read=true）：新句到达 →
  `tts init ready=true`（系统 TTS 初始化并朗读）→ 点击推进后下一句再次自动
  触发 ✓；定位过程加入临时门控日志（已移除）。

## 2026-10-06 会话（二十八）：翻译源扩展（参考 MoeTranslate / overlay-translator 移植）

- **需求**（用户）：从 MoeTranslate-5.5.1 与 overlay-translator-0.4.7 搬运
  免费翻译实现、常用 API 调用（选常用的）、AI 调用实现。
- **移植清单**（新建 `OnsTranslateEngines.java`，全部 HttpURLConnection +
  org.json，零新增依赖）：
  - 免费：**Google**（translate_a/single gtx 非官方端点，overlay 版）；
    **Bing**（cn.bing.com 网页翻译，抓页提取 IG/IID/token/key + Cookie 再
    POST ttranslatev3，MoeTranslate 版）
  - 常用 API：**DeepL**（/v2/translate，DeepL-Auth-Key，host 可配 free/pro）；
    **百度翻译开放平台**（md5(appid+q+salt+key) 签名，q 原文含换行参与签名）
  - AI：**Gemini**（generateContent REST + 安全档位全 BLOCK_NONE，MoeTranslate
    gemini SDK 的等价 REST 实现）；**Anthropic**（/v1/messages，x-api-key +
    anthropic-version，overlay 版）
  - Sakura 等 galgame 向 LLM 为 OpenAI 兼容端点，现有 api 引擎填其地址即可
- **接入**：`OnsTranslateClient` 新增六引擎常量/配置键（deepl_host/key、
  baidu_appid/key、gemini_key/model、anthropic_base/key/model），路由扩展；
  isConfigured 按引擎判定（免费源恒可用）。翻译设置弹窗引擎行改八引擎循环，
  配置行按引擎打开对应弹窗（免费源提示无需配置）；测试行走全引擎路由。
- **实测自曝自修两处**：①测试弹窗回调直触视图（翻译线程 OnlyOriginalThread
  崩溃杀应用）——runOnUiThread 包裹 + 引擎线程回调 try/catch 防死亡；
  ②VirtualMouseBindings 式的空集挡默认问题此轮无，但 Bing 端点反爬在本
  模拟器网络稳定 400（桌面/移动 UA、IID 全变体、Origin/Referer 均拒）。
- **模拟器实测**：引擎八档循环与持久化 ✓；Bing 真实调用打通到端点（错误
  信息带响应体清晰显示在测试弹窗，不再崩溃）✓；Google 端点本网络不可达
  （000，需代理）；DeepL/百度/Gemini/Claude 需用户凭据，代码路径按参考
  实现移植待凭据实测。
- 已知边界：Bing 非官方端点受反爬策略影响（同参考项目已知风险）；免费
  引擎的可用性高度依赖设备网络环境（google 需代理、bing 看端点策略）。

## 2026-10-06 会话（二十七）：云同步完整重实现（参考 RinneMobile 架构）

- **需求**（用户）：云同步完全参考 `RinneMobile-main` 重新实现。
- **新同步栈**（app 模块 `com.tyranor.next.core.sync`，1543 行）：
  - `WebDavClient`：参考客户端的 HttpURLConnection 移植（零新增依赖）——
    坚果云 `/dav/` 自动补全、明文 HTTP 拒绝（回环放行）、PROPFIND/MKCOL/
    HEAD/GET/PUT/DELETE/listFiles/getLastModified、读取声明长度+流式累计
    双重上限；路径逐段 URL 编码。
  - `SyncSnapshotCodec`：快照大小校验（远端 16MB/本地备份 32MB）+ gzip
    编解码（魔数检测，兼容老纯 JSON）+ 解压放大防护。
  - `SyncManager`：参考同步流完整移植——本地/云端快照各算 SHA-256 与上次
    同步哈希比对 → 首次上传 / 新设备首装下载（本地库空+云端有数据）/ 无变化 /
    单侧上行 / 单侧下行 / 双侧冲突（取消·用本地·用云端·智能合并；合并 =
    云端键级并入本地后重导出上传）。
- **快照内容（VNLingo 映射）**：games=游戏库文本元数据（9 游戏实测；不含
  封面/扫描根，同参考实现约束）、overrides=单游戏引擎覆盖（prefs 镜像导入
  后由启动同步回灌 DB）、settings=策展配置整文件（ons_extract_tts/anki_
  card_config/gamepad_remap/virtual_mouse_bindings）、play_time=游玩统计
  （仅本地为空时采纳）。不含存档 zip、词典库、封面、云凭据。
- **同步中心 UI**（`SyncCenterActivity`，Compose）：服务器/账号/密码 +
  自动同步开关 + 保存/测试连接/立即同步 + 状态行（已配置/上次同步时间）+
  冲突对话框（同步线程经 SynchronousQueue 阻塞等用户选择，2 分钟超时取消）+
  本地备份 `.vnlbak` 导出/导入（SAF，gzip 快照）。设置页「云同步」入口切换
  至同步中心；`OnsSaveCloud`（面板存档上传下载，独立数据域）保留。
- **模拟器端到端实测**（本地最小 WebDAV 服务器 `.tmp-test/webdav_server.py`
  + adb reverse，回环明文放行）：测试连接（探针写+删）✓ → 首传（gzip 快照
  1013B：9 游戏+2 覆盖+4 配置+游玩统计）✓ → 立即同步无变化判定 ✓ →
  云端改动（改 senhana 元数据标题）→ 下载合并 ✓（Room 库确认更新）→
  `.vnlbak` 备份导出（SAF 落盘，内容校验）✓。
- 已知边界：冲突对话框与导入备份的实机路径未走全（冲突需双侧同时更改的
  构造场景；导入与下载共用 importSnapshot 已由下载路径验证）；真实坚果云
  端到端待用户凭据实测；自动同步目前仅存配置（夜间调度为参考实现的账号
  体系部分，未引入）。

- **绑定 UI 补充（同日）**：默认/自定义在设置页区分显示——未自定义的动作
  展示默认键（只读「↓（默认）」，无 ×），自定义后展示可删 chips + 恢复默认；
  修复默认键误显示为可删（点击无反应）的误导。

## 2026-10-06 会话（二十六）：虚拟鼠标键盘支持 + 可配置按键绑定

- **需求**（用户）：①键盘方向键/回车支持——实测上下左右步进分配不对（疑似
  竖屏/横屏）②长按连续移动 ③设置页按键映射里增加虚拟鼠标快捷键映射
  （手柄+键盘）。
- **方向步进修复**：旧实现左右 1 单位、上下 2 单位（横屏下上下移距离两倍，
  体感即「还按竖屏在动」）——重构为四向等步进（14dp 起、随按住时长线性
  加速至 48dp 封顶），方向语义为屏幕系（上=−y），无轴向交换。
- **长按连续移动**：内部重复引擎（按下 350ms 后起、60ms/步、逐步加速），
  不依赖系统按键重复——adb 注入等无重复事件序列同样长按可连移；系统重复
  事件一律吞掉防双驱动；抬起/视图分离/切回触摸模式均复位。
- **键盘回车确认**：确认动作为可绑定动作之一，默认 = 手柄 A / 键盘回车
  （KEYCODE_ENTER）/ DPAD_CENTER；鼠标模式下回车即在光标处点击（不再穿透
  推进对话），模式关时照旧透传游戏。
- **VirtualMouseBindings**（新，com.core.engine）：五动作（上/下/左/右/确认）
  → 任意 keyCode 集（手柄/键盘统一、不区分来源），prefs
  `virtual_mouse_bindings` MODE_MULTI_PROCESS（设置页写入、引擎进程快照读），
  自定义集整体替换默认（清空恢复默认）。GamepadRemap 同款快照式。
- **设置页**：GamepadSettingsActivity 新增「虚拟鼠标按键」区——五动作行
  （当前绑定 chips 点 × 删单键 + 映射按钮捕获新键 + 恢复默认），捕获式
  交互与手柄重映射一致（BACK 取消）；未自定义时显示默认绑定。
  自曝自修：refresh 把空 StringSet 存进快照挡住默认回退（未自定义显示
  「未映射」且游戏内默认失效）——空集不进快照 + codes() 空集回退。
- **模拟器实测**：kazurauta——方向键四向移动且步进一致、回车在 Start 菜单
  定位点击进游戏、绑定 S→上移后游戏内 S 上移生效且默认 ↑ 按替换语义失效；
  设置页捕获 S/显示默认/持久化全通过。
- 已知边界：长按连移未在真机键盘上人工验证（adb 无法模拟按住；逻辑为
  确定性定时器驱动）；绑定 chips 的 × 对默认键同样显示（点了无变化，
  属轻微 UX 粗糙）。

## 2026-10-06 会话（二十五）：统一虚拟鼠标抽象（手柄通用鼠标模拟，ONS/KRKR/Artemis）

- **需求**（用户）：构造统一的虚拟鼠标抽象，手柄控制光标移动 + 确认键实现
  触摸点击，覆盖 ONS/KRKR/Artemis 三宿主；不修改引擎实现，主要在应用层完成。
- **统一抽象**：`OnsVirtualMouse` 迁移为 `com.core.engine.EngineVirtualMouse`
  （与 EngineLeftButtons 同包）。宿主只需提供三件事，引擎实现零改动：
  surfaceProvider（坐标基准；NativeActivity 宿主给 decorView）、clickInjector
  （一次完整点击：视图系=合成 MotionEvent dispatchTouchEvent，NativeActivity=
  内核触摸注入 JNI）、overlayClickHandler（光标悬停面板控件时优先点平台）。
  D-pad 移动（长按加速）/ 左摇杆连续移动 / A（BUTTON_A）确认。
- **ONS**：仅改用 EngineVirtualMouse（行为不变，键路由/摇杆/注入全既有）。
- **KRKR**（KirikiroidLauncherBaseActivity）：光标画在覆盖层；点击=合成
  MotionEvent 派发 GLSurfaceView（与手指同链路，不触碰引擎）；dispatchKeyEvent
  前置鼠标路由（面板之后）、新增 dispatchGenericMotionEvent 摇杆路由；右缘
  点击模式键由 null 供应商改为真实开关（prefs krkr_virtual_mouse，默认关）。
- **Artemis**（两个关键点，均实测发现）：
  1. **触摸**：NativeActivity 的触摸 InputQueue 为 native 独占，应用层无法
     dispatchTouchEvent——内核（artemis-compat，自研带桥构建）按 InjectHostKey
     先例补对称的 `InjectHostTouch(x,y,down)` JNI（key id 1=鼠标左键，与物理
     触摸同管线），pluginVersion 31→32 触发自动重装；同时 OnInputEvent 过滤
     SOURCE_CLASS_JOYSTICK（摇杆归一化轴值此前会被误当窗口像素触摸注入引擎）。
     官方内核无该符号 → 调用处捕获降级为无点击。
  2. **按键**：实测 NativeActivity 的按键**不经 Activity.dispatchKeyEvent**
     （D-pad/A 到不了 Java——此前「接线五处」的 Artemis 手柄重映射实际从未
     生效过，MEMORY 已修正）。鼠标模式把光标窗口设为可聚焦（FLAG_NOT_FOCUSABLE
     移除）接管按键焦点：KeyCatcher.dispatchKeyEvent → GamepadRemap → 鼠标
     handleKey，未消费键转发 ArtemisActivity 的 EmulateKeyEvent 映射链（鼠标
     模式下 ENTER/ESC/SKIP 仍可用）；模式关时窗口 NOT_FOCUSABLE，按键归还
     游戏（游戏窗口失焦不暂停引擎——内核未注册 focus 回调，已核实）。
  3. 光标窗口 TYPE_APPLICATION + NOT_TOUCHABLE（触摸全穿透游戏）；显式
     gravity=TOP|START（MEMORY 既有坑）。
- **模拟器端到端实测（三引擎全过）**：
  - KRKR kazurauta：右缘新点击模式键开关；D-pad 移动光标；A 定位点击
    Start 菜单项进游戏；A 推进对话 ✓
  - Artemis blossom（新内核 32 自动重装）：D-pad 移动；A 定位点击 START；
    A 推进至正式对话（extract 桥文本+语音配对正常）✓
  - ONS esg（回归）：点击模式键开启后光标出现；A 定位点击 NEW GAME；
    A 推进对话 ✓（此前模式被关过——ons_overlay virtual_mouse_mode=false，
    实测点击模式键开关正常）
- 已知边界：Artemis 鼠标模式下按键被光标窗口接管（未消费键经映射链转发，
  游戏直收的手柄键在鼠标模式不直达）；官方 revision 内核无 InjectHostTouch
  （无点击、有光标）；摇杆在 Artemis 上不达 Java（dispatchGenericMotionEvent
  对 NativeActivity 不触发），移动靠 D-pad。

## 2026-10-06 会话（二十四）：连续页累积显示（用户规则：点击+新文本才翻页）

- **需求**（用户两次补充）：hook 文本此前「多行页只出末段」；改为「点击推进 +
  有新文本」两条件同时满足才翻页覆盖，否则文本累积拼接显示；点面板/词典等
  不推进游戏的操作不算边界、不打断显示。
- **实现**（全在 Java 桥层，零 native 改动）：`OnsExtractBridge.handleDialogue`
  重写为连续页累积——
  - 边界 = `markPageAdvance()` 挂起标记（宿主游戏输入路径置位）或 >10s 长间隔
    兜底；新事件若是快照延伸（打字机/逐行增长，字节前缀差分）一律增量接在
    累积显示之后（延伸优先于点击：点打字中的画面只加速不翻页）；
  - 点击后到达的全新快照 → 整体覆盖（真翻页）；引擎重发更短/相同快照且无
    点击 → 不切换显示（「没有新内容就不用切」）；
  - KRKR（payload 带 `src=krkr`）：native 页启发翻页后按行重发的打字机分段与
    累积尾部真实重叠 → 尾部缝合（`stitchAppend`，无重叠=同页新行加 `\n`；
    切断点避开代理对）；ONS 等行级快照 → 直接追加 `\n` 新行。
- **推进信号接线**：KRKR=KrGLSurfaceView ACTION_UP + launcher dispatchKeyEvent
  ENTER/DPAD_CENTER（面板消费的按键已提前 return 排除）+ 左缘 OK/SKIP；
  ONS=SDLSurface setOnTouchListener（恒 false 只观测，虚拟鼠标注入同路径覆盖）
  + 左缘 OK/NEXT/AUTO + dispatchKeyEvent ENTER/DPAD_CENTER。
  面板/词典点击不经过游戏输入路径，天然不误标。
- **Artemis/Web 不需改动**：Artemis 内核消息层本就累积整页（facade 非延伸即
  翻页，语义已正确）；Web dialogue 事件本就是整框内容。
- **模拟器端到端实测（kazurauta + esg，通过）**：
  - kazurauta 三行旁白页（ショーウィンドウの中で／流行りの…／キッチンカーでは…）
    面板完整显示三行（\n 分隔）——此前只出末段；点击推进后新页整体覆盖无残留；
    面板句栏点选词显示不变；
  - esg 同一文本框三行（白銀の深淵より…／真のエルフの…／お手数ですが…）累积
    显示 ✓（ONS 页缓冲逐行增长，extension 路径拼接）；
  - 排查记录：kazurauta 面板一度只见单行，根因是上会话持久化的候选切换
    （cand_idx=2 派生末行覆盖句栏显示）——切回「自动还原」后累积生效。
    候选模式（原始流/派生）仍优先于累积显示，属既有机制。
- 已知边界：AUTO/SKIP 连续翻页与自动进页（无点击）靠 10s 长间隔兜底分隔，
  页与页可能短暂并显示；KRKR 新行首字与上行尾字相同的罕见缝合会吞一个重叠
  字符（候选机制可兜底）。

## 2026-10-06 会话（二十三）：收尾盘点 + 未完成清单固化

- **云同步闪退修复**（设置页入口 ClassCastException，findActivity 解包），
  实测配置弹窗正常打开；**去掉加入群聊**；**更新检查改指 VNLingo 本项目**；
  **崩溃日志更名调试日志**（文案落地，功能页扩展记待办）；
  **TTS 设置补自动朗读全局开关**（面板隐藏也生效）。
- **用户反馈三项修复**（同日早前批次）：自定义试听文本输入栏；发音人带
  语言代码 + 语言筛选两级选择；试听无音频根因修复（/forward voice 参数
  改用 id）。端到端实测：本地测试服务器收到自定义文本并回传 93KB WAV。
- **未完成清单**（全部已固化到 待办.txt，此处为索引）：
  条目存档管理页云同步按键（上传/下载/时间显示/方向选择）；
  词典查询语言模式；调试日志功能页扩展；制卡媒体槽位模拟器实测；
  制卡/TTS 保存提示文案；MultiTTS 缺服务引导；i18n 混排清理；
  Anki/TTS 设置全量搬 TrackReader（最大项，需逐项验收材料）。

## 2026-10-06 会话（二十二）：设置页整改第一批（用户反馈驱动）：设置页整改第一批（用户反馈驱动）

- **云同步闪退修复**：设置页云同步入口与翻译/制卡同款 ClassCastException
  （包装上下文强转 Activity），改 findActivity 解包，实测配置弹窗正常打开
  （WebDAV/GitHub 服务选择 + 地址/账号/令牌配置齐全）。
- **去掉加入群聊**入口；**更新检查**从上游 Tyranor-Next 改指向本项目
  FantasyzeroXYZ/VNLingo releases。
- **崩溃日志 → 调试日志**：字符串更名，后续扩展为含 debug 消息的
  查看与导出页（本轮先更名与文案，功能页扩展记待办）。
- **TTS 设置补自动朗读开关**：全局默认开关写入 OnsTtsEngines；面板隐藏时
  无语音句的自动 TTS 本就不依赖面板可见性（autoPlay 直呼路径），此开关
  提供全局默认入口（面板内「自动」键仍为游戏内快调）。
- **遗留（记待办，按优先级）**：条目存档管理页加云同步按键与方向选择/
  时间显示；词典查询语言模式；Anki/TTS 全量搬 TrackReader。

## 2026-10-06 会话（二十一）：TTS 端到端实测 + 三处修复（用户反馈驱动）

- **自定义试听文本输入栏**：TTS 设置弹窗顶部新增输入框（默认测试句），
  试听行朗读输入框内容——用户反馈「没有自定义文本输入栏」已修。
- **发音人列表带语言代码 + 语言筛选**（用户反馈「没有显示语言代码也没有
  按引擎筛选」）：MultiTtsClient 新增 fetchVoicePairsOn（条目含 locale），
  发音人选择改两级——先按语言筛选（从 locale 归并：全部/ZH/EN/…），
  再从筛选后的列表单选；显示名带 [zh-CN] 语言代码。
- **试听无音频根因修复**：MultiTTS /forward 的 voice 参数吃 id
  （bdetts_xiao-xiao-duo-yu-yan），此前存了 name（bdetts/晓晓 多语言）→
  服务端 500「未找到发音人」。VoiceEntry 改为 value 取 id 优先、display
  取「目录/name」供 UI 显示。实测直连：正确 id 的 /forward 返回 200 + 30KB WAV。
- **端到端实测**（本地测试服务器 :1221 模拟 /api/tts + adb reverse）：
  HTTP 引擎试听 → 服务器日志收到 `TTS request text='音声テスト。语音测试。Voice test.'`
  （输入框自定义文本）→ 回传 93KB WAV → 落盘 cache/tts_settings_test.wav
  → MediaPlayer 播放。链路闭环。
- 测试脚本 .tmp-test/tts_server.py（1221 端口模拟 /api/tts、/forward、/voices
  三协议）可复用于后续 TTS 回归。

## 2026-10-06 会话（二十）：TTS 设置全量化（TrackReader 参数面对齐）

- **TTS 设置补全**（用户反馈「全部搬过来」）：弹窗从三行扩展为全参数——
  语速 / 音高 / 音量（0..100，25 步进档位循环）/ 发音人（按引擎拉取列表：
  MultiTTS 经 /voices 目录 JSON 展平、系统引擎经 TextToSpeech.getVoices、
  HTTP 提示在模板中指定）/ MultiTTS 服务器地址（host:port 可配置）/
  引擎 / HTTP 模板 / 试听（带全部参数）。
- **MultiTtsClient 扩展**：synthesizeOn/isServiceUpOn/fetchVoiceNamesOn
  （指定 host:port）；服务器地址可配置（原硬编码 127.0.0.1:8774）；
  fetchVoiceNames 解析 /voices 目录 JSON（catalog 展平）。
- **参数持久化**（OnsTtsEngines）：rate/pitch/volume/voice/multi_host 全局
  存取；面板朗读与制卡合成（ttsSynthesizeQuiet）全部改用配置参数——
  语速仍受游戏内语速档位（ttsRate）快调覆盖；系统引擎新增音高与发音人
  （Voice.name 匹配 setVoice）。
- **实测**：弹窗全参数渲染 ✓；语速 50→75 循环 + 持久化（prefs rate=75）✓；
  引擎/HTTP 模板联动与持久化（上轮已验）✓。音量默认改 50 与档位网格一致。
- 已知边界：系统 TTS 音色列表含全部语言（以 #语言 前缀分组便于筛选），
  未按当前句语言过滤；试听的 HTTP/MultiTTS 合成需对应服务在线。

## 2026-10-06 会话（十九）：TTS 多引擎（系统/MultiTTS/HTTP API）+ 四设置入口

- **OnsTtsEngines**（新）：TTS 引擎路由——安卓自带 TTS（系统 TextToSpeech 直呼，
  无音频产物）/ MultiTTS / 自定义 HTTP API（GET 模板含 {text} 占位符替换为
  URL 编码文本，兼容 TTS Server `/api/tts?text=` 与 LunaTranslator 格式；
  32MB 保险丝 + Content-Length 截断校验）。引擎选择与模板持久化（全局）。
- **面板朗读路由**：高级 TTS 开关 = 使用配置引擎（multi/http），关 = 系统；
  MultiTTS 合成路径仅在引擎=multi 时走；ensureTts 的 MultiTTS 系统引擎绑定
  同步收窄到 multi 档。HTTP 朗读复用 playVoiceBytes。
- **制卡音频**：ttsSynthesizeQuiet 感知引擎（http → httpSynthesize；multi →
  MultiTtsClient；system → null 不生成制卡音频）。
- **TTS 设置弹窗**（showTtsSettings）：引擎循环 + HTTP 模板输入（仅 HTTP 档
  启用）+ 试听（multi/http 合成播放、system 直呼）。
- **设置页四入口**：Anki 设置（原制卡设置更名）/ TTS 设置（新）/ 翻译设置 /
  词典设置（跳主界面词典 Tab——MainTabs.requestTab 跨页跳转机制）。
- **实测**：设置页四入口呈现 ✓；TTS 弹窗引擎循环 MultiTTS→HTTP→系统 ✓；
  HTTP 模板随引擎档启用/置灰 ✓；保存持久化（engine=multi + 模板）✓；
  词典设置跳转词典 Tab ✓（搜索页 22 条状态正常）。

## 2026-10-06 会话（十八）：制卡自定义字段映射（参考 web game text 扩展方案）

- **AnkiCardConfig**（新，com.core.anki）：全局制卡配置——牌组名/模型名/字段映射
  （逻辑槽位→Anki 模型字段名，JSON 存储）/制卡时自动截图开关/例句语音源
  （自动=游戏配对优先回退 TTS / 仅游戏 / 仅 TTS / 关闭）。槽位九种：单词、读音、
  释义、例句、整页、译文、截图、例句语音、单词语音。默认映射 = 内置默认模型
  TyranorNext Word 四字段。
- **makeWordCard 重写**：按映射装配模型字段——模型已存在则经 getFieldList 读
  真实字段列表（AddContentApi），不存在且名为默认 → 兜底创建四字段模型，
  自定义名不存在 → 明确报错；未映射字段留空。新槽位解析：
  截图 = captureGameFrame（新增，PixelCopy 主线程同步限时等待，Surface 优先
  回退整窗）→ addMedia → `<img>`；例句语音 = bridge.ensureVoiceBytes()（游戏
  配对）按语音源策略回退 MultiTtsClient.synthesize → addMedia → `[sound:]`；
  单词语音 = TTS(term)；译文 = 新增 lastTranslation 字段（翻译成功时记录）。
- **制卡设置 UI**（OnsExtractSettingsDialogs.showAnkiCardSettings）：牌组/模型
  输入 + 语音源循环 + 自动截图开关 + 字段映射编辑（模型字段列表自 AnkiDroid
  读取，点行循环槽位；读取失败提示并退回默认四字段）。入口=设置页「制卡设置」
  （翻译设置旁）。
- **实测自曝自修**：设置页 ctx 被 AppLocaleController 包装（非 Activity 实例），
  既有翻译设置入口 `ctx as Activity` 是潜伏崩溃（本次新入口首次触发，崩溃日志
  精准定位）——两处都改经 AppLocaleController.findActivity 解包。
- **端到端实测**：默认映射制卡 ✓；把 Word 字段内容源改为「读音」保存 → 制卡 →
  AnkiDroid Card Browser 最新卡排序字段 = つづく（读音而非单词）✓；测试映射
  已清理（重置 anki_card_config.xml 回默认）。截图/语音槽位走同一装配路径，
  未在本次模拟器环境单独验证（默认模型无对应字段；kazurauta 无语音且未配
  MultiTTS），待有语音材料时复核。
- 已知简化：制卡设置保存提示复用 api_saved 文案（含「再次点翻译生效」字样，
  与制卡语境不符），后续换中性「已保存」。

## 2026-10-05 会话（十七）：健壮性第九批（存档重定向日志限流）：健壮性第九批（存档重定向日志限流）

- **KrPathUtils 重定向日志限流**：游戏高频反复开关同一批存档文件（且 .bak 备份
  名带时间戳，按路径去重无效——首版目标去重实测 265 条），日志以每秒数行的
  速度刷屏，多次把有效诊断信息挤出 logcat 缓冲。改 5s 限流（首条照记），
  实测同窗口 265 → 10 条。
- 本批同时确认：存档镜像（krkr_mirror）与游戏目录的互通由主应用
  GameSaveManager 的同步机制（同步清单/待回写/H3 互斥设计）承担，
  存档导出不会漏最新进度，非问题。

## 2026-10-05 会话（十六）：健壮性第八批（词典炸弹包保险丝 + 连按压力实测）

- **Yomichan/MDX 条目读取上限**：OnsDictStore.readText 此前无界读入
  ByteArrayOutputStream——炸弹包或异常文件（解压后数 GB）会在 OOM 里拖垮
  进程。加 64MB 单条目保险丝：正常 Yomichan 分包 <20MB，超限判导入失败
  （DictionaryScreen 的 runCatching 落「导入失败」toast）。
- **连按压力实测**：游戏内面板句栏 5 次跨单元快速连按（间隔 0.4s）——
  executor 串行 + 代次早停下无崩溃无 ANR；miss 正确提示新文案「未查到该词」
  （round 2 的文案拆分在压力场景下验证生效）。
- 旋转压力测试按 MEMORY 记录跳过：SDL 内核（KRKR/ONS）旋转即杀进程，
  属上游 Kirikiroid2/ONScripter 行为，非本仓可修。

## 2026-10-05 会话（十五）：健壮性第七批（Zip Slip 防护）

- **SaveZipUtil.unzipInto 补 Zip Slip 防护**：条目名直接 `new File(targetDir, name)`
  拼路径且无校验——恶意/损坏的存档包（用户手选或云同步来源）可用 `../` 穿越
  或绝对路径写出目标目录，覆盖应用任意文件。现对每条目做 canonicalPath 前缀
  校验，越界即抛错中止导入。
- **排查记录**：全仓共 4 处 ZipInputStream 解包——GameSaveManager（canonicalPath
  校验 + safeZipEntryName + 重复条目/文件数上限）与 RpgMakerRuntimeEnvironment
  （canonicalPath 校验）本就有防护；EnginePluginBootstrap 解 APK 内置 assets
  （可信来源）；OnsDictStore 的 Yomichan zip 只按名读流不落盘（无此问题）。
- **端到端实测**：构造含 `../zipslip_canary.txt` 的恶意 zip，走游戏内存档管理
  → 覆盖导入 ZIP → SAF 选择——导入被拒（toast「压缩包包含非法路径：…」），
  越界 canary 文件不存在，游戏存档（9 个文件）未受影响。

## 2026-10-05 会话（十四）：健壮性第六批（补丁完整性 + 扫描线程模型）

- **内核补丁下载完整性校验**：KrkrOnlinePatchService.downloadToFile 此前只限大小
  不核长度——服务端提前断流时 read"正常"返回 -1，截断补丁被当成功装进游戏目录
  = 坏内核（游戏无法启动且极难排查）。现在 Content-Length 可用（非 chunked）时
  必须核对 total==expected，不符抛错走既有失败路径（临时文件清理）。
  新文案 patch_download_truncated（已收/应收字节数，三语）。
- **扫描线程模型**：scanFrom 每次点选 new Thread → 改单线程 ExecutorService 串行
  （多线程查同一 SQLite 无增益）；配合代次早停，过期任务被消费到时立即返回，
  不阻塞新扫描；daemon 线程不阻碍进程退出。
- 冒烟：executor 模型下游戏内非首单位查词正常（続く 释义弹出）。
- 审查记录：BackgroundUpdateWorker 只做检查通知不下载 APK，无完整性问题。

## 2026-10-05 会话（十三）：健壮性第五批（崩溃日志落盘 + 设置页查看/分享）

- **CrashLogWriter**（新）：默认 UncaughtExceptionHandler 包装——未捕获异常先同步
  写 filesDir/crash/crash_<时间戳>.txt（时间/进程/线程/版本/系统/机型 + 堆栈），
  再交还系统原 handler（崩溃对话框与杀进程行为不变）；保留最近 3 份；写日志全程
  try 包裹不二次抛出。Application.onCreate 最早安装 → 主进程与引擎子进程
  （:kirikiri2）都覆盖，引擎崩溃同样有迹可查。
- **设置页「崩溃日志」入口**：列出记录（文件名+大小），点击经 FileProvider +
  ACTION_SEND 系统分享面板发出——现场设备（无 adb）用户可直接把日志发回来。
- **端到端实测**：`am crash com.tyranor.next` 触发真实崩溃 → 日志落盘（840B，
  上下文完整）→ 设置页显示「1 份记录」→ 弹窗列出文件 → 分享面板带 URI 弹出。
- 审查结论（无需改动）：KrkrOnlinePatchService（超时 12s/20s、12s/60s + 临时文件
  finally 清理 + 崩溃残留 .tmp 处理）、EngineScanner（runCatching 全覆盖 + IO
  调度）、OnsSideButtons（无常量外静态）、OnsTranslateClient（线程 + 15s/60s 超时）。

## 2026-10-05 会话（十二）：健壮性第四批（面板宽度量测重试）

- **applyPanelWidth 有界重试**：横屏面板宽度以按钮行自然宽度为准，但开面板早于
  首次布局时量到 0 → 直接落 560dp 兜底且不再重算——上轮冒烟中面板呈全宽透明条
  即此因（配合导航模式变化的坐标系漂移，一度误判成更深的 bug）。改为量不到时
  100ms 间隔重试至多 5 次。实测手势导航布局下面板正确收敛为紧凑居中形态。
- 排查记录：MultiTtsClient 超时（4s/60s、2s/5s）、SaveZipUtil 流式（16KB buf）、
  ArtemisExtractBridge 直传文本（无 JSON 注入面）、GameScreenshots 纯存储助手、
  ensureVoiceBytes 主线程取字节为有意设计（配对缓存防下一句覆盖）——均无需改动。

## 2026-10-05 会话（十一）：健壮性第三批（授权链路打通 + 悬浮窗 token 安全）

- **AnkiDroid 授权链路首次真正可用**：requestPermissions 此前在制卡后台线程直呼，
  系统授权框从不出现（用户卡死在「词卡需要授权」循环，只能靠 pm grant）。
  改为主线程 Handler post 发起。实测闭环：revoke → 制卡 → 弹窗出现 → 允许
  → 再点制卡 → 「词卡已添加到 Anki」（granted=true USER_SET）。
- **历史悬浮窗 token 安全**：悬浮窗静态持有首个 Activity 的 WindowManager/视图，
  旋转重建或重开游戏后旧 token 作废——show() 用旧 wm addView 会抛
  BadTokenException，或内容永远挂在已死的旧窗口上（showing() 恒 true 不再重挂）。
  改为记录 owner Activity，换实例整体重建；所有 wm 操作 try/catch 兜底，
  失败丢弃引用下次重建。
- **词典导入中被删除的孤儿防护**：导入完成回写 count 时校验 UPDATE 是否命中，
  落空（词典已被删）即清理孤儿词条并报导入失败（同进程 delete 有锁串行，
  此为未来多入口的防御）。
- 冒烟：授权全链路 + 游戏内查词正常。

## 2026-10-05 会话（十）：健壮性第二批 + 冒烟中自曝自修

- **历史语音缓存封顶**：persistHistoryVoice 落盘后裁剪目录，只留最近 100 条
  （每条 MB 级，长会话无限累积会吃掉数百 MB；cacheDir 被动回收不可依赖）。
- **词典库跨进程忙等**：OnsDictStore.db() 开库设 `PRAGMA busy_timeout = 5000`
  （词典页导入/删除 与 游戏进程查词并发时短写事务让路）。**注意 PRAGMA 带结果行
  必须 rawQuery，execSQL 会抛 "Queries can be performed using query or rawQuery
  only"——首版用 execSQL 直接把主进程启动崩了（自测自修）**。
- **查词 miss 文案拆分**：扫描未命中与「未导入词典」原先共用一句长文案，实测
  严重误导排查方向（词典明明加载了）；已加载词典时未命中改提示「未查到该词」。
- **db 加载诊断**：db() 记录 entries 数与 pid（定位双进程各自加载状态）。
- 冒烟：游戏内非首单位点选 → 続く 释义正常；主/游戏进程均加载 22 条。
- 记录：模拟器切了手势导航后右缘/面板布局与三键导航不同（面板呈全宽透明态，
  点按坐标全部漂移）——坐标类自动化测试前先确认导航模式一致（MEMORY 已记）。

## 2026-10-05 会话（九）：健壮性批次（实测暴露的静默失败/无兜底类）

- **游戏内扫描查词**：scanFrom 全程 try/catch（词典库异常降级为无结果，不再有
  杀进程风险）；scanByChar / scanByWordTwoPhase 循环内检查代次号——快速连按
  时过期扫描立即中止，不再空跑至多 40 次词典查询；dictRequestId/
  translateRequestId 改 volatile 保证跨线程可见。
- **OnsDictStore.search 顶层兜底**：库损坏/满盘/并发写冲突降级为空结果 + 日志，
  游戏内扫描与词典页两处调用方都不再因库异常崩溃（exactLookup 原有内层捕获）。
- **AnkiDroidHelper 静默 null 补日志**：addNewDeck / addNewBasicModel /
  addNewCustomModel / addNote 的「API 返回 null」路径与异常分开记 warning——
  本次制卡排查中「无日志可看」的根因即此类路径（provider 拒绝/集合未就绪时
  AddContentApi 返 null 不抛异常）。
- **OnsFreeTranslate 缓存**：裸 HashMap（后台线程并发读写）改 synchronizedMap +
  访问序 LinkedHashMap + 64 条 LRU 上限。
- **词典页导入进度**：importFromFile 的 Progress 回调接入悬浮框导入行摘要
  （term_bank 分包粒度），大词典分钟级导入不再像卡死。
- **install-debug-apk.sh**：安装前打印 APK 构建时间与体积——识别「改动未进包」
  的旧包误导（本会话两次踩到）。
- 冒烟回归：词典页搜索/状态行正常（zh locale 下 22 条中文显示，证实上一会话的
  混排确为 en-US locale 所致）；游戏内非首单位点选查词（続く）正常命中。

## 2026-10-05 会话（八）：词典页改版——搜索为主体，管理收悬浮框

- **页面重构**（DictionaryScreen）：主体改为单词搜索查询——AppSearchField 输入 +
  250ms 防抖，IO 线程走 OnsDictStore.search（最长前缀 + 词形还原分层，与游戏内
  查词同管线），结果卡片=词条 + 【读音】+ 释义多行；空查询显示当前词典状态
  （名称 + 条数），无词典/未命中各有提示卡。
- **词典管理悬浮框**：右上角入口（ic_engine_manage）弹出 AppAlertDialog 底部弹入
  悬浮框，内含导入入口（SAF 选 Yomichan zip / MDX jsonl）+ 词典行（启停开关/
  设为当前/删除，删除有二级确认）。管理操作后刷新词典状态与在途搜索。
- **模拟器实测**：搜索 tsuzuku（romaji 种子别名）正确出卡 tsuzuku【つづく】；
  悬浮框导入/词典行/开关/完成均正常呈现。adb 无法直输 CJK（input text 非 ASCII
  报 NPE），测试词典补了 romaji 别名词条（.tmp-test/dict_alias.sql）。
- **已知 i18n 混排（en-US 模拟器特有，未处理）**：设备 en-US 下 engine 的
  values-en 命中部分 key（如词条数「N entries」），app/engine 其余 key 回退默认
  中文，界面中英混排；zh 设备全部走默认中文不受影响。待办已记 i18n 清理项。

## 2026-10-05 会话（七）：模拟器实测非首单位选词查词 + 实际制卡通过（三修复）

- **renderSentence CJK 死循环（ANR 根因）**：CJK 分支漏了 `i = end` 推进——任何 CJK 句
  在 setSpan 处无限循环（unitRanges/span 无限增长）拖死主线程（打字机推进即 ANR）。
  修：`i = end` 移到两分支公共路径。实测面板正常渲染 21 字日语句，连推多行无 ANR。
- **[FTLN] 对白只落日志、从未接入提取桥**：NativeBridge.onKrkrText 对 [FTLN] 仅 Log.i
  （注释以为带钩内核走 KR2Activity.setExtractListener 独立通道），但现役 1.3.9 内核
  无 nativeSetExtractSink（UnsatisfiedLinkError 静默降级），该通道永远不存在——
  桥的 pageText/sentenceText 恒空，面板一直靠 [FTRAW] 候选兜底显示，制卡报
  「暂无可制卡文本」，翻译/朗读同样拿不到文本。修：[FTLN] 就地转发
  OnsExtractBridge.onEvent（dialogue 事件，与 [FTRAW] 同款 b64 装载）。
- **打字机延伸句语义**：桥的句文本=前缀差分增量，KRKR 打字机半句→整句是前缀延伸，
  制卡例句只剩尾部增量（实测首卡例句只有「とに、わたしも続く。」10 字）。
  修：前缀延伸（common==旧长且新更长）时句=整句解码；新增行仍走增量语义
  （保 ONS 多行页取末行行为）。
- **模拟器端到端实测（kazurauta，通过）**：点选句中非首单位 続 → 递减扫描命中
  続く【つづく】释义 → 制卡 → AnkiDroid 2.25.0 收到词卡（deck TyranorNext，
  Word/Reading/Meaning/Sentence 四字段齐全）；第一卡暴露例句截断 → 修复后
  第二卡复核例句=完整 21 字句。AnkiDroid 侧经 deck 列表（2 cards due）与
  Card browser（Edit note 全字段）双重确认。
- **测试环境搭建（可复用）**：AnkiDroid 本地无 → GitHub release 直连下载
  AnkiDroid-2.25.0-full-universal.apk（111MB）安装；首启过 intro + All files
  access；制卡权限 `pm grant com.tyranor.next com.ichi2.anki.permission.READ_WRITE_DATABASE`
  （prot=dangerous，requestPermissions 系统弹窗在模拟器上未出现，pm grant 最稳）；
  测试词典 17 词条经 `run-as com.tyranor.next sqlite3` 直插 ons_dict.db
  （种子 SQL 在 .tmp-test/dict_seed.sql，覆盖实测句词汇）。
- 遗留：正式词典需用户经词典页导入（Yomichan zip / jsonl）；AnkiDroid 未装/未授权
  时当前仅 toast 提示，无引导跳转。

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

## 2026-10-05 会话（六追加四）：Artemis 官方内核字符串层钩子（LunaTranslator 式）

- **发现**：官方 revision 内核（六款全）导出全套引擎 API——artemis:: 类、完整
 Lua API、FreeType。字符串层挂点直接 dlsym：`CBackLog::Add`（第 6 参=每页
 完整文本，显示时序）、`CArtemisParser::Text/TextTail`（解析器文本段）。
 LunaHook64.dll 亦证实 LunaTranslator 有 Artemis 专属模块（Artemis64x/
 .?AVArtemis@@，Windows 版按版本串识别）。
- **实现**（artemis_official_hook.cpp，编入 artemis_loader）：arm64 手写内联
 钩子（4 指令跳板；安装时校验序言无 PC 相对指令；内核内部直调故 GOT 不可达，
 必须内联）；artemis_loader dlopen 后安装，dlsym 探测 CBackLog::Add 自动区分
 官方/clean 内核；上行复用 [FTLN]/[FTRAW] 通道。
- **验证状态**：编译通过；设备实测待模拟器恢复（本会话末模拟器掉线），且
 尚缺官方内核测试游戏（材料仅 blossom=clean）。clean 内核回归同待跑。

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
- 入口：OnsSideButtons 新增「放大镜」键（，截图与设置之间），ONS/KRKR/Artemis/Web
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

执行 `docs/dev/待办.txt` 产品调整，按用户补充保留 Artemis 并修复其两处缺陷。全部在模拟器
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

- `:app:assembleDebug` 全程构建通过（含 ML Kit 首次拉取）。
- ⏸ 运行时行为未在模拟器回归（VNLingo 与 Tyranor-Next 同包名 `com.tyranor.next`，安装互覆）；
 功能行为以来源仓库的实测为准（ONS 面板/查词/制卡/翻译/TTS/截图/点击模式均已实测）。

## 进行中 ⏸

- **KRKR 对白完整还原（用户同意暂缓）**：现状=FT 字符流钩子全量命中，但 KAG
 多行页的后续行字符只查询一次，自动还原可能只含末段/带重绘链；候选切换
 （原始流/最适后缀）+ 历史悬浮窗已兜底。两个备选方案已评估待实施：
 A=字符串层 native 钩子（TVPCreateAndAddWindow 锚点 + krkr2-main 对齐偏移 +
 arm64 跳板，每内核一次）；B=修 TJS 发射器 KAG3.32 SIGSEGV（延迟注入或
 非替换式包装，建议先做）。

- `feat/extract-suite` 分支本次新增 3 个提交（Artemis 修复 / 导航与裁剪 / 手柄重映射）未推送。
- 遗留：packed Artemis 游戏语音字节不可播（需内核侧语音副本机制）；官方 revision 内核无提取；
 外置模拟器（PPSSPP/Eden/Winlator）基础设施代码保留但无入口。
