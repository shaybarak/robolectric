package org.robolectric;

import android.app.Application;
import android.os.Build;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.internal.AssumptionViolatedException;
import org.junit.internal.runners.model.EachTestNotifier;
import org.junit.runner.Description;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.InitializationError;
import org.junit.runners.model.Statement;
import org.junit.runners.model.TestClass;
import org.robolectric.annotation.Config;
import org.robolectric.internal.GradleManifestFactory;
import org.robolectric.internal.InstrumentingClassLoaderFactory;
import org.robolectric.internal.ManifestFactory;
import org.robolectric.internal.ManifestIdentifier;
import org.robolectric.internal.MavenManifestFactory;
import org.robolectric.internal.ParallelUniverse;
import org.robolectric.internal.ParallelUniverseInterface;
import org.robolectric.internal.SdkConfig;
import org.robolectric.internal.SdkEnvironment;
import org.robolectric.internal.bytecode.ClassHandler;
import org.robolectric.internal.bytecode.InstrumentationConfiguration;
import org.robolectric.internal.bytecode.InvokeDynamic;
import org.robolectric.internal.bytecode.RobolectricInternals;
import org.robolectric.internal.bytecode.ShadowInvalidator;
import org.robolectric.internal.bytecode.ShadowMap;
import org.robolectric.internal.bytecode.ShadowWrangler;
import org.robolectric.internal.dependency.CachedDependencyResolver;
import org.robolectric.internal.dependency.DependencyResolver;
import org.robolectric.internal.dependency.LocalDependencyResolver;
import org.robolectric.internal.dependency.MavenDependencyResolver;
import org.robolectric.internal.dependency.PropertiesDependencyResolver;
import org.robolectric.manifest.AndroidManifest;
import org.robolectric.res.EmptyResourceLoader;
import org.robolectric.res.Fs;
import org.robolectric.res.FsFile;
import org.robolectric.res.OverlayResourceLoader;
import org.robolectric.res.PackageResourceLoader;
import org.robolectric.res.ResourceExtractor;
import org.robolectric.res.ResourceLoader;
import org.robolectric.res.ResourcePath;
import org.robolectric.res.RoutingResourceLoader;
import org.robolectric.util.Logger;
import org.robolectric.util.Pair;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static com.google.common.collect.Lists.reverse;

/**
 * Installs a {@link org.robolectric.internal.bytecode.InstrumentingClassLoader} and
 * {@link org.robolectric.res.ResourceLoader} in order to provide a simulation of the Android runtime environment.
 */
public class RobolectricTestRunner extends BlockJUnit4ClassRunner {
  private static final String CONFIG_PROPERTIES = "robolectric.properties";
  private static final Map<AndroidManifest, ResourceLoader> appResourceLoaderCache = new HashMap<>();
  private static final Map<ManifestIdentifier, AndroidManifest> appManifestsCache = new HashMap<>();
  private static ResourceLoader compiletimeSdkResourceLoader;

  private TestLifecycle<Application> testLifecycle;
  private DependencyResolver dependencyResolver;

  static {
    new SecureRandom(); // this starts up the Poller SunPKCS11-Darwin thread early, outside of any Robolectric classloader
  }

  private final HashSet<Class<?>> loadedTestClasses = new HashSet<>();
  private final Map<String, Config> packageConfigCache = new LinkedHashMap<String, Config>() {
    @Override
    protected boolean removeEldestEntry(Map.Entry eldest) {
      return size() > 10;
    }
  };

  /**
   * Creates a runner to run {@code testClass}. Looks in your working directory for your AndroidManifest.xml file
   * and res directory by default. Use the {@link Config} annotation to configure.
   *
   * @param testClass the test class to be run
   * @throws InitializationError if junit says so
   */
  public RobolectricTestRunner(final Class<?> testClass) throws InitializationError {
    super(testClass);
  }

  @SuppressWarnings("unchecked")
  private void assureTestLifecycle(SdkEnvironment sdkEnvironment) {
    try {
      ClassLoader robolectricClassLoader = sdkEnvironment.getRobolectricClassLoader();
      testLifecycle = (TestLifecycle) robolectricClassLoader.loadClass(getTestLifecycleClass().getName()).newInstance();
    } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  protected DependencyResolver getJarResolver() {
    if (dependencyResolver == null) {
      if (Boolean.getBoolean("robolectric.offline")) {
        String dependencyDir = System.getProperty("robolectric.dependency.dir", ".");
        dependencyResolver = new LocalDependencyResolver(new File(dependencyDir));
      } else {
        File cacheDir = new File(new File(System.getProperty("java.io.tmpdir")), "robolectric");

        if (cacheDir.exists() || cacheDir.mkdir()) {
          Logger.info("Dependency cache location: %s", cacheDir.getAbsolutePath());
          dependencyResolver = new CachedDependencyResolver(new MavenDependencyResolver(), cacheDir, 60 * 60 * 24 * 1000);
        } else {
          dependencyResolver = new MavenDependencyResolver();
        }
      }

      URL buildPathPropertiesUrl = getClass().getClassLoader().getResource("robolectric-deps.properties");
      if (buildPathPropertiesUrl != null) {
        Logger.info("Using Robolectric classes from %s", buildPathPropertiesUrl.getPath());

        FsFile propertiesFile = Fs.fileFromPath(buildPathPropertiesUrl.getFile());
        try {
          dependencyResolver = new PropertiesDependencyResolver(propertiesFile, dependencyResolver);
        } catch (IOException e) {
          throw new RuntimeException("couldn't read " + buildPathPropertiesUrl, e);
        }
      }
    }

    return dependencyResolver;
  }

  public InstrumentationConfiguration createClassLoaderConfig(Config config) {
    return InstrumentationConfiguration.newBuilder().withConfig(config).build();
  }

  protected Class<? extends TestLifecycle> getTestLifecycleClass() {
    return DefaultTestLifecycle.class;
  }

  public static void injectEnvironment(ClassLoader robolectricClassLoader,
      ClassHandler classHandler, ShadowInvalidator invalidator) {
    String className = RobolectricInternals.class.getName();
    Class<?> robolectricInternalsClass = ReflectionHelpers.loadClass(robolectricClassLoader, className);
    ReflectionHelpers.setStaticField(robolectricInternalsClass, "classHandler", classHandler);
    ReflectionHelpers.setStaticField(robolectricInternalsClass, "shadowInvalidator", invalidator);
  }

  @Override
  protected Statement classBlock(RunNotifier notifier) {
    final Statement statement = childrenInvoker(notifier);
    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        try {
          statement.evaluate();
          for (Class<?> testClass : loadedTestClasses) {
            invokeAfterClass(testClass);
          }
        } finally {
          afterClass();
          loadedTestClasses.clear();
        }
      }
    };
  }

  private static void invokeAfterClass(final Class<?> clazz) throws Throwable {
    final TestClass testClass = new TestClass(clazz);
    final List<FrameworkMethod> afters = testClass.getAnnotatedMethods(AfterClass.class);
    for (FrameworkMethod after : afters) {
      after.invokeExplosively(null);
    }
  }

  @Override
  protected void runChild(FrameworkMethod method, RunNotifier notifier) {
    Description description = describeChild(method);
    EachTestNotifier eachNotifier = new EachTestNotifier(notifier, description);

    final Config config = getConfig(method.getMethod());
    if (shouldIgnore(method, config)) {
      eachNotifier.fireTestIgnored();
    } else if(shouldRunApiVersion(config)) {
      eachNotifier.fireTestStarted();
      try {
        AndroidManifest appManifest = getAppManifest(config);
        InstrumentingClassLoaderFactory instrumentingClassLoaderFactory = new InstrumentingClassLoaderFactory(createClassLoaderConfig(config), getJarResolver());
        SdkEnvironment sdkEnvironment = instrumentingClassLoaderFactory.getSdkEnvironment(new SdkConfig(pickSdkVersion(config, appManifest)));
        methodBlock(method, config, appManifest, sdkEnvironment).evaluate();
      } catch (AssumptionViolatedException e) {
        eachNotifier.addFailedAssumption(e);
      } catch (Throwable e) {
        eachNotifier.addFailure(e);
      } finally {
        eachNotifier.fireTestFinished();
      }
    }
  }

  /**
   * Returns the ResourceLoader for the compile time SDK.
   */
  @NotNull
  private static ResourceLoader getCompiletimeSdkResourceLoader() {
    if (compiletimeSdkResourceLoader == null) {
      compiletimeSdkResourceLoader = new EmptyResourceLoader("android", new ResourceExtractor(new ResourcePath(android.R.class, "android", null, null)));
    }
    return compiletimeSdkResourceLoader;
  }

  protected boolean shouldRunApiVersion(Config config) {
    return true;
  }

  protected boolean shouldIgnore(FrameworkMethod method, Config config) {
    return method.getAnnotation(Ignore.class) != null;
  }

  private ParallelUniverseInterface parallelUniverseInterface;

  Statement methodBlock(final FrameworkMethod method, final Config config, final AndroidManifest appManifest, final SdkEnvironment sdkEnvironment) {
    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        // Configure shadows *BEFORE* setting the ClassLoader. This is necessary because
        // creating the ShadowMap loads all ShadowProviders via ServiceLoader and this is
        // not available once we install the Robolectric class loader.
        configureShadows(sdkEnvironment, config);

        Thread.currentThread().setContextClassLoader(sdkEnvironment.getRobolectricClassLoader());

        Class bootstrappedTestClass = sdkEnvironment.bootstrappedClass(getTestClass().getJavaClass());
        HelperTestRunner helperTestRunner = getHelperTestRunner(bootstrappedTestClass);

        final Method bootstrappedMethod;
        try {
          //noinspection unchecked
          bootstrappedMethod = bootstrappedTestClass.getMethod(method.getName());
        } catch (NoSuchMethodException e) {
          throw new RuntimeException(e);
        }

        parallelUniverseInterface = getHooksInterface(sdkEnvironment);
        try {
          try {
            // Only invoke @BeforeClass once per class
            if (!loadedTestClasses.contains(bootstrappedTestClass)) {
              invokeBeforeClass(bootstrappedTestClass);
            }
            assureTestLifecycle(sdkEnvironment);

            parallelUniverseInterface.setSdkConfig(sdkEnvironment.getSdkConfig());
            parallelUniverseInterface.resetStaticState(config);

            int sdkVersion = pickSdkVersion(config, appManifest);
            Class<?> androidBuildVersionClass = sdkEnvironment.bootstrappedClass(Build.VERSION.class);
            ReflectionHelpers.setStaticField(androidBuildVersionClass, "SDK_INT", sdkVersion);
            SdkConfig sdkConfig = new SdkConfig(sdkVersion);
            ReflectionHelpers.setStaticField(androidBuildVersionClass, "RELEASE", sdkConfig.getAndroidVersion());

            ResourceLoader systemResourceLoader = sdkEnvironment.getSystemResourceLoader(getJarResolver());
            ResourceLoader appResourceLoader = getAppResourceLoader(appManifest);

            parallelUniverseInterface.setUpApplicationState(bootstrappedMethod, testLifecycle, getCompiletimeSdkResourceLoader(), systemResourceLoader, appResourceLoader, appManifest, config);
            testLifecycle.beforeTest(bootstrappedMethod);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }

          final Statement statement = helperTestRunner.methodBlock(new FrameworkMethod(bootstrappedMethod));

          // todo: this try/finally probably isn't right -- should mimic RunAfters? [xw]
          try {
            statement.evaluate();
          } finally {
            try {
              parallelUniverseInterface.tearDownApplication();
            } finally {
              try {
                internalAfterTest(bootstrappedMethod);
              } finally {
                parallelUniverseInterface.resetStaticState(config); // afterward too, so stuff doesn't hold on to classes?
              }
            }
          }
        } finally {
          Thread.currentThread().setContextClassLoader(RobolectricTestRunner.class.getClassLoader());
          parallelUniverseInterface = null;
        }
      }
    };
  }

  private void invokeBeforeClass(final Class clazz) throws Throwable {
    if (!loadedTestClasses.contains(clazz)) {
      loadedTestClasses.add(clazz);

      final TestClass testClass = new TestClass(clazz);
      final List<FrameworkMethod> befores = testClass.getAnnotatedMethods(BeforeClass.class);
      for (FrameworkMethod before : befores) {
        before.invokeExplosively(null);
      }
    }
  }

  protected HelperTestRunner getHelperTestRunner(Class bootstrappedTestClass) {
    try {
      return new HelperTestRunner(bootstrappedTestClass);
    } catch (InitializationError initializationError) {
      throw new RuntimeException(initializationError);
    }
  }

  /**
   * Detects what build system is in use and returns the appropriate ManifestFactory implementation.
   * @param config Specification of the SDK version, manifest file, package name, etc.
   */
  protected ManifestFactory getManifestFactory(Config config) {
    if (config.constants() != null && config.constants() != Void.class) {
      return new GradleManifestFactory();
    } else {
      return new MavenManifestFactory();
    }
  }

  protected AndroidManifest getAppManifest(Config config) {
    ManifestFactory manifestFactory = getManifestFactory(config);
    ManifestIdentifier identifier = manifestFactory.identify(config);

    synchronized (appManifestsCache) {
      AndroidManifest appManifest;
      appManifest = appManifestsCache.get(identifier);
      if (appManifest == null) {
        appManifest = manifestFactory.create(identifier);
        appManifestsCache.put(identifier, appManifest);
      }

      return appManifest;
    }
  }

  public Config getConfig(Method method) {
    Class testClass = getTestClass().getJavaClass();

    Config config = Config.Builder.defaults().build();

    Config globalConfig = cachedPackageConfig(""); // global config
    config = override(config, globalConfig);

    for (String packageName : reverse(packagesFor(testClass))) {
      Config packageConfig = cachedPackageConfig(packageName);
      config = override(config, packageConfig);
    }

    for (Class clazz : reverse(parentClassesFor(testClass))) {
      Config classConfig = (Config) clazz.getAnnotation(Config.class);
      config = override(config, classConfig);
    }

    Config methodConfig = method.getAnnotation(Config.class);
    config = override(config, methodConfig);

    return config;
  }

  @NotNull
  private List<String> packagesFor(Class<?> javaClass) {
    String testPackageName = javaClass.getPackage().getName();
    List<String> packageHierarchy = new ArrayList<>();
    while (!testPackageName.isEmpty()) {
      packageHierarchy.add(testPackageName);
      int lastDot = testPackageName.lastIndexOf('.');
      testPackageName = lastDot > 1 ? testPackageName.substring(0, lastDot) : "";
    }
    return packageHierarchy;
  }

  @NotNull
  private List<Class> parentClassesFor(Class testClass) {
    List<Class> testClassHierarchy = new ArrayList<>();
    while (testClass != null && !testClass.equals(Object.class)) {
      testClassHierarchy.add(testClass);
      testClass = testClass.getSuperclass();
    }
    return testClassHierarchy;
  }

  private Config override(Config config, Config classConfig) {
    return classConfig != null ? new Config.Implementation(config, classConfig) : config;
  }

  @Nullable
  private Config cachedPackageConfig(String packageName) {
    synchronized (packageConfigCache) {
      Config config = packageConfigCache.get(packageName);
      if (config == null && !packageConfigCache.containsKey(packageName)) {
        config = packageName.isEmpty() ? buildGlobalConfig() : buildPackageConfig(packageName);
        packageConfigCache.put(packageName, config);
      }
      return config;
    }
  }

  /**
   * Generate the global {@link Config}.
   *
   * More specific packages, test classes, and test method configurations
   * will override values provided here.
   *
   * The default implementation uses properties provided by {@link #getConfigProperties(String)}.
   *
   * The returned object is likely to be reused for many tests.
   *
   * @return global {@link Config} object.
   */
  protected Config buildGlobalConfig() {
    return Config.Implementation.fromProperties(getConfigProperties(""));
  }

  /**
   * Generate {@link Config} for the specified package.
   *
   * More specific packages, test classes, and test method configurations
   * will override values provided here.
   *
   * The default implementation uses properties provided by {@link #getConfigProperties(String)}.
   *
   * The returned object is likely to be reused for many tests.
   *
   * @param packageName The name of the package, or empty string (<code>""</code>) for the top level package.
   * @return {@link Config} object for the specified package.
   */
  @Nullable
  private Config buildPackageConfig(String packageName) {
    return Config.Implementation.fromProperties(getConfigProperties(packageName));
  }

  /**
   * Return a {@link Properties} file for the given package name, or <code>null</code> if none is available.
   */
  protected Properties getConfigProperties(String packageName) {
    String resourceName = packageName.replace('.', '/') + "/" + CONFIG_PROPERTIES;
    try (InputStream resourceAsStream = getResourceAsStream(resourceName)) {
      if (resourceAsStream == null) return null;
      Properties properties = new Properties();
      properties.load(resourceAsStream);
      return properties;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  // visible for testing
  InputStream getResourceAsStream(String resourceName) {
    return getClass().getClassLoader().getResourceAsStream(resourceName);
  }

  protected void configureShadows(SdkEnvironment sdkEnvironment, Config config) {
    ShadowMap shadowMap = createShadowMap();

    if (config != null) {
      Class<?>[] shadows = config.shadows();
      if (shadows.length > 0) {
        shadowMap = shadowMap.newBuilder().addShadowClasses(shadows).build();
      }
    }

    if (InvokeDynamic.ENABLED) {
      ShadowMap oldShadowMap = sdkEnvironment.replaceShadowMap(shadowMap);
      Set<String> invalidatedClasses = shadowMap.getInvalidatedClasses(oldShadowMap);
      sdkEnvironment.getShadowInvalidator().invalidateClasses(invalidatedClasses);
    }

    ClassHandler classHandler = new ShadowWrangler(shadowMap, sdkEnvironment.getSdkConfig()
        .getApiLevel());
    injectEnvironment(sdkEnvironment.getRobolectricClassLoader(), classHandler, sdkEnvironment.getShadowInvalidator());
  }

  protected int pickSdkVersion(Config config, AndroidManifest manifest) {
    if (config != null && config.sdk().length > 1) {
      throw new IllegalArgumentException("RobolectricTestRunner does not support multiple values for @Config.sdk");
    } else if (config != null && config.sdk().length == 1) {
      return config.sdk()[0];
    } else {
      return manifest.getTargetSdkVersion();
    }
  }

  ParallelUniverseInterface getHooksInterface(SdkEnvironment sdkEnvironment) {
    ClassLoader robolectricClassLoader = sdkEnvironment.getRobolectricClassLoader();
    try {
      Class<?> clazz = robolectricClassLoader.loadClass(ParallelUniverse.class.getName());
      Class<? extends ParallelUniverseInterface> typedClazz = clazz.asSubclass(ParallelUniverseInterface.class);
      Constructor<? extends ParallelUniverseInterface> constructor = typedClazz.getConstructor(RobolectricTestRunner.class);
      return constructor.newInstance(this);
    } catch (ClassNotFoundException | NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
      throw new RuntimeException(e);
    }
  }

  public void internalAfterTest(final Method method) {
    testLifecycle.afterTest(method);
  }

  private void afterClass() {
    testLifecycle = null;
  }

  @TestOnly
  boolean allStateIsCleared() {
    return testLifecycle == null;
  }

  @Override
  public Object createTest() throws Exception {
    throw new UnsupportedOperationException("this should always be invoked on the HelperTestRunner!");
  }

  private final ResourceLoader getAppResourceLoader(final AndroidManifest appManifest) {
    ResourceLoader resourceLoader = appResourceLoaderCache.get(appManifest);
    if (resourceLoader == null) {
      List<PackageResourceLoader> appAndLibraryResourceLoaders = new ArrayList<>();
      for (ResourcePath resourcePath : appManifest.getIncludedResourcePaths()) {
        appAndLibraryResourceLoaders.add(new PackageResourceLoader(resourcePath, new ResourceExtractor(resourcePath)));
      }
      resourceLoader = new OverlayResourceLoader(appManifest.getPackageName(), appAndLibraryResourceLoaders);
      appResourceLoaderCache.put(appManifest, resourceLoader);
    }
    return resourceLoader;
  }

  protected ShadowMap createShadowMap() {
    return ShadowMap.EMPTY;
  }

  public class HelperTestRunner extends BlockJUnit4ClassRunner {
    public HelperTestRunner(Class<?> testClass) throws InitializationError {
      super(testClass);
    }

    @Override protected Object createTest() throws Exception {
      Object test = super.createTest();
      testLifecycle.prepareTest(test);
      return test;
    }

    @Override public Statement classBlock(RunNotifier notifier) {
      return super.classBlock(notifier);
    }

    @Override public Statement methodBlock(FrameworkMethod method) {
      return super.methodBlock(method);
    }

    @Override
    protected Statement methodInvoker(FrameworkMethod method, Object test) {
      final Statement invoker = super.methodInvoker(method, test);
      return new Statement() {
        @Override
        public void evaluate() throws Throwable {
          Thread orig = parallelUniverseInterface.getMainThread();
          parallelUniverseInterface.setMainThread(Thread.currentThread());
          try {
            invoker.evaluate();
          } finally {
            parallelUniverseInterface.setMainThread(orig);
          }
        }
      };
    }
  }
}
