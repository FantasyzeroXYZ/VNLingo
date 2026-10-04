package com.yuri.onscripter;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.view.ViewGroup;

import org.libsdl.app.SDLActivity;

import com.core.engine.DoubleBackExit;
import com.core.engine.LaunchContract;
import com.core.engine.R;
import com.core.ons.OnsExtractBridge;
import com.core.ons.OnsExtractPanel;
import com.core.ons.OnsLibLoader;
import com.core.ons.OnsSavePanel;
import com.core.ons.OnsSettings;
import com.core.ons.OnsVideoOverlay;
import com.core.ons.OnsVirtualMouse;

import java.io.File;
import java.io.FileNotFoundException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

public class ONScripter extends SDLActivity {
    private static final String TAG = "YukiONS";
    public static final String YURI_VERSION = "Yuri_0.7.7";
    private static final String PREF_OVERLAY = "ons_overlay";
    private static final String KEY_OVERLAY_VISIBLE = "visible";
    /** 点击模式：true=虚拟鼠标（D-pad/摇杆移光标 + A 点击），false=触摸直传。 */
    private static final String KEY_MOUSE_MODE = "virtual_mouse_mode";
    private static final int CONTROL_BUTTON_SIZE_DP = 40;
    private static final int CONTROL_BUTTON_GAP_DP = 5;
    private static final int TOGGLE_BUTTON_SIZE_DP = 32;

    private ArrayList<String> onsArgs;
    private boolean ignoreCutout = true;
    private String gameRoot;
    private FrameLayout onsOverlay;
    private LinearLayout leftControls;
    private LinearLayout rightControls;
    private ImageButton toggleButton;
    private final ArrayList<TextView> autoButtons = new ArrayList<>();
    private boolean controlsVisible = true;
    private boolean autoMode = false;
    /** 视频覆盖播放控制器：在本 Activity 窗口内覆盖播放，不启动独立 Activity。 */
    private OnsVideoOverlay videoOverlay;
    /** [ONS-BRIDGE] 对话/语音提取面板。 */
    private OnsExtractPanel extractPanel;
    private OnsSavePanel savePanel;
    /** 手柄方向键虚拟鼠标。 */
    private OnsVirtualMouse virtualMouse;
    private native int nativeInitJavaCallbacks();
    private native int nativeGetWidth();
    private native int nativeGetHeight();
    // [ONS-BRIDGE] 语音字节拉取（三段式活捕获 + 按存储名补读）；
    // public 供 com.core.ons.OnsExtractBridge 调用，JNI 符号不受修饰符影响
    public native int nativeOnsWaveSize();
    public native byte[] nativeOnsWaveName();
    public native byte[] nativeOnsWaveBytes();
    public native void nativeOnsWaveDrop();
    public native byte[] nativeOnsReadFile(byte[] name);

    /** [ONS-BRIDGE] libonsyuri 提取事件上行入口（SDL 线程）。 */
    public void onBridgeEvent(byte[] payload) {
        OnsExtractBridge.get().onEvent(payload);
    }

    @Override public void loadLibraries() {
        OnsLibLoader.load(this);
    }

    @Override public String[] getLibraries() {
        return new String[]{"SDL2", "lua", "jpeg", "bz2", "modplug", "SDL2_image", "SDL2_mixer", "SDL2_ttf", OnsSettings.PREF_NAME};
    }

    @Override public String getMainSharedObject() {
        return OnsLibLoader.getMainSharedObject(this).getAbsolutePath();
    }

    @Override public String[] getArguments() {
        if (onsArgs == null) onsArgs = new ArrayList<>();
        return onsArgs.toArray(new String[0]);
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        gameRoot = firstNonEmpty(
                getIntent().getStringExtra(LaunchContract.PATH),
                getIntent().getStringExtra(LaunchContract.GAME_PATH),
                getIntent().getStringExtra(LaunchContract.ROOT_URI),
                getIntent().getStringExtra(LaunchContract.GAME_URI));
        gameRoot = normalizeRootPath(gameRoot);
        onsArgs = getIntent().getStringArrayListExtra(LaunchContract.GAME_ARGS);
        OnsSettings settings = OnsSettings.load(this);
        if (onsArgs == null) onsArgs = settings.buildArgs(this, gameRoot);
        ignoreCutout = getIntent().getBooleanExtra(LaunchContract.IGNORE_CUTOUT, settings.ignoreCutout);
        // 提前加载/释放 assets：确保 libonsyuri 与内置 DroidSansFallback.ttf 在 SDLActivity 启动前可用。
        OnsLibLoader.load(this);
        ensureDefaultFont();
        super.onCreate(savedInstanceState);
        fixSurfaceCentering();  // 修复平板设备上画面不居中的问题
        try { nativeInitJavaCallbacks(); } catch (Throwable t) { Log.w(TAG, "nativeInitJavaCallbacks failed", t); }
        setupVirtualControls();
        // [ONS-BRIDGE] 提取桥与面板：桥先挂接（可能早于面板收到事件），面板装进覆盖层
        OnsExtractBridge.get().attach(this);
        try {
            extractPanel = new OnsExtractPanel(new com.core.ons.OnsExtractFacade(this));
            extractPanel.install(onsOverlay, this::toggleVirtualMouseMode, this::isVirtualMouseMode);
        } catch (Throwable t) { Log.w(TAG, "extract panel install failed", t); }
        try {
            savePanel = new OnsSavePanel(new com.core.ons.OnsExtractFacade(this));
            // 存档入口走右缘固定存档键（不再装「存」悬浮球）
            savePanel.setEntryChipVisible(false);
            savePanel.install(onsOverlay);
            if (extractPanel != null) extractPanel.setSavesToggle(savePanel::toggle);
        } catch (Throwable t) { Log.w(TAG, "save panel install failed", t); }
        fullscreen();
    }

    @Override public void onResume() {
        super.onResume();
        if (gameRoot != null) com.core.engine.PlayTimeTracker.onForeground(this, gameRoot);
        fullscreen();
        // 播片期间切后台会暂停解码，回到前台必须恢复，否则视频停在暂停帧。
        if (videoOverlay != null) videoOverlay.onHostResume();
    }

    @Override public void onPause() {
        super.onPause();
        if (gameRoot != null) com.core.engine.PlayTimeTracker.onBackground(gameRoot);
        // 切后台时暂停解码：overlay 不随 Activity 销毁，回来还在。
        if (videoOverlay != null) videoOverlay.onHostPause();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) fullscreen();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        // 提取面板按键（webgametxt 语义）：面板收起只认 RB 呼出；展开时消费全部按键
        // （手柄不再注入游戏），故置于虚拟鼠标之前
        if (extractPanel != null && extractPanel.handleKey(event)) return true;
        // 手柄方向键虚拟鼠标：D-pad 移动光标、A 键点击（仅虚拟鼠标模式；触摸模式直传游戏）
        if (virtualMouseMode && virtualMouse != null && virtualMouse.handleKey(event)) return true;
        // 播片期间按键优先给视频层：任意键跳过，音量键仍交还系统。
        // BACK 不在此列——它沿用下面 tyn 自己的双击退出语义，
        // 避免播 OP 时误触退出游戏。
        if (event != null && videoOverlay != null && videoOverlay.isPlaying()) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_VOLUME_UP
                    || code == KeyEvent.KEYCODE_VOLUME_DOWN
                    || code == KeyEvent.KEYCODE_VOLUME_MUTE
                    || code == KeyEvent.KEYCODE_MUTE) {
                return super.dispatchKeyEvent(event);
            }
            // BACK 不在此处吞掉：落到下方既有的双击退出 / ESC 透传分支。
            // 交给 super 会绕过 ONScripter 自身的本地处理——鼠标侧键 BACK 不被静默消费，
            // 普通 BACK 首按也不会透传 ESC 给游戏。
            if (code != KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) videoOverlay.skipByKey();
                return true;
            }
        }
        // BACK 首按透传 ESC 给 ONS（游戏内取消/右键语义），2 秒内双击真正退出；
        // ESC 的 down+up 在 DOWN 时成对发送，UP 一律吞掉，避免重复/悬空事件。
        // 鼠标来源的 BACK（侧键）静默消费：不透传 ESC、不计入双击退出
        //（与 libsdl3 链 SDLSurface 吞掉鼠标 BACK 的净行为一致）。
        if (event != null && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if ((event.getSource() & InputDevice.SOURCE_MOUSE) != 0) return true;
            switch (event.getAction()) {
                case KeyEvent.ACTION_DOWN:
                    if (event.getRepeatCount() == 0) {
                        if (DoubleBackExit.shouldExit(this)) finish();
                        else sendEscToOns();
                    }
                    return true;
                default:
                    return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override public void onBackPressed() {
        // 无视图消费 BACK 时的兜底路径：只布防退出窗口，不向游戏透传
        if (DoubleBackExit.shouldExit(this)) finish();
    }

    @Override protected void exitFromBack() {
        // 基类旧路径（dispatchBackKey/预测回调）的兜底：真正退出而非发 ESC
        finish();
    }

    private void sendEscToOns() {
        Log.d(TAG, "send ESC to ONS");
        try {
            SDLActivity.onNativeKeyDown(KeyEvent.KEYCODE_ESCAPE);
            SDLActivity.onNativeKeyUp(KeyEvent.KEYCODE_ESCAPE);
        } catch (Throwable t) {
            Log.w(TAG, "send ESC failed", t);
        }
    }

    public int getFD(byte[] pathbyte, int mode) {
        String utf8Path = null;
        String gbkPath = null;
        File file = null;
        try {
            if (pathbyte == null || gameRoot == null || gameRoot.isEmpty()) return -1;
            utf8Path = decodePath(pathbyte);
            gbkPath = decodePath(pathbyte, "GBK");
            file = resolveGameFile(utf8Path);
            if (file == null) return -1;
            if (mode != 0) {
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
            }
            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, mode == 0 ? ParcelFileDescriptor.MODE_READ_ONLY : (ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE));
            int fd = pfd.detachFd();
            Log.i(TAG, "getFD fd=" + fd + " mode=" + mode + " file=" + file);
            return fd;
        } catch (FileNotFoundException expectedMiss) {
            // ONS probes many optional archive/script/image names during startup.
            // A missing read target is normal and must not emit thousands of stack traces.
            if (mode != 0 || (file != null && file.exists())) {
                logGetFdFailure(pathbyte, mode, utf8Path, gbkPath, file);
                Log.w(TAG, "getFD open failed: " + expectedMiss.getMessage());
            }
            return -1;
        } catch (Throwable t) {
            logGetFdFailure(pathbyte, mode, utf8Path, gbkPath, file);
            Log.w(TAG, "getFD failed", t);
            return -1;
        }
    }

    public int mkdir(byte[] pathbyte) {
        try {
            if (pathbyte == null || gameRoot == null || gameRoot.isEmpty()) return -1;
            File f = resolveGameFile(decodePath(pathbyte));
            return f != null && (f.exists() || f.mkdirs()) ? 0 : -1;
        } catch (Throwable t) {
            Log.w(TAG, "mkdir failed", t);
            return -1;
        }
    }

    public void playVideo(byte[] pathbyte) {
        if (pathbyte == null) return;
        String path = decodePath(pathbyte);
        Log.i(TAG, "playVideo " + path);
        try {
            File file = resolveVideoFile(path);
            if (file == null || !file.exists()) {
                Log.w(TAG, "video not found: " + path);
                return;
            }
            final String real = file.getAbsolutePath();
            // native 侧从 SDL 线程调用本方法，视图操作必须切到主线程。
            runOnUiThread(() -> startVideoOverlay(real));
        } catch (Throwable t) {
            Log.e(TAG, "playVideo failed", t);
        }
    }

    /**
     * 在本 Activity 窗口内覆盖播放，不启动新 Activity。
     *
     * 为什么不用独立 Activity：本类是 singleInstance + 独立 taskAffinity，
     * 拉起别的 Activity 会引发 task 切换，实测导致 SDL 收到 onStop()，
     * 且视频结束后返回的是启动页而非游戏本身。
     */
    private void startVideoOverlay(String realPath) {
        if (isFinishing() || isDestroyed()) return;
        if (videoOverlay == null) videoOverlay = new OnsVideoOverlay(this);
        boolean ok = videoOverlay.play(realPath, true);
        if (!ok) Log.w(TAG, "overlay refused to play: " + realPath);
    }

    public void playVideo(Uri uri) {
        if (uri == null) return;
        String path = uri.getPath();
        if (path == null || path.isEmpty()) {
            Log.w(TAG, "playVideo(uri) has no path: " + uri);
            return;
        }
        final String real = path;
        runOnUiThread(() -> startVideoOverlay(real));
    }

    public void testVideo() {
        File file = resolveVideoFile("test.mp4");
        if (file != null && file.exists()) playVideo(Uri.fromFile(file));
    }

    private void setupVirtualControls() {
        try {
            controlsVisible = getSharedPreferences(PREF_OVERLAY, MODE_PRIVATE).getBoolean(KEY_OVERLAY_VISIBLE, true);
            virtualMouseMode = getSharedPreferences(PREF_OVERLAY, MODE_PRIVATE).getBoolean(KEY_MOUSE_MODE, true);
            onsOverlay = new FrameLayout(this);
            onsOverlay.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            onsOverlay.setClickable(false);
            onsOverlay.setFocusable(false);

            // 左列 = 顶部折叠键 + 按键列：同列同大小（40dp），折叠只藏按键；
            // 折叠键与右缘同一水平面（距顶 12dp），整列贴左缘（避开系统导航条）
            LinearLayout leftWrap = new LinearLayout(this);
            leftWrap.setOrientation(LinearLayout.VERTICAL);
            toggleButton = makeToggleButton();
            LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(
                    dp(CONTROL_BUTTON_SIZE_DP), dp(CONTROL_BUTTON_SIZE_DP));
            toggleLp.bottomMargin = dp(CONTROL_BUTTON_GAP_DP);
            toggleLp.gravity = Gravity.CENTER_HORIZONTAL;
            leftWrap.addView(toggleButton, toggleLp);
            leftControls = buildControlColumn();
            leftControls.setVisibility(controlsVisible ? View.VISIBLE : View.GONE);
            leftWrap.addView(leftControls);
            rightControls = null;
            FrameLayout.LayoutParams leftLp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.TOP);
            leftLp.topMargin = dp(12);
            leftLp.leftMargin = leftEdgeMargin();
            onsOverlay.addView(leftWrap, leftLp);
            leftWrap.post(this::recalibrateLeftEdge);

            addContentView(onsOverlay, new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT));
            applyVirtualControlsVisibility();
            // 手柄方向键/左摇杆虚拟鼠标（覆盖层纯绘制 + 按键前置拦截 + 光标命中面板优先）
            virtualMouse = new OnsVirtualMouse(onsOverlay, () -> mSurface, this::injectTapAtCursor,
                    (x, y) -> extractPanel != null && extractPanel.dispatchCursorClick(x, y));
        } catch (Throwable t) {
            Log.w(TAG, "setupVirtualControls failed", t);
        }
    }

    private static final int REQ_EXPORT_SAVES = 42001;
    private static final int REQ_IMPORT_SAVES = 42002;
    /** 词典导入（提取面板 SAF 选择回调，面板侧发起）。 */
    public static final int REQ_IMPORT_DICT = 42003;

    /** 引擎实际生效的存档目录（--save-dir 参数优先，缺省 <gameRoot>/save）。 */
    private File currentSaveDir() {
        if (onsArgs != null) {
            for (int i = 0; i + 1 < onsArgs.size(); i++) {
                if ("--save-dir".equals(onsArgs.get(i))) {
                    File f = new File(onsArgs.get(i + 1));
                    if (f.isDirectory() || f.mkdirs()) return f;
                }
            }
        }
        return gameRoot == null ? null : new File(gameRoot, "save");
    }

    /** 提取面板入口：导出当前游戏存档为 zip（系统文件选择器选位置）。 */
    public void exportSaveArchive() {
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/zip");
            String dirName = gameRoot == null ? "ons" : new File(gameRoot).getName();
            intent.putExtra(Intent.EXTRA_TITLE, dirName + "_saves.zip");
            startActivityForResult(intent, REQ_EXPORT_SAVES);
        } catch (Throwable t) {
            Log.w(TAG, "export saves launch failed", t);
        }
    }

    /** 提取面板入口：SAF 选择词典文件导入（回调 REQ_IMPORT_DICT → onDictImportPicked）。 */
    public void importDictionarySaf() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQ_IMPORT_DICT);
        } catch (Throwable t) {
            Log.w(TAG, "dict import launch failed", t);
        }
    }

    /** 提取面板入口：从系统文件选择器选 zip 导入存档（覆盖当前存档目录）。 */
    public void importSaveArchive() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/zip");
            startActivityForResult(intent, REQ_IMPORT_SAVES);
        } catch (Throwable t) {
            Log.w(TAG, "import saves launch failed", t);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_EXPORT_SAVES && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            Uri dest = data.getData();
            File saveDir = currentSaveDir();
            new Thread(() -> {
                int count = -1;
                String err = null;
                try {
                    count = zipDirectory(saveDir, dest);
                } catch (Throwable t) {
                    err = t.getMessage();
                }
                final int fc = count;
                final String ferr = err;
                runOnUiThread(() -> Toast.makeText(this,
                        fc >= 0
                                ? getString(R.string.engine_ons_extract_export_done, fc)
                                : getString(R.string.engine_ons_extract_action_failed) + (ferr == null ? "" : ": " + ferr),
                        Toast.LENGTH_LONG).show());
            }, "ons-export").start();
            return;
        }
        if (requestCode == REQ_IMPORT_SAVES && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            Uri src = data.getData();
            File saveDir = currentSaveDir();
            new Thread(() -> {
                int count = -1;
                String err = null;
                try {
                    count = unzipInto(src, saveDir);
                } catch (Throwable t) {
                    err = t.getMessage();
                }
                final int fc = count;
                final String ferr = err;
                runOnUiThread(() -> Toast.makeText(this,
                        fc >= 0
                                ? getString(R.string.engine_ons_extract_import_done, fc)
                                : getString(R.string.engine_ons_extract_action_failed) + (ferr == null ? "" : ": " + ferr),
                        Toast.LENGTH_LONG).show());
            }, "ons-import").start();
            return;
        }
        if (requestCode == REQ_IMPORT_DICT && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            if (extractPanel != null) extractPanel.onDictImportPicked(data.getData());
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /** 云同步：把当前存档目录 zip 到本地缓存文件（失败返回 null）。 */
    public File zipSavesToCache() {
        File saveDir = currentSaveDir();
        if (saveDir == null || !saveDir.isDirectory()) return null;
        File out = new File(getCacheDir(), "ons_cloud_saves.zip");
        try {
            return zipDirectory(saveDir, Uri.fromFile(out)) >= 0 ? out : null;
        } catch (Throwable t) {
            Log.w(TAG, "zipSavesToCache failed", t);
            return null;
        }
    }

    /** 云同步：把下载的 zip 覆盖导入当前存档目录（返回文件数，失败 -1）。 */
    public int importSavesFromZip(File zip) {
        try {
            return unzipInto(Uri.fromFile(zip), currentSaveDir());
        } catch (Throwable t) {
            Log.w(TAG, "importSavesFromZip failed", t);
            return -1;
        }
    }

    /** 云同步/存档命名用的游戏目录名。 */
    public String gameDisplayName() {
        return gameRoot == null ? "ons" : new File(gameRoot).getName();
    }

    /** 存档面板：列出当前存档目录的存档文件（按修改时间倒序，过滤引擎日志）。 */
    public File[] listSaves() {
        File dir = currentSaveDir();
        if (dir == null || !dir.isDirectory()) return new File[0];
        File[] files = dir.listFiles();
        if (files == null) return new File[0];
        java.util.List<File> saves = new java.util.ArrayList<>();
        for (File f : files) {
            String name = f.getName();
            if (f.isFile() && !"stdout.txt".equals(name) && !"stderr.txt".equals(name)) {
                saves.add(f);
            }
        }
        saves.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return saves.toArray(new File[0]);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        // 手柄左摇杆移动虚拟光标（面板展开时也可用；音量类事件交还系统）
        if (virtualMouseMode && virtualMouse != null && virtualMouse.handleMotion(event)) return true;
        return super.dispatchGenericMotionEvent(event);
    }

    private int zipDirectory(File dir, Uri dest) throws java.io.IOException {
        return com.core.ons.SaveZipUtil.zipDirectory(dir, dest, getContentResolver());
    }

    private void zipDirectoryInner(File root, File file, java.util.zip.ZipOutputStream zip, int[] count)
            throws java.io.IOException {
        File[] children = file.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) {
                zipDirectoryInner(root, child, zip, count);
            } else {
                String entryName = root.toPath().relativize(child.toPath()).toString().replace('\\', '/');
                zip.putNextEntry(new java.util.zip.ZipEntry(entryName));
                try (java.io.FileInputStream in = new java.io.FileInputStream(child)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) zip.write(buf, 0, n);
                }
                zip.closeEntry();
                count[0]++;
            }
        }
    }

    private int unzipInto(Uri source, File dir) throws java.io.IOException {
        return com.core.ons.SaveZipUtil.unzipInto(source, dir, getContentResolver());
    }

    /** 虚拟鼠标 A 键点击：在 SDL Surface 视图坐标处合成一次完整触摸。 */
    private void injectTapAtCursor(View surface, float x, float y) {        try {
            long now = android.os.SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
            down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            surface.dispatchTouchEvent(down);
            down.recycle();
            final View target = surface;
            final float fx = x, fy = y;
            mainHandler().postDelayed(() -> {
                try {
                    long t2 = android.os.SystemClock.uptimeMillis();
                    MotionEvent up = MotionEvent.obtain(t2, t2, MotionEvent.ACTION_UP, fx, fy, 0);
                    up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                    target.dispatchTouchEvent(up);
                    up.recycle();
                } catch (Throwable t) {
                    Log.w(TAG, "virtual mouse up failed", t);
                }
            }, 60);
        } catch (Throwable t) {
            Log.w(TAG, "virtual mouse tap failed", t);
        }
    }

    private android.os.Handler mainHandler() {
        return new android.os.Handler(android.os.Looper.getMainLooper());
    }

    private void toggleVirtualControls() {
        controlsVisible = !controlsVisible;
        getSharedPreferences(PREF_OVERLAY, MODE_PRIVATE).edit().putBoolean(KEY_OVERLAY_VISIBLE, controlsVisible).apply();
        applyVirtualControlsVisibility();
    }

    private void applyVirtualControlsVisibility() {
        int vis = controlsVisible ? View.VISIBLE : View.GONE;
        if (leftControls != null) leftControls.setVisibility(vis);
        if (rightControls != null) rightControls.setVisibility(vis);
        if (toggleButton != null) toggleButton.setImageResource(
                controlsVisible ? R.drawable.ons_toggle_up : R.drawable.ons_toggle_down);
    }

    private LinearLayout buildControlColumn() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        int[] keys = new int[]{
                KeyEvent.KEYCODE_ESCAPE,
                KeyEvent.KEYCODE_CTRL_LEFT,
                KeyEvent.KEYCODE_A,
                KeyEvent.KEYCODE_MENU,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_SPACE
        };
        String[] labels = new String[]{
                "ESC",
                "SKIP",
                "AUTO",
                "MENU",
                "OK",
                "NEXT"
        };
        for (int i = 0; i < keys.length; i++) {
            TextView button = makeActionButton(labels[i], keys[i], false);
            if (labels[i].contains("AUTO")) autoButtons.add(button);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(CONTROL_BUTTON_SIZE_DP), dp(CONTROL_BUTTON_SIZE_DP));
            lp.topMargin = dp(CONTROL_BUTTON_GAP_DP);
            lp.bottomMargin = dp(CONTROL_BUTTON_GAP_DP);
            column.addView(button, lp);
        }
        return column;
    }

    private void updateAutoButtons() {
        for (TextView b : autoButtons) {
            if (b == null) continue;
            styleVirtualButton(b, autoMode, false);
            b.setText("AUTO");
        }
    }

    private void styleVirtualButton(TextView tv, boolean active, boolean toggle) {
        tv.setBackground(makeVirtualButtonBackground(active, toggle));
        tv.setTextColor(Color.WHITE);
    }

    private GradientDrawable makeVirtualButtonBackground(boolean active, boolean toggle) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(toggle ? 11 : 10));
        if (active) {
            bg.setColor(Color.argb(210, 0, 122, 255));
            bg.setStroke(dp(2), Color.argb(230, 255, 255, 255));
        } else {
            bg.setColor(Color.argb(166, 16, 16, 16));
            bg.setStroke(dp(1), Color.argb(135, 255, 255, 255));
        }
        return bg;
    }

    @SuppressLint("AppCompatCustomView") // SDLActivity is a platform Activity; its lightweight overlay intentionally uses platform widgets.
    private TextView makeActionButton(String label, int keyCode, boolean toggle) {
        TextView tv = new TextView(this) {
            @Override
            public boolean performClick() {
                return super.performClick();
            }
        };
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(toggle ? 16 : 9);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setLines(toggle ? 1 : 2);
        tv.setIncludeFontPadding(false);
        tv.setLineSpacing(0f, 0.92f);
        tv.setTag(label.contains("AUTO") ? "auto" : "normal");
        styleVirtualButton(tv, label.contains("AUTO") && autoMode, toggle);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                toggle ? dp(TOGGLE_BUTTON_SIZE_DP) : dp(CONTROL_BUTTON_SIZE_DP),
                toggle ? dp(TOGGLE_BUTTON_SIZE_DP) : dp(CONTROL_BUTTON_SIZE_DP));
        tv.setLayoutParams(lp);
        tv.setPadding(dp(1), dp(3), dp(1), dp(3));
        final boolean[] touchActivation = {false};
        tv.setOnClickListener(v -> {
            if (touchActivation[0]) return;
            if (toggle) {
                toggleVirtualControls();
            } else {
                SDLActivity.onNativeKeyDown(keyCode);
                SDLActivity.onNativeKeyUp(keyCode);
                if ("auto".equals(v.getTag())) {
                    autoMode = !autoMode;
                    updateAutoButtons();
                }
            }
        });
        tv.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                v.setAlpha(0.65f);
                if (!toggle) {
                    SDLActivity.onNativeKeyDown(keyCode);
                }
                return true;
            } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                v.setAlpha(1f);
                if (toggle) {
                    toggleVirtualControls();
                } else {
                    SDLActivity.onNativeKeyUp(keyCode);
                    if ("auto".equals(v.getTag())) {
                        autoMode = !autoMode;
                        updateAutoButtons();
                    }
                }
                if (e.getAction() == MotionEvent.ACTION_UP) {
                    touchActivation[0] = true;
                    try {
                        v.performClick();
                    } finally {
                        touchActivation[0] = false;
                    }
                }
                return true;
            }
            return true;
        });
        return tv;
    }

    @SuppressLint("AppCompatCustomView") // SDLActivity is a platform Activity; its lightweight overlay intentionally uses platform widgets.
    private ImageButton makeToggleButton() {
        ImageButton button = new ImageButton(this);
        button.setImageResource(controlsVisible ? R.drawable.ons_toggle_up : R.drawable.ons_toggle_down);
        button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        button.setBackground(makeVirtualButtonBackground(false, false));
        button.setColorFilter(Color.WHITE);
        // 与按键列同款尺寸/内边距
        int pad = dp(8);
        button.setPadding(pad, pad, pad, pad);
        button.setLayoutParams(new FrameLayout.LayoutParams(dp(CONTROL_BUTTON_SIZE_DP), dp(CONTROL_BUTTON_SIZE_DP)));
        button.setOnClickListener(v -> toggleVirtualControls());
        button.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                v.setAlpha(0.65f);
                return true;
            } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                v.setAlpha(1f);
                if (e.getAction() == MotionEvent.ACTION_UP) {
                    v.performClick();
                }
                return true;
            }
            return true;
        });
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------
    // 点击模式（虚拟鼠标 ↔ 触摸）与左缘贴边
    // ------------------------------------------------------------------

    /** 虚拟鼠标模式开/关（触摸直传游戏）；持久化在 PREF_OVERLAY。 */
    private boolean virtualMouseMode = true;

    private boolean isVirtualMouseMode() {
        return virtualMouseMode;
    }

    private void toggleVirtualMouseMode() {
        virtualMouseMode = !virtualMouseMode;
        getSharedPreferences(PREF_OVERLAY, MODE_PRIVATE)
                .edit().putBoolean(KEY_MOUSE_MODE, virtualMouseMode).apply();
        // 切回触摸：隐藏光标，D-pad/A 不再被消费
        if (!virtualMouseMode && virtualMouse != null) virtualMouse.hideCursor();
    }

    /** 左缘贴边边距 = 2dp + 左侧系统栏 inset。 */
    private int leftEdgeMargin() {
        try {
            android.view.WindowInsets ins = getWindow().getDecorView().getRootWindowInsets();
            if (ins != null) {
                return dp(2) + ins.getInsets(android.view.WindowInsets.Type.systemBars()).left;
            }
        } catch (Throwable ignored) {
        }
        return dp(2);
    }

    /** onCreate 时 insets 未就绪：attach 后再校准左缘边距。 */
    private void recalibrateLeftEdge() {
        try {
            LinearLayout wrap = (LinearLayout) leftControls.getParent();
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) wrap.getLayoutParams();
            int margin = leftEdgeMargin();
            if (lp.leftMargin != margin) {
                lp.leftMargin = margin;
                wrap.setLayoutParams(lp);
            }
        } catch (Throwable ignored) {
        }
    }

    private void ensureDefaultFont() {
        if (gameRoot == null || gameRoot.isEmpty()) return;
        try {
            File fallbackFont = new File(gameRoot, "default.ttf");
            if (fallbackFont.exists()) return;
            File builtin = new File(getFilesDir(), "DroidSansFallback.ttf");
            if (!builtin.exists()) return;
            File parent = fallbackFont.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            copyFile(builtin, fallbackFont);
            Log.i(TAG, "default.ttf fallback installed: " + fallbackFont);
        } catch (Throwable t) {
            Log.w(TAG, "install default.ttf fallback failed", t);
        }
    }

    private File resolveVideoFile(String raw) {
        File file = resolveGameFile(raw);
        if (file == null) return null;
        if (file.exists()) return file;
        File mp4 = replaceExtension(file, ".mp4");
        if (mp4.exists()) return mp4;
        File ci = findFileIgnoreCase(file);
        if (ci != null && ci.exists()) return ci;
        File ciMp4 = findFileIgnoreCase(mp4);
        return ciMp4 != null ? ciMp4 : file;
    }

    private File resolveGameFile(String raw) {
        if (raw == null) return null;
        String path = raw.replace('\\', '/').trim();
        if (path.startsWith("file://")) path = path.substring("file://".length());
        File file = new File(path);
        if (!file.isAbsolute()) file = new File(gameRoot, path);
        File ci = findFileIgnoreCase(file);
        return ci != null ? ci : file;
    }

    private File findFileIgnoreCase(File file) {
        if (file == null || file.exists()) return file;
        File parent = file.getParentFile();
        if (parent == null) return null;
        File fixedParent = parent.exists() ? parent : findFileIgnoreCase(parent);
        if (fixedParent == null || !fixedParent.isDirectory()) return null;
        File[] list = fixedParent.listFiles();
        if (list == null) return null;
        String wanted = file.getName();
        for (File f : list) {
            if (f.getName().equalsIgnoreCase(wanted)) return f;
        }
        return null;
    }

    private File replaceExtension(File file, String ext) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        File parent = file.getParentFile();
        return new File(parent == null ? new File(".") : parent, base + ext);
    }

    @Override
    @SuppressLint("MissingSuperCall")
    public void onDestroy() {
        // 先收掉视频覆盖层：ijk 的 Surface/播放器必须在本进程被杀之前释放，
        // 否则 HWUI 渲染线程会操作已销毁的 SurfaceTexture，退出游戏时崩溃
        //（FORTIFY: pthread_mutex_lock called on a destroyed mutex / hwuiTask1 SIGABRT）。
        if (videoOverlay != null) {
            videoOverlay.dismiss();
            videoOverlay = null;
        }
        if (extractPanel != null) {
            extractPanel.release();
            extractPanel = null;
        }
        if (savePanel != null) {
            savePanel.release();
            savePanel = null;
        }
        OnsExtractBridge.get().detach();
        // ONS runs in a dedicated process. SDL native teardown can destroy
        // graphics mutexes while an OEM HWUI worker still references them.
        Log.i(TAG, "terminate dedicated ONS process before SDL/HWUI teardown");
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    /**
     * 修复平板设备上画面不居中的问题
     * SDLActivity默认不设置SurfaceView居中，导致在平板(4:3/16:10)上运行16:9游戏时画面底部对齐
     */
    private void fixSurfaceCentering() {
        try {
            if (mLayout != null && mSurface != null) {
                // 获取当前的LayoutParams
                ViewGroup.LayoutParams lp = mSurface.getLayoutParams();
                if (lp instanceof RelativeLayout.LayoutParams) {
                    RelativeLayout.LayoutParams rlp = (RelativeLayout.LayoutParams) lp;
                    // 添加居中规则
                    rlp.addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE);
                    mSurface.setLayoutParams(rlp);
                    Log.i(TAG, "Fixed surface centering for tablet display");
                } else {
                    // 如果不是RelativeLayout.LayoutParams，重新创建
                    RelativeLayout.LayoutParams newLp = new RelativeLayout.LayoutParams(
                            RelativeLayout.LayoutParams.MATCH_PARENT,
                            RelativeLayout.LayoutParams.MATCH_PARENT);
                    newLp.addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE);
                    mSurface.setLayoutParams(newLp);
                    Log.i(TAG, "Recreated layout params with centering for tablet display");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "fixSurfaceCentering failed", t);
        }
    }

    private void fullscreen() {
        Window window = getWindow();
        if (ignoreCutout && Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = window.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            window.setAttributes(lp);
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = window.getDecorView().getWindowInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
        window.getDecorView().setSystemUiVisibility(
                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
    }

    private String normalizeRootPath(String value) {
        if (value == null) return null;
        String v = value.trim();
        if (v.startsWith("file://")) v = v.substring("file://".length());
        if (v.startsWith("content://")) {
            try {
                Uri uri = Uri.parse(v);
                String docId = null;
                // tree-document 混合 URI（tree/<treeId>/document/<docId>）与纯 document URI：
                // DocumentsContract.getDocumentId 只接受纯 document 形式，混合形式（游戏目录以
                // 子文档挂在游戏库 tree 下，如 tree/primary:lib/game/document/primary:lib/game/<dir>）
                // 会抛 IllegalArgumentException；此处取 /document/ 之后的编码段解码得完整子文档 id，
                // 避免回退 getTreeDocumentId 只取到 tree 根目录（Artemis 等严格按目录加载的引擎会因此崩溃）。
                String encodedPath = uri.getEncodedPath();
                if (encodedPath != null) {
                    int marker = encodedPath.indexOf("/document/");
                    if (marker >= 0) {
                        try { docId = Uri.decode(encodedPath.substring(marker + "/document/".length())); } catch (Throwable ignored) { }
                    }
                }
                if (docId == null || docId.isEmpty()) {
                    try { docId = android.provider.DocumentsContract.getTreeDocumentId(uri); } catch (Throwable ignored) { docId = null; }
                }
                if (docId == null || docId.isEmpty()) {
                    try { docId = android.provider.DocumentsContract.getDocumentId(uri); } catch (Throwable ignored) { docId = null; }
                }
                String path = docIdToPath(docId);
                if (path != null) return path;
            } catch (Throwable ignored) { }
        }
        return v;
    }

    private String docIdToPath(String docId) {
        if (docId == null) return null;
        int colon = docId.indexOf(':');
        String volume = colon >= 0 ? docId.substring(0, colon) : docId;
        String rel = colon >= 0 ? docId.substring(colon + 1) : "";
        if ("primary".equalsIgnoreCase(volume)) return "/storage/emulated/0" + (rel.isEmpty() ? "" : "/" + rel);
        if (volume != null && !volume.isEmpty()) return "/storage/" + volume + (rel.isEmpty() ? "" : "/" + rel);
        return null;
    }

    private String firstNonEmpty(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) return v;
        }
        return null;
    }

    private String decodePath(byte[] pathbyte) {
        return new String(pathbyte, StandardCharsets.UTF_8).replace('\\', '/');
    }

    private String decodePath(byte[] pathbyte, String charsetName) {
        try {
            return new String(pathbyte, Charset.forName(charsetName)).replace('\\', '/');
        } catch (Throwable ignored) {
            return "<" + charsetName + " unavailable>";
        }
    }

    private void logGetFdFailure(byte[] pathbyte, int mode, String utf8Path, String gbkPath, File file) {
        try {
            File root = gameRoot == null ? null : new File(gameRoot);
            File parent = file == null ? null : file.getParentFile();
            boolean allFilesAccess = Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                    || Environment.isExternalStorageManager();
            Log.w(TAG, "getFD diagnostic"
                    + " mode=" + mode
                    + " bytes=" + bytesToHex(pathbyte, 96)
                    + " utf8=" + printable(utf8Path)
                    + " gbk=" + printable(gbkPath)
                    + " resolved=" + printable(file == null ? null : file.getAbsolutePath())
                    + " exists=" + (file != null && file.exists())
                    + " readable=" + (file != null && file.canRead())
                    + " parentExists=" + (parent != null && parent.exists())
                    + " parentReadable=" + (parent != null && parent.canRead())
                    + " root=" + printable(gameRoot)
                    + " rootExists=" + (root != null && root.exists())
                    + " rootReadable=" + (root != null && root.canRead())
                    + " allFilesAccess=" + allFilesAccess);
        } catch (Throwable diagnosticError) {
            Log.w(TAG, "getFD diagnostic failed", diagnosticError);
        }
    }

    private String bytesToHex(byte[] bytes, int maxBytes) {
        if (bytes == null) return "null";
        int count = Math.min(bytes.length, Math.max(0, maxBytes));
        StringBuilder out = new StringBuilder(count * 2 + 16);
        for (int i = 0; i < count; i++) {
            int value = bytes[i] & 0xff;
            if (value < 16) out.append('0');
            out.append(Integer.toHexString(value));
        }
        if (bytes.length > count) out.append("…(").append(bytes.length).append(" bytes)");
        return out.toString();
    }

    private String printable(String value) {
        return value == null ? "<null>" : '"' + value.replace("\n", "\\n").replace("\r", "\\r") + '"';
    }

    private void copyFile(File from, File to) throws java.io.IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(from);
             java.io.FileOutputStream out = new java.io.FileOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }
}
