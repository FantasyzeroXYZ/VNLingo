package com.core.agent

import android.content.Context
import org.json.JSONObject

/**
 * Agent 对话用的 MCP 操作门面（app 进程侧）：解析 OnsAgentEngine 的 op JSON，
 * 分发到 McpServerStore / McpHttpClient。
 */
object McpServerProxy {

    /**
     * @param json {"op":"list"} 或 {"op":"list_tools","server_id":..}
     *             或 {"op":"call","server_id":..,"tool_name":..,"arguments":{..}}
     */
    @JvmStatic
    fun call(context: Context, json: String): String {
        val app = context.applicationContext
        return try {
            val obj = JSONObject(json)
            when (obj.optString("op", "")) {
                "list", "list_servers" -> McpServerStore.listJson(app)
                "list_tools" -> {
                    val server = McpServerStore.get(app, obj.optString("server_id"))
                    val client = McpHttpClient()
                    val session = client.open(server)
                    client.listTools(session).toString()
                }
                "call" -> {
                    val server = McpServerStore.get(app, obj.optString("server_id"))
                    val client = McpHttpClient()
                    val session = client.open(server)
                    val args = obj.optJSONObject("arguments") ?: JSONObject()
                    client.callTool(session, obj.optString("tool_name"), args).toString()
                }
                else -> "未知 MCP 操作: " + obj.optString("op")
            }
        } catch (t: Throwable) {
            "MCP 操作失败: " + (t.message ?: t.toString())
        }
    }

    @JvmStatic
    fun listServers(context: Context): String {
        val summary = McpServerStore.savedSummary(context)
        return summary.ifEmpty { "" } // 空串 = 无 MCP 服务器
    }

    /** 设置页管理用：列出全部服务器。 */
    @JvmStatic
    fun listForSettings(context: Context): List<McpServerStore.Server> =
        McpServerStore.list(context)

    @JvmStatic
    fun add(context: Context, name: String, endpoint: String): McpServerStore.Server =
        McpServerStore.add(context, name, endpoint)

    @JvmStatic
    fun remove(context: Context, id: String) {
        McpServerStore.remove(context, id)
    }
}
