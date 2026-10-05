# VNLingo 项目规范（项目专门）

> 项目专属工程约定。通用规则（编码/构建/JNI/提交）见《安卓开发通用规范》，
> 本文只写 VNLingo 特有的部分。配合 `PROGRESS.md`（进度）、`MEMORY.md`（经验）、
> `待办.txt`（待办）、`docs/游戏内提取制卡功能方案.md`（提取功能线方案）阅读。

## 1. 架构分层与进程边界

```
app 模块        UI（Compose）/ 扫描 / 启动器 / 设置 / 词典页
engine 模块     引擎宿主（ONScripter/Tyrano/RpgMaker/Artemis/KR2 Activity）、
                提取面板（OnsExtractPanel）、facade、native 插件加载、cpp 钩子
引擎子进程      :kirikiri2 / :artemis.clean / :ons（各引擎独立进程）
```

- 引擎配置跨进程传递：**Intent extra 最可靠**；prefs 跨进程有陈旧窗口（见通用规范 §3）。
- 引擎子进程内的 JNI 上行统一入口：
  - ONS/KRKR/Artemis-hook → `bridge/NativeBridge` → `OnsExtractBridge`；
  - Artemis 带桥内核 → `ArtemisActivity.onArtemisExtract` → `ArtemisExtractBridge`。
- 面板/浮窗在引擎进程内以 WindowManager 悬浮窗承载（NativeActivity surface 独占合成，
  addContentView 的视图 bounds 恒为 0）。

## 2. 引擎接入清单（新增/恢复一条引擎线的固定动作）

1. 内核/运行时就位（开源运行时优先源码级集成，见 §4）；
2. `ArtemisEngineFingerprintDetector` / launch 分支：版本检测 + fallback 链；
3. 宿主 Activity：安装面板（`OnsExtractPanel`）+ 左右缘按键（`EngineLeftButtons`
   统一抽象：点按/按住/高亮三语义）+ 手柄重映射（`GamepadRemap`）；
4. `ExtractFacade` 实现（句/页/语音/候选/存档五组接口）；
5. 放大镜、剧情文本框等面板能力自动获得（由 OnsExtractPanel 统一承载）；
6. 模拟器实测 + 文档（PROGRESS/MEMORY/待办）。

## 3. 提取功能线规范

- **事件通道约定**（NativeBridge/OnsExtractBridge）：
  - `[TNEXT]` = KRKR TJS 发射器标记行；`[FTLN]` = FT 钩子整页/还原句（KRKR/Artemis 官方钩子）；
    `[FTRAW]` = 原始重绘链候选；ONS 内置桥走 `{"type":"dialogue","src":"kernel"}` 语义。
  - 新增通道必须：三语言 strings 同步、CJK/长度过滤（挡启动期 UI 字符流）、
    在本文档登记键名与语义。
- **候选机制**（LunaTranslator 式）：facade 实现 `getSentenceCandidates()`（默认空），
  面板 ♪ 状态行点击弹单选悬浮框，选择按游戏持久化（`cand_idx_<game>`）。
- **历史**：独立悬浮窗 `OnsHistoryOverlay`（可拖动/清空/✕），面板历史键开关，
  新句增量追加；部分行被完整行覆盖（前缀延伸判定）。
- **双向门控**：`OnsExtractBridge.setHookPreferred(true)` 时内核直出事件被忽略，
  仅 `src=hook` 通道生效（ONS hook 模式预留；实现见 OnsExtractBridge）。

## 4. 内核/插件打包规范（高危，必须遵守）

- 内核文件改动（替换/重编）**必须 `pluginVersion` +1**（`app/src/main/nativeplugins/
  <engine>/manifest.json`），否则已装设备不会重新解压（EnginePluginBootstrap 按版本比对）。
- manifest.json 的 `libs` 数组与实际 so 清单同步；**不得改名**宿主约定库名
  （如 `libartemis-clean.so`——宿主按名打包，引擎源码仓产出 `libartemis.so` 由
  宿主重命名，见 artemis-compat AGENT.md）。
- 内核源码仓（artemis-compat、krkr2-main、OnscripterYuri）的本地路径与上游约定
  记录在 MEMORY.md；内核更新 = 源码更新 → 构建 → strip → 替换 → 版本+1 → 实测。

## 5. 运行时钩子（逆向路线）规范

- 挂点选择优先级：**源码级桥 > dlsym 可得的导出符号 > 签名扫描**；字形层挂点
  是最后手段（字符流有重组歧义）。
- 每个挂点必须在《游戏内提取制卡功能方案.md》或 MEMORY.md 登记：目标符号
  （mangled）、内核与偏移、序言校验结论、调用频率/线程、上行通道。
- 内联跳板统一使用 `artemis_official_hook.cpp` 的实现模式（4 指令跳板 +
  PC 相对指令校验拒绝挂钩 + mprotect/RX/clear_cache），不重复造轮子。
- 已知环境约束：本机模拟器 shadowhook_init 失败（errno=12，API 34+ linker），
  模拟器验证必须走手写跳板或 GOT 补丁；真机 shadowhook 可用。

## 6. 模拟器测试规范

- 测试材料在 `D:\Desktop\模拟器测试材料\<Engine>\`；游戏接入后从主页卡片进
  （卡片点击坐标用 uiautomator dump 现取，勿凭记忆硬编码）。
- 引擎控制台/警告页会吃按键：盲点前先截图确认当前界面；KAG 控制台自愈
  （boot 宏回显结束后自动消失）。
- 每轮验证以 logcat（引擎专属 TAG）+ 截图双证据为准；脚本安装见 `scripts/`。

## 7. 文档与提交纪律

- 每次会话：PROGRESS.md（进度，按会话分节）、MEMORY.md（可复用经验/踩坑/
  外部事实）、待办.txt（完成/遗留勾选）三件套必须更新后提交。
- 提交信息：`类型(范围): 摘要`；正文分条列改动与实测证据。
- 里程碑打注释 tag（如 `local-2026-10-05`）。
- 品牌名：用户可见文案一律 **VNLingo**；applicationId 保持 `com.tyranor.next`
  不变（包名兼容上游）。
- 模拟器抽屉里的 "Tyranor Next" 条目是宿主脚本装的
  `org.scummvm.scummvm.debug`（标签被改），与本项目无关。
