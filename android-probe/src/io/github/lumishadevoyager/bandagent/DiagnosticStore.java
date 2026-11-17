package io.github.lumishadevoyager.bandagent;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.*;

/** Private durable outbox. Band ACK means stored here, not uploaded to the PC. */
public final class DiagnosticStore extends SQLiteOpenHelper {
    public DiagnosticStore(Context context) { super(context, "diagnostics.db", null, 1); }
    @Override public void onCreate(SQLiteDatabase db) { db.execSQL("CREATE TABLE events (id TEXT PRIMARY KEY, body TEXT NOT NULL, uploaded INTEGER NOT NULL DEFAULT 0, received INTEGER NOT NULL)"); }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { throw new IllegalStateException("unsupported_diagnostics_migration"); }
    public JSONArray accept(String entry, JSONArray events) throws Exception {
        if (events.length() > 3) throw new IllegalArgumentException("too_many_log_events");
        SQLiteDatabase db = getWritableDatabase(); JSONArray ack = new JSONArray(); db.beginTransaction();
        try {
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.getJSONObject(i);
                if (!entry.equals(event.getString("entryId")) || !event.getString("id").matches("[a-zA-Z0-9_]{1,80}") || !event.getString("code").matches("[a-z0-9_]{1,64}") || !event.getString("version").matches("[a-zA-Z0-9.+_-]{1,64}")) throw new IllegalArgumentException("invalid_diagnostic_identity");
                JSONObject details = new JSONObject(), source = event.optJSONObject("details");
                String[] allowed = { "state", "exportType", "start", "nestedStart", "canIUseRecord", "canIUseStart", "errorCode", "phase", "bytes", "sequence", "queueLength" };
                if (source != null) for (String key : allowed) { Object value = source.opt(key); if (value instanceof Boolean || value instanceof Number || value == JSONObject.NULL) details.put(key, value); else if (value instanceof String) details.put(key, ((String)value).substring(0, Math.min(100, ((String)value).length()))); }
                JSONObject safe = new JSONObject().put("id", event.getString("id")).put("entryId", entry).put("version", event.getString("version")).put("versionCode", event.getLong("versionCode")).put("timestamp", event.optLong("timestamp")).put("code", event.getString("code")).put("level", "error".equals(event.optString("level")) ? "error" : "info").put("details", details).put("source", "wearable");
                ContentValues values = new ContentValues(); values.put("id", safe.getString("id")); values.put("body", safe.toString()); values.put("received", System.currentTimeMillis());
                db.insertWithOnConflict("events", null, values, SQLiteDatabase.CONFLICT_IGNORE); ack.put(safe.getString("id"));
            }
            db.execSQL("DELETE FROM events WHERE uploaded=1 AND received < ?", new Object[] { System.currentTimeMillis() - 7L * 86400000 });
            try (Cursor count = db.rawQuery("SELECT COUNT(*) FROM events WHERE uploaded=0", null)) { count.moveToFirst(); if (count.getLong(0) > 2000) throw new IllegalStateException("diagnostic_outbox_full"); }
            db.setTransactionSuccessful(); return ack;
        } finally { db.endTransaction(); }
    }
    public JSONArray pending() throws Exception {
        JSONArray events = new JSONArray();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT body FROM events WHERE uploaded=0 ORDER BY received LIMIT 30", null)) { while (cursor.moveToNext()) events.put(new JSONObject(cursor.getString(0))); }
        return events;
    }
    public void uploaded(JSONArray ids) throws Exception { for (int i = 0; i < ids.length(); i++) { ContentValues values = new ContentValues(); values.put("uploaded", 1); getWritableDatabase().update("events", values, "id=?", new String[] { ids.getString(i) }); } }
    public void phone(String entry, long version, String code, JSONObject details, boolean error) throws Exception {
        JSONObject event = new JSONObject().put("id", "phone_" + java.util.UUID.randomUUID().toString().replace("-", "")).put("entryId", entry).put("version", "android_" + version).put("versionCode", version).put("timestamp", System.currentTimeMillis()).put("code", code).put("level", error ? "error" : "info").put("source", "phone").put("details", details);
        ContentValues values = new ContentValues(); values.put("id", event.getString("id")); values.put("body", event.toString()); values.put("received", System.currentTimeMillis());
        getWritableDatabase().insertOrThrow("events", null, values);
    }
}
