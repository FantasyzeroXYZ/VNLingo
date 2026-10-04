# VNLingo 会话进度记录

> 本文件由 AI 会话维护，记录本仓库功能线的实施进度。配合 `MEMORY.md`（环境/踩坑/决策）与
> `docs/游戏内提取制卡功能方案.md`（提取功能线方案）阅读。
> 更新时间：2026-10-05

## 2026-10-05 会话：提取/制卡功能线整体移植（分支 feat/extract-suite，4+1 提交）

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

- `feat/extract-suite` 分支 5 个提交未推送（推送到 FantasyzeroXYZ/VNLingo 由用户决定）。
- 产品调整计划见 `待办.txt`（去首页导航、引擎并入设置页、词典页入底栏、裁剪引擎支持范围）。
