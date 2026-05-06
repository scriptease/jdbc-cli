package com.scriptease.jdbccli.client

object ClientMain {
    fun run(args: Array<String>) {
        if (args.isEmpty()) {
            System.err.println("""{"error":"no subcommand given"}""")
            System.exit(1)
        }
        when (args[0]) {
            "ping" -> println(HttpClient.get("/ping"))
            "list" -> println(HttpClient.get("/list"))
            "open" -> {
                val p = parseFlags(args, 1)
                val alias = p["alias"] ?: die("--alias required")
                val jdbcUrl = p["jdbc-url"] ?: die("--jdbc-url required")
                val user = p["user"] ?: ""
                val keychainRef = p["password-keychain"]
                val password = when {
                    keychainRef != null -> ""  // daemon will resolve from keychain
                    p.containsKey("password-stdin") -> readLine() ?: ""
                    else -> ""
                }
                val body = buildJsonOpen(alias, jdbcUrl, user, password, keychainRef)
                println(HttpClient.post("/open", body))
            }
            "close" -> {
                val p = parseFlags(args, 1)
                val alias = p["alias"] ?: die("--alias required")
                println(HttpClient.post("/close", """{"alias":"$alias"}"""))
            }
            "query" -> {
                val p = parseFlags(args, 1)
                val alias = p["alias"] ?: die("--alias required")
                val asJson = p.containsKey("json")
                val sql = parsePositional(args, 1) ?: die("SQL argument required")
                fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
                val body = """{"alias":"${esc(alias)}","sql":"${esc(sql)}","json":$asJson}"""
                println(HttpClient.post("/query", body))
            }
            "exec" -> {
                val p = parseFlags(args, 1)
                val alias = p["alias"] ?: die("--alias required")
                val sql = parsePositional(args, 1) ?: die("SQL argument required")
                fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
                val body = """{"alias":"${esc(alias)}","sql":"${esc(sql)}"}"""
                println(HttpClient.post("/exec", body))
            }
            "schema" -> {
                val p = parseFlags(args, 1)
                val alias = p["alias"] ?: die("--alias required")
                fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
                println(HttpClient.post("/schema", """{"alias":"${esc(alias)}"}"""))
            }
            "describe" -> {
                val p = parseFlags(args, 1)
                val alias = p["alias"] ?: die("--alias required")
                val table = p["table"] ?: die("--table required")
                fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
                println(HttpClient.post("/describe", """{"alias":"${esc(alias)}","table":"${esc(table)}"}"""))
            }
            "begin" -> {
                val p = parseFlags(args, 1)
                val alias = p["alias"] ?: die("--alias required")
                fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
                println(HttpClient.post("/begin", """{"alias":"${esc(alias)}"}"""))
            }
            "commit" -> {
                val p = parseFlags(args, 1)
                val alias = p["alias"] ?: die("--alias required")
                fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
                println(HttpClient.post("/commit", """{"alias":"${esc(alias)}"}"""))
            }
            "rollback" -> {
                val p = parseFlags(args, 1)
                val alias = p["alias"] ?: die("--alias required")
                fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
                println(HttpClient.post("/rollback", """{"alias":"${esc(alias)}"}"""))
            }
            "batch" -> {
                val p = parseFlags(args, 1)
                val defaultAlias = p["alias"]
                val lines = generateSequence(::readLine).toList()
                val body = if (defaultAlias == null) {
                    lines.joinToString("\n")
                } else {
                    fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
                    lines.joinToString("\n") { line ->
                        val trimmed = line.trim()
                        if (trimmed.startsWith("{") && !trimmed.contains(""""alias"""")) {
                            """{"alias":"${esc(defaultAlias)}",${ trimmed.removePrefix("{") }"""
                        } else trimmed
                    }
                }
                println(HttpClient.post("/batch", body))
            }
            else -> {
                System.err.println("""{"error":"unknown subcommand: ${args[0]}"}""")
                System.exit(1)
            }
        }
    }

    private data class ParsedArgs(val flags: Map<String, String>, val positionals: List<String>)

    // Flags in this set never consume the next token as their value.
    private val BOOL_FLAGS = setOf("json", "password-stdin")

    private fun parse(args: Array<String>, start: Int): ParsedArgs {
        val flags = mutableMapOf<String, String>()
        val positionals = mutableListOf<String>()
        var i = start
        while (i < args.size) {
            val arg = args[i]
            if (arg.startsWith("--")) {
                val key = arg.removePrefix("--")
                if (key in BOOL_FLAGS || i + 1 >= args.size || args[i + 1].startsWith("--")) {
                    flags[key] = ""; i++
                } else {
                    flags[key] = args[i + 1]; i += 2
                }
            } else {
                positionals += arg; i++
            }
        }
        return ParsedArgs(flags, positionals)
    }

    // Kept for backward-compat with existing call sites.
    private fun parseFlags(args: Array<String>, start: Int) = parse(args, start).flags
    private fun parsePositional(args: Array<String>, start: Int) = parse(args, start).positionals.firstOrNull()

    private fun buildJsonOpen(alias: String, jdbcUrl: String, user: String, password: String, keychainRef: String? = null): String {
        fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
        val kc = if (keychainRef != null) ""","passwordKeychain":"${esc(keychainRef)}"""" else ""
        return """{"alias":"${esc(alias)}","jdbcUrl":"${esc(jdbcUrl)}","user":"${esc(user)}","password":"${esc(password)}"$kc}"""
    }

    private fun die(msg: String): Nothing {
        System.err.println("""{"error":"$msg"}""")
        System.exit(1)
        error("unreachable")
    }
}
