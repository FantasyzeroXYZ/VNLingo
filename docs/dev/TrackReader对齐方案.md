# TrackReader 对齐方案（提取面板功能对齐路线）

> 参考实现：`D:\code\test-JavaScript\TrackReader`（浏览器扩展，TypeScript，
> packages/domain 为纯业务层，约 4.7k 行核心实现）。
> 目标：VNLingo 提取面板的 查词/词典管理/词形还原/Anki/手柄制卡/TTS 按
> TrackReader 的实现方式对齐（算法与数据结构移植，语言 TS→Java/Kotlin）。
> 原则：只移植行为与数据结构，不移植浏览器平台绑定（chrome.*/IndexedDB
> 换成 Android 等价物）。

---

## 0. TrackReader 关键实现位置（packages/domain/src/）

| 域 | 文件 | 行数 | 职责 |
|---|---|---|---|
| 词形还原 | yomitan/deinflection/index.ts + transform-engine.ts | 132+285 | 多语言工厂 + 递归还原引擎（suffix/prefix 规则、conditions、score） |
| 词形还原规则 | yomitan/deinflection/ja/transforms.ts | 815 | 日语全量活用规则（Yomitan 体系） |
| 日语工具 | yomitan/deinflection/ja/japanese-util.ts + preprocessors.ts | 268+100 | 假名/汉字工具 + 预处理管线 |
| 多语言 | deinflection/en|ko|zh|vi/ | 各若干 | 英/韩/中/越规则描述符（注册表模式） |
| 词典 | yomitan/db.ts + dictionary-query.ts + dictionary-import.ts | 101+720+291 | Yomitan 词典格式（IndexedDB→需换 Android 存储）+ 查询 + 导入 |
| Anki | anki/anki-connect.ts + field-mapper.ts + schemes.ts + cloze.ts + occlusion.ts + media.ts + card-types.ts | 151+183+69+134+… | AnkiConnect 协议 + 字段映射 + 连接/字段映射方案 + 填空/遮挡卡 + 媒体 |
| TTS | tts/engine.ts + multitts.ts + web-speech.ts + http-provider.ts + tts-server.ts + tts-cache.ts + luna-translator.ts | 699+235+… | provider 抽象 + 缓存 + 审计 |
| 手柄 | input/pad-signals.ts | 446 | 手柄信号抽象（游戏书播放器的控制输入） |
| 生词本 | vocabulary.ts | 99 | 生词本数据模型 |

---

## 1. 词形还原（词形还原引擎）——优先级 P1

### TrackReader 方案
- 语言注册表（ja/en/ko/zh/vi）→ 每语言一个 descriptor（规则集 + 预处理管线）
- 引擎：递归应用 suffix/prefix 规则直至收敛为词典原形；每步带
  conditions（词性条件，防止非法还原链）与 score（越短越可信）
- 日语：全量 Yomitan 活用规则（815 行：动词五段/一段/サ变/カ变、て形
  促音便/い音便、否定、 使役、被动、意愿、命令、形容词活用……）

### VNLingo 现状
- `OnsDeinflector.java` 仅 129 行：内置最高频规则（敬体/て/た/否定/い形容
  词/です），最长后缀剥离、无 conditions/score、无递归链
- 注释已自认"后续可再移植全量规则表"——TrackReader 的 ja/transforms.ts
  就是那张全量表

### 差距与移植要点
- 移植 transforms.ts 全量规则为 Java 数据（规则对象列表，机械翻译）
- 引擎移植：递归 + conditions 位掩码 + score 排序（~150 行 Java）
- 预处理管线（全角半角/假名规范化）按需移植
- 接口保持 `deinflect(query) → List<String>`（词元 tap 查词流不变），
  内部升级为带权候选排序

### 优先级 P1：查词质量直接受益、纯数据+纯函数移植、风险低

## 2. Anki 管理——优先级 P2

### TrackReader 方案
- **AnkiConnect**（HTTP localhost:8765）协议：deck/model 查询、addNote、
  多媒体 storeMediaFile
- **方案（schemes）**：连接方案与字段映射方案持久化（用户可配多套）
- **字段映射**：模板占位符 → 词典查询结果字段（word/reading/meaning/
  sentence/audio…）动态填充
- **卡型**：word 卡 / 句卡 / 填空（cloze）/ 遮挡（occlusion）生成器
- **媒体**：发音音频、截图存入 Anki 媒体目录

### VNLingo 现状
- 仅 **AnkiDroid** intent API（`AnkiDroidHelper`，word/sentence 双方案）——
  只支持装了 AnkiDroid 的手机端；无桌面 AnkiConnect、无媒体、无填空/遮挡

### 差距与移植要点
- 新增 `AnkiConnectClient`（OkHttp/HttpURLConnection POST JSON，协议简单）
- 卡型生成器移植：cloze/occlusion（纯字符串处理，好移植）
- 字段映射方案持久化（SharedPreferences/JSON）
- 双后端：AnkiDroid（手机）/ AnkiConnect（桌面+模拟器场景），按可用性选择
- 音频媒体：提取缓存里的语音字节 → storeMediaFile（模拟器+桌面场景高价值）

### 优先级 P2：补齐桌面/模拟器制卡路径 + 卡型丰富度

## 3. 词典管理与查词——优先级 P3

### TrackReader 方案
- Yomitan 词典格式导入（zip → IndexedDB）：term banks 按
  index-*.json 分片、结构化内容（structured-content）渲染
- 查询：前缀/后缀/精确多策略 + 序列（sequence）聚合 + tag 过滤
- 词典管理：多词典启用/优先级排序

### VNLingo 现状
- `OnsDictStore`：Yomichan 词典 SQLite + 最长前缀分层搜索（已有基础）
- 差距：结构化内容渲染、多词典优先级、查询策略完整度

### 移植要点
- dictionary-query.ts 的查询策略对照补齐（720 行，按需分批）
- 结构化内容按需子集（html 标签子集渲染 Android Spannable）
- 多词典优先级 UI+存储

### 优先级 P3：现有查询已可用，按查词体验反馈增量补

## 4. TTS 实现与管理——优先级 P4

### TrackReader 方案
- provider 抽象（web-speech / http-provider / luna-translator / tts-server /
  multitts 多种实现可插拔）+ engine 编排（队列、打断、逐句高亮跟随）+
  tts-cache（合成结果缓存）+ tts-audit（审计）

### VNLingo 现状
- `MultiTtsClient`（MultiTTS app 集成，89 行）+ 面板自动朗读开关

### 移植要点
- provider 接口抽象化（MultiTTS 作为其中一个 provider 保留）
- 朗读队列与打断语义、逐句跟随（与提取面板高亮联动）
- 合成缓存（同句不重复合成）

### 优先级 P4：功能已可用，按体验反馈增强

## 5. 手柄控制制卡——优先级 P5

### TrackReader 方案
- `pad-signals.ts`：手柄原始信号抽象（按键→语义动作映射、长按/连发、
  组合键），游戏书播放器用它做控制输入

### VNLingo 现状
- `GamepadRemap`（手柄→键盘映射）+ 面板按键组；制卡只能触摸操作

### 移植要点
- 语义动作表（候选上移/下移/选中/制卡/播语音/切候选）+ GamepadRemap
  联动：手柄键 → 面板语义动作
- 面板获得焦点态时的手柄导航（候选列表上下选、A 确认制卡、Y 播语音）

### 优先级 P5：依赖候选/制卡流程稳定后做

## 6. 其他值得对齐的点（记录备用）

- `vocabulary.ts` 生词本模型（与候选/制卡联动）
- `tts-cache.ts` / `storage/cache.ts` 缓存模式
- `sync/`（WebDAV/GitHub 云同步——生词本/设置备份，VNLingo 已有
  OnsSaveCloud 可对照）

---

## 7. 实施顺序与里程碑

| 批次 | 内容 | 预估 |
|---|---|---|
| M1 | §1 词形还原全量规则 + 引擎移植（P1） | 1 个会话 |
| M2 | §2 AnkiConnect + 卡型生成器 + 双后端（P2） | 1 个会话 |
| M3 | §3 词典查询策略补齐（P3，按反馈分批） | 按需 |
| M4 | §4 TTS provider 化（P4）、§5 手柄制卡（P5） | 按反馈 |

每批次完成后：模拟器实测 + PROGRESS/MEMORY/待办更新 + 提交。
