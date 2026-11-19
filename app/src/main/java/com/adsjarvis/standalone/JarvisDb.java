package com.adsjarvis.standalone;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import com.adsjarvis.standalone.core.Memory;
import com.adsjarvis.standalone.core.MemoryRepo;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;

/**
 * App-private SQLite database (excluded from backup/transfer; protected by
 * Android file-based encryption). Holds memories, the audit log, task history
 * and scheduled reminders.
 */
public final class JarvisDb extends SQLiteOpenHelper {
    private static JarvisDb instance;

    public static synchronized JarvisDb get(Context c) {
        if (instance == null) instance = new JarvisDb(c.getApplicationContext());
        return instance;
    }

    private JarvisDb(Context c) { super(c, "jarvis.db", null, 1); }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE memory(id INTEGER PRIMARY KEY AUTOINCREMENT, category TEXT, mkey TEXT, text TEXT, source TEXT,"
            + " created INTEGER, updated INTEGER, uses INTEGER, pinned INTEGER, history TEXT, embedding BLOB)");
        db.execSQL("CREATE TABLE audit(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, tool TEXT, agent TEXT, tier TEXT,"
            + " args TEXT, approved INTEGER, ok INTEGER, summary TEXT)");
        db.execSQL("CREATE TABLE task(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, request TEXT, tools TEXT, outcome TEXT,"
            + " ok INTEGER, model TEXT, ms INTEGER)");
        db.execSQL("CREATE TABLE reminder(id INTEGER PRIMARY KEY AUTOINCREMENT, due INTEGER, text TEXT, done INTEGER)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) {}

    // ------------------------------------------------------------------ memory

    public final MemoryRepo memories = new MemoryRepo() {
        private ContentValues cv(Memory m) {
            ContentValues v = new ContentValues();
            v.put("category", m.category.name()); v.put("mkey", m.key); v.put("text", m.text); v.put("source", m.source);
            v.put("created", m.createdAt); v.put("updated", m.updatedAt); v.put("uses", m.uses); v.put("pinned", m.pinned ? 1 : 0);
            v.put("history", m.history.toString());
            if (m.embedding != null) {
                ByteBuffer b = ByteBuffer.allocate(m.embedding.length * 4);
                for (float f : m.embedding) b.putFloat(f);
                v.put("embedding", b.array());
            }
            return v;
        }
        private Memory read(Cursor c) {
            Memory m = new Memory();
            m.id = c.getLong(0);
            try { m.category = Memory.Category.valueOf(c.getString(1)); } catch (Exception e) { m.category = Memory.Category.KNOWLEDGE; }
            m.key = c.getString(2); m.text = c.getString(3); m.source = c.getString(4);
            m.createdAt = c.getLong(5); m.updatedAt = c.getLong(6); m.uses = c.getInt(7); m.pinned = c.getInt(8) == 1;
            try { m.history = new JSONArray(c.getString(9)); } catch (Exception e) { m.history = new JSONArray(); }
            byte[] e = c.getBlob(10);
            if (e != null) { ByteBuffer b = ByteBuffer.wrap(e); m.embedding = new float[e.length / 4]; for (int i = 0; i < m.embedding.length; i++) m.embedding[i] = b.getFloat(); }
            return m;
        }
        public long insert(Memory m) { m.id = getWritableDatabase().insert("memory", null, cv(m)); return m.id; }
        public void update(Memory m) { getWritableDatabase().update("memory", cv(m), "id=?", new String[]{"" + m.id}); }
        public void delete(long id) { getWritableDatabase().delete("memory", "id=?", new String[]{"" + id}); }
        public Memory get(long id) {
            try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM memory WHERE id=?", new String[]{"" + id})) {
                return c.moveToFirst() ? read(c) : null;
            }
        }
        public List<Memory> all() {
            List<Memory> out = new ArrayList<>();
            try (Cursor c = getReadableDatabase().rawQuery("SELECT * FROM memory ORDER BY updated DESC", null)) {
                while (c.moveToNext()) out.add(read(c));
            }
            return out;
        }
        public void clear() { getWritableDatabase().delete("memory", null, null); }
    };

    // ------------------------------------------------------------------ audit + tasks

    public void audit(String tool, String agent, String tier, String args, boolean approved, boolean ok, String summary) {
        ContentValues v = new ContentValues();
        v.put("ts", System.currentTimeMillis()); v.put("tool", tool); v.put("agent", agent); v.put("tier", tier);
        v.put("args", args); v.put("approved", approved ? 1 : 0); v.put("ok", ok ? 1 : 0); v.put("summary", summary);
        getWritableDatabase().insert("audit", null, v);
        getWritableDatabase().execSQL("DELETE FROM audit WHERE id NOT IN (SELECT id FROM audit ORDER BY id DESC LIMIT 2000)");
    }

    public void task(String request, String tools, String outcome, boolean ok, String model, long ms) {
        ContentValues v = new ContentValues();
        v.put("ts", System.currentTimeMillis()); v.put("request", request.length() > 300 ? request.substring(0, 300) : request);
        v.put("tools", tools); v.put("outcome", outcome.length() > 500 ? outcome.substring(0, 500) : outcome);
        v.put("ok", ok ? 1 : 0); v.put("model", model); v.put("ms", ms);
        getWritableDatabase().insert("task", null, v);
        getWritableDatabase().execSQL("DELETE FROM task WHERE id NOT IN (SELECT id FROM task ORDER BY id DESC LIMIT 1000)");
    }

    /** Rows as display lines, newest first. table = audit | task | reminder */
    public List<String> lines(String table, String where, int limit) {
        List<String> out = new ArrayList<>();
        java.text.DateFormat df = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT);
        String sql;
        switch (table) {
            case "audit": sql = "SELECT ts, tool, agent, tier, approved, ok, summary, args FROM audit"; break;
            case "task": sql = "SELECT ts, request, tools, ok, outcome, model, ms FROM task"; break;
            default: sql = "SELECT due, text, done, id FROM reminder";
        }
        if (where != null) sql += " WHERE " + where;
        sql += " ORDER BY " + (table.equals("reminder") ? "due ASC" : "id DESC") + " LIMIT " + limit;
        try (Cursor c = getReadableDatabase().rawQuery(sql, null)) {
            while (c.moveToNext()) {
                String when = df.format(new java.util.Date(c.getLong(0)));
                if (table.equals("audit"))
                    out.add(when + " · " + c.getString(1) + " [" + c.getString(3) + ", " + c.getString(2) + "] "
                        + (c.getInt(4) == 1 ? "approved" : "DENIED") + " → " + (c.getInt(5) == 1 ? "ok" : "failed") + ": " + c.getString(6));
                else if (table.equals("task"))
                    out.add(when + " · " + (c.getInt(3) == 1 ? "✓ " : "✗ ") + c.getString(1)
                        + (c.getString(2).isEmpty() ? "" : "\n   tools: " + c.getString(2)) + "\n   → " + c.getString(4)
                        + (c.getString(5).isEmpty() ? "" : "\n   model " + c.getString(5)) + " · " + c.getLong(6) + " ms");
                else out.add("#" + c.getLong(3) + " " + when + " · " + c.getString(1) + (c.getInt(2) == 1 ? " (done)" : ""));
            }
        }
        return out;
    }

    public long addReminder(long due, String text) {
        ContentValues v = new ContentValues();
        v.put("due", due); v.put("text", text); v.put("done", 0);
        return getWritableDatabase().insert("reminder", null, v);
    }

    public void markReminder(long id, boolean done) {
        ContentValues v = new ContentValues();
        v.put("done", done ? 1 : 0);
        getWritableDatabase().update("reminder", v, "id=?", new String[]{"" + id});
    }

    public void deleteReminder(long id) { getWritableDatabase().delete("reminder", "id=?", new String[]{"" + id}); }

    public int count(String table) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + table, null)) { return c.moveToFirst() ? c.getInt(0) : 0; }
    }

    public void wipeAll() {
        SQLiteDatabase db = getWritableDatabase();
        for (String t : new String[]{"memory", "audit", "task", "reminder"}) db.delete(t, null, null);
    }
}
