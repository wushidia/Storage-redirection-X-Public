package org.srx.monitor;

/** Package metadata for a root app_process, without creating a framework application. */
public final class MonitorPackages {
  private final Class<?> api;
  private final java.lang.reflect.Method service;

  public MonitorPackages() throws ReflectiveOperationException {
    api = Class.forName("android.content.pm.IPackageManager");
    service = Class.forName("android.app.AppGlobals").getDeclaredMethod("getPackageManager");
  }

  public String[] packagesForUid(int uid) {
    try {
      return (String[]) api.getMethod("getPackagesForUid", int.class)
          .invoke(service.invoke(null), uid);
    } catch (ReflectiveOperationException error) {
      throw new IllegalStateException("read UID packages", error);
    }
  }

  public String sharedName(int uid) {
    try {
      return (String) api.getMethod("getNameForUid", int.class).invoke(service.invoke(null), uid);
    } catch (ReflectiveOperationException error) {
      throw new IllegalStateException("read shared UID", error);
    }
  }
}
