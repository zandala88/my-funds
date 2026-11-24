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
            sendStatus(1, Bundle().apply { putString("class", javaClass.name); putString("test", name); putInt("numtests", 7); putInt("current", count + 1) })
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
            test("noOpImportPreservesEarlierRecoveryPoint") {
                LocalStore(context, database).use { store ->
                    store.save(first)
                    val changed = first.copy(settings = AppSettings(darkMode = true))
                    store.replaceWithBackup(BackupCodec.encode(changed))
                    check(!store.replaceWithBackup(BackupCodec.encode(changed)))
                    check(store.restorePrevious()); check(store.load() == first)
                }
            }
            test("offlineNativeEngineReadsSyntheticImage") {
                val image = java.io.File.createTempFile("ocr-fixture-", ".png", context.cacheDir)
                try {
                    this@LocalStoreInstrumentation.context.assets.open("alipay-synthetic.png").use { input -> image.outputStream().use { input.copyTo(it) } }
                    val lines = OfflineOcr(context).recognize(android.net.Uri.fromFile(image), java.util.concurrent.atomic.AtomicBoolean(false)) { }
                    val parsed = HoldingsOcr.parse(lines)
                    check(parsed.any { it.code == "000001" }); check(parsed.any { it.code == "161725" })
                    val shares = parsed.first { it.code == "000001" }.shares
                    check(shares == null || shares.compareTo(BigDecimal("1234.5678")) == 0)
                } finally { image.delete() }
            }
            finish(-1, Bundle().apply { putString("stream", "\nOK ($count tests)\n") })
        } catch (t: Throwable) {
            finish(1, Bundle().apply { putString("stream", "FAIL after $count tests: ${t.stackTraceToString()}") })
        } finally { context.deleteDatabase(database) }
    }
}
