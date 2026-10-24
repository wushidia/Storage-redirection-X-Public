package org.srx.hook;

import static org.junit.Assert.*;

import java.lang.reflect.Method;
import org.junit.Test;

public class FuseEventTest {
  public static class FileOpenResult {
    public int status;
    public int uid;
  }
  public static class Provider {
    public FileOpenResult onFileOpenForFuse(String path, String ioPath, int uid, int tid,
        int reason, boolean write, boolean redact, boolean metrics) {
      return null;
    }
    public int insertFileIfNecessaryForFuse(String path, int uid) {
      return 0;
    }
    public int isOpenAllowedForFuse(String path, int uid) {
      return 0;
    }
    public int onFileOpenForFuse(int unrelated) {
      return 0;
    }
  }
  @Test
  public void onlyKnownFuseCallShapesAreSelected() {
    int count = 0;
    for (Method method : Provider.class.getDeclaredMethods())
      if (FileMonitorHooks.fuseSignature(method))
        count++;
    assertEquals(3, count);
  }
  @Test
  public void mediaStoreProxyUsesOriginalUidReturnedByProvider() {
    Object[] arguments = {new Object(), "/storage/emulated/0/a", "/storage/emulated/0/a", 1023, 77,
        0, true, false, false};
    assertEquals(10123, FileMonitorHooks.originatingUid("onFileOpenForFuse", arguments, 10123));
    assertEquals(1023, FileMonitorHooks.originatingUid("onFileOpenForFuse", arguments, -1));
    assertEquals(10123,
        FileMonitorHooks.originatingUid("insertFileIfNecessaryForFuse",
            new Object[] {new Object(), "/storage/emulated/0/a", 10123}, -1));
  }
  @Test
  public void modernOpenObserverReplacesLegacyObserver() {
    java.util.List<Method> selected = FileMonitorHooks.findFuseMethods(Provider.class);
    assertEquals(2, selected.size());
    for (Method method : selected) assertNotEquals("isOpenAllowedForFuse", method.getName());
  }
}
