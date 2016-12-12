package org.robolectric.internal;

import org.robolectric.internal.bytecode.ShadowInvalidator;
import org.robolectric.internal.dependency.DependencyResolver;
import org.robolectric.internal.bytecode.ShadowMap;
import org.robolectric.internal.bytecode.ShadowWrangler;
import org.robolectric.res.Fs;
import org.robolectric.res.PackageResourceLoader;
import org.robolectric.res.ResourceExtractor;
import org.robolectric.res.ResourceLoader;
import org.robolectric.res.ResourcePath;

import java.util.HashMap;
import java.util.Map;

public class SdkEnvironment {
  private final SdkConfig sdkConfig;
  private final ClassLoader robolectricClassLoader;
  private final ShadowInvalidator shadowInvalidator;
  private ShadowMap shadowMap = ShadowMap.EMPTY;
  private ResourceLoader systemResourceLoader;

  public SdkEnvironment(SdkConfig sdkConfig, ClassLoader robolectricClassLoader) {
    this.sdkConfig = sdkConfig;
    this.robolectricClassLoader = robolectricClassLoader;
    shadowInvalidator = new ShadowInvalidator();
  }

  public synchronized ResourceLoader getSystemResourceLoader(DependencyResolver dependencyResolver) {
    if (systemResourceLoader == null) {
      ResourcePath resourcePath;
      try {
        Class<?> androidInternalRClass = getRobolectricClassLoader().loadClass("com.android.internal.R");
        Class<?> androidRClass = getRobolectricClassLoader().loadClass("android.R");
        Fs systemResFs = Fs.fromJar(dependencyResolver.getLocalArtifactUrl(sdkConfig.getAndroidSdkDependency()));
        resourcePath = new ResourcePath(androidRClass, androidRClass.getPackage().getName(), systemResFs.join("res"), systemResFs.join("assets"), androidInternalRClass);
      } catch (ClassNotFoundException e) {
        throw new RuntimeException(e);
      }

      ResourceExtractor resourceExtractor = new ResourceExtractor(resourcePath);
      systemResourceLoader = new PackageResourceLoader(resourcePath, resourceExtractor);
    }
    return systemResourceLoader;
  }

  public Class<?> bootstrappedClass(Class<?> testClass) {
    try {
      return robolectricClassLoader.loadClass(testClass.getName());
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  public ClassLoader getRobolectricClassLoader() {
    return robolectricClassLoader;
  }

  public ShadowInvalidator getShadowInvalidator() {
    return shadowInvalidator;
  }

  public SdkConfig getSdkConfig() {
    return sdkConfig;
  }

  public ShadowMap replaceShadowMap(ShadowMap shadowMap) {
    ShadowMap oldMap = this.shadowMap;
    this.shadowMap = shadowMap;
    return oldMap;
  }
}
