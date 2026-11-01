package org.srx.packages;

import android.content.pm.PackageInfo;
import android.os.Parcel;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

public final class PackageQueryMain {
  private PackageQueryMain() {}

  public static void main(String[] args) throws Exception {
    if ("sync-config-runtime".equals(args[0])) {
      ConfigChanges.sync(new java.io.File(args[1]), new java.io.File(args[2]),
          "baseline".equals(args[3]));
      return;
    }
    if ("remove-profile".equals(args[0])) {
      new ConfigProfileStore(new java.io.File(args[1]), new java.io.File("/dev/srx_config"))
          .removeProfile(args[2], Integer.parseInt(args[3]), new java.io.File(args[4]));
      return;
    }
    if ("write-profile".equals(args[0])) {
      JSONObject profile = new JSONObject(new String(Base64.decode(args[4], Base64.NO_WRAP),
          java.nio.charset.StandardCharsets.UTF_8));
      System.out.println(new ConfigProfileStore(new java.io.File(args[1]), new java.io.File("/dev/srx_config"))
          .writeProfile(args[2], Integer.parseInt(args[3]), profile, false));
      return;
    }
    RootPackageApi api = new RootPackageApi();
    if ("uids".equals(args[0])) {
      api.writeUidIndex(new java.io.File(args[1]), new java.io.File(args[2]));
      return;
    }
    if ("users".equals(args[0])) {
      System.out.println(api.users());
      return;
    }
    if (!"packages".equals(args[0]))
      throw new IllegalArgumentException("Unknown package query: " + args[0]);
    JSONArray result = new JSONArray();
    for (PackageInfo info : api.installedPackages(Integer.parseInt(args[1]))) {
      Parcel parcel = Parcel.obtain();
      try {
        info.applicationInfo.writeToParcel(parcel, 0);
        result.put(new JSONObject().put("installed_at", info.firstInstallTime)
            .put("application", Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP)));
      } finally {
        parcel.recycle();
      }
    }
    System.out.println(result);
  }
}
