package org.srx.monitor;

import android.os.IBinder;
import java.util.ArrayDeque;

/** File-access events, not per-byte read/write tracing. */
public final class MonitorProtocol {
  public static final String DESCRIPTOR = "com.storage.redirect.x.IFileMonitor";
  public static final String ATTACH = "com.storage.redirect.x.ATTACH_FILE_MONITOR";
  public static final int EVENT = IBinder.FIRST_CALL_TRANSACTION;
  public static final int CLEAR = IBinder.FIRST_CALL_TRANSACTION + 1;
  public static final int DROPPED = IBinder.FIRST_CALL_TRANSACTION + 2;
  public static final int RELOAD_POLICY = IBinder.FIRST_CALL_TRANSACTION + 3;
  public static final int SUCCESS = 1;
  public static final int FAILED = 2;
  public static final int CREATE = 4;
  public static final int BLOCKED = 8;
  public static final int EXISTS = 32;
  public static final long DEFAULT_RETENTION_SECONDS = 7 * 86400L;
  public static final long FLUSH_DELAY_MS = 5000L;
  public static final long PRUNE_INTERVAL_MS = 86400000L;
  private MonitorProtocol() {}

  public static String storagePath(String input) {
    if (input == null || !input.startsWith("/"))
      return null;
    String path = input;
    for (String alias : new String[] {"/sdcard", "/storage/self/primary", "/data/media/0",
             "/mnt/user/0/primary", "/mnt/runtime/default/emulated/0",
             "/mnt/runtime/read/emulated/0", "/mnt/runtime/write/emulated/0"}) {
      if (path.equals(alias) || path.startsWith(alias + "/")) {
        path = "/storage/emulated/0" + path.substring(alias.length());
        break;
      }
    }
    ArrayDeque<String> parts = new ArrayDeque<>();
    for (String part : path.split("/")) {
      if (part.isEmpty() || part.equals("."))
        continue;
      if (part.equals("..")) {
        if (!parts.isEmpty())
          parts.removeLast();
      } else
        parts.addLast(part);
    }
    String normalized = "/" + String.join("/", parts);
    return normalized.equals("/storage/emulated/0") || normalized.startsWith("/storage/emulated/0/")
        ? normalized
        : null;
  }

  public static boolean acceptsActor(int uid, String[] names) {
    if (uid < 0 || uid / 100000 != 0 || uid == 1000 || names.length == 0)
      return false;
    for (String name : names) {
      if ("android".equals(name) || "com.android.systemui".equals(name))
        return false;
    }
    return true;
  }

  public static boolean providerPackage(String name) {
    return "com.android.providers.media.module".equals(name)
        || "com.google.android.providers.media.module".equals(name)
        || "com.android.providers.media".equals(name)
        || "com.android.providers.downloads".equals(name);
  }
}
