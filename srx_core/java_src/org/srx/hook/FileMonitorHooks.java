package org.srx.hook;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicBoolean;
import org.srx.monitor.MonitorEventBuffer;
import org.srx.monitor.MonitorProtocol;

/** MediaProvider/FUSE access events. Never hooks libc I/O or the system UI. */
public final class FileMonitorHooks extends Hooker {
  private static final String TAG = "SrxFileMonitor";
  private static final HashSet<Method> INSTALLED = new HashSet<>();
  private static volatile IBinder service;
  private static IBinder.DeathRecipient serviceDeath;
  private static volatile boolean enabled = true;
  private static Handler sender;
  private static int fuseHooks;
  private static final ThreadLocal<Boolean> INSIDE_PROVIDER = new ThreadLocal<Boolean>() {
    @Override
    protected Boolean initialValue() {
      return false;
    }
  };
  private static boolean transportErrorLogged;
  private static final MonitorEventBuffer<Event> EVENTS = new MonitorEventBuffer<>(8192);
  private static final AtomicBoolean DRAIN_SCHEDULED = new AtomicBoolean();
  private static final String SOURCE = Long.toString(System.nanoTime());
  private static long reportedLost;
  private static final class Event {
    final int uid, flags, status;
    final long time;
    final String writer, path, operation;
    Event(int uid, int flags, int status, String writer, String path, String operation) {
      this.uid = uid;
      this.flags = flags;
      this.status = status;
      this.writer = writer;
      this.path = path;
      this.operation = operation;
      time = System.currentTimeMillis();
    }
  }
  private final ContentProvider provider;
  private final Method method;
  private final String kind;
  private final Field statusField;
  private final Field uidField;

  private FileMonitorHooks(ContentProvider provider, Method method, String kind)
      throws ReflectiveOperationException {
    this.provider = provider;
    this.method = method;
    this.kind = kind;
    statusField =
        kind.equals("onFileOpenForFuse") ? method.getReturnType().getDeclaredField("status") : null;
    if (statusField != null)
      statusField.setAccessible(true);
    uidField =
        kind.equals("onFileOpenForFuse") ? method.getReturnType().getDeclaredField("uid") : null;
    if (uidField != null)
      uidField.setAccessible(true);
  }

  public static void setInitialEnabled(boolean value) {
    enabled = value;
    if (!value) EVENTS.clear();
  }

  private static synchronized Handler sender() {
    if (sender == null) {
      HandlerThread thread = new HandlerThread("srx-fuse-events");
      thread.start();
      sender = new Handler(thread.getLooper());
    }
    return sender;
  }

  public static synchronized void installOn(ContentProvider provider) {
    boolean media = false, downloads = false;
    for (Class<?> c = provider.getClass(); c != null; c = c.getSuperclass()) {
      media |= c.getName().equals("com.android.providers.media.MediaProvider")
          || c.getName().equals("com.android.providers.media.module.MediaProvider");
      downloads |= c.getName().equals("com.android.providers.downloads.DownloadProvider");
    }
    if (!media && !downloads)
      return;
    sender();
    try {
      Method call = provider.getClass().getMethod("call", String.class, String.class, Bundle.class);
      install(provider, call, "bridge");
      java.util.List<Method> fuseMethods = findFuseMethods(provider.getClass());
      for (Class<?> c = provider.getClass(); c != null && c != ContentProvider.class;
          c = c.getSuperclass()) {
        for (Method method : c.getDeclaredMethods()) {
          String name = method.getName();
          Class<?>[] p = method.getParameterTypes();
          if (media && fuseMethods.contains(method)) {
            if (install(provider, method, name))
              fuseHooks++;
          } else if ((name.equals("insert") || name.equals("update")) && p.length > 1
              && p[0] == Uri.class && p[1] == ContentValues.class) {
            install(provider, method, (media ? "media_" : "download_") + name);
          }
        }
      }
      if (media) {
        Class<?> daemon = Class.forName("com.android.providers.media.fuse.FuseDaemon", false,
            provider.getClass().getClassLoader());
        install(provider, daemon.getDeclaredMethod("run"), "fuse_ready");
      }
      Log.i(TAG,
          "provider observer installed: " + provider.getClass().getName()
              + ", FUSE entries=" + fuseHooks);
    } catch (ReflectiveOperationException error) {
      Log.w(TAG, "provider observer installation", error);
    }
  }

  public static boolean fuseSignature(Method method) {
    Class<?>[] p = method.getParameterTypes();
    if (p.length < 2 || p[0] != String.class)
      return false;
    if (method.getName().equals("onFileOpenForFuse")) {
      return p.length >= 3 && p[1] == String.class && p[2] == int.class
          && method.getReturnType().getSimpleName().equals("FileOpenResult");
    }
    return p[1] == int.class && method.getReturnType() == int.class;
  }

  public static java.util.List<Method> findFuseMethods(Class<?> type) {
    ArrayList<Method> methods = new ArrayList<>();
    HashSet<String> signatures = new HashSet<>();
    boolean modern = false;
    for (Class<?> c = type; c != null && c != ContentProvider.class; c = c.getSuperclass()) {
      for (Method method : c.getDeclaredMethods()) {
        String name = method.getName();
        if ((name.equals("onFileOpenForFuse") || name.equals("isOpenAllowedForFuse")
                || name.equals("insertFileIfNecessaryForFuse"))
            && fuseSignature(method)) {
          if (signatures.add(name + java.util.Arrays.toString(method.getParameterTypes()))) {
            methods.add(method);
            modern |= name.equals("onFileOpenForFuse");
          }
        }
      }
    }
    if (modern) {
      java.util.Iterator<Method> it = methods.iterator();
      while (it.hasNext())
        if (it.next().getName().equals("isOpenAllowedForFuse"))
          it.remove();
    }
    return methods;
  }

  private static boolean install(ContentProvider provider, Method method, String kind)
      throws ReflectiveOperationException {
    if (INSTALLED.contains(method))
      return false;
    FileMonitorHooks hooker = new FileMonitorHooks(provider, method, kind);
    if (!Hooker.installCallback(method, hooker, "monitorCallback"))
      return false;
    INSTALLED.add(method);
    return true;
  }

  public Object monitorCallback(Object[] args) throws Throwable {
    if (kind.equals("bridge"))
      return bridge(args);
    if (kind.equals("fuse_ready")) {
      Hooker.refreshProviderLibraries();
      return callBackup(args);
    }
    if (kind.startsWith("media_") || kind.startsWith("download_"))
      return providerOperation(args);
    if (!enabled)
      return callBackup(args);
    Object result;
    try {
      result = callBackup(args);
    } catch (Throwable original) {
      reportFuse(args, 13, -1);
      throw original;
    }
    try {
      reportFuse(args, result instanceof Integer ? (Integer) result : statusField.getInt(result),
          uidField == null ? -1 : uidField.getInt(result));
    } catch (ReflectiveOperationException | RuntimeException error) {
      Log.w(TAG, "observe FUSE access result", error);
    }
    return result;
  }

  private Object bridge(Object[] args) throws Throwable {
    String methodName = (String) args[1];
    if (!MonitorProtocol.ATTACH.equals(methodName)
        && !(MonitorProtocol.ATTACH + ".CONTROL").equals(methodName))
      return callBackup(args);
    if (Binder.getCallingUid() != 0)
      throw new SecurityException("Root monitor service required");
    Bundle extras = (Bundle) args[3];
    if (MonitorProtocol.ATTACH.equals(methodName)) {
      IBinder next = extras.getBinder("service");
      if (service != next) {
        if (service != null && serviceDeath != null)
          service.unlinkToDeath(serviceDeath, 0);
        serviceDeath = () -> {
          if (service == next)
            service = null;
        };
        next.linkToDeath(serviceDeath, 0);
        service = next;
      }
      enabled = extras.getBoolean("enabled", true);
      if (!enabled)
        EVENTS.clear();
      transportErrorLogged = false;
      requestDrain();
      Bundle result = new Bundle();
      result.putInt("version", 2);
      result.putInt("fuse_hooks", fuseHooks);
      return result;
    }
    IBinder target = service;
    if (target == null)
      throw new IllegalStateException("Monitor service is unavailable");
    Parcel data = Parcel.obtain(), reply = Parcel.obtain();
    try {
      data.writeInterfaceToken(MonitorProtocol.DESCRIPTOR);
      data.writeString(provider.getContext().getPackageName());
      boolean policy = "policy".equals(extras.getString("command"));
      target.transact(policy ? MonitorProtocol.RELOAD_POLICY : MonitorProtocol.CLEAR, data, reply, 0);
      reply.readException();
      if (policy) {
        setInitialEnabled(reply.readInt() != 0);
        requestDrain();
      }
      return Bundle.EMPTY;
    } finally {
      data.recycle();
      reply.recycle();
    }
  }

  public static int originatingUid(String kind, Object[] args, int returnedUid) {
    if (kind.equals("onFileOpenForFuse") && returnedUid >= 0)
      return returnedUid;
    return (Integer) args[kind.equals("onFileOpenForFuse") ? 3 : 2];
  }

  private void reportFuse(Object[] args, int status, int returnedUid) {
    int uid = originatingUid(kind, args, returnedUid);
    String path = (String) args[1];
    emit(uid, path, kind.equals("insertFileIfNecessaryForFuse") ? "creat" : "open", status);
  }

  private Object providerOperation(Object[] args) throws Throwable {
    int uid = Binder.getCallingUid();
    if (uid == android.os.Process.myUid() || !enabled || INSIDE_PROVIDER.get())
      return callBackup(args);
    INSIDE_PROVIDER.set(true);
    try {
      Uri uri = (Uri) args[1];
      ContentValues values = (ContentValues) args[2];
      ArrayList<String> before = new ArrayList<>();
      if (kind.endsWith("update"))
        before = paths(uri, args);
      Object result = callBackup(args);
      try {
        if (kind.endsWith("insert") && result instanceof Uri) {
          ArrayList<String> inserted = paths((Uri) result, null);
          if (inserted.isEmpty() && values != null) {
            String requested = values.getAsString("_data");
            if (requested == null && kind.equals("download_insert")) {
              String hint = values.getAsString("hint");
              if (hint != null && hint.startsWith("file://"))
                requested = Uri.parse(hint).getPath();
            }
            String scoped = MonitorProtocol.storagePath(requested);
            if (scoped != null)
              inserted.add(scoped);
          }
          for (String path : inserted) emit(uid, path, kind, 0);
        } else if (result instanceof Integer && ((Integer) result) > 0) {
          String destination = values == null ? null : values.getAsString("_data");
          for (String path : before) emit(uid, destination == null ? path : destination, kind, 0);
        }
      } catch (RuntimeException error) {
        Log.w(TAG, "observe provider access", error);
      }
      return result;
    } finally {
      INSIDE_PROVIDER.set(false);
    }
  }

  private ArrayList<String> paths(Uri uri, Object[] args) {
    ArrayList<String> paths = new ArrayList<>();
    String selection = null;
    String[] selectionArgs = null;
    if (args != null && args.length > 3) {
      if (args[3] instanceof Bundle) {
        Bundle b = (Bundle) args[3];
        selection = b.getString("android:query-arg-sql-selection");
        selectionArgs = b.getStringArray("android:query-arg-sql-selection-args");
      } else if (args[3] instanceof String) {
        selection = (String) args[3];
        if (args.length > 4)
          selectionArgs = (String[]) args[4];
      }
    }
    try (Cursor cursor =
             provider.query(uri, new String[] {"_data"}, selection, selectionArgs, null)) {
      if (cursor != null)
        while (cursor.moveToNext()) {
          String path = MonitorProtocol.storagePath(cursor.getString(0));
          if (path != null)
            paths.add(path);
        }
    } catch (RuntimeException error) {
      Log.w(TAG, "resolve provider access path", error);
    }
    return paths;
  }

  private void emit(int uid, String input, String operation, int status) {
    if (!enabled || uid < 0 || uid / 100000 != 0 || uid == 1000)
      return;
    String path = MonitorProtocol.storagePath(input);
    if (path == null) return;
    int flags = status == 0 ? MonitorProtocol.SUCCESS : MonitorProtocol.FAILED;
    if (Math.abs(status) == 13 || Math.abs(status) == 1)
      flags |= MonitorProtocol.BLOCKED;
    if (status == 17 || status == -17)
      flags = MonitorProtocol.EXISTS;
    if (operation.equals("creat") || operation.endsWith("insert"))
      flags |= MonitorProtocol.CREATE;
    final String writer = provider.getContext().getPackageName();
    EVENTS.offer(new Event(uid, flags, status, writer, path, operation));
    requestDrain();
  }

  private static void requestDrain() {
    if (service != null && DRAIN_SCHEDULED.compareAndSet(false, true))
      sender().post(FileMonitorHooks::drain);
  }

  private static void drain() {
    Event event;
    while (enabled && (event = EVENTS.peek()) != null) {
      IBinder target = service;
      if (target == null) {
        finishDrain();
        return;
      }
      Parcel data = Parcel.obtain();
      try {
        long lost = EVENTS.lost();
        if (lost != reportedLost) {
          Parcel status = Parcel.obtain();
          try {
            status.writeInterfaceToken(MonitorProtocol.DESCRIPTOR);
            status.writeString(event.writer);
            status.writeString(SOURCE);
            status.writeLong(lost);
            if (!target.transact(MonitorProtocol.DROPPED, status, null, IBinder.FLAG_ONEWAY))
              throw new RemoteException("Monitor loss report was not accepted");
            reportedLost = lost;
          } finally {
            status.recycle();
          }
        }
        data.writeInterfaceToken(MonitorProtocol.DESCRIPTOR);
        data.writeLong(event.time);
        data.writeInt(event.uid);
        data.writeInt(android.os.Process.myPid());
        data.writeString(event.writer);
        data.writeString(event.path);
        data.writeString(event.operation);
        data.writeInt(event.status);
        data.writeInt(event.flags);
        if (!target.transact(MonitorProtocol.EVENT, data, null, IBinder.FLAG_ONEWAY))
          throw new RemoteException("Monitor event was not accepted");
        EVENTS.delivered(event);
      } catch (RemoteException error) {
        if (!transportErrorLogged) {
          transportErrorLogged = true;
          Log.w(TAG, "send file access event", error);
        }
        // Keep the scheduled flag across retries so new events do not create
        // thousands of retry messages while a Binder channel is congested.
        sender().postDelayed(FileMonitorHooks::drain, 1000);
        return;
      } finally {
        data.recycle();
      }
    }
    finishDrain();
  }

  private static void finishDrain() {
    DRAIN_SCHEDULED.set(false);
    if (enabled && EVENTS.peek() != null)
      requestDrain();
  }
}
