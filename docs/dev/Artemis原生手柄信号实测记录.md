# Artemis 原生手柄信号实测记录（2026-10-09）

> 目的：记录 **Artemis 内核原生**（不含 VNLingo 自定义映射）对手柄各信号的反应。
> 设备：本机（alioth），游戏：Girl's Blossom Project（English Version），内核：官方内核（默认）。

## 一、已验证的事实（截图/日志为证）

1. **Artemis 引擎原生读手柄**：标题菜单 `Start / Continue / Load / Config / Extra / Exit` 上，
   连续 3 次 `input gamepad keyevent 20`（D-pad ↓）使高亮**逐项移动**（Start→Continue→Load→Config），
   随后 `input gamepad keyevent 96`（A）触发进入所选项（画面变化）。
   → 即：**D-pad = 菜单上下选择，A = 确认/决定**，由游戏自己处理。
2. **触摸模式下这些信号不进 Java**：同一时段 `adb logcat | grep -E "panel action|YukiArtemis|vmouse"` 为空。
   → 内核/游戏直接消费，我们的 Activity 看不到（这也是"面板手柄快捷键在 Artemis 官方内核下无效"的原因）。
3. **我们的窗持焦时能收到**（虚拟鼠标模式实测）：
   `YukiArtemis: mouse catcher key code=20/96 source=0x401`（手柄源 0x401）。

## 二、逐键探测（方法说明与原始数据）

方法：进游戏 → 稳定后逐键发送 `input gamepad keyevent <code>`，与**上一帧**做像素差；
每键之间等待画面"稳定"再取新基线。脚本：`.tmp/gp_probe2.py`（截图在 `.tmp/gp2/`）。

| 按键 | keyCode | 变化占比 | 判定 |
|---|---|---|---|
| DPAD_UP | 19 | 35.66% | 有变化※ |
| DPAD_DOWN | 20 | 33.65% | 有变化※ |
| DPAD_LEFT | 21 | 33.36% | 有变化※ |
| DPAD_RIGHT | 22 | 32.01% | 有变化※ |
| L1 | 102 | 31.12% | 有变化※ |
| R1 | 103 | 47.18% | 有变化※ |
| L2 | 104 | 25.86% | 有变化※ |
| R2 | 105 | 27.53% | 有变化※ |
| START | 108 | 24.47% | 有变化※ |
| SELECT | 109 | 23.21% | 有变化※ |
| THUMBL | 106 | 21.00% | 有变化※ |
| THUMBR | 107 | 17.17% | 有变化※ |
| BUTTON_A | 96 | 9.14% | 有变化※ |
| BUTTON_B | 97 | 29.08% | 有变化※ |
| BUTTON_X | 99 | 1.67% | 有变化※ |
| BUTTON_Y | 100 | 13.54% | 有变化※ |

※ **警告：这批数字不具判定力**。该场景画面本身在持续动（立绘/背景/演出），
"稳定阈值"未达成，因此每个键的差值里都混着**自然动画漂移**（同为 20%~47%）。
要得到可用结论，必须加**对照组**：同一等待时长、不按键的漂移量作基线，只有显著高于它才算"该键有反应"。

## 三、结论与下一步

- 能确定的：**D-pad = 菜单导航、A = 确认**（第一节第 1 条，截图对比清晰、步进一一对应）；
  **其余键位尚未取得可靠证据**，上表数字不可当结论使用。
- 可靠的测法（二选一，各需约 10 分钟）：
  1. **对照组法**：每个键测两次（按键 / 不按键），比较"按键差值 ÷ 漂移差值"，>3 倍即认定有反应；
  2. **每键重启法**：每个键都从**全新标题画面**开始（重启游戏 ~40s/次），标题菜单是静态的，
     单一按键的差值即可直接判定；配合人工看 1~2 张关键截图。
- 手柄**摇杆/扳机轴**（`AXIS_X/Y/RX/RY/HAT_X/HAT_Y/LTRIGGER/RTRIGGER`）无法用 adb 注入，
  只能在**真实手柄**上测：`adb logcat | grep "vmouse motion"` 会打出 source 与各轴数值。

---

## 补充实测（2026-10-12）：扳机轴-only 连接模式

游戏《流星ワールドアクター》/ 自研 clean 内核下实测：

- **本连接模式下扳机不产生键码**：`adb shell getevent -l` 按 R2 只有
  `EV_ABS ABS_GAS 000003ff→00000000`（轴量 0→1023→0），**没有任何 BTN 按键事件**；
  L2 同理（`ABS_BRAKE`）。键位布局 `/system/usr/keylayout/Vendor_045e_Product_02fd.kl`
  把扳机只映射为 `axis 0x09 RTRIGGER / axis 0x0a LTRIGGER`。
- 与上表（2026-10-09 实测 L2=104 / R2=105 有变化）**并不矛盾**：那份是 `input gamepad keyevent`
  注入的合成事件；物理按键是否产生键码与**手柄连接模式**有关（同一手柄早前连接时
  应用侧收到过 code=105）。
- 结论：物理扳机**不能指望系统给键码**，应用必须自己做轴→键合成——已落地
  `com.core.engine.TriggerAxisKeys`（详见 `MEMORY.md` 2026-10-12 段、登记册 G-010）。
- 另发现：Artemis 按键焦点在独立悬浮窗（mouseKeyCatcher）时，手柄**轴事件进 View 树
  默认丢弃**、到不了 Activity —— 覆盖窗持焦的宿主必须在 **View 层**接轴事件。
