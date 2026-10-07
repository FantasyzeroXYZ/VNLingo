# VNLingo MEMORY — AI 会话记忆（环境、踩坑、决策、续作指南）

> 供后续 AI 会话快速接手。配合 `PROGRESS.md`（进度，同目录）与 `docs/guide/游戏内提取制卡功能方案.md`（提取功能线方案）阅读。
> 更新时间：2026-10-05

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
