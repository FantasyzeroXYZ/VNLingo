# VNLingo 会话进度记录

> 本文件由 AI 会话维护，记录本仓库功能线的实施进度。配合 `MEMORY.md`（环境/踩坑/决策，同目录）与
> `docs/说明/游戏内提取制卡功能方案.md`（提取功能线方案）阅读。
> 更新时间：2026-10-06

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
