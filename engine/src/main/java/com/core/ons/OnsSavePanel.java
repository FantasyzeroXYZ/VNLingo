package com.core.ons;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.core.engine.R;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ONS 存档面板：独立悬浮球入口（右侧「存」球），展开当前游戏存档列表
 * （文件名/大小/修改时间，按时间倒序），支持整目录导出/导入 zip（SAF）、
 * WebDAV/GitHub 云同步与逐条删除。
 *
 * 与 OnsExtractPanel 同一配色族与底部圆角面板形态；悬浮球复用 OnsFloatingChip。
 */
@SuppressLint("AppCompatCustomView")
public class OnsSavePanel {

    private static final String TAG = "OnsSave";
    private static final String PREFS = "ons_extract_tts";
    private static final String KEY_CHIP_RIGHT = "chip_save_right";
    private static final String KEY_CHIP_TOP = "chip_save_top";
    /** 横屏侧边栏宽度（dp）。 */
    private static final int SIDEBAR_WIDTH_DP = 320;
    /** 与 OnsExtractPanel 同一配色族。 */
    private static final int BG_PANEL = 0xFFFFFFFF;
    private static final int BG_BUTTON = 0xFFF1F5F9;
    private static final int STROKE = 0xFFE2E8F0;
    private static final int ACCENT = 0xFF007AFF;
    private static final int TEXT_TITLE = 0xFF0F172A;
    private static final int TEXT_BODY = 0xFF1E293B;
    private static final int TEXT_BUTTON = 0xFF334155;
    private static final int TEXT_DIM = 0xFF64748B;
    private static final int TEXT_PLACEHOLDER = 0xFF94A3B8;
    private static final int DELETE = 0xFFDC2626;

    private final ExtractFacade facade;
    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());

    private OnsFloatingChip chip;
    /** 窗口承载模式（NativeActivity 宿主）：「存」球以独立悬浮窗安装。 */
    private boolean chipWindowMode;
    /** false = 不装「存」悬浮球（宿主经右缘存档键开合，见 OnsSideButtons）。 */
    private boolean entryChipVisible = true;
    private LinearLayout panel;
    private TextView titleView;
    private LinearLayout listContainer;
    private boolean expanded;
    /** 多选集合（存档绝对路径）；refresh 后剔除已不存在的项。 */
    private final java.util.Set<String> selectedPaths = new java.util.HashSet<>();
    private android.widget.CheckBox selectAllBox;
    private TextView deleteSelectedBtn;

    public OnsSavePanel(ExtractFacade facade) {
        this.facade = facade;
        this.activity = facade.getActivity();
    }

    /** 窗口承载模式（NativeActivity 宿主）：「存」球以独立悬浮窗安装。 */
    public void setChipWindowMode(boolean window) {
        this.chipWindowMode = window;
    }

    /** false = 不装「存」悬浮球（入口改由右缘固定存档键承担）。 */
    public void setEntryChipVisible(boolean visible) {
        this.entryChipVisible = visible;
    }

    /** 重挂「存」球悬浮窗（覆盖层窗口后加入会盖住球，重加提升 z 序）。 */
    public void raiseChipWindow() {
        if (chip != null && chipWindowMode) {
            chip.readdWindow();
        }
    }

    /** 在宿主覆盖层内安装存档入口（悬浮球可选）与面板。 */
    public void install(FrameLayout overlay) {
        if (entryChipVisible) {
            chip = new OnsFloatingChip(activity, prefs(), KEY_CHIP_RIGHT, KEY_CHIP_TOP, 12, 52,
                    activity.getString(R.string.engine_ons_save_chip));
            chip.getView().setContentDescription(
                    activity.getString(R.string.engine_ons_save_panel_desc));
            chip.setClickListener(this::toggle);
            if (chipWindowMode) {
                chip.installAsWindow();
            } else {
                chip.install(overlay);
            }
        }

        panel = buildPanel();
        panel.setVisibility(View.GONE);
        overlay.addView(panel, panelParams());
        // 横屏侧边栏铺满右缘会盖住悬浮球：把球提到面板之上，保证随时可再点收起
        if (chip != null && !chipWindowMode) {
            chip.getView().bringToFront();
        }
    }

    /** 面板根视图（WindowOverlayHost 可见性联动用）。 */
    public android.view.View panelView() {
        return panel;
    }

    public void release() {
        main.removeCallbacksAndMessages(null);
    }

    /**
     * 面板布局随屏幕方向：横屏停靠右缘为定宽侧边栏（全高，左侧圆角），
     * 竖屏维持底部弹出面板（全宽，顶部圆角，高度上限 50%）。
     */
    private FrameLayout.LayoutParams panelParams() {
        if (isLandscape()) {
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    dp(SIDEBAR_WIDTH_DP), FrameLayout.LayoutParams.MATCH_PARENT, Gravity.END);
            // 三键导航条叠在右缘：面板右缘避开，保证 ✕/行尾按钮可点
            lp.rightMargin = systemBarInset(true);
            return lp;
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        lp.bottomMargin = systemBarInset(false);
        return lp;
    }

    /** 右/下系统栏 inset（导航条宽度），拿不到时 0。 */
    private int systemBarInset(boolean right) {
        try {
            android.view.WindowInsets ins = activity.getWindow().getDecorView().getRootWindowInsets();
            if (ins != null) {
                android.graphics.Insets sb = ins.getInsets(android.view.WindowInsets.Type.systemBars());
                return right ? sb.right : sb.bottom;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private boolean isLandscape() {
        return activity.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
    }

    private android.content.SharedPreferences prefs() {
        return activity.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private LinearLayout buildPanel() {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(10), dp(14), dp(12));
        panel.setClickable(true);

        // 标题排：标题 + 关闭键（✕），侧边栏形态下也始终有面板内关闭入口
        LinearLayout titleRow = new LinearLayout(activity);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleView = new TextView(activity);
        titleView.setTextColor(TEXT_TITLE);
        titleView.setTextSize(13);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleRow.addView(titleView, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        titleRow.addView(makeAction(R.drawable.ic_close,
                android.R.string.cancel, () -> {
                    expanded = false;
                    panel.setVisibility(View.GONE);
                }));

        // 图标按钮排：导出 / 导入 / 云同步 / 刷新（重新扫描当前存档目录）
        LinearLayout actions = new LinearLayout(activity);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.addView(makeAction(R.drawable.ic_upload,
                R.string.engine_ons_extract_save_export, facade::exportSaveArchive));
        actions.addView(makeAction(R.drawable.ic_download,
                R.string.engine_ons_extract_save_import, facade::importSaveArchive));
        actions.addView(makeAction(R.drawable.ic_cloud,
                R.string.engine_ons_extract_cloud, this::showCloudDialog));
        actions.addView(makeAction(R.drawable.ic_refresh,
                R.string.engine_ons_save_refresh, this::refresh));

        // 多选排：全选 + 删除选中（删除随选中数启用）
        LinearLayout selection = new LinearLayout(activity);
        selection.setOrientation(LinearLayout.HORIZONTAL);
        selection.setGravity(Gravity.CENTER_VERTICAL);
        selectAllBox = new android.widget.CheckBox(activity);
        selectAllBox.setText(R.string.engine_ons_save_select_all);
        selectAllBox.setTextColor(TEXT_BODY);
        selectAllBox.setTextSize(12);
        selectAllBox.setOnCheckedChangeListener((b, checked) -> syncSelectAll(checked));
        selection.addView(selectAllBox, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        deleteSelectedBtn = new TextView(activity);
        deleteSelectedBtn.setText(R.string.engine_ons_save_delete_selected);
        deleteSelectedBtn.setTextColor(DELETE);
        deleteSelectedBtn.setTextSize(12);
        deleteSelectedBtn.setTypeface(Typeface.DEFAULT_BOLD);
        deleteSelectedBtn.setPadding(dp(10), dp(6), dp(10), dp(6));
        deleteSelectedBtn.setOnClickListener(v -> confirmDeleteSelected());
        selection.addView(deleteSelectedBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        listContainer = new LinearLayout(activity);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroller = new ScrollView(activity);
        scroller.addView(listContainer);
        // weight 1：横屏侧边栏定高时列表占满余下空间；竖屏 WRAP_CONTENT 面板下无副作用
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        listLp.topMargin = dp(6);

        panel.addView(titleRow, matchWrap());
        panel.addView(actions);
        panel.addView(selection, matchWrap());
        panel.addView(scroller, listLp);
        applyPanelBackground(panel);
        return panel;
    }

    /** 开合面板（悬浮球与右缘存档键共用入口）。 */
    public void toggle() {
        expanded = !expanded;
        panel.setVisibility(expanded ? View.VISIBLE : View.GONE);
        if (expanded) {
            // 按当前方向重算布局（横屏侧边栏 / 竖屏底部面板），旋转后展开也正确
            panel.setLayoutParams(panelParams());
            panel.requestLayout();
            if (!isLandscape()) {
                panel.post(() -> {
                    int max = Math.round(
                            activity.getResources().getDisplayMetrics().heightPixels * 0.5f);
                    if (panel.getHeight() > max) {
                        panel.getLayoutParams().height = max;
                        panel.requestLayout();
                    }
                });
            }
            applyPanelBackground(panel);
            refresh();
        }
    }

    /** 面板圆角随方向：横屏侧边栏圆左侧（贴右缘），竖屏圆顶部（贴底部）。 */
    private void applyPanelBackground(LinearLayout target) {
        target.setBackground(isLandscape() ? roundedLeft() : roundedTop());
    }

    /** 最近一次 refresh 扫到的存档（全选/删除选中状态计算用）。 */
    private List<File> currentSaves = new java.util.ArrayList<>();
    /** 程序化同步全选框状态时忽略自身监听，防递归。 */
    private boolean syncingSelectionUi;

    private void refresh() {
        titleView.setText(activity.getString(
                R.string.engine_ons_save_title, facade.gameDisplayName()));
        listContainer.removeAllViews();
        File[] saves = facade.listSaves();
        // 剔除已不存在的选中项，保持勾选状态跨刷新稳定
        selectedPaths.retainAll(filePaths(saves));
        currentSaves = new java.util.ArrayList<>(java.util.Arrays.asList(saves));
        if (saves.length == 0) {
            TextView empty = new TextView(activity);
            empty.setText(R.string.engine_ons_save_empty);
            empty.setTextColor(TEXT_PLACEHOLDER);
            empty.setTextSize(12);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(16), 0, dp(16));
            listContainer.addView(empty, matchWrap());
            syncSelectionUi();
            return;
        }
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
        for (File file : saves) {
            listContainer.addView(saveRow(file, format), matchWrap());
            View divider = new View(activity);
            divider.setBackgroundColor(STROKE);
            listContainer.addView(divider, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Math.max(1, dp(1))));
        }
        syncSelectionUi();
    }

    /** 同步全选框与「删除选中」按钮（数量/可用态）。 */
    private void syncSelectionUi() {
        syncingSelectionUi = true;
        boolean all = !currentSaves.isEmpty() && selectedPaths.containsAll(filePaths(currentSaves.toArray(new File[0])));
        selectAllBox.setChecked(all);
        syncingSelectionUi = false;
        int n = selectedPaths.size();
        deleteSelectedBtn.setText(activity.getString(R.string.engine_ons_save_delete_selected_fmt, n));
        deleteSelectedBtn.setEnabled(n > 0);
        deleteSelectedBtn.setAlpha(n > 0 ? 1f : 0.45f);
    }

    /** 全选/全不选当前列表。 */
    private void syncSelectAll(boolean checked) {
        if (syncingSelectionUi) return;
        if (checked) {
            for (File f : currentSaves) selectedPaths.add(f.getAbsolutePath());
        } else {
            selectedPaths.clear();
        }
        syncSelectionUi();
    }

    private static List<String> filePaths(File[] files) {
        List<String> out = new java.util.ArrayList<>(files.length);
        for (File f : files) out.add(f.getAbsolutePath());
        return out;
    }

    private LinearLayout saveRow(File file, SimpleDateFormat format) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        android.widget.CheckBox check = new android.widget.CheckBox(activity);
        check.setChecked(selectedPaths.contains(file.getAbsolutePath()));
        check.setOnCheckedChangeListener((b, checked) -> {
            if (checked) selectedPaths.add(file.getAbsolutePath());
            else selectedPaths.remove(file.getAbsolutePath());
            syncSelectionUi();
        });
        row.addView(check, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout textCol = new LinearLayout(activity);
        textCol.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(activity);
        name.setText(file.getName());
        name.setTextColor(TEXT_BODY);
        name.setTextSize(13);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        TextView meta = new TextView(activity);
        meta.setText(format.format(new Date(file.lastModified()))
                + " · " + humanSize(file.length()));
        meta.setTextColor(TEXT_DIM);
        meta.setTextSize(11);
        textCol.addView(name, matchWrap());
        textCol.addView(meta, matchWrap());
        row.addView(textCol, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // 行尾单条操作：下载（到系统 Download）/ 删除
        row.addView(makeAction(R.drawable.ic_download,
                R.string.engine_ons_save_export_one, () -> downloadSave(file)));
        row.addView(makeAction(R.drawable.ic_delete,
                R.string.engine_ons_save_delete, () -> confirmDeleteOne(file)));
        return row;
    }

    /** 单条下载：拷贝到系统 Download 目录（MediaStore，免权限），后台线程 + toast。 */
    private void downloadSave(File file) {
        new Thread(() -> {
            boolean ok = false;
            try {
                android.content.ContentResolver cr = activity.getContentResolver();
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, file.getName());
                v.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
                android.net.Uri uri = cr.insert(
                        android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) throw new IllegalStateException("insert null");
                try (java.io.OutputStream out = cr.openOutputStream(uri);
                     java.io.InputStream in = new java.io.FileInputStream(file)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                }
                ok = true;
            } catch (Throwable t) {
                Log.w(TAG, "save download failed", t);
            }
            final boolean done = ok;
            main.post(() -> toast(activity.getString(done
                    ? R.string.engine_ons_save_downloaded
                    : R.string.engine_ons_save_download_failed, file.getName())));
        }, "ons-save-download").start();
    }

    /** 单条删除（确认弹窗；同步清理多选集合）。 */
    private void confirmDeleteOne(File file) {
        new android.app.AlertDialog.Builder(activity)
                .setTitle(file.getName())
                .setMessage(R.string.engine_ons_save_delete_confirm)
                .setPositiveButton(R.string.engine_ons_save_delete, (d, w) -> {
                    boolean ok = file.delete();
                    toast(ok ? R.string.engine_ons_save_deleted
                            : R.string.engine_ons_extract_action_failed);
                    if (ok) {
                        selectedPaths.remove(file.getAbsolutePath());
                        refresh();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 批量删除选中存档（确认弹窗；存档可由游戏内自行增删，整批删无副作用）。 */
    private void confirmDeleteSelected() {
        if (selectedPaths.isEmpty()) return;
        int count = selectedPaths.size();
        new android.app.AlertDialog.Builder(activity)
                .setTitle(R.string.engine_ons_save_delete_selected)
                .setMessage(activity.getString(R.string.engine_ons_save_delete_selected_confirm, count))
                .setPositiveButton(R.string.engine_ons_save_delete, (d, w) -> {
                    int ok = 0;
                    for (String path : selectedPaths) {
                        if (new File(path).delete()) ok++;
                    }
                    selectedPaths.clear();
                    toast(activity.getString(ok == count
                            ? R.string.engine_ons_save_deleted_multi
                            : R.string.engine_ons_save_deleted_partial, ok, count));
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------
    // 云同步（配置弹窗下沉 OnsSaveCloud 共用；此处只保留传输动作）
    // ------------------------------------------------------------------

    /** ☁ 弹窗：模式切换 + 连接配置 + 上传/下载（上传走当前游戏存档 zip）。 */
    private void showCloudDialog() {
        OnsSaveCloud.showConfigDialog(activity, this::cloudUpload, this::cloudDownload, null);
    }

    /** 上传：zip 当前存档目录 → 云端（后台线程 + toast）。 */
    private void cloudUpload() {
        toast(R.string.engine_ons_extract_dict_importing);
        new Thread(() -> {
            File zip = facade.zipSavesToCache();
            if (zip == null) {
                main.post(() -> toast(activity.getString(
                        R.string.engine_ons_extract_cloud_failed, "no saves")));
                return;
            }
            OnsSaveCloud.upload(activity, facade.gameDisplayName(), zip, (ok, message) ->
                    main.post(() -> toast(activity.getString(ok
                            ? R.string.engine_ons_extract_cloud_uploaded
                            : R.string.engine_ons_extract_cloud_failed, message))));
        }, "ons-cloud-upload").start();
    }

    /** 下载：云端 zip → 覆盖导入当前存档目录（后台线程 + toast）。 */
    private void cloudDownload() {
        toast(R.string.engine_ons_extract_dict_importing);
        new Thread(() -> {
            File zip = new File(activity.getCacheDir(), "ons_cloud_saves_dl.zip");
            OnsSaveCloud.download(activity, facade.gameDisplayName(), zip, (ok, message) -> {
                if (!ok) {
                    main.post(() -> toast(activity.getString(isNotFound(message)
                            ? R.string.engine_ons_extract_cloud_not_found
                            : R.string.engine_ons_extract_cloud_failed, message)));
                    return;
                }
                int count = facade.importSavesFromZip(zip);
                main.post(() -> {
                    if (count >= 0) {
                        toast(activity.getString(
                                R.string.engine_ons_extract_cloud_downloaded, count));
                        main.post(this::refresh);
                    } else {
                        toast(activity.getString(
                                R.string.engine_ons_extract_cloud_failed, "import"));
                    }
                });
            });
        }, "ons-cloud-download").start();
    }

    private static boolean isNotFound(String message) {
        return message != null && message.contains("404");
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 图标按钮（标准矢量图标，文本仅作无障碍描述；深底白图标对齐左右缘按键组）。 */
    private android.widget.ImageView makeAction(int iconRes, int descRes, Runnable action) {
        android.widget.ImageView iv = new android.widget.ImageView(activity);
        iv.setImageResource(iconRes);
        iv.setContentDescription(activity.getString(descRes));
        iv.setColorFilter(0xFFFFFFFF);
        int side = dp(34);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(side, side);
        lp.rightMargin = dp(8);
        lp.leftMargin = dp(2);
        int pad = dp(6);
        iv.setPadding(pad, pad, pad, pad);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        iv.setBackground(rounded(0xA6101010, dp(10), 0x87FFFFFF, dp(1)));
        iv.setOnClickListener(v -> {
            try {
                action.run();
            } catch (Throwable t) {
                Log.w(TAG, "save action failed", t);
                toast(R.string.engine_ons_extract_action_failed);
            }
        });
        iv.setLayoutParams(lp);
        return iv;
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024f);
        return String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f);
    }

    private void toast(int res) {
        Toast.makeText(activity, res, Toast.LENGTH_SHORT).show();
    }

    private void toast(String text) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }

    private GradientDrawable rounded(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    /** 按键同款：底色 + 圆角 + 描边。 */
    private GradientDrawable rounded(int color, float radius, int strokeColor, float strokeWidth) {
        GradientDrawable d = rounded(color, radius);
        d.setStroke(Math.max(1, Math.round(strokeWidth)), strokeColor);
        return d;
    }

    private GradientDrawable roundedTop() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(BG_PANEL);
        float r = dp(16);
        d.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        d.setStroke(dp(1), STROKE);
        return d;
    }

    /** 横屏侧边栏背景：圆左侧两角（贴屏幕右缘）。 */
    private GradientDrawable roundedLeft() {
        GradientDrawable d = new GradientDrawable();
        d.setColor(BG_PANEL);
        float r = dp(16);
        d.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
        d.setStroke(dp(1), STROKE);
        return d;
    }
}
