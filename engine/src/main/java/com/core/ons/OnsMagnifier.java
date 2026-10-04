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

    private OnsMagnifier(Activity activity) {
        this.activity = activity;
    }

    /** 开关放大镜：已开则关闭；不同 Activity 的旧实例先关再开新。 */
    public static void toggle(Activity activity) {
        OnsMagnifier cur = current != null ? current.get() : null;
        if (cur != null) {
            boolean same = cur.activity == activity;
            cur.dismiss();
            if (same) return;
        }
        OnsMagnifier m = new OnsMagnifier(activity);
        current = new WeakReference<>(m);
        m.show();
    }

    /** 关闭放大镜（宿主销毁时可调用；无实例则无操作）。 */
    public static void dismiss(Activity activity) {
        OnsMagnifier cur = current != null ? current.get() : null;
        if (cur != null && cur.activity == activity) cur.dismiss();
    }

    private void show() {
        int sw = activity.getResources().getDisplayMetrics().widthPixels;
        int sh = activity.getResources().getDisplayMetrics().heightPixels;
        int lensW = Math.round(sw * 0.55f);
        int lensH = Math.round(sh * 0.30f);
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
        lensView.setScaleType(ImageView.ScaleType.FIT_XY);
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
        controls.addView(minus);
        controls.addView(label);
        controls.addView(plus);
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
        main.removeCallbacks(tick);
        if (frame != null) {
            try {
                activity.getWindowManager().removeView(frame);
            } catch (Throwable ignored) {
            }
            frame = null;
        }
        if (frameBmp != null) {
            frameBmp.recycle();
            frameBmp = null;
        }
        if (current != null && current.get() == this) current = null;
    }

    private void changeZoom(int dir) {
        int idx = 0;
        for (int i = 0; i < ZOOMS.length; i++) {
            if (ZOOMS[i] == zoom) idx = i;
        }
        idx = Math.max(0, Math.min(ZOOMS.length - 1, idx + dir));
        zoom = ZOOMS[idx];
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
        try {
            int[] lensXy = new int[2];
            frame.getLocationOnScreen(lensXy);
            int lw = Math.max(1, frame.getWidth());
            int lh = Math.max(1, lensView.getHeight());
            int lensCx = lensXy[0] + lw / 2;
            int lensCy = lensXy[1] + lh / 2;
            int rw = Math.max(8, Math.round(lw / zoom));
            int rh = Math.max(8, Math.round(lh / zoom));

            android.view.SurfaceView sv = gameSurface();
            if (sv != null) {
                // PixelCopy 会把整个 surface 缩放填进目标位图，故必须抓全幅再手动裁剪
                int[] sxy = new int[2];
                sv.getLocationOnScreen(sxy);
                int sw = sv.getWidth(), sh2 = sv.getHeight();
                if (frameBmp == null || frameBmp.getWidth() != sw || frameBmp.getHeight() != sh2) {
                    if (frameBmp != null) frameBmp.recycle();
                    frameBmp = Bitmap.createBitmap(sw, sh2, Bitmap.Config.ARGB_8888);
                }
                // 裁剪区（surface 本地坐标）：镜头中心 ± 1/(2zoom)，钳到 surface 内
                final int rx = Math.max(0, Math.min(lensCx - sxy[0] - rw / 2, sw - rw));
                final int ry = Math.max(0, Math.min(lensCy - sxy[1] - rh / 2, sh2 - rh));
                final Bitmap bmp = frameBmp;
                PixelCopy.request(sv, bmp, result -> {
                    if (!shown) return;
                    if (result == PixelCopy.SUCCESS) {
                        failures = 0;
                        Bitmap crop = Bitmap.createBitmap(bmp, rx, ry, rw, rh);
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
            final Bitmap bmp = frameBmp;
            final int frx = Math.max(0, Math.min(lensCx - rw / 2, w - rw));
            final int fry = Math.max(0, Math.min(lensCy - rh / 2, h - rh));
            final int frw = Math.min(rw, w);
            final int frh = Math.min(rh, h);
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
