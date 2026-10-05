# VNLingo 项目规范

> 项目专属规范与现状。通用规则见 `docs/dev/安卓开发通用规范.md`。
> 过程性文档：`docs/dev/PROGRESS.md`（进度）、`docs/dev/MEMORY.md`（经验）、
> `docs/dev/待办.txt`（待办）。

## 1. 品牌与包名

- 用户可见文案一律 **VNLingo**（三语言同步）；applicationId 保持
  `com.tyranor.next` 不变（包名兼容上游）。
- 模拟器抽屉里的 "Tyranor Next" 条目是宿主脚本装的
  `org.scummvm.scummvm.debug`（标签被改），与本项目无关。

## 2. 引擎与内核矩阵

| 引擎线 | 内核 | 提取数据源 |
|---|---|---|
| ONS | onsyuri（唯一内核） | 源码级内置钩子（脚本字节嗅探） |
| KRKR | Kirikiroid2 1.2.6/1.3.4/1.3.9 | FT_Get_Char_Index 字符流钩子（运行时 GOT 补丁） |
| Artemis | clean（自研，artemis-compat 源码仓） | 源码级 extract_bridge（TagPrint/TagAudio） |
| Artemis | 官方 revision ×5（libartemis*.so，闭源） | 字符串层内联钩子（CBackLog::Add/Parser::Text），待官方内核游戏实测 |
| RPG Maker | MV/MZ（NW.js） | Web 线脚本注入（__tn_extract.js） |
| Web/Tyrano、Ren'Py | WebView | 脚本注入 |

## 3. 测试材料现状

材料目录：`D:\Desktop\模拟器测试材料\<Engine>\`。标记：已有真实 / 需真实 / 可生成。

- ONS：esg（日文，已实测）、monque（已入库）；缺 SJIS 编码、带语音游戏
- KRKR：kazurauta（KAG3.32，1.3.9）、tsukikage（中文，1.3.4）、krkr2=Yosuga（1.2.6 待补测）；
  缺多行整页密集、带语音游戏
- Artemis：blossom（clean 内核实测 ✓）；**缺官方 revision 内核游戏**（官方钩子端到端验证必需）
- RPG Maker：mukbang（MZ）、senhana（MV）；Web/Tyrano/Ren'Py 材料缺

## 4. 关键约定

- 内核文件替换必须 `pluginVersion`+1（manifest.json），否则已装设备不重装
- 不改名宿主约定库名（libartemis-clean.so 等）
- 品牌文案三语言同步；applicationId 不改
- 模拟器抽屉 "Tyranor Next" 残留条目 = scummvm debug（无关）
