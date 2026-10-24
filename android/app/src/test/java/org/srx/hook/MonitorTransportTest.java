package org.srx.hook;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.*;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import java.lang.reflect.*;
import java.time.Duration;
import java.util.ArrayList;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSystemClock;
import org.srx.monitor.MonitorEventBuffer;
import org.srx.monitor.MonitorProtocol;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class MonitorTransportTest {
  private static Field field(String name) throws Exception {
    Field field = FileMonitorHooks.class.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  public static final class Provider extends ContentProvider {
    public int isOpenAllowedForFuse(String path, int uid) { return 0; }
    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String order) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
  }

  @Test
  public void preconnectionEventsKeepTimesAndCongestedTransportHasOneRetry() throws Exception {
    Provider provider = new Provider();
    ProviderInfo info = new ProviderInfo();
    info.authority = "media";
    provider.attachInfo(new ContextWrapper(RuntimeEnvironment.getApplication()) {
      @Override public String getPackageName() { return "com.android.providers.media.module"; }
    }, info);
    Constructor<FileMonitorHooks> constructor = FileMonitorHooks.class.getDeclaredConstructor(
        ContentProvider.class, Method.class, String.class);
    constructor.setAccessible(true);
    FileMonitorHooks observer = constructor.newInstance(provider,
        Provider.class.getMethod("isOpenAllowedForFuse", String.class, int.class), "isOpenAllowedForFuse");
    Method emit = FileMonitorHooks.class.getDeclaredMethod("emit", int.class, String.class, String.class, int.class);
    emit.setAccessible(true);
    field("sender").set(null, new Handler(Looper.getMainLooper()));
    field("service").set(null, null);
    emit.invoke(observer, 10234, "/storage/emulated/0/boot-first.txt", "open", 0);
    emit.invoke(observer, 10234, "/storage/emulated/0/boot-second.txt", "open", 0);
    MonitorEventBuffer<?> queue = (MonitorEventBuffer<?>) field("EVENTS").get(null);
    Object first = queue.peek();
    Field time = first.getClass().getDeclaredField("time");
    time.setAccessible(true);
    long originalTime = time.getLong(first);
    ShadowSystemClock.advanceBy(Duration.ofSeconds(7));
    ArrayList<String> paths = new ArrayList<>();
    ArrayList<Long> times = new ArrayList<>();
    int[] attempts = {0};
    Binder service = new Binder() {
      @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        assertEquals(MonitorProtocol.EVENT, code);
        if (++attempts[0] == 1) return false;
        data.enforceInterface(MonitorProtocol.DESCRIPTOR);
        times.add(data.readLong());
        data.readInt(); data.readInt(); data.readString();
        paths.add(data.readString());
        return true;
      }
    };
    field("service").set(null, service);
    Method request = FileMonitorHooks.class.getDeclaredMethod("requestDrain");
    request.setAccessible(true);
    request.invoke(null);
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(1, attempts[0]);
    assertSame(first, queue.peek());
    emit.invoke(observer, 10234, "/storage/emulated/0/boot-third.txt", "open", 0);
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(1, attempts[0]);
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
    assertEquals(4, attempts[0]);
    assertEquals(java.util.Arrays.asList("/storage/emulated/0/boot-first.txt",
        "/storage/emulated/0/boot-second.txt", "/storage/emulated/0/boot-third.txt"), paths);
    assertEquals(originalTime, (long) times.get(0));
    assertNull(queue.peek());
    FileMonitorHooks.setInitialEnabled(false);
    emit.invoke(observer, 10234, "/storage/emulated/0/disabled.txt", "open", 0);
    shadowOf(Looper.getMainLooper()).idle();
    assertNull(queue.peek());
    assertEquals(4, attempts[0]);
    FileMonitorHooks.setInitialEnabled(true);
    emit.invoke(observer, 10234, "/storage/emulated/0/enabled-again.txt", "open", 0);
    shadowOf(Looper.getMainLooper()).idle();
    assertEquals(5, attempts[0]);
    assertEquals("/storage/emulated/0/enabled-again.txt", paths.get(paths.size() - 1));
    field("service").set(null, null);
  }
}
