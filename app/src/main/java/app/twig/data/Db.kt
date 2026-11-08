package app.twig.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class Db(context: Context, name: String? = "twig.db") : SQLiteOpenHelper(context, name, null, 1) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE nodes (" +
                "id INTEGER PRIMARY KEY, parent INTEGER NOT NULL, rank REAL NOT NULL, text TEXT NOT NULL, " +
                "url TEXT, note TEXT, kind INTEGER NOT NULL, done INTEGER NOT NULL, collapsed INTEGER NOT NULL, " +
                "created INTEGER NOT NULL, modified INTEGER NOT NULL, attach TEXT)"
        )
        db.execSQL("CREATE TABLE meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun loadAll(): List<NodeRow> {
        val out = ArrayList<NodeRow>()
        readableDatabase.rawQuery(
            "SELECT id,parent,rank,text,url,note,kind,done,collapsed,created,modified,attach FROM nodes", null
        ).use { c ->
            while (c.moveToNext()) {
                out += NodeRow(
                    id = c.getLong(0),
                    parent = c.getLong(1),
                    rank = c.getDouble(2),
                    text = c.getString(3) ?: "",
                    url = c.str(4),
                    note = c.str(5),
                    kind = c.getInt(6),
                    done = c.getInt(7) != 0,
                    collapsed = c.getInt(8) != 0,
                    created = c.getLong(9),
                    modified = c.getLong(10),
                    attach = c.str(11),
                )
            }
        }
        return out
    }

    fun loadMeta(): Map<String, String> {
        val out = HashMap<String, String>()
        readableDatabase.rawQuery("SELECT k,v FROM meta", null).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getString(1)
        }
        return out
    }

    fun write(upserts: List<NodeRow>, deletes: Collection<Long>, meta: Map<String, String>) {
        if (upserts.isEmpty() && deletes.isEmpty() && meta.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (upserts.isNotEmpty()) {
                val st = db.compileStatement(
                    "INSERT OR REPLACE INTO nodes (id,parent,rank,text,url,note,kind,done,collapsed,created,modified,attach) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"
                )
                for (r in upserts) {
                    st.clearBindings()
                    st.bindLong(1, r.id)
                    st.bindLong(2, r.parent)
                    st.bindDouble(3, r.rank)
                    st.bindString(4, r.text)
                    if (r.url != null) st.bindString(5, r.url) else st.bindNull(5)
                    if (r.note != null) st.bindString(6, r.note) else st.bindNull(6)
                    st.bindLong(7, r.kind.toLong())
                    st.bindLong(8, if (r.done) 1 else 0)
                    st.bindLong(9, if (r.collapsed) 1 else 0)
                    st.bindLong(10, r.created)
                    st.bindLong(11, r.modified)
                    if (r.attach != null) st.bindString(12, r.attach) else st.bindNull(12)
                    st.executeInsert()
                }
                st.close()
            }
            if (deletes.isNotEmpty()) {
                val st = db.compileStatement("DELETE FROM nodes WHERE id=?")
                for (id in deletes) {
                    st.bindLong(1, id)
                    st.executeUpdateDelete()
                }
                st.close()
            }
            for ((k, v) in meta) {
                db.execSQL("INSERT OR REPLACE INTO meta (k,v) VALUES (?,?)", arrayOf(k, v))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun Cursor.str(i: Int): String? = if (isNull(i)) null else getString(i)
}
