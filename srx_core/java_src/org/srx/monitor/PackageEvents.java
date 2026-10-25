package org.srx.monitor;

import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import org.json.JSONArray;
import org.json.JSONObject;
import org.srx.packages.ConfigProfileStore;
import org.srx.packages.RootPackageApi;

// 独立 Root 服务接收安装广播，不向 system_server 注入任何 Hook。
final class PackageEvents extends Binder {
  private static final String DESCRIPTOR = "android.content.IIntentReceiver";
  private final File module;
  private final Handler worker;
  private final java.util.function.IntConsumer onUidChanged;
  private final HashMap<String, Long> installed = new HashMap<>();
  private Object activity;
  private Class<?> activityApi;

  PackageEvents(File module, Handler worker, java.util.function.IntConsumer onUidChanged) {
    this.module = module;
    this.worker = worker;
    this.onUidChanged = onUidChanged;
  }

  void start() throws Exception {
    IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
        .getMethod("getService", String.class).invoke(null, "activity");
    activityApi = Class.forName("android.app.IActivityManager");
    activity = Class.forName("android.app.IActivityManager$Stub")
        .getMethod("asInterface", IBinder.class).invoke(null, binder);
    Class<?> receiverApi = Class.forName(DESCRIPTOR);
    Object receiver = Class.forName(DESCRIPTOR + "$Stub")
        .getMethod("asInterface", IBinder.class).invoke(null, this);
    IntentFilter filter = new IntentFilter();
    filter.addAction(Intent.ACTION_PACKAGE_ADDED);
    filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
    filter.addDataScheme("package");
    activityApi.getMethod("registerReceiverWithFeature", Class.forName("android.app.IApplicationThread"),
        String.class, String.class, String.class, receiverApi, IntentFilter.class, String.class,
        int.class, int.class).invoke(activity, null, null, null, "srx-packages", receiver,
            filter, null, -1, 2);
    worker.post(() -> {
      try {
        refreshUidIndex();
        reconcileUninstalled();
      } catch (Exception error) {
        Log.e("SrxPackageEvents", "Reconcile installed packages", error);
      }
    });
  }

  @Override
  protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
    if (code == INTERFACE_TRANSACTION) {
      if (reply != null) reply.writeString(DESCRIPTOR);
      return true;
    }
    if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags);
    data.enforceInterface(DESCRIPTOR);
    Intent intent = data.readInt() == 0 ? null : Intent.CREATOR.createFromParcel(data);
    int resultCode = data.readInt();
    String resultData = data.readString();
    Bundle extras = data.readInt() == 0 ? null : Bundle.CREATOR.createFromParcel(data);
    boolean ordered = data.readInt() != 0;
    data.readInt();
    data.readInt();
    if (intent != null) worker.post(() -> {
      try {
        handle(intent);
      } catch (Exception error) {
        Log.e("SrxPackageEvents", "Handle package event: " + intent, error);
      }
    });
    if (ordered) {
      try {
        activityApi.getMethod("finishReceiver", IBinder.class, int.class, String.class,
            Bundle.class, boolean.class, int.class).invoke(activity, this, resultCode,
                resultData, extras, false, intent == null ? 0 : intent.getFlags());
      } catch (ReflectiveOperationException error) {
        Log.e("SrxPackageEvents", "Finish package broadcast", error);
      }
    }
    return true;
  }

  private JSONObject read(File file) throws Exception {
    return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
  }

  private void handle(Intent intent) throws Exception {
    if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false) || intent.getData() == null) return;
    int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
    if (uid < 0) return;
    onUidChanged.accept(uid);
    String name = intent.getData().getSchemeSpecificPart();
    refreshUidIndex();
    int userId = uid / 100000;
    String key = userId + ":" + name;
    ConfigProfileStore store = new ConfigProfileStore(new File(module, "config"), new File("/dev/srx_config"));
    if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())) {
      installed.remove(key);
      store.writeProfile(name, userId, new JSONObject().put("enabled", false), true, true);
      return;
    }
    if (!Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())) return;
    File global = new File(module, "config/global.json");
    if (!global.exists()) return;
    JSONObject settings = read(global);
    if (!settings.optBoolean("auto_enable_redirect_for_new_apps", false)) return;
    RootPackageApi api = new RootPackageApi();
    android.content.pm.PackageInfo info = api.packageInfo(name, userId);
    if (info == null || info.applicationInfo == null || info.applicationInfo.uid != uid
        || (info.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        || uid % 100000 < 10000 || name.equals("com.storage.redirect.x")
        || name.equals("com.storage.redirect.x.debug")) return;
    if (Long.valueOf(info.firstInstallTime).equals(installed.get(key))) return;
    JSONObject profile = new JSONObject();
    String templateId = settings.optString("auto_enable_new_apps_template_id", "");
    if (!templateId.isEmpty()) {
      JSONArray templates = read(new File(module, "config/templates.json")).getJSONArray("templates");
      JSONObject chosen = null;
      for (int index = 0; index < templates.length(); index++) {
        JSONObject template = templates.getJSONObject(index);
        if (templateId.equals(template.getString("id"))) chosen = template;
      }
      if (chosen == null) throw new IllegalStateException("Automatic template unavailable: " + templateId);
      for (String field : new String[] {"allowed_real_paths", "path_mappings", "mapping_mode_only",
               "sandboxed_paths", "read_only_paths"}) {
        if (chosen.has(field)) profile.put(field, chosen.get(field));
      }
    }
    profile.put("enabled", true);
    String[] peers = api.packagesForUid(uid);
    if (peers != null) {
      for (String peer : peers) {
        if (name.equals(peer)) continue;
        File file = new File(module, "config/apps/" + peer + ".json");
        if (!file.exists()) continue;
        JSONObject inherited = read(file).getJSONObject("users").optJSONObject(Integer.toString(userId));
        if (inherited != null && inherited.optBoolean("enabled", true)) {
          profile = new JSONObject(inherited.toString());
          break;
        }
      }
    }
    store.writeProfile(name, userId, profile, true);
    installed.put(key, info.firstInstallTime);
    refreshRules(userId);
  }

  private void reconcileUninstalled() throws Exception {
    File[] files = new File(module, "config/apps").listFiles((directory, name) -> name.endsWith(".json"));
    if (files == null) return;
    RootPackageApi packages = new RootPackageApi();
    ConfigProfileStore store = new ConfigProfileStore(new File(module, "config"), new File("/dev/srx_config"));
    for (File file : files) {
      JSONObject users = read(file).getJSONObject("users");
      java.util.Iterator<String> keys = users.keys();
      String name = file.getName().substring(0, file.getName().length() - 5);
      while (keys.hasNext()) {
        String key = keys.next();
        if (!users.getJSONObject(key).optBoolean("enabled", true)) continue;
        int userId = Integer.parseInt(key);
        if (packages.packageInfo(name, userId) == null)
          store.writeProfile(name, userId, new JSONObject().put("enabled", false), true, true);
      }
    }
  }

  private void refreshUidIndex() throws Exception {
    new RootPackageApi().writeUidIndex(new File(module, "config"), new File("/dev/srx_config"));
  }

  static void refreshRules(int userId) throws Exception {
    if (!new RootPackageApi().isUserUnlocked(userId)) return;
    for (String authority : new String[] {"media", "downloads"}) {
      try (MonitorConnection connection = new MonitorConnection(authority, userId)) {
        Bundle command = new Bundle();
        command.putString("command", "rules");
        connection.call(MonitorProtocol.ATTACH + ".CONTROL", command);
      }
    }
  }
}
