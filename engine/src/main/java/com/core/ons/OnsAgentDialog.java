package com.core.ons;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * AI 游戏助手聊天对话框：用户输入 → AgentEngine (LLM + function calling) →
 * 工具调用 → 回复。消息列表支持用户/助手/状态三种样式。
 * 复用翻译 API 配置（base_url/api_key/model），无需额外配置。
 */
public final class OnsAgentDialog {

    private static final int COLOR_USER = 0xFF2DD4BF;
    private static final int COLOR_ASSISTANT = 0xFFE2E8F0;
    private static final int COLOR_STATUS = 0xFF94A3B8;
    private static final int BG_PANEL = 0xE6181818;
    private static final int BG_INPUT = 0xFF1E293B;

    private OnsAgentDialog() {
    }

    /** 打开聊天对话框。 */
    public static void show(Activity activity, OnsExtractPanel panel) {
        OnsTranslateClient client = null; // 复用翻译 API 配置

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(BG_PANEL);
        bg.setCornerRadius(dp(activity, 12));
        root.setBackground(bg);
        int pad = dp(activity, 12);
        root.setPadding(pad, pad, pad, pad);

        // 标题
        TextView title = new TextView(activity);
        title.setText("🤖 AI 游戏助手");
        title.setTextColor(0xFF2DD4BF);
        title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        root.addView(title);

        // 消息列表（可滚动）
        ScrollView msgScroller = new ScrollView(activity);
        msgScroller.setVerticalScrollbarPosition(View.SCROLLBAR_POSITION_RIGHT);
        LinearLayout msgList = new LinearLayout(activity);
        msgList.setOrientation(LinearLayout.VERTICAL);
        msgScroller.addView(msgList);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        msgLp.topMargin = dp(activity, 8);
        msgLp.bottomMargin = dp(activity, 8);
        root.addView(msgScroller, msgLp);

        // 输入行：EditText + 发送按钮
        LinearLayout inputRow = new LinearLayout(activity);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.BOTTOM);
        EditText inputField = new EditText(activity);
        inputField.setHint("输入问题（如：分析这句语法）");
        inputField.setTextColor(0xFFE2E8F0);
        inputField.setHintTextColor(0xFF64748B);
        inputField.setTextSize(13);
        inputField.setBackground(roundRect(activity, BG_INPUT, 8));
        inputField.setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 8));
        inputField.setMaxLines(3);
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        inputRow.addView(inputField, inputLp);

        Button sendBtn = new Button(activity);
        sendBtn.setText("➤");
        sendBtn.setTextSize(14);
        sendBtn.setBackground(roundRect(activity, 0xFF2DD4BF, 8));
        sendBtn.setTextColor(0xFF000000);
        LinearLayout.LayoutParams sendLp = new LinearLayout.LayoutParams(
                dp(activity, 40), dp(activity, 40));
        sendLp.leftMargin = dp(activity, 6);
        inputRow.addView(sendBtn, sendLp);
        root.addView(inputRow);

        // 对话历史（JSON 消息列表）
        final JSONArray history = new JSONArray();

        // 消息渲染辅助
        final OnsAgentEngine.ToolHost toolHost = buildToolHost(activity, panel);

        Runnable[] sendAction = new Runnable[1];
        sendAction[0] = () -> {
            String text = inputField.getText() == null ? "" : inputField.getText().toString().trim();
            if (text.isEmpty()) return;
            inputField.setText("");
            addMessage(msgList, activity, "user", text);
            scrollToBottom(msgScroller);

            // 禁用发送按钮直到回复
            sendBtn.setEnabled(false);

            OnsAgentEngine.chat(activity, history, text, toolHost,
                    new OnsAgentEngine.AgentCallback() {
                        private TextView statusView;

                        @Override
                        public void onStatus(String status) {
                            activity.runOnUiThread(() -> {
                                if (statusView == null) {
                                    statusView = addMessage(msgList, activity, "status", status);
                                } else {
                                    statusView.setText(status);
                                }
                            });
                        }

                        @Override
                        public void onResponse(String response) {
                            activity.runOnUiThread(() -> {
                                if (statusView != null) {
                                    msgList.removeView(statusView);
                                    statusView = null;
                                }
                                addMessage(msgList, activity, "assistant", response);
                                scrollToBottom(msgScroller);
                                sendBtn.setEnabled(true);
                                // 保存 assistant 回复到历史
                                try {
                                    JSONObject m = new JSONObject()
                                            .put("role", "assistant")
                                            .put("content", response);
                                    history.put(m);
                                } catch (Throwable ignored) {
                                }
                            });
                        }
                    });

            // 保存用户消息到历史
            try {
                JSONObject m = new JSONObject().put("role", "user").put("content", text);
                history.put(m);
            } catch (Throwable ignored) {
            }
        };
        sendBtn.setOnClickListener(v -> sendAction[0].run());

        new android.app.AlertDialog.Builder(activity)
                .setView(root)
                .setNegativeButton("关闭", null)
                .show();
    }

    private static TextView addMessage(LinearLayout list, Activity activity,
                                       String role, String text) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setLineSpacing(0f, 1.4f);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(activity, 8));
        switch (role) {
            case "user":
                tv.setTextColor(COLOR_USER);
                bg.setColor(0xFF1A1A2E);
                bg.setStroke(dp(activity, 1), COLOR_USER);
                tv.setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 8));
                break;
            case "assistant":
                tv.setTextColor(COLOR_ASSISTANT);
                bg.setColor(0xFF16213E);
                tv.setPadding(dp(activity, 10), dp(activity, 8), dp(activity, 10), dp(activity, 8));
                break;
            default:
                tv.setTextColor(COLOR_STATUS);
                tv.setTextSize(11);
                tv.setPadding(dp(activity, 10), dp(activity, 4), dp(activity, 10), dp(activity, 4));
                break;
        }
        tv.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(activity, 4);
        list.addView(tv, lp);
        return tv;
    }

    private static void scrollToBottom(ScrollView scroller) {
        scroller.post(() -> scroller.fullScroll(View.FOCUS_DOWN));
    }

    private static OnsAgentEngine.ToolHost buildToolHost(Activity activity, OnsExtractPanel panel) {
        return new OnsAgentEngine.ToolHost() {
            @Override
            public String getCurrentText() {
                String s = panel.getCurrentSentenceForAgent();
                return s.isEmpty() ? "" : s;
            }

            @Override
            public String getGameName() {
                return panel.getGameDisplayName();
            }

            @Override
            public String lookupWord(String word) {
                return panel.agentLookupWord(word);
            }

            @Override
            public String translateText(String text, String targetLang) {
                return panel.agentTranslate(text, targetLang);
            }

            @Override
            public String takeScreenshot() {
                panel.captureScreenshot();
                return "截图已保存到 screenshots 目录";
            }

            @Override
            public void speakText(String text) {
                panel.agentSpeak(text);
            }

            @Override
            public String getPlayStats() {
                return "游玩统计功能开发中";
            }

            @Override
            public String mcpListServers() {
                return com.core.agent.McpServerProxy.listServers(activity);
            }

            @Override
            public String mcpCallTool(String json) {
                return com.core.agent.McpServerProxy.call(activity, json);
            }
        };
    }

    private static GradientDrawable roundRect(Activity activity, int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(activity, radiusDp));
        return d;
    }

    private static int dp(Activity activity, int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
