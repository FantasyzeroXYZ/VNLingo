# VNLingo 工作区长期记忆（跨会话约定）

## 故障记录的双册约定（2026-10-11 用户要求）

用户要求把"某游戏的问题 + 如何修复"沉淀成可检索的登记册，避免后续开发重复踩坑。分工固定为：

- **内核仓库** `D:\Desktop\artemis-compat\docs\known-issues.md`
  —— 放**内核侧**（`src/**`）故障。含「症状速查表」（按日志关键词/观感反查条目）+ 条目 `K-0XX`。
  条目模板：症状 / 触发 / 根因（文件:行）/ 修复 / 验证 / 状态 / 对应 AGENT.md 规则。
- **本项目** `docs/dev/游戏问题记录.md`
  —— 放**软件（App）侧**故障。含「维度速查表」（引擎、内核、版本、各类覆盖键与合法取值）
  + 「问题索引表」+ 条目 `G-0XX`（游戏 · 引擎(内核/版本) · 现象）。
  根因在内核的条目只写"如何判断 + 结论 + 指向内核 K-0XX"，不重复细节。
- 历史案例以**索引级**收录（游戏 → 引擎/内核 → 问题 → 指向 `docs/dev/MEMORY.md` 段落），
  细节仍留在 `MEMORY.md`（会话流水），避免两处重复漂移。

**新增问题一律按模板登记**，并同步加进「问题索引表」/「症状速查表」。

其他：

- `docs/dev/MEMORY.md` 开头、`docs/guide/VNLingo项目规范.md` 的文档清单、
  内核仓 `AGENT.md` 与 `AGENTS.md` 均已加入指向这两册的引用，改动时保持引用有效。
- 行尾：内核仓统一 CRLF；VNLingo 各文件保持原有风格（`docs/dev/*.md` 为 LF）。

## 开发准则：共通抽象 + 差异适配（2026-10-11 用户要求）

多引擎宿主共用的行为必须收口到 `engine/src/main/java/com/core/engine/` 改一次，
宿主只做差异适配，禁止复制粘贴公共逻辑。已有收口点：
`EngineOverlayRouting`（路由/光标同步/沉浸式）、`EngineControlMode`（控制模式状态/持久化/切换，
宿主差异走 Differ 回调）、`VirtualMouseController`（虚拟鼠标宿主无关接口）、
`VirtualMouseInput`（手柄光标核心）。新增宿主禁止手写 mode 字段+toggle+prefs 三件套。
完整条文见 `docs/guide/VNLingo项目规范.md` §4。

## Ren'Py 模块安装入口约定

Ren'Py 等依赖外置 APK 模块的引擎：安装/下载入口必须做在该引擎的**设置独立页**内
（`RenPyModuleManageCard`），引擎列表行点击永远进设置独立页；
行右侧状态图标才开版本弹窗。深层入口（小图标背后的弹窗）对用户等于不存在。
