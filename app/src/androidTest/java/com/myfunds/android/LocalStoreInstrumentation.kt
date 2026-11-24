package com.myfunds.android

import android.app.Instrumentation
import android.os.Bundle
import com.myfunds.core.*
import java.math.BigDecimal

/** Zero-dependency instrumentation runner; execute with adb shell am instrument -w. */
class LocalStoreInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val context = targetContext
        val database = "instrumentation-portfolio.db"
        context.deleteDatabase(database)
        var count = 0
        fun test(name: String, action: () -> Unit) {
            sendStatus(1, Bundle().apply { putString("class", javaClass.name); putString("test", name); putInt("numtests", 5); putInt("current", count + 1) })
            action(); count++
            sendStatus(0, Bundle().apply { putString("class", javaClass.name); putString("test", name) })
        }
        try {
            val first = Portfolio(holdings = listOf(Holding("test-h", "000001", "测试基金", "default", BigDecimal("12.3456"), BigDecimal("1.20"))))
            test("persistsAcrossReopen") {
                LocalStore(context, database).use { it.save(first) }
                LocalStore(context, database).use { check(it.load() == first) }
            }
            test("invalidImportPreservesCurrent") {
                LocalStore(context, database).use { store ->
                    try { store.replaceWithBackup("bad JSON"); error("accepted invalid backup") } catch (_: IllegalArgumentException) { }
                    check(store.load() == first)
                }
            }
            test("repeatImportAndUndo") {
                LocalStore(context, database).use { store ->
                    val next = first.copy(settings = AppSettings(darkMode = true))
                    store.replaceWithBackup(BackupCodec.encode(next))
                    store.replaceWithBackup(BackupCodec.encode(next))
                    check(store.load() == next)
                    store.save(first)
                    store.replaceWithBackup(BackupCodec.encode(next))
                    check(store.restorePrevious())
                    check(store.load() == first)
                }
            }
            test("quoteCachePersists") {
                val quote = FundQuote("000001", "测试基金", BigDecimal("1.2500"), "2026-11-23")
                LocalStore(context, database).use { it.saveQuotes(listOf(quote)) }
                LocalStore(context, database).use { check(it.loadQuotes()["000001"] == quote) }
            }
            test("largePortfolioAndRecoveryAvoidCursorWindowLimit") {
                val big = first.copy(trades = List(19000) { i -> Trade("large-$i", "test-h", "CALIBRATE", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, "2026-11-24") })
                val raw = BackupCodec.encode(big)
                check(raw.toByteArray().size > 2 * 1024 * 1024)
                LocalStore(context, database).use { it.replaceWithBackup(raw) }
                LocalStore(context, database).use {
                    check(it.load() == big)
                    it.replaceWithBackup(BackupCodec.encode(first))
                    check(it.restorePrevious()); check(it.load() == big)
                }
            }
            finish(-1, Bundle().apply { putString("stream", "\nOK ($count tests)\n") })
        } catch (t: Throwable) {
            finish(1, Bundle().apply { putString("stream", "FAIL after $count tests: ${t.stackTraceToString()}") })
        } finally { context.deleteDatabase(database) }
    }
}
