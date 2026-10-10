package com.core.ons;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.PixelCopy;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/**
 * 游戏画面放大镜：可拖动的悬浮放大窗，持续抓取游戏画面中镜头下方区域并按倍率放大显示，
 * 方便字号偏小的游戏阅读。
 *
 * - 抓取走 PixelCopy.request(游戏 Window)：只含游戏自身渲染（SurfaceView/GL/Native），
 *   不含本悬浮窗与按键组等覆盖层——镜头盖住小字区域即显示其放大版，无自反馈。
 * - 自主选取范围：整窗可拖动到任意位置；＋/－ 调整倍率（2x/3x/4x）；✕ 关闭。
 * - 连续抓帧约 10fps（PixelCopy 异步），连续失败 5 次自动关闭（窗口销毁等场景）。
 * - 入口：OnsSideButtons 新增「放大镜」键（各引擎通用的右缘按键组）。
 */
public final class OnsMagnifier {

    private static final String TAG = "OnsMagnifier";
    private static final long TICK_MS = 100;
    private static final float[] ZOOMS = {2f, 3f, 4f};

    private static WeakReference<OnsMagnifier> current;

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::capture;

    private WindowManager.LayoutParams lp;
    private LinearLayout frame;
    private ImageView lensView;
    private TextView zoomLabel;
    private Bitmap frameBmp;
    private float zoom = ZOOMS[1];
    private boolean shown;
    private int failures;

    /**
     * 用户**选中的待放大区域**（屏幕坐标，左闭右开）。
     *
     * 交互改造（用户要求）：不再是"放大镜跟着镜头跑、显示镜头下方那块"，
     * 而是**先由用户框选一块区域**，之后放大镜固定放大这一块（窗口可以随便挪，
     * 放大内容不变）。＋/－ 以选区中心为基准缩放选区（= 调整倍率）。
     */
    private final android.graphics.Rect roi = new android.graphics.Rect();
    private boolean hasRoi;
    private View selector;
    private WindowManager.LayoutParams selectorLp;

    private OnsMagnifier(Activity activity) {
        this.activity = activity;
    }

    /** 开关放大镜：已开则关闭；没开则进入「框选区域」步骤（选完才出现放大窗）。 */
    public static void toggle(Activity activity) {
        OnsMagnifier cur = current != null ? current.get() : null;
        if (cur != null) {
            boolean same = cur.activity == activity;
            cur.dismiss();
            if (same) return;
        }
        OnsMagnifier m = new OnsMagnifier(activity);
        current = new WeakReference<>(m);
        m.startSelect();
    }

    /** 关闭放大镜（宿主销毁时可调用；无实例则无操作）。 */
    public static void dismiss(Activity activity) {
        OnsMagnifier cur = current != null ? current.get() : null;
        if (cur != null && cur.activity == activity) cur.dismiss();
    }

    /**
     * 第一步：框选。全屏半透明选择层，手指拖出一个矩形，松手确认；
     * 取消则退出（不留放大窗）。选区太小的松手会被忽略，继续拖。
     */
    private void startSelect() {
        if (selector != null) return;
        final int sw = activity.getResources().getDisplayMetrics().widthPixels;
        final int sh = activity.getResources().getDisplayMetrics().heightPixels;
        final int minSide = dp(activity, 24);

        final android.graphics.Paint dim = new android.graphics.Paint();
        dim.setColor(0x99000000);
        final android.graphics.Paint clear = new android.graphics.Paint();
        clear.setXfermode(new android.graphics.PorterDuffXfermode(
                android.graphics.PorterDuff.Mode.CLEAR));
        final android.graphics.Paint edge = new android.graphics.Paint();
        edge.setColor(0xFF2DD4BF);
        edge.setStyle(android.graphics.Paint.Style.STROKE);
        edge.setStrokeWidth(dp(activity, 2));
        final android.graphics.RectF sel = new android.graphics.RectF();

        final android.widget.FrameLayout box = new android.widget.FrameLayout(activity);
        final View canvas = new View(activity) {
            float sx, sy;

            @Override
            protected void onDraw(android.graphics.Canvas c) {
                super.onDraw(c);
                // 半透明遮罩挖出选区（用户能看清框住了哪块字）
                c.drawRect(0, 0, getWidth(), getHeight(), dim);
                if (!sel.isEmpty()) {
                    int sc = c.saveLayer(0, 0, getWidth(), getHeight(), null);
                    c.drawRect(sel, clear);
                    c.restoreToCount(sc);
                    c.drawRect(sel, edge);
                }
            }

            @Override
            public boolean onTouchEvent(MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getX();
                        sy = e.getY();
                        sel.set(sx, sy, sx, sy);
                        invalidate();
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float x = Math.max(0, Math.min(e.getX(), getWidth()));
                        float y = Math.max(0, Math.min(e.getY(), getHeight()));
                        sel.set(Math.min(sx, x), Math.min(sy, y), Math.max(sx, x), Math.max(sy, y));
                        invalidate();
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        if (sel.width() >= minSide && sel.height() >= minSide) {
                            roi.set((int) sel.left, (int) sel.top, (int) sel.right, (int) sel.bottom);
                            hasRoi = true;
                            endSelect();
                            show();
                        } else {
                            sel.setEmpty();
                            invalidate();
                            android.widget.Toast.makeText(activity,
                                    com.core.engine.R.string.engine_ons_magnifier_select_hint,
                                    android.widget.Toast.LENGTH_SHORT).show();
                        }
                        return true;
                    }
                    default:
                        return false;
                }
            }
        };
        box.addView(canvas, new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 顶部提示 + 右上取消（不挡框选：提示条本身也参与触摸，取消键单独可点）
        TextView hint = new TextView(activity);
        hint.setText(com.core.engine.R.string.engine_ons_magnifier_select_hint);
        hint.setTextColor(0xFFFFFFFF);
        hint.setTextSize(14);
        hint.setPadding(dp(activity, 16), dp(activity, 10), dp(activity, 16), dp(activity, 10));
        GradientDrawable hb = new GradientDrawable();
        hb.setCornerRadius(dp(activity, 8));
        hb.setColor(0xCC000000);
        hint.setBackground(hb);
        android.widget.FrameLayout.LayoutParams hp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        hp.topMargin = dp(activity, 16);
        box.addView(hint, hp);

        TextView cancel = new TextView(activity);
        cancel.setText("✕");
        cancel.setTextColor(0xFFFFFFFF);
        cancel.setTextSize(18);
        cancel.setPadding(dp(activity, 14), dp(activity, 8), dp(activity, 14), dp(activity, 8));
        GradientDrawable cb = new GradientDrawable();
        cb.setCornerRadius(dp(activity, 8));
        cb.setColor(0xCC000000);
        cancel.setBackground(cb);
        cancel.setClickable(true);
        cancel.setOnClickListener(v -> {
            endSelect();
            dismiss();
        });
        android.widget.FrameLayout.LayoutParams cp = new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.gravity = Gravity.TOP | Gravity.END;
        cp.topMargin = dp(activity, 16);
        cp.rightMargin = dp(activity, 16);
        box.addView(cancel, cp);

        selector = box;
        selectorLp = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                android.graphics.PixelFormat.TRANSLUCENT);
        // TYPE_APPLICATION 必须显式 gravity（缺省 x/y 不按左上锚定，见 MEMORY）
        selectorLp.gravity = Gravity.TOP | Gravity.START;
        try {
            activity.getWindowManager().addView(selector, selectorLp);
        } catch (Throwable t) {
            android.util.Log.w(TAG, "magnifier selector add failed", t);
            selector = null;
        }
    }

    private void endSelect() {
        if (selector == null) return;
        try {
            activity.getWindowManager().removeView(selector);
        } catch (Throwable ignored) {
        }
        selector = null;
    }

    private void show() {
        int sw = activity.getResources().getDisplayMetrics().widthPixels;
        int sh = activity.getResources().getDisplayMetrics().heightPixels;
        int lensW = Math.round(sw * 0.55f);
        int lensH = Math.round(sh * 0.30f);
        // 按**选中区域**的宽高比定镜头尺寸：内容不被拉伸，放大倍率 = 镜头宽 / 选区宽
        if (hasRoi && roi.width() > 0 && roi.height() > 0) {
            lensH = Math.round(lensW * (float) roi.height() / roi.width());
            int minH = Math.round(sh * 0.12f), maxH = Math.round(sh * 0.60f);
            lensH = Math.max(minH, Math.min(lensH, maxH));
        }
        int ctrlH = Math.round(sh * 0.05f);

        frame = new LinearLayout(activity);
        frame.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(activity, 10));
        bg.setColor(0x66000000);
        bg.setStroke(dp(activity, 2), 0xFF2DD4BF);
        frame.setBackground(bg);
        frame.setClickable(true);
        frame.setFocusable(true);

        lensView = new ImageView(activity);
        // FIT_CENTER：选区宽高比已被镜头尺寸吸收，这里兜底不拉伸（自由框选的选区形状任意）
        lensView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        lensView.setBackgroundColor(Color.BLACK);
        // 固定像素尺寸：WRAP_CONTENT 窗口 + MATCH_PARENT 子视图 + FIT_XY 位图
        // 三者组合存在测量歧义（实测呈 1:1 显示），全部改显式 px
        frame.addView(lensView, new LinearLayout.LayoutParams(lensW, lensH));

        LinearLayout controls = new LinearLayout(activity);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        TextView minus = controlButton(activity, "－", () -> changeZoom(-1));
        TextView label = zoomLabel = controlButton(activity, zoomLabel(), null);
        label.setMinWidth(dp(activity, 44));
        TextView plus = controlButton(activity, "＋", () -> changeZoom(1));
        TextView close = controlButton(activity, "✕", this::dismiss);
        // 「选」= 重新框选要放大的区域（用户要求：先选区域、再固定放大该区域）
        TextView reselect = controlButton(activity, "选", () -> {
            hideFrame();
            startSelect();
        });
        controls.addView(minus);
        controls.addView(label);
        controls.addView(plus);
        controls.addView(reselect);
        // 弹性占位：把 ✕ 顶到右侧
        View spacer = new View(activity);
        controls.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));
        controls.addView(close);
        frame.addView(controls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 整窗拖动
        frame.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY, origX, origY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        origX = lp.x;
                        origY = lp.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = (int) (origX + event.getRawX() - downX);
                        lp.y = (int) (origY + event.getRawY() - downY);
                        try {
                            activity.getWindowManager().updateViewLayout(frame, lp);
                        } catch (Throwable ignored) {
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });

        lp = new WindowManager.LayoutParams(
                lensW,
                lensH + ctrlH,
                WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                android.graphics.PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        // 初始位置：右上区域（不压左缘按键列）
        lp.x = Math.round(sw * 0.42f);
        lp.y = Math.round(sh * 0.12f);
        activity.getWindowManager().addView(frame, lp);
        shown = true;
        failures = 0;
        main.post(tick);
    }

    private void dismiss() {
        shown = false;
        endSelect();
        main.removeCallbacks(tick);
        hideFrame();
        if (frameBmp != null) {
            frameBmp.recycle();
            frameBmp = null;
        }
        if (current != null && current.get() == this) current = null;
    }

    /** 只移除放大窗（保留实例与已选区域）：重新框选时用，选完再 show()。 */
    private void hideFrame() {
        shown = false;
        main.removeCallbacks(tick);
        if (frame != null) {
            try {
                activity.getWindowManager().removeView(frame);
            } catch (Throwable ignored) {
            }
            frame = null;
        }
    }

    /**
     * 调整倍率：**以选区中心为基准缩放选区**（选区变小 = 放大更多）。
     * 用户要求"先选区域、再放大该区域"，所以倍率不再由镜头与内容的相对位置决定。
     */
    private void changeZoom(int dir) {
        int idx = 0;
        for (int i = 0; i < ZOOMS.length; i++) {
            if (ZOOMS[i] == zoom) idx = i;
        }
        idx = Math.max(0, Math.min(ZOOMS.length - 1, idx + dir));
        float next = ZOOMS[idx];
        if (next == zoom) return;
        if (hasRoi && roi.width() > 0 && roi.height() > 0) {
            float k = zoom / next;   // 放大更多（next 更大）→ 选区更小
            int cx = roi.centerX(), cy = roi.centerY();
            int nw = Math.max(dp(activity, 24), Math.round(roi.width() * k));
            int nh = Math.max(dp(activity, 24), Math.round(roi.height() * k));
            int maxW = activity.getResources().getDisplayMetrics().widthPixels;
            int maxH = activity.getResources().getDisplayMetrics().heightPixels;
            nw = Math.min(nw, maxW);
            nh = Math.min(nh, maxH);
            int left = Math.max(0, Math.min(cx - nw / 2, maxW - nw));
            int top = Math.max(0, Math.min(cy - nh / 2, maxH - nh));
            roi.set(left, top, left + nw, top + nh);
        }
        zoom = next;
        if (zoomLabel != null) zoomLabel.setText(zoomLabel());
    }

    private String zoomLabel() {
        return Math.round(zoom) + "x";
    }

    /** 游戏渲染源：优先 SurfaceView（SDL/GL 内容量级、窗口抓取拿不到其内容），无则窗口。 */
    private android.view.SurfaceView gameSurface() {
        try {
            java.util.List<android.view.SurfaceView> found = new java.util.ArrayList<>();
            collectSurfaceViews(activity.getWindow().getDecorView(), found);
            for (android.view.SurfaceView sv : found) {
                if (sv.isShown() && sv.getWidth() > 0 && sv.getHeight() > 0) return sv;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void collectSurfaceViews(View view, java.util.List<android.view.SurfaceView> out) {
        if (view instanceof android.view.SurfaceView) {
            out.add((android.view.SurfaceView) view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectSurfaceViews(group.getChildAt(i), out);
            }
        }
    }

    /**
     * 抓一帧游戏渲染 → 取镜头下方 1/zoom 区域 → 放大显示；随后调度下一帧。
     * 渲染源优先游戏的 SurfaceView（SDL/GL surface 由 SurfaceFlinger 单独合成，
     * 窗口 PixelCopy 拿不到其内容——实测黑屏）；无 SurfaceView 的宿主
     * （Artemis/NativeActivity）退回窗口抓取（可能黑屏，连续失败自动关闭）。
     * 镜头下方区域 = 镜头屏幕矩形与渲染源屏幕矩形的交集（源坐标 1:1）。
     */
    private void capture() {
        if (!shown) return;
        if (!hasRoi || roi.width() < 8 || roi.height() < 8) {
            schedule();
            return;
        }
        try {
            // 用户**框选的区域**（屏幕坐标）—— 与放大窗位置无关：窗口可以随便挪，
            // 放大内容始终是那一块（用户要求：先选区域、再固定放大该区域）。
            final int rw = roi.width();
            final int rh = roi.height();

            android.view.SurfaceView sv = gameSurface();
            if (sv != null) {
                // PixelCopy 会把整个 surface 缩放填进目标位图，故必须抓全幅再手动裁剪
                int[] sxy = new int[2];
                sv.getLocationOnScreen(sxy);
                final int sw = sv.getWidth(), sh2 = sv.getHeight();
                if (frameBmp == null || frameBmp.getWidth() != sw || frameBmp.getHeight() != sh2) {
                    if (frameBmp != null) frameBmp.recycle();
                    frameBmp = Bitmap.createBitmap(sw, sh2, Bitmap.Config.ARGB_8888);
                }
                // 选区（屏幕）→ surface 本地坐标；越界就钳到 surface 内
                final int cw = Math.max(1, Math.min(rw, sw));
                final int ch = Math.max(1, Math.min(rh, sh2));
                final int rx = Math.max(0, Math.min(roi.left - sxy[0], sw - cw));
                final int ry = Math.max(0, Math.min(roi.top - sxy[1], sh2 - ch));
                final Bitmap bmp = frameBmp;
                PixelCopy.request(sv, bmp, result -> {
                    if (!shown) return;
                    if (result == PixelCopy.SUCCESS) {
                        failures = 0;
                        Bitmap crop = Bitmap.createBitmap(bmp, rx, ry, cw, ch);
                        lensView.setImageBitmap(crop);
                    } else if (++failures > 5) {
                        dismiss();
                        return;
                    }
                    schedule();
                }, main);
                return;
            }

            // 无 SurfaceView（Artemis/NativeActivity）：窗口抓取兜底
            View decor = activity.getWindow().getDecorView();
            int w = decor.getWidth();
            int h = decor.getHeight();
            if (w <= 4 || h <= 4) {
                schedule();
                return;
            }
            if (frameBmp == null || frameBmp.getWidth() != w || frameBmp.getHeight() != h) {
                if (frameBmp != null) frameBmp.recycle();
                frameBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            }
            int[] dxy = new int[2];
            decor.getLocationOnScreen(dxy);
            final Bitmap bmp = frameBmp;
            final int cw2 = Math.max(1, Math.min(rw, w));
            final int ch2 = Math.max(1, Math.min(rh, h));
            final int frx = Math.max(0, Math.min(roi.left - dxy[0], w - cw2));
            final int fry = Math.max(0, Math.min(roi.top - dxy[1], h - ch2));
            final int frw = cw2;
            final int frh = ch2;
            PixelCopy.request(activity.getWindow(), bmp, result -> {
                if (!shown) return;
                if (result == PixelCopy.SUCCESS) {
                    failures = 0;
                    if (frw > 0 && frh > 0) {
                        Bitmap crop = Bitmap.createBitmap(bmp, frx, fry, frw, frh);
                        lensView.setImageBitmap(crop);
                    }
                } else if (++failures > 5) {
                    dismiss();
                    return;
                }
                schedule();
            }, main);
        } catch (Throwable t) {
            if (++failures > 5) {
                dismiss();
                return;
            }
            schedule();
        }
    }

    private void schedule() {
        main.postDelayed(tick, TICK_MS);
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private TextView controlButton(Activity activity, String label, Runnable action) {
        TextView tv = new TextView(activity);
        tv.setText(label);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(15);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(activity, 10), dp(activity, 4), dp(activity, 10), dp(activity, 4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(activity, 6), 0, dp(activity, 6), 0);
        tv.setLayoutParams(lp);
        if (action != null) {
            tv.setOnClickListener(v -> {
                try {
                    action.run();
                } catch (Throwable ignored) {
                }
            });
        }
        return tv;
    }
}
