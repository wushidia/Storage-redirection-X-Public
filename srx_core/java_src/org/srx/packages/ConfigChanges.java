package org.srx.packages;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONObject;

// 配置事件统一按用户比较规则，同一共享 UID 每批只停止一次。
final class ConfigChanges {
  private ConfigChanges() {}

  static void sync(File config, File runtime, boolean baseline) throws Exception {
    Files.createDirectories(runtime.toPath());
    android.system.Os.chmod(runtime.getPath(), 0700);
    File previous = new File(runtime, "config_snapshots");
    Files.createDirectories(previous.toPath());
    try (RandomAccessFile file = new RandomAccessFile(new File(previous, ".lock"), "rw")) {
      FileLock lock = file.getChannel().lock();
      try {
        File ready = new File(previous, ".ready");
        syncLocked(new File(config, "apps"), previous, baseline || !ready.exists());
        if (!ready.exists()) Files.write(ready.toPath(), new byte[0]);
      } finally {
        lock.release();
      }
    }
  }

  private static void syncLocked(File apps, File previous, boolean baseline) throws Exception {
    Set<String> names = new TreeSet<>();
    collectNames(apps, names);
    collectNames(previous, names);
    RootPackageApi api = baseline ? null : new RootPackageApi();
    Set<Integer> stoppedUids = new HashSet<>();
    for (String name : names) {
      File source = new File(apps, name);
      File cached = new File(previous, name);
      JSONObject current = read(source);
      JSONObject before = read(cached);
      if (current.toString().equals(before.toString())) continue;
      if (!baseline) {
        stopChangedUsers(name.substring(0, name.length() - 5), before, current, api, stoppedUids);
      }
      if (source.exists()) {
        ConfigProfileStore.writeAtomic(cached,
            (current.toString() + "\n").getBytes(StandardCharsets.UTF_8), false);
      } else {
        Files.deleteIfExists(cached.toPath());
      }
    }
  }

  private static void collectNames(File directory, Set<String> names) {
    File[] files = directory.listFiles((parent, name) -> name.endsWith(".json"));
    if (files == null) return;
    for (File file : files) names.add(file.getName());
  }

  private static JSONObject read(File file) throws Exception {
    return file.exists()
        ? new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8))
        : new JSONObject();
  }

  private static void stopChangedUsers(String name, JSONObject before, JSONObject current,
      RootPackageApi api, Set<Integer> stoppedUids) throws Exception {
    if (name.equals("com.storage.redirect.x") || name.equals("com.storage.redirect.x.debug")
        || name.equals("com.android.providers.media")
        || name.equals("com.android.providers.media.module")
        || name.equals("com.google.android.providers.media.module")
        || name.equals("com.android.providers.downloads")) return;
    JSONObject oldUsers = before.optJSONObject("users");
    JSONObject newUsers = current.optJSONObject("users");
    Set<String> users = new TreeSet<>();
    if (oldUsers != null) oldUsers.keys().forEachRemaining(users::add);
    if (newUsers != null) newUsers.keys().forEachRemaining(users::add);
    for (String key : users) {
      JSONObject oldProfile = oldUsers == null ? null : oldUsers.optJSONObject(key);
      JSONObject newProfile = newUsers == null ? null : newUsers.optJSONObject(key);
      if (String.valueOf(oldProfile).equals(String.valueOf(newProfile))) continue;
      if (!isEnabled(oldProfile) && !isEnabled(newProfile)) continue;
      int userId = Integer.parseInt(key);
      android.content.pm.PackageInfo info = api.packageInfo(name, userId);
      if (info == null || info.applicationInfo == null) continue;
      int uid = info.applicationInfo.uid;
      if (uid / 100000 != userId || uid % 100000 < 10000 || !stoppedUids.add(uid)) continue;
      api.forceStop(name, userId);
    }
  }

  private static boolean isEnabled(JSONObject profile) {
    return profile != null && profile.optBoolean("enabled", true);
  }
}
