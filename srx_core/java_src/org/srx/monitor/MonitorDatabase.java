package org.srx.monitor;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import java.io.File;
import java.util.List;

/** One writer thread, one transaction per batch, matching the reference's store. */
public final class MonitorDatabase implements AutoCloseable {
  private final SQLiteDatabase database;
  private long nextPrune;
  private long lastRetention = Long.MIN_VALUE;

  public MonitorDatabase(File file) {
    file.getParentFile().mkdirs();
    SQLiteDatabase.OpenParams params =
        new SQLiteDatabase.OpenParams.Builder()
            .addOpenFlags(SQLiteDatabase.CREATE_IF_NECESSARY | SQLiteDatabase.NO_LOCALIZED_COLLATORS
                | SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING)
            .setSynchronousMode("NORMAL")
            .build();
    database = SQLiteDatabase.openDatabase(file, params);
    database.execSQL("CREATE TABLE IF NOT EXISTS records (id INTEGER PRIMARY KEY AUTOINCREMENT,"
        + " timestamp BIGINT NOT NULL, uid INTEGER NOT NULL, pid INTEGER NOT NULL,"
        + " writer TEXT NOT NULL, package TEXT NOT NULL, path TEXT NOT NULL,"
        + " func TEXT NOT NULL, status INTEGER NOT NULL, flags INTEGER NOT NULL)");
    database.execSQL("CREATE INDEX IF NOT EXISTS records_timestamp ON records(timestamp)");
  }

  public void append(List<MonitorRecord> records) {
    if (records.isEmpty())
      return;
    database.beginTransaction();
    try (SQLiteStatement insert = database.compileStatement(
             "INSERT INTO records(timestamp,uid,pid,writer,package,path,func,status,flags)"
             + " VALUES(?,?,?,?,?,?,?,?,?)")) {
      for (MonitorRecord record : records) {
        insert.clearBindings();
        insert.bindLong(1, record.timestamp);
        insert.bindLong(2, record.uid);
        insert.bindLong(3, record.pid);
        insert.bindString(4, record.writer);
        insert.bindString(5, record.actor);
        insert.bindString(6, record.path);
        insert.bindString(7, record.operation);
        insert.bindLong(8, record.status);
        insert.bindLong(9, record.flags);
        insert.executeInsert();
      }
      database.setTransactionSuccessful();
    } finally {
      database.endTransaction();
    }
  }

  public void prune(long now, long retentionSeconds) {
    if (lastRetention != retentionSeconds) {
      nextPrune = 0;
      lastRetention = retentionSeconds;
    }
    if (now < nextPrune)
      return;
    if (retentionSeconds > 0)
      database.delete(
          "records", "timestamp < ?", new String[] {Long.toString(now - retentionSeconds * 1000)});
    nextPrune = now + MonitorProtocol.PRUNE_INTERVAL_MS;
  }

  public void clear() {
    database.delete("records", null, null);
  }
  public long latestId() {
    try (Cursor c = database.rawQuery("SELECT COALESCE(MAX(id),0) FROM records", null)) {
      return c.moveToFirst() ? c.getLong(0) : 0;
    }
  }
  @Override
  public void close() {
    database.close();
  }
}
