package com.core.ons;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.content.Intent;
import android.net.Uri;
import android.view.KeyEvent;
import android.view.PixelCopy;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.core.engine.R;
import com.core.engine.PanelActionBindings;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ONS 提取面板（剧情文本框）：展示 [ONS-BRIDGE] 提取的当前句与配对语音，
 * 提供查词/制卡/翻译、播放/复制/保存语音、截图、历史列表与听力模式。
 *
 * 显示与按键风格对齐 webgametxt（TextOverlay/HistoryPanel），查词/制卡/翻译
 * 实现参考 D:\Desktop\test-flutter\anki（本地 Yomichan 词典 SQLite + 最长前缀
 * 分层搜索 + 词形还原；AnkiDroid 制卡 word/sentence 双方案；OpenAI 兼容翻译）：
 * - 白底圆角底部面板，标题行 + ♪ 语音状态行，emoji 按钮排
 * - 句栏词元化（标点/空格切分），点按或 D-pad ←→ 选词 → 词典查义（释义区，
 *   词卡一键入库），🌐 翻译整句（OpenAI 兼容 API）
 * - 🕘 历史（#序号 + 正文，点按朗读）、🎧 听力模式（有语音句隐藏文本点按揭示）
 * - 手柄：面板关 RB 呼出；开时 D-pad ←→ 选词 / ↑↓ 滚动、A 查词选中词元
 *   （无选中回退播放）、B 两步退出、X 翻译、Y 词卡（有查词结果）/句卡、
 *   START 句卡，其余按键拦截不透传游戏；键盘 Space 播放、C 复制、Esc 收面板
 *
 * 平台控件覆盖层，与 ONScripter 既有虚拟按键同一叠加方式（不干扰 SDL 触摸）。
 */
@SuppressLint("AppCompatCustomView") // engine 层轻量覆盖层沿用平台控件
public class OnsExtractPanel {

    private static final String TAG = "OnsExtract";
    private static final String TTS_PREFS = "ons_extract_tts";
    private static final String KEY_TTS_ON = "tts_enabled";
    private static final String KEY_TTS_AUTO = "tts_auto";
    private static final String KEY_LISTENING = "listening_mode";
    private static final String KEY_OPACITY = "panel_opacity";
    /** 软件内"游戏音量"（0..100，百分比；不影响手机系统音量）。 */
    private static final String KEY_GAME_VOLUME = "game_volume";
    private static final String KEY_TTS_MULTI = "tts_multi";
    /** TTS 语速（百分比 int，50..150；系统 TTS setSpeechRate 与 MultiTTS speed 共用）。 */
    private static final String KEY_TTS_RATE = "tts_rate";
    /** 句读模式：按标点分段朗读（段间自然停顿）。 */
    private static final String KEY_TTS_JUDOU = "tts_judou";
    /** 循环次数：0 = 不循环，N = 整句重复 N+1 遍。 */
    private static final String KEY_TTS_LOOP = "tts_loop";
    private static final int[] RATE_STEPS = {50, 75, 100, 125, 150};
    /** MultiTTS HTTP TTS（Forwarding service，127.0.0.1:8774）。 */
    private static final String MULTI_TTS_PACKAGE = "org.nobody.multitts";
    /** 面板透明度档位（不透明百分比），点按循环切换。 */
    private static final int[] OPACITY_STEPS = {100, 85, 70, 55, 40};
    private static final int BG_PANEL = 0xE6101010;
    private static final int BG_BUTTON = 0xFFF1F5F9;
    /** 面板圆形按钮底（暗灰圆钮，白图标）。 */
    private static final int BG_DARK = 0xFF2E2E2E;
    private static final int STROKE_LIGHT = 0x87FFFFFF;
    private static final int BG_DEF = 0xFF2A2A2A;
    private static final int STROKE = 0xFFE2E8F0;
    private static final int ACCENT = 0xFF007AFF;
    private static final int DEF_ACCENT = 0xFF0D9488;
    /** 暗色面板配色：语音行青色 / 正文白 / 次要文字浅灰 / 分隔线半透明白。 */
    private static final int STATUS_TEAL = 0xFF2DD4BF;
    private static final int TEXT_ON_DARK = 0xFFFFFFFF;
    private static final int TEXT_ON_DARK_DIM = 0xFFCBD5E1;
    private static final int DIVIDER_DARK = 0x33FFFFFF;
    private static final int SELECT_BG = 0x40FBBF24; // webgametxt 选中词琥珀高亮
    private static final int TEXT_TITLE = 0xFF0F172A;
    private static final int TEXT_BODY = 0xFF1E293B;
    private static final int TEXT_BUTTON = 0xFF334155;
    private static final int TEXT_DIM = 0xFF64748B;
    private static final int TEXT_PLACEHOLDER = 0xFF94A3B8;
    private static final int HISTORY_MAX = 200;

    /** 历史条目：句文本 + 当句配对语音名（回放原声用，空串表示无语音走 TTS）。 */
    private static final class HistoryEntry {
        final String sentence;
        final String voiceName;

        HistoryEntry(String sentence, String voiceName) {
            this.sentence = sentence;
            this.voiceName = voiceName == null ? "" : voiceName;
        }
    }

    private final ExtractFacade facade;
    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** M4 查询语言（auto/ja/zh/ko/en/vi；启动 extra 下发，全局 + 每游戏覆盖已合并）。 */
    private String dictQueryLang = "auto";
    /** M4 是否查读音（仅日语有效）。 */
    private boolean dictReading = true;
    /** M4「自动」下汉字归属（ja/zh；只读全局多进程 prefs）。 */
    private String dictHanPriority = "ja";

    private OnsFloatingChip chip;
    private View sideButtons;
    /** 窗口承载模式（NativeActivity 宿主）：右缘按键组以独立小悬浮窗安装。 */
    private boolean sideButtonsWindowMode;
    private LinearLayout panel;
    private TextView statusView;
    private TextView sentenceView;
    private TextView listeningView;
    private TextView transView;
    private ScrollView mainScroller;
    private ScrollView historyScroller;
    private LinearLayout historyList;
    private MediaPlayer player;
    /**
     * 朗读代次：每次停止/切句自增。
     *
     * HTTP-TTS / MultiTTS 是**后台线程合成**再回主线程播放；若 A 句的合成在用户切到 B 句
     * 之后才返回，它仍会回来播 A 的声音 —— 表现就是"切句后偶尔还残留上一条对白的 TTS"
     * （stopTts() 管不到尚未返回的合成）。异步回来时代次不一致就丢弃。
     */
    private volatile int ttsEpoch;
    private File currentVoiceFile;
    private boolean expanded;
    /** 系统 TTS：无配对语音时朗读（BGM 不进配对通道，不受影响）。 */
    private android.speech.tts.TextToSpeech tts;
    /** 当前 tts 实例使用的引擎包名（null=系统默认）。 */
    private String activeTtsEngine;
    private boolean ttsReady;
    private boolean ttsEnabled;
    private boolean ttsAuto;
    /** 朗读直调 MultiTTS 引擎（开启后忽略系统默认 TTS 设置）。 */
    private boolean multiTtsEnabled;
    /** TTS 语速百分比（RATE_STEPS 之一，持久化）。 */
    private int ttsRate;
    private boolean ttsJudou;
    private int ttsLoop;
    private String lastSpokenSentence;
    private android.widget.ImageView multiTtsToggle;
    private android.widget.ImageView ttsToggle;
    private android.widget.ImageView autoToggle;
    private android.widget.ImageView historyToggle;
    private android.widget.ImageView listeningToggle;
    /** 历史视图开关与累计对话（句增量 + 当句配对语音名，供历史回放）。 */
    private boolean historyMode;
    private final List<HistoryEntry> history = new ArrayList<>();
    private String lastHistorySentence;
    /** 听力模式：有语音句隐藏文本，点按揭示；翻句后重新隐藏。 */
    private boolean listeningMode;
    private boolean listeningRevealed;
    /** 词元（标点/空格切分）char 区间与当前选中序号；-1 未选中。 */
    private final List<int[]> unitRanges = new ArrayList<>();
    private int selectedToken = -1;
    private SpannableString sentenceSpan;
    /** 最近一次查词结果（词卡用）。 */
    private List<OnsDictStore.Group> defGroups;
    private String defSentence;
    /** M6：当前查词命中的方式与还原路径（供释义悬浮窗标题与制卡槽位使用）。 */
    private String defMatchType = "";
    private String defPath = "";
    private String defLemma = "";
    /** 查词/翻译请求代次号：翻句或重复请求后丢弃过期回调结果。 */
    private volatile int dictRequestId;
    private volatile int translateRequestId;
    /** 扫描线程池：单线程串行化查词（多线程查同一 SQLite 无增益）。快速连按时
     *  过期任务排队在前，但循环首行即代次检查、立刻返回，不阻塞新扫描。 */
    private final java.util.concurrent.ExecutorService scanExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ons-scan");
                t.setDaemon(true);
                return t;
            });
    /** 面板不透明百分比（1..100，持久化）。 */
    private int panelOpacity;
    /**
     * 软件内**游戏音量**百分比（0..100，持久化）。
     *
     * 用户要求：音量键只调"这个软件里的游戏音量"，不要去动手机系统音量。
     * 各引擎的落地方式不同（宿主通过 {@link #setGameVolumeHook} 接），
     * 面板只负责 UI 与持久化。
     */
    private int gameVolume;
    private java.util.function.IntConsumer gameVolumeHook;

    /** 宿主接入"把百分比音量应用到引擎"（0..100）。 */
    public void setGameVolumeHook(java.util.function.IntConsumer hook) {
        gameVolumeHook = hook;
        if (hook != null) hook.accept(gameVolume);
    }

    /** 当前游戏音量百分比（0..100）。 */
    public int getGameVolume() {
        return gameVolume;
    }
    private TextView opacityToggle;
    private LinearLayout actionsRow;
    private android.widget.HorizontalScrollView actionsScrollView;
    public OnsExtractPanel(ExtractFacade facade) {
        this.facade = facade;
        this.activity = facade.getActivity();
        android.content.SharedPreferences prefs =
                activity.getSharedPreferences(TTS_PREFS, android.content.Context.MODE_PRIVATE);
        ttsEnabled = prefs.getBoolean(KEY_TTS_ON, true);
        // 面板「自动」开关的初值与**全局自动朗读**保持一致：否则全局开着、面板显示"关"，
        // 用户把面板开关关掉后仍在自动朗读（用户反馈"开关没有正常发挥效果"）。
        // 之后两边靠 settingRow 的写透保持同步。
        ttsAuto = OnsTtsEngines.autoRead(activity);
        multiTtsEnabled = prefs.getBoolean(KEY_TTS_MULTI, false);
        ttsRate = prefs.getInt(KEY_TTS_RATE, 100);
        ttsJudou = prefs.getBoolean(KEY_TTS_JUDOU, false);
        ttsLoop = prefs.getInt(KEY_TTS_LOOP, 0);
        listeningMode = prefs.getBoolean(KEY_LISTENING, false);
        panelOpacity = prefs.getInt(KEY_OPACITY, OPACITY_STEPS[0]);
        gameVolume = prefs.getInt(KEY_GAME_VOLUME, 100);
        // 词典库提前打开（导入/查词共用；后台线程 search 不依赖 context）
        OnsDictStore.get().init(activity);
        // 音频不再保留：清掉旧版本遗留的语音缓存目录（历史回放改走档案按名补读 / TTS）
        GameMediaCleanup.purgeLegacyAudioCache(activity);
        GameMediaCleanup.purgeStaleCardMedia(activity);
        // 面板动作绑定表快照（呼出/选词/查词/制卡/翻译…；设置页在 app 进程写，
        // 这里在引擎进程刷新，见 PanelActionBindings）
        com.core.engine.PanelActionBindings.refresh(activity);
        com.core.engine.VirtualMouseBindings.refresh(activity);
        OnsDeinflector.init(activity);  // 词形还原全量规则（assets/deinflect_*.json）
        com.core.ons.deinflect.DeinflectPackStore.get().init(activity);  // M2 语言包
        // M4 查询语言设置：查询语言/是否查读音随启动 extra 下发（全局 + 每游戏覆盖
        // 已在 app 侧 resolver 合并）；汉字归属只读全局设置（多进程 prefs）。
        android.content.Intent intent = activity.getIntent();
        dictQueryLang = intent == null ? "auto"
                : intent.getStringExtra(com.core.engine.LaunchContract.DICT_QUERY_LANG);
        if (dictQueryLang == null || dictQueryLang.isEmpty()) dictQueryLang = "auto";
        dictReading = intent == null
                || intent.getBooleanExtra(com.core.engine.LaunchContract.DICT_READING, true);
        try {
            dictHanPriority = activity.getSharedPreferences(
                            com.core.engine.EnginePrefs.APP_PREFS,
                            android.content.Context.MODE_MULTI_PROCESS)
                    .getString("dict_han_priority", "ja");
        } catch (Throwable ignored) {
            dictHanPriority = "ja";
        }
        if (!"zh".equals(dictHanPriority)) dictHanPriority = "ja";
        // 词典范围过滤按「会话语言」：auto 不过滤（保持既有行为）
        OnsDictStore.get().setQueryLanguage(dictQueryLang);
        android.util.Log.i("OnsExtractPanel", "dict query lang=" + dictQueryLang
                + " reading=" + dictReading + " han=" + dictHanPriority);
    }

    private android.content.SharedPreferences ttsPrefs() {
        return activity.getSharedPreferences(TTS_PREFS, android.content.Context.MODE_PRIVATE);
    }

    /** 窗口承载模式（NativeActivity 宿主）：右缘按键组以独立小悬浮窗安装。 */
    public void setSideButtonsWindowMode(boolean window) {
        this.sideButtonsWindowMode = window;
    }

    /**
     * 独立安装（窗口承载宿主）：右缘按键组装为独立小窗；面板视图不加入任何父布局、
     * 由宿主以独立 WindowManager 窗口承载（底部、可触摸、GONE 时窗口塌缩为 0 不挡游戏）。
     * 返回面板根视图。
     */
    public LinearLayout installDetached(Runnable onMouseModeToggle,
                                        java.util.function.Supplier<Boolean> mouseMode) {
        this.mouseModeToggle = onMouseModeToggle;
        this.mouseModeSupplier = mouseMode;
        sideButtons = OnsSideButtons.installAsWindow(activity, this::togglePanel,
                this::captureScreenshot, this::showSettingsDialog,
                onMouseModeToggle, mouseMode);
        panel = buildPanel();
        panel.setVisibility(View.GONE);
        facade.setListener(this::refresh);
        return panel;
    }

    /** 在宿主覆盖层内安装右缘按键组（文本框开关/截图/设置/音量/点击模式/主页）与剧情文本框面板。 */
    public void install(android.view.ViewGroup overlay,
                        Runnable onMouseModeToggle, java.util.function.Supplier<Boolean> mouseMode) {
        this.mouseModeToggle = onMouseModeToggle;
        this.mouseModeSupplier = mouseMode;
        if (sideButtonsWindowMode) {
            sideButtons = OnsSideButtons.installAsWindow(activity, this::togglePanel,
                    this::captureScreenshot, this::showSettingsDialog,
                    onMouseModeToggle, mouseMode);
        } else {
            sideButtons = OnsSideButtons.install(overlay, this::togglePanel,
                    this::captureScreenshot, this::showSettingsDialog,
                    onMouseModeToggle, mouseMode);
        }

        panel = buildPanel();
        android.widget.FrameLayout.LayoutParams panelLp = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        panel.setVisibility(View.GONE);
        overlay.addView(panel, panelLp);

        facade.setListener(this::refresh);
    }

    /** 面板根视图（WindowOverlayHost 可见性联动用）。 */
    public android.view.View panelView() {
        return panel;
    }

    /** 面板是否处于展开态（宿主焦点切换/输入独占判定用）。 */
    public boolean isExpanded() {
        return expanded;
    }

    /**
     * 把右缘按键组提到最前（面板窗在它之后加入会盖住右侧按键 → 面板展开时点不到）。
     * 由 {@link #togglePanel()} 在展开时自动调用。
     */
    public void raiseSideButtons() {
        if (sideButtons != null && sideButtonsWindowMode) {
            OnsSideButtons.raiseWindow(activity, sideButtons);
        }
    }

    /**
     * 面板可见性回调（悬浮窗宿主用）：开合后同步覆盖窗触摸放行。
     * 不能只靠 OnLayoutChangeListener——VISIBLE→GONE 时视图 bounds 不变、
     * 布局回调不触发，覆盖窗会停留在可触摸态吃掉全部游戏输入（Artemis 触摸失灵根因）。
     */
    public void setPanelVisibilityHook(Runnable hook) {
        panelVisibilityHook = hook;
    }

    private Runnable panelVisibilityHook;

    /** 宿主提供的点击模式（虚拟鼠标）开关与状态查询（快捷键用）。 */
    private Runnable mouseModeToggle;
    private java.util.function.Supplier<Boolean> mouseModeSupplier;

    public void release() {
        facade.setListener(null);
        stopTts();
        if (tts != null) {
            try { tts.shutdown(); } catch (Throwable ignored) {}
            tts = null;
            ttsReady = false;
        }
        stopVoice();
        main.removeCallbacksAndMessages(null);
    }

    // ------------------------------------------------------------------
    // UI 构建（webgametxt TextOverlay 风格：白底圆角 + 标题/♪状态 + emoji 按钮排）
    // ------------------------------------------------------------------

    private LinearLayout buildPanel() {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(10), dp(14), dp(12));
        panel.setBackground(roundedTop(BG_PANEL, dp(16)));
        panel.setClickable(true);

        statusView = new TextView(activity);
        statusView.setTextColor(STATUS_TEAL);
        statusView.setTextSize(11);
        statusView.setSingleLine(true);
        statusView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        statusView.setText(activity.getString(R.string.engine_ons_extract_standby));
        // 候选切换入口：有多来源候选时点状态行循环（0=自动，1..n=候选源）
        statusView.setOnClickListener(v -> showCandidateDialog());

        // 本句视图：当前句（词元可点）+ 听力占位 + 释义区 + 翻译行
        sentenceView = new TextView(activity);
        sentenceView.setTextColor(TEXT_ON_DARK);
        sentenceView.setTextSize(16);
        sentenceView.setTextIsSelectable(false); // 点词查词，不与选中冲突
        sentenceView.setMovementMethod(LinkMovementMethod.getInstance());
        sentenceView.setLineSpacing(0f, 1.25f); // 折行紧凑（2x 会被当成额外空行）
        sentenceView.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY);

        listeningView = new TextView(activity);
        listeningView.setTextColor(TEXT_PLACEHOLDER);
        listeningView.setTextSize(13);
        listeningView.setGravity(Gravity.CENTER);
        listeningView.setPadding(0, dp(16), 0, dp(16));
        listeningView.setVisibility(View.GONE);
        listeningView.setOnClickListener(v -> {
            listeningRevealed = true;
            refresh();
        });

        // 释义不再入面板：查词结果路由到顶部独立悬浮窗 OnsDictOverlay（jidoujisho 式）
        transView = new TextView(activity);
        transView.setTextColor(TEXT_ON_DARK_DIM);
        transView.setTextSize(13);
        transView.setLineSpacing(0f, 1.5f);
        transView.setVisibility(View.GONE);

        LinearLayout mainColumn = wrapColumn(sentenceView, listeningView, transView);
        mainScroller = new ScrollView(activity);
        mainScroller.addView(mainColumn);
        LinearLayout.LayoutParams mainLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        mainLp.topMargin = dp(6);
        mainLp.bottomMargin = dp(6);

        // 历史视图：#序号 + 正文 + ♪ 语音标记，点按回放（webgametxt HistoryPanel 风格）
        historyList = new LinearLayout(activity);
        historyList.setOrientation(LinearLayout.VERTICAL);
        historyScroller = new ScrollView(activity);
        historyScroller.addView(historyList);
        historyScroller.setVisibility(View.GONE);
        LinearLayout.LayoutParams historyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        historyLp.topMargin = dp(6);
        historyLp.bottomMargin = dp(6);

        // 图标按钮排（置于面板顶部）：播放/复制/翻译/制卡；截图/存语音已由右缘按键承担
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        // 按键少于行宽时整排水平居中（超出可滚动，HorizontalScrollView 兜底）
        actions.setGravity(Gravity.CENTER);
        actions.addView(makeAction(R.drawable.ic_play, R.string.engine_ons_extract_play, this::playVoice));
        actions.addView(makeAction(R.drawable.ic_copy, R.string.engine_ons_extract_copy_text, this::copyText));
        actions.addView(makeAction(R.drawable.ic_translate, R.string.engine_ons_extract_translate, this::translateCurrent));
        actions.addView(makeAction(R.drawable.ic_card, R.string.engine_ons_extract_anki, this::sendToAnki));
        // AI 游戏助手（Agent/Chat LLM + 工具调用）
        actions.addView(makeAction(R.drawable.ic_autorenew, R.string.engine_ons_agent_title, () -> {
            OnsAgentDialog.show(activity, this);
        }));
        // 开关型：历史视图 / 听力模式 / TTS / 自动朗读（状态持久化）
        historyToggle = makeToggle(R.drawable.ic_history, R.string.engine_ons_extract_history, false, () -> {
            toggleHistoryOverlay();  // 历史独立悬浮窗（用户要求与文本框分离）
            return OnsHistoryOverlay.showing();
        });
        listeningToggle = makeToggle(R.drawable.ic_headphones, R.string.engine_ons_extract_listening, listeningMode, () -> {
            listeningMode = !listeningMode;
            listeningRevealed = false;
            ttsPrefs().edit().putBoolean(KEY_LISTENING, listeningMode).apply();
            refresh();
            return listeningMode;
        });
        multiTtsToggle = makeToggle(R.drawable.ic_multi_tts, R.string.engine_ons_extract_multi_tts, multiTtsEnabled, () -> {
            multiTtsEnabled = !multiTtsEnabled;
            ttsPrefs().edit().putBoolean(KEY_TTS_MULTI, multiTtsEnabled).apply();
            // 切换朗读引擎：销毁旧实例，下次朗读按新引擎重建
            stopTts();
            if (tts != null) {
                try { tts.shutdown(); } catch (Throwable ignored) {}
                tts = null;
                ttsReady = false;
                activeTtsEngine = null;
            }
            return multiTtsEnabled;
        });
        ttsToggle = makeToggle(R.drawable.ic_volume, R.string.engine_ons_extract_tts_toggle, ttsEnabled, () -> {
            ttsEnabled = !ttsEnabled;
            ttsPrefs().edit().putBoolean(KEY_TTS_ON, ttsEnabled).apply();
            if (!ttsEnabled) stopTts();
            return ttsEnabled;
        });
        autoToggle = makeToggle(R.drawable.ic_autorenew, R.string.engine_ons_extract_auto_toggle, ttsAuto, () -> {
            ttsAuto = !ttsAuto;
            ttsPrefs().edit().putBoolean(KEY_TTS_AUTO, ttsAuto).apply();
            return ttsAuto;
        });
        actions.addView(historyToggle);
        actions.addView(listeningToggle);
        actions.addView(multiTtsToggle);
        actions.addView(ttsToggle);
        actions.addView(autoToggle);
        // 透明度按钮：显示当前百分比，点按循环档位
        opacityToggle = makeOpacityButton();
        actions.addView(opacityToggle);
        actionsRow = actions;
        // 按钮较多，横向可滚动
        android.widget.HorizontalScrollView scroll = new android.widget.HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(actions);
        actionsScrollView = scroll;

        // ♪ 状态行 + 正文在顶，按键排置底（用户要求：按键在面板最下方）
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        panel.addView(statusView);
        panel.addView(mainScroller, mainLp);
        panel.addView(historyScroller, historyLp);
        panel.addView(scroll, actionsLp);
        return panel;
    }

    private LinearLayout wrapColumn(View... views) {
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        for (View v : views) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(4);
            col.addView(v, lp);
        }
        return col;
    }

    /** 图标按钮（标准矢量图标，文本仅作无障碍描述）。 */
    private android.widget.ImageView makeAction(int iconRes, int descRes, Runnable action) {
        android.widget.ImageView iv = new android.widget.ImageView(activity);
        iv.setImageResource(iconRes);
        iv.setContentDescription(activity.getString(descRes));
        // 与左右缘按键组同款：深色半透明底 + 白色图标
        iv.setColorFilter(0xFFFFFFFF);
        int side = dp(32);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(side, side);
        lp.rightMargin = dp(6);
        lp.leftMargin = dp(1);
        int pad = dp(6);
        iv.setPadding(pad, pad, pad, pad);
        iv.setBackground(rounded(BG_DARK, dp(16)));
        iv.setOnClickListener(v -> {
            try {
                action.run();
            } catch (Throwable t) {
                Log.w(TAG, "extract action failed", t);
                toast(R.string.engine_ons_extract_action_failed);
            }
        });
        iv.setLayoutParams(lp);
        return iv;
    }

    /** 透明度按钮：文本型（显示当前百分比），点按循环档位。 */
    private TextView makeOpacityButton() {
        TextView tv = new TextView(activity);
        tv.setText(activity.getString(R.string.engine_ons_extract_opacity_fmt, panelOpacity));
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(11);
        tv.setGravity(Gravity.CENTER);
        tv.setMinWidth(dp(40));
        tv.setMinHeight(dp(40));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(32));
        lp.rightMargin = dp(6);
        lp.leftMargin = dp(1);
        tv.setBackground(rounded(BG_DARK, dp(20)));
        tv.setOnClickListener(v -> cycleOpacity());
        tv.setLayoutParams(lp);
        return tv;
    }

    /** 面板显示/隐藏（右缘按键组与手柄 RB 共用）：开启态切到隐藏态保持提取与 TTS 运行。 */
    public void togglePanel() {
        expanded = !expanded;
        panel.setVisibility(expanded ? View.VISIBLE : View.GONE);
        // 诊断：右侧「剧情文本框」按键与手柄 RB 都走这里，日志用于定位
        // 「点击后收不起来」这类宿主侧问题（窗口承载/触摸路由）。
        android.util.Log.i("OnsExtractPanel", "togglePanel expanded=" + expanded
                + " panelVisibility=" + (panel.getVisibility() == View.VISIBLE ? "VISIBLE" : "GONE")
                + " panelAttached=" + (panel.getParent() != null)
                + " panelSize=" + panel.getWidth() + "x" + panel.getHeight());
        if (expanded) {
            // 面板窗是后加入的，会压在右缘按键组之上 → 展开时把侧键窗提到最前，
            // 保证「打开剧情文本框后右侧按键依然可点」（用户要求）。
            raiseSideButtons();
            // refresh 先行：apply* 任何一环异常都不能挡住内容显示（窗口承载宿主
            // 实测 applyAdaptiveHeight 一类的尺寸同步异常会吞掉 refresh）
            refresh();
            try {
                applyOpacity();
                applyPanelWidth();
                applyAdaptiveHeight();
            } catch (Throwable t) {
                android.util.Log.w("OnsExtractPanel", "panel layout sync failed", t);
            }
        }
        // 可见性已同步生效，直接通知宿主同步覆盖窗触摸放行（post 一次避让同帧布局）
        if (panelVisibilityHook != null) {
            final Runnable hook = panelVisibilityHook;
            main.post(hook);
        }
    }

    /**
     * 窗口承载宿主（Artemis）的面板窗宽度：竖屏全宽；横屏两侧各留 dp(64)
     * 给左缘/右缘按键列，面板水平居中不遮按键。
     */
    public int preferredWindowWidthPx() {
        boolean landscape = activity.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        int screenW = activity.getResources().getDisplayMetrics().widthPixels;
        if (!landscape) return android.view.ViewGroup.LayoutParams.MATCH_PARENT;
        return Math.max(dp(320), screenW - dp(64) * 2);
    }

    /**
     * 面板宽度：竖屏全宽；横屏水平居中、宽度以按钮行自然宽度为准
     * （一排按键正好放下，不遮过多画面）。量不到按钮行宽（开面板早于首次
     * 布局）时有界重试——一次性 fallback 会让面板永久停在兜底宽度。
     */
    private int panelWidthRetry;
    private final Runnable applyPanelWidthOnce = new Runnable() {
        @Override
        public void run() {
            if (panel == null || !expanded) return;
            int buttonsWidth = 0;
            if (actionsScrollView != null && actionsScrollView.getChildCount() > 0) {
                buttonsWidth = actionsScrollView.getChildAt(0).getWidth();
            }
            if (buttonsWidth <= 0 && panelWidthRetry < 5) {
                panelWidthRetry++;
                panel.postDelayed(this, 100);
                return;
            }
            int screenW = activity.getResources().getDisplayMetrics().widthPixels;
            int w = buttonsWidth > 0
                    ? Math.min(buttonsWidth + dp(28), screenW - dp(24))
                    : Math.min(dp(560), screenW - dp(24));
            applyPanelWidthValue(w, true);
        }
    };

    /**
     * 宿主模式感知的宽度应用：窗口承载宿主（Artemis）下 panel.getLayoutParams()
     * 是 WindowManager.LayoutParams，强转 FrameLayout.LayoutParams 会
     * ClassCastException（曾把 togglePanel 展开链路的 refresh 一起吞掉）——
     * 改宽后经 panelVisibilityHook 触发宿主 updateViewLayout。
     */
    private void applyPanelWidthValue(int w, boolean centerHorizontal) {
        android.view.ViewGroup.LayoutParams lp = panel.getLayoutParams();
        if (lp instanceof android.widget.FrameLayout.LayoutParams) {
            android.widget.FrameLayout.LayoutParams flp =
                    (android.widget.FrameLayout.LayoutParams) lp;
            flp.width = w;
            flp.gravity = Gravity.BOTTOM | (centerHorizontal ? Gravity.CENTER_HORIZONTAL : 0);
            panel.setLayoutParams(flp);
        } else {
            lp.width = w;
            panel.setLayoutParams(lp);
            if (panelVisibilityHook != null) {
                final Runnable hook = panelVisibilityHook;
                main.post(hook);
            }
        }
    }

    private void applyPanelWidth() {
        if (panel == null) return;
        boolean landscape = activity.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        if (!landscape) {
            applyPanelWidthValue(android.view.ViewGroup.LayoutParams.MATCH_PARENT, false);
            return;
        }
        panelWidthRetry = 0;
        panel.post(applyPanelWidthOnce);
    }

    /**
     * 高度自适应：面板 WRAP_CONTENT 随内容收缩；内容超出屏高 60% 时，
     * 把可见滚动区压到剩余空间并锁定面板高度，内容转内部滚动。
     */
    /** 自适应高度任务（合并多次请求，避免文本高频更新时堆一串测量）。 */
    private final Runnable adaptiveHeightJob = new Runnable() {
        @Override
        public void run() {
            if (panel == null || !expanded) return;
            ScrollView visible = historyMode ? historyScroller : mainScroller;
            if (visible == null || visible.getVisibility() != View.VISIBLE) return;
            // 高度上限：内容装得下就长高，装不下才回到滑动（用户要求：自适应 + 有上限）
            int max = Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.7f);
            int width = panel.getWidth() > 0
                    ? panel.getWidth()
                    : activity.getResources().getDisplayMetrics().widthPixels;
            int wSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST);
            int hSpec = View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST);
            visible.measure(wSpec, hSpec);
            int need = Math.max(dp(60), Math.min(visible.getMeasuredHeight(), max));
            if (visible.getLayoutParams().height != need) {
                visible.getLayoutParams().height = need;
                panel.getLayoutParams().height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
                panel.requestLayout();
            }
        }
    };

    private void applyAdaptiveHeight() {
        if (panel == null) return;
        // 不再「重置 WRAP_CONTENT → 塌缩 → 再撑开」（每次文本更新都闪现一下）。
        // 改为离线量测内容自然高度：需要的高度与当前不一致时一次性调整，
        // 同高更新零重布局，文本就地替换不闪。多次调用合并成一次任务。
        panel.removeCallbacks(adaptiveHeightJob);
        panel.post(adaptiveHeightJob);
    }

    /** 应用面板不透明度：只作用于面板/按钮/释义区背景，文本保持全不透明。 */
    private void applyOpacity() {
        if (panel == null) return;
        int alpha = Math.round(255f * panelOpacity / 100f);
        if (panel.getBackground() != null) panel.getBackground().setAlpha(alpha);
        if (actionsRow != null) {
            for (int i = 0; i < actionsRow.getChildCount(); i++) {
                android.graphics.drawable.Drawable bg = actionsRow.getChildAt(i).getBackground();
                if (bg != null) bg.setAlpha(alpha);
            }
        }
    }

    /** 点按循环透明度档位并持久化。 */
    private void cycleOpacity() {
        int index = 0;
        for (int i = 0; i < OPACITY_STEPS.length; i++) {
            if (OPACITY_STEPS[i] == panelOpacity) index = i;
        }
        panelOpacity = OPACITY_STEPS[(index + 1) % OPACITY_STEPS.length];
        ttsPrefs().edit().putInt(KEY_OPACITY, panelOpacity).apply();
        if (opacityToggle != null) {
            opacityToggle.setText(activity.getString(
                    R.string.engine_ons_extract_opacity_fmt, panelOpacity));
        }
        applyOpacity();
    }

    // ------------------------------------------------------------------
    // 游戏设置弹窗（右缘设置键）：收纳文本框相关开关与透明度
    // ------------------------------------------------------------------

    /**
     * 设置弹窗（右缘设置键）：分区收纳——
     * 朗读（TTS/自动/MultiTTS/语速）、翻译（API 配置）、词典（管理/导入）、
     * 云同步（WebDAV/GitHub 配置）、显示（听力/历史/透明度）。
     * 弹窗内切换与面板内开关按钮同源同持久化，变更后同步面板按钮视觉。
     */
    private void showSettingsDialog() {
        // 每次打开都从**全局**读一次自动朗读状态：两个入口（游戏内/设置页）共用同一个值，
        // 避免"面板显示关、其实全局开着还在念"这类不同步（用户反馈的问题）。
        ttsAuto = OnsTtsEngines.autoRead(activity);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, dp(8), pad, 0);

        android.content.SharedPreferences p = ttsPrefs();

        box.addView(sectionHeader(R.string.engine_ons_settings_section_tts));
        box.addView(settingRow(R.drawable.ic_volume,
                R.string.engine_ons_extract_tts_toggle, ttsEnabled, () -> {
                    ttsEnabled = !ttsEnabled;
                    p.edit().putBoolean(KEY_TTS_ON, ttsEnabled).apply();
                    if (!ttsEnabled) stopTts();
                    return ttsEnabled;
                }));
        box.addView(settingRow(R.drawable.ic_autorenew,
                R.string.engine_ons_extract_auto_toggle, ttsAuto, () -> {
                    ttsAuto = !ttsAuto;
                    // **只写全局这一份**（面板不再单独存）：两个入口同步、开关真正生效。
                    OnsTtsEngines.setAutoRead(activity, ttsAuto);
                    p.edit().putBoolean(KEY_TTS_AUTO, ttsAuto).apply();   // 兼容旧键
                    return ttsAuto;
                }));
        box.addView(settingRow(R.drawable.ic_multi_tts,
                R.string.engine_ons_extract_multi_tts, multiTtsEnabled, () -> {
                    multiTtsEnabled = !multiTtsEnabled;
                    p.edit().putBoolean(KEY_TTS_MULTI, multiTtsEnabled).apply();
                    // 切换朗读引擎：销毁旧实例，下次朗读按新引擎重建
                    stopTts();
                    if (tts != null) {
                        try { tts.shutdown(); } catch (Throwable ignored) {}
                        tts = null;
                        ttsReady = false;
                        activeTtsEngine = null;
                    }
                    return multiTtsEnabled;
                }));

        // 语速：进度条（用户要求百分比类一律用进度条微调，下拉档位太粗）
        box.addView(SettingsWidgets.sliderRow(activity, R.drawable.ic_volume,
                activity.getString(R.string.engine_ons_extract_rate), 25, 300, ttsRate, "%d%%",
                value -> {
                    ttsRate = value;
                    p.edit().putInt(KEY_TTS_RATE, ttsRate).apply();
                    if (tts != null) {
                        try { tts.setSpeechRate(ttsRate / 100f); } catch (Throwable ignored) {}
                    }
                }));

        // TTS 流式播放开关（用户要求：设置里可开关，开启后 HTTP 类引擎边收边播降低延迟）。
        // 系统 TTS 的 speak() 本身就是边合成边播，此开关对它无影响。
        box.addView(SettingsWidgets.switchRow(activity, R.drawable.ic_volume,
                activity.getString(R.string.engine_ons_tts_streaming),
                OnsTtsEngines.streaming(activity),
                on -> {
                    OnsTtsEngines.setStreaming(activity, on);
                    android.util.Log.i(TAG, "tts streaming = " + on);
                }));

        // 翻译 / 词典 / 云同步设置已移至应用「设置」页（产品调整：游戏内不再放配置类条目）
        TextView movedHint = new TextView(activity);
        movedHint.setText(R.string.engine_ons_settings_moved_hint);
        movedHint.setTextColor(TEXT_DIM);
        movedHint.setTextSize(12);
        movedHint.setPadding(0, dp(12), 0, 0);
        box.addView(movedHint);

        box.addView(sectionHeader(R.string.engine_ons_settings_section_display));
        box.addView(settingRow(R.drawable.ic_headphones,
                R.string.engine_ons_extract_listening, listeningMode, () -> {
                    listeningMode = !listeningMode;
                    listeningRevealed = false;
                    p.edit().putBoolean(KEY_LISTENING, listeningMode).apply();
                    refresh();
                    return listeningMode;
                }));
        box.addView(settingRow(R.drawable.ic_history,
                R.string.engine_ons_extract_history, OnsHistoryOverlay.showing(), () -> {
                    toggleHistoryOverlay();
                    return OnsHistoryOverlay.showing();
                }));

        // 游戏音量：进度条（**只调本软件里的游戏音量，不动手机系统音量**）
        box.addView(SettingsWidgets.sliderRow(activity, R.drawable.ic_volume,
                activity.getString(R.string.engine_ons_settings_game_volume), 0, 100, gameVolume,
                "%d%%", value -> {
                    gameVolume = value;
                    p.edit().putInt(KEY_GAME_VOLUME, gameVolume).apply();
                    if (gameVolumeHook != null) {
                        try { gameVolumeHook.accept(gameVolume); } catch (Throwable ignored) {}
                    }
                }));

        // 透明度：进度条（1..100，连续可调）
        box.addView(SettingsWidgets.sliderRow(activity, R.drawable.ic_settings,
                activity.getString(R.string.engine_ons_extract_opacity), 10, 100, panelOpacity,
                "%d%%", value -> {
                    panelOpacity = value;
                    ttsPrefs().edit().putInt(KEY_OPACITY, panelOpacity).apply();
                    if (opacityToggle != null) {
                        opacityToggle.setText(activity.getString(
                                R.string.engine_ons_extract_opacity_fmt, panelOpacity));
                    }
                    applyOpacity();
                }));

        // 分区较多，内容整体可滚动（AlertDialog 不自动给自定义视图加滚动）
        android.widget.ScrollView scroller = new android.widget.ScrollView(activity);
        scroller.addView(box);
        new android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.engine_ons_settings_title)
                .setView(scroller)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** 分区标题（小号灰字）。 */
    private TextView sectionHeader(int labelRes) {
        TextView tv = new TextView(activity);
        tv.setText(labelRes);
        tv.setTextColor(TEXT_DIM);
        tv.setTextSize(11);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(12), 0, dp(2));
        tv.setLayoutParams(matchWrap());
        return tv;
    }

    /** 导航型设置行骨架：图标 + 标题（占满）+ 右侧值视图，点击行为由调用方绑定。 */
    private LinearLayout settingNavRow(int iconRes, int labelRes, TextView valueView) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        row.setClickable(true);

        android.widget.ImageView icon = new android.widget.ImageView(activity);
        icon.setImageResource(iconRes);
        icon.setColorFilter(TEXT_BUTTON);
        icon.setContentDescription(activity.getString(labelRes));
        int side = dp(22);
        row.addView(icon, new LinearLayout.LayoutParams(side, side));

        TextView label = new TextView(activity);
        label.setText(labelRes);
        label.setTextColor(TEXT_BODY);
        label.setTextSize(14);
        label.setPadding(dp(12), 0, 0, 0);
        row.addView(label, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (valueView != null) row.addView(valueView); // 无状态值的行为纯按钮（如翻译测试）
        return row;
    }

    private void styleNavValue(TextView value) {
        value.setTextColor(TEXT_DIM);
        value.setTextSize(13);
        value.setSingleLine(true);
        value.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        value.setMaxWidth(dp(170));
    }

    /** 档位数组下标（找不到返回 0），供下拉框选中项定位。 */
    private static int stepIndex(int[] steps, int value) {
        for (int i = 0; i < steps.length; i++) {
            if (steps[i] == value) return i;
        }
        return 0;
    }

    /** 设置行：图标 + 标题 + 开/关状态，点整行切换并同步面板开关按钮视觉。 */
    private LinearLayout settingRow(int iconRes, int labelRes, boolean value,
                                    java.util.function.Supplier<Boolean> onToggle) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        row.setClickable(true);

        android.widget.ImageView icon = new android.widget.ImageView(activity);
        icon.setImageResource(iconRes);
        icon.setColorFilter(TEXT_BUTTON);
        icon.setContentDescription(activity.getString(labelRes));
        int side = dp(22);
        row.addView(icon, new LinearLayout.LayoutParams(side, side));

        TextView label = new TextView(activity);
        label.setText(labelRes);
        label.setTextColor(TEXT_BODY);
        label.setTextSize(14);
        label.setPadding(dp(12), 0, 0, 0);
        row.addView(label, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView state = new TextView(activity);
        state.setTextSize(13);
        state.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(state);
        updateSettingState(state, value);
        row.setOnClickListener(v -> {
            boolean now = onToggle.get();
            updateSettingState(state, now);
            syncToggleVisuals();
        });
        return row;
    }

    private void updateSettingState(TextView state, boolean value) {
        state.setText(value ? R.string.engine_ons_state_on : R.string.engine_ons_state_off);
        state.setTextColor(value ? ACCENT : TEXT_DIM);
    }

    /** 面板内开关按钮视觉与当前状态对齐（设置弹窗切换后调用）。 */
    private void syncToggleVisuals() {
        restyleToggle(historyToggle, historyMode);
        restyleToggle(listeningToggle, listeningMode);
        restyleToggle(multiTtsToggle, multiTtsEnabled);
        restyleToggle(ttsToggle, ttsEnabled);
        restyleToggle(autoToggle, ttsAuto);
        if (opacityToggle != null) {
            opacityToggle.setText(activity.getString(
                    R.string.engine_ons_extract_opacity_fmt, panelOpacity));
        }
        applyOpacity();
    }

    private void restyleToggle(android.widget.ImageView toggle, boolean active) {
        if (toggle != null) {
            toggle.setBackground(rounded(active ? ACCENT : BG_DARK, dp(20)));
        }
    }

    /** 历史 → 独立悬浮窗：组装展示行（#序号 + 正文 + ♪语音名）并开/关。 */
    public void toggleHistoryOverlay() {
        if (OnsHistoryOverlay.showing()) {
            OnsHistoryOverlay.hide();
            return;
        }
        java.util.List<String> items = new java.util.ArrayList<>();
        synchronized (this) {
            for (int i = 0; i < history.size(); i++) {
                HistoryEntry e = history.get(i);
                items.add("#" + (history.size() - i) + " " + e.sentence
                        + (e.voiceName.isEmpty() ? "" : "  ♪" + e.voiceName));
            }
        }
        OnsHistoryOverlay.setOnClear(() -> {
            synchronized (this) {
                history.clear();
                lastHistorySentence = "";
            }
        });
        OnsHistoryOverlay.show(activity, items);
    }

    private void applyHistoryMode() {
        historyScroller.setVisibility(historyMode ? View.VISIBLE : View.GONE);
        mainScroller.setVisibility(historyMode ? View.GONE : View.VISIBLE);
        if (historyMode) rebuildHistory();
    }

    private void rebuildHistory() {
        historyList.removeAllViews();
        if (history.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText(activity.getString(R.string.engine_ons_extract_history_empty));
            empty.setTextColor(TEXT_PLACEHOLDER);
            empty.setTextSize(12);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(16), 0, dp(16));
            historyList.addView(empty, matchWrap());
            return;
        }
        for (int i = 0; i < history.size(); i++) {
            final HistoryEntry entry = history.get(i);
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.TOP);
            row.setPadding(0, dp(6), 0, dp(6));

            TextView idx = new TextView(activity);
            idx.setText("#" + (i + 1));
            idx.setTextColor(TEXT_PLACEHOLDER);
            idx.setTextSize(9);
            idx.setTypeface(Typeface.MONOSPACE);
            idx.setPadding(0, dp(2), dp(8), 0);
            row.addView(idx, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            TextView body = new TextView(activity);
            body.setText(entry.sentence);
            body.setTextColor(TEXT_ON_DARK);
            body.setTextSize(13);
            body.setTypeface(Typeface.DEFAULT_BOLD);
            body.setLineSpacing(0f, 1.4f);
            body.setOnClickListener(v -> playHistoryEntry(entry));
            row.addView(body, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            if (!entry.voiceName.isEmpty()) {
                // 语音标记：点该条目回放当句原声而非 TTS
                TextView voiceMark = new TextView(activity);
                voiceMark.setText("♪");
                voiceMark.setTextColor(DEF_ACCENT);
                voiceMark.setTextSize(11);
                voiceMark.setPadding(dp(6), dp(2), 0, 0);
                row.addView(voiceMark, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }
            historyList.addView(row, matchWrap());

            if (i < history.size() - 1) {
                View divider = new View(activity);
                divider.setBackgroundColor(DIVIDER_DARK);
                historyList.addView(divider, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
            }
        }
        historyList.post(() -> historyScroller.smoothScrollTo(0, historyList.getHeight()));
    }

    /** 候选显示序：0=自动(状态机还原)，1..n=候选源；按游戏持久化。 */
    private int candidateIndex;
    private boolean initializedCandidatePref;


    /**
     * 候选选择悬浮框（LunaTranslator 式「钩子后选最适配」）：
     * 列出 自动还原 + 各候选源（截断预览），单选即生效并按游戏记忆。
     */
    private void showCandidateDialog() {
        if (facade == null) return;
        java.util.List<String> cands = facade.getSentenceCandidates();
        if (cands.isEmpty()) {
            toast(R.string.engine_ons_extract_candidate_none);
            return;
        }
        String[] items = new String[cands.size() + 1];
        items[0] = activity.getString(R.string.engine_ons_extract_candidate_auto);
        for (int i = 0; i < cands.size(); i++) {
            String c = cands.get(i);
            items[i + 1] = (i == 0 ? activity.getString(
                    R.string.engine_ons_extract_candidate_raw) + " " : "")
                    + (c.length() > 32 ? c.substring(0, 32) + "…" : c);
        }
        new android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.engine_ons_extract_candidate_pick)
                .setSingleChoiceItems(items, Math.min(candidateIndex, items.length - 1),
                        (d, which) -> {
                            candidateIndex = which;
                            ttsPrefs().edit()
                                    .putInt("cand_idx_" + facade.gameDisplayName(), which)
                                    .apply();
                            d.dismiss();
                            refresh();
                        })
                .show();
    }

    /** M6：当前命中的展示文案（如「词形还原 食べる · -te→v1」/「查表还原 走る」；普通命中为空）。 */
    private String matchDetailText() {
        if ("table-deinflect".equals(defMatchType)) {
            return defLemma.isEmpty() ? "查表还原" : "查表还原 " + defLemma;
        }
        if (defMatchType != null && defMatchType.startsWith("deinflect")) {
            StringBuilder sb = new StringBuilder("词形还原");
            if (!defLemma.isEmpty()) sb.append(' ').append(defLemma);
            if (!defPath.isEmpty()) sb.append(" · ").append(defPath);
            return sb.toString();
        }
        return "";
    }

    private void refresh() {
        ExtractFacade bridge = facade;
        String sentence = bridge.getSentenceText();
        if (sentence.isEmpty()) sentence = bridge.getPageText();
        // 候选切换（LunaTranslator 式「选最适配」）：0=自动(状态机还原)，1..n=候选源
        java.util.List<String> cands = bridge.getSentenceCandidates();
        if (!initializedCandidatePref && facade != null) {
            initializedCandidatePref = true;
            candidateIndex = ttsPrefs()
                    .getInt("cand_idx_" + facade.gameDisplayName(), 0);
        }
        if (candidateIndex > 0 && !cands.isEmpty()) {
            String pick = cands.get(Math.min(candidateIndex - 1, cands.size() - 1));
            if (pick != null && !pick.isEmpty()) sentence = pick;
        }
        // 历史累计：句增量非空且非重复才入列；翻句重置听力揭示态与选词
        if (!sentence.isEmpty() && !sentence.equals(lastHistorySentence)) {
            // 部分行被完整行替换：打字中途的停顿 flush 会先产生半句，
            // 完整句到达时若是其延伸则覆盖（不新增）
            if (!history.isEmpty()
                    && sentence.startsWith(history.get(history.size() - 1).sentence)) {
                history.remove(history.size() - 1);
            }
            history.add(new HistoryEntry(sentence, bridge.getVoiceName()));
            if (history.size() > HISTORY_MAX) history.remove(0);
            if (OnsHistoryOverlay.showing()) {
                HistoryEntry he = history.get(history.size() - 1);
                OnsHistoryOverlay.addEntry(activity, "#" + HISTORY_MAX + " " + he.sentence
                        + (he.voiceName.isEmpty() ? "" : "  ♪" + he.voiceName));
            }
            // 语音不落盘（用户要求「音频不用保留」）：回放走档案按名补读 / TTS
            lastHistorySentence = sentence;
            listeningRevealed = false;
            selectedToken = -1;
            dictRequestId++; // 翻句使在途查词失效
            translateRequestId++; // 翻句使在途翻译失效
            defGroups = null;
            matchedRange = null; // 旧句高亮区间不跨句
            defSentence = null;
            defMatchType = "";
            defPath = "";
            defLemma = "";
            if (transView != null) {
                transView.setTag(null); // 翻句清掉旧译文
                transView.setText("");
            }
        }
        // 用户要求：**切换对白时上一条 TTS 立即停止**，绝不允许两条语音同时出声
        // （否则 TTS 会盖住游戏语音，听不见对白语音）。
        //
        // 关键点：这个停止必须放在"入口"处、且在所有提前 return 之前 ——
        // autoPlayForNewSentence() 内部有多处 early return（自动朗读关闭 / 补念分支 /
        // 同句去重），把 stopTts() 放在里面时，"切到有语音的新句"这类情况根本走不到，
        // 于是上一条 TTS 继续念、压住新句的游戏语音。
        //
        // 只对"真正换了句"生效：同一句逐字/逐段变长（新文本以上一次为前缀）不打断，
        // 否则会把自己念到一半的同一句掐掉。
        {
            final String cur = sentence == null ? "" : sentence;
            final String ref = pendingSpokenSentence != null
                    ? pendingSpokenSentence
                    : (lastSpokenSentence == null ? "" : lastSpokenSentence);
            if (!cur.isEmpty() && !ref.isEmpty() && !cur.equals(ref) && !cur.startsWith(ref)) {
                android.util.Log.i(TAG, "line switched → stop previous TTS immediately");
                stopTts();
                main.removeCallbacks(autoSpeakJob);
                pendingSpokenSentence = null;
                pendingSpokenSuffix = null;
            }
        }
        // 自动朗读在面板隐藏时也要生效（TTS 直呼线程安全）；配对语音由游戏播放，不重复播。
        // 生效判定在 autoPlayForNewSentence 内（面板「自动」快调 OR 设置页「自动朗读」全局开关）
        autoPlayForNewSentence(sentence);
        // 有游戏语音 → **立刻掐断可能已经开念的 TTS**。
        // 真机现象（Artemis）：语音信息常常比文本晚到（extract 事件 voice= 先为空、稍后才有），
        // 自动朗读会抢在语音之前开口，听起来就是"该有语音的句子却念了 TTS"。
        // 这条兜底：一旦这一句确认有语音，就把已开念的 TTS 停掉并作废待念队列。
        if (!bridge.getVoiceName().isEmpty()) {
            stopTts();
            main.removeCallbacks(autoSpeakJob);
            pendingSpokenSentence = null;
            pendingSpokenSuffix = null;
            if (sentence != null && !sentence.isEmpty()) lastSpokenSentence = sentence;
        }
        if (!expanded || panel == null) return;
        final String sentenceFinal = sentence;
        final String voiceName = bridge.getVoiceName();
        final boolean hideForListening = listeningMode && !voiceName.isEmpty() && !listeningRevealed;
        final List<OnsDictStore.Group> groups = defGroups;
        final String lookupTerm = defSentence;
        final boolean hasTrans = transView.getTag() != null;
        // 释义路由到顶部独立悬浮窗（查词命中即显示；翻句 defGroups=null 时收起）。
        // 不受面板展开状态影响：悬浮窗独立于剧情文本框（jidoujisho 式顶部释义卡）。
        // 标题只显示词典条目的规范词形（首个分组 term）——不是扫描匹配串，
        // 更不是点击位置之后的整段原文（用户约定：查询词作标题）。
        if (groups != null && !groups.isEmpty()) {
            String overlayTitle = lookupTerm;
            for (OnsDictStore.Group g : groups) {
                if (g != null && g.term != null && !g.term.isEmpty()) {
                    overlayTitle = g.term;
                    break;
                }
            }
            OnsDictOverlay.show(activity, overlayTitle, groups, this::makeWordCard,
                    matchDetailText());
        } else {
            OnsDictOverlay.hide();
        }
        main.post(() -> {
            if (!expanded) return;
            java.util.List<String> candsNow = facade != null
                    ? facade.getSentenceCandidates() : java.util.Collections.emptyList();
            statusView.setText((candsNow.size() >= 1 ? "⇄候选 " : "")
                    + (voiceName.isEmpty()
                    ? activity.getString(R.string.engine_ons_extract_voice_none_line)
                    : activity.getString(R.string.engine_ons_extract_voice_line, voiceName)));
            sentenceView.setVisibility(hideForListening ? View.GONE : View.VISIBLE);
            listeningView.setVisibility(hideForListening ? View.VISIBLE : View.GONE);
            if (hideForListening) {
                listeningView.setText(R.string.engine_ons_extract_listening_hidden);
            } else if (!historyMode) {
                renderSentence(sentenceFinal);
            }
            transView.setVisibility(hasTrans ? View.VISIBLE : View.GONE);
            if (historyMode) rebuildHistory();
            applyAdaptiveHeight(); // 内容增减（释义/译文/翻句）后重算自适应高度
        });
    }

    // ------------------------------------------------------------------
    // 选词与查词（TrackReader 对齐：text-utils splitWords 选词单位规则 +
    // dictionary-query decrementalScan 递减扫描——CJK 逐字递减、空格分词
    // 语言两阶段扫描；命中跨度高亮，说话人行/多行通用）
    // ------------------------------------------------------------------

    /** 当前句文本（renderSentence 时缓存，扫描线程用）。 */
    private String currentSentence = "";
    /** 命中跨度（递减扫描结果；非空时高亮它而非选中单元）。 */
    private int[] matchedRange;
    /** 命中词原文：打字机流走（文本变化）时按内容在新文本中重定位高亮。 */
    private String matchedText = "";

    /** 最近一次成功译文（制卡「译文」槽位来源；翻句不清，仅新翻译覆盖/失败清空）。 */
    private volatile String lastTranslation = "";

    private static boolean isCjkChar(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0xF900 && c <= 0xFAFF) || (c >= 0x3040 && c <= 0x30FF)
                || (c >= 0xAC00 && c <= 0xD7AF);
    }

    /** CJK 判定（TrackReader isCJK：CJK 字符占比 > 50%）。 */
    private static boolean isCjkText(String text) {
        if (text == null || text.isEmpty()) return false;
        int cjk = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isCjkChar(text.charAt(i))) cjk++;
        }
        return cjk * 2 > text.length();
    }

    /** 纯标点/空白候选拒判（TrackReader tryMatchTermOnly 的正则等价）。 */
    private static boolean isTokenSeparator(char c) {
        if (Character.isWhitespace(c)) return true;
        return "、。！？，,.!?::;；()（）「」『』《》〈〉・〜~—–…※♪"
                .indexOf(c) >= 0;
    }

    private static boolean isRejectable(String s) {
        if (s == null || s.isEmpty()) return true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c) && !isTokenSeparator(c)) return false;
        }
        return true;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c);
    }

    /** 空格分词语言的词跨度（内部撇号/连字符保留：don't / well-being）。 */
    private static java.util.List<int[]> buildWordSpans(String text) {
        java.util.List<int[]> spans = new java.util.ArrayList<>();
        int i = 0, n = text.length();
        while (i < n) {
            if (isWordChar(text.charAt(i))) {
                int j = i + 1;
                while (j < n) {
                    char c = text.charAt(j);
                    if (isWordChar(c)) { j++; continue; }
                    if ((c == '\'' || c == '\u2019' || c == '-') && j + 1 < n
                            && isWordChar(text.charAt(j + 1))) { j += 2; continue; }
                    break;
                }
                spans.add(new int[]{i, j});
                i = j;
            } else {
                i++;
            }
        }
        return spans;
    }

    /** 找包含 pos 的词；落在空白则取右侧最近词（TrackReader findWordIndex）。 */
    private static int findWordIndex(java.util.List<int[]> spans, int pos) {
        for (int i = 0; i < spans.size(); i++) {
            if (pos >= spans.get(i)[0] && pos < spans.get(i)[1]) return i;
        }
        for (int i = 0; i < spans.size(); i++) {
            if (spans.get(i)[0] >= pos) return i;
        }
        return spans.size() - 1;
    }

    private void renderSentence(String text) {
        unitRanges.clear();
        // 懒渲染：超长页只保留尾部 240 字符参与选词（完整内容走历史悬浮窗），
        // 防止巨型页每字符建 ClickableSpan 拖垮主线程（此前 ANR 根因）
        if (text.length() > 240) text = text.substring(text.length() - 240);
        // 同句重渲染（打字机 flush）保留扫描高亮与点选状态；翻句由调用方显式清位。
        // 文本流走时按匹配词原文重定位高亮（旧跨度失位不再显示一串错误内容）：
        // 从旧起点附近找匹配词在新文本中的位置，找不到才清空
        if (!text.equals(currentSentence)) {
            if (matchedRange != null && !matchedText.isEmpty()) {
                int from = Math.max(0, Math.min(matchedRange[0] - 8, text.length() - 1));
                int at = text.indexOf(matchedText, Math.max(0, from));
                if (at >= 0) {
                    matchedRange = new int[]{at, at + matchedText.length()};
                } else {
                    matchedRange = null;
                    selectedToken = -1;
                }
            } else {
                matchedRange = null;
                selectedToken = -1;
            }
        }
        currentSentence = text;
        SpannableString ss = new SpannableString(text);
        boolean cjk = isCjkText(text);
        int n = text.length();
        int i = 0;
        while (i < n) {
            int start, end;
            if (cjk) {
                // CJK：逐字拆为单字选择单位（跳过空白）
                if (Character.isWhitespace(text.charAt(i))) { i++; continue; }
                start = i; end = i + 1;
            } else {
                // 空格分词语言：按空格划分词作为点击单位（原始词，含附着标点）
                while (i < n && Character.isWhitespace(text.charAt(i))) i++;
                if (i >= n) break;
                start = i;
                int j = i + 1;
                while (j < n && !Character.isWhitespace(text.charAt(j))) j++;
                end = j;
            }
            i = end;
            final int idx = unitRanges.size();
            final int s0 = start, e0 = end;
            unitRanges.add(new int[]{s0, e0});
            ss.setSpan(new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    selectUnit(idx, s0);
                }

                @Override
                public void updateDrawState(TextPaint ds) {
                    // 深色面板上的正文必须纯白：TEXT_BODY 近黑在 0xE6101010 底上不可读
                    ds.setColor(TEXT_ON_DARK);
                    ds.setUnderlineText(false);
                }
            }, s0, e0, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        sentenceSpan = ss;
        applyTokenSelection();
    }

    private void applyTokenSelection() {
        if (sentenceSpan == null) return;
        for (BackgroundColorSpan span : sentenceSpan.getSpans(
                0, sentenceSpan.length(), BackgroundColorSpan.class)) {
            sentenceSpan.removeSpan(span);
        }
        int[] r = matchedRange;
        if (r == null && selectedToken >= 0 && selectedToken < unitRanges.size()) {
            r = unitRanges.get(selectedToken);
        }
        if (r != null) {
            sentenceSpan.setSpan(new BackgroundColorSpan(SELECT_BG),
                    r[0], r[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        sentenceView.setText(sentenceSpan);
    }

    /** 点选单元：高亮该单元，并以其起点做递减扫描查词。 */
    private void selectUnit(int idx, int start) {
        selectUnit(idx, start, false);
    }

    /**
     * 选中词元并查词。
     *
     * @param silentMiss true = 查不到词元时不弹「未查到」提示（手柄选词/确认查词的路径，
     *                   用户要求手柄查词未命中不要弹窗；面板状态行仍会给轻提示）
     */
    private void selectUnit(int idx, int start, boolean silentMiss) {
        if (idx < 0 || idx >= unitRanges.size() || sentenceSpan == null) return;
        selectedToken = idx;
        matchedRange = null;
        matchedText = "";
        applyTokenSelection();
        scanFrom(start, silentMiss);
    }

    /** 只高亮词元、不查词（手柄「按确认键才查词」模式下的移动）。 */
    private void highlightUnit(int idx) {
        if (idx < 0 || idx >= unitRanges.size() || sentenceSpan == null) return;
        selectedToken = idx;
        matchedRange = null;
        matchedText = "";
        applyTokenSelection();
    }

    /**
     * 上下行同列选词：把选区在**多行文本**里按行上下移动，尽量落在同一列
     * （用当前词元中心的水平位置在目标行里找覆盖它的词元，找不到就取最近的）。
     *
     * 单行文本 / 已经到首末行 / 目标行没有词元时**回退为滚动**——这样 ↑↓ 在
     * 单行面板里依然是有用的滚动键，不会变成「按了没反应」。
     *
     * @return true = 已按上下行选词处理（调用方不要再滚动）
     */
    private boolean moveTokenVertical(int dir) {
        if (historyMode || unitRanges.isEmpty() || sentenceSpan == null || sentenceView == null) {
            android.util.Log.i(TAG, "vnav bail: no tokens span=" + (sentenceSpan != null)
                    + " ranges=" + unitRanges.size() + " view=" + (sentenceView != null));
            return false;
        }
        android.text.Layout layout = sentenceView.getLayout();
        if (layout == null) {
            android.util.Log.i(TAG, "vnav bail: layout null");
            return false;
        }
        int cur = selectedToken >= 0 ? selectedToken : 0;
        if (cur < 0 || cur >= unitRanges.size()) {
            android.util.Log.i(TAG, "vnav bail: cur=" + cur + " ranges=" + unitRanges.size());
            return false;
        }
        int[] range = unitRanges.get(cur);
        int line = layout.getLineForOffset(range[0]);
        int targetLine = line + dir;
        if (targetLine < 0 || targetLine >= layout.getLineCount()) {
            android.util.Log.i(TAG, "vnav bail: line=" + line + " target=" + targetLine
                    + " lines=" + layout.getLineCount());
            return false;
        }

        // 当前词元中心的水平位置（相对行首；左对齐文本下即为「列」）
        float center = (layout.getPrimaryHorizontal(range[0])
                + layout.getPrimaryHorizontal(range[1])) / 2f
                + layout.getLineLeft(line);
        int best = -1;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < unitRanges.size(); i++) {
            int[] r = unitRanges.get(i);
            if (layout.getLineForOffset(r[0]) != targetLine) continue;
            float h0 = layout.getPrimaryHorizontal(r[0]) + layout.getLineLeft(targetLine);
            float h1 = layout.getPrimaryHorizontal(r[1]) + layout.getLineLeft(targetLine);
            if (center >= h0 && center <= h1) { // 同列命中，直接用
                best = i;
                bestDistance = 0f;
                break;
            }
            float mid = (h0 + h1) / 2f;
            float distance = Math.abs(mid - center);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        if (best < 0) {
            android.util.Log.i(TAG, "vnav bail: no token on line " + targetLine);
            return false; // 目标行没有可选的词元 → 交给滚动
        }
        android.util.Log.i(TAG, "vnav " + line + "->" + targetLine
                + " token " + cur + "->" + best + " x=" + Math.round(center));
        if (com.core.engine.PanelOptions.isAutoLookup(activity)) {
            selectUnit(best, unitRanges.get(best)[0], true);
        } else {
            highlightUnit(best);
        }
        return true;
    }

    /** 后台扫描：结果刷新到释义区；代次号丢弃过期结果（快速连按选词）。
     *  扫描循环内也查代次——过期扫描立刻中止，不再空跑几十次词典查询；
     *  全程捕获异常：词典库异常（损坏/满盘）降级为无结果，不杀进程。 */
    private void scanFrom(final int startPos) {
        scanFrom(startPos, false);
    }

    private void scanFrom(final int startPos, boolean silentMiss) {
        final int requestId = ++dictRequestId;
        final String text = currentSentence;
        scanExecutor.execute(() -> {
            try {
                if (!OnsDictStore.get().hasDictionary()) {
                    main.post(() -> toast(R.string.engine_ons_extract_dict_none));
                    return;
                }
                final long scanStart = System.currentTimeMillis();
                final boolean deinflect = OnsDictStore.isDeinflectEnabled(activity);
                ScanHit hit = isCjkText(text)
                        ? scanByChar(text, startPos, requestId, deinflect)
                        : scanByWordTwoPhase(text, startPos, requestId, deinflect);
                if (requestId != dictRequestId) return;
                final long scanMs = System.currentTimeMillis() - scanStart;
                if (hit == null) {
                    com.core.diag.DiagLog.debug("dict", "miss in " + scanMs + "ms len=" + text.length());
                    // 未命中一律**不弹提示**（用户要求：没有释义用户自己就明白了）。
                    // 只有「词典库为空」这种需要用户处理的情况才提示。
                    boolean noDict = !OnsDictStore.get().hasDictionary();
                    com.core.diag.DiagLog.debug("dict", "miss(silent) noDict=" + noDict);
                    if (noDict) {
                        main.post(() -> toast(R.string.engine_ons_extract_dict_none));
                    } else {
                        main.post(this::refresh);
                    }
                    return;
                }
                defGroups = hit.groups;
                defSentence = hit.matched;
                defMatchType = hit.matchType;
                defPath = hit.path;
                defLemma = hit.lemma;
                matchedRange = hit.range;
                matchedText = hit.matched;
                com.core.diag.DiagLog.debug("dict", "hit: " + hit.matched
                        + " groups=" + hit.groups.size() + " in " + scanMs + "ms"
                        + " match=" + hit.matchType
                        + (hit.path.isEmpty() ? "" : " path=" + hit.path));
                main.post(this::refresh);
            } catch (Throwable t) {
                Log.w(TAG, "dict scan failed", t);
            }
        });
    }

    private static final class ScanHit {
        final String matched;
        final int[] range;
        final List<OnsDictStore.Group> groups;
        /** M6：命中方式（term/reading/table-deinflect/deinflect-term/deinflect-reading）。 */
        final String matchType;
        /** M6：还原路径（如 `-te→v1`；非还原命中为空）。 */
        final String path;
        /** M6：还原后的词典形（非还原命中为空）。 */
        final String lemma;

        ScanHit(String matched, int[] range, List<OnsDictStore.Group> groups) {
            this(matched, range, groups, "term", "", "");
        }

        ScanHit(String matched, int[] range, List<OnsDictStore.Group> groups,
                String matchType, String path, String lemma) {
            this.matched = matched;
            this.range = range;
            this.groups = groups;
            this.matchType = matchType == null ? "" : matchType;
            this.path = path == null ? "" : path;
            this.lemma = lemma == null ? "" : lemma;
        }
    }

    // ---- 通用候选匹配器（TrackReader tryMatchCandidate 对齐）----
    //  ① 精确 term → ② 精确 reading（日语）→ ③ 查表还原（M2 语言包，先于规则）
    //  → ④ 规则还原→term → ⑤ 规则还原→reading。matchedText = 候选全文（高亮面），
    //  命中词条形走 deinflectedTerm/卡片标题。递减由扫描循环驱动，此处不做前缀缩短。

    private static final class CandidateMatch {
        final String matchedText;
        final List<OnsDictStore.Group> groups;
        final String deinflectedTerm;   // 还原原形（非还原命中为 null）
        /** M6：命中方式（term/reading/table-deinflect/deinflect-term/deinflect-reading）。 */
        final String matchType;
        /** M6：还原路径（如 `-te→v1`；查表还原为 `table`，非还原命中为空）。 */
        final String deinflectionPath;

        CandidateMatch(String matchedText, List<OnsDictStore.Group> groups, String deinflectedTerm) {
            this(matchedText, groups, deinflectedTerm,
                    deinflectedTerm == null ? "term" : "deinflect-term",
                    deinflectedTerm == null ? "" : "");
        }

        CandidateMatch(String matchedText, List<OnsDictStore.Group> groups,
                       String deinflectedTerm, String matchType) {
            this(matchedText, groups, deinflectedTerm, matchType, "");
        }

        CandidateMatch(String matchedText, List<OnsDictStore.Group> groups,
                       String deinflectedTerm, String matchType, String deinflectionPath) {
            this.matchedText = matchedText;
            this.groups = groups;
            this.deinflectedTerm = deinflectedTerm;
            this.matchType = matchType == null ? "" : matchType;
            this.deinflectionPath = deinflectionPath == null ? "" : deinflectionPath;
        }
    }

    /** 候选含假名才查读音（TrackReader skipReading = scope!==ja 的无 scope 替代）。 */
    private static boolean hasKana(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x3041 && c <= 0x309F) || (c >= 0x30A1 && c <= 0x30FF)) return true;
        }
        return false;
    }

    /** M4：该候选生效的还原语言（显式设置优先；auto 时按脚本 + 汉字归属判定）。 */
    private String effectiveLang(String cand) {
        if (!"auto".equals(dictQueryLang)) return dictQueryLang;
        return OnsDeinflector.detectLanguage(cand, dictHanPriority);
    }

    private CandidateMatch tryMatchCandidate(String cand, boolean deinflect) {
        if (isRejectable(cand)) return null;
        // M4：语言决定还原规则与是否查读音（对齐参考 skipReading = scope !== 'ja'）；
        // 单字候选仍不查读音——假名单字几乎没有词典价值，却占扫描层数的大头。
        String lang = effectiveLang(cand);
        boolean tryReading = dictReading && "ja".equals(lang) && cand.length() > 1;
        List<String> keys = new ArrayList<>();
        keys.add(cand);
        List<OnsDeinflector.Result> roots = new ArrayList<>();
        if (deinflect) {
            // M1：还原候选按 score 升序（同分 term 字典序），替代原先的字典序列表
            for (OnsDeinflector.Result r : OnsDeinflector.deinflectDetailed(cand, lang)) {
                if (!r.term.equals(cand) && !keys.contains(r.term)) {
                    keys.add(r.term);
                    roots.add(r);
                }
            }
        }
        // B3：原形 + 全部还原形合并成一次 IN 查询（term 列、reading 列各一次），
        // 替代原来的逐候选逐形串行查询（每次都有游标/JNI 往返）。
        // 命中优先级保持原语义：①term(cand) ②reading(cand) ③查表还原 ④term(root) ⑤reading(root)
        OnsDictStore store = OnsDictStore.get();
        java.util.Map<String, List<OnsDictStore.Group>> termHits = store.batchTermLookup(keys, 5);
        List<OnsDictStore.Group> g = termHits.get(cand);
        if (g != null && !g.isEmpty()) return new CandidateMatch(cand, g, null);
        java.util.Map<String, List<OnsDictStore.Group>> readingHits =
                java.util.Collections.emptyMap();
        if (tryReading) {
            readingHits = store.batchReadingLookup(keys, 5);
            g = readingHits.get(cand);
            if (g != null && !g.isEmpty()) return new CandidateMatch(cand, g, null);
        }
        // ③ 查表还原（M2 语言包）：优先于规则还原；未安装/停用/未命中即跳过
        if (deinflect) {
            try {
                java.util.Map<String, List<String>> tableLemmas =
                        com.core.ons.deinflect.DeinflectPackStore.get()
                                .lookupTableLemmasBatch(lang, keys);
                List<String> candLemmas = tableLemmas.get(cand);
                if (candLemmas != null && !candLemmas.isEmpty()) {
                    java.util.Map<String, List<OnsDictStore.Group>> lemmaHits =
                            store.batchTermLookup(candLemmas, 5);
                    for (String lemma : candLemmas) {
                        if (lemma.equals(cand)) continue;
                        g = lemmaHits.get(lemma);
                        if (g != null && !g.isEmpty()) {
                            return new CandidateMatch(cand, g, lemma, "table-deinflect");
                        }
                    }
                }
            } catch (Throwable t) {
                android.util.Log.w("OnsExtractPanel", "table deinflect failed cand=" + cand, t);
            }
        }
        if (deinflect) {
            // ④ 规则还原→term ⑤ 规则还原→reading（带还原路径，供面板/制卡显示）
            for (OnsDeinflector.Result r : roots) {
                String root = r.term;
                g = termHits.get(root);
                if (g != null && !g.isEmpty()) {
                    return new CandidateMatch(cand, g, root, "deinflect-term", r.pathString());
                }
                if (tryReading) {
                    g = readingHits.get(root);
                    if (g != null && !g.isEmpty()) {
                        return new CandidateMatch(cand, g, root, "deinflect-reading", r.pathString());
                    }
                }
            }
            // ⑥ 预处理变体字面命中（兜底）：中文全角/NFKC、半角カナ ﾂﾞ 这类规范化变体走这里
            try {
                java.util.List<String> variants = OnsDeinflector.preprocessVariants(cand, lang);
                if (!variants.isEmpty()) {
                    java.util.Map<String, List<OnsDictStore.Group>> variantHits =
                            store.batchTermLookup(variants, 5);
                    for (String variant : variants) {
                        g = variantHits.get(variant);
                        if (g != null && !g.isEmpty()) {
                            return new CandidateMatch(cand, g, variant, "variant", "preprocess");
                        }
                    }
                    if (tryReading) {
                        java.util.Map<String, List<OnsDictStore.Group>> variantReadingHits =
                                store.batchReadingLookup(variants, 5);
                        for (String variant : variants) {
                            g = variantReadingHits.get(variant);
                            if (g != null && !g.isEmpty()) {
                                return new CandidateMatch(cand, g, variant,
                                        "variant-reading", "preprocess");
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                android.util.Log.w("OnsExtractPanel", "variant lookup failed cand=" + cand, t);
            }
        }
        return null;
    }

    /** CJK 逐字递减扫描（TrackReader _decrementalScanByChar）：从 startPos 起逐次
     *  缩短尾部长度，首个命中即返回。requestId 过期（新一轮点选/翻句）立即中止。 */
    private ScanHit scanByChar(String text, int startPos, int requestId, boolean deinflect) {
        final int maxLen = text.length() - startPos;
        for (int len = maxLen; len >= 1; len--) {
            if (requestId != dictRequestId) return null;
            String cand = text.substring(startPos, startPos + len);
            CandidateMatch m = tryMatchCandidate(cand, deinflect);
            if (m != null) {
                return new ScanHit(m.matchedText,
                        new int[]{startPos, startPos + m.matchedText.length()}, m.groups,
                        m.matchType, m.deinflectionPath, m.deinflectedTerm);
            }
        }
        return null;
    }

    /**
     * 空格分词语言两阶段扫描（TrackReader _decrementalScanByWordTwoPhase）：
     * 阶段一：首词词形还原，构建「原句 + 还原句」车队；
     * 阶段二：每辆车做纯精确 term 递减扫描（无还原、无读音——还原只发生在阶段一）。
     * 命中映射回原文词跨度（首词 1:1 替换，词数对齐），matchedText = 原文面。
     */
    private ScanHit scanByWordTwoPhase(String text, int startPos, int requestId, boolean deinflect) {
        java.util.List<int[]> origSpans = buildWordSpans(text);
        if (origSpans.isEmpty()) return null;
        int startIdx = findWordIndex(origSpans, startPos);
        if (startIdx < 0) return null;
        String firstWord = text.substring(origSpans.get(startIdx)[0], origSpans.get(startIdx)[1]);

        // 车队：原句 + 首词替换为各还原形的句子（并行记录该车的还原结果，供 M6 显示路径）
        java.util.List<String> vehicles = new java.util.ArrayList<>();
        java.util.List<OnsDeinflector.Result> vehicleRoots = new java.util.ArrayList<>();
        vehicles.add(text);
        vehicleRoots.add(null);
        if (deinflect) {
            for (OnsDeinflector.Result r : OnsDeinflector.deinflectDetailed(
                    firstWord, effectiveLang(firstWord))) {
                if (r.term.equals(firstWord)) continue;
                String replaced = text.substring(0, origSpans.get(startIdx)[0]) + r.term
                        + text.substring(origSpans.get(startIdx)[1]);
                if (!vehicles.contains(replaced)) {
                    vehicles.add(replaced);
                    vehicleRoots.add(r);
                }
            }
        }

        for (int v = 0; v < vehicles.size(); v++) {
            String vehicle = vehicles.get(v);
            OnsDeinflector.Result vehicleRoot = vehicleRoots.get(v);
            if (requestId != dictRequestId) return null;
            java.util.List<int[]> vSpans = buildWordSpans(vehicle);
            int vi = findWordIndex(vSpans, origSpans.get(startIdx)[0]);
            if (vi < 0) continue;
            for (int e = vSpans.size() - 1; e >= vi; e--) {
                if (requestId != dictRequestId) return null;
                String cand = vehicle.substring(vSpans.get(vi)[0], vSpans.get(e)[1]);
                if (isRejectable(cand)) continue;
                // 纯精确 term（tryMatchTermOnly）
                List<OnsDictStore.Group> g = OnsDictStore.get().searchTermExact(cand, 5);
                if (!g.isEmpty()) {
                    // 还原句与原句首词 1:1 替换、词数对齐：按车辆词序折回原句
                    int origEndIdx = Math.min(e, origSpans.size() - 1);
                    int[] range = {origSpans.get(startIdx)[0], origSpans.get(origEndIdx)[1]};
                    return new ScanHit(text.substring(range[0], range[1]), range, g,
                            vehicleRoot == null ? "term" : "deinflect-term",
                            vehicleRoot == null ? "" : vehicleRoot.pathString(),
                            vehicleRoot == null ? "" : vehicleRoot.term);
                }
            }
        }
        return null;
    }

    private void dismissDef() {
        defGroups = null;
        defSentence = null;
        defMatchType = "";
        defPath = "";
        defLemma = "";
        OnsDictOverlay.hide();
        refresh();
    }

    // ---- AI Agent 桥接（OnsAgentDialog ToolHost 回调）----

    String getCurrentSentenceForAgent() {
        String s = facade.getSentenceText();
        return s.isEmpty() ? facade.getPageText() : s;
    }

    String getGameDisplayName() {
        return facade.gameDisplayName();
    }

    String agentLookupWord(String word) {
        List<OnsDictStore.Group> groups = OnsDictStore.get().search(word, 3);
        if (groups.isEmpty()) return "未查到「" + word + "」";
        StringBuilder sb = new StringBuilder();
        for (OnsDictStore.Group g : groups) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(g.term);
            if (!g.reading.isEmpty() && !g.reading.equals(g.term))
                sb.append("【").append(g.reading).append("】");
            for (String gloss : g.glosses) sb.append('\n').append(gloss);
        }
        return sb.toString();
    }

    String agentTranslate(String text, String targetLang) {
        // 同步调用翻译引擎（agent 线程安全）
        final String[] result = {null};
        final CountDownLatch latch = new CountDownLatch(1);
        OnsTranslateClient.translateWithEngine(activity, text, (translated, error) -> {
            result[0] = translated != null ? translated : "翻译失败: " + error;
            latch.countDown();
        });
        try { latch.await(30, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        return result[0] != null ? result[0] : "翻译超时";
    }

    void agentSpeak(String text) {
        speakText(text);
    }

    // ------------------------------------------------------------------
    // 词卡（参考 anki 项目 word scheme：Word/Reading/Meaning/Sentence）
    // ------------------------------------------------------------------

    /** TTS 静默合成（MultiTTS 已启用才有值；失败返回 null）——制卡音频槽位用。 */
    private byte[] ttsSynthesizeQuiet(String text) {
        if (!multiTtsEnabled || text == null || text.isEmpty()) return null;
        String engine = OnsTtsEngines.engine(activity);
        if (OnsTtsEngines.ENGINE_HTTP.equals(engine)) {
            return OnsTtsEngines.httpSynthesize(activity, text);
        }
        try {
            return MultiTtsClient.synthesizeOn(text,
                    OnsTtsEngines.voice(activity),
                    OnsTtsEngines.rate(activity),
                    OnsTtsEngines.volume(activity),
                    OnsTtsEngines.pitch(activity),
                    OnsTtsEngines.multiHost(activity));
        } catch (Throwable t) {
            Log.w(TAG, "card tts synthesize failed", t);
            return null;
        }
    }

    /**
     * 主线程同步截帧（PixelCopy：游戏 Surface 优先，回退整窗），制卡配图用。
     * 后台线程调用：内部发请求到主线程并限时等待；超时/失败返回 null。
     */
    private Bitmap captureGameFrame(long timeoutMs) {
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<Bitmap> out =
                new java.util.concurrent.atomic.AtomicReference<>();
        final Runnable request = () -> {
            try {
                android.view.SurfaceView surface = findGameSurface();
                if (surface != null) {
                    Bitmap bmp = Bitmap.createBitmap(Math.max(1, surface.getWidth()),
                            Math.max(1, surface.getHeight()), Bitmap.Config.ARGB_8888);
                    PixelCopy.request(surface, bmp, result -> {
                        if (result == PixelCopy.SUCCESS) out.set(bmp); else bmp.recycle();
                        latch.countDown();
                    }, main);
                    return;
                }
                Bitmap bmp = Bitmap.createBitmap(
                        activity.getWindow().getDecorView().getWidth(),
                        activity.getWindow().getDecorView().getHeight(),
                        Bitmap.Config.ARGB_8888);
                PixelCopy.request(activity.getWindow(), bmp, result -> {
                    if (result == PixelCopy.SUCCESS) out.set(bmp); else bmp.recycle();
                    latch.countDown();
                }, main);
            } catch (Throwable t) {
                Log.w(TAG, "captureGameFrame request failed", t);
                latch.countDown();
            }
        };
        main.post(request);
        try {
            latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        return out.get();
    }

    private void makeWordCard() {
        final List<OnsDictStore.Group> groups = defGroups;
        final String term = defSentence;
        if (groups == null || groups.isEmpty() || term == null || term.isEmpty()) {
            toast(R.string.engine_ons_extract_def_none);
            return;
        }
        ExtractFacade bridge = facade;
        final String sentence;
        if (bridge.getSentenceText().isEmpty()) {
            sentence = bridge.getPageText();
        } else {
            sentence = bridge.getSentenceText();
        }
        if (sentence.isEmpty()) {
            android.util.Log.w("OnsExtractPanel", "makeWordCard no text: sentence='"
                    + bridge.getSentenceText() + "' page='" + bridge.getPageText() + "'");
            toast(R.string.engine_ons_extract_no_text);
            return;
        }
        // AnkiDroid 未安装 / 未授权：明确引导而不是通用失败 toast
        //（未安装跳商店安装页；未授权弹对话框后跳 AnkiDroid 应用信息页授权）
        com.core.anki.AnkiDroidHelper gateHelper = new com.core.anki.AnkiDroidHelper(activity);
        if (!gateHelper.isAnkiDroidInstalled()) {
            toast(R.string.engine_ons_extract_anki_not_installed);
            try {
                activity.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("market://details?id=com.ichi2.anki")));
            } catch (Throwable ignoredMarket) {
            }
            return;
        }
        if (!gateHelper.hasPermission()) {
            new android.app.AlertDialog.Builder(activity)
                    .setTitle(R.string.engine_ons_extract_anki_need_permission)
                    .setPositiveButton(R.string.engine_ons_extract_anki_go_grant, (d, w) -> {
                        try {
                            gateHelper.launchAnkiDroidApp();
                        } catch (Throwable ignored) {
                        }
                        try {
                            activity.startActivity(new android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:com.ichi2.anki")));
                        } catch (Throwable ignored) {
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }

        // 字段映射（AnkiCardConfig）：逻辑槽位 → Anki 模型字段名；牌组/模板必须是用户
        // 从 AnkiDroid 现有内容里选的（不预设、不自动创建）
        final String deckName = com.core.anki.AnkiCardConfig.deck(activity);
        final String modelName = com.core.anki.AnkiCardConfig.model(activity);
        final java.util.Map<String, String> fieldMap = com.core.anki.AnkiCardConfig.fieldMap(activity);
        final String voiceSource = com.core.anki.AnkiCardConfig.voiceSource(activity);
        final boolean captureOnCard = com.core.anki.AnkiCardConfig.captureOnCard(activity);

        if (deckName.isEmpty() || modelName.isEmpty()) {
            toast(R.string.engine_ons_anki_need_config);
            return;
        }

        new Thread(() -> {
            com.core.anki.AnkiDroidHelper helper = new com.core.anki.AnkiDroidHelper(activity);
            try {
                if (!helper.isAnkiDroidInstalled()) {
                    main.post(() -> toast(R.string.engine_ons_extract_anki_not_installed));
                    return;
                }
                if (!helper.hasPermission()) {
                    helper.requestPermission(activity);
                    main.post(() -> toast(R.string.engine_ons_extract_anki_need_permission));
                    return;
                }

                // 模板契约（video_learn_tool 制卡导出契约对齐）：模板必须是用户在
                // AnkiDroid 中已有的模型——不再自动创建；缺失时明确报错引导到
                // 制卡设置选择。字段列表读真实模型（不猜字段组合）。
                Long modelId = helper.findModelId(modelName);
                if (modelId == null) {
                    main.post(() -> toast(activity.getString(
                            R.string.engine_ons_anki_target_missing, deckName, modelName)));
                    return;
                }
                String[] realFields = helper.getModelFieldNames(modelId);
                if (realFields == null || realFields.length == 0) {
                    main.post(() -> toast(R.string.engine_ons_extract_anki_failed));
                    return;
                }
                String[] modelFields = realFields;
                // 牌组只认 AnkiDroid 里已存在的（用户从列表里选过），不自动新建
                Long deckId = helper.findDeckId(deckName);
                if (deckId == null) {
                    main.post(() -> toast(activity.getString(
                            R.string.engine_ons_anki_target_missing, deckName, modelName)));
                    return;
                }

                // ---- 逻辑槽位内容装配 ----
                StringBuilder meaning = new StringBuilder();
                String reading = "";
                for (OnsDictStore.Group g : groups) {
                    if (reading.isEmpty() && !g.reading.isEmpty()) reading = g.reading;
                    for (String gloss : g.glosses) {
                        if (meaning.length() > 0) meaning.append("<br>");
                        meaning.append(android.text.Html.escapeHtml(gloss));
                    }
                }
                java.util.Map<String, String> slots = new java.util.LinkedHashMap<>();
                slots.put(com.core.anki.AnkiCardConfig.SLOT_WORD, term);
                slots.put(com.core.anki.AnkiCardConfig.SLOT_READING, reading);
                slots.put(com.core.anki.AnkiCardConfig.SLOT_DEFINITION, meaning.toString());
                slots.put(com.core.anki.AnkiCardConfig.SLOT_SENTENCE,
                        android.text.Html.escapeHtml(sentence));
                slots.put(com.core.anki.AnkiCardConfig.SLOT_TRANSLATION,
                        lastTranslation == null ? "" : lastTranslation);
                // 单词标签（查词词典名）+ 来源（游戏显示名）+ 词形还原路径；未映射时内容闲置
                slots.put(com.core.anki.AnkiCardConfig.SLOT_WORD_TAGS,
                        com.core.ons.OnsDictStore.get().getDictName());
                slots.put(com.core.anki.AnkiCardConfig.SLOT_DEINFLECTION, defPath);
                slots.put(com.core.anki.AnkiCardConfig.SLOT_SOURCE, getGameDisplayName());

                File dir = new File(activity.getExternalFilesDir(null), "extract");
                dir.mkdirs();

                // 截图槽位：制卡时现截（主线程同步等待），失败回退最近一张手动截图
                String shotField = fieldMap.get(com.core.anki.AnkiCardConfig.SLOT_IMAGE);
                Log.i(TAG, "word card image slot: field=" + shotField + " captureOnCard=" + captureOnCard);
                if (shotField != null && !shotField.isEmpty()) {
                    Bitmap shot = captureOnCard ? captureGameFrame(2500) : null;
                    Log.i(TAG, "word card frame captured=" + (shot != null));
                    if (shot == null) shot = bridge.getScreenshot();
                    if (shot == null) {
                        Log.w(TAG, "word card: 没有可用截图（自动截取失败且无最近一次手动截图）");
                    }
                    if (shot != null && !shot.isRecycled()) {
                        try {
                            File png = new File(dir, "anki_shot_" + timestamp() + ".png");
                            try (FileOutputStream fos = new FileOutputStream(png)) {
                                shot.compress(Bitmap.CompressFormat.PNG, 95, fos);
                            }
                            Log.i(TAG, "word card screenshot file=" + png.getAbsolutePath()
                                    + " bytes=" + png.length());
                            String mark = helper.addMedia(png, png.getName(), "image");
                            Log.i(TAG, "word card image mark=" + mark);
                            // 已交给 AnkiDroid 复制，本地临时文件不再保留
                            if (!png.delete()) Log.w(TAG, "临时截图删除失败: " + png.getName());
                            if (mark != null) {
                                slots.put(com.core.anki.AnkiCardConfig.SLOT_IMAGE, mark);
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "card screenshot import failed", t);
                        }
                    }
                }

                // 例句语音槽位：游戏配对语音优先（auto/game），无则回退 TTS（auto/tts）
                String sentAudioField = fieldMap.get(com.core.anki.AnkiCardConfig.SLOT_SENTENCE_AUDIO);
                boolean wantSentenceAudio = sentAudioField != null && !sentAudioField.isEmpty()
                        && !com.core.anki.AnkiCardConfig.VOICE_OFF.equals(voiceSource);
                if (wantSentenceAudio) {
                    byte[] voice = com.core.anki.AnkiCardConfig.VOICE_TTS.equals(voiceSource)
                            ? null : bridge.ensureVoiceBytes();
                    if (voice == null && !com.core.anki.AnkiCardConfig.VOICE_GAME.equals(voiceSource)) {
                        voice = ttsSynthesizeQuiet(sentence);
                    }
                    if (voice != null) {
                        try {
                            String base = sanitize(bridge.getVoiceName());
                            if (base.isEmpty()) base = "sentence_" + timestamp();
                            File vf = new File(dir, "anki_s_" + base);
                            try (FileOutputStream fos = new FileOutputStream(vf)) {
                                fos.write(voice);
                            }
                            String mark = helper.addMedia(vf, vf.getName(), "audio");
                            if (mark != null) {
                                slots.put(com.core.anki.AnkiCardConfig.SLOT_SENTENCE_AUDIO, mark);
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "card sentence audio import failed", t);
                        }
                    }
                }

                // 单词语音槽位：本地音频库（AnkiConnect localaudio）优先按配置，否则 TTS
                String wordAudioField = fieldMap.get(com.core.anki.AnkiCardConfig.SLOT_AUDIO);
                if (wordAudioField != null && !wordAudioField.isEmpty()) {
                    boolean localFirst = com.core.anki.AnkiCardConfig.WORD_VOICE_LOCAL.equals(
                            com.core.anki.AnkiCardConfig.wordVoiceSource(activity));
                    boolean done = false;
                    if (localFirst) {
                        try {
                            File lf = new File(dir, "anki_w_" + timestamp() + ".bin");
                            boolean ok = com.core.anki.LocalAudioClient.fetchWordAudioToFile(
                                    "127.0.0.1",
                                    com.core.anki.AnkiCardConfig.localAudioPort(activity),
                                    term, reading,
                                    com.core.anki.AnkiCardConfig.localAudioSources(activity),
                                    lf);
                            if (ok) {
                                String ext = com.core.anki.LocalAudioClient.extensionOf(lf.getName());
                                File named = new File(dir, "anki_w_" + timestamp() + "." + ext);
                                if (lf.renameTo(named) || named.exists()) {
                                    File use = named.exists() ? named : lf;
                                    String mark = helper.addMedia(use, use.getName(), "audio");
                                    if (mark != null) {
                                        slots.put(com.core.anki.AnkiCardConfig.SLOT_AUDIO, mark);
                                        done = true;
                                    }
                                }
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "local audio word card failed", t);
                        }
                    }
                    if (!done) {
                        byte[] wav = ttsSynthesizeQuiet(term);
                        if (wav != null) {
                            try {
                                File vf = new File(dir, "anki_w_" + timestamp() + ".wav");
                                try (FileOutputStream fos = new FileOutputStream(vf)) {
                                    fos.write(wav);
                                }
                                String mark = helper.addMedia(vf, vf.getName(), "audio");
                                if (mark != null) {
                                    slots.put(com.core.anki.AnkiCardConfig.SLOT_AUDIO, mark);
                                }
                            } catch (Throwable t) {
                                Log.w(TAG, "card word audio import failed", t);
                            }
                        }
                    }
                }

                // ---- 按模型字段序装配：字段名反查映射槽位 ----
                java.util.Map<String, String> fieldToSlot = new java.util.LinkedHashMap<>();
                for (java.util.Map.Entry<String, String> e : fieldMap.entrySet()) {
                    if (!fieldToSlot.containsKey(e.getValue())) fieldToSlot.put(e.getValue(), e.getKey());
                }
                String[] fields = new String[modelFields.length];
                for (int i = 0; i < modelFields.length; i++) {
                    String slot = fieldToSlot.get(modelFields[i]);
                    fields[i] = slot == null ? "" : java.util.Objects.toString(slots.get(slot), "");
                }
                Log.i(TAG, "word card fields: " + java.util.Arrays.toString(modelFields)
                        + " → " + java.util.Arrays.toString(fields));
                Long noteId = helper.addNote(modelId, deckId, fields, null);
                // 映射了图片/语音但媒体没进 AnkiDroid 媒体库：明确告知（否则卡片只是「缺图」）
                final boolean mediaMissing =
                        (shotField != null && !shotField.isEmpty()
                                && !slots.containsKey(com.core.anki.AnkiCardConfig.SLOT_IMAGE))
                        || (sentAudioField != null && !sentAudioField.isEmpty()
                                && !slots.containsKey(com.core.anki.AnkiCardConfig.SLOT_SENTENCE_AUDIO));
                main.post(() -> {
                    if (noteId == null) {
                        toast(R.string.engine_ons_extract_anki_failed);
                    } else if (mediaMissing) {
                        toast(R.string.engine_ons_extract_word_added_media_failed);
                    } else {
                        toast(R.string.engine_ons_extract_word_added);
                    }
                });
            } catch (Throwable t) {
                Log.w(TAG, "makeWordCard failed", t);
                main.post(() -> toast(R.string.engine_ons_extract_anki_failed));
            }
        }, "ons-word-card").start();
    }

    // ------------------------------------------------------------------
    // 翻译（OpenAI 兼容 API；参考 anki 项目 api_client.dart）
    // ------------------------------------------------------------------

    private void translateCurrent() {
        // 翻译链（TrackReader translate() 对齐）：未配置 API 也能翻译——
        // 已配置 API 优先，失败/未配置回退 MyMemory 免费服务（零配置开箱即用）
        ExtractFacade bridge = facade;
        String sentence = bridge.getSentenceText();
        if (sentence.isEmpty()) sentence = bridge.getPageText();
        if (sentence.isEmpty()) return;
        final int requestId = ++translateRequestId;
        final String sentenceF = sentence;
        transView.setTag(Boolean.TRUE);
        transView.setText(activity.getString(R.string.engine_ons_extract_translating));
        if (expanded) transView.setVisibility(View.VISIBLE);
        new Thread(() -> {
            // 先用户 API（若已配置），失败回退 MyMemory 免费链
            String translated = null;
            try {
                translated = OnsFreeTranslate.translate(activity, sentenceF);
            } catch (Throwable t) {
                Log.w(TAG, "translate chain failed", t);
            }
            if (translated == null && OnsTranslateClient.isConfigured(activity)) {
                final String[] out = {null};
                final Object lock = new Object();
                OnsTranslateClient.translateWithEngine(activity, sentenceF, (tr, err) -> {
                    synchronized (lock) {
                        out[0] = tr;
                        lock.notifyAll();
                    }
                });
                synchronized (lock) {
                    try { lock.wait(20000); } catch (InterruptedException ignored) { }
                }
                translated = out[0];
            }
            final String tr = translated;
            final String err = translated == null ? "network" : null;
            main.post(() -> {
                if (requestId != translateRequestId) return; // 翻句或已有更新请求
                if (tr != null) {
                    lastTranslation = tr;
                    transView.setTag(Boolean.TRUE);
                    transView.setText(activity.getString(
                            R.string.engine_ons_extract_translate_line, tr));
                    transView.setVisibility(View.VISIBLE);
                } else {
                    lastTranslation = "";
                    transView.setTag(null);
                    transView.setVisibility(View.GONE);
                    toast(activity.getString(
                            R.string.engine_ons_extract_translate_failed, "network"));
                }
                if (expanded) {
                    // 先按内容自适应长高（能放下就不需要滑动）；真的超过高度上限时才滚到底
                    applyAdaptiveHeight();
                    mainScroller.post(() -> {
                        if (isMainContentOverflowing()) mainScrollToTrans();
                    });
                }
            });
        }, "ons-translate").start();
    }

    /** 主滚动区内容是否超过当前可视高度（超过才需要滚动/自动滚到底）。 */
    private boolean isMainContentOverflowing() {
        if (mainScroller == null || mainScroller.getChildCount() == 0) return false;
        return mainScroller.getChildAt(0).getHeight() > mainScroller.getHeight();
    }

    private void mainScrollToTrans() {
        mainScroller.post(() -> mainScroller.fullScroll(View.FOCUS_DOWN));
    }

    /** 翻译 API 设置弹窗（首次点翻译或长按 🌐 均可呼出）。 */
    private void showApiSettings() {
        android.content.SharedPreferences p = OnsTranslateClient.prefs(activity);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, pad, pad, 0);
        EditText baseField = settingField(box, R.string.engine_ons_extract_api_hint_base,
                p.getString(OnsTranslateClient.KEY_BASE_URL, ""));
        EditText keyField = settingField(box, R.string.engine_ons_extract_api_hint_key,
                p.getString(OnsTranslateClient.KEY_API_KEY, ""));
        EditText modelField = settingField(box, R.string.engine_ons_extract_api_hint_model,
                p.getString(OnsTranslateClient.KEY_MODEL, ""));
        EditText targetField = settingField(box, R.string.engine_ons_extract_api_hint_target,
                p.getString(OnsTranslateClient.KEY_TARGET, ""));

        new android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.engine_ons_extract_api_title)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    android.content.SharedPreferences.Editor e = p.edit();
                    e.putString(OnsTranslateClient.KEY_BASE_URL, textOr(baseField,
                            OnsTranslateClient.DEFAULT_BASE_URL));
                    e.putString(OnsTranslateClient.KEY_API_KEY, textOr(keyField, ""));
                    e.putString(OnsTranslateClient.KEY_MODEL, textOr(modelField,
                            OnsTranslateClient.DEFAULT_MODEL));
                    e.putString(OnsTranslateClient.KEY_TARGET, textOr(targetField,
                            OnsTranslateClient.DEFAULT_TARGET));
                    e.apply();
                    toast(R.string.engine_ons_extract_api_saved);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private EditText settingField(LinearLayout box, int hintRes, String value) {
        TextView hint = new TextView(activity);
        hint.setText(hintRes);
        hint.setTextColor(TEXT_DIM);
        hint.setTextSize(11);
        box.addView(hint, matchWrap());
        EditText field = new EditText(activity);
        field.setText(value);
        field.setTextSize(13);
        field.setSingleLine(true);
        box.addView(field, matchWrap());
        return field;
    }

    private static String textOr(EditText field, String fallback) {
        String s = field.getText() == null ? "" : field.getText().toString().trim();
        return s.isEmpty() ? fallback : s;
    }

    // ------------------------------------------------------------------
    // 词典管理 / 本地翻译模型 / 翻译测试（对齐 D:\Desktop\test-flutter\anki
    // 的 dictionary_home_page 与 settings_page 翻译分区）
    // ------------------------------------------------------------------

    /** 词典变化后刷新设置页摘要的钩子（showSettingsDialog 设置）。 */
    private Runnable dictChangedHook;

    /** 词典管理弹窗：多词典列表（启停/删除/设为当前）+ 导入。 */
    private void showDictManagerDialog(Runnable onChanged) {
        dictChangedHook = onChanged;
        android.app.AlertDialog[] holder = new android.app.AlertDialog[1];
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, dp(8), pad, 0);
        List<OnsDictStore.DictInfo> dicts = OnsDictStore.get().listDicts(activity);
        if (dicts.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText(R.string.engine_ons_dict_empty);
            empty.setTextColor(TEXT_DIM);
            empty.setTextSize(13);
            box.addView(empty);
        }
        Runnable refresh = () -> {
            if (holder[0] != null) {
                holder[0].dismiss();
                main.post(() -> showDictManagerDialog(onChanged));
            }
        };
        for (OnsDictStore.DictInfo info : dicts) {
            box.addView(dictRowView(info, onChanged, refresh));
        }
        TextView importBtn = dialogActionButton(
                activity.getString(R.string.engine_ons_dict_import), null);
        importBtn.setOnClickListener(v -> {
            holder[0].dismiss();
            importDictionary();
        });
        box.addView(importBtn);
        android.widget.ScrollView scroller = new android.widget.ScrollView(activity);
        scroller.addView(box);
        holder[0] = new android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.engine_ons_dict_manager)
                .setView(scroller)
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private View dictRowView(OnsDictStore.DictInfo info, Runnable onChanged, Runnable refresh) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(10);
        row.setPadding(0, pad, 0, pad);
        TextView title = new TextView(activity);
        String count = activity.getString(R.string.engine_ons_dict_entries_fmt, info.count);
        title.setText(info.name + (info.current ? " ★" : "") + "（" + count + "）");
        title.setTextColor(info.enabled ? TEXT_BODY : TEXT_DIM);
        title.setTextSize(14);
        row.addView(title);
        LinearLayout buttons = new LinearLayout(activity);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        if (!info.current) {
            buttons.addView(dialogSmallButton(
                    activity.getString(R.string.engine_ons_dict_set_current), ACCENT, () -> {
                        OnsDictStore.get().setCurrent(activity, info.id);
                        onChanged.run();
                        refresh.run();
                    }));
        }
        buttons.addView(dialogSmallButton(
                activity.getString(info.enabled
                        ? R.string.engine_ons_dict_disable : R.string.engine_ons_dict_enable),
                TEXT_BUTTON, () -> {
                    OnsDictStore.get().setEnabled(activity, info.id, !info.enabled);
                    onChanged.run();
                    refresh.run();
                }));
        buttons.addView(dialogSmallButton(
                activity.getString(R.string.engine_ons_dict_delete), 0xFFDC2626, () -> {
                    new android.app.AlertDialog.Builder(activity)
                            .setTitle(R.string.engine_ons_dict_delete)
                            .setMessage(activity.getString(
                                    R.string.engine_ons_dict_delete_confirm, info.name))
                            .setPositiveButton(R.string.engine_ons_dict_delete, (d, w) -> {
                                OnsDictStore.get().deleteDict(activity, info.id);
                                toast(activity.getString(
                                        R.string.engine_ons_dict_deleted, info.name));
                                onChanged.run();
                                refresh.run();
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                }));
        row.addView(buttons);
        return row;
    }

    /** 本地翻译模型管理（ML Kit 按语言下载/删除）。 */
    private void showLocalModelDialog() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, dp(8), pad, 0);
        String[][] langs = {{"zh", "中文"}, {"en", "英语"}, {"ja", "日语"}, {"ko", "韩语"}};
        for (String[] lang : langs) {
            box.addView(modelRowView(lang[0], lang[1]));
        }
        android.widget.ScrollView scroller = new android.widget.ScrollView(activity);
        scroller.addView(box);
        new android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.engine_ons_translate_models)
                .setView(scroller)
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 单语言模型行：状态文本 + 下载/删除动作按钮（动作完成后原位刷新）。 */
    private View modelRowView(String code, String label) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.VERTICAL);
        int pad10 = dp(10);
        row.setPadding(0, pad10, 0, pad10);
        TextView title = new TextView(activity);
        title.setText(label);
        title.setTextColor(TEXT_BODY);
        title.setTextSize(14);
        row.addView(title);
        TextView state = new TextView(activity);
        state.setTextColor(TEXT_DIM);
        state.setTextSize(12);
        row.addView(state);
        TextView action = dialogSmallButton("", TEXT_BUTTON, null);
        row.addView(action);
        Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            boolean downloaded = OnsMlKitTranslator.isModelDownloaded(activity, code);
            state.setText(activity.getString(downloaded
                    ? R.string.engine_ons_translate_model_downloaded
                    : R.string.engine_ons_translate_model_not_dl));
            // 未下载→「下载」，已下载→「删除」
            action.setText(activity.getString(downloaded
                    ? R.string.engine_ons_dict_delete
                    : R.string.engine_ons_translate_model_download));
            action.setOnClickListener(v -> new Thread(() -> {
                try {
                    if (downloaded) {
                        OnsMlKitTranslator.deleteModel(activity, code);
                        main.post(() -> toast(activity.getString(
                                R.string.engine_ons_translate_model_removed, label)));
                    } else {
                        main.post(() -> toast(label + "…"));
                        OnsMlKitTranslator.downloadModel(activity, code);
                        main.post(() -> toast(activity.getString(
                                R.string.engine_ons_translate_model_done, label)));
                    }
                    main.post(refresh[0]);
                } catch (Throwable t) {
                    Log.w(TAG, "model toggle failed", t);
                    main.post(() -> toast(activity.getString(
                            R.string.engine_ons_translate_model_fail,
                            t.getMessage() == null ? t.toString() : t.getMessage())));
                }
            }, "ons-model-toggle").start());
        };
        refresh[0].run();
        return row;
    }

    /** 翻译测试（对齐参考 settings_page 翻译分区：源/目标 + 输入 + 运行 + 结果）。 */
    private void showTranslateTestDialog() {
        android.content.SharedPreferences p = OnsTranslateClient.prefs(activity);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        box.setPadding(pad, dp(8), pad, 0);
        final String[] srcCodes = {"auto", "zh", "en", "ja", "ko"};
        final String[] tgtCodes = {"zh", "en", "ja", "ko"};
        final String[][] codeNames = {{"zh", "中文"}, {"en", "英语"}, {"ja", "日语"}, {"ko", "韩语"}};
        final java.util.List<String> srcLabels = new java.util.ArrayList<>();
        for (String c : srcCodes) {
            srcLabels.add("auto".equals(c)
                    ? activity.getString(R.string.engine_ons_translate_auto) : codeName(codeNames, c));
        }
        final java.util.List<String> tgtLabels = new java.util.ArrayList<>();
        for (String c : tgtCodes) tgtLabels.add(codeName(codeNames, c));
        int pad8 = dp(8);
        String srcCur = p.getString(OnsTranslateClient.KEY_SOURCE, OnsTranslateClient.DEFAULT_SOURCE);
        int srcIdx = 0;
        for (int i = 0; i < srcCodes.length; i++) if (srcCodes[i].equals(srcCur)) srcIdx = i;
        LinearLayout srcRow = SettingsWidgets.dropdownRow(activity, 0,
                activity.getString(R.string.engine_ons_translate_src), srcLabels, srcIdx,
                position -> p.edit().putString(
                        OnsTranslateClient.KEY_SOURCE, srcCodes[position]).apply());
        srcRow.setPadding(0, pad8, 0, 0);
        box.addView(srcRow);
        String tgtCur = OnsTranslateClient.targetCode(activity);
        int tgtIdx = 0;
        for (int i = 0; i < tgtCodes.length; i++) if (tgtCodes[i].equals(tgtCur)) tgtIdx = i;
        LinearLayout tgtRow = SettingsWidgets.dropdownRow(activity, 0,
                activity.getString(R.string.engine_ons_translate_tgt), tgtLabels, tgtIdx,
                position -> p.edit().putString(OnsTranslateClient.KEY_TARGET,
                        codeName(codeNames, tgtCodes[position])).apply());
        tgtRow.setPadding(0, pad8, 0, 0);
        box.addView(tgtRow);
        EditText input = new EditText(activity);
        input.setHint(R.string.engine_ons_translate_test);
        input.setTextSize(13);
        input.setMinLines(1);
        input.setMaxLines(3);
        box.addView(input);
        TextView output = new TextView(activity);
        output.setTextColor(TEXT_BODY);
        output.setTextSize(14);
        output.setPadding(0, pad8, 0, 0);
        TextView runBtn = dialogActionButton(
                activity.getString(R.string.engine_ons_translate_run), null);
        runBtn.setOnClickListener(v -> {
            String text = input.getText() == null ? "" : input.getText().toString().trim();
            if (text.isEmpty()) return;
            runBtn.setEnabled(false);
            runBtn.setTextColor(TEXT_DIM);
            OnsTranslateClient.translateWithEngine(activity, text, (translated, error) -> {
                if (translated != null) output.setText(translated);
                else output.setText(activity.getString(
                        R.string.engine_ons_extract_translate_failed, error));
            });
        });
        box.addView(runBtn);
        box.addView(output);
        android.widget.ScrollView scroller = new android.widget.ScrollView(activity);
        scroller.addView(box);
        new android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.engine_ons_translate_test_title)
                .setView(scroller)
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static String codeName(String[][] table, String code) {
        for (String[] row : table) {
            if (row[0].equals(code)) return row[1];
        }
        return code;
    }

    /** 弹窗内主操作按钮（全宽圆角）。 */
    private TextView dialogActionButton(String label, Runnable action) {
        TextView tv = new TextView(activity);
        tv.setText(label);
        tv.setTextColor(TEXT_BUTTON);
        tv.setTextSize(13);
        tv.setGravity(Gravity.CENTER);
        int padV = dp(10);
        tv.setPadding(padV, padV, padV, padV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        tv.setLayoutParams(lp);
        tv.setBackground(rounded(BG_BUTTON, dp(10), STROKE, dp(1)));
        if (action != null) tv.setOnClickListener(v -> action.run());
        return tv;
    }

    /** 弹窗内小操作按钮（胶囊）。 */
    private TextView dialogSmallButton(String label, int bgColor, Runnable action) {
        TextView tv = new TextView(activity);
        tv.setText(label);
        tv.setTextColor(bgColor == ACCENT ? Color.WHITE : TEXT_BUTTON);
        tv.setTextSize(12);
        int padH = dp(12);
        int padV = dp(6);
        tv.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        tv.setLayoutParams(lp);
        tv.setBackground(rounded(bgColor == ACCENT ? ACCENT : BG_BUTTON, dp(10), STROKE, dp(1)));
        if (action != null) tv.setOnClickListener(v -> action.run());
        return tv;
    }

    // ------------------------------------------------------------------
    // 词典导入（Yomichan zip / MDX jsonl → 本地 SQLite）
    // ------------------------------------------------------------------

    private void importDictionary() {
        try {
            facade.importDictionary();
        } catch (Throwable t) {
            Log.w(TAG, "dict import launch failed", t);
        }
    }

    /** SAF 选择回调（ONScripter.onActivityResult 委托）。 */
    public void onDictImportPicked(Uri uri) {
        if (uri == null) return;
        toast(R.string.engine_ons_extract_dict_importing);
        new Thread(() -> {
            try {
                int count = OnsDictStore.get().importFromFile(activity, uri,
                        msg -> main.post(() -> toast(msg)));
                String name = OnsDictStore.get().getDictName();
                main.post(() -> {
                    toast(activity.getString(
                            R.string.engine_ons_extract_dict_imported, name, count));
                    if (dictChangedHook != null) dictChangedHook.run();
                });
            } catch (Throwable t) {
                Log.w(TAG, "dict import failed", t);
                main.post(() -> toast(R.string.engine_ons_extract_action_failed));
            }
        }, "ons-dict-import").start();
    }

    // ------------------------------------------------------------------
    // 手柄虚拟光标命中面板（OnsVirtualMouse A 键优先回调）
    // ------------------------------------------------------------------

    /**
     * 光标点击的覆盖层优先命中：右缘按键组 → 找最深可点击子控件派发；
     * 面板内 → 同上（兼容 LinkMovementMethod 的句栏词元点击）。
     * 返回 true 表示已消费，不再注入游戏画面。
     */
    public boolean dispatchCursorClick(float x, float y) {
        if (sideButtons != null && sideButtons.getVisibility() == View.VISIBLE
                && hitView(sideButtons, x, y)) {
            View target = deepestClickable(sideButtons, x, y);
            if (target != null) {
                dispatchTap(target, x, y);
                return true;
            }
        }
        if (expanded && panel != null && panel.getVisibility() == View.VISIBLE
                && hitView(panel, x, y)) {
            View target = deepestClickable(panel, x, y);
            if (target != null) {
                dispatchTap(target, x, y);
                return true;
            }
            // 面板本体上、但不是任何可点控件：视为「点一下收起剧情文本框」。
            // 用户反馈：显示中的剧情文本框点不掉（鼠标模式下尤其明显）。
            togglePanel();
            return true;
        }
        return false;
    }

    private static boolean hitView(View v, float x, float y) {
        int[] l = new int[2];
        v.getLocationOnScreen(l);
        return x >= l[0] && x < l[0] + v.getWidth() && y >= l[1] && y < l[1] + v.getHeight();
    }

    private View deepestClickable(View root, float x, float y) {
        if (root instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) root;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View child = group.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE || !hitView(child, x, y)) continue;
                View deeper = deepestClickable(child, x, y);
                if (deeper != null) return deeper;
                if (child.isClickable()) return child;
            }
        }
        return root.isClickable() ? root : null;
    }

    private void dispatchTap(View target, float x, float y) {
        int[] l = new int[2];
        target.getLocationOnScreen(l);
        float localX = x - l[0];
        float localY = y - l[1];
        long now = android.os.SystemClock.uptimeMillis();
        target.dispatchTouchEvent(android.view.MotionEvent.obtain(
                now, now, android.view.MotionEvent.ACTION_DOWN, localX, localY, 0));
        target.dispatchTouchEvent(android.view.MotionEvent.obtain(
                now, now + 60, android.view.MotionEvent.ACTION_UP, localX, localY, 0));
    }

    // ------------------------------------------------------------------
    // 手柄 / 键盘按键（webgametxt TextOverlay + 词典导航语义）
    // ------------------------------------------------------------------

    /**
     * 面板按键路由，宿主 dispatchKeyEvent 在虚拟鼠标**之前**调用。
     *
     * 语义动作表（{@code PanelActionBindings}，对齐参考 webgametxt 的 dict-gamepad）：
     * - 面板收起：**只认呼出键**（默认 RB）的 DOWN 边沿，其它键一律返回 false 透传，
     *   且绝不改动其它键的按下/边沿状态（参考明确踩坑：在这里同步会吞掉游戏的边沿检测，
     *   表现为「退出面板后按 A 不再推进游戏」）；
     * - 面板展开：先吃掉非 DOWN 相位与长按重复，再查表分发；未命中任何动作的键也返回
     *   true（面板打开时手柄信号只进面板，不透传游戏）；音量键保持放行。
     */
    public boolean handleKey(KeyEvent event) {
        if (event == null) return false;
        int code = event.getKeyCode();
        // 虚拟鼠标开关（点击模式）快捷键：面板收起/展开都生效——它本来就是
        // 「不开面板也要能切」的快捷操作（用户要求快速开关虚拟鼠标）。
        //
        // 稳定性要点（用户反馈「切换不稳定」的根因）：
        // ① 只认 repeatCount == 0 的首次按下——长按会持续下发 repeatCount>0 的 DOWN，
        //    不过滤就会来回狂切；
        // ② 去抖窗口：部分手柄/多设备会重复下发同一键（重复 DOWN/多帧抖动），窗口内忽略；
        // ③ 抬起（ACTION_UP）不触发，也不改状态。
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && PanelActionBindings.matches(PanelActionBindings.PANEL_MOUSE_TOGGLE, code)) {
            if (event.getRepeatCount() == 0 && debounceToggle("mouse")) {
                toggleMouseModeFromKey();
            }
            return true;
        }
        if (!expanded) {
            if (PanelActionBindings.matches(PanelActionBindings.PANEL_TOGGLE, code)
                    && event.getAction() == KeyEvent.ACTION_DOWN) {
                if (event.getRepeatCount() == 0 && debounceToggle("panel")) {
                    Log.i(TAG, "panel toggle via key code=" + code);
                    togglePanel();
                }
                return true;
            }
            return false;
        }
        // 面板开着：吞掉非动作相位，防止长按重复/抬起透传游戏
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;

        // ── 再按一次「剧情文本框」呼出键 = 收起面板（用户要求） ──────────────
        // 默认呼出键 RB 同时也是释义下滚键，这里按「开关」语义优先：展开态再按即隐藏。
        // 滚动仍可用：左肩键(L1)上滚、摇杆/十字键 HAT、面板内拖动与右缘滚动按钮。
        if (PanelActionBindings.matches(PanelActionBindings.PANEL_TOGGLE, code)) {
            if (event.getRepeatCount() == 0 && debounceToggle("panel")) {
                Log.i(TAG, "panel toggle(close) via key code=" + code);
                togglePanel();
            }
            return true;
        }

        // ── 系统音量键：面板展开期间仍放行（既有约定） ──────────────────
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE || code == KeyEvent.KEYCODE_MUTE) {
            return false;
        }

        // ── 动作表分发（默认绑定与旧硬编码 switch 逐键一致） ────────────
        // 命中动作记一条日志（真机验收「面板消费了哪些键」的取证入口）
        String action = panelActionOf(code, event.isLongPress());
        if (action != null) {
            android.util.Log.i(TAG, "panel action " + action + " keyCode=" + code);
        }
        if (PanelActionBindings.matches(PanelActionBindings.PANEL_NAV_UP, code)) {
            // ↑↓：多行文本里上下行同列选词；选不动（单行/到边界/目标行无词元）则滚动
            if (!moveTokenVertical(-1)) scrollFocused(-1);
            return true;
        }
        if (PanelActionBindings.matches(PanelActionBindings.PANEL_NAV_DOWN, code)) {
            if (!moveTokenVertical(1)) scrollFocused(1);
            return true;
        }
        if (PanelActionBindings.matches(PanelActionBindings.PANEL_TOKEN_PREV, code)) {
            moveTokenSelection(-1);
            return true;
        }
        if (PanelActionBindings.matches(PanelActionBindings.PANEL_TOKEN_NEXT, code)) {
            moveTokenSelection(1);
            return true;
        }
        if (PanelActionBindings.matches(PanelActionBindings.PANEL_CONFIRM, code)) {
            // webgametxt 词典语义 确认=查词；未选词时回退播放（朗读/重播）
            if (!historyMode && selectedToken >= 0 && selectedToken < unitRanges.size()) {
                // 手柄确认查词：未命中不弹「未查到」提示（用户要求）
                selectUnit(selectedToken, unitRanges.get(selectedToken)[0], true);
            } else {
                playVoice();
            }
            return true;
        }
        if (PanelActionBindings.matches(PanelActionBindings.PANEL_PLAY, code)) {
            playVoice();
            return true;
        }
        if (PanelActionBindings.matches(PanelActionBindings.PANEL_EXIT, code)) {
            exitStep();
            return true;
        }
        if (PanelActionBindings.matches(PanelActionBindings.PANEL_TRANSLATE, code)) {
            translateCurrent();
            return true;
        }
        if (PanelActionBindings.matches(PanelActionBindings.PANEL_COPY, code)) {
            copyText();
            return true;
        }
        if (PanelActionBindings.matches(PanelActionBindings.PANEL_CARD, code)) {
            // 有查词结果制词卡，否则句卡
            if (defGroups != null && !defGroups.isEmpty()) makeWordCard();
            else sendToAnki();
            return true;
        }
        if (PanelActionBindings.matches(PanelActionBindings.PANEL_SENTENCE_CARD, code)) {
            sendToAnki();
            return true;
        }
        if (PanelActionBindings.matches(PanelActionBindings.PANEL_SCROLL_UP, code)) {
            scrollFocused(-1);
            return true;
        }
        if (PanelActionBindings.matches(PanelActionBindings.PANEL_SCROLL_DOWN, code)) {
            // 对齐旧行为：RB 长按向上滚（短按向下）
            scrollFocused(event.isLongPress() ? -1 : 1);
            return true;
        }
        if (PanelActionBindings.matches(PanelActionBindings.PANEL_HISTORY, code)) {
            toggleHistoryOverlay();
            return true;
        }
        return true; // webgametxt：面板打开时其余按键全拦，不透传游戏
    }

    /**
     * 面板/虚拟鼠标开关去抖：同一开关在 [TOGGLE_DEBOUNCE_MS] 内只认一次。
     * 手柄长按重复与「多设备/抖动重复下发同一键」都会造成来回狂切，靠它兜住。
     */
    private static final long TOGGLE_DEBOUNCE_MS = 350;
    private long lastMouseToggleMs;
    private long lastPanelToggleMs;

    private boolean debounceToggle(String which) {
        long now = android.os.SystemClock.uptimeMillis();
        if ("mouse".equals(which)) {
            if (now - lastMouseToggleMs < TOGGLE_DEBOUNCE_MS) return false;
            lastMouseToggleMs = now;
            return true;
        }
        if (now - lastPanelToggleMs < TOGGLE_DEBOUNCE_MS) return false;
        lastPanelToggleMs = now;
        return true;
    }

    /** 快速开关虚拟鼠标（点击模式）：调宿主开关并提示新状态。 */
    private void toggleMouseModeFromKey() {
        Runnable toggle = mouseModeToggle;
        if (toggle == null) {
            toast(R.string.engine_ons_extract_action_failed);
            return;
        }
        try {
            toggle.run();
        } catch (Throwable t) {
            Log.w(TAG, "mouse mode toggle failed", t);
            return;
        }
        boolean on = mouseModeSupplier != null && Boolean.TRUE.equals(mouseModeSupplier.get());
        Log.i(TAG, "panel action mouse_toggle → " + (on ? "virtual mouse ON" : "virtual mouse OFF"));
        toast(on ? R.string.engine_ons_extract_mouse_on : R.string.engine_ons_extract_mouse_off);
    }

    /**
     * 面板展开时是否吞掉摇杆/轴事件（宿主 dispatchGenericMotionEvent 调用）。
     * 面板打开期间摇杆不得驱动游戏、也不驱动虚拟鼠标光标；收起时返回 false 透传。
     *
     * 另外处理**十字键被手柄报成 HAT 轴**的情况（实测部分手柄 D-pad 不产生按键事件，
     * 只报 `AXIS_HAT_X/Y`）：面板展开时按 HAT 方向执行与 D-pad 按键相同的语义
     * （←→ 选词、↑↓ 滚动），否则用户在面板里按方向键「没反应」。
     */
    public boolean handleMotion(android.view.MotionEvent event) {
        // 扳机轴当按键：部分手柄的 L2/R2 不报按键、只报 AXIS_LTRIGGER/AXIS_RTRIGGER。
        // 绑定了这两个键的「开关虚拟鼠标」在轴越过阈值时触发一次（松开归零后重新武装），
        // 否则这类手柄上开关会「有时灵有时不灵」。
        if (handleTriggerToggleAxes(event)) return true;
        if (!expanded) return false;
        float hatX = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_X);
        float hatY = event.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y);
        if (hatX != 0f || hatY != 0f) {
            long now = android.os.SystemClock.uptimeMillis();
            if (now - lastHatNavMs < HAT_NAV_MIN_INTERVAL_MS) return true;
            lastHatNavMs = now;
            if (Math.abs(hatX) >= Math.abs(hatY)) {
                if (hatX > 0) moveTokenSelection(1); else moveTokenSelection(-1);
            } else if (!moveTokenVertical(hatY > 0 ? 1 : -1)) {
                scrollFocused(hatY > 0 ? 1 : -1);
            }
            return true;
        }
        // 摇杆/其它轴：面板展开时一律吞掉（不驱动游戏、也不驱动光标）
        return true;
    }

    /** HAT 轴导航的最小间隔（防同一次方向被重复上报导致连跳）。 */
    private static final long HAT_NAV_MIN_INTERVAL_MS = 120;
    private long lastHatNavMs;

    /** 扳机轴「按下」锁存（>阈值算按下，回落到阈值以下重新武装）。 */
    private boolean l2Latched;
    private boolean r2Latched;

    /**
     * 把 L2/R2 的**扳机轴**当作按键处理（仅影响绑定了对应键的「开关虚拟鼠标」动作）。
     * 越过阈值 → 触发一次（走去抖）；回落 → 重新武装。返回 true = 已消费该事件。
     */
    private boolean handleTriggerToggleAxes(android.view.MotionEvent event) {
        float rt = event.getAxisValue(android.view.MotionEvent.AXIS_RTRIGGER);
        float lt = event.getAxisValue(android.view.MotionEvent.AXIS_LTRIGGER);
        boolean consumed = false;
        if (rt > 0.5f || lt > 0.5f) {
            if (rt > 0.5f && !r2Latched) {
                r2Latched = true;
                if (PanelActionBindings.matches(
                        PanelActionBindings.PANEL_MOUSE_TOGGLE, android.view.KeyEvent.KEYCODE_BUTTON_R2)
                        && debounceToggle("mouse")) {
                    toggleMouseModeFromKey();
                }
                consumed = true;
            }
            if (lt > 0.5f && !l2Latched) {
                l2Latched = true;
                if (PanelActionBindings.matches(
                        PanelActionBindings.PANEL_MOUSE_TOGGLE, android.view.KeyEvent.KEYCODE_BUTTON_L2)
                        && debounceToggle("mouse")) {
                    toggleMouseModeFromKey();
                }
                consumed = true;
            }
        } else {
            r2Latched = false;
            l2Latched = false;
        }
        return consumed;
    }

    /** 命中的面板动作名（仅用于验收日志；未命中返回 null）。 */
    private static String panelActionOf(int code, boolean longPress) {
        if (PanelActionBindings.matches(PanelActionBindings.PANEL_NAV_UP, code)) return "nav_up";
        if (PanelActionBindings.matches(PanelActionBindings.PANEL_NAV_DOWN, code)) return "nav_down";
        if (PanelActionBindings.matches(PanelActionBindings.PANEL_TOKEN_PREV, code)) return "token_prev";
        if (PanelActionBindings.matches(PanelActionBindings.PANEL_TOKEN_NEXT, code)) return "token_next";
        if (PanelActionBindings.matches(PanelActionBindings.PANEL_CONFIRM, code)) return "confirm";
        if (PanelActionBindings.matches(PanelActionBindings.PANEL_PLAY, code)) return "play";
        if (PanelActionBindings.matches(PanelActionBindings.PANEL_EXIT, code)) return "exit";
        if (PanelActionBindings.matches(PanelActionBindings.PANEL_TRANSLATE, code)) return "translate";
        if (PanelActionBindings.matches(PanelActionBindings.PANEL_COPY, code)) return "copy";
        if (PanelActionBindings.matches(PanelActionBindings.PANEL_CARD, code)) return "card";
        if (PanelActionBindings.matches(PanelActionBindings.PANEL_SENTENCE_CARD, code)) return "sentence_card";
        if (PanelActionBindings.matches(PanelActionBindings.PANEL_SCROLL_UP, code)) return "scroll_up";
        if (PanelActionBindings.matches(PanelActionBindings.PANEL_SCROLL_DOWN, code)) {
            return longPress ? "scroll_up(longpress)" : "scroll_down";
        }
        if (PanelActionBindings.matches(PanelActionBindings.PANEL_HISTORY, code)) return "history";
        if (PanelActionBindings.matches(PanelActionBindings.PANEL_MOUSE_TOGGLE, code)) return "mouse_toggle";
        return null;
    }


    private void moveTokenSelection(int dir) {
        if (historyMode || unitRanges.isEmpty()) return;
        int next;
        if (selectedToken < 0) {
            next = dir > 0 ? 0 : unitRanges.size() - 1;
        } else {
            next = selectedToken + dir;
            if (next < 0 || next >= unitRanges.size()) return; // 已在边界
        }
        // 手柄查词方式（手柄设置页「剧情文本框查词」）：
        // - 按确认键（默认）：移动只换高亮，按确认键才查词；
        // - 移动到即查词：移动即查，且查不到不弹提示。
        if (com.core.engine.PanelOptions.isAutoLookup(activity)) {
            selectUnit(next, unitRanges.get(next)[0], true);
        } else {
            highlightUnit(next);
        }
    }

    /** 两步退出（webgametxt B/Esc 语义）：先收释义区，再揭示听力文本/收面板。 */
    private void exitStep() {
        if (defGroups != null) {
            dismissDef();
            return;
        }
        if (listeningMode && !facade.getVoiceName().isEmpty() && !listeningRevealed) {
            listeningRevealed = true;
            refresh();
            return;
        }
        if (expanded) togglePanel();
    }

    private void scrollFocused(int dir) {
        ScrollView sc = historyMode ? historyScroller : mainScroller;
        if (sc == null || sc.getVisibility() != View.VISIBLE) return;
        sc.smoothScrollBy(0, dir * Math.max(dp(60), sc.getHeight() * 2 / 3));
    }

    private void refreshToggleVisual(android.widget.ImageView toggle, boolean active) {
        if (toggle == null) return;
        toggle.setBackground(rounded(active ? ACCENT : BG_BUTTON, dp(10), STROKE, dp(1)));
    }

    // ------------------------------------------------------------------
    // 动作
    // ------------------------------------------------------------------

    private void copyText() {
        try {
            ExtractFacade bridge = facade;
            String text = bridge.getSentenceText();
            if (text.isEmpty()) text = bridge.getPageText();
            ClipboardManager cm = activity.getSystemService(ClipboardManager.class);
            cm.setPrimaryClip(new ClipData("ONS", new String[]{"text/plain"}, new ClipData.Item(text)));
            toast(R.string.engine_ons_extract_copied);
        } catch (Throwable t) {
            Log.w(TAG, "copyText failed", t);
            toast(R.string.engine_ons_extract_action_failed);
        }
    }

    private void playVoice() {
        stopVoice();
        // 这一句**没有配对语音** → 一律走 TTS，绝不去读缓存字节：
        // 缓存里可能还留着上一条台词的音频，用户反馈"重播无语音的句子会重播到别的音效上"。
        if (facade.getVoiceName().isEmpty()) {
            if (ttsEnabled) {
                speakSentence();
            } else {
                toast(R.string.engine_ons_extract_no_voice);
            }
            return;
        }
        byte[] bytes = facade.ensureVoiceBytes();
        if (bytes == null) {
            // 有语音名但读不到字节：TTS 开启时朗读，否则提示
            if (ttsEnabled) {
                speakSentence();
            } else {
                toast(R.string.engine_ons_extract_no_voice);
            }
            return;
        }
        playVoiceBytes(bytes, facade.getVoiceName());
    }

    /**
     * 历史回放：按存储名补读档案里的语音；读不到回退 TTS 朗读。
     *
     * 不再把语音落盘缓存（用户要求「音频不用保留」）：语音只在当次会话里用于播放与制卡，
     * 不长期占用存储。旧版本留下的缓存目录由 [GameMediaCleanup#purgeLegacyAudioCache] 清理。
     */
    private void playHistoryEntry(HistoryEntry entry) {
        stopVoice();
        if (!entry.voiceName.isEmpty()) {
            byte[] bytes = facade.readVoiceBytesByName(entry.voiceName);
            if (bytes != null) {
                playVoiceBytes(bytes, entry.voiceName);
                return;
            }
        }
        speakText(entry.sentence);
    }

    /** 把语音字节写入缓存文件并用 MediaPlayer 播放（调用方先 stopVoice）。 */
    private void playVoiceBytes(byte[] bytes, String voiceName) {
        try {
            File dir = new File(activity.getCacheDir(), "ons_extract");
            dir.mkdirs();
            String name = sanitize(voiceName);
            currentVoiceFile = new File(dir, (name.isEmpty() ? "voice" : name));
            try (FileOutputStream out = new FileOutputStream(currentVoiceFile)) {
                out.write(bytes);
            }
            player = new MediaPlayer();
            player.setDataSource(currentVoiceFile.getAbsolutePath());
            player.setOnCompletionListener(mp -> stopVoice());
            player.prepare();
            player.start();
        } catch (Throwable t) {
            Log.w(TAG, "playVoiceBytes failed", t);
            toast(R.string.engine_ons_extract_action_failed);
        }
    }

    private void stopVoice() {
        if (player != null) {
            try {
                player.stop();
                player.release();
            } catch (Throwable ignored) {
            }
            player = null;
        }
    }

    private void saveVoice() {
        byte[] bytes = facade.ensureVoiceBytes();
        if (bytes == null) {
            toast(R.string.engine_ons_extract_no_voice);
            return;
        }
        try {
            File dir = new File(activity.getExternalFilesDir(null), "extract");
            dir.mkdirs();
            String name = sanitize(facade.getVoiceName());
            if (name.isEmpty()) name = "voice_" + timestamp();
            File out = new File(dir, name);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(bytes);
            }
            toast(out.getAbsolutePath());
        } catch (Throwable t) {
            Log.w(TAG, "saveVoice failed", t);
            toast(R.string.engine_ons_extract_action_failed);
        }
    }

    /** 开关型图标按钮（历史/听力/TTS/自动），active 态用主色底标识。 */
    private android.widget.ImageView makeToggle(int iconRes, int descRes, boolean active,
                                                java.util.function.Supplier<Boolean> onToggle) {
        final android.widget.ImageView[] holder = new android.widget.ImageView[1];
        android.widget.ImageView iv = makeAction(iconRes, descRes, () -> {
            boolean now = onToggle.get();
            holder[0].setBackground(rounded(now ? ACCENT : BG_DARK, dp(16)));
            applyOpacity(); // 新背景需继承面板透明度
        });
        holder[0] = iv;
        iv.setBackground(rounded(active ? ACCENT : BG_DARK, dp(16)));
        return iv;
    }

    // ------------------------------------------------------------------
    // TTS 朗读（无配对语音部分）
    // ------------------------------------------------------------------

    /**
     * 待补念的系统 TTS 请求（初始化完成前到达，见 ensureTts 的 OnInitListener）。
     * 只用于系统 TTS 分支；代次变化（切句/停止）时作废。
     */
    private String pendingTtsSentence;
    private int pendingTtsEpoch = -1;
    /** 初始化是否**失败过**（false 且 !ttsReady 表示仍在初始化中）。 */
    private boolean ttsInitFailed;

    private void ensureTts() {
        // 系统 TTS 用哪个引擎：优先用户显式选择（不需要权限，直接传给构造函数），
        // 其次才是「高级引擎=MultiTTS」时的 MultiTTS 包名，否则跟随系统默认（null）。
        String chosen = OnsTtsEngines.systemEngine(activity);
        String desired = !chosen.isEmpty() ? chosen
                : (multiTtsEnabled
                   && OnsTtsEngines.ENGINE_MULTI.equals(OnsTtsEngines.engine(activity))
                   ? MULTI_TTS_PACKAGE : null);
        if (tts != null && (activeTtsEngine == null
                ? desired == null : activeTtsEngine.equals(desired))) return;
        if (tts != null) {
            try { tts.shutdown(); } catch (Throwable ignored) {}
            tts = null;
            ttsReady = false;
            ttsProblemNotifiedRes = 0;   // 换引擎/重建后允许再提示一次
        }
        android.speech.tts.TextToSpeech.OnInitListener listener = status -> {
            ttsReady = status == android.speech.tts.TextToSpeech.SUCCESS;
            ttsInitFailed = !ttsReady;
            Log.i(TAG, "tts init ready=" + ttsReady + " engine=" + activeTtsEngine);
            // **初始化完成前到达的朗读请求不能丢**：TextToSpeech 的 init 是异步的，
            // 第一句往往还在初始化中；之前直接丢弃 → 表现就是"系统 TTS 从来没出声过"。
            // 这里就绪后补念（代次已变=已切句/停止，则不补）。
            if (ttsReady) {
                final String pending = pendingTtsSentence;
                final int pendingEpoch = pendingTtsEpoch;
                pendingTtsSentence = null;
                pendingTtsEpoch = -1;
                if (pending != null && !pending.isEmpty() && pendingEpoch == ttsEpoch) {
                    Log.i(TAG, "tts ready → speak pending: " + pending.length() + " chars");
                    speakText(pending);
                }
            }
        };
        tts = desired == null
                ? new android.speech.tts.TextToSpeech(activity, listener)
                : new android.speech.tts.TextToSpeech(activity, listener, desired);
        activeTtsEngine = desired;
    }

    private java.util.Locale ttsLocale(String text) {
        String charset = facade.getLockedCharset();
        if ("SJIS".equals(charset)) return java.util.Locale.JAPAN;
        if ("GBK".equals(charset)) return java.util.Locale.SIMPLIFIED_CHINESE;
        if (text != null) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if ((c >= 0x3040 && c <= 0x30FF) || (c >= 0x31F0 && c <= 0x31FF)) {
                    return java.util.Locale.JAPAN;
                }
            }
        }
        return java.util.Locale.SIMPLIFIED_CHINESE;
    }

    /** 按句末标点（。！？…；）切分为朗读段；无标点返回原句单元素数组。 */
    private static java.util.List<String> splitJudou(String text) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '…' || c == '；') {
                if (i + 1 - start > 0) out.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length()) out.add(text.substring(start));
        return out;
    }

    private void speakSentence() {
        ExtractFacade bridge = facade;
        String sentence = bridge.getSentenceText();
        if (sentence.isEmpty()) sentence = bridge.getPageText();
        speakText(sentence);
    }

    private void speakText(String sentence) {
        if (sentence == null || sentence.isEmpty()) return;
        // 本次朗读的代次：异步合成回来时若已切句/停止（代次变了）就丢弃，
        // 避免"切句后还残留上一条对白的 TTS 声音"（竞态，用户反馈的偶发现象）。
        final int epoch = ttsEpoch;
        if (multiTtsEnabled && OnsTtsEngines.ENGINE_MULTI.equals(OnsTtsEngines.engine(activity))) {
            // 高级 TTS 引擎按设置路由：MultiTTS / 自定义 HTTP API（参考 TrackReader
            // src/domain/tts Provider 方案）；合成字节 → 本地 MediaPlayer 播放，后台线程。
            // 安卓自带 TTS（系统引擎）走方法末尾的 TextToSpeech 直呼路径。
            String engine = OnsTtsEngines.engine(activity);
            if (OnsTtsEngines.ENGINE_HTTP.equals(engine)) {
                new Thread(() -> {
                    byte[] wav = OnsTtsEngines.httpSynthesize(activity, sentence);
                    if (wav == null) {
                        main.post(() -> toast(R.string.engine_ons_tts_http_down));
                        return;
                    }
                    main.post(() -> {
                        if (epoch != ttsEpoch) {
                            Log.i(TAG, "stale http-tts dropped (line switched)");
                            return;
                        }
                        stopVoice();
                        playVoiceBytes(wav, "http_tts");
                    });
                }, "ons-http-tts").start();
                return;
            }
            // MultiTTS HTTP 合成 + 本地 MediaPlayer 播放（WAV），后台线程。
            // 语速 = 面板语速档位（游戏内快调）；音高/音量/发音人/服务器 = TTS 设置。
            int speed = Math.round(OnsTtsEngines.rate(activity) * ttsRate / 100f);
            new Thread(() -> {
                byte[] wav = MultiTtsClient.synthesizeOn(sentence,
                        OnsTtsEngines.voice(activity), speed,
                        OnsTtsEngines.volume(activity), OnsTtsEngines.pitch(activity),
                        OnsTtsEngines.multiHost(activity));
                if (wav == null) {
                    // 分类引导：服务未启动（连接失败）vs 服务在但拒绝请求（发音人等）
                    boolean up = MultiTtsClient.isServiceUp();
                    main.post(() -> toast(up
                            ? R.string.engine_ons_tts_multi_reject
                            : R.string.engine_ons_tts_multi_down));
                    return;
                }
                try {
                    File dir = new File(activity.getCacheDir(), "ons_extract");
                    dir.mkdirs();
                    File out = new File(dir, "multitts_tts.wav");
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        fos.write(wav);
                    }
                    main.post(() -> {
                        if (epoch != ttsEpoch) {
                            Log.i(TAG, "stale multitts dropped (line switched)");
                            return;
                        }
                        stopVoice();
                        try {
                            player = new MediaPlayer();
                            player.setDataSource(out.getAbsolutePath());
                            player.setOnCompletionListener(mp -> stopVoice());
                            player.prepare();
                            player.start();
                        } catch (Throwable t) {
                            Log.w(TAG, "multitts playback failed", t);
                            stopVoice();
                        }
                    });
                } catch (Throwable t) {
                    Log.w(TAG, "multitts wav write failed", t);
                }
            }, "ons-multitts").start();
            return;
        }
        ensureTts();
        if (!ttsReady) {
            if (!ttsInitFailed) {
                // 仍在初始化（异步）：**不丢弃**，等 OnInitListener 就绪后补念。
                // 之前这里直接 return → "系统 TTS 从来没成功过"的直接原因之一。
                pendingTtsSentence = sentence;
                pendingTtsEpoch = epoch;
                Log.i(TAG, "system tts still initializing → queue utterance");
                return;
            }
            // 初始化真的失败：只打日志 + **同一故障只提示一次**（用户反馈：不要一直弹提示）
            Log.w(TAG, "system tts not ready (engine=" + OnsTtsEngines.engine(activity)
                    + ", default=" + defaultTtsEngineName() + ")");
            notifyTtsProblemOnce(R.string.engine_ons_tts_engine_not_ready);
            return;
        }
        try {
            int langResult = tts.setLanguage(ttsLocale(sentence));
            if (langResult == android.speech.tts.TextToSpeech.LANG_MISSING_DATA
                    || langResult == android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "system tts language unsupported: result=" + langResult
                        + " engine=" + defaultTtsEngineName());
                notifyTtsProblemOnce(R.string.engine_ons_tts_lang_unsupported);
                return;
            }
            tts.setSpeechRate(ttsRate / 100f);
            // 音高/发音人来自 TTS 设置（pitch 0..100 → 系统引擎 0.5..2.0，50=1.0x）
            float pitchFactor = OnsTtsEngines.pitch(activity) / 50f;
            tts.setPitch(Math.max(0.5f, Math.min(2f, pitchFactor)));
            String sysVoiceName = OnsTtsEngines.voice(activity);
            if (!sysVoiceName.isEmpty() && tts.getVoices() != null) {
                for (android.speech.tts.Voice v : tts.getVoices()) {
                    if (v != null && sysVoiceName.equals(v.getName())) {
                        tts.setVoice(v);
                        break;
                    }
                }
            }
            if (epoch != ttsEpoch) {
                Log.i(TAG, "stale system-tts dropped (line switched)");
                return;
            }
            tts.stop();
            // 句读模式：按句末标点分段，首段 QUEUE_FLUSH 其余 QUEUE_ADD（段间
            // 自然停顿）；循环 = 整段序列重复 ttsLoop+1 遍。关闭句读时整句一次。
            if (ttsJudou) {
                java.util.List<String> segs = splitJudou(sentence);
                for (int round = 0; round <= ttsLoop; round++) {
                    for (int i = 0; i < segs.size(); i++) {
                        int queue = (round == 0 && i == 0)
                                ? android.speech.tts.TextToSpeech.QUEUE_FLUSH
                                : android.speech.tts.TextToSpeech.QUEUE_ADD;
                        tts.speak(segs.get(i), queue, null, "ons_extract");
                    }
                }
            } else {
                for (int round = 0; round <= ttsLoop; round++) {
                    tts.speak(sentence, round == 0
                            ? android.speech.tts.TextToSpeech.QUEUE_FLUSH
                            : android.speech.tts.TextToSpeech.QUEUE_ADD, null, "ons_extract");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "tts speak failed", t);
        }
    }

    /** 系统默认 TTS 引擎包名（诊断"系统 TTS 无声"用；真机上常见默认就是 MultiTTS）。 */
    private String defaultTtsEngineName() {
        try {
            if (tts != null) {
                String name = tts.getDefaultEngine();
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Throwable ignored) {
        }
        try {
            String name = android.provider.Settings.Secure.getString(
                    activity.getContentResolver(), "tts_default_synth");
            if (name != null && !name.isEmpty()) return name;
        } catch (Throwable ignored) {
        }
        return "?";
    }

    /**
     * TTS 故障提示：**同一故障只弹一次**（换引擎/改设置后会重置）。
     * 用户反馈："不用一直提示 tts 无法使用的消息"——自动朗读每句都会尝试一次系统 TTS，
     * 失败时逐句弹提示会刷屏。
     */
    private int ttsProblemNotifiedRes;
    private void notifyTtsProblemOnce(int res) {
        if (ttsProblemNotifiedRes == res) return;
        ttsProblemNotifiedRes = res;
        toast(res);
    }

    private void stopTts() {        // 作废所有在途的异步合成（见 ttsEpoch 注释）：否则它们回来后会播上一条的声音。
        ttsEpoch++;
        if (tts != null) {
            try { tts.stop(); } catch (Throwable ignored) {}
        }
        // **MultiTTS / HTTP-TTS 的音频走的是本地 MediaPlayer，不属于系统 TTS**：
        // 只调 tts.stop() 对它完全无效（用户反馈：切句后"停不下来、继续念一段"）。
        // 这里一并停掉 MediaPlayer（语音回放也走它，切句时同样应该停）。
        stopVoice();
    }

    /**
     * 自动朗读的触发（**去抖 + 增量合并**）。
     *
     * 背景（用户反馈 Tyrano 重复朗读）：这类引擎的对白是**逐段更新**的，例如先出
     * `AAAA`、随后变成 `AAAA BBBB`；旧实现「文本一变就念」，于是先念 AAAA，等 BBBB
     * 到位后又把 AAAA BBBB 整句念一遍——同一句听两遍。
     *
     * 现在的判定：
     * 1. 文本与上次已念内容相同 → 直接忽略；
     * 2. 每次文本变化都**重新计时**，只有文本静止 [AUTO_SPEAK_STABLE_MS]（正在增长时
     *    [AUTO_SPEAK_GROW_MS]）才真正朗读——打字机/逐段刷新期间不会中途开念；
     * 3. 「增长」= 新文本以上一次待念文本为前缀（同一句还在变长），此时用更长的静默窗口，
     *    确保等到这句彻底显示完再念一次；
     * 4. 手动「播放」不受影响（走 speakSentence 直呼）。
     */
    private static final long AUTO_SPEAK_STABLE_MS = 700;
    private static final long AUTO_SPEAK_GROW_MS = 900;
    private String pendingSpokenSentence;
    /**
     * 待补念的"新增部分"（同上：交给去抖任务念，见 speakOnlyAppendedPart）。
     *
     * 用户要求：快速连续切换对白时不要「一句一句各播一下就跳过」，
     * 直接播**最新的**那一句。所以补念也不能立即播——立即播会在连切时
     * 每次都被下一句掐断，听感就是"碎片式地播一下跳一下"。
     */
    private String pendingSpokenSuffix;

    private final Runnable autoSpeakJob = new Runnable() {
        @Override
        public void run() {
            final String suffix = pendingSpokenSuffix;
            pendingSpokenSuffix = null;
            String text = pendingSpokenSentence;
            pendingSpokenSentence = null;
            if (text == null || text.isEmpty()) return;
            // 生效条件 = 面板「自动」快调 OR 设置页「自动朗读」全局开关（后者面板隐藏
            // 也生效——隐藏剧情文本框时更新文本后照常自动朗读）
            final boolean autoOn = OnsTtsEngines.autoRead(activity);   // 单一真相源（面板/设置页同一个值）
            if (!autoOn) return;
            // **有配对语音的对白一律不 TTS**：游戏自己会播那段语音，再念一遍就是双声。
            // 这条判定必须在最前面（补念分支以前漏了它 → 有语音的句子长出来时仍会念一段）。
            if (!facade.getVoiceName().isEmpty()) {
                android.util.Log.i(TAG, "auto speak skipped: 该句有游戏语音 voice="
                        + facade.getVoiceName());
                return;
            }
            // ① 同句补念（去抖后只剩最后一次的新增部分：连切时前面几次已被取消）
            if (suffix != null && !suffix.isEmpty()) {
                lastSpokenSentence = text;
                if (!hasSpeakableContent(suffix)) return;
                android.util.Log.i(TAG, "auto speak append(" + suffix.length() + "): " + suffix);
                // 有游戏语音就不念（判定已在函数开头统一做过，这里是双保险）
                if (facade.getVoiceName().isEmpty() && (ttsEnabled || OnsTtsEngines.autoRead(activity))) {
                    speakText(suffix);
                }
                return;
            }
            if (text.equals(lastSpokenSentence)) return; // 去重：同一句不重复念
            lastSpokenSentence = text;
            // 有配对语音时游戏自身已在播放，自动模式不重复播；仅对无语音句子 TTS，
            // 重播由用户手动点「播放」按钮。
            // 自动路径不受面板「TTS 朗读」开关牵制（面板隐藏时该开关不可达，会把全局
            // 自动朗读静音）——ttsEnabled 只管手动「播放」按钮。
            android.util.Log.i(TAG, "auto speak(" + text.length() + "): " + text);
            if (facade.getVoiceName().isEmpty() && (ttsEnabled || OnsTtsEngines.autoRead(activity))) {
                speakSentence();
            }
        }
    };

    /**
     * 「同一句又长出来了」的补念：文本以上次**已念**内容为前缀时，只念新增部分，
     * 绝不重念整句（用户要求：Tyrano 这类逐段更新的对白不能重复播放）。
     * 返回 true = 已按补念处理。
     */
    private boolean speakOnlyAppendedPart(String text) {
        String spoken = lastSpokenSentence;
        if (spoken == null || spoken.isEmpty()) return false;
        if (!text.startsWith(spoken) || text.length() <= spoken.length()) return false;
        String suffix = text.substring(spoken.length()).trim();
        lastSpokenSentence = text;
        if (!hasSpeakableContent(suffix)) {
            android.util.Log.i(TAG, "auto speak: 仅标点增量，跳过: " + suffix);
            return true;
        }
        // **不再立即播**：交给同一套去抖任务（下一次文本变化会 removeCallbacks 取消本
        // 次调度）。快速连切对白时，前面几次调度被取消，最终只念最后一次的新增部分——
        // 用户要求"直接跳到最新的播放最新的对白"，而不是一句一句各播一下就跳过。
        pendingSpokenSuffix = suffix;
        pendingSpokenSentence = text;
        main.removeCallbacks(autoSpeakJob);
        main.postDelayed(autoSpeakJob, AUTO_SPEAK_GROW_MS);
        return true;
    }

    /** 增量里是否有可朗读内容（纯标点/空白就不念，避免怪异发音）。 */
    private static boolean hasSpeakableContent(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) return true;
        }
        return false;
    }

    private void autoPlayForNewSentence(String sentence) {
        if (sentence == null || sentence.isEmpty()) return;
        if (sentence.equals(lastSpokenSentence)) return;
        boolean autoOn = OnsTtsEngines.autoRead(activity);   // 单一真相源：面板的「自动」开关写的就是它
        if (!autoOn) {
            Log.i(TAG, "auto speak skipped: auto off (panel=" + ttsAuto
                    + ", global=" + OnsTtsEngines.autoRead(activity) + ")");
            return;
        }
        // ① 已念过的句子又长出新内容（AAAA → AAAA BBBB）→ 只补念新增部分
        if (speakOnlyAppendedPart(sentence)) return;
        // ② 同一句还在变长（尚未念过）→ 等更久，避免"先念一半、整句再念一遍"
        boolean growing = pendingSpokenSentence != null
                && sentence.startsWith(pendingSpokenSentence)
                && sentence.length() > pendingSpokenSentence.length();
        pendingSpokenSentence = sentence;
        pendingSpokenSuffix = null;   // 新句：丢掉可能残留的"补念片段"，避免念错内容
        // 用户要求：推进到下一句时，**上一句还在念就立即停掉**，随后转为当前句的朗读。
        // 原来只有去抖到点后 speakSentence() 里的 tts.stop() 才停 → 400/650ms 内旧句会多念
        // 一段（听感上就是"切句了还在念上一句"）。这里在新句（非"同句补念"）一到就抢先停。
        stopTts();
        android.util.Log.i(TAG, "auto speak: previous utterance stopped, pending new sentence");
        main.removeCallbacks(autoSpeakJob);
        main.postDelayed(autoSpeakJob, growing ? AUTO_SPEAK_GROW_MS : AUTO_SPEAK_STABLE_MS);
    }

    /**
     * 截图（右缘截图键与面板相机键共用）：PixelCopy 抓整窗 → 存
     * screenshots/<游戏名>/ 供主页「截图管理」查看，同时作为制卡配图缓存。
     */
    public void captureScreenshot() {
        try {
            // 只截游戏画面：优先 PixelCopy 游戏 SurfaceView（不含覆盖层/面板/按键），
            // 找不到（Web 宿主等）再回退整窗截取
            android.view.SurfaceView surface = findGameSurface();
            if (surface != null) {
                Bitmap bmp = Bitmap.createBitmap(Math.max(1, surface.getWidth()),
                        Math.max(1, surface.getHeight()), Bitmap.Config.ARGB_8888);
                PixelCopy.request(surface, bmp, result -> {
                    if (result != PixelCopy.SUCCESS) {
                        bmp.recycle();
                        captureWindowScreenshot();
                        return;
                    }
                    saveScreenshot(bmp);
                }, main);
            } else {
                captureWindowScreenshot();
            }
        } catch (Throwable t) {
            Log.w(TAG, "takeScreenshot failed", t);
            toast(R.string.engine_ons_extract_action_failed);
        }
    }

    /** 整窗截取（Surface 不可用时的回退）。 */
    private void captureWindowScreenshot() {
        try {
            Bitmap bmp = Bitmap.createBitmap(activity.getWindow().getDecorView().getWidth(),
                    activity.getWindow().getDecorView().getHeight(), Bitmap.Config.ARGB_8888);
            Window window = activity.getWindow();
            PixelCopy.request(window, bmp, result -> {
                if (result != PixelCopy.SUCCESS) {
                    toast(R.string.engine_ons_extract_action_failed);
                    return;
                }
                saveScreenshot(bmp);
            }, main);
        } catch (Throwable t) {
            Log.w(TAG, "window screenshot failed", t);
            toast(R.string.engine_ons_extract_action_failed);
        }
    }

    /** 落盘截图 + toast（成功路径共用）。 */
    private void saveScreenshot(Bitmap bmp) {
        try {
            facade.setScreenshot(bmp);
            File dir = GameScreenshots.dir(activity, facade.gameDisplayName());
            dir.mkdirs();
            File out = new File(dir, "screenshot_" + timestamp() + ".png");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            toast(activity.getString(
                    R.string.engine_ons_extract_screenshot_saved, out.getName()));
        } catch (Throwable t) {
            Log.w(TAG, "screenshot save failed", t);
            bmp.recycle();
            toast(R.string.engine_ons_extract_action_failed);
        }
    }

    /** 在窗口视图树里找游戏的 SurfaceView（SDL/GL 渲染层），不可见或没有则 null。 */
    private android.view.SurfaceView findGameSurface() {
        try {
            List<android.view.SurfaceView> found = new ArrayList<>();
            collectSurfaceViews(activity.getWindow().getDecorView(), found);
            for (android.view.SurfaceView sv : found) {
                if (sv.isShown() && sv.getWidth() > 0 && sv.getHeight() > 0) return sv;
            }
        } catch (Throwable t) {
            Log.w(TAG, "find game surface failed", t);
        }
        return null;
    }

    private static void collectSurfaceViews(android.view.View view,
                                            List<android.view.SurfaceView> out) {
        if (view instanceof android.view.SurfaceView) {
            out.add((android.view.SurfaceView) view);
            return;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectSurfaceViews(group.getChildAt(i), out);
            }
        }
    }

    /**
     * AnkiDroid 一键制卡（句卡）：按「字段映射」把 句子/句子翻译/句子音频/图片/来源
     * 写进对应字段（与词卡同一套参考要素；整页文本不再是要素）。
     * ContentProvider 调用与媒体拷贝较慢，整体放后台线程执行。
     */
    private void sendToAnki() {
        final ExtractFacade bridge = facade;
        final String sentence = bridge.getSentenceText().isEmpty()
                ? bridge.getPageText() : bridge.getSentenceText();
        final String voiceName = bridge.getVoiceName();
        if (sentence.isEmpty()) {
            toast(R.string.engine_ons_extract_no_text);
            return;
        }
        new Thread(() -> {
            com.core.anki.AnkiDroidHelper helper =
                    new com.core.anki.AnkiDroidHelper(activity);
            try {
                if (!helper.isAnkiDroidInstalled()) {
                    main.post(() -> toast(R.string.engine_ons_extract_anki_not_installed));
                    return;
                }
                if (!helper.hasPermission()) {
                    helper.requestPermission(activity);
                    main.post(() -> toast(R.string.engine_ons_extract_anki_need_permission));
                    return;
                }
                // 牌组与模板一律走用户配置（不预设、不自动创建）：未配置或 AnkiDroid 里
                // 已不存在时明确提示去「设置 → Anki 设置」选择
                final String deckName = com.core.anki.AnkiCardConfig.deck(activity);
                final String modelName = com.core.anki.AnkiCardConfig.model(activity);
                if (deckName.isEmpty() || modelName.isEmpty()) {
                    main.post(() -> toast(R.string.engine_ons_anki_need_config));
                    return;
                }
                Long deckId = helper.findDeckId(deckName);
                Long modelId = helper.findModelId(modelName);
                String[] modelFields = modelId == null ? null : helper.getModelFieldNames(modelId);
                if (deckId == null || modelFields == null || modelFields.length < 2) {
                    main.post(() -> toast(activity.getString(
                            R.string.engine_ons_anki_target_missing, deckName, modelName)));
                    return;
                }

                // 句卡也走字段映射（与词卡同一套参考要素）：句子 / 句子翻译 / 句子音频 /
                // 图片 / 来源；未映射到的内容不写（不再把截图+语音硬塞进第二个字段）
                java.util.Map<String, String> slots = new java.util.LinkedHashMap<>();
                slots.put(com.core.anki.AnkiCardConfig.SLOT_SENTENCE,
                        android.text.Html.escapeHtml(sentence));
                if (lastTranslation != null && !lastTranslation.isEmpty()) {
                    slots.put(com.core.anki.AnkiCardConfig.SLOT_TRANSLATION, lastTranslation);
                }
                slots.put(com.core.anki.AnkiCardConfig.SLOT_SOURCE, getGameDisplayName());

                File dir = new File(activity.getExternalFilesDir(null), "extract");
                dir.mkdirs();
                java.util.Map<String, String> fieldMap =
                        com.core.anki.AnkiCardConfig.fieldMap(activity);
                // 图片：与词卡同逻辑——按「制卡时自动截图」现截，失败回退最近一次手动截图
                boolean captureOnCard = com.core.anki.AnkiCardConfig.captureOnCard(activity);
                Bitmap shot = captureOnCard ? captureGameFrame(2500) : null;
                if (shot == null) shot = bridge.getScreenshot();
                Log.i(TAG, "sentence card image: field="
                        + fieldMap.get(com.core.anki.AnkiCardConfig.SLOT_IMAGE)
                        + " captureOnCard=" + captureOnCard + " bitmap=" + (shot != null));
                if (shot != null && !shot.isRecycled()) {
                    File shotFile = new File(dir, "anki_" + timestamp() + ".png");
                    try (FileOutputStream fos = new FileOutputStream(shotFile)) {
                        shot.compress(Bitmap.CompressFormat.PNG, 95, fos);
                    }
                    String mark = helper.addMedia(shotFile, shotFile.getName(), "image");
                    Log.i(TAG, "sentence card image mark=" + mark);
                    if (!shotFile.delete()) Log.w(TAG, "临时截图删除失败: " + shotFile.getName());
                    if (mark != null) slots.put(com.core.anki.AnkiCardConfig.SLOT_IMAGE, mark);
                } else {
                    Log.w(TAG, "sentence card: 没有可用截图（自动截取失败且无最近一次手动截图）");
                }
                // 句子音频（配对语音）
                byte[] voice = bridge.ensureVoiceBytes();
                if (voice != null) {
                    String base = sanitize(voiceName);
                    if (base.isEmpty()) base = "voice_" + timestamp();
                    File voiceFile = new File(dir, "anki_" + base);
                    try (FileOutputStream fos = new FileOutputStream(voiceFile)) {
                        fos.write(voice);
                    }
                    String mark = helper.addMedia(voiceFile, voiceFile.getName(), "audio");
                    if (mark != null) {
                        slots.put(com.core.anki.AnkiCardConfig.SLOT_SENTENCE_AUDIO, mark);
                    }
                }

                // 按模板字段序装配：字段 → 映射到它的要素内容
                java.util.Map<String, String> fieldToSlot = new java.util.LinkedHashMap<>();
                for (java.util.Map.Entry<String, String> e : fieldMap.entrySet()) {
                    if (!fieldToSlot.containsKey(e.getValue())) fieldToSlot.put(e.getValue(), e.getKey());
                }
                String[] values = new String[modelFields.length];
                java.util.Arrays.fill(values, "");
                boolean anyFilled = false;
                for (int i = 0; i < modelFields.length; i++) {
                    String slot = fieldToSlot.get(modelFields[i]);
                    if (slot == null) continue;
                    String content = slots.get(slot);
                    if (content == null || content.isEmpty()) continue;
                    values[i] = content;
                    anyFilled = true;
                }
                if (!anyFilled) {
                    main.post(() -> toast(R.string.engine_ons_anki_need_field_map));
                    return;
                }
                Long noteId = helper.addNote(modelId, deckId, values, null);
                main.post(() -> toast(noteId != null
                        ? R.string.engine_ons_extract_anki_added
                        : R.string.engine_ons_extract_anki_failed));
            } catch (Throwable t) {
                Log.w(TAG, "sendToAnki failed", t);
                main.post(() -> toast(R.string.engine_ons_extract_anki_failed));
            }
        }, "ons-extract-anki").start();
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private String sanitize(String name) {
        if (name == null) return "";
        String clean = name.replace('\\', '_').replace('/', '_').replace(':', '_');
        int dot = clean.lastIndexOf('.');
        String ext = dot > 0 ? clean.substring(dot) : ".bin";
        String base = dot > 0 ? clean.substring(0, dot) : clean;
        if (base.length() > 64) base = base.substring(base.length() - 64);
        return base + ext;
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    private void toast(int res) {
        Toast.makeText(activity, res, Toast.LENGTH_SHORT).show();
    }

    private void toast(String text) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private GradientDrawable rounded(int color, float radius, int strokeColor, int strokeW) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        d.setStroke(strokeW, strokeColor);
        return d;
    }

    private GradientDrawable roundedTop(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        d.setStroke(dp(1), STROKE);
        return d;
    }
}
