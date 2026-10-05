package com.core.engine;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 游戏画面左缘竖排按键组（引擎适配按键的统一抽象，ONScripter/KRKR/Artemis 共用）：
 * 折叠键（ONS 同款 chevron 图标，折叠态持久化）+ 按键列。规格对齐 ONS 虚拟按键：
 * 40dp 圆角矩形（radius 10dp）、深色半透明底白字、激活态蓝底白描边、
 * 间距 5dp、距顶 12dp、贴缘 2dp + 系统栏 inset。
 * 两种安装形态：
 * - {@link #install(ViewGroup, String, List)}：加入宿主视图树（同进程视图合成的宿主，如 ONS/KRKR）；
 * - {@link #installAsWindow(Activity, String, List)}：独立小悬浮窗锚定左上
 *   （NativeActivity 宿主，如 Artemis——窗口只占按键自身区域，触摸不遮挡游戏）。
 * 按键语义：
 * - 点按式（{@link ButtonSpec#action}）：点击 = 按下+抬起；
 * - 按住式（{@link ButtonSpec#press}/{@link ButtonSpec#release}）：触摸按下/抬起
 *   （ONS SKIP=按住 Ctrl）；
 * - 高亮态（{@link ButtonSpec#active}）：AUTO 等开关态蓝底显示，
 *   状态变化后由宿主调 {@link #refreshActive(View)}。
 */
public final class EngineLeftButtons {

    private static final String PREFS = "engine_left_buttons";
    /** 对齐 ONScripter.makeVirtualButtonBackground 非激活/激活配色。 */
    private static final int BG = 0xA6101010;
    private static final int STROKE = 0x87FFFFFF;
    private static final int ACTIVE_BG = 0xD2007AFF;
    private static final int BUTTON_DP = 40;   // ONScripter.CONTROL_BUTTON_SIZE_DP
    private static final int GAP_DP = 5;       // ONScripter.CONTROL_BUTTON_GAP_DP
    private static final int EDGE_MARGIN_DP = 2;
    private static final int TOP_MARGIN_DP = 12;

    /** 单个按键：短标签（1-4 字符）+ 三种可选语义（见类注释）。 */
    public static final class ButtonSpec {
        public final String label;
        /** 点按式：点击触发（按下+抬起）。 */
        public final Runnable action;
        /** 按住式：触摸按下触发。与 action 互斥，设置后 action 被忽略。 */
        public final Runnable press;
        /** 按住式：触摸抬起/取消触发。 */
        public final Runnable release;
        /** 高亮态查询（null = 普通键）；变化后宿主调 {@link #refreshActive}。 */
        public final BooleanSupplier active;

        public ButtonSpec(String label, Runnable action) {
            this(label, action, null, null, null);
        }

        /** 按住式按键（如 SKIP=按住 Ctrl）。 */
        public static ButtonSpec hold(String label, Runnable press, Runnable release) {
            return new ButtonSpec(label, null, press, release, null);
        }

        /** 点按 + 开关高亮（如 AUTO）。 */
        public static ButtonSpec toggled(String label, Runnable action, BooleanSupplier active) {
            return new ButtonSpec(label, action, null, null, active);
        }

        public ButtonSpec(String label, Runnable action, Runnable press, Runnable release,
                          BooleanSupplier active) {
            this.label = label;
            this.action = action;
            this.press = press;
            this.release = release;
            this.active = active;
        }
    }

    private EngineLeftButtons() {
    }

    /** 加入宿主视图树左缘（overlay 顶层），内置折叠键。返回按键组根视图。 */
    public static View install(ViewGroup overlay, String prefsKey, List<ButtonSpec> buttons) {
        return install(overlay, prefsKey, buttons, true);
    }

    /**
     * 加入宿主视图树左缘。builtInToggle=false 时不装折叠键
     * （宿主自有开关经 {@link #applyVisibility} 驱动）。返回按键组根视图。
     */
    public static View install(ViewGroup overlay, String prefsKey, List<ButtonSpec> buttons,
                               boolean builtInToggle) {
        Activity activity = (Activity) overlay.getContext();
        LinearLayout container = buildContainer(activity, prefsKey, buttons, builtInToggle);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START);
        lp.topMargin = dp(activity, TOP_MARGIN_DP);
        // 左缘三键导航/挖孔 inset 避让；onCreate 时 insets 未就绪，attach 后校准
        lp.leftMargin = edgeMargin(activity);
        overlay.addView(container, lp);
        recalibrateInsets(activity, container);
        return container;
    }

    /** 独立小悬浮窗锚定左上（NativeActivity 宿主用），内置折叠键。返回按键组根视图。 */
    public static View installAsWindow(Activity activity, String prefsKey, List<ButtonSpec> buttons) {
        LinearLayout container = buildContainer(activity, prefsKey, buttons, true);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;  // 必须显式：缺省 gravity 会让 x/y 不按左上锚定
        // 横屏左缘系统栏占位：按 insets 锚定 TOP|START
        int leftInset = 0, topInset = 0;
        android.view.WindowInsets ins = activity.getWindow().getDecorView().getRootWindowInsets();
        if (ins != null) {
            android.graphics.Insets sb = ins.getInsets(android.view.WindowInsets.Type.systemBars());
            leftInset = sb.left;
            topInset = sb.top;
        }
        lp.x = leftInset + dp(activity, EDGE_MARGIN_DP);
        lp.y = topInset + dp(activity, TOP_MARGIN_DP);
        activity.getWindowManager().addView(container, lp);
        reanchorOnInsets(activity, container, false);
        return container;
    }

    /**
     * 悬浮窗锚定校准：创建时状态栏可能尚未隐去（游戏稍后才进沉浸式），按 display
     * inset 计算的 y 会永久错位一个状态栏高度（右缘实测 y=159 vs 左缘 32）。改为
     * 锚定活动自身窗口 frame（decorView 位置/宽度随系统栏显隐由系统自动更新），
     * attach、布局、insets 变化与延时多重触发，左右两缘恒同一原点。
     */
    private static void reanchorOnInsets(Activity activity, View container, boolean rightEdge) {
        View decor = activity.getWindow().getDecorView();
        Runnable reanchor = () -> {
            try {
                int[] loc = new int[2];
                decor.getLocationOnScreen(loc);
                WindowManager.LayoutParams lp =
                        (WindowManager.LayoutParams) container.getLayoutParams();
                int ny = loc[1] + dp(activity, TOP_MARGIN_DP);
                int nx = lp.x;
                if (rightEdge) {
                    int w = container.getWidth();
                    if (w > 0) nx = loc[0] + decor.getWidth() - w - dp(activity, EDGE_MARGIN_DP);
                } else {
                    nx = loc[0] + dp(activity, EDGE_MARGIN_DP);
                }
                if (nx != lp.x || ny != lp.y) {
                    lp.x = nx;
                    lp.y = ny;
                    activity.getWindowManager().updateViewLayout(container, lp);
                }
            } catch (Throwable ignored) {
            }
        };
        container.post(reanchor);
        container.postDelayed(reanchor, 1000);
        container.postDelayed(reanchor, 3000);
        container.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or2, ob) -> reanchor.run());
        container.setOnApplyWindowInsetsListener((v, insets) -> {
            reanchor.run();
            return insets;
        });
    }

    /** 展开显示（visible=false 折叠）按键列并持久化、同步折叠键图标。 */
    public static void applyVisibility(View container, boolean visible) {
        if (container == null || !(container.getTag() instanceof ColumnTag)) return;
        ColumnTag tag = (ColumnTag) container.getTag();
        if (!tag.selfToggle) return;  // 宿主自有开关时不接管
        setColumnVisible(container, visible);
        container.getContext().getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                .edit().putBoolean(tag.prefsKey, visible).apply();
        if (tag.toggleView != null) {
            tag.toggleView.setImageResource(visible
                    ? com.core.engine.R.drawable.ons_toggle_up
                    : com.core.engine.R.drawable.ons_toggle_down);
        }
    }

    /** 重算全部高亮态（AUTO 等开关状态变化后由宿主调用）。 */
    public static void refreshActive(View container) {
        if (container == null || !(container.getTag() instanceof ColumnTag)) return;
        ColumnTag tag = (ColumnTag) container.getTag();
        for (int i = 0; i < tag.column.getChildCount(); i++) {
            View child = tag.column.getChildAt(i);
            if (!(child instanceof TextView) || !(child.getTag() instanceof ButtonSpec)) continue;
            ButtonSpec spec = (ButtonSpec) child.getTag();
            styleButton((TextView) child, spec.active != null && spec.active.getAsBoolean());
        }
    }

    private static final class ColumnTag {
        LinearLayout column;
        ImageButton toggleView;
        String prefsKey;
        boolean selfToggle;
    }

    private static LinearLayout buildContainer(Activity activity, String prefsKey,
                                               List<ButtonSpec> buttons, boolean builtInToggle) {
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        ColumnTag tag = new ColumnTag();
        tag.prefsKey = prefsKey;
        tag.selfToggle = builtInToggle;

        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        for (ButtonSpec spec : buttons) {
            TextView tv = button(activity, spec);
            tv.setTag(spec);
            column.addView(tv);
        }
        tag.column = column;

        boolean visible = activity.getSharedPreferences(PREFS, Activity.MODE_PRIVATE)
                .getBoolean(prefsKey, true);
        column.setVisibility(visible ? View.VISIBLE : View.GONE);

        if (builtInToggle) {
            ImageButton toggle = new ImageButton(activity);
            toggle.setImageResource(visible
                    ? com.core.engine.R.drawable.ons_toggle_up
                    : com.core.engine.R.drawable.ons_toggle_down);
            // 与右侧折叠键（OnsSideButtons.button）同款：深色圆角矩形底 + 白色图标
            toggle.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            toggle.setColorFilter(Color.WHITE);
            int pad = dp(activity, 8);
            toggle.setPadding(pad, pad, pad, pad);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(activity, 10));
            bg.setColor(BG);
            bg.setStroke(dp(activity, 1), STROKE);
            toggle.setBackground(bg);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    dp(activity, BUTTON_DP), dp(activity, BUTTON_DP));
            tlp.bottomMargin = dp(activity, GAP_DP);
            toggle.setLayoutParams(tlp);
            toggle.setOnClickListener(v -> {
                boolean now = column.getVisibility() != View.VISIBLE;
                applyVisibility(container, now);
            });
            tag.toggleView = toggle;
            container.addView(toggle);  // 折叠键在顶（对齐 ONS 参考实现）
        }
        container.addView(column);
        container.setTag(tag);
        return container;
    }

    private static void setColumnVisible(View container, boolean visible) {
        if (!(container.getTag() instanceof ColumnTag)) return;
        ((ColumnTag) container.getTag()).column.setVisibility(
                visible ? View.VISIBLE : View.GONE);
    }

    private static TextView button(Activity activity, ButtonSpec spec) {
        TextView tv = new TextView(activity);
        tv.setText(spec.label);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(13);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        int side = dp(activity, BUTTON_DP);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(side, side);
        lp.topMargin = dp(activity, GAP_DP);
        tv.setLayoutParams(lp);
        tv.setPadding(dp(activity, 2), 0, dp(activity, 2), 0);
        styleButton(tv, spec.active != null && spec.active.getAsBoolean());
        if (spec.press != null || spec.release != null) {
            // 按住式：触摸按下/抬起（SKIP=按住 Ctrl；alpha 反馈对齐 ONS）
            tv.setOnTouchListener((v, e) -> {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    v.setAlpha(0.65f);
                    if (spec.press != null) {
                        try {
                            spec.press.run();
                        } catch (Throwable ignored) {
                        }
                    }
                    return true;
                }
                if (e.getActionMasked() == MotionEvent.ACTION_UP
                        || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    v.setAlpha(1f);
                    if (spec.release != null) {
                        try {
                            spec.release.run();
                        } catch (Throwable ignored) {
                        }
                    }
                    return true;
                }
                return false;
            });
        } else if (spec.action != null) {
            tv.setOnClickListener(v -> {
                try {
                    spec.action.run();
                } catch (Throwable ignored) {
                }
            });
        }
        return tv;
    }

    private static void styleButton(TextView tv, boolean active) {
        Activity activity = (Activity) tv.getContext();
        GradientDrawable bg = new GradientDrawable();
        // 圆角矩形 radius 10dp：与 OnsSideButtons 右缘/ONS 虚拟按键同形
        bg.setCornerRadius(dp(activity, 10));
        bg.setColor(active ? ACTIVE_BG : BG);
        bg.setStroke(dp(activity, active ? 2 : 1), active ? 0xE6FFFFFF : STROKE);
        tv.setBackground(bg);
    }

    /** 左缘边距 = EDGE_MARGIN_DP + 系统栏左 inset；attach 后校准（insets 未就绪兜底）。 */
    private static int edgeMargin(Activity activity) {
        try {
            android.view.WindowInsets ins = activity.getWindow().getDecorView().getRootWindowInsets();
            if (ins != null) {
                android.graphics.Insets sb = ins.getInsets(android.view.WindowInsets.Type.systemBars());
                return dp(activity, EDGE_MARGIN_DP) + sb.left;
            }
        } catch (Throwable ignored) {
        }
        return dp(activity, EDGE_MARGIN_DP);
    }

    private static void recalibrateInsets(Activity activity, View container) {
        container.post(() -> {
            ViewGroup.LayoutParams lp = container.getLayoutParams();
            if (lp instanceof FrameLayout.LayoutParams) {
                ((FrameLayout.LayoutParams) lp).leftMargin = edgeMargin(activity);
                container.setLayoutParams(lp);
            }
        });
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
