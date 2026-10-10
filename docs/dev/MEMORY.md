# VNLingo MEMORY — AI 会话记忆（环境、踩坑、决策、续作指南）

> 供后续 AI 会话快速接手。配合 `PROGRESS.md`（进度，同目录）与 `docs/guide/游戏内提取制卡功能方案.md`（提取功能线方案）阅读。
> 更新时间：2026-10-08

## 本机环境事实

- **构建**：`cd /d/Desktop/demo-code/VNLingo && JAVA_HOME="D:/dev-cache/jdk-17.0.20.1+1" ./gradlew :app:assembleDebug`
  （系统默认 Java 11，Gradle 必须 JDK 17+；NDK 28，SDK 在 `D:\dev-cache\Android\Sdk`）
- **模拟器**：AVD `Pixel_6`（`D:/dev-cache/Android/Sdk/emulator/emulator.exe -avd Pixel_6`），
  x86_64 镜像跑 arm64 引擎需 `adb install -r --abi arm64-v8a`；`adb root` 可用
- **网络**：GitHub 直连极慢 → `https://gh-proxy.com/https://github.com/...` 镜像（clone/fetch 均可）；
  Google Maven（dl.google.com）直连可用（ML Kit 依赖拉取正常）
- **同包名警告**：VNLingo 与 Tyranor-Next 的 applicationId 同为 `com.tyranor.next`，安装互覆；
  模拟器回归前先确认装的是哪个

## 移植方法决策（2026-10-05，可复用）

- 两仓无共同祖先（本地=基线快照+功能提交；VNLingo=上游真实历史）→ git 层面 merge-base 不可用。
  改用：`find` 文件清单 `comm` 求差 + 我方 `git diff b28feb1..HEAD` 提交史圈定「我们改过的文件」，
  与「两仓内容不同的共有文件」求交集 → 即需合并式搬运的清单。
- 共有文件搬运 = 在来源仓生成 `git diff <基线> -- <file>` 补丁 → 目标仓 `patch -p1 --fuzz=3` 应用；
  上游漂移容忍度高（本次 26 个补丁 23 个一次通过）。注意 `tr '_' '/'` 还原路径会毁掉
  `ies_net`、`file_paths` 这类含下划线/无下划线路径，逐个核对。
- **只增不删**约束下的三类跳过项（VNLingo 维持原样）：App 图标、设置页加群入口、卡片占位文字。
- 评审后两类再调整：游戏内存档面板（条目管理页已有导入导出）与 ONS 编码 auto（保持原三选项）
  均已回退——不要再把它们加回来。

## 实测踩坑（重要）

1. **浅克隆仓 fetch 受限**：shallow 根不允许更新 refs（`rejected ... because shallow roots are
   not allowed to be updated`）→ 在浅仓里 `git fetch --depth N <源路径>` 拉对方历史，而不是反向。
2. **git rm 原子性**：多个 pathspec 之一不匹配 → 整条中止、一个都没删；分步删或先 `ls` 确认。
3. **`git add -A` 会扫入用户自己的未跟踪文件**（如 `待办.txt`）——提交前 `git status` 核对，
   误入用 `git rm --cached` + `commit --amend` 修。
4. **SDL Activity 怕旋转重建**：直拉引擎 Activity（无 ORIENTATION extras）时用 `settings put
   system user_rotation` 强锁横屏后反复切换会触发重建杀进程；回归测试固定走直拉且不再动旋转设置。
5. **系统三键导航条（横屏右缘 ~126px）叠在应用窗口上**：贴右缘的自绘按键必须加
   `systemBars().right` inset 边距，否则部分点按被系统 Back/Home/Recents 吃掉
   （表现为「点了没反应/莫名回桌面」）；onCreate 时 insets 未就绪，需 attach 后 post 校准。
6. **像素级点按验证**：截图渲染尺寸 ≠ 设备物理像素（2400x1080），点按坐标先用 python/PIL
   从原 PNG 量白色像素块定位，别拿缩放图估；更稳的是 `uiautomator dump` 拿控件 bounds
   （就是物理坐标），缩放图估坐标易偏。
7. **ARM 转译镜像装 APK 必须带 `--abi arm64-v8a`**：APK 里 artemis 系 .so 只有 arm64，
   不带该参数安装会按 x86_64 主 ABI 解包 → `:artemis.clean` 进程加载
   libartemis_audio_bridge.so 失败直接崩（UnsatisfiedLinkError）。
   2026-10-07 再犯一次：MCP android_install_app 走裸 streamed install（无 --abi），
   结果 ONS 启动即 `libSDL2.so is for EM_AARCH64 instead of EM_X86_64` 闪退
   （app/lib/ 被 x86_64 依赖库带偏，arm64 插件加载必炸）。检查法：
   `ls /data/app/*tyranor*/lib/` 应为 arm64。修复 = 带 --abi 重装（-r 保留数据）。
   **任何会话装 APK 都要带 --abi**，MCP install 工具不透传该参数时改用 adb 命令。
8. **该 AVD 有宿主侧遗留 root 脚本周期性拉起 ScummVM**（logcat `START ... from uid 0`），
   会莫名抢前台干扰 UI 自动化——先 `am force-stop org.scummvm.scummvm.debug` 再操作。
9. **Git Bash 里 `adb shell uiautomator dump /sdcard/ui.xml` 的路径会被 MSYS 改写**：
   设备路径写 `//sdcard/ui.xml`（双斜杠），取回用 `adb pull //sdcard/ui.xml <Windows 路径>`；
   Windows python 读不了 `/tmp`，临时文件放项目内。

## 移植外事实（2026-10-05 会话四新增：放大镜）

- **PixelCopy 两种抓法的本质差异**：`PixelCopy.request(SurfaceView, dst, ...)` 会把
  整个 surface **缩放填进** dst（dst 尺寸=取景区想放大必须先抓全幅再 createBitmap
  裁剪）；`PixelCopy.request(Window, dst, ...)` 只抓窗口自身渲染，**拿不到
  SurfaceView 内容**（SDL/GL surface 由 SurfaceFlinger 单独合成 → 实测黑屏）。
  放大镜（OnsMagnifier）因此优先 SurfaceView 抓取，NativeActivity 宿主
  （无 SurfaceView）退窗口抓取（可能黑屏，未实测成功）。
- WindowManager 悬浮窗 `TYPE_APPLICATION` **必须显式 lp.gravity = TOP|START**：
  缺省 gravity 下 x/y 不按左上锚定（Artemis 左键窗实测漂到屏幕中央）。

## 移植外事实（2026-10-05 会话三新增：KRKR）

- **Kirikiroid2 层文本运行时不可拦截（实证）**：cocos2d::Label::setString 只承载控制台/UI
  （三版本同名导出 `_ZN7cocos2d5Label9setStringERKSs`，旧 GNU COW string ABI，参数首字即
  char*）；GdipDrawString 有导出但零调用；层文本走内部 FreeType 管线（符号未导出且 FT 调用
  不经 PLT，GOT 钩子无效）。**对白提取唯一可靠路径 = krkr2-main 源码带钩内核**；接收端
  （KR2Activity.setExtractListener → OnsExtractBridge → 面板）已全链路就绪，内核替换即生效。
- **shadowhook 在 :kirikiri2 进程对插件目录 dlopen 的内核 inline hook 失败**（errno=12
  "Init linker mod failed"，:artemis.clean 进程同样用法则成功）——退「导出虚表 mprotect 补丁」
  （Label vtable 槽扫描匹配 dlsym 地址后改槽，slotIndex=173）。
- **TJS 发射器（patch.tjs 包装 KAGParser）在 KAG3.32（kazurauta）引导期 SIGSEGV 复现**——
  `global.KAGParser = 子类` 赋值即崩；设置 kr_extract_tjs 默认关、标注实验性。
- **模拟器宿主侧 root 脚本干扰升级**：周期拉起 com.vnlingo.app（用户自己的 VNLingo 悬浮字幕
  Android 应用！与本项目同名）与 ScummVM，还会 `pm disable` com.tyranor.next——表现为
  「Activity class does not exist」；用 `pm default-state com.tyranor.next` 恢复，
  干扰应用可 `pm disable-user`（会被脚本还原）或 `pm uninstall -k --user 0`（会破坏 PMS
  活动索引 → 同类「does not exist」，重启模拟器无效，需 `cmd package install-existing` 还原）。
- **进程活着时勿用 run-as 改 SharedPreferences XML**——进程内存态会在下次写回时覆盖外部修改；
  先 `am force-stop` 再改。

## 移植外事实（2026-10-05 会话二新增）

- **FT 钩子定时器的 ANR 教训（KRKR 实测）**：O(n²) 重绘链推导全速跑 + 与
  每字形钩子共用一把锁 → ARM 转译下游戏主线程被饿死（ANR 5s）。
  修复：锁内只拷 512 字节尾部快照、推导在锁外、400ms 轮询、raw 4096 截尾。
  通用教训：**推导/扫描类重活必须在锁外对快照做**。
- **模拟器新实例的组件禁用干扰**：模拟器重启后宿主脚本可能把
  com.tyranor.next 组件置 disabled（am start 报 does not exist）——
  `pm default-state com.tyranor.next` 恢复后再测。
- **KRKR 对白完整还原的两个备选（已评估，暂缓实施）**：
  A=字符串层 native 钩子——锚点：TVPCreateAndAddWindow(tTJSNI_Window*) 已导出
  （libgame.so 可见核心符号不止 GDI 桩），krkr2-main 源码对齐消息层文本函数偏移，
  arm64 手写跳板（本机 shadowhook 不可用）；B=TJS 发射器 KAG3.32 SIGSEGV 修复
  （延迟注入/非替换式包装）。LunaTranslator 对 KiriKiri 亦无自动多行重组，
  其方案=引擎签名钩子（字符串层）+ HCODE + 用户选候选，与我们的候选机制同构。
- **KRKR 内核导出全套 FreeType（旧结论已推翻）**：libgame*.so 的 FT_* 在 dynsym
  且 JUMP_SLOT 走自身 GOT——GOT 补丁可拦一切层文本渲染字符。对白路径 =
  FT_Get_Char_Index→FT_Load_Glyph（FT_Load_Char 不被调）。KAG 打字机整行重绘，
  码点流呈前缀链，还原需重绘状态机；说话人行（名字层+正文层双层重绘）有重复段
  待精化。kirikiri 核心层文本处理符号仍隐藏（18559 个导出多为 cocos2d/STL）。
- **品牌已更名 VNLingo（2026-10-05 会话五）**：app_name 与用户可见文案全部
  VNLingo；applicationId 保持 com.tyranor.next（包名兼容上游，勿改）。图标为
  PIL 程序生成（深靛蓝底白 V + 青条），源 PNG 可随时用同脚本重生成。
- **模拟器抽屉里的 "Tyranor Next" 条目不是本项目**：是宿主脚本装的
  `org.scummvm.scummvm.debug`（ScummVM debug，标签被改成 Tyranor Next），
  看到它别误判更名失败。
- **Artemis 左缘按键无效 = 内核 EmulateKeyEvent 是日志桩**（会话四实装修复）：
  注入走 `InjectHostKey` → EnqueueInput（与物理键同管线）；**key 是引擎官方 key id
  （13=ENTER、37-40=方向、140=ctrl），不是 Android keycode；status 0=down/非0=up**。
  点按式注入要 down+延时 up（120ms），跨帧才保证 IsPush/DownEdge 可见。
- **左缘按键统一抽象 EngineLeftButtons**（ONS/KRKR/Artemis 共用）：ButtonSpec 三语义
  （action 点按 / hold 按住 / toggled 高亮）、ONS chevron 折叠键在顶、圆角矩形 10dp；
  ONScripter 自制左列已删迁（autoButtons/styleVirtualButton 等一并移除）。
- **Artemis 文本提取的正解是带桥内核，不是运行时 hook（2026-10-05 会话三修正）**：
  内核源码仓在本地 `D:\Desktoprtemis-compat`（README/AGENT.md 齐全），其
  `build-android/libartemis.so` 即「带桥构建」（导出 SetExtractEmitter 全套，
  TagPrint/TagAudio 直接发射，native_activity.cpp 启动自注册）——此前反汇编盲找
  hook 点绕了远路，用户提醒「源码就在本地」后一步到位。随包 libartemis-clean.so
  已替换为该构建（llvm-strip 后 3.5MB），`pluginVersion` 29→30 触发自动重装；
  `nativeInstallExtractHook` 检测到 `SetExtractEmitter` 导出即跳过 GOT 钩子（防双发射）。
  语音配对：只认纯语音事件（text 空），消费即清 + 同句打字机分段粘滞（前缀延伸判定），
  否则无 voplay 的行会继承上一句语音 / 同句分段冲掉 ♪ 标注。GOT 钩子保留作无桥内核兜底；
  shadowhook inline 在本模拟器环境 `shadowhook_init` 即报 errno=12（API 34+ linker），
  与安装时机无关，别再往这个方向试。
- **游戏内面板与引擎进程跨进程读同份 SQLite/prefs**：`OnsDictStore`（ons_dict.db）多进程
  文件锁天然可用；SharedPreferences 必须显式 `MODE_MULTI_PROCESS`（OnsTranslateClient 已改），
  否则引擎进程读到陈旧缓存。
- **WindowOverlayHost 触摸放行别只靠 OnLayoutChangeListener**：VISIBLE→GONE 时 bounds 不变
  不触发回调，覆盖窗会停留可触摸态吃掉全部输入（Artemis 触摸失灵根因）；开合后必须显式同步
  （OnsExtractPanel.setPanelVisibilityHook）。

## 关键设计决策

- **云同步（2026-10-06 会话二十七重实现，参考 RinneMobile）**：app 模块
  `com.tyranor.next.core.sync` 三件套（WebDavClient/SyncSnapshotCodec/
  SyncManager）+ `ui.sync.SyncCenterActivity`。同步语义 = 快照哈希比对
  （本地/云端各 SHA-256 vs 上次同步哈希）分流：首传/首装下载/无变化/单侧/
  冲突（用户选）。快照 = 游戏库文本元数据 + 单游戏覆盖 prefs 镜像 + 策展
  配置整文件 + 游玩统计；**不含**存档 zip（面板 OnsSaveCloud 独立通道）、
  词典、封面、扫描根（SAF URI 跨设备无效且防泄露）。导入语义 = 键级合并
  （本地独有保留、同名以导入方为准、本机封面保留）；overrides 导入走 prefs
  镜像 + invalidateRowCache（启动同步回灌 DB，仓库设计的导入路径）。远程
  文件 `VNLingo/VNLingo_sync.json`（gzip）。本地备份 `.vnlbak` = gzip 快照。
  测试：本地最小 WebDAV 服务器 `.tmp-test/webdav_server.py`（HEAD/GET/PUT/
  MKCOL/DELETE/PROPFIND）+ `adb reverse tcp:1222`，客户端回环明文放行。
- 提取语义统一为「完整对话 + 配对语音 + 截图」，各引擎只做事件源
  （`ExtractFacade` 接口），面板/词典/翻译/制卡管线全复用（engine `com.core.ons`）。
- **连续页累积（2026-10-06 会话二十四，用户规则）**：「点击推进 + 有新文本」
  才翻页覆盖，否则累积拼接显示（同页换行 `\n` 追加 / KRKR 尾部缝合 / 打字机
  延伸增量）。实现在 `OnsExtractBridge.handleDialogue`（ONS+KRKR 共用），
  宿主经 `OnsExtractBridge.markPageAdvance()`（静态）上报推进点击——只有游戏
  输入路径（surface 触摸 UP、ENTER/DPAD_CENTER 透传、左缘 OK/NEXT/SKIP/AUTO）
  调用；面板/词典点击不经过该路径，不会打断显示。**快照延伸（字节前缀差分）
  优先于点击标记**：点打字中的画面只加速不翻页。候选切换（cand_idx>0）仍会
  覆盖句栏显示（既有机制）——排查显示问题先查 `ons_extract_tts.xml` 的
  `cand_idx_<游戏>`。Artemis（内核消息层累积）与 Web（整框事件）天然满足，
  未改动。
- 存档管理：条目管理页（应用层）承担导入导出；游戏内不再放存档 UI；
  facade 的存档数据通道（SaveZipUtil/WebSaveArchive）保留作数据能力。
- ONS 编码：保持 gbk/sjis/utf8 手选（默认 gbk），不做 auto 探测。
- AnkiDroid API 走 vendored 源码（Apache-2.0 头保留）而非 JitPack；ML Kit 是唯一新增依赖。
- 截图走 PixelCopy 游戏 SurfaceView（纯画面），Surface 不可用再回退整窗。
- **引擎支持裁剪（2026-10-05，用户决定）**：只保留 krkr/ons/rpgmaker 系列/tyrano/renpy/web/
  artemis；Siglus/RealLive/AVG32/UK2/FVP/YU-RIS/CatSystem2/PC 与 PSP/NS 外置跳转从
  扫描/启动/UI/设置全链路移除，外置模拟器基础设施类保留为死代码（无入口）。
- **设置归位（用户决定）**：词典管理=底栏词典页；翻译/云同步=应用设置页
  （OnsExtractSettingsDialogs 宿主无关弹窗）；游戏内面板设置只留朗读+显示。
- **手柄重映射**：GamepadRemap 快照式（宿主 onResume 刷新），映射在 dispatchKeyEvent
  最前端生效，先于提取面板/虚拟鼠标/游戏链路。**注意：Artemis（NativeActivity）
  的按键不经 dispatchKeyEvent（2026-10-06 实测修正）——其重映射仅在虚拟鼠标模式
  （光标窗口接管按键焦点）时经 routeMouseKey 生效**；统一虚拟鼠标 =
  `com.core.engine.EngineVirtualMouse`（ONS/KRKR/Artemis 共用；Artemis 点击走
  内核 InjectHostTouch JNI、按键走可聚焦光标窗口，见会话二十五）。

## 实测踩坑（2026-10-07 会话四十二新增：Compose 对话框自动化 + toast 消失）

1. **adb 自动化填 Compose 对话框三字段**：悬浮框 AlertDialog 在 IME 打开时
   整体上移，`ui_resolve` 给的是 occluded 布局坐标——直接照着点会点在键盘上。
   可靠流程：关键盘点字段 → `input text` 注入（不弹键盘）→ BACK 收键盘 →
   再点下一字段；填完再点确认键。BACK 在 IME 打开时只收键盘、IME 已收时
   会关对话框（onDismissRequest），状态全丢。
2. **流式重装后 toast 可能不显示**：`adb install` 流式重装后 SystemUI 资源
   缓存指向旧 APK 路径（ziparchive "No such file"），Toast 照常入队但视图
   膨胀失败——屏幕上看不到。判断 toast 是否触发要靠 logcat
   `SystemUIToast` 行，别等截图。
3. **清空 Compose 输入框**：没有 select-all 快捷键可用时，
   `input keyevent 123`（MOVE_END）+ 循环 `keyevent 67`（DEL）最稳；
   点字段时光标落点不定，先 MOVE_END 再删。
4. **engine 静态注入点解「engine 需要的数据在 app」**：模块方向 app → engine
   不许反向依赖，但 app 进程的 Application.onCreate 可向 engine 静态字段
   注入回调/提供者（例：OnsSaveCloud.setWebDavCredentials 注入 SyncManager
   活动账户）。所有进程都会跑同一 Application.onCreate，引擎子进程同样生效。
5. **pm clear 后重装游戏库不必重扫**：游戏文件在 /sdcard/Download/games/
   等共享存储不受 pm clear 影响，SAF 重授权 + 扫描即可恢复；首次启动游戏
   前需系统设置里开「所有文件访问权限」（MANAGE_EXTERNAL_STORAGE），否则
   启动失败弹「启动失败」对话框。
6. **存档上传 E2E 不用真玩游戏**：ONS scoped 存档目录
   `/sdcard/Android/data/<pkg>/files/save/<游戏名>/` 可用 adb 直接种假存档
   文件（默认 scopedSaveDir=true），再走存档管理页「上传存档到云端」即可
   验证完整上传链路（本地 WebDAV 服务器 .tmp-test/webdav_server.py +
   `adb reverse tcp:1222 tcp:1222`）。

## 实测踩坑（2026-10-07 会话四十一新增：模块依赖方向）

1. **engine 模块不能引用 app 类**：Gradle 依赖方向是 app → engine，跨模块
   搬运代码时容易把新类落到 app 包却在 engine 里引用（MCP 三类最初落在
   `com.tyranor.next.core.agent`，OnsAgentDialog 引用时直接编译失败）。
   规则：被 engine（游戏内 UI/引擎面板）使用的核心逻辑一律放
   `engine/src/main/java/com/core/**`；app 包只放纯 UI 入口层。engine 已配
   Kotlin（stdlib 在依赖里，大量 .kt 文件），移 Kotlin 文件过去零成本。
2. **跨模块搬运后 grep 旧包名收尾**：`grep -rn "tyranor.next.core.agent"`
   能抓全残留引用（本次 OnsAgentDialog 有两处全限定名引用，修一处漏一处）。

## 实测踩坑（2026-10-07 会话四十新增：移植遗漏与多轮迭代收尾）

1. **移植功能可能没有 UI 入口**：PlayStatsActivity 从 Tyranor-Next 移植时
   Manifest/Activity 都在，但**没有任何地方调用 createIntent**（基线 commit
   7b923c4 起即如此，后续无人发现）。排查方法：对每个 Activity 统计
   createIntent 引用是否为零。功能「移植完成」的验收标准必须包含入口可达。
2. **多轮迭代后模拟器不稳定**：宿主脚本/GMS 弹窗/应用闪退叠加后，连应用
   启动都可能落到桌面。`pm clear` + `uninstall/install` 可恢复，但更稳妥
   的做法是 reboot 后再测（曾实测 reboot 后恢复正常）。
3. **功能清单对账要定期做**：多轮迭代后易出现「代码写了但 UI 没接」
   （如 PlaySessionTracker 数据层完成但无统计入口）。每轮收尾对照
   「Activity 清单 × 入口清单」做差集扫描可发现这类遗漏。

## 实测踩坑（2026-10-06 会话二十九新增：自动朗读开关断连）

1. **同一功能两处开关必须同键或显式联动**：TTS 自动朗读曾有面板键
   （ons_extract_tts/tts_auto）与设置页全局开关（ons_tts_engine/auto_read）
   两套存储互不相通，全局开关形同虚设（会话二十三引入时未接通）。修复后
   语义 = OR（任一开启即自动朗读）。新增开关时先查同功能既有键。
2. **隐藏 UI 后不可达的开关不得阻塞全局路径**：自动朗读曾被面板「TTS 朗读」
   开关（ttsEnabled）静默拦截——面板隐藏时该开关无法操作，等于全局开关被
   永久静音。分层：ttsEnabled 只管手动播放；自动路径由自动开关全权管辖。
3. **此类断连的定位法**：在门控处加临时日志（生效条件各布尔 + 输入）跑一遍
   真实流程，一眼看出哪个条件不符合；定位后移除日志。

## 实测踩坑（2026-10-06 会话三十新增：释义悬浮窗与高亮保留）

1. **renderSentence 每次清 matchedRange = 扫描高亮从未显示过**：查词命中后
   refresh→renderSentence 会重建 span 并把 matchedRange/selectedToken 清空，
   递减扫描命中的整词高亮（区别于点选的单字高亮）实际从未渲染。修复：同句
   重渲染保留状态、翻句显式清位。凡「高亮后重建渲染」的路径都要查这类清位。
2. **多进程/干扰环境下长链路 UI 自动化的止损点**：模拟器宿主脚本+GMS 弹窗
   会反复抢前台/拉起无关应用/闪退，此时逐 hop dump 校验仍可能被吞点击。核心
   链路验证完即收，剩余确定性短路径以代码审阅替代实机轮次，避免无限重试。
3. **顶部悬浮窗会阻挡其覆盖区域的游戏点击**（jidoujisho 同款交互代价）：
   释义悬浮窗开着时推进剧情要点窗外（下方）；滚动区高度上限 90dp 防止空
   释义撑出大面积遮挡。

## 实测踩坑（2026-10-06 会话二十八新增：翻译源移植）

1. **翻译测试弹窗回调直触视图 = 翻译线程崩溃杀应用**：translateWithEngine
   的回调在引擎线程执行，设置弹窗的回调直接 setText → OnlyOriginalThread
   FATAL 整个应用退出。修复双保险：弹窗回调 runOnUiThread 包裹 + 引擎线程
   回调调用点 try/catch（引擎线程绝不能因消费方异常死亡）。新增引擎路径时
   此约束同样适用。
2. **Bing 非官方端点（ttranslatev3）在本模拟器网络稳定 400**：token 流程
   （抓页 IG/IID/params_AbusePreventionHelper + Cookie）忠实按 MoeTranslate
   移植，桌面/移动 UA、三个页面 IID、Origin/Referer、key/token 交换全试过
   仍 {"statusCode":400}——IP 信誉/反爬策略，非代码问题。错误已带响应体
   透传到测试弹窗。真机住宅网络可能可用（MoeTranslate 用户实测背景）。
3. **googleapis 免费翻译端点在国内网络不可达**（curl 直接 000）——免费
   Google 引擎需设备代理；文档/UI 不承诺可用性。

## 实测踩坑（2026-10-06 会话二十六新增：虚拟鼠标绑定与长按连移）

1. **旧虚拟鼠标上下步进是左右的两倍**（`UP->-2/DOWN->2` vs `LEFT->-1/RIGHT->1`，
   ONS 原始实现遗留）——横屏下上下移距两倍，用户体感「竖屏移动没切成横屏」。
   已改四向等步进 + 按住时长加速。排查此类「方向分配不对」报告先查步进表
   再查轴向。
2. **空 StringSet 会挡住默认回退**：绑定快照 refresh 时 `getStringSet` 缺省
   emptySet 非空 → 存进快照的空数组使 `codes()` 认为已自定义 → 默认绑定
   失效且 UI 显示「未映射」。空集不得进快照（`isNotEmpty` 才入）。
3. **长按连移不依赖系统按键重复**：内部 Handler 重复引擎（350ms 延迟、
   60ms/步、线性加速），系统 repeat 事件一律吞掉——adb `input keyevent`
   注入无重复序列，同样可测单步；真机键盘/手柄按住由内部引擎驱动，行为
   与注入环境无关。
4. **绑定语义**：动作的自定义键集「整体替换」默认（非合并）——设置页恢复
   默认 = 删除自定义集。写文档/回复用户时明确此语义。

## 实测踩坑（2026-10-06 会话二十五新增：虚拟鼠标与 NativeActivity 输入）

1. **NativeActivity 的按键不经 Activity.dispatchKeyEvent（实证，修正旧认知）**：
   Artemis 宿主此前「dispatchKeyEvent 前置手柄重映射」从未真正生效（当时未真机
   手柄验证）——D-pad/A 根本到不了 Java（键走 InputQueue 到内核）。应用层拿键的
   可行路径 = 光标窗口设为可聚焦（移除 FLAG_NOT_FOCUSABLE）接管按键焦点，事件
   经 ViewRootImpl 派发到窗口根视图的 dispatchKeyEvent；模式关时窗口
   NOT_FOCUSABLE，按键自动归还游戏窗口。内核未注册 focus 回调 → 游戏不因失焦
   暂停（已核实）。
2. **NativeActivity 的触摸应用层不可注入**：触摸 InputQueue 为 native 独占，
   dispatchTouchEvent 无对象可派发（无 SurfaceView/视图树）。唯一路径 = 内核侧
   注入 JNI（artemis-compat InjectHostTouch，与 InjectHostKey 同构，key id 1=
   鼠标左键，坐标为窗口像素由引擎换算 stage）。官方 revision 内核无此符号，
   调用处必须 catch UnsatisfiedLinkError 降级。
3. **摇杆事件会被内核当触摸**：NativeActivity 窗口的 SOURCE_CLASS_JOYSTICK
   Motion 事件轴值是 -1..1 归一化，内核 OnInputEvent 不过滤会被误当窗口像素
   触摸入队（坐标 (0.7,-0.3) 之类）。已在内核过滤该 source 类。
4. **手柄键注入验证可模拟**：无实体手柄时 `adb shell input keyevent` 直发
   KEYCODE_DPAD_*/BUTTON_A(96) 即可全链路验证（EngineVirtualMouse 只看 keycode
   不看 source）；光标位移验证用前后截图 diff（箭头 ~26px 白色带黑描边），
   白像素匹配会撞上按键组/游戏文字，diff 最可靠。
5. **游戏菜单点击测试要点**：定位点击的靶点要按游戏菜单文字的实际物理坐标算
   （截图 2000 宽显示 × 1.2 缩放），空白处点击无反应会被误判成「点击失效」。

## 实测踩坑（2026-10-06 会话二十三新增：设置页入口与 TTS 链路）

1. **设置页全部弹窗入口的 ctx 都被 AppLocaleController 包装**：
   `ctx as Activity` 必崩（翻译/制卡/云同步三处先后踩雷）。统一写法
   `AppLocaleController.findActivity(ctx) ?: return@ArrowPreference`。
   新增设置入口时禁止再写强转。
2. **MultiTTS /forward 的 voice 参数吃 id 不吃显示名**：/voices 目录 JSON 里
   每条有 id（bdetts_xiao-xiao-…）与 name（晓晓 多语言），传 name 报 500
   「未找到发音人」。VoiceEntry 必须 value=id 优先、display=「目录/name」。
3. **本地 TTS 测试服务器**：.tmp-test/tts_server.py 单文件模拟三协议
   （/api/tts、/forward、/voices，WAV 响应并打印收到的文本）+
   `adb reverse tcp:1221 tcp:1221` 即可让 HTTP 引擎端到端测试不依赖真机
   TTS 服务；服务器日志会打印收到的自定义文本，是验证「真的收到音频
   请求」的最直接证据。
4. **横屏侧栏布局 + 弹窗 ScrollView 的点按坐标极易漂移**：设置列表在
   侧栏布局下的行位置与竖屏不同，弹窗 ScrollView 滚动后 uiautomator dump
   的 bounds 才是准的——盲按截图坐标会连续误触（本轮多次踩）。

## 实测踩坑（2026-10-05 会话十一新增：授权框与窗口 token）

1. **Activity.requestPermissions 必须主线程发起**：后台线程调用时系统授权框
   静默不弹（无异常、无日志），用户视角就是「点了没反应」。制卡等后台流程里
   请求权限一律 Handler(mainLooper).post。
2. **TYPE_APPLICATION 悬浮窗（游戏内面板/悬浮框）跟着 Activity token 走**：
   Activity 旋转重建后旧 token 作废，静态持有旧 WindowManager 的悬浮窗
   addView/updateViewLayout 会抛 BadTokenException 或永远挂在死窗口上
   （showing() 还返回 true 导致永不重挂）。跨重建的悬浮窗要记录 owner Activity，
   换实例整体重建 + 所有 wm 操作 try/catch。

## 实测踩坑（2026-10-05 会话十新增：PRAGMA/导航模式）

1. **PRAGMA 带结果行必须 rawQuery**：`execSQL("PRAGMA busy_timeout = 5000")` 在
   Android 上抛 `SQLiteException: Queries can be performed using SQLiteDatabase
   query or rawQuery methods only`（PRAGMA 有返回行时 execSQL 拒绝）→ 主进程
   启动即崩。用 rawQuery + close，外面再包 try。
2. **导航模式影响全部点按坐标**：模拟器切手势导航（底部横条）后，右缘按键列与
   提取面板的 inset/宽度计算都变（面板呈全宽透明态、坐标整体漂移），三键导航
   时代码的坐标记忆全部失效。坐标类自动化前先 `dumpsys input | grep -i scale`/
   截屏确认导航模式；测试中不要切导航模式。
3. **查词 miss 与「未导入词典」共用 toast 会把排查带偏**：本次因这句长文案
   误判成「游戏进程词典库为空」绕了远路——已拆分成两句（miss=未查到该词）。
   工程上：同一 toast 文案覆盖两个语义分支时优先拆分。

## 实测踩坑（2026-10-05 会话七新增：制卡端到端实测）

1. **install-debug-apk.sh 不带 `BUILD=1` 只装已构建 APK**（脚本头部有用法）；且中途
   失败的构建会留下不一致的 up-to-date 缓存，之后"成功"的构建仍可能打包旧 dex——
   怀疑改动没生效时用 python zipfile 逐 dex 验证特征字符串：
   `zipfile` 读 APK → 按 `classes\d*\.dex` 逐个 `in` 判断。Git Bash 的
   `unzip -p | strings` 在 171MB 多 dex APK 上会漏报/误报，不可作判据。
2. **KRKR 1.3.9 内核的 sink 通道永远不存在**：KR2Activity.setExtractListener →
   nativeSetExtractSink 在现役 libgame.so 无导出（UnsatisfiedLinkError 被 catch
   静默降级，logcat 只有一行 warn）。FT 钩子 [FTLN] 的唯一上行通道是
   NativeBridge.onKrkrText——别再往 sink 通道上加功能，也别被
   「krkr2 extract sink armed」日志骗了（catch 之后仍会打印）。
3. **AnkiDroid 2.25 制卡链路（实测可用）**：官方 AddContentApi（vendored 源码，
   authority `content://com.ichi2.anki.flashcards`）仍被支持；权限
   `com.ichi2.anki.permission.READ_WRITE_DATABASE` prot=dangerous，但
   requestPermissions 的系统弹窗在模拟器上不出现 → 自动化用
   `adb shell pm grant com.tyranor.next com.ichi2.anki.permission.READ_WRITE_DATABASE`。
   首次制卡前 AnkiDroid 必须完成过首启（intro + All files access），否则 collection
   未建。AnkiDroid 非 debuggable（run-as 拒绝），验证走 UI（deck 列表/Card browser）
   或 logcat。
4. **模拟器快速种词典**：设备端自带 sqlite3 且 `run-as com.tyranor.next` 可用
   （debug 包），SQL push 到 /data/local/tmp（MSYS 下记得 `MSYS_NO_PATHCONV=1`）
   后 `run-as ... sh -c 'sqlite3 databases/ons_dict.db < ...'`；schema =
   dicts/entries/kv 三表，需写 kv 的 current_dict 与 dict_name，改完重启应用生效
   （entryCount 启动时读入）。
5. **AnkiDroid 制卡入口注意**：面板释义区 📖（makeWordCard，词卡）与动作排 📋
   （sendToAnki，句卡）是两个入口；制卡失败各分支都有 toast，但失败路径多数无日志，
   排查时先截 toast（点击后 ~1.5s 内截图）。

## 续作指南（下次会话从这里开始）

1. **推送**：`feat/extract-suite` 已推远端（b884e97，2026-10-07 经 GitHub
   浏览器 OAuth 授权一次完成认证；origin 已改回 github.com 真实地址——
   gh-proxy 镜像不支持 push）。main 落后 103+ 提交待合并。
2. **运行时回归**（模拟器装 VNLingo debug——注意会覆盖 Tyranor-Next，且必须
   `adb install -r --abi arm64-v8a`）：
   - ONS（ym/esg）：右缘按键组、提取面板暗色圆钮、查词/翻译/制卡、点击模式切换
   - Web（Tyrano/RPG Maker MV/MZ 各一）：`__tn_extract.js` 注入、对话/语音捕获
   - Artemis（blossom，clean 内核）：提取面板文本/语音名（native 提取钩子）、关闭面板后触摸
   - 词典：词典页导入 → 多词典管理；应用设置翻译测试弹窗
   - 手柄：映射后进游戏验证（模拟器无实体手柄，可 `adb shell input keyevent` 源模拟有限，
     完整验证需真机手柄）
   - ML Kit 模型下载需设备带 GMS
3. **遗留**：packed Artemis 语音字节不可播（内核侧语音副本缺失）；官方 revision 内核无提取；
   手柄重映射未在真机手柄上验证。
4. **文档约定**：方案文档进 `docs/`（状态/目标 → 需求范围 → 架构 → 实施记录）；
   commit 用「类型: 描述」并按单一类型分组；**每次修改后补充对应文档的实施记录**。

## 实测踩坑（2026-10-08 会话新增：Artemis 官方内核提取正解）

1. **官方内核（Girl's Blossom Project / V1）文本提取唯一生效通道 = `CArtemis::CommandPrint`**：
   `CArtemisParser::Text/TextTail`、`CBackLog::Add` 三个解析器钩子补丁地址全部
   核对无误（dlsym 偏移与 maps 基址算术吻合）但**零调用**——该游戏的文本根本
   不流经这些函数。`CArtemis::CommandPrint(CScriptBlock&, bool)` 随每个脚本
   print 命令触发（boot 期也有），块参数 `std::map<string,string>` 是 libc++
   `__tree_node`：left+0x00 right+0x08 parent+0x10 black+0x18 **key+0x20
   value+0x38**（readStdString 直读）；key=`"data"` 携带文本。**说话人行与
   正文行成对到达（间隔 ~2ms）**：先 `data="Himari"` 再 `data="正文"`。
   sizeof(CScriptBlock)≈88（数组连续存放，转储按 96 切会错位）。
   `CScriptBlock::ToString` 有导出但该游戏不走（防御性钩子保留）。
2. **emitText JNI SIGABRT（CheckJNI）**：`onArtemisExtract` 签名是**三个
   String**（text, voiceName, voiceCached），CallStaticVoidMethod 少传参数时
   CheckJNI 会把 va_list 的栈上垃圾当 jobject 校验 → `use of invalid jobject`
   SIGABRT（abort 地址就在栈帧附近是判别特征）。JNI 可变参调用参数个数必须
   与签名严格一致；测 JNI 上行务必在 CheckJNI 开启的 debug 包上验。
3. **MIUI 12.5 (alioth) W^X 加固下的 inline hook 写入**：mprotect 改 RX 后
   无法恢复写（EACCES）→ 用 `/proc/self/mem` pwrite 内核直写 + pread 回读
   校验 + `__builtin___clear_cache`，实测补丁在位且可执行。
4. **官方内核 GOT 钩子必然失败**：DispatchTag/SetMessageLayered/AudioChannels::Play
   是 clean 内核符号，官方内核不导入（有导出无 GOT 槽）——语音配对要官方内核
   生效须走同款 dlsym+inline hook（CSoundTrack/CSoundManager 播放路径，待做）。
5. **横屏游戏的截图坐标**：screencap 返回 2400x1080（横屏），缩略图坐标换算
   系数 ×2.667；竖屏列表页 1080x2400 系数 ×1.2——先 `python PIL` 打印
   `im.size` 再换算，方向搞错全部点击落空（本轮反复踩）。
6. **JNI 存活对象的内存转储用 memcpy 直拷**（调用期内对象有效）；
   process_vm_readv 自读在页边界会静默截断（返回已读字节数）。

## 实测踩坑（2026-10-08 会话二新增：面板窗口宿主与 ONS 侧键）

1. **窗口承载宿主的面板宽度强转会吞掉整个展开链路**：applyPanelWidth 把
   WindowManager.LayoutParams 强转 FrameLayout.LayoutParams → ClassCastException，
   异常发生在 togglePanel 的 refresh() 之前 → 面板展开后永远空白「待机」。
   修复双保险：refresh 提前到 apply* 之前 + apply 加 try/catch；
   宽度应用按宿主模式分流（WindowManager.LayoutParams → panelVisibilityHook
   updateViewLayout；FrameLayout → setLayoutParams）。诊断特征：面板工具条
   可见但状态永远「待机」、正文永远空。
2. **ONS overlay 模式右缘按键组在 alioth 上整体不可触**（可见但点不动）：
   uiautomator dump 显示侧键 bounds 全 [0,0][0,0]（左栏 EngineLeftButtons
   正常）；dumpsys window windows 拿到侧键窗（独立窗口化后）真实 frame
   x=2284-2394——此前按缩略图估的 2261 根本没进窗口。**自动化点侧键前先
   dumpsys 拿窗口 frame，别信缩略图估算**。已把 ONS 整体切独立窗口承载
   （对齐 Artemis），真机面板/点词/词典卡全通。
3. **MIUI 隐藏导航条也占触摸区**：fullscreen 下 NavigationBar0 不可见但
   右缘 ~210px 仍归它管（mNavigationBarPosition=2=右），贴缘窗口用
   insets.right=0 的锚点会落进劫持区。短边 cutout（SHORT_EDGES）下窗口
   content=[80,0][2400,1080]（左 80px 挖孔区）。
4. **Artemis CommandVoice 通道已就位**：CArtemis::CommandVoice(CScriptBlock&,bool)
   与 CommandPrint 同签名，块参数树取疑似语音文件名（音频扩展名/含路径），
   首 8 调用记录键值对（logcat "CommandVoice arg"）——Girl's Blossom 序章
   无语音行未实测，跨游戏验证时先看这批日志核键名。
5. **说话人/正文 native 配对**：400ms 窗 + 短行(≤48B) 判说话人，合并单次上行；
   420ms 兜底线程 flush 独立行。分条上行会让 facade 历史多出说话人碎片条目。

## 实测踩坑（2026-10-08 会话三新增：查词性能与真机基准方法）

1. **`entries` 表没有 reading 索引是查词慢的根因**（会话四十三定位）：
   `migrate()` 只建了 `idx_entries_term`（单列，实际没用）与
   `idx_entries_dict_term`。查询 `dict_id IN (1) AND reading = ?` 在 SQLite 3.28
   （Android 11）上**不会**走 skip-scan（无 `sqlite_stat1` 统计），退化为按
   `dict_id` 单列扫描该索引 + `ORDER BY pop DESC` 临时 B 树。面板
   `tryMatchCandidate` 对每个候选都查一次 reading，21 字句子 200 - 600 次查询 →
   真机实测整句扫描 2345 ms，补 `(reading, dict_id)` 索引后 22.7 ms。
   **结论：词条表按 TrackReader `db.ts` 的做法建 term / reading / dictionaryId
   三个索引；导入后跑一次 ANALYZE。** 顺手记：单列 `idx_entries_term` 无用可删。
2. **安卓代码的性能可以在真机上直接量，不用装 APK**：`d8 --min-api 30` 把编译产物
   打成 dex → `adb push` 到 `/data/local/tmp` → `adb shell "CLASSPATH=/data/local/tmp/x.dex
   app_process /system/bin <主类>"`。此时用的是**设备 ART 与设备 libsqlite.so**
   （SQLite 版本 3.28.0），比桌面 JVM 估算准得多；`android.util.Log` 等框架类由
   设备的 bootclasspath 提供，`org.json` 也直接可用。要点：类要有 package 声明
   （无包名的 .class 在 ART 上会 ClassNotFoundException）；私有嵌套类无法跨包引用，
   用反射 shim 注入测试数据。
3. **Windows PowerShell 的 `>` 重定向会毁二进制**：本机把 stdout 当文本编码成
   UTF-16LE（文件头 `ff fe`），`adb exec-out ... > file` 拉下来的数据库必然
   "file is not a database"。改用 `cmd /c "adb exec-out ... > file"` 或
   [System.IO.FileStream] 直写。
4. **真机拉应用私有数据库**：`cmd /c "adb exec-out ""run-as com.tyranor.next cat
   databases/ons_dict.db"" > x.db"`。应用 shell 用户不能执行 /data/local/tmp 里的
   原生二进制，但 `shell` 用户可读写执行该目录——所以测库要用**副本**
   （`cp` 到 /data/local/tmp），不要试图原地改应用私有库。
5. **词典查询计划随规模变化**：6.7 万词条时无 reading 索引的退化扫描约 4 ms/次
   （66k 索引页基本驻留 2 MB 页缓存），50 万词条时同一查询 71 ms/次、整句扫描 46 秒。
   小词典上的「还行」会掩盖索引缺口，性能用例必须带一个大词典（生成脚本见
   `.tmp/perf/gen_big_dict.py`）。

## 实测踩坑（2026-10-08 会话四新增：查词性能 B1–B4 实施）

1. **补索引必须另开一条连接，否则「后台线程」名存实亡**：`CREATE INDEX` 与查询
   共用同一个 `SQLiteDatabase` 时，DDL 的写事务会把同连接的查询一起堵住。
   做法：`db()` 打开时只探测 `sqlite_master`，词条数 ≤ 10 万同步建
   （6.7 万 = 297 ms，可接受），超过则 `openOrCreateDatabase` 新开连接 +
   `busy_timeout` 后在后台建，主连接照常查词；完成状态用静态
   `isReadingIndexBuilding()` 暴露给 UI 轮询。注意建索引期间别处写库会等
   busy_timeout（5 s），大词典导入要避开这一窗口。
2. **DDL 要先用 sqlite_master 探测**：`CREATE INDEX IF NOT EXISTS` 与
   `DROP INDEX IF EXISTS` 每次执行都算一次 schema 写事务（跨进程打开时更容易
   撞锁），先 `SELECT 1 FROM sqlite_master WHERE name=?` 再决定更省事。
   `ANALYZE` 无结果行，可以 `execSQL`；`PRAGMA` 有结果行，必须 `rawQuery`。
3. **批量 IN 改写要保住命中优先级**：把「原形 term → 原形 reading → 各还原形
   term → reading」的串行查询改成批量时，**不能只用一张「命中键 → 结果」的表**——
   同一个还原形既可能 term 命中也可能 reading 命中，必须 term / reading 各查一次
   （两张 map），再按原顺序取用；`maxGroups` 上限与 gloss 合并语义照旧
   （超出上限只并释义、不新增组）。正确性用整句全候选新旧路径逐一对照兜底
   （真机 41/41 一致）。
4. **建索引后记得 ANALYZE**：没有 `sqlite_stat1` 时 SQLite 3.28 对
   `dict_id IN (...) AND reading = ?` 不会自己选 skip-scan；建索引 + ANALYZE 后
   计划稳定落到 `idx_entries_reading (reading=? AND dict_id=?)`。
5. **性能证据链要能一键复现**：`.tmp/perf/droid/perf/Probe5.java` 在**同一份库**上
   对照「旧串行 / 新批量」×「有 / 无索引」四组，编译方式 = `javac`（classpath =
   `android.jar` + `json.jar`）→ `d8 --min-api 30` → `app_process`
   （见上一节第 2 条）。改完立刻出数字：无索引 374 ms → 有索引 5.9 ms，
   同库旧串行路径 603 ms / 9.7 ms。
6. **应用内的性能日志要落在 DiagLog 而不是临时打点**：面板查词现在记
   `hit: <词> groups=N in Mms` / `miss in Mms`，真机日志（`files/diag/diag.log`，
   `run-as` 可读）实测 8–31 ms/次，后续回归直接看数值，不必再搭基准。

## 实测踩坑（2026-10-08 会话四之二：游玩统计注入与运行时恢复）

1. **`play_sessions.game_title` 不是游戏名**：`PlaySessionLifecycle` 取的是
   `activity.title`，引擎宿主 Activity 上等于应用名（全部记录都是「VNLingo」）。
   按 title 聚合/展示会把所有游戏并成一条；**展示名要用 game_uri 路径推导**
   （`.../game/<引擎>/<游戏目录>/...` 取游戏目录段；旧 JSON 数据源的键是游戏根
   目录绝对路径，同一套推导可用）。要修生命周期侧就得引入「路径 → 库内标题」
   查询，暂未做。
2. **engine 引不到 app 的注入模式可复用**：`OnsSaveCloud.setWebDavCredentials`
   之后，AI 助手统计走同一招 `OnsAgentDialog.setPlayStatsProvider`（静态
   provider + 接口，`TyranorNextApplication.onCreate` 注入）。引擎子进程也会跑
   Application.onCreate，因此注入在游戏进程同样生效；provider 调用要 try/catch，
   引擎线程不能因数据源异常死掉。
3. **LLM 工具「有定义没接线」是隐蔽桩**：`get_play_stats` 之前既没有工具条目、
   `get_game_info` 也从不调用 `getPlayStats()`——只看 `ToolHost` 实现会以为
   「只差数据源」。加工具时同时补 `buildTools()` 条目与 `executeTool` 分支。
4. **原生运行时的真实恢复通道**：插件 zip 随 APK 打包在
   `assets/nativeplugins/<engineId>.zip`，`EnginePluginBootstrap` 只认这个来源
   （无下载通道、manifest 也没配期望哈希）。所以「卸载后恢复」的正确实现 =
   `markRestored` + `provisionIfNeeded` 重新解压内置 zip（离线可用），
   而不是去找网络下载。本地 zip 导入要用 `NativePluginInstaller`，其整包
   SHA-256 校验在未配置期望哈希时会必然失败——已放宽为「无期望哈希则仅结构
   校验」并在 UI/结果里标注 `verified=false`。
5. **MIUI DocumentsUI 的文件列表不吃 `adb input tap`**：抽屉、视图切换、Chip
   都能点，列表项（网格/列表都试过）点不动；`input swipe x y x y 60` 模拟点按
   同样无效，D-pad 只移动焦点且会误入目录。**需要选文件的自动化请改用模拟器
   （Pixel_6，历史会话里 SAF 选文件成功过）或无障碍服务**，别在真机上耗时间。

## 实测踩坑（2026-10-08 会话四十五新增：KRKR 提取通道的真实覆盖面）

1. **FT/Label 钩子只覆盖「一部分」KRKR 游戏，判据要看计数而不是看有没有报错**：
   tsukikage no simulacre（KAGParserEx + 自带 textrender/layerExDraw/extNagano/
   PreRenderFontEx）全套钩子都「武装成功」（Label 虚表 slot 173、FT 三个 GOT 槽
   patched=1、反向映射 42387 项），但整局 **FT_Get_Char_Index / FT_Load_Glyph /
   FT_Load_Char / FT_Get_Next_Char / Label::setString 调用计数完全冻结**——
   标题菜单、18 禁告知、对白全都不走这些路径；对照组 kazurauta 进正片后计数
   168→909 且 `ft line:` 正常。**「武装成功」≠「有字符流」，判失效先看计数器。**
2. **诊断埋点要按「通道计数 + 原始链内容」两件套做，且计数打印不能被
   「raw 为空就 continue」的早退挡住**：首版把 `ft state` 放在 `if (gRawBuf.empty())
   continue;` 之后，清空后完全看不到计数是否还在涨，差点误判成「有字符流但推导
   不产出」。计数器日志必须在任何早退之前打。
3. **GOT 槽原值落在哪个库可以一眼定位 FreeType 归属**：`cat /proc/<pid>/maps`
   （`run-as` 读自家进程即可）配合 `GOT hook ... original=0x...`，地址落在
   libgame.so 段内即说明 FT_Get_Next_Char 解析到内核内静态链接的 FreeType；
   `dlsym` 地址与槽原值一致才排除「第二份 FreeType」假设。
4. **`FT_Load_Glyph(face, glyph_index)` 也能还原码点**：码点→字形的映射若在字体
   装载期一次性完成，运行期只剩字形索引。做法 = 惰性建 `glyph_index → 码点`
   反向表（对 BMP 逐码点问原 `FT_Get_Char_Index`，取首次命中；arms 上 4 万项
   约 200ms 一次建成），构建后原子发布指针、读侧无锁；与 `FT_Get_Char_Index`
   通道共用相邻去重入口，避免同一字符被喂两次。对「只走 Load_Glyph」的内核可用，
   对完全不走 FT 的游戏（上面第 1 条）仍然无效。
5. **真机游戏条目页的正确入口**：游戏卡片 → 底部弹窗 → `引擎设置`；两个并排按钮
   的实际 bounds 用 `uiautomator dump` 拿（`引擎设置` 在 [554,1985][1036,2145]，
   按截图缩放估坐标会点到左边的「存档管理」）。`EngineSettingsActivity`
   **未导出**，`am start` 直拉会被 SecurityException 拒绝，只能走 UI 导航。
6. **给下游 agent 的方案文档可能带过期假设**：本轮方案记的「SDL3 下单游戏开关被
   `if (!isSdl3)` 隐藏」「`kr_extract_tjs` 无 UI 入口」都与 HEAD 代码不符
   （前者 if 只包住操作卡片，后者设置页早有开关）。执行前先按 HEAD 核对一遍，
   别照抄方案里的行号/结论。

## 实测踩坑（2026-10-08 会话四十五之二：Kirikiroid2 里 TJS 探针怎么写）

用 `kr_extract_probe`（默认关）向 patch.tjs overlay 注入纯 ASCII 探针实测得到：

1. **`for (var k in obj)` 与 `in` 运算符都不被这个 TJS 支持**——两者都直接
   syntax error，而 patch.tjs 是**整份编译**的：一处语法错误 = 整个 patch 不执行
   → 游戏引导失败（表现为控制台只有 `Syntax error at line N`、随后卡死）。
   改脚本时先用「编号构造 + 读错误行号」的方式二分，别一次写完。
2. **访问不存在的全局会抛异常**，而且 TJS 会把异常连同**反汇编转储**（单次约
   2 万行）打进控制台 → 足以把 logcat 冲爆、把之前的探针输出挤掉。
   所以「轮询尚未注册的全局」不能用 try/catch 试错；只能比较**已存在**的对象
   （例：记下 `global.KAGParser` 原值，逐帧比 `===`），或用一次性探测。
3. **`System.addContinuousHandler` 存在且非致命**，是 krkr2 里唯一可用的延迟手段：
   实测连续跑满 901 / 1800 帧，游戏照常渲染、照常响应点击（旧结论
   「延迟手段本身致命」应限定为 **Timer**：Timer 在 Initialize 阶段实例化会段错误）。
   移除用 `System.removeContinuousHandler(fn)`（找不到就 try/catch 吞掉）。
4. **KAG 在 patch.tjs 之后第 1 帧就把 `global.KAGParser` 换掉**（Config/Init 阶段，
   实测 `swapped frames=1`），但换掉后 `global.KAGParserEx` **仍不是全局名**
   （类没有暴露成全局；访问会抛）。⇒ 想包装解析器类，「注册前不可写」的窗口只有
   ~1 帧，且拿不到 Ex 的全局名，只能**在检测到 `global.KAGParser` 被替换的那一帧
   子类化当前值并写回**（这也是避开引导期崩溃的唯一时机）。
5. **探针输出通道**：`Debug.message` 的输出在 logcat 里是 tag
   `cocos2d-x debug info`（完整文本、不受 Label 钩子 CJK 过滤影响），
   所以纯 ASCII 探针照样可读；而 `NativeBridge: krkr text raw` 只截前 24 字符，
   不适合读长行。
6. **可回滚性要实测**：关闭 `kr_extract_probe` 后 overlay 不再生成
   （`KRKR patch overlay disabled target=null overlay=null`）、控制台恢复 632 行基线；
   探针注入时 overlay 会把游戏自己的 patch.tjs 内容作为 base 一起写回
   （`baseBytes` 含 `cleanup.bytes`），不要让它把游戏 patch 整份替换掉。

## 实测踩坑（2026-10-08 会话四十五之三：TJS 里怎么「包装」一个插件解析器类）

针对 tsukikage（KAGParserEx）实测出的可用/不可用手段（脚本标识
`TYRANOR_NEXT_KRKR_EXTRACT_WRAP_V*`，脚本在 `EngineLauncher.krExtractProbeScript()`）：

1. **TJS2 不允许在函数体内声明类**：`function f() { class X extends KAGParser {} }`
   在运行期抛异常（不是语法错误，编译能过）→「第 1 帧临时声明子类」这条路直接封死，
   必须在顶层声明。
2. **顶层子类可以合法声明，但它绑定的是声明时刻的基类**（patch.tjs 阶段 = 换类前的
   旧 KAGParser）。所以正确做法 = 顶层声明 + **第 1 帧写回** `global.KAGParser`
   （实测 `assignClass=ok`，游戏正常运行、对白照常显示）。
3. **换后的类方法可读可写**：`global.KAGParser.getNextTag` 能读出函数对象，也能被
   赋成自己的函数（实测 `readMethod=ok` / `writeMethod=ok`），替换后的函数**会以
   实例为 `this` 被调用**。
4. **但「调用原方法」四种约定全失败**（`&(orig incontextof this)`、`orig.call(this)`、
   裸 `orig()`、临时把类槽还原成原方法再 `this.getNextTag()`）——所以「方法级包装 +
   转发」在 TJS2 层做不到；能做的是**整类替换**（子类用 `super.getNextTag()` 转发，
   `super` 只在类体内可用）。
5. **异常转储会冲爆 logcat**：被替换的方法里一旦每次调用都抛，TJS 每次抛都打整套
   反汇编（单次约 2 万行），16 MB ring buffer 里的探针标记会被挤掉——排查时要
   在失败后**立刻停手**（`__tnAllFailed` 守卫）并在洪水前抓一份日志。
6. **换类后必须验证游戏本体**：本轮包装成功后游戏照常出对白
   （「俺こと卯月誠一が交わした、大切な契約の言葉。」），说明该游戏即使退化成
   普通 KAGParser 语义也能跑。**但「收到文本」的归属当时判错了**：`のものもの`
   等文本其实来自字形通道（见下一节第 1、2 条），包装类**从未被实例化**——
   「游戏能跑」不等于「包装生效」。

## 实测踩坑（2026-10-08 会话四十六新增：通道归属判定与字形通道过滤）

1. **`NativeBridge.onKrkrText` 对 `[FTLN]`/`[FTRAW]`/`[TNEXT]` 打同一条
   `krkr text:` 日志** → 只看这条日志**无法判断文本来自哪条通道**。凡是「某通道是否
   生效」的结论都必须用**该通道独有的上游证据**（如解析器通道要有 `[TNEXT]` 原文行、
   或包装类自己的构造/调用计数），否则会把字形通道的输出误记成解析器通道的成果
   （第一轮 §6.5.7 的误判即由此产生）。
2. **判定「包装类是否真的参与运行」的最小手段**：在类里加
   `function TnClass() { ...计数... }` 构造函数 + 在被覆盖方法入口加计数。
   实测 tsukikage：`parserChange=1`（第 1 帧换过一次后再无人动 `global.KAGParser`）、
   `wrapCtor=0`、`gt=0` —— 游戏**根本不从 `global.KAGParser` 构造解析器**，
   包装类永远不参与运行。**「赋值成功」≠「被使用」**。
3. **字形通道只能看到「字体图集未命中」的字形**：对白打字机在 Label/图集命中的情况下
   `FT_Get_Char_Index` / `FT_Load_Glyph` 计数会长时间纹丝不动
   （实测 `idx_calls=202 glyph_calls=202` 冻结一分钟），所以该通道
   **天生只能看到新绘制的文本（选项按钮、装饰、开头几笔）**，与「哪段是正文」无关。
4. **过滤设计：冻结判定不能参与上行门控**。首版按方案写「派生结果连续 N 次相同 →
   判定冻结 → 停发」，实测把正常对白全杀掉（kazurauta 10 条 → 0 条 `ft line`），
   因为打字机停顿时**派生结果与 raw 都不变**，而这正是该上行的时刻。
   正确分工：防重复交给 `gPage != gLastEmitted` + 同页去重集合；
   「冻结」只作为诊断标记（`glyph-frozen`）。
5. **堆叠串判定（`きさらぎさらぎさらぎさらぎ…`）的可行启发式**：串首同一
   1–3 码点单元连续重复 ≥3 次即判堆叠。阈值取 3 而不是 2，是为了不误伤正文里的
   叠词/重复短语（「ああ、そうか」「そうかそうか」）；代价是
   `のものもの` 这类「2.5 次重复」的碎片仍可能漏过（实测 tsukikage 过滤后剩 1 条）。
6. **磁盘**：`D:` 盘曾只剩几百 KB，Gradle 报 `Failed to store cache entry ...
   磁盘空间不足`。用 `--no-build-cache` 可绕过缓存写盘；临时产物
   （`.tmp/perf/*.db`、`.tmp/dexcheck/*.dex`、旧截图）可再生，是首选清理对象。
   另注：本会话中途 `.tmp` / `.tmp-test` 两个 scratch 目录**被外部清空过一次**，
   依赖 scratch 目录留证据的步骤要尽快把结论写进 docs。

## 实测踩坑（2026-10-08 会话四十七新增：TrackReader 参考代码怎么在本机跑起来）

1. **参考包是「`.ts` 后缀 + 纯 JS/JSDoc」**（`packages/domain/src/yomitan/**` 里没有
   任何 TS 语法），但内部 import 写的是 `./transforms.js` 这类 **`.js` 说明符**，
   而磁盘上是 `.ts` 文件：`node` 直接跑（含 `--experimental-strip-types`）会报
   `ERR_MODULE_NOT_FOUND: .../transforms.js`。**正解 = 用参考仓自带的 esbuild 打包**：
   `node_modules\.bin\esbuild <脚本>.mts --bundle --platform=node --format=esm --outfile=x.mjs`
   （esbuild 会把 `./x.js` 重写解析到 `./x.ts`），再 `node x.mjs`。临时脚本放参考仓根
   目录用相对路径导入最省事，跑完记得删。
2. **五语言规则 JSON 的正确核对方式是语义 diff**，不是比字节：用
   `.tmp/diff-deinflect.mjs` 比对 conditions 键集合与逐条内容、规则组集合与逐条规则、
   preprocessors；现网 assets 与参考导出**逐字段一致**（ja 439/en 40/ko 124/zh 0/vi 0，
   conditions ja 22/en 5/ko 6/zh 1/vi 1）。注意 `conditions` 是**对象映射**不是数组
   （按数组统计会得到 `undefined`）。
3. **词形还原的对照验证可以完全离线跑**：Java 侧用
   `javac -cp <android.jar> OnsDeinflector.java Probe.java` → `d8 --min-api 30 --output <已存在目录>`
   → `adb push` dex + JSON → `adb shell "CLASSPATH=... app_process /system/bin Probe /data/local/tmp"`，
   设备提供真实 `org.json` / `android.util.Log`。为了让 harness 能喂 JSON，
   `OnsDeinflector` 新增了 `initFromJson(Map<String,String>)`（`init(Context)` 变成
   读 assets 再调它）——**引擎类要留一个「纯数据入口」**，否则只能在设备 UI 上验证。
4. **抓设备/Node 输出必须用 `cmd /c "... > file"`**：PowerShell 的 `>` 会写 UTF-16LE，
   `Out-File -Encoding utf8` 会带 BOM，两侧一对比就全是乱码/空行（本轮先踩了一次，
   15 个用例全报 DIFF）。`adb shell` 的 stdout 也要走 `cmd /c` 重定向才是原始 UTF-8。
5. **参考的 `deinflectionPath` 就是 `deinflect` 结果的 `conditions`**（终止条件数组，通常
   单元素），`trace` 选项只有声明没实现；本项目在 `Result` 上补了真正的条件链 `path`
   （不参与去重键），面板/制卡才有 `A→B` 可显示。
6. **`preprocess` 是按名字驱动的**：JSON 只导出 `preprocessors: [名称]`，实现必须按名
   查表；未知名要**保留原文**（对齐参考 try/catch 兜底），否则以后加语言会静默丢变体。
   zh 的「繁体→简体」实际只做 NFKC（参考注释名与实现不符）。

## 实测踩坑（2026-10-08 会话四十八新增：词典查询语言设置 M4）

1. **「每游戏覆盖」在引擎子进程怎么落地**：面板在 `:kirikiri2`/`:ons` 等子进程，
   读不到 app 模块的 `PerGameSettingsStore`（模块方向 app → engine，不能反向依赖）。
   可行做法 = app 侧 `EngineSettingsResolver` 先合并出生效值，`EngineLauncher.buildIntent`
   把它写成 **intent extra**（`LaunchContract.DICT_QUERY_LANG` / `DICT_READING`），
   面板在构造函数里 `activity.getIntent().getStringExtra(...)` 读取。
   **只有「全局唯一、不含每游戏覆盖」的设置**（如 `dict_han_priority`）才走
   `getSharedPreferences(EnginePrefs.APP_PREFS, MODE_MULTI_PROCESS)` 直读。
2. **SQLite 加列后必须先探列再 `ALTER`**：`dicts` 表加 `scope` 用
   `PRAGMA table_info(dicts)` 判存在性（与 `entries.dict_id` 同一套路），
   否则每次启动都会写一次 schema 事务；**列默认值必须是「安全」值**——
   scope 默认 `universal`，配 `buildScope` 里 `scope='universal' OR scope=?` 的条件，
   保证未标注词典在任何查询语言下都不会突然查不到。
3. **查询范围（scope）缓存要带语言维度**：`buildScope` 的 3s TTL 缓存原先只按时间失效，
   加了查询语言后必须把语言一起入键（`scopeCacheLang`），否则切语言后仍用旧范围。
4. **改「读音查询门控」是行为变更，不只是开关**：原先「候选含假名才查 reading」是
   B2 的性能收窄；M4 对齐参考后改成「日语即查（长度>1）」，**每层扫描的 reading 查询
   次数会上升**——好在有 B1 的 `(reading, dict_id)` 索引 + B3 批量 IN（同一句 5.9ms 级），
   但改这块时要意识到这是**命中率与耗时的取舍**，别只看功能对齐。
5. **设备抓 UI 截图前先唤醒**：真机 MIUI 息屏很快，`adb shell input tap` 落在黑屏上会
   **静默无效**，而 `screencap` 会返回一张全黑或与上一张完全相同的图（可直接用文件大小/
   sha 判断「这一帧没动」）。稳妥做法：一条命令里先 `input keyevent 224` +
   `input swipe 540 2000 540 700 200` 唤醒解锁，再点，再截图。

## 实测踩坑（2026-10-08 会话四十九新增：语言包 M2 与弹窗布局）

1. **弹窗内容一多就会被裁掉，且不会自动可滚动**：`AppAlertDialog` 的 `text` 槽里如果
   只放一个普通 `Column`，超出高度后**下面的内容直接不可见也无法滚动**（本轮把语言包
   卡片插到词典列表上方后就复现了）。修法：给该 `Column` 加
   `Modifier.verticalScroll(rememberScrollState())`，并把同轴嵌套的 `LazyColumn`
   改成 `forEach`——**同方向嵌套滚动组件会直接抛异常**（infinite max height）。
2. **可测试性钩子要提前留**：`DeinflectPackStore` 一开始只有 `init(Context)`，
   harness 没法在 app_process 里造 Context；补一个 `initAt(path)`（内部就是
   `SQLiteDatabase.openOrCreateDatabase(File)`）后，语言包语义就能在真机上跑
   33 条断言。**凡是「纯数据 + SQLite」的引擎组件都值得留这种入口**。
3. **importTablePack 不做去重**：参考实现的去重发生在 parse/build 阶段
   （`build-language-pack.mts` 与 `parse.ts`），store 只负责写。别在 store 里
   自作主张去重/清洗，否则与参考语义分叉、断言会对不上（本轮先按参考写，测试
   期望值改成 `[gehen, gehen]` 才对）。
4. **导入器的形态要按「能不能 parse 成单个 JSON」分流**：JSONL 的第一行往往是
   `{"lang":..}`，若用 `text.startsWith("{")` 就断定是单对象，`new JSONObject(整段)`
   会抛异常并**整份导入失败**；正确做法是 try 单对象 → 失败再逐行解析，且逐行容错
   （单行非法跳过）。
5. **每个断言组都要能独立失败**：harness 里一次未捕获异常会把后面所有用例跳过
   （本轮 p2 用例写错就导致后面 6 条直接没跑，只看到 "Killed"）。分组 try/catch +
   末尾打印 `PASS=n FAIL=n`，才能一眼看出「跑完了没有」。

## 实测踩坑（2026-10-08 会话五十一新增：语言分派、变体兜底与布局）

1. **半角浊点写成 `\uFF8E` 是个真 bug**：半角浊点「ﾞ」是 **U+FF9E**、半浊点「ﾟ」是
   **U+FF9F**；旧预处理实现判的是 `\uFF8E`（另一个字符），于是 `ﾂﾂﾞｸ` 永远变不成
   `ツヅク→つづく`，半角卡那文本永远查不到词。**文本处理的字符常量必须回查码表**，
   这类 bug 不会报错、只会静默不命中（本轮靠 `ﾂﾂﾞｸ → つづく` 的断言抓出来）。
2. **「参考语义」和「参考注释」要分开信**：`japanese-util.ts` 的 `isCodePointKana`
   只认平假名/片假名两段（不含半角カナ FF66-FF9F、不含片假名拡張 31F0-31FF）。
   我按「常识」补了范围，结果与参考 12 个码点里差 3 个。**移植纯函数要逐例对照参考**；
   项目确实需要更宽的范围时（面板递减扫描会拿到半角假名），**另开一个函数**
   （`isCodePointKanaExtended`）并注明是项目扩展，别改参考函数。
3. **规则表为空的语言（zh/vi）无法靠「还原候选」命中预处理变体**：参考
   `deinflectWithPreprocess` 会跳过 `term === rawSource && score === 0` 的自匹配，
   zh 又没有 transforms，所以「ＡＢＣ→ABC」这类规范化变体到不了查词层（参考也一样）。
   计划验收要求「中文模式下全角/NFKC 变体可命中」，做法是**在查询层补一次字面变体兜底**
   （⑥，放在规则还原之后），且变体也要走一次读音查询（否则 `ﾂﾂﾞｸ → つづく → 続く` 会漏）。
4. **词典 scope 的过滤语义要分两半测**：「universal 的词典在任何查询语言下都必须可见」
   与「scope=ja 的词典在 ko 查询下必须不可见」是两条独立断言——只测后者会掩盖
   「按语言整个库消失」的回归（方案里把前者写成硬约束正是因为这个）。
5. **弹窗里别套卡片**：`DictRow` 自身是 `MiuixCard`，把词典列表再包进一层「已导入词典」
   卡片就是卡中卡——白色主题下没有明显边框，只会表现为**一大团空白 + 层级错乱**。
   列表段落用**纯文本小标题**（不套卡片），单条内容各自成卡；固定卡片用 `verticalScroll`，
   同轴嵌套的 `LazyColumn` 要改成 `forEach`（否则抛 infinite max height）。
6. **二进制取设备文件必须 `adb exec-out`**：`adb shell run-as … cat db > file` 会被
   `adb shell` 做换行转换，得到的 SQLite 文件 `database disk image is malformed`；
   换 `adb exec-out` 后 66545 条词条完整可读。**数据库/图片一律 exec-out**。

## 实测踩坑（2026-10-08 会话五十三新增：直接制卡入口与制卡验证）

1. **`adb shell input keyevent 111`（ESC）会取消 AlertDialog**：用它「收起软键盘」时把
   弹窗一起关掉了，后面的点击落到底层页面上，于是「看起来点了制卡其实没点」。
   要收键盘就用 `input keyevent 4`（同样是 back，但 AlertDialog 也会取消）——
   **正确做法是不要收键盘**：AlertDialog 的按钮在键盘上方仍可点，直接点按钮即可。
2. **制卡成功的验证要看 AnkiDroid 侧，不要只看 toast**：toast 只闪 ~2s（`screencap`
   常抓不到）。可靠证据＝打开 AnkiDroid → 左侧抽屉「卡片浏览器」→ 搜索关键字 →
   点开「卡片信息」，可看到 Sort Field / Deck / **Note Type** / Created 时间；
   或用抽屉里的牌组列表看 New 计数变化。
3. **测试制卡会污染用户牌组，测完要删**：浏览器里长按首行进入多选 → 勾选其余行 →
   ⋮ → 「删除笔记」；删除后牌组标题会显示「共有 N 张卡片」。本轮顺手清理了会话五十
   在游戏面板上误点产生的一张词卡。

## 实测踩坑（2026-10-08 会话五十四新增：别把默认值做成隐式预设）

1. **`getString(key, DEFAULT_X)` 形式的默认值就是隐式预设**：界面会显示一个用户从未
   选择过的名字，用户点「确定」后它就被真正写进配置，之后制卡全落到那个牌组/模板上。
   **凡是「必须由用户从外部数据里选」的设置（牌组/模板/词典 scope…），未配置必须返回
   空串并让 UI 显示「请选择」**，不能给具体名字。
2. **「取或建」函数会把预设变成用户数据**：`getOrCreateWordModel("TyranorNext Word")`
   之类的兜底会在用户的 AnkiDroid 里静默创建同名模板/牌组——比 UI 上显示预设名更糟。
   改成 `findXxx`（只查不建），并让调用方在未配置/已不存在时给明确提示。
3. **空选择的语义要明确**：下拉首项「请选择…」= 清除配置（写空串），而不是「保持旧值」；
   数据源读取失败（AnkiDroid 未装/未授权）时才保持旧值不动，避免把配置写成脏值。
4. **模板换了要剔除失效映射**：字段映射保存时只保留「当前模板真实存在的字段」，
   否则换模板后旧字段名会留在配置里、制卡时被静默忽略。

## 实测踩坑（2026-10-08 会话五十五新增：要素名必须照抄参考）

1. **制卡要素 key/标签是「契约」，不能自造**：参考的表在 TrackReader
   `packages/domain/src/anki/field-mapper.ts` 的 `WORD_FIELDS`（web game text 只是
   re-export）。正确 key 是 `word/reading/definition/audio/sentence/translation/
   sentenceAudio/source/wordTags/deinflection/image…`；我们之前自造的
   `meaning`/`screenshot`/`sentence_audio`/`word_audio`/`word_tags` 以及凭方案文档加的
   `page`/`match_type` 都不在表里。**加要素前先去参考表里找 key，找不到就不要加。**
2. **改 key 要给老配置留迁移**：读取 `field_map` 时把旧 key 映射到新 key
   （meaning→definition、screenshot→image、sentence_audio→sentenceAudio、
   word_audio→audio、word_tags→wordTags），并丢弃已废弃的 page/match_type；
   否则用户原来的映射会静默失效。
3. **默认映射也照参考**：参考 settings 的初值是
   `{ word: 'Front', reading: '', definition: 'Back', sentence: '' }`——默认只映射
   word→Front、definition→Back，别自己编一套 "Word/Reading/Meaning/Sentence"。
4. **同一套要素要贯穿所有制卡入口**：词卡与句卡都按 `field_map` 装配字段；
   句卡此前把「截图+语音+整页」硬塞进前两个字段，既不跟映射走、也引用了不存在的 page 要素。

## 实测踩坑（2026-10-08 会话五十六新增：设置页改独立页）

1. **`getWindow().getInsetsController()` 不能在 `setContentView` 之前调**：decor view 还没建，
   `PhoneWindow.getInsetsController()` 直接 NPE 把 Activity 崩掉（本轮踩了一次，logcat 里
   是 `NullPointerException ... DecorView.getWindowInsetsController() on a null object reference`）。
   状态栏/导航栏着色统一放到 `setContentView` 之后，并 try/catch 兜底。
2. **弹窗改独立页不要重写 UI**：把弹窗的「内容构建」与「保存动作」拆成
   `buildXxxPage(...)` 返回 `{content, titleRes, save}`，弹窗与独立页共用同一份实现——
   行为完全一致、改动面最小；「点了就生效」的页（翻译设置）用 `hasSave=false` 隐藏保存按钮，
   避免出现一个按了没反应的按钮。
3. **设置页入口形态与其它入口保持一致**：引擎/云同步/MCP 等本来都是独立 Activity，
   翻译/Anki/TTS 之前是悬浮弹窗，风格割裂；新增 Activity 后要与之一致地设置
   浅色状态栏与导航栏底色。

## 实测踩坑（2026-10-08 会话五十七新增：下拉选择控件）

1. **Miuix 的 `OverlayDropdownPreference` 在 Compose Dialog 里点不开**：它依赖自己的浮层宿主，
   放进 `AppAlertDialog`（Compose Dialog）后点击无任何反应（连续两次点击截图完全相同，可据此
   判定「没弹出来」）。**弹窗内的下拉要用 Material3 `ExposedDropdownMenuBox`**（同一窗口内的
   Popup）；Miuix 那个只在普通页面（设置页）可用。
2. **`Modifier.menuAnchor()` 需要类型参数**：Compose BOM 2026.05 对应的 Material3 里必须写
   `menuAnchor(MenuAnchorType.PrimaryNotEditable)`，只读下拉否则编译不过。
3. **设置控件要有统一层**：循环选择散落在各弹窗里，改起来容易漏。engine 侧新增
   `SettingsWidgets.dropdownRow/switchRow`，之后所有设置行都走它；
   **改完记得删掉旧的循环辅助函数**（`nextStep/nextCode/cycleIndex`），否则很容易又被拿去写循环。
4. **Spinner 首次布局也会回调 `onItemSelected`**：回调里若直接写配置，会在打开页面时
   重写一遍当前值（同值，无害），但不要在回调里做重活（如重建整页）。
5. **工具栏快捷键与设置行是两回事**：右缘面板的「透明度」按钮点按循环属于快捷操作，
   保留循环；但设置弹窗里的同名项必须是下拉（否则用户找不到「选一个值」的入口）。

## 硬性约定（2026-10-08 用户要求，必须遵守）：二级设置页风格一致性

**凡是新增/改造的二级设置页（设置页点进去的独立页），外观与结构必须与既有页面一致**：

1. **壳**：Activity 必须继承 `AppScreenActivity` 并调用 `setAppScreenContent { ... }`
   （统一处理 edge-to-edge、系统栏样式、`finish` 转场、`AppScreenScaffold` 主题包裹）。
   **不要**自己在 Activity 里搭 `LinearLayout` + 顶栏，也不要只为了「能显示」而用裸 View。
2. **正文结构**：
   ```
   MiuixSettingsTheme {
     MiuixScaffold(containerColor = Transparent, contentWindowInsets = WindowInsets(0.dp),
                   topBar = { AppTopBar(title = ..., background = Transparent,
                                        contentColor = MiuixTheme.colorScheme.onBackground) }) { inner ->
       LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)
                    .padding(top = inner.calculateTopPadding()),
                  contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp),
                  verticalArrangement = Arrangement.spacedBy(12.dp)) {
         item { MiuixCard(Modifier.fillMaxWidth().glassShadow().glassBorder(),
                          cornerRadius = AppComponentCornerRadius) { Column(Modifier.padding(vertical = 4.dp)) { ... } } }
         item { BottomInsetSpacer() }
       }
     }
   }
   ```
3. **行控件只用 Miuix preference**：`OverlayDropdownPreference`（下拉）、`SwitchPreference`
   （开关）、`ArrowPreference`（跳转/弹窗入口）；文本输入沿用既有页面的
   Material3 `OutlinedTextField`。**新设置项一律下拉/开关，不要写「点按循环」的文字行。**
4. **复用入口**：`app/.../ui/settings/EngineSettingsScreens.kt` 提供
   `EngineSettingsPage/SettingsCard/SettingsHint` 三个壳组件 + 三个页面正文
   （`TranslateSettingsScreen` / `TtsSettingsScreen` / `AnkiCardSettingsScreen`），
   新增同类页面直接照它写。
5. **反例（不要再犯）**：会话五十六曾把翻译/Anki/TTS 页做成「engine 侧 Activity +
   自绘顶栏 + 纯 Android View 内容」，与其它设置页风格完全割裂，随后被要求重做——
   引擎侧的 `buildXxxPage`（View 版本）只保留给**游戏内弹窗**使用，设置页一律走 Compose。

## 实测踩坑（2026-10-08 会话五十九新增：制卡媒体的正确落地方式）

1. **Android 11+ 给 AnkiDroid 传媒体必须用 `content://` + 显式授权**：
   `AddContentApi.addMediaFromUri(Uri.fromFile(...))` 在 Android 11+ **必然失败**——
   AnkiDroid 进程读不到我们 `Android/data/<pkg>/files/...` 的 `file://`（scoped storage）。
   正确做法：`FileProvider.getUriForFile(ctx, "${packageName}.fileprovider", file)`
   （宿主清单里 `file_paths.xml` 要包含对应目录，如 `external-files-path extract/`）+
   `context.grantUriPermission("com.ichi2.anki", uri, FLAG_GRANT_READ_URI_PERMISSION)`，
   再交给 `addMediaFromUri`。
2. **AnkiDroid 的媒体目录是 scoped 路径**：
   `/sdcard/Android/data/com.ichi2.anki/files/AnkiDroid/collection.media`
   （`/sdcard/AnkiDroid/collection.media` 是 2.16 之前的旧位置，**现在不是它的媒体库**）。
   第三方应用也**写不进**这两个目录（Android 11+）——所以任何「直拷」兜底都只会
   **假装成功**：字段里写上 `<img src=x>`，AnkiDroid 却找不到文件，卡片缺图且无从排查。
   **失败就返回失败**，并给用户明确提示。
3. **媒体导入每一步都要记日志**：本轮靠 `addMedia(content) ok:` / `addMedia(file) 返回空`
   与两边目录 `ls` 的对照才定位到「写进了废目录」。日志要含：走了哪条路径、返回的标记、
   文件字节数、目标目录内容。
4. **验证「卡片有图」要看 AnkiDroid 侧的媒体目录**（`adb shell ls .../collection.media`），
   不要只看字段里的 `<img>` 标记或 toast——标记存在 ≠ 文件存在。
5. 外部塞进媒体目录的文件，AnkiDroid 下次打开（或「检查媒体」）才会收录——修复用户已有
   卡片时可以直接 `adb push` 到该目录并让用户重开 AnkiDroid。

## 硬性约定（2026-10-09 用户要求）：媒体与存档分家

1. **图片、音频绝不放进「存档管理」**：截图的长期存放地是「截图管理」页
   （`getExternalFilesDir/screenshots/<游戏>/`），音频**不保留**（只用于当次播放/制卡）。
   往 `GameSaveManager.excludeFor` 里加媒体扩展名过滤时**不要包含 `.bmp`**——
   ONS 的 `save*.bmp` 是存档缩略图，属于存档本体。
2. **截图入口放在游戏「条目选项」**（`GameScreen` 的 DrawerAction：存档管理 → 截图管理 →
   引擎设置），传 `game.title` 给 `ScreenshotManagerActivity` 只看该游戏；
   不要再把截图管理塞进存档页或做成孤立页面（该页曾长期没有入口）。
3. **媒体文件即用即删**：制卡截图/语音交给 AnkiDroid 复制进 `collection.media` 后，
   本地 `extract/anki_*` 立即删除；`GameMediaCleanup` 在应用启动与面板安装时兜底清理
   旧版本残留（`cacheDir/ons_extract_history`、`extract/anki_*`）。
4. **历史回放的语音不再落盘**：走「档案按名补读（`readVoiceBytesByName`）→ TTS 朗读」，
   不要为了回放方便把语音缓存到磁盘（用户明确要求音频不保留）。
5. **Kotlin `object` 的方法给 Java 调用必须加 `@JvmStatic`**（`PanelActionBindings` 首版漏了
   `matches/codes`，Java 侧报「无法从静态上下文引用非静态方法」；`const val` 常量本身没问题）。
6. **Artemis 的按键本来完全到不了面板**：NativeActivity 直读输入，按键只到持焦窗口。
   要让面板收到键，必须给面板窗包一层键捕获外壳并**在面板展开时把该窗设为可聚焦**
   （去掉 `FLAG_NOT_FOCUSABLE`），光标窗同时让出焦点，`setKeepNativeFocused(true)` 保渲染；
   收起时全部还原。调用 `setKeepNativeFocused` 要 try/catch（官方内核可能没有该符号）。
7. **脚本化真机验证的边界**：`input tap` 会被 MIUI 通知栏下拉、SAF（DocumentsUI）选择器抢走焦点，
   且 SAF 选择器的「保存」按钮**不接受注入点击**；游戏启动也常因焦点问题失手。凡涉及
   「选择器保存 / 长按 / 手柄实体键」的验收，要么如实标注未完成，要么请人工确认，不要反复重试烧时间。
8. **起游戏做真机验证不要靠点击**：用导出的快捷方式蹦床直接起——
   `adb shell am start -n com.tyranor.next/com.tyranor.next.ui.game.GameShortcutActivity --es extra_shortcut_id game_<sha256(游戏uri)>`
   （`GameShortcutManager.shortcutId` = `"game_" + sha256(uri)`；uri 用 `adb exec-out run-as … cat databases/game_library.db` 拉库后查）。
   这条路径稳、可复现，是后续所有游戏内验收的首选入口。
9. **Artemis 的输入是 NativeActivity 直读**（2026-10-09 修正后的准确结论）：
   - **触摸模式下**（我们的窗 NOT_FOCUSABLE、焦点在 native 窗）按键确实到不了 Java；
   - **但只要让我们的窗口持焦**（虚拟鼠标模式的 `mouseKeyCatcher`、或面板键捕获窗），
     手柄事件**能进 Java**——实测 `mouse catcher key code=20/96 source=0x401`（DPAD_DOWN/BUTTON_A，
     手柄源 0x401）。我最初判定「完全不经 Java」是**测量错误**：那条路径当时没有日志，
     `virtualMouse.handleKey` 在 Artemis 分支也不打日志，于是「没日志」被我误读成「没事件」。
   - 因此「软件接收 → 转换 → 回送内核」在**按键**上是可行的：`routeMouseKey` 里未消费的键
     已经 `super.dispatchKeyEvent(mapped)` 走内核的 `EmulateKeyEvent` 映射链回送游戏；
   - **触摸/点击**仍需要内核侧入口（clean 内核的 `InjectHostTouch`，或钩子捕获的
     `CArtemisTouch`）——Android 不允许普通应用把事件塞进别人的/native 的输入队列。
   - 该设备上 7 个 Artemis 内核 .so **都已安装**（`files/engine_plugins/artemis/current/arm64-v8a/`，
     含 `libartemis-clean.so`），manifest 记 `pluginVersion: 32`，所以切内核是即时可用的。
   教训：**加日志再做结论**；没有日志的路径不能当作"没有事件"。
10. **注入手柄事件用 `adb shell input gamepad keyevent <code>`**（`input` 支持
   dpad/keyboard/mouse/gamepad/joystick… 源前缀）；排查「手柄键到底进了谁」时，键盘源与手柄源
   各发一次对比，能区分「事件没到」与「到了但没人处理」。
   **别记错 keyCode**：`KEYCODE_BUTTON_L1/R1/L2/R2 = 102/103/104/105`，`A/B/X/Y = 96/97/99/100`，
   `START = 108`；我一度用 132 当 R2 测了半天没反应。
11. **面板功能键的放置顺序**：像「开关虚拟鼠标」这种**面板收起时也要能用**的快捷键，必须在
   `handleKey` 里**先于 `if (!expanded)` 判断**处理；放进展开态分支里会导致「不开面板切不了」。
12. **十字键可能只报 HAT 轴、不报按键**（用户实测「A 能点击但方向键不动」的根因）：
   手柄 D-pad 在部分设备/内核上只产生 `AXIS_HAT_X/Y` 的 **generic motion 事件**。
   凡是「按键式」处理方向键的地方都要**同时处理 HAT 轴**：
   - `EngineVirtualMouse.handleMotion`：HAT → 固定步长移动光标（12dp/120ms 去抖）；
   - `OnsExtractPanel.handleMotion`：面板展开时 HAT → 与 D-pad 按键同一套语义（选词/滚动）。
   排查入口：`DiagLog` 里 `vmouse motion source=0x… X=… HAT_X=…`（限频 1s）。
13. **模式分流约定（三宿主统一顺序：面板 → 虚拟鼠标 → 游戏）**：面板展开时方向键/确认归面板；
   面板收起且虚拟鼠标模式开 → 方向键移动光标、确认点击；面板收起且触摸模式 → 全透传游戏；
   不冲突的面板快捷键（翻译/制卡/复制/播放/历史/模式切换）两种模式下都生效。
14. **「开关类」快捷键必须过滤重复事件**（用户反馈「切换虚拟鼠标不稳定」的根因，2026-10-09）：
   长按会持续下发 `ACTION_DOWN + repeatCount>0`，部分手柄/多设备还会重复下发首次 DOWN；
   只判 `ACTION_DOWN` 就会来回狂切。正确写法：`repeatCount == 0` **且** 加 350ms 去抖
   （`debounceToggle`），抬起不触发。面板呼出键（RB）同样是开关，已加同等保护；
   真机验证：`input keyevent --longpress 105`（R2 长按）→ 恰好 1 次切换。
   另外：部分手柄 L2/R2 **只报 `AXIS_LTRIGGER/AXIS_RTRIGGER`**、不报按键——绑定到这两个键的
   开关动作要在 `handleMotion` 里把扳机轴当按键处理（阈值 0.5 + 锁存/重新武装），否则「有时灵有时不灵」。
15. **虚拟鼠标输入核心已抽出**：`com.core.engine.VirtualMouseInput`（按键五动作 + 长按重复 +
   HAT 轴长按重复 + 摇杆死区 + 模拟器旋转补偿 + 轴诊断），宿主只实现 `Target`
   （isCursorVisible/ensureCursor/moveBy/click/requestRender）。Tyrano/RPGM 的 WebView 光标层
   已接它；KRKR/ONS/Artemis 仍用各自的 `EngineVirtualMouse`（同语义，已打同一批修复），
   要彻底统一需单独迁移提交 + 三引擎回归。
16. **逐段出现的对白不能"来一段念一段"**（用户反馈 Tyrano TTS 重复播放，2026-10-09）：
   Tyrano 这类引擎的对白是**分次更新**的（先 `AAAA`，随后变成 `AAAA BBBB`），旧实现「文本一变就念」
   → 先念 AAAA、整句到位后再念一遍 AAAA BBBB。两层修复（`OnsExtractPanel.autoPlayForNewSentence`）：
   - **稳定窗口**：每次文本变化都重新计时，静止 400ms（若还在增长则 650ms）才朗读
     → 打字机/快速增量只念一次整句；
   - **前缀增量补念**：文本以上次**已念**内容为前缀时，只念新增部分
     （`auto speak append(len)`），绝不重念整句 → 晚到的续写也不会重复。
   诊断日志：`auto speak(len): …` / `auto speak append(len): …`（`adb logcat | grep "auto speak"`）。
   注意：`speakSentence()` 念的是 facade 当前句，补念增量要用 `speakText(suffix)`。
：`com.core.engine.VirtualMouseInput`（按键五动作 + 长按重复 +
   HAT 轴长按重复 + 摇杆死区 + 模拟器旋转补偿 + 轴诊断），宿主只实现 `Target`
   （isCursorVisible/ensureCursor/moveBy/click/requestRender）。Tyrano/RPGM 的 WebView 光标层
   已接它；KRKR/ONS/Artemis 仍用各自的 `EngineVirtualMouse`（同语义，已打同一批修复），
   要彻底统一需单独迁移提交 + 三引擎回归。
5. **「存档目录 = 游戏根」的引擎必须用白名单，不能只排资源包**（2026-10-09 修复）：
   Artemis 游戏根里除了本体的 `root.pfs/movie/system`，还有**我们自己写进去的**
   `screenshots/`、`extract/`、`artemis_extract/voice/`（提取面板与内核），
   旧实现只排除 pfs/arc 之类资源包名 ⇒ 导出 ZIP 把截图与语音一起打包（用户实测 18 MB 包
   里 19/22 个条目是媒体）。正确做法：**只认根级 `*.dat`**（目录名不以 .dat 结尾，
   递归自然不会进子目录）；AVG32/UK2 同理（`save.ini` / `FLAGnn.dat` 白名单）。
6. **安全兜底**：`resolveSaveLocation` 里除 AVG32/UK2/ARTEMIS 外，任何引擎解析出的存档目录
   若等于游戏根，直接判「不可管理」（`save_error_save_dir_is_game_root`）。
   排查这类问题时，**先解开用户手上的导出 ZIP 看条目**——一眼就能看出谁把什么打了进去。


17. **条目「调试信息」**（2026-10-10）：`GameRunRecord`（game_run_record prefs，按 uri 记录
   engine/target/file/patch/at/result/detail）+ `GameDebugInfoActivity`（最近一次运行 + DiagLog.readTail(400)
   + filesDir/crash 最新文件，可复制/分享）；`EngineLauncher.launch` 成功/失败都留痕。
   排查启动失败/白屏先看这里，再看 logcat 里引擎自己的 tag trace。
18. **Artemis 引擎的脚本停滞（The Shell Part I 白屏）**：点 Start 后脚本 `call estag04` 创建
   `lyc {id=startmask, file=:bg/white}` 白蒙版 → trans/wait 走完后**再无任何 tag trace**，
   渲染循环仍在跑（draw 只剩 bg+startmask），二次点击只到 `click: fallback -> onpush key 1`。
   无视频请求、无音频解码报错。怀疑点：脚本此前注册了 `setonsoundfinish {id=4, function=sesys_voiceend}`，
   而我们的 `artemis_audio_bridge.cpp` 里**没有**任何 finish/完成回调实现 → 等声音结束的回调不来，
   脚本永久停住（白屏）。下一步：给音频桥补"播放结束"上报，或先验证 sesys_voiceend 是否被调用。
19. **The Shell Part I 白屏定位（2026-10-09/10，Artemis）**：点 Start 后脚本走
   `call estag04`(system/script.asb) → `lyc {id=startmask, file=:bg/white}` 白幕 → trans →
   `wait {input=1,time=1000}` → `wait {input=0,time=0}` **然后彻底静止**（渲染循环仍在跑）。
   证据链：
   - 游戏是**解包版**（目录名带 (files)），`system/script.asb` 可直接读，尾部 token 显示这段
     逻辑属于影片流程：`movie_play` / `movie_autoskip` / `call file=system/first.iet
     label=movie_emergendcy` / `movie_play_exit` / 错误弹窗「ファイルの終端に到達しました」；
     `movie/` 目录里只有 `op1.dat`（开场影片）。
   - 官方内核 `libartemis.so` 里**有** `PlayVideo` 与 `OnFinishVideo` 的 JNI 字符串，
     签名 `(Ljava/lang/String;IIII)V` 与 `ArtemisActivity.PlayVideo` 一致 → 影片本应经
     Java(IJK) 播放；但真机跑：`PlayVideo` **一次都没被调用**（无 `ArtemisVideo` 日志），
     说明内核在影片打开阶段就卡住（还没走到 Java 桥）。
   - 已排除：内核 official/clean 都是同样白屏；auto_patch=auto 无变化；无音频/video 报错；
     logcat 无 JNI/NoSuchMethod 异常。
   结论：**开场影片无法播放，脚本等它结束 → 停在白幕**。
   下一步（未做）：在 Artemis 标签钩子（artemis_extract_hook/official_hook）里拦截影片标签
   （movie_play/lyvideo）：先记日志确认参数，再在无法播放时主动走"影片结束"路径
   （OnFinishVideo / movie_play_exit），让脚本继续。
   已落地：`ArtemisActivity.PlayVideo` 加日志 + 启动异常时回调 `OnFinishVideo`（防再次卡死）。
20. **The Shell Part I 白屏已修复（2026-10-10，方案 A+C）**：真机确认该游戏跑的是
   **libartemis-clean.so（clean 内核）**——不是 prefs 里写的 official（loader 日志：
   `dlopen .../libartemis-clean.so` + `CBackLog::Add not exported (clean kernel?)`）。
   因此修复落在 clean 内核这条链路上：
   - `artemis_extract_hook.cpp`（编入 artemis_audio_bridge）：带内置提取桥的内核**也要装
     DispatchTag 钩子**（用 gSuppressText 关掉文本发射，避免与内置桥重复），否则拿不到
     标签分发、无法做任何控制；
   - **C 记录**：每个不同标签名打一条 `tag: <name>`（全量标签流，定位卡点神器）；
   - **A 跳过**：影片标签（video/movie 类）直接判定已处理，不进原实现；
   - **真正解卡的一步**：影片打不开的另一形态是脚本用 `wait time=0 input=0` 等它结束，
     于是永久停住。容错开启时把这种"无限等待"当已完成 → 脚本继续（日志
     `blocking wait(time=0,input=0) skipped`）。跳过该 wait 后 The Shell Part I 立刻进入
     开场旁白，画面正常推进（截图为证）。
   - 官方内核路径同样加了 `CArtemis::CommandVideo`（记录+跳过）与 `CommandWait`（记录）钩子，
     但本机该游戏走 clean 内核，未实测官方路径。
   开关：`tyranor_prefs` 的 `artemis_skip_video`（默认 true，Java 侧
   `ArtemisActivity.artemisSkipVideo()` 供 native 查询）；置 false 恢复旧行为（会白屏）。
   代价：开场影片不会播放（本来也播不了），换来游戏可玩。尚未加设置页开关。
21. **Artemis「偶发启动退回桌面」根因：我们自己钩子里的 walkArgTree 崩溃（2026-10-10）**。
   真机 crash buffer：
   `Fatal signal 11 (SIGSEGV) ... tid 12712 (Thread-7) pid ...:artemis`，
   回溯 #01-#05 全在 libartemis_loader.so，用 NDK llvm-addr2line 解出：
   `hookedCommandWait → walkArgTree → __memcpy`（artemis_official_hook.cpp:367）。
   即：**wait/video 这类命令的块指针布局与 CommandPrint/CommandVoice 不同**，
   在它们身上调 walkArgTree 会 memcpy 非法地址 → 脚本/音频工作线程崩 → Activity 结束
   （本类 onDestroy 还会 killProcess）→ 用户看到"启动直接回桌面"。因为是工作线程崩，
   主线程只表现为 `onPause finishing=true`，且**偶发**（取决于是否走到那条命令 / 用哪个内核）。
   教训：读引擎内部结构参数前先在真机验证；新钩子先只记"被调用"，别一上来就读参数。
   修复：CommandVideo/CommandWait 只计数与打日志，不读参数；影片跳过后标记一次，
   让紧随其后的 wait 也过掉（官方内核版的"影片容错"）。
22. **Artemis 补丁卷 `.pfs.NNN` 的扫描上限（已真机闭环，2026-10-10）**：
   自研 clean 内核 `D:\Desktop\artemis-compat` 的 `src/pack/pack_manager.cpp:44`：
   `for (uint32_t idx = 0; idx < 64; ++idx)` —— 只探测补丁卷 `.000`~`.063`。
   本作日语补丁是 `TheShell.pfs.080`（索引 80）→ **从未被挂载** → 用的是基础 PFS 里的表
   （语言行只有 en/cn/tw 三行）→ 设置页没有「日本語」。
   改名 `TheShell.pfs.080` → `TheShell.pfs.062` 后，**真机确认设置页出现日语选项** ✓。
   根治：把上限提高（如 128/999）并重编 `build-artemis`（`cmake --build build-android -j8`），
   替换 `files/engine_plugins/artemis/current/arm64-v8a/libartemis-clean.so`。
   排查中被证伪的推断（记下来避免重复走）：
   - ✗「补丁没被解出来」：App 解包正常（表尺寸等于补丁版）；
   - ✗「缺 pc/ja 资源」：补丁只给 en/cn/tw 图，与日语行无关；
   - ✗「auth.lua 覆盖导致」：清空后无变化；
   - ✗「list_windows→list_android 重命名」：无关；
   根因是 `PackManager::Read` 的**包内优先、松散文件兜底**——所以 App 往游戏目录放什么都不生效，
   只有让内核挂载到补丁卷才行。**这条也解释了为什么 App 侧所有尝试都"没反应"。**
   （`system/extend/**` 不覆盖的修复仍保留：那是防止破坏用户/补丁覆盖文件的健壮性修复，
   不是本问题的原因——之前提交信息里把它写成"真因"是错的，以本条为准。）
23. **右侧按键/面板/虚拟鼠标的"统一调用层"现状（2026-10-10 审计）**：
   统一入口（各引擎都应只用这几个，不要再在宿主里另写一份）：
   - 右侧按键列（剧情文本框/截图/放大镜/设置/音量/点击模式/主页）：由
     `OnsExtractPanel.install(...)` 内部调用 `OnsSideButtons.install/installAsWindow`
     安装 —— **所有引擎（含 Tyrano/RPGM）都有**，宿主不需要自己装。
   - 面板开合：`OnsExtractPanel.togglePanel()`（右缘按键、手柄 RB/面板键共用）。
   - 按键路由：`extractPanel.handleKey(mapped)` → 再 `virtualMouse*.handleGamepadKey`。
   - 轴路由：`extractPanel.handleMotion(event)` → 再 `virtualMouse*.handleGamepadMotion`。
   - **光标点击路由：`extractPanel.dispatchCursorClick(x, y)`**（先右缘侧键、再面板本体；
     面板上非控件处点一下即收起）。五个宿主统一走它——Artemis/Tyrano/RPGM 之前各写一套，
     都出过"鼠标点侧键/面板无效"的问题。
   - 输入核心只有一份：`com.core.engine.VirtualMouseInput`（按键/长按/连点/HAT/摇杆/
     旋转/诊断）。`EngineVirtualMouse`（视图宿主）已收敛为薄壳；Tyrano/RPGM 用
     `com.core.tyrano.VirtualMouseLayer`（同一核心 + `window.__tnMouse` JS 契约）。
   审计矩阵（✓=已接线，✗=曾缺漏，现已补）：
     宿主        面板 虚拟鼠标 左侧按键 光标点面板 沉浸式
     ONS          ✓    ✓       ✓        ✓          ✓
     Artemis      ✓    ✓       ✓        ✓          ✓
     Kirikiroid   ✓    ✓       ✓        ✓          ✓
     Tyrano       ✓    ✓       ✗(待定)  ✓(已补)    ✓
     RPGM         ✓    ✓(新补手柄) ✗(待定) ✓(已补)  ✓(已补)
   注：RPGM 原来那份 `com.core.rpgmaker.VirtualMouseLayer` 已删除（重复实现、无手柄输入、
   还留着早先要求删掉的拖拽手柄圆钮），RPGM 改用 Tyrano 版；`rclick`/长按右键语义两边一致。
   遗留：① Tyrano/RPGM 是否要装左侧按键列（EngineLeftButtons）待定——它们目前只有右缘列；
   ② 这两处改动在真机上未验证（设备库里只有 KIRIKIRI/ONS/ARTEMIS 游戏）。
24. **覆盖层统一入口落地（2026-10-10，goal 完成）**：
   - `com.core.engine.VirtualMouseController`：虚拟鼠标宿主无关接口
     （handleKey/handleMotion/isCursorVisible/showCursor/hideCursor）；
     `EngineVirtualMouse`（视图宿主）与 `com.core.tyrano.VirtualMouseLayer`（Web 宿主）都实现它。
   - `com.core.engine.EngineOverlayRouting`：**唯一**的覆盖层路由/装配入口
     routeKey（面板优先→虚拟鼠标）、routeMotion（面板展开全吞→虚拟鼠标）、
     routeCursorClick（平台控件优先，未命中由宿主注入游戏）、applyImmersive（沉浸式）。
   - 五个宿主已全部迁移：ONScripter / KirikiroidLauncher / ArtemisLauncher / TyranoActivity /
     RpgMakerActivity。RPGM 的重复层已删除（改用 Tyrano 版，顺带获得手柄输入）。
   - 真机验证：Artemis（R1 开/关面板 ✓、手柄源 A×3 = 3 组干净 DOWN/UP ✓、连点 armed/撤销 ✓）；
     ONS ESG-13556（面板开关 ✓、vmouse 消费 A ✓）。Tyrano/RPGM 仅编译级验证
     （设备无该类游戏）。
   - 连点证据链：按下 → `click repeat armed code=96 delay=320`；抬起 → 无 tick（单击只点一次）；
     按住 ≥320ms → 同一路径产生 `click repeat tick`（真机需人工按住手柄确认）。
25. **WebDAV（坚果云）实测坑与修复（2026-10-10）**：
   - **探针必须先在远端建目录**：直接 PUT `VNLingo/xxx` 且父目录不存在时，坚果云返回
     409 `AncestorsNotFound: The ancestors of this location does not found` →
     以前"测试连接"永远失败。修复：`testConnectionOrThrow()` 先 `mkdirs(TEST_PROBE_DIR)`。
     实测序列：MKCOL→201、PUT→201、DELETE→204 ✓。
   - **PROPFIND 不能带 XML body**：带 body（无论 `application/xml` 还是 `text/xml`、带不带
     charset）坚果云都回 400 `IllegalArgument: invalid http request entity`；
     **不带 body 的 PROPFIND（RFC 4918 等价 allprop）返回 207** ✓。
     修复：`listFiles` 先发无 body 版本，失败再回退带 body 版本（兼容其它服务端）。
   - 错误信息改为人类可读：从 WebDAV 的 XML `<s:message>/<s:exception>` 提取内容，
     再按 401/403/404/409/507/5xx 追加成因提示（如 401 → 提醒坚果云要用「应用密码」）。
   - 账户支持**编辑**（此前只能删除再添加）：`SyncManager.updateAccount(...)` 原地替换、
     保持列表位置与活动状态，密码留空=保持不变；UI 账号行新增「编辑」。
   - 真机验收：测试连接 → 「连接成功」；立即同步 → 状态出现"上次同步"、远端
     `VNLingo/VNLingo_sync.json` HEAD 返回 200；编辑保存后仍是 1 个账户且保持"当前"、
     再次测试连接仍成功（密码被保留）。
26. **云同步凭据的本地加密存储（2026-10-10）**：
   此前 `vnlingo_sync.xml` 里 `webdav_password` / `webdav_accounts` 是**明文**。
   现按 Android 平台标准做法加密：
   - 新增 `com.tyranor.next.core.sync.SyncSecretStore`：密钥在 **AndroidKeyStore** 中生成
     （别名 `vnlingo_webdav_credentials_v1`，AES-256-GCM，`setRandomizedEncryptionRequired`），
     密钥不可导出；每次加密随机 12B IV，存 `Base64(IV||ciphertext||tag)`；GCM 自带完整性校验；
     解密失败（换机/还原导致密钥丢失）返回 null，调用方降级为"需重新登录"。
     等价于 Jetpack `EncryptedSharedPreferences` 的安全性，但不新增依赖（该库已被 deprecated）。
   - `SyncManager`：明文键 `webdav_server/username/password/accounts` →
     `*_enc`；读取优先密文，只有密文键不存在时才读旧明文**并即时迁移**（写密文+删明文）；
     加密不可用时回退明文写入（保证功能不坏）并打日志。
   - 真机验证：升级+启动后四个明文键全部消失，`webdav_password_enc`(60)/`webdav_accounts_enc`(188)
     存在，明文密码在全文件中已搜不到；同步页账户仍正常显示、连接测试仍「连接成功」、同步正常。
   注意：`auto_sync`/`last_sync_*` 非敏感，保持明文；备份文件里会带上密文，跨设备因密钥不同
   无法解密 → 应用会清掉并要求重新登录（预期行为）。
27. **云同步中心「存档云同步」列表 + 两个既有隐患的根因（2026-10-10）**：
   - 新功能：`SaveCloudCatalog` + 同步页列表。每行显示：游戏标题、云端 Last-Modified、
     本机上次上传时间、本地存档数、状态（仅本地/仅云端），操作：上传/下载/删除云端。
     同一云端名只列一行（库里同标题重复条目会互相覆盖同一个 zip）。
   - **Android 的 HttpURLConnection 只允许 OPTIONS/GET/HEAD/POST/PUT/DELETE/TRACE/PATCH**，
     `setRequestMethod("PROPFIND"/"MKCOL")` 直接抛 ProtocolException。这解释了：
     * `WebDavClient.listFiles`（PROPFIND 列目录）事实上一直不可用 → 已删除该死代码，
       改为一律用 **HEAD 逐个文件**（本列表只需知道"每个游戏的云端 zip 时间"）；
     * `OnsSaveCloud.webdavEnsureDir` 的 MKCOL 一直被 catch 吞掉 → 目录从未创建 →
       上传 PUT 报 409 AncestorsNotFound（用户"同步不成功"的另一半原因）。
   - 修复：两处都改为**裸 TLS socket** 发 MKCOL（`WebDavClient.rawRequest` /
     `OnsSaveCloud.rawRequest`：HTTP/1.1 + Basic 认证 + Connection: close，只读状态行）。
   - 真机验证：上传 ESG-13556 → 日志 `MKCOL TyranorNext/ -> 201`、`MKCOL TyranorNext/saves/ -> 201`，
     远端 `TyranorNext/saves/ESG-13556.zip` HEAD=200；列表行显示「云端：2026-10-10 12:42」并排到最前；
     「删除云端」后远端 404、该行按钮自动变回禁用（仅本地）；「下载」点击无异常。
   - 遗留：因不能用 PROPFIND，"本地库没有、只有云端"的 zip 列不出来（需 OkHttp 或自研
     PROPFIND 解析才能补齐）；游戏标题乱码是扫描期既有的编码问题，与本次无关。
28. **云存档改为「每个游戏一个专属子文件夹」（2026-10-10）**：
   远端布局从 `<dir>/<标题>.zip` 改为 **`<dir>/<标题>/saves.zip`**（每个游戏的同步内容
   单独放自己的子文件夹，便于以后在同目录再放 meta/截图等附属文件）。
   - 上传：MKCOL 逐级创建 `dir/**` 与 `dir/<标题>/`，PUT 写 `saves.zip`，并**删掉旧平铺 zip**（迁移）；
   - 读取/下载：先新路径，失败回退旧平铺路径（老上传仍可见、可下载）；
   - 删除云端：删新路径（再顺手删空的游戏子文件夹），新路径没有则删旧平铺 zip；
   - 列表 HEAD 同样先新后旧（日志 `cloud save uses legacy path: ...` 可确认走了兼容分支）。
   真机验证：`MKCOL TyranorNext/saves/ESG-13556/ -> 201`、`legacy flat zip removed`、
   新路径 HEAD=200 / 旧路径 404；刷新列表后日志不再出现 legacy 回退（说明已读新路径）。
   注：坚果云对目录 HEAD 返回 403（不支持 HEAD 集合），属正常。