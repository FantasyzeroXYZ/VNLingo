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

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
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
    private static final String KEY_TTS_MULTI = "tts_multi";
    /** TTS 语速（百分比 int，50..150；系统 TTS setSpeechRate 与 MultiTTS speed 共用）。 */
    private static final String KEY_TTS_RATE = "tts_rate";
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
    /** 面板不透明百分比（OPACITY_STEPS 档位之一，持久化）。 */
    private int panelOpacity;
    private TextView opacityToggle;
    private LinearLayout actionsRow;
    private android.widget.HorizontalScrollView actionsScrollView;
    public OnsExtractPanel(ExtractFacade facade) {
        this.facade = facade;
        this.activity = facade.getActivity();
        android.content.SharedPreferences prefs =
                activity.getSharedPreferences(TTS_PREFS, android.content.Context.MODE_PRIVATE);
        ttsEnabled = prefs.getBoolean(KEY_TTS_ON, true);
        ttsAuto = prefs.getBoolean(KEY_TTS_AUTO, false);
        multiTtsEnabled = prefs.getBoolean(KEY_TTS_MULTI, false);
        ttsRate = prefs.getInt(KEY_TTS_RATE, 100);
        listeningMode = prefs.getBoolean(KEY_LISTENING, false);
        panelOpacity = prefs.getInt(KEY_OPACITY, OPACITY_STEPS[0]);
        // 词典库提前打开（导入/查词共用；后台线程 search 不依赖 context）
        OnsDictStore.get().init(activity);
        OnsDeinflector.init(activity);  // 词形还原全量规则（assets/deinflect_ja.json）
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

    /**
     * 面板可见性回调（悬浮窗宿主用）：开合后同步覆盖窗触摸放行。
     * 不能只靠 OnLayoutChangeListener——VISIBLE→GONE 时视图 bounds 不变、
     * 布局回调不触发，覆盖窗会停留在可触摸态吃掉全部游戏输入（Artemis 触摸失灵根因）。
     */
    public void setPanelVisibilityHook(Runnable hook) {
        panelVisibilityHook = hook;
    }

    private Runnable panelVisibilityHook;

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
        if (expanded) {
            applyOpacity();
            applyPanelWidth();
            applyAdaptiveHeight();
            refresh();
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
            android.widget.FrameLayout.LayoutParams flp =
                    (android.widget.FrameLayout.LayoutParams) panel.getLayoutParams();
            flp.width = w;
            flp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            panel.setLayoutParams(flp);
        }
    };

    private void applyPanelWidth() {
        if (panel == null) return;
        boolean landscape = activity.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        android.widget.FrameLayout.LayoutParams lp =
                (android.widget.FrameLayout.LayoutParams) panel.getLayoutParams();
        if (!landscape) {
            lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT;
            lp.gravity = Gravity.BOTTOM;
            panel.setLayoutParams(lp);
            return;
        }
        panelWidthRetry = 0;
        panel.post(applyPanelWidthOnce);
    }

    /**
     * 高度自适应：面板 WRAP_CONTENT 随内容收缩；内容超出屏高 60% 时，
     * 把可见滚动区压到剩余空间并锁定面板高度，内容转内部滚动。
     */
    private void applyAdaptiveHeight() {
        if (panel == null) return;
        panel.getLayoutParams().height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
        mainScroller.getLayoutParams().height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
        historyScroller.getLayoutParams().height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
        panel.requestLayout();
        panel.post(() -> {
            if (panel == null || !expanded) return;
            ScrollView visible = historyMode ? historyScroller : mainScroller;
            if (visible.getVisibility() != View.VISIBLE) return;
            int max = Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.6f);
            int content = panel.getHeight();
            if (content <= max) return;
            int others = content - visible.getHeight();
            visible.getLayoutParams().height = Math.max(dp(60), max - others);
            panel.getLayoutParams().height = max;
            panel.requestLayout();
        });
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
                    p.edit().putBoolean(KEY_TTS_AUTO, ttsAuto).apply();
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

        // 语速行：显示当前百分比，点行循环档位（系统 TTS 与 MultiTTS 共用）
        TextView rateValue = new TextView(activity);
        Runnable syncRateText = () -> rateValue.setText(activity.getString(
                R.string.engine_ons_extract_opacity_fmt, ttsRate));
        syncRateText.run();
        LinearLayout rateRow = settingNavRow(R.drawable.ic_volume,
                R.string.engine_ons_extract_rate, rateValue);
        rateRow.setOnClickListener(v -> {
            ttsRate = nextStep(RATE_STEPS, ttsRate);
            p.edit().putInt(KEY_TTS_RATE, ttsRate).apply();
            syncRateText.run();
            if (tts != null) {
                try { tts.setSpeechRate(ttsRate / 100f); } catch (Throwable ignored) {}
            }
        });
        box.addView(rateRow);

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

        // 透明度行：显示当前百分比，点行循环档位
        TextView opacityValue = new TextView(activity);
        Runnable updateOpacityText = () -> opacityValue.setText(activity.getString(
                R.string.engine_ons_extract_opacity_fmt, panelOpacity));
        updateOpacityText.run();
        LinearLayout opacityRow = settingNavRow(R.drawable.ic_settings,
                R.string.engine_ons_extract_opacity, opacityValue);
        opacityRow.setOnClickListener(v -> {
            cycleOpacity();
            updateOpacityText.run();
        });
        box.addView(opacityRow);

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

    private static int nextStep(int[] steps, int current) {
        int index = 0;
        for (int i = 0; i < steps.length; i++) {
            if (steps[i] == current) index = i;
        }
        return steps[(index + 1) % steps.length];
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
    private void toggleHistoryOverlay() {
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
            // 主线程先取当句字节引用（配对时已缓存），后台只负责落盘，
            // 避免后台再取时缓存已被下一句覆盖
            byte[] pairedVoice = bridge.getVoiceName().isEmpty() ? null : bridge.ensureVoiceBytes();
            persistHistoryVoice(bridge.getVoiceName(), pairedVoice);
            lastHistorySentence = sentence;
            listeningRevealed = false;
            selectedToken = -1;
            dictRequestId++; // 翻句使在途查词失效
            translateRequestId++; // 翻句使在途翻译失效
            defGroups = null;
            matchedRange = null; // 旧句高亮区间不跨句
            defSentence = null;
            if (transView != null) {
                transView.setTag(null); // 翻句清掉旧译文
                transView.setText("");
            }
        }
        // 自动朗读在面板隐藏时也要生效（TTS 直呼线程安全）；配对语音由游戏播放，不重复播。
        // 生效判定在 autoPlayForNewSentence 内（面板「自动」快调 OR 设置页「自动朗读」全局开关）
        autoPlayForNewSentence(sentence);
        if (!expanded || panel == null) return;
        final String sentenceFinal = sentence;
        final String voiceName = bridge.getVoiceName();
        final boolean hideForListening = listeningMode && !voiceName.isEmpty() && !listeningRevealed;
        final List<OnsDictStore.Group> groups = defGroups;
        final String lookupTerm = defSentence;
        final boolean hasTrans = transView.getTag() != null;
        // 释义路由到顶部独立悬浮窗（查词命中即显示；翻句 defGroups=null 时收起）。
        // 不受面板展开状态影响：悬浮窗独立于剧情文本框（jidoujisho 式顶部释义卡）
        if (groups != null && !groups.isEmpty()) {
            OnsDictOverlay.show(activity, lookupTerm, groups, this::makeWordCard);
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
        // 同句重渲染（打字机 flush）保留扫描高亮与点选状态；翻句由调用方显式清位
        if (!text.equals(currentSentence)) {
            matchedRange = null;
            selectedToken = -1;
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
        if (idx < 0 || idx >= unitRanges.size() || sentenceSpan == null) return;
        selectedToken = idx;
        matchedRange = null;
        applyTokenSelection();
        scanFrom(start);
    }

    /** 后台扫描：结果刷新到释义区；代次号丢弃过期结果（快速连按选词）。
     *  扫描循环内也查代次——过期扫描立刻中止，不再空跑几十次词典查询；
     *  全程捕获异常：词典库异常（损坏/满盘）降级为无结果，不杀进程。 */
    private void scanFrom(final int startPos) {
        final int requestId = ++dictRequestId;
        final String text = currentSentence;
        scanExecutor.execute(() -> {
            try {
                if (!OnsDictStore.get().hasDictionary()) {
                    main.post(() -> toast(R.string.engine_ons_extract_dict_none));
                    return;
                }
                ScanHit hit = isCjkText(text)
                        ? scanByChar(text, startPos, requestId)
                        : scanByWordTwoPhase(text, startPos, requestId);
                if (requestId != dictRequestId) return;
                if (hit == null) {
                    // 已加载词典但未命中 ≠ 未导入词典：两句文案分开，避免误判成库为空
                    boolean noDict = !OnsDictStore.get().hasDictionary();
                    main.post(() -> toast(noDict
                            ? R.string.engine_ons_extract_dict_none
                            : R.string.engine_ons_extract_def_none));
                    return;
                }
                defGroups = hit.groups;
                defSentence = hit.matched;
                matchedRange = hit.range;
                com.core.diag.DiagLog.debug("dict", "hit: " + hit.matched
                        + " groups=" + hit.groups.size());
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

        ScanHit(String matched, int[] range, List<OnsDictStore.Group> groups) {
            this.matched = matched;
            this.range = range;
            this.groups = groups;
        }
    }

    /** 词典查一个候选（含词形还原变体），命中返回释义组。 */
    private List<OnsDictStore.Group> searchWithDeinflect(String cand) {
        List<OnsDictStore.Group> groups = OnsDictStore.get().search(cand, 5);
        if (groups != null && !groups.isEmpty()) return groups;
        for (String root : OnsDeinflector.deinflect(cand)) {
            if (root.equals(cand)) continue;
            groups = OnsDictStore.get().search(root, 5);
            if (groups != null && !groups.isEmpty()) return groups;
        }
        return null;
    }

    /** CJK 逐字递减扫描：从 startPos 起逐次缩短尾部长度，首个命中即返回。
     *  requestId 过期（新一轮点选/翻句）立即中止。 */
    private ScanHit scanByChar(String text, int startPos, int requestId) {
        final int maxLen = Math.min(text.length() - startPos, 40);
        for (int len = maxLen; len >= 1; len--) {
            if (requestId != dictRequestId) return null;
            String cand = text.substring(startPos, startPos + len);
            if (isRejectable(cand)) continue;
            List<OnsDictStore.Group> groups = searchWithDeinflect(cand);
            if (groups != null && !groups.isEmpty()) {
                return new ScanHit(cand, new int[]{startPos, startPos + len}, groups);
            }
        }
        return null;
    }

    /**
     * 空格分词语言两阶段扫描（TrackReader _decrementalScanByWordTwoPhase）：
     * 阶段一：首词词形还原，构建「原句 + 还原句」车队；
     * 阶段二：每辆车做纯精确词递减扫描（首词起到尾词止）。
     */
    private ScanHit scanByWordTwoPhase(String text, int startPos, int requestId) {
        java.util.List<int[]> origSpans = buildWordSpans(text);
        if (origSpans.isEmpty()) return null;
        int startIdx = findWordIndex(origSpans, startPos);
        if (startIdx < 0) return null;
        String firstWord = text.substring(origSpans.get(startIdx)[0], origSpans.get(startIdx)[1]);

        // 车队：原句 + 首词替换为各还原形的句子
        java.util.List<String> vehicles = new java.util.ArrayList<>();
        vehicles.add(text);
        for (String root : OnsDeinflector.deinflect(firstWord)) {
            if (root.equals(firstWord)) continue;
            String replaced = text.substring(0, origSpans.get(startIdx)[0]) + root
                    + text.substring(origSpans.get(startIdx)[1]);
            if (!vehicles.contains(replaced)) vehicles.add(replaced);
        }

        for (String vehicle : vehicles) {
            if (requestId != dictRequestId) return null;
            java.util.List<int[]> vSpans = buildWordSpans(vehicle);
            int vi = findWordIndex(vSpans, origSpans.get(startIdx)[0]);
            if (vi < 0) continue;
            for (int e = vSpans.size() - 1; e >= vi; e--) {
                if (requestId != dictRequestId) return null;
                String cand = vehicle.substring(vSpans.get(vi)[0], vSpans.get(e)[1]);
                if (isRejectable(cand)) continue;
                List<OnsDictStore.Group> groups = OnsDictStore.get().search(cand, 5);
                if (groups != null && !groups.isEmpty()) {
                    // 命中跨度映射回原句（还原句与原句共享起点，尾词按原句词跨度折算）
                    int endIdx = Math.min(startIdx + (e - vi), origSpans.size() - 1);
                    int[] range = {origSpans.get(startIdx)[0], origSpans.get(endIdx)[1]};
                    return new ScanHit(cand, range, groups);
                }
            }
        }
        return null;
    }

    private void dismissDef() {
        defGroups = null;
        defSentence = null;
        OnsDictOverlay.hide();
        refresh();
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

        // 字段映射（AnkiCardConfig）：逻辑槽位 → Anki 模型字段名，参考
        // web game text 扩展 ankiFieldMap 方案；模型不存在时默认模型兜底创建
        final String deckName = com.core.anki.AnkiCardConfig.deck(activity);
        final String modelName = com.core.anki.AnkiCardConfig.model(activity);
        final java.util.Map<String, String> fieldMap = com.core.anki.AnkiCardConfig.fieldMap(activity);
        final String voiceSource = com.core.anki.AnkiCardConfig.voiceSource(activity);
        final boolean captureOnCard = com.core.anki.AnkiCardConfig.captureOnCard(activity);

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

                // 模型解析：已存在 → 读真实字段列表；不存在 → 默认模型四字段兜底创建；
                // 自定义模型名不存在则报错（不能猜测用户模型的字段组合）
                Long modelId = helper.findModelId(modelName);
                String[] modelFields;
                if (modelId != null) {
                    String[] names = helper.getModelFieldNames(modelId);
                    if (names == null || names.length == 0) {
                        main.post(() -> toast(R.string.engine_ons_extract_anki_failed));
                        return;
                    }
                    modelFields = names;
                } else if (com.core.anki.AnkiCardConfig.DEFAULT_MODEL.equals(modelName)) {
                    modelId = helper.getOrCreateWordModel(modelName);
                    modelFields = new String[]{
                            "Word", "Reading", "Meaning", "Sentence", "Image", "Audio"};
                } else {
                    final String missing = modelName;
                    main.post(() -> toast(activity.getString(
                            R.string.engine_ons_extract_anki_model_missing, missing)));
                    return;
                }
                if (modelId == null) {
                    main.post(() -> toast(R.string.engine_ons_extract_anki_failed));
                    return;
                }
                Long deckId = helper.getOrCreateDeck(deckName);
                if (deckId == null) {
                    main.post(() -> toast(R.string.engine_ons_extract_anki_failed));
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
                slots.put(com.core.anki.AnkiCardConfig.SLOT_MEANING, meaning.toString());
                slots.put(com.core.anki.AnkiCardConfig.SLOT_SENTENCE,
                        android.text.Html.escapeHtml(sentence));
                slots.put(com.core.anki.AnkiCardConfig.SLOT_PAGE,
                        android.text.Html.escapeHtml(bridge.getPageText()));
                slots.put(com.core.anki.AnkiCardConfig.SLOT_TRANSLATION,
                        lastTranslation == null ? "" : lastTranslation);

                File dir = new File(activity.getExternalFilesDir(null), "extract");
                dir.mkdirs();

                // 截图槽位：制卡时现截（主线程同步等待），失败回退最近一张手动截图
                String shotField = fieldMap.get(com.core.anki.AnkiCardConfig.SLOT_SCREENSHOT);
                if (shotField != null && !shotField.isEmpty()) {
                    Bitmap shot = captureOnCard ? captureGameFrame(2500) : null;
                    if (shot == null) shot = bridge.getScreenshot();
                    if (shot != null && !shot.isRecycled()) {
                        try {
                            File png = new File(dir, "anki_shot_" + timestamp() + ".png");
                            try (FileOutputStream fos = new FileOutputStream(png)) {
                                shot.compress(Bitmap.CompressFormat.PNG, 95, fos);
                            }
                            String mark = helper.addMedia(png, png.getName(), "image");
                            if (mark != null) {
                                slots.put(com.core.anki.AnkiCardConfig.SLOT_SCREENSHOT, mark);
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

                // 单词语音槽位：TTS 生成
                String wordAudioField = fieldMap.get(com.core.anki.AnkiCardConfig.SLOT_WORD_AUDIO);
                if (wordAudioField != null && !wordAudioField.isEmpty()) {
                    byte[] wav = ttsSynthesizeQuiet(term);
                    if (wav != null) {
                        try {
                            File vf = new File(dir, "anki_w_" + timestamp() + ".wav");
                            try (FileOutputStream fos = new FileOutputStream(vf)) {
                                fos.write(wav);
                            }
                            String mark = helper.addMedia(vf, vf.getName(), "audio");
                            if (mark != null) {
                                slots.put(com.core.anki.AnkiCardConfig.SLOT_WORD_AUDIO, mark);
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "card word audio import failed", t);
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
                Long noteId = helper.addNote(modelId, deckId, fields,
                        new java.util.HashSet<>(java.util.Collections.singletonList("TyranorNext")));
                main.post(() -> toast(noteId != null
                        ? R.string.engine_ons_extract_word_added
                        : R.string.engine_ons_extract_anki_failed));
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
                if (expanded) mainScrollToTrans();
            });
        }, "ons-translate").start();
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
        String[] srcCodes = {"auto", "zh", "en", "ja", "ko"};
        String[][] codeNames = {{"zh", "中文"}, {"en", "英语"}, {"ja", "日语"}, {"ko", "韩语"}};
        TextView srcView = new TextView(activity);
        TextView tgtView = new TextView(activity);
        Runnable syncLangs = () -> {
            String src = p.getString(OnsTranslateClient.KEY_SOURCE, OnsTranslateClient.DEFAULT_SOURCE);
            String srcName = "auto".equals(src)
                    ? activity.getString(R.string.engine_ons_translate_auto)
                    : codeName(codeNames, src);
            srcView.setText(activity.getString(
                    R.string.engine_ons_translate_src_set, srcName));
            tgtView.setText(activity.getString(
                    R.string.engine_ons_translate_tgt, codeName(codeNames, OnsTranslateClient.targetCode(activity))));
        };
        srcView.setTextColor(TEXT_BODY);
        srcView.setTextSize(13);
        int pad8 = dp(8);
        srcView.setPadding(0, pad8, 0, 0);
        srcView.setOnClickListener(v -> {
            String cur = p.getString(OnsTranslateClient.KEY_SOURCE, OnsTranslateClient.DEFAULT_SOURCE);
            p.edit().putString(OnsTranslateClient.KEY_SOURCE, nextCode(srcCodes, cur)).apply();
            syncLangs.run();
        });
        box.addView(srcView);
        tgtView.setTextColor(TEXT_BODY);
        tgtView.setTextSize(13);
        tgtView.setPadding(0, pad8, 0, 0);
        tgtView.setOnClickListener(v -> {
            String next = nextCode(new String[]{"zh", "en", "ja", "ko"},
                    OnsTranslateClient.targetCode(activity));
            p.edit().putString(OnsTranslateClient.KEY_TARGET, codeName(codeNames, next)).apply();
            syncLangs.run();
        });
        box.addView(tgtView);
        syncLangs.run();
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

    private static String nextCode(String[] codes, String current) {
        for (int i = 0; i < codes.length; i++) {
            if (codes[i].equals(current)) {
                return codes[(i + 1) % codes.length];
            }
        }
        return codes[0];
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
     * 面板按键路由，宿主 dispatchKeyEvent 在虚拟鼠标之前调用。
     * 面板收起时只认 RB（呼出面板），其余透传给虚拟鼠标与游戏；
     * 面板展开时消费全部按键（手柄不再注入游戏），返回 true 即已消费。
     */
    public boolean handleKey(KeyEvent event) {
        if (event == null) return false;
        int code = event.getKeyCode();
        if (!expanded) {
            if (code == KeyEvent.KEYCODE_BUTTON_R1 && event.getAction() == KeyEvent.ACTION_DOWN) {
                togglePanel();
                return true;
            }
            return false;
        }
        // 面板开着：吞掉非动作相位，防止长按重复/抬起透传游戏
        if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
                scrollFocused(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                scrollFocused(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                moveTokenSelection(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                moveTokenSelection(1);
                return true;
            case KeyEvent.KEYCODE_BUTTON_A:
                // webgametxt 词典语义 A=确认查词；未选词时回退播放（朗读/重播）
                if (!historyMode && selectedToken >= 0 && selectedToken < unitRanges.size()) {
                    selectUnit(selectedToken, unitRanges.get(selectedToken)[0]);
                } else {
                    playVoice();
                }
                return true;
            case KeyEvent.KEYCODE_SPACE:
                playVoice();
                return true;
            case KeyEvent.KEYCODE_BUTTON_B:
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
                exitStep();
                return true;
            case KeyEvent.KEYCODE_BUTTON_X:
            case KeyEvent.KEYCODE_T:
                translateCurrent();
                return true;
            case KeyEvent.KEYCODE_C:
                copyText();
                return true;
            case KeyEvent.KEYCODE_BUTTON_Y:
                // 有查词结果制词卡，否则句卡
                if (defGroups != null && !defGroups.isEmpty()) makeWordCard();
                else sendToAnki();
                return true;
            case KeyEvent.KEYCODE_BUTTON_START:
                sendToAnki();
                return true;
            case KeyEvent.KEYCODE_BUTTON_R1:
                scrollFocused(event.isLongPress() ? -1 : 1);
                return true;
            case KeyEvent.KEYCODE_BUTTON_L1:
                scrollFocused(-1);
                return true;
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_MUTE:
                return false; // 系统音量键放行，面板展开期间仍可调音量
            default:
                return true; // webgametxt：面板打开时其余按键全拦，不透传游戏
        }
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
        selectUnit(next, unitRanges.get(next)[0]);
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
        byte[] bytes = facade.ensureVoiceBytes();
        if (bytes == null) {
            // 无配对语音：TTS 开启时朗读，否则提示
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
     * 历史回放：优先读配对时落盘的语音缓存（字节在配对时已由 bridge 活捕获），
     * 无缓存再按存储名补读档案；都失败回退 TTS 朗读。
     */
    private void playHistoryEntry(HistoryEntry entry) {
        stopVoice();
        if (!entry.voiceName.isEmpty()) {
            File cached = historyVoiceFile(entry.voiceName);
            if (cached != null && cached.exists() && cached.length() > 0) {
                try {
                    player = new MediaPlayer();
                    player.setDataSource(cached.getAbsolutePath());
                    player.setOnCompletionListener(mp -> stopVoice());
                    player.prepare();
                    player.start();
                    return;
                } catch (Throwable t) {
                    Log.w(TAG, "history voice playback failed for " + entry.voiceName, t);
                    stopVoice();
                }
            }
            byte[] bytes = facade.readVoiceBytesByName(entry.voiceName);
            if (bytes != null) {
                playVoiceBytes(bytes, entry.voiceName);
                return;
            }
        }
        speakText(entry.sentence);
    }

    private File historyVoiceFile(String voiceName) {
        String name = sanitize(voiceName);
        if (name.isEmpty()) return null;
        File dir = new File(activity.getCacheDir(), "ons_extract_history");
        dir.mkdirs();
        return new File(dir, name);
    }

    /**
     * 配对时把当前句语音字节写入缓存目录（后台落盘），供历史回放。
     * 引擎档案层的按名补读在 UI 线程不可靠，落盘是历史回放的主路径。
     */
    private void persistHistoryVoice(String voiceName, byte[] bytes) {
        if (voiceName == null || voiceName.isEmpty() || bytes == null || bytes.length == 0) return;
        new Thread(() -> {
            try {
                File out = historyVoiceFile(voiceName);
                if (out == null || (out.exists() && out.length() == bytes.length)) return;
                try (FileOutputStream fos = new FileOutputStream(out)) {
                    fos.write(bytes);
                }
                trimHistoryVoiceDir(out.getParentFile());
            } catch (Throwable t) {
                Log.w(TAG, "persistHistoryVoice failed for " + voiceName, t);
            }
        }, "ons-history-voice").start();
    }

    /** 语音缓存目录封顶：只留最近 100 条（每条可达 MB 级，长会话无限累积
     *  会吃掉数百 MB；cacheDir 虽可被系统回收，主动封顶更稳）。 */
    private static void trimHistoryVoiceDir(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files == null || files.length <= 100) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - 100; i++) {
            if (!files[i].delete()) {
                Log.w("OnsExtractPanel", "trim history voice failed: " + files[i].getName());
            }
        }
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

    private void ensureTts() {
        // MultiTTS 作为系统 TTS 引擎绑定仅在高级引擎=MultiTTS 时生效
        String desired = multiTtsEnabled
                && OnsTtsEngines.ENGINE_MULTI.equals(OnsTtsEngines.engine(activity))
                ? MULTI_TTS_PACKAGE : null;
        if (tts != null && (activeTtsEngine == null
                ? desired == null : activeTtsEngine.equals(desired))) return;
        if (tts != null) {
            try { tts.shutdown(); } catch (Throwable ignored) {}
            tts = null;
            ttsReady = false;
        }
        android.speech.tts.TextToSpeech.OnInitListener listener = status -> {
            ttsReady = status == android.speech.tts.TextToSpeech.SUCCESS;
            Log.i(TAG, "tts init ready=" + ttsReady + " engine=" + activeTtsEngine);
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

    private void speakSentence() {
        ExtractFacade bridge = facade;
        String sentence = bridge.getSentenceText();
        if (sentence.isEmpty()) sentence = bridge.getPageText();
        speakText(sentence);
    }

    private void speakText(String sentence) {
        if (sentence == null || sentence.isEmpty()) return;
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
        if (!ttsReady) return;
        try {
            tts.setLanguage(ttsLocale(sentence));
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
            tts.stop();
            tts.speak(sentence, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "ons_extract");
        } catch (Throwable t) {
            Log.w(TAG, "tts speak failed", t);
        }
    }

    private void stopTts() {
        if (tts != null) {
            try { tts.stop(); } catch (Throwable ignored) {}
        }
    }

    private void autoPlayForNewSentence(String sentence) {
        if (sentence == null || sentence.isEmpty()) return;
        if (sentence.equals(lastSpokenSentence)) return;
        // 生效条件 = 面板「自动」快调 OR 设置页「自动朗读」全局开关（后者面板隐藏
        // 也生效——隐藏剧情文本框时更新文本后照常自动朗读）
        boolean autoOn = ttsAuto || OnsTtsEngines.autoRead(activity);
        if (!autoOn) return;
        lastSpokenSentence = sentence;
        // 有配对语音时游戏自身已在播放，自动模式不重复播；仅对无语音句子 TTS，
        // 重播由用户手动点「播放」按钮。
        // 自动路径不受面板「TTS 朗读」开关牵制（面板隐藏时该开关不可达，会把全局
        // 自动朗读静音）——ttsEnabled 只管手动「播放」按钮。
        if (facade.getVoiceName().isEmpty() && (ttsEnabled || OnsTtsEngines.autoRead(activity))) {
            speakSentence();
        }
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
     * AnkiDroid 一键制卡（句卡）：Front=当前句，Back=整页文本 + 截图 + 语音（有则带上）。
     * ContentProvider 调用与媒体拷贝较慢，整体放后台线程执行。
     */
    private void sendToAnki() {
        final ExtractFacade bridge = facade;
        final String sentence = bridge.getSentenceText().isEmpty()
                ? bridge.getPageText() : bridge.getSentenceText();
        final String page = bridge.getPageText();
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
                Long deckId = helper.getOrCreateDeck(com.core.anki.AnkiDroidHelper.DEFAULT_DECK);
                Long modelId = helper.getOrCreateModel(com.core.anki.AnkiDroidHelper.DEFAULT_MODEL);
                if (deckId == null || modelId == null) {
                    main.post(() -> toast(R.string.engine_ons_extract_anki_failed));
                    return;
                }

                StringBuilder back = new StringBuilder();
                File dir = new File(activity.getExternalFilesDir(null), "extract");
                dir.mkdirs();
                // 截图（最近一张）→ Back 顶部
                Bitmap shot = bridge.getScreenshot();
                File shotFile = new File(dir, "anki_" + timestamp() + ".png");
                boolean hasShot = false;
                if (shot != null && !shot.isRecycled()) {
                    try (FileOutputStream fos = new FileOutputStream(shotFile)) {
                        shot.compress(Bitmap.CompressFormat.PNG, 95, fos);
                    }
                    String mark = helper.addMedia(shotFile, shotFile.getName(), "image");
                    if (mark != null) {
                        back.append(mark);
                        hasShot = true;
                    }
                }
                // 语音
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
                        if (hasShot) back.append("<br>");
                        back.append(mark);
                    }
                }
                // 整页文本
                if (!page.isEmpty()) {
                    back.append("<br><br>").append(android.text.Html.escapeHtml(page));
                }

                Long noteId = helper.addNote(modelId, deckId,
                        new String[]{sentence, back.toString()},
                        new java.util.HashSet<>(java.util.Collections.singletonList("TyranorNext")));
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
