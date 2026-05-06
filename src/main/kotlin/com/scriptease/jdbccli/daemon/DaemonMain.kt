package com.scriptease.jdbccli.daemon

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.eclipse.jetty.io.Content
import org.eclipse.jetty.server.Handler
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.unixdomain.server.UnixDomainServerConnector
import org.eclipse.jetty.util.Callback
import org.eclipse.jetty.util.thread.QueuedThreadPool
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

object DaemonMain {
    private val sockPath: Path = Path.of(System.getProperty("user.home"), ".jdbc-cli", "sock")

    fun run() {
        Files.createDirectories(sockPath.parent)
        Files.deleteIfExists(sockPath)

        val threadPool = QueuedThreadPool()
        val server = Server(threadPool)

        val connector = UnixDomainServerConnector(server)
        connector.unixDomainPath = sockPath
        server.addConnector(connector)

        server.handler = Router
        server.start()

        Files.setPosixFilePermissions(
            sockPath,
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        )

        Runtime.getRuntime().addShutdownHook(Thread {
            Pools.closeAll()
            server.stop()
        })

        System.err.println("jdbc-cli daemon started on $sockPath")
        server.join()
    }
}

@Serializable
private data class OpenReq(val alias: String, val jdbcUrl: String, val user: String = "", val password: String = "")

@Serializable
private data class CloseReq(val alias: String)

@Serializable
private data class QueryReq(val alias: String, val sql: String, val json: Boolean = false)

@Serializable
private data class ExecReq(val alias: String, val sql: String)

@Serializable
private data class SchemaReq(val alias: String)

@Serializable
private data class DescribeReq(val alias: String, val table: String)

@Serializable
private data class BeginReq(val alias: String)

@Serializable
private data class CommitReq(val alias: String)

@Serializable
private data class RollbackReq(val alias: String)

object Router : Handler.Abstract() {
    private val json = Json { ignoreUnknownKeys = true }

    override fun handle(request: Request, response: Response, callback: Callback): Boolean {
        val path = request.httpURI.path
        val method = request.method
        return when {
            method == "GET" && path == "/ping" -> {
                sendText(response, callback, "ok")
                true
            }
            method == "GET" && path == "/list" -> {
                val aliases = Pools.list()
                val body = aliases.joinToString(",", "[", "]") { "\"$it\"" }
                sendJson(response, callback, body)
                true
            }
            method == "POST" && path == "/open" -> {
                val body = Content.Source.asString(request, Charsets.UTF_8)
                val req = json.decodeFromString<OpenReq>(body)
                try {
                    Pools.open(req.alias, req.jdbcUrl, req.user, req.password)
                    sendJson(response, callback, """{"ok":true}""")
                } catch (e: Exception) {
                    sendError(response, callback, 400, e.message ?: "open failed")
                }
                true
            }
            method == "POST" && path == "/close" -> {
                val body = Content.Source.asString(request, Charsets.UTF_8)
                val req = json.decodeFromString<CloseReq>(body)
                try {
                    Pools.close(req.alias)
                    sendJson(response, callback, """{"ok":true}""")
                } catch (e: Exception) {
                    sendError(response, callback, 400, e.message ?: "close failed")
                }
                true
            }
            method == "POST" && path == "/query" -> {
                val body = Content.Source.asString(request, Charsets.UTF_8)
                val req = json.decodeFromString<QueryReq>(body)
                try {
                    val result = Pools.withConn(req.alias) { conn ->
                        conn.createStatement().use { stmt ->
                            stmt.executeQuery(req.sql).use { rs ->
                                if (req.json) ResultSets.toJson(rs) else ResultSets.toTsv(rs)
                            }
                        }
                    }
                    if (req.json) sendJson(response, callback, result)
                    else sendText(response, callback, result)
                } catch (e: Exception) {
                    sendError(response, callback, 400, e.message ?: "query failed")
                }
                true
            }
            method == "POST" && path == "/exec" -> {
                val body = Content.Source.asString(request, Charsets.UTF_8)
                val req = json.decodeFromString<ExecReq>(body)
                try {
                    val rows = Pools.withConn(req.alias) { conn ->
                        conn.createStatement().use { stmt -> stmt.executeUpdate(req.sql) }
                    }
                    sendJson(response, callback, """{"rowsAffected":$rows}""")
                } catch (e: Exception) {
                    sendError(response, callback, 400, e.message ?: "exec failed")
                }
                true
            }
            method == "POST" && path == "/schema" -> {
                val body = Content.Source.asString(request, Charsets.UTF_8)
                val req = json.decodeFromString<SchemaReq>(body)
                try {
                    val tables = Pools.withConn(req.alias) { conn ->
                        conn.metaData.getTables(null, null, "%", arrayOf("TABLE")).use { rs ->
                            val list = mutableListOf<String>()
                            while (rs.next()) list += "\"${rs.getString("TABLE_NAME").replace("\"", "\\\"")}\""
                            list
                        }
                    }
                    sendJson(response, callback, "[${tables.joinToString(",")}]")
                } catch (e: Exception) {
                    sendError(response, callback, 400, e.message ?: "schema failed")
                }
                true
            }
            method == "POST" && path == "/describe" -> {
                val body = Content.Source.asString(request, Charsets.UTF_8)
                val req = json.decodeFromString<DescribeReq>(body)
                try {
                    val cols = Pools.withConn(req.alias) { conn ->
                        conn.metaData.getColumns(null, null, req.table, "%").use { rs ->
                            val list = mutableListOf<String>()
                            while (rs.next()) {
                                val name = rs.getString("COLUMN_NAME").replace("\"", "\\\"")
                                val type = rs.getString("TYPE_NAME").replace("\"", "\\\"")
                                val size = rs.getInt("COLUMN_SIZE")
                                val nullable = rs.getInt("NULLABLE") != 0
                                list += """{"name":"$name","type":"$type","size":$size,"nullable":$nullable}"""
                            }
                            list
                        }
                    }
                    sendJson(response, callback, "[${cols.joinToString(",")}]")
                } catch (e: Exception) {
                    sendError(response, callback, 400, e.message ?: "describe failed")
                }
                true
            }
            method == "POST" && path == "/begin" -> {
                val body = Content.Source.asString(request, Charsets.UTF_8)
                val req = json.decodeFromString<BeginReq>(body)
                try {
                    Pools.begin(req.alias)
                    sendJson(response, callback, """{"ok":true}""")
                } catch (e: Exception) {
                    sendError(response, callback, 400, e.message ?: "begin failed")
                }
                true
            }
            method == "POST" && path == "/commit" -> {
                val body = Content.Source.asString(request, Charsets.UTF_8)
                val req = json.decodeFromString<CommitReq>(body)
                try {
                    Pools.commit(req.alias)
                    sendJson(response, callback, """{"ok":true}""")
                } catch (e: Exception) {
                    sendError(response, callback, 400, e.message ?: "commit failed")
                }
                true
            }
            method == "POST" && path == "/rollback" -> {
                val body = Content.Source.asString(request, Charsets.UTF_8)
                val req = json.decodeFromString<RollbackReq>(body)
                try {
                    Pools.rollback(req.alias)
                    sendJson(response, callback, """{"ok":true}""")
                } catch (e: Exception) {
                    sendError(response, callback, 400, e.message ?: "rollback failed")
                }
                true
            }
            else -> {
                sendError(response, callback, 404, "not found")
                true
            }
        }
    }

    fun sendText(response: Response, callback: Callback, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        response.status = 200
        response.headers.put("Content-Type", "text/plain; charset=utf-8")
        response.headers.put("Content-Length", bytes.size.toString())
        response.write(true, ByteBuffer.wrap(bytes), callback)
    }

    fun sendJson(response: Response, callback: Callback, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        response.status = 200
        response.headers.put("Content-Type", "application/json; charset=utf-8")
        response.headers.put("Content-Length", bytes.size.toString())
        response.write(true, ByteBuffer.wrap(bytes), callback)
    }

    fun sendError(response: Response, callback: Callback, status: Int, msg: String) {
        val escaped = msg.replace("\"", "\\\"")
        val bytes = """{"error":"$escaped"}""".toByteArray(Charsets.UTF_8)
        response.status = status
        response.headers.put("Content-Type", "application/json; charset=utf-8")
        response.headers.put("Content-Length", bytes.size.toString())
        response.write(true, ByteBuffer.wrap(bytes), callback)
    }
}
