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

- 提取语义统一为「完整对话 + 配对语音 + 截图」，各引擎只做事件源
  （`ExtractFacade` 接口），面板/词典/翻译/制卡管线全复用（engine `com.core.ons`）。
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
  最前端生效，先于提取面板/虚拟鼠标/游戏链路。

## 续作指南（下次会话从这里开始）

1. **推送**：`feat/extract-suite` 分支 8 个提交未推送（用户决定何时 push 到 origin）。
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
