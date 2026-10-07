package com.core.ons;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

/**
 * AI 游戏助手引擎：OpenAI 兼容 chat/completions + function calling 循环。
 *
 * 用户发消息 → LLM 可能返回 tool_calls（查词/截图/翻译等）→ 执行工具 →
 * 结果回传 LLM → 循环直到 LLM 给出最终文本回复。
 *
 * 复用翻译 API 配置（OnsTranslateClient 的 base_url/api_key/model），
 * 不引入新依赖。工具集由宿主通过 [ToolHost] 接口注入。
 */
public final class OnsAgentEngine {

    private static final String TAG = "OnsAgent";
    private static final int MAX_TOOL_ROUNDS = 8;
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 120000;

    /** 工具宿主：由面板/宿主 Activity 实现，提供游戏内操作能力。 */
    public interface ToolHost {
        String getCurrentText();
        String getGameName();
        String lookupWord(String word);
        String translateText(String text, String targetLang);
        String takeScreenshot();
        void speakText(String text);
        String getPlayStats();
        /** MCP：列出已保存的 MCP 服务器（空 = 无）。 */
        String mcpListServers();
        /** MCP：json = {"op":"list","server_id":..} 或 {"op":"call","server_id":..,"tool_name":..,"arguments":{..}} */
        String mcpCallTool(String json);
    }

    /** 工具定义（发给 LLM 的 function schema）。 */
    private static JSONObject tool(String name, String desc, JSONObject params) throws Exception {
        return new JSONObject().put("type", "function")
                .put("function", new JSONObject()
                        .put("name", name).put("description", desc)
                        .put("parameters", params));
    }

    private static JSONArray buildTools() throws Exception {
        JSONArray tools = new JSONArray();
        tools.put(tool("get_current_text",
                "获取当前游戏画面上的对话文本", new JSONObject()));
        tools.put(tool("get_game_info",
                "获取当前游戏名称和游玩统计", new JSONObject()));
        tools.put(tool("lookup_word",
                "查询词典，返回词条、读音和释义",
                new JSONObject().put("type", "object")
                        .put("word", new JSONObject().put("type", "string")
                                .put("description", "要查询的日语词汇"))));
        tools.put(tool("translate_text",
                "翻译文本到目标语言",
                new JSONObject().put("type", "object")
                        .put("text", new JSONObject().put("type", "string")
                                .put("description", "要翻译的文本"))
                        .put("target_lang", new JSONObject().put("type", "string")
                                .put("description", "目标语言，如 中文、英语、日语"))));
        tools.put(tool("take_screenshot",
                "截取当前游戏画面", new JSONObject()));
        tools.put(tool("speak_text",
                "用 TTS 朗读指定文本",
                new JSONObject().put("type", "object")
                        .put("text", new JSONObject().put("type", "string")
                                .put("description", "要朗读的文本"))));
        tools.put(tool("mcp_list_servers",
                "列出本机已确认保存的 MCP 服务器", new JSONObject()));
        tools.put(tool("mcp_list_tools",
                "列出指定 MCP 服务器提供的工具",
                new JSONObject().put("type", "object")
                        .put("server_id", new JSONObject().put("type", "string")
                                .put("description", "mcp_list_servers 返回的 server_id"))));
        tools.put(tool("mcp_call_tool",
                "调用 MCP 服务器的远程工具",
                new JSONObject().put("type", "object")
                        .put("server_id", new JSONObject().put("type", "string")
                                .put("description", "服务器 id"))
                        .put("tool_name", new JSONObject().put("type", "string")
                                .put("description", "工具名"))
                        .put("arguments_json", new JSONObject().put("type", "string")
                                .put("description", "工具参数 JSON 字符串，可为 {}"))));
        return tools;
    }

    private static String systemPrompt(String gameName, String mcpSummary) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 VNLingo 游戏助手，帮助用户玩视觉小说游戏。\n")
          .append("当前游戏：").append(gameName).append("\n")
          .append("你可以：\n")
          .append("- 分析当前对话文本的语法和词汇\n")
          .append("- 翻译句子到任意语言\n")
          .append("- 查询日语词典\n")
          .append("- 截取游戏画面\n")
          .append("- 朗读文本\n")
          .append("- 提供游戏攻略建议\n")
          .append("使用工具获取游戏实时状态。回复用户时使用与用户相同的语言。")
          .append("如果用户的问题不需要调用工具，直接回答。");
        if (mcpSummary != null && !mcpSummary.isEmpty()) {
            sb.append("\n\n").append(mcpSummary);
            sb.append("\n调用 MCP 工具：mcp_list_servers 列出服务器，mcp_list_tools(server_id) 列出工具，mcp_call_tool(server_id, tool_name, arguments_json) 调用。");
        }
        return sb.toString();
    }

    /**
     * 发送消息并处理 function calling 循环。
     *
     * @param history  对话历史（不含本次用户消息；由调用方维护并追加）
     * @param userMsg  用户输入
     * @param host     工具宿主
     * @param callback 结果回调（主线程由调用方保证；onToken 用于流式展示中间工具调用状态）
     */
    public static void chat(Context context, JSONArray history, String userMsg,
                            ToolHost host, AgentCallback callback) {
        new Thread(() -> {
            try {
                callback.onStatus("思考中…");
                String baseUrl = OnsTranslateClient.prefs(context)
                        .getString(OnsTranslateClient.KEY_BASE_URL,
                                OnsTranslateClient.DEFAULT_BASE_URL);
                String apiKey = OnsTranslateClient.prefs(context)
                        .getString(OnsTranslateClient.KEY_API_KEY, "");
                String model = OnsTranslateClient.prefs(context)
                        .getString(OnsTranslateClient.KEY_MODEL,
                                OnsTranslateClient.DEFAULT_MODEL);

                // 构建消息列表
                JSONArray messages = new JSONArray();
                messages.put(new JSONObject().put("role", "system")
                        .put("content", systemPrompt(host.getGameName(), host.mcpListServers())));
                if (history != null) {
                    for (int i = 0; i < history.length(); i++) {
                        messages.put(history.getJSONObject(i));
                    }
                }
                messages.put(new JSONObject().put("role", "user").put("content", userMsg));

                JSONArray tools = buildTools();

                // function calling 循环
                for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
                    JSONObject resp = chatCompletion(baseUrl, apiKey, model,
                            messages.toString(), tools.toString());
                    JSONObject choice = resp.optJSONArray("choices") != null
                            && resp.getJSONArray("choices").length() > 0
                            ? resp.getJSONArray("choices").getJSONObject(0) : null;
                    if (choice == null) throw new IllegalStateException("empty choices");
                    JSONObject msg = choice.optJSONObject("message");
                    if (msg == null) throw new IllegalStateException("no message");

                    JSONArray toolCalls = msg.optJSONArray("tool_calls");
                    if (toolCalls != null && toolCalls.length() > 0) {
                        // LLM 要求调用工具：执行并回传结果
                        messages.put(msg); // 保留 assistant 的 tool_calls 消息
                        for (int i = 0; i < toolCalls.length(); i++) {
                            JSONObject tc = toolCalls.getJSONObject(i);
                            JSONObject fn = tc.optJSONObject("function");
                            if (fn == null) continue;
                            String fnName = fn.optString("name", "");
                            String fnArgs = fn.optString("arguments", "{}");
                            callback.onStatus("执行工具: " + fnName);
                            String result = executeTool(host, fnName, fnArgs);
                            messages.put(new JSONObject()
                                    .put("role", "tool")
                                    .put("tool_call_id", tc.optString("tool_call_id", ""))
                                    .put("content", result));
                        }
                        continue; // 带工具结果再问 LLM
                    }

                    // 纯文本回复 = 最终答案
                    String content = msg.optString("content", "").trim();
                    callback.onResponse(content);
                    return;
                }
                callback.onResponse("(工具调用轮次超限)");
            } catch (Throwable t) {
                Log.w(TAG, "agent chat failed", t);
                callback.onResponse("⚠ " + (t.getMessage() != null ? t.getMessage() : t.toString()));
            }
        }, "ons-agent").start();
    }

    /** 执行工具调用，返回结果文本。 */
    private static String executeTool(ToolHost host, String name, String argsJson) {
        try {
            JSONObject args = new JSONObject(argsJson);
            switch (name) {
                case "get_current_text":
                    String text = host.getCurrentText();
                    return text.isEmpty() ? "（当前无对话文本）" : text;
                case "get_game_info":
                    return "游戏: " + host.getGameName();
                case "lookup_word": {
                    String word = args.optString("word", "");
                    if (word.isEmpty()) return "缺少 word 参数";
                    return host.lookupWord(word);
                }
                case "translate_text": {
                    String txt = args.optString("text", "");
                    String lang = args.optString("target_lang", "中文");
                    if (txt.isEmpty()) return "缺少 text 参数";
                    return host.translateText(txt, lang);
                }
                case "take_screenshot":
                    host.takeScreenshot();
                    return "截图已保存";
                case "speak_text": {
                    String txt = args.optString("text", "");
                    if (txt.isEmpty()) return "缺少 text 参数";
                    host.speakText(txt);
                    return "已朗读";
                }
                case "mcp_list_servers":
                    return host.mcpListServers();
                case "mcp_list_tools": {
                    String serverId = args.optString("server_id", "");
                    if (serverId.isEmpty()) return "缺少 server_id";
                    return host.mcpCallTool("{\"op\":\"list_tools\",\"server_id\":\"" + serverId + "\"}");
                }
                case "mcp_call_tool": {
                    String serverId = args.optString("server_id", "");
                    String toolName = args.optString("tool_name", "");
                    if (serverId.isEmpty() || toolName.isEmpty()) return "缺少 server_id 或 tool_name";
                    String argumentsJson = args.optString("arguments_json", "{}");
                    return host.mcpCallTool("{\"op\":\"call\",\"server_id\":\"" + serverId
                            + "\",\"tool_name\":\"" + toolName + "\",\"arguments\":" + argumentsJson + "}");
                }
                default:
                    return "未知工具: " + name;
            }
        } catch (Throwable t) {
            Log.w(TAG, "tool " + name + " failed", t);
            return "工具执行失败: " + t.getMessage();
        }
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    private static JSONObject chatCompletion(String baseUrl, String apiKey,
                                             String model, String messagesJson,
                                             String toolsJson) throws Exception {
        String url = baseUrl.endsWith("/") ? baseUrl + "chat/completions"
                : baseUrl + "/chat/completions";
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("temperature", 0.3);
        JSONArray messages = new JSONArray(messagesJson);
        body.put("messages", messages);
        JSONArray tools = new JSONArray(toolsJson);
        if (tools.length() > 0) body.put("tools", tools);

        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            if (!apiKey.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }
            int code = conn.getResponseCode();
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String response = stream == null ? "" : readAll(stream);
            if (code >= 400) {
                throw new IllegalStateException("HTTP " + code + ": "
                        + (response.length() > 200 ? response.substring(0, 200) : response));
            }
            return new JSONObject(response);
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    /** 回调接口。 */
    public interface AgentCallback {
        /** 工具调用等中间状态（展示给用户）。 */
        void onStatus(String status);
        /** 最终文本回复。 */
        void onResponse(String text);
    }
}
