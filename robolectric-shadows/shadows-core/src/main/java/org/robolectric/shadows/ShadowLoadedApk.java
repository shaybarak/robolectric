package org.robolectric.shadows;

import android.app.LoadedApk;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

@Implements(value = LoadedApk.class, isInAndroidSdk = false)
public class ShadowLoadedApk {

  @Implementation
  public ClassLoader getClassLoader() {
    return this.getClass().getClassLoader();
  }
}
