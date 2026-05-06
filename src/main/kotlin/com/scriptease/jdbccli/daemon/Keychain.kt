package com.scriptease.jdbccli.daemon

object Keychain {
    fun lookup(serviceAccount: String): String {
        val slash = serviceAccount.indexOf('/')
        val service = if (slash >= 0) serviceAccount.substring(0, slash) else serviceAccount
        val account = if (slash >= 0) serviceAccount.substring(slash + 1) else ""

        val cmd = buildList {
            add("security"); add("find-generic-password")
            add("-s"); add(service)
            if (account.isNotEmpty()) { add("-a"); add(account) }
            add("-w")
        }
        val proc = ProcessBuilder(cmd).redirectErrorStream(false).start()
        val raw = proc.inputStream.bufferedReader().readText()
        val exitCode = proc.waitFor()
        if (exitCode != 0) error("security find-generic-password failed (exit $exitCode) for service '$service'")
        return raw.trimEnd('\n')
    }
}
