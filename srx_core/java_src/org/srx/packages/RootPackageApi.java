package org.srx.packages;

import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.IBinder;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

// 在 Root 进程中直接调用服务，所有包查询都显式携带 Android 用户 ID。
@android.annotation.SuppressLint("PrivateApi")
public final class RootPackageApi {
  private final Object packages;
  private final Object users;
  private final Class<?> packageApi;
  private final Class<?> userApi;
  private final Class<?> flagsType;

  public RootPackageApi() throws ReflectiveOperationException {
    this(service("package", "android.content.pm.IPackageManager"),
        Class.forName("android.content.pm.IPackageManager"),
        service("user", "android.os.IUserManager"), Class.forName("android.os.IUserManager"),
        Build.VERSION.SDK_INT);
  }

  public RootPackageApi(Object packages, Class<?> packageApi, Object users, Class<?> userApi,
      int sdk) {
    this.packages = packages;
    this.packageApi = packageApi;
    this.users = users;
    this.userApi = userApi;
    flagsType = sdk >= 33 ? long.class : int.class;
  }

  private static Object service(String name, String type) throws ReflectiveOperationException {
    IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
        .getMethod("getService", String.class).invoke(null, name);
    if (binder == null)
      throw new IllegalStateException("Service unavailable: " + name);
    return Class.forName(type + "$Stub").getMethod("asInterface", IBinder.class)
        .invoke(null, binder);
  }

  private Object flags() {
    if (flagsType == long.class)
      return Long.valueOf(0);
    return Integer.valueOf(0);
  }

  public List<PackageInfo> installedPackages(int userId) throws ReflectiveOperationException {
    Object slice = packageApi.getMethod("getInstalledPackages", flagsType, int.class)
        .invoke(packages, flags(), userId);
    List<?> values = (List<?>) slice.getClass().getMethod("getList").invoke(slice);
    ArrayList<PackageInfo> result = new ArrayList<>();
    for (Object value : values) {
      PackageInfo info = (PackageInfo) value;
      if (info.applicationInfo != null && info.applicationInfo.uid / 100000 == userId)
        result.add(info);
    }
    return result;
  }

  public PackageInfo packageInfo(String packageName, int userId)
      throws ReflectiveOperationException {
    return (PackageInfo) packageApi.getMethod("getPackageInfo", String.class, flagsType, int.class)
        .invoke(packages, packageName, flags(), userId);
  }

  private List<?> userInfos() throws ReflectiveOperationException {
    Method method;
    try {
      method = userApi.getMethod("getUsers", boolean.class, boolean.class, boolean.class);
    } catch (NoSuchMethodException legacySignature) {
      // 部分 ROM 保留单参数接口；接口内部失败仍向上传递。
      return (List<?>) userApi.getMethod("getUsers", boolean.class).invoke(users, true);
    }
    return (List<?>) method.invoke(users, true, true, true);
  }

  public JSONArray users() throws ReflectiveOperationException, org.json.JSONException {
    List<?> values = userInfos();
    JSONArray result = new JSONArray();
    for (Object value : values) {
      Class<?> type = value.getClass();
      result.put(new JSONObject().put("id", type.getField("id").getInt(value))
          .put("name", type.getField("name").get(value)));
    }
    return result;
  }

  public boolean isUserUnlocked(int userId) throws ReflectiveOperationException {
    return (Boolean) userApi.getMethod("isUserUnlocked", int.class).invoke(users, userId);
  }

  public String[] packagesForUid(int uid) throws ReflectiveOperationException {
    return (String[]) packageApi.getMethod("getPackagesForUid", int.class).invoke(packages, uid);
  }

  public void forceStop(String packageName, int userId) throws ReflectiveOperationException {
    Object activity = service("activity", "android.app.IActivityManager");
    Class.forName("android.app.IActivityManager")
        .getMethod("forceStopPackage", String.class, int.class)
        .invoke(activity, packageName, userId);
  }

  public void writeUidIndex(java.io.File config, java.io.File mirror) throws Exception {
    java.util.ArrayList<String> entries = new java.util.ArrayList<>();
    JSONArray profiles = users();
    for (int index = 0; index < profiles.length(); index++) {
      int userId = profiles.getJSONObject(index).getInt("id");
      for (PackageInfo info : installedPackages(userId))
        entries.add(info.packageName + ":" + info.applicationInfo.uid);
    }
    java.util.Collections.sort(entries);
    byte[] content = ("# package:uid\n" + String.join("\n", entries) + "\n")
        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    java.nio.file.Files.createDirectories(config.toPath());
    java.io.File locks = new java.io.File(config, ".locks");
    java.nio.file.Files.createDirectories(locks.toPath());
    try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(new java.io.File(locks, "uid-index"), "rw")) {
      java.nio.channels.FileLock lock = file.getChannel().lock();
      try {
        ConfigProfileStore.writeAtomic(new java.io.File(config, "system_writer_uids.list"), content, false);
        java.nio.file.Files.createDirectories(mirror.toPath());
        ConfigProfileStore.writeAtomic(new java.io.File(mirror, "system_writer_uids.list"), content, true);
      } finally {
        lock.release();
      }
    }
  }
}
