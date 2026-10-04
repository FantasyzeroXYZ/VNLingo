# VNLingo MEMORY — AI 会话记忆（环境、踩坑、决策、续作指南）

> 供后续 AI 会话快速接手。配合 `PROGRESS.md`（进度）与 `docs/游戏内提取制卡功能方案.md`（提取功能线方案）阅读。
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

## 移植外事实（2026-10-05 会话二新增）

- **随包 Artemis 内核（含 libartemis-clean.so）均无 extract_bridge 发射器**：`llvm-nm -D`
  全部 .so 查无 `onArtemisExtract`/`getExtractCacheDir` 字符串。提取数据源由
  `artemis_extract_hook.cpp`（shadowhook 1.1.1 经 dlsym 使用，Java 包装是编译桩）对 clean
  内核 `artc::Compositor::SetMessageLayered` / `artc::AudioChannels::Play` inline hook 补齐；
  官方 revision 内核（artemis:: 命名空间）符号布局不同，不挂钩、无提取。
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
