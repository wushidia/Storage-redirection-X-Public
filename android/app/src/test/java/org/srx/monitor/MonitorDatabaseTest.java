package org.srx.monitor;

import static org.junit.Assert.*;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class MonitorDatabaseTest {
  private File file;
  private MonitorDatabase store;
  @Before
  public void setup() {
    file = new File(
        RuntimeEnvironment.getApplication().getCacheDir(), "monitor-" + System.nanoTime() + ".db");
    store = new MonitorDatabase(file);
  }
  @After
  public void close() {
    store.close();
    SQLiteDatabase.deleteDatabase(file);
  }
  private MonitorRecord event(long time, String path) {
    return new MonitorRecord(
        time, 10234, 123, "com.android.providers.media.module", "example.app", path, "open", 0, 1);
  }
  private int count() {
    try (SQLiteDatabase db =
             SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
        Cursor c = db.rawQuery("SELECT count(*) FROM records", null)) {
      c.moveToFirst();
      return c.getInt(0);
    }
  }
  @Test
  public void everyRepeatedAccessIsRetainedAndNamesRemainExact() {
    String path = "/storage/emulated/0/文件|a\nb.txt";
    List<MonitorRecord> records = new ArrayList<>();
    for (int i = 0; i < 250; i++) records.add(event(1000 + i, path));
    store.append(records);
    assertEquals(250, count());
    assertEquals(250, store.latestId());
    try (SQLiteDatabase db =
             SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
        Cursor c =
            db.rawQuery("SELECT path,package FROM records ORDER BY id DESC LIMIT 100", null)) {
      assertEquals(100, c.getCount());
      c.moveToFirst();
      assertEquals(path, c.getString(0));
      assertEquals("example.app", c.getString(1));
    }
  }
  @Test
  public void retentionIsIndependentAndZeroMeansKeepUntilCleared() {
    long now = 2_000_000_000L;
    store.append(Arrays.asList(
        event(now - 800000, "/storage/emulated/0/old"), event(now, "/storage/emulated/0/new")));
    store.prune(now, 0);
    assertEquals(2, count());
    store.prune(now, 600);
    assertEquals(1, count());
    store.clear();
    assertEquals(0, count());
  }
  @Test
  public void rollbackDoesNotPublishHalfOfABatch() {
    List<MonitorRecord> invalid = Arrays.asList(event(1, "/storage/emulated/0/a"),
        new MonitorRecord(2, 10234, 123, "writer", null, "/storage/emulated/0/b", "open", 0, 1));
    try {
      store.append(invalid);
      fail("null actor must reject the transaction");
    } catch (IllegalArgumentException expected) {
    }
    assertEquals(0, count());
  }
  @Test
  public void onlyPrimaryEmulatedStorageAndAliasesPass() {
    for (String path : new String[] {
             "/storage/emulated/0/x", "/sdcard/x", "/storage/self/primary/x", "/data/media/0/x"})
      assertEquals("/storage/emulated/0/x", MonitorProtocol.storagePath(path));
    for (String path :
        new String[] {"/storage/emulated/00/x", "/storage/emulated/10/x", "/data/user/0/x",
            "/storage/AAAA/x", "/storage/emulated/0/../10/x", "storage/emulated/0/x"})
      assertNull(path, MonitorProtocol.storagePath(path));
    assertEquals("/storage/emulated/0/Android/data/example.app/x",
        MonitorProtocol.storagePath("/storage/emulated/0/Android/data/example.app/x"));
  }
  @Test
  public void systemUiAndFrameworkAreExcludedButOtherSystemAppsRemain() {
    assertFalse(MonitorProtocol.acceptsActor(10149, new String[] {"com.android.systemui"}));
    assertFalse(
        MonitorProtocol.acceptsActor(1000, new String[] {"android", "com.android.settings"}));
    assertFalse(MonitorProtocol.acceptsActor(1010149, new String[] {"example.app"}));
    assertTrue(MonitorProtocol.acceptsActor(1001, new String[] {"com.android.phone"}));
    assertTrue(MonitorProtocol.acceptsActor(10123, new String[] {"example.app"}));
  }
}
