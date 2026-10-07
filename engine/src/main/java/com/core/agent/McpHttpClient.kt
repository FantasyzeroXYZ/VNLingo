package com.core.agent

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * MCP Streamable HTTP JSON-RPC 最小客户端（参考 RinneMobile McpHttpClient，
 * OkHttp → HttpURLConnection）。不支持从模型文本注入 auth 头。
 */
class McpHttpClient {

    class Session(val server: McpServerStore.Server, val sessionId: String, val protocolVersion: String)

    private companion object {
        const val MAX_BODY_BYTES = 256L * 1024L
        const val DEFAULT_PROTOCOL_VERSION = "2025-06-18"
        const val CONNECT_TIMEOUT = 15000
        const val READ_TIMEOUT = 35000
    }

    /** initialize + notifications/initialized，返回会话。 */
    @Throws(Exception::class)
    fun open(server: McpServerStore.Server): Session {
        val params = JSONObject()
            .put("protocolVersion", DEFAULT_PROTOCOL_VERSION)
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "VNLingo").put("version", "1"))
        val initialized = request(server, null, DEFAULT_PROTOCOL_VERSION, "initialize", params)
        val negotiated = initialized.result.optString("protocolVersion", DEFAULT_PROTOCOL_VERSION).trim()
        if (!negotiated.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) throw IOException("MCP 返回的协议版本无效")
        try {
            request(server, initialized.sessionId, negotiated, "notifications/initialized", JSONObject())
            return Session(server, initialized.sessionId, negotiated)
        } catch (error: Throwable) {
            terminateSessionBestEffort(server, initialized.sessionId, negotiated)
            throw error
        }
    }

    @Throws(Exception::class)
    fun listTools(session: Session): JSONObject {
        val response = request(session.server, session.sessionId, session.protocolVersion,
            "tools/list", JSONObject())
        val tools = response.result.optJSONArray("tools")
            ?: throw IOException("MCP tools/list 响应缺少 tools")
        return JSONObject()
            .put("server_id", session.server.id)
            .put("server_name", session.server.name)
            .put("protocol_version", session.protocolVersion)
            .put("tool_count", tools.length())
            .put("tools", compactTools(tools))
    }

    @Throws(Exception::class)
    fun callTool(session: Session, toolName: String, arguments: JSONObject): JSONObject {
        val response = request(session.server, session.sessionId, session.protocolVersion,
            "tools/call", JSONObject().put("name", toolName).put("arguments", arguments))
        return JSONObject()
            .put("server_id", session.server.id)
            .put("server_name", session.server.name)
            .put("tool_name", toolName)
            .put("result", response.result)
    }

    private data class RpcResponse(val result: JSONObject, val sessionId: String)

    @Throws(Exception::class)
    private fun request(server: McpServerStore.Server, sessionId: String?, protocolVersion: String,
                        method: String, params: JSONObject): RpcResponse {
        val id = UUID.randomUUID().toString()
        val payload = JSONObject().put("jsonrpc", "2.0").put("method", method).put("params", params)
            .put("id", id)
        val conn = (URL(server.endpoint).openConnection() as HttpURLConnection)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.doOutput = true
            conn.setRequestProperty("Accept", "application/json, text/event-stream")
            conn.setRequestProperty("MCP-Protocol-Version", protocolVersion)
            conn.setRequestProperty("Content-Type", "application/json")
            if (!sessionId.isNullOrEmpty()) conn.setRequestProperty("Mcp-Session-Id", sessionId)
            val bytes = payload.toString().toByteArray(StandardCharsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val returnedSession = conn.getHeaderField("Mcp-Session-Id") ?: sessionId ?: ""
            val raw = if (code >= 400) readLimited(conn.errorStream) else readLimited(conn.inputStream)
            if (code >= 400) throw IOException("MCP HTTP $code${diagnostic(raw)}")
            val value = parseRpcPayload(raw)
            if (value.has("error")) throw IOException("MCP 错误：" + rpcErrorMessage(value.opt("error")))
            val result = value.optJSONObject("result") ?: throw IOException("MCP 响应缺少 result")
            return RpcResponse(result, returnedSession)
        } finally {
            conn.disconnect()
        }
    }

    private fun terminateSessionBestEffort(server: McpServerStore.Server, sessionId: String,
                                           protocolVersion: String) {
        if (sessionId.isBlank()) return
        try {
            val conn = (URL(server.endpoint).openConnection() as HttpURLConnection)
            try {
                conn.requestMethod = "DELETE"
                conn.setRequestProperty("MCP-Protocol-Version", protocolVersion)
                conn.setRequestProperty("Mcp-Session-Id", sessionId)
                conn.responseCode
            } finally {
                conn.disconnect()
            }
        } catch (ignored: Throwable) {
        }
    }

    private fun rpcErrorMessage(error: Any?): String {
        val value = when (error) {
            is JSONObject -> error.optString("message", "未知错误")
            null, JSONObject.NULL -> "未知错误"
            else -> error.toString()
        }
        val cleaned = value.replace(Regex("[\\p{Cntrl}&&[^\\r\\n\\t]]"), " ").trim()
        return cleaned.ifEmpty { "未知错误" }.take(300)
    }

    private fun compactTools(tools: JSONArray): JSONArray {
        val compact = JSONArray()
        val count = minOf(tools.length(), 128)
        for (i in 0 until count) {
            val source = tools.optJSONObject(i) ?: continue
            val name = source.optString("name", "").trim()
            if (name.isEmpty() || name.length > 120) continue
            val item = JSONObject().put("name", name)
            val title = source.optString("title", "").trim()
            if (title.isNotEmpty()) item.put("title", title.take(200))
            val description = source.optString("description", "").trim()
            if (description.isNotEmpty()) {
                item.put("description", if (description.length <= 2000) description else description.take(2000) + "…")
            }
            val inputSchema = source.optJSONObject("inputSchema")
            if (inputSchema != null) {
                if (inputSchema.toString().length <= 12 * 1024) item.put("inputSchema", inputSchema)
                else item.put("inputSchema", JSONObject()
                    .put("type", "object")
                    .put("description", "该工具的输入结构过大，请根据工具说明谨慎调用"))
            }
            val annotations = source.optJSONObject("annotations")
            if (annotations != null) item.put("annotations", annotations)
            compact.put(item)
        }
        return compact
    }

    private fun parseRpcPayload(raw: String?): JSONObject {
        var value = raw?.trim() ?: ""
        // Streamable HTTP 可能返回 SSE 格式（data: 行）；取首个含 result/error 的行
        if (value.contains("\ndata:") || value.startsWith("data:")) {
            var fallback: JSONObject? = null
            for (line in value.split(Regex("\\r?\\n"))) {
                if (!line.startsWith("data:")) continue
                val data = line.substring(5).trim()
                if (data.isEmpty()) continue
                try {
                    val candidate = JSONObject(data)
                    if (candidate.has("result") || candidate.has("error")) return candidate
                    fallback = candidate
                } catch (ignored: Exception) {
                }
            }
            fallback?.let { return it }
            value = ""
        }
        if (value.isEmpty()) throw IOException("MCP 返回空响应")
        return JSONObject(value)
    }

    @Throws(IOException::class)
    private fun readLimited(stream: InputStream?): String {
        if (stream == null) return ""
        val output = ByteArrayOutputStream(8192)
        val buffer = ByteArray(8192)
        var total = 0L
        stream.use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_BODY_BYTES) throw IOException("MCP 响应过大")
                output.write(buffer, 0, count)
            }
        }
        return String(output.toByteArray(), StandardCharsets.UTF_8)
    }

    private fun diagnostic(value: String?): String {
        val text = value?.replace(Regex("[\\r\\n]+"), " ")?.trim() ?: ""
        return if (text.isEmpty()) "" else "：" + text.take(300)
    }
}
