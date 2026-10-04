package com.core.ons;

import android.app.Activity;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.core.engine.R;

import java.util.function.Supplier;

/**
 * 游戏右缘固定竖排按键组（独立于可拖动悬浮球）：
 * 顶部折叠键（折叠后仍在最顶端）+ 剧情文本框开/关、截图、游戏设置、
 * 音量（百分比滑条）、点击模式切换（虚拟鼠标/触摸，宿主未提供回调时不显示）、
 * 回到主页（确认后退出游戏回软件主页面）。
 * 尺寸/底色/描边/图标色对齐左侧虚拟按键列（40dp、深色半透明底 + 白色图标），
 * 整列贴右缘（避开系统导航条）；文本框/截图/存档/设置/点击模式回调由宿主提供。
 */
public class OnsSideButtons {

    /** 对齐 ONScripter.makeVirtualButtonBackground 非激活态配色。 */
    private static final int BG = 0xA6101010;
    private static final int STROKE = 0x87FFFFFF;
    /** 对齐 ONScripter.CONTROL_BUTTON_SIZE_DP / _GAP_DP。 */
    private static final int BUTTON_DP = 40;
    private static final int GAP_DP = 5;
    /** 贴缘边距（dp），叠加系统导航条 inset。 */
    private static final int EDGE_MARGIN_DP = 2;
    /** 折叠键距顶（dp）；左右两侧同一水平面。 */
    private static final int TOP_MARGIN_DP = 12;
    private static final String PREFS = "ons_side_buttons";
    private static final String KEY_VISIBLE = "side_visible";

    /** 安装到覆盖层右缘。返回按键组根视图（光标命中用）。 */
    public static android.view.View install(ViewGroup overlay, Runnable onToggleTextFrame,
                                            Runnable onScreenshot, Runnable onSettings,
                                            Runnable onMouseModeToggle,
                                            Supplier<Boolean> mouseMode) {
        Activity activity = (Activity) overlay.getContext();
        LinearLayout container = buildContainer(activity, onToggleTextFrame, onScreenshot,
                onSettings, onMouseModeToggle, mouseMode);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.END);
        lp.topMargin = dp(activity, TOP_MARGIN_DP);
        // 三键导航条叠在右缘（横屏 ~126px）：按键列必须避开，否则点按会被
        // 系统 Back/Home/Recents 吃掉（表现为「点了没反应/直接回桌面」）。
        // onCreate 时窗口 insets 未就绪，attach 后再校准一次。
        lp.rightMargin = edgeMargin(activity, true);
        overlay.addView(container, lp);
        recalibrateInsets(activity, container, true);
        return container;
    }

    /**
     * 以独立小悬浮窗安装（NativeActivity 宿主用）：小窗只占按键自身区域，
     * 其余触摸全部穿透给游戏；返回按键组根视图。
     */
    public static android.view.View installAsWindow(Activity activity, Runnable onToggleTextFrame,
                                                    Runnable onScreenshot, Runnable onSettings,
                                                    Runnable onMouseModeToggle,
                                                    Supplier<Boolean> mouseMode) {
        LinearLayout container = buildContainer(activity, onToggleTextFrame, onScreenshot,
                onSettings, onMouseModeToggle, mouseMode);
        android.view.WindowManager.LayoutParams lp = new android.view.WindowManager.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT);
        // 横屏时系统导航栏占右侧（应用窗 2274 ≠ 屏宽 2400）：按系统栏 insets
        // 计算可用区，TOP|START 绝对锚定（insets 适配标志在这类窗口上表现不一致）
        int[] anchor = anchorTopEnd(activity, dp(activity, BUTTON_DP + 4),
                dp(activity, EDGE_MARGIN_DP), dp(activity, TOP_MARGIN_DP));
        lp.x = anchor[0];
        lp.y = anchor[1];
        activity.getWindowManager().addView(container, lp);
        return container;
    }

    /** 应用可用区右上角锚点（TOP|START 坐标）：[x, y]，insets 不可用时退化为屏宽。 */
    static int[] anchorTopEnd(Activity activity, int viewWidthPx, int rightMarginPx, int topMarginPx) {
        int displayW = activity.getResources().getDisplayMetrics().widthPixels;
        int rightInset = 0;
        int leftInset = 0;
        int topInset = 0;
        android.view.WindowInsets ins = activity.getWindow().getDecorView().getRootWindowInsets();
        if (ins != null) {
            android.graphics.Insets sb = ins.getInsets(android.view.WindowInsets.Type.systemBars());
            rightInset = sb.right;
            leftInset = sb.left;
            topInset = sb.top;
        }
        int usable = Math.max(viewWidthPx, displayW - leftInset - rightInset);
        int x = leftInset + Math.max(0, usable - viewWidthPx - rightMarginPx);
        return new int[]{x, topInset + topMarginPx};
    }

    /** 贴缘边距 = EDGE_MARGIN_DP + 对应侧系统栏 inset（导航条宽度）。 */
    static int edgeMargin(Activity activity, boolean right) {
        try {
            android.view.WindowInsets ins = activity.getWindow().getDecorView().getRootWindowInsets();
            if (ins != null) {
                android.graphics.Insets sb = ins.getInsets(android.view.WindowInsets.Type.systemBars());
                return dp(activity, EDGE_MARGIN_DP) + (right ? sb.right : sb.left);
            }
        } catch (Throwable ignored) {
        }
        return dp(activity, EDGE_MARGIN_DP);
    }

    /** insets 在 onCreate 时未就绪：attach 后再校准一次贴缘边距。 */
    private static void recalibrateInsets(Activity activity, ViewGroup column, boolean right) {
        column.post(() -> {
            int margin = edgeMargin(activity, right);
            ViewGroup.LayoutParams p = column.getLayoutParams();
            if (p instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) p;
                if (right ? flp.rightMargin != margin : flp.leftMargin != margin) {
                    if (right) flp.rightMargin = margin;
                    else flp.leftMargin = margin;
                    column.setLayoutParams(flp);
                }
            }
        });
    }

    /** 容器 = 顶部折叠键 + 按键列（折叠只藏按键列，折叠键常驻最顶端）。 */
    private static LinearLayout buildContainer(Activity activity, Runnable onToggleTextFrame,
                                               Runnable onScreenshot,
                                               Runnable onSettings, Runnable onMouseModeToggle,
                                               Supplier<Boolean> mouseMode) {
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setClickable(true);
        container.setFocusable(true);

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(button(activity, R.drawable.ic_chat,
                R.string.engine_ons_side_text_frame, onToggleTextFrame));
        column.addView(button(activity, R.drawable.ic_camera,
                R.string.engine_ons_side_screenshot, onScreenshot));
        column.addView(button(activity, R.drawable.ic_settings,
                R.string.engine_ons_side_settings, onSettings));
        column.addView(button(activity, R.drawable.ic_volume,
                R.string.engine_ons_side_volume, () -> showVolumePopup(activity)));
        if (onMouseModeToggle != null && mouseMode != null) {
            column.addView(clickModeButton(activity, onMouseModeToggle, mouseMode));
        }
        column.addView(button(activity, R.drawable.ic_home,
                R.string.engine_ons_side_home, () -> confirmHome(activity)));
        container.addView(column);

        boolean visible = prefs(activity).getBoolean(KEY_VISIBLE, true);
        column.setVisibility(visible ? View.VISIBLE : View.GONE);
        ImageView toggle = button(activity,
                visible ? R.drawable.ons_toggle_up : R.drawable.ons_toggle_down,
                R.string.engine_ons_side_toggle, null);
        toggle.setOnClickListener(v -> {
            boolean now = column.getVisibility() != View.VISIBLE;
            column.setVisibility(now ? View.VISIBLE : View.GONE);
            toggle.setImageResource(now ? R.drawable.ons_toggle_up : R.drawable.ons_toggle_down);
            prefs(activity).edit().putBoolean(KEY_VISIBLE, now).apply();
        });
        container.addView(toggle, 0);
        return container;
    }

    /** 点击模式键：图标随当前模式（虚拟鼠标 ↔ 触摸），状态由宿主持有。 */
    private static ImageView clickModeButton(Activity activity, Runnable onToggle,
                                             Supplier<Boolean> mouseMode) {
        final ImageView[] holder = new ImageView[1];
        ImageView iv = button(activity, mouseMode.get()
                        ? R.drawable.ic_cursor : R.drawable.ic_touch,
                R.string.engine_ons_side_click_mode, () -> {
                    onToggle.run();
                    holder[0].setImageResource(Boolean.TRUE.equals(mouseMode.get())
                            ? R.drawable.ic_cursor : R.drawable.ic_touch);
                });
        holder[0] = iv;
        return iv;
    }

    private static android.content.SharedPreferences prefs(Activity activity) {
        return activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
    }

    /** 媒体音量百分比滑条弹窗（游戏声音走 STREAM_MUSIC；拖动即生效）。 */
    private static void showVolumePopup(Activity activity) {
        try {
            AudioManager am = (AudioManager) activity.getSystemService(Activity.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            TextView label = new TextView(activity);
            label.setTextColor(0xFF1E293B);
            label.setTextSize(13);
            SeekBar seek = new SeekBar(activity);
            seek.setMax(100);
            seek.setProgress(Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max));
            Runnable sync = () -> label.setText(activity.getString(
                    R.string.engine_ons_side_volume_pct, seek.getProgress()));
            sync.run();
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int pct, boolean fromUser) {
                    if (fromUser) am.setStreamVolume(AudioManager.STREAM_MUSIC,
                            Math.round(pct * max / 100f), 0);
                    sync.run();
                }
                @Override public void onStartTrackingTouch(SeekBar bar) {
                }
                @Override public void onStopTrackingTouch(SeekBar bar) {
                }
            });
            LinearLayout box = new LinearLayout(activity);
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(activity, 16);
            box.setPadding(pad, dp(activity, 8), pad, 0);
            box.addView(label);
            box.addView(seek);
            new android.app.AlertDialog.Builder(activity)
                    .setTitle(R.string.engine_ons_side_volume)
                    .setView(box)
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } catch (Throwable ignored) {
        }
    }

    /** 回软件主页面：确认弹窗（防误触）→ 拉起主 App 主页并退出游戏。 */
    private static void confirmHome(Activity activity) {
        new android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.engine_ons_side_home)
                .setMessage(R.string.engine_ons_side_home_confirm)
                .setPositiveButton(R.string.engine_ons_side_home_ok, (d, w) -> {
                    Intent home = new Intent();
                    home.setClassName(activity.getPackageName(),
                            activity.getPackageName() + ".MainActivity");
                    home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    try {
                        activity.startActivity(home);
                    } catch (Throwable ignored) {
                        // 主页不可达时至少退出游戏回上级
                    }
                    activity.finish();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static ImageView button(Activity activity, int iconRes, int descRes, Runnable action) {
        ImageView iv = new ImageView(activity);
        iv.setImageResource(iconRes);
        iv.setColorFilter(0xFFFFFFFF); // 图标白色，对齐左键文字色（矢量本体为黑填充）
        iv.setContentDescription(activity.getString(descRes));
        int side = dp(activity, BUTTON_DP);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(side, side);
        lp.bottomMargin = dp(activity, GAP_DP);
        int pad = dp(activity, 8);
        iv.setPadding(pad, pad, pad, pad);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setBackground(rounded(activity));
        iv.setOnClickListener(v -> {
            try {
                if (action != null) action.run();
            } catch (Throwable ignored) {
            }
        });
        iv.setLayoutParams(lp);
        return iv;
    }

    private static GradientDrawable rounded(Activity activity) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(BG);
        d.setCornerRadius(dp(activity, 10));
        d.setStroke(dp(activity, 1), STROKE);
        return d;
    }

    private static int dp(Activity activity, int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
