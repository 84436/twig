package app.twig.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class NodeRow(val id: Long, val parent: Long, val rank: Double, val text: String, val url: String?, val done: Boolean)

/** v4 starts a fresh file, so the v3 outline in twig.db stays untouched. */
class Db(context: Context, name: String?) : SQLiteOpenHelper(context, name, null, 1) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE nodes (id INTEGER PRIMARY KEY, parent INTEGER NOT NULL, rank REAL NOT NULL, " +
                "text TEXT NOT NULL, url TEXT, done INTEGER NOT NULL)"
        )
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun loadAll(): List<NodeRow> = readableDatabase.rawQuery("SELECT id,parent,rank,text,url,done FROM nodes", null).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(NodeRow(c.getLong(0), c.getLong(1), c.getDouble(2), c.getString(3) ?: "", c.getString(4), c.getInt(5) != 0))
            }
        }
    }

    fun loadMeta(): Map<String, String> = readableDatabase.rawQuery("SELECT k,v FROM meta", null).use { c ->
        buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
    }

    fun write(upserts: List<NodeRow>, deletes: Collection<Long>, meta: Map<String, String>) {
        if (upserts.isEmpty() && deletes.isEmpty() && meta.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.compileStatement("INSERT OR REPLACE INTO nodes (id,parent,rank,text,url,done) VALUES (?,?,?,?,?,?)").use { st ->
                for (r in upserts) {
                    st.clearBindings()
                    st.bindLong(1, r.id)
                    st.bindLong(2, r.parent)
                    st.bindDouble(3, r.rank)
                    st.bindString(4, r.text)
                    if (r.url != null) st.bindString(5, r.url)
                    st.bindLong(6, if (r.done) 1 else 0)
                    st.executeInsert()
                }
            }
            db.compileStatement("DELETE FROM nodes WHERE id=?").use { st ->
                for (id in deletes) {
                    st.bindLong(1, id)
                    st.executeUpdateDelete()
                }
            }
            for ((k, v) in meta) db.execSQL("INSERT OR REPLACE INTO meta (k,v) VALUES (?,?)", arrayOf(k, v))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
