package com.scriptease.jdbccli.daemon

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

object Pools {
    private val lock = ReentrantLock()
    private val pools = mutableMapOf<String, HikariDataSource>()
    private val txConns = mutableMapOf<String, java.sql.Connection>()

    fun list(): List<String> = lock.withLock { pools.keys.toList() }

    fun open(alias: String, jdbcUrl: String, user: String, password: String) = lock.withLock {
        if (pools.containsKey(alias)) error("alias '$alias' already open")
        val cfg = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            if (user.isNotEmpty()) username = user
            if (password.isNotEmpty()) this.password = password
            maximumPoolSize = 5
        }
        pools[alias] = HikariDataSource(cfg)
    }

    fun close(alias: String) = lock.withLock {
        if (txConns.containsKey(alias)) error("alias '$alias' has an active transaction — rollback first")
        val ds = pools.remove(alias) ?: error("alias '$alias' not found")
        ds.close()
    }

    fun begin(alias: String) = lock.withLock {
        if (txConns.containsKey(alias)) error("transaction already active for '$alias'")
        val ds = pools[alias] ?: error("alias '$alias' not open")
        val conn = ds.connection
        conn.autoCommit = false
        txConns[alias] = conn
    }

    fun commit(alias: String) = lock.withLock {
        val conn = txConns.remove(alias) ?: error("no active transaction for '$alias'")
        conn.commit()
        conn.close()
    }

    fun rollback(alias: String) = lock.withLock {
        val conn = txConns.remove(alias) ?: error("no active transaction for '$alias'")
        conn.rollback()
        conn.close()
    }

    fun closeAll() = lock.withLock {
        txConns.values.forEach { runCatching { it.rollback(); it.close() } }
        txConns.clear()
        pools.values.forEach { it.close() }
        pools.clear()
    }

    fun <T> withConn(alias: String, block: (java.sql.Connection) -> T): T {
        val txConn = lock.withLock { txConns[alias] }
        if (txConn != null) return block(txConn)
        val ds = lock.withLock { pools[alias] ?: error("alias '$alias' not open") }
        return ds.connection.use(block)
    }
}
