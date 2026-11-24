package com.myfunds.android

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.myfunds.core.*

class LocalStore(context: Context, name: String = "portfolio.db") : SQLiteOpenHelper(context, name, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE state (key TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE quotes (code TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE cache (key TEXT PRIMARY KEY, payload TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("数据库版本不兼容，原数据未删除") }
    private fun read(table: String, keyColumn: String, key: String): String? {
        val db = readableDatabase
        val size = db.rawQuery("SELECT length(CAST(payload AS BLOB)) FROM $table WHERE $keyColumn=?", arrayOf(key)).use { if (it.moveToFirst()) it.getInt(0) else return null }
        return decodeUtf8Strict(readChunked(size) { offset, length ->
            db.rawQuery("SELECT substr(CAST(payload AS BLOB),?,?) FROM $table WHERE $keyColumn=?", arrayOf((offset + 1).toString(), length.toString(), key)).use {
                check(it.moveToFirst()) { "本机数据读取失败" }; it.getBlob(0)
            }
        })
    }
    private fun write(db: SQLiteDatabase, table: String, keyColumn: String, key: String, payload: String) {
        db.insertWithOnConflict(table, null, ContentValues().apply { put(keyColumn, key); put("payload", payload) }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "本机存储写入失败" } }
    }
    private fun transaction(action: (SQLiteDatabase) -> Unit) {
        val db = writableDatabase; db.beginTransaction()
        try { action(db); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    fun load(): Portfolio = read("state", "key", "portfolio")?.let(BackupCodec::decode) ?: Portfolio()
    fun save(p: Portfolio) { val raw = BackupCodec.encode(p); transaction { write(it, "state", "key", "portfolio", raw) } }
    fun replaceWithBackup(raw: String) = replacePreparedBackup(BackupCodec.prepare(raw))
    fun replacePreparedBackup(prepared: PreparedBackup): Boolean {
        var changed = false
        transaction { db ->
            if (load() == prepared.portfolio) return@transaction
            val count = db.compileStatement("INSERT OR REPLACE INTO state(key,payload) SELECT 'before-import',payload FROM state WHERE key='portfolio'").executeUpdateDelete()
            if (count == 0) write(db, "state", "key", "before-import", BackupCodec.encode(Portfolio()))
            write(db, "state", "key", "portfolio", prepared.encoded)
            changed = true
        }
        return changed
    }
    fun restorePrevious(): Boolean {
        val previous = read("state", "key", "before-import") ?: return false
        val validated = BackupCodec.encode(BackupCodec.decode(previous))
        transaction { write(it, "state", "key", "portfolio", validated) }
        return true
    }
    fun saveQuotes(quotes: List<FundQuote>) {
        val merged = MarketCache.mergeQuotes(loadQuotes(), quotes)
        val codes = quotes.map { it.code }.toSet()
        transaction { db -> merged.values.filter { it.code in codes }.forEach { q ->
                write(db, "quotes", "code", q.code, MarketParser.encodeQuotes(listOf(q)))
        } }
    }
    fun loadQuotes(): Map<String, FundQuote> = buildMap {
        readableDatabase.query("quotes", arrayOf("payload"), null, null, null, null, null).use { cursor ->
            while (cursor.moveToNext()) runCatching { MarketParser.quotes(cursor.getString(0)) }.getOrNull()?.forEach { put(it.code, it) }
        }
    }
    fun cache(key: String): String? = read("cache", "key", key)
    fun putCache(key: String, value: String) { require(value.length <= 2 * 1024 * 1024); transaction { write(it, "cache", "key", key, value) } }
    fun clearCache() { transaction { it.delete("quotes", null, null); it.delete("cache", null, null) } }
}
