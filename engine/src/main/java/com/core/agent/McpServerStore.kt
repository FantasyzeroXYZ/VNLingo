package com.core.agent

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

/**
 * MCP 服务器本地注册表（参考 RinneMobile McpServerStore 移植）：
 * 显式批准的远程 Streamable HTTP MCP 服务器。add_mcp_server 为 LLM 可调工具，
 * 添加即视为用户本机确认；每次远程工具调用仍需走确认流（当前简化为直接执行）。
 */
object McpServerStore {
    private const val PREFS = "vnlingo_mcp_servers"
    private const val KEY_SERVERS = "servers"
    private const val MAX_SERVERS = 12

    data class Server(
        val id: String?,
        val name: String?,
        val endpoint: String?,
        val createdAt: Long,
    )

    fun store(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS)

    @JvmStatic
    @Throws(Exception::class)
    fun add(context: Context, name: String?, endpoint: String?): Server {
        val safeName = validateName(name)
        val safeEndpoint = validateEndpoint(endpoint)
        val servers = read(context)
        for (server in servers) {
            if (server.endpoint.equals(safeEndpoint, ignoreCase = true)) return server
        }
        if (servers.size >= MAX_SERVERS) throw IllegalStateException("最多添加 $MAX_SERVERS 个 MCP 服务器")
        val value = Server(UUID.randomUUID().toString(), safeName, safeEndpoint, System.currentTimeMillis())
        servers.add(value)
        write(context, servers)
        return value
    }

    @JvmStatic
    @Throws(Exception::class)
    fun get(context: Context, id: String?): Server {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}".toRegex())) throw IllegalArgumentException("server_id 格式错误")
        for (server in read(context)) if (server.id.equals(id, ignoreCase = true)) return server
        throw IllegalArgumentException("未找到 MCP 服务器")
    }

    @JvmStatic
    @Throws(Exception::class)
    fun remove(context: Context, id: String?): Server {
        val servers = read(context)
        var target: Server? = null
        for (server in servers) if (server.id.equals(id, ignoreCase = true)) { target = server; break }
        if (target == null) throw IllegalArgumentException("未找到 MCP 服务器")
        servers.remove(target)
        write(context, servers)
        return target
    }

    @JvmStatic
    fun list(context: Context): List<Server> = read(context)

    @JvmStatic
    fun listJson(context: Context): String {
        val items = JSONArray()
        for (server in read(context)) items.put(toJson(server))
        return JSONObject().put("success", true)
            .put("source", "local_confirmed_registry")
            .put("message", "以下 MCP 服务器均已经用户本机确认并保存")
            .put("servers", items)
            .toString()
    }

    @JvmStatic
    fun savedSummary(context: Context): String {
        val servers = read(context)
        if (servers.isEmpty()) return ""
        val value = StringBuilder("本机当前已确认并保存的 MCP：")
        for (server in servers) {
            value.append("\n- ").append(server.name).append("（").append(server.endpoint).append("）")
        }
        return value.toString()
    }

    @JvmStatic
    @Throws(Exception::class)
    fun findByEndpoint(context: Context, endpoint: String?): Server? {
        val safeEndpoint = validateEndpoint(endpoint)
        for (server in read(context)) {
            if (server.endpoint.equals(safeEndpoint, ignoreCase = true)) return server
        }
        return null
    }

    @JvmStatic
    fun preview(server: Server): String =
        "名称：${server.name}\n地址：${server.endpoint}\n传输：Streamable HTTP"

    @JvmStatic
    @Throws(Exception::class)
    fun validateName(value: String?): String {
        val result = value?.trim() ?: ""
        if (result.isEmpty() || result.length > 80) throw IllegalArgumentException("MCP 名称长度应为 1-80 个字符")
        if (result.matches(".*[\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069].*".toRegex()))
            throw IllegalArgumentException("MCP 名称包含不允许的字符")
        return result
    }

    @JvmStatic
    @Throws(Exception::class)
    fun validateEndpoint(value: String?): String {
        var result = value?.trim() ?: ""
        while (result.endsWith("/")) result = result.substring(0, result.length - 1)
        if (result.isEmpty() || result.length > 2048) throw IllegalArgumentException("MCP 地址长度不正确")
        try {
            val uri = URI(result)
            val scheme = uri.scheme
            val host = uri.host
            if (scheme == null || host.isNullOrBlank() || uri.rawUserInfo != null
                || uri.rawQuery != null || uri.rawFragment != null
            ) {
                throw IllegalArgumentException("MCP 地址格式不正确")
            }
            val cleartextLoopback = isLoopbackHost(host)
            if (!scheme.equals("https", ignoreCase = true) &&
                !(cleartextLoopback && scheme.equals("http", ignoreCase = true))
            ) {
                throw IllegalArgumentException("MCP 地址必须使用 HTTPS；仅 localhost 或 127.0.0.1 允许 HTTP")
            }
            return result
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("MCP 地址格式不正确", error)
        }
    }

    private fun isLoopbackHost(host: String): Boolean =
        "localhost".equals(host, ignoreCase = true) || host == "127.0.0.1"

    private fun read(context: Context): MutableList<Server> {
        val raw = store(context).getString(KEY_SERVERS, "[]") ?: "[]"
        return decode(raw)
    }

    private fun write(context: Context, servers: List<Server>) {
        val values = JSONArray()
        for (s in servers) values.put(toJson(s))
        store(context).edit().putString(KEY_SERVERS, values.toString()).apply()
    }

    private fun toJson(s: Server): JSONObject =
        JSONObject().put("id", s.id).put("name", s.name)
            .put("endpoint", s.endpoint).put("created_at", s.createdAt)

    @Throws(Exception::class)
    private fun decode(raw: String?): MutableList<Server> {
        val values = JSONArray(raw ?: "[]")
        val result = mutableListOf<Server>()
        for (i in 0 until values.length()) {
            val obj = values.optJSONObject(i) ?: continue
            val id = obj.optString("id", "")
            val endpoint = obj.optString("endpoint", "")
            if (id.isEmpty() || endpoint.isEmpty()) continue
            result.add(Server(id, obj.optString("name", ""), endpoint, obj.optLong("created_at", 0)))
        }
        return result
    }
}
