package org.srx.packages;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.json.JSONObject;

// UI 与安装事件共享同一把进程间锁，只替换目标用户，避免覆盖其他用户配置。
public final class ConfigProfileStore {
  private final File config;
  private final File mirror;

  public ConfigProfileStore(File config) {
    this(config, null);
  }

  public ConfigProfileStore(File config, File mirror) {
    this.config = config;
    this.mirror = mirror;
  }

  public JSONObject writeProfile(String packageName, int userId, JSONObject profile,
      boolean enableExistingOnly) throws Exception {
    return writeProfile(packageName, userId, profile, enableExistingOnly, false);
  }

  public JSONObject writeProfile(String packageName, int userId, JSONObject profile,
      boolean enableExistingOnly, boolean requireExisting) throws Exception {
    return mutateProfile(packageName, userId, profile, enableExistingOnly, requireExisting, null);
  }

  public JSONObject removeProfile(String packageName, int userId, File archive) throws Exception {
    return mutateProfile(packageName, userId, null, false, true, archive);
  }

  // 共享目录只保存规则，Provider 降权后仍需读取。
  @android.annotation.SuppressLint("SetWorldReadable")
  private JSONObject mutateProfile(String packageName, int userId, JSONObject profile,
      boolean preserveExisting, boolean requireExisting, File archive) throws Exception {
    if (!packageName.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*") || userId < 0)
      throw new IllegalArgumentException("Invalid package or user");
    File apps = new File(config, "apps");
    File locks = new File(config, ".locks");
    Files.createDirectories(apps.toPath());
    Files.createDirectories(locks.toPath());
    try (RandomAccessFile file = new RandomAccessFile(new File(locks, packageName), "rw")) {
      FileLock lock = file.getChannel().lock();
      try {
        File destination = new File(apps, packageName + ".json");
        JSONObject root = destination.exists()
            ? new JSONObject(new String(Files.readAllBytes(destination.toPath()), StandardCharsets.UTF_8))
            : new JSONObject();
        JSONObject users = root.optJSONObject("users");
        if (users == null) {
          users = new JSONObject();
          root.put("users", users);
        }
        String key = Integer.toString(userId);
        JSONObject existing = users.optJSONObject(key);
        if (requireExisting && existing == null) return root;
        if (profile == null) {
          users.remove(key);
          if (users.length() == 0) {
            Files.createDirectories(archive.toPath());
            Files.move(destination.toPath(), new File(archive, packageName + ".removed.json").toPath(),
                StandardCopyOption.REPLACE_EXISTING);
            if (mirror != null) {
              File shared = new File(new File(mirror, "apps"), destination.getName());
              if (shared.exists()) Files.move(shared.toPath(),
                  new File(archive, packageName + ".shared.removed.json").toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return root;
          }
        } else if (preserveExisting && existing != null) {
          existing.put("enabled", profile.getBoolean("enabled"));
        } else {
          users.put(key, profile);
        }
        byte[] content = (root.toString(2) + "\n").getBytes(StandardCharsets.UTF_8);
        writeAtomic(destination, content, false);
        if (mirror != null) {
          File sharedApps = new File(mirror, "apps");
          Files.createDirectories(sharedApps.toPath());
          if (!mirror.setReadable(true, false) || !mirror.setExecutable(true, false)
              || !sharedApps.setReadable(true, false) || !sharedApps.setExecutable(true, false))
            throw new java.io.IOException("Set shared config permissions");
          writeAtomic(new File(sharedApps, destination.getName()), content, true);
        }
        return root;
      } finally {
        lock.release();
      }
    }
  }

  // SELinux 标记仅由独立 Root 进程设置，普通 App 不执行此入口。
  @android.annotation.SuppressLint({"PrivateApi", "SetWorldReadable"})
  static void writeAtomic(File destination, byte[] content, boolean shared) throws Exception {
    File temporary = File.createTempFile(destination.getName() + ".", ".tmp", destination.getParentFile());
    try {
      Files.write(temporary.toPath(), content);
      if (!temporary.setReadable(true, false))
        throw new java.io.IOException("Set config permissions: " + temporary);
      if (shared && !(Boolean) Class.forName("android.os.SELinux")
              .getMethod("setFileContext", String.class, String.class)
              .invoke(null, temporary.getPath(), "u:object_r:shell_data_file:s0"))
        throw new java.io.IOException("Label shared config: " + temporary);
      Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary.toPath());
    }
  }
}
