package org.robolectric.util;

import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.ShadowsAdapter;
import org.robolectric.ShadowsAdapter.ShadowActivityAdapter;
import org.robolectric.ShadowsAdapter.ShadowApplicationAdapter;
import org.robolectric.internal.Shadow;
import org.robolectric.internal.runtime.RuntimeAdapter;
import org.robolectric.internal.runtime.RuntimeAdapterFactory;
import org.robolectric.manifest.AndroidManifest;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.Display;
import android.view.ViewRootImpl;

import static android.os.Build.VERSION_CODES.M;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

public class ActivityController<T extends Activity> extends ComponentController<ActivityController<T>, T> {
  private final ShadowsAdapter shadowsAdapter;
  private ShadowActivityAdapter shadowReference;

  public static <T extends Activity> ActivityController<T> of(ShadowsAdapter shadowsAdapter, T activity, Intent intent) {
    return new ActivityController<>(shadowsAdapter, activity, intent).attach();
  }

  public static <T extends Activity> ActivityController<T> of(ShadowsAdapter shadowsAdapter, T activity) {
    return new ActivityController<>(shadowsAdapter, activity, null).attach();
  }

  private ActivityController(ShadowsAdapter shadowsAdapter, T activity, Intent intent) {
    super(shadowsAdapter, activity, intent);
    this.shadowsAdapter = shadowsAdapter;
    shadowReference = shadowsAdapter.getShadowActivityAdapter(this.component);
  }

  public ActivityController<T> withIntent(Intent intent) {
    super.withIntent(intent);

    // This is a hack to support existing usages where withIntent() is called after attach().
    ReflectionHelpers.setField(component, "mIntent", getIntent());
    ReflectionHelpers.setField(component, "mComponent", getIntent().getComponent());
    return myself;
  }

  public ActivityController<T> attach() {
    if (attached) {
      return this;
    }

    Context baseContext = RuntimeEnvironment.application.getBaseContext();

    final String title = getActivityTitle();
    final ClassLoader cl = baseContext.getClassLoader();
    final ActivityInfo info = getActivityInfo(RuntimeEnvironment.application);
    final Class<?> threadClass = getActivityThreadClass(cl);
    final Class<?> nonConfigurationClass = getNonConfigurationClass(cl);

    final RuntimeAdapter runtimeAdapter = RuntimeAdapterFactory.getInstance();
    runtimeAdapter.callActivityAttach(component, baseContext, threadClass, RuntimeEnvironment.application, getIntent(), info, title, nonConfigurationClass);

    shadowReference.setThemeFromManifest();
    attached = true;
    return this;
  }

  private ActivityInfo getActivityInfo(Application application) {
    try {
      return application.getPackageManager().getActivityInfo(new ComponentName(application.getPackageName(), component.getClass().getName()), PackageManager.GET_ACTIVITIES | PackageManager.GET_META_DATA);
    } catch (PackageManager.NameNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  private Class<?> getActivityThreadClass(ClassLoader cl) {
    try {
      return cl.loadClass(shadowsAdapter.getShadowActivityThreadClassName());
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  private Class<?> getNonConfigurationClass(ClassLoader cl) {
    try {
      return cl.loadClass("android.app.Activity$NonConfigurationInstances");
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  private String getActivityTitle() {
    String title = null;

    /* Get the label for the activity from the manifest */
    ShadowApplicationAdapter shadowApplicationAdapter = shadowsAdapter.getApplicationAdapter(component);
    AndroidManifest appManifest = shadowApplicationAdapter.getAppManifest();
    if (appManifest == null) return null;
    String labelRef = appManifest.getActivityLabel(component.getClass());

    if (labelRef != null) {
      if (labelRef.startsWith("@")) {
        /* Label refers to a string value, get the resource identifier */
        int labelRes = RuntimeEnvironment.application.getResources().getIdentifier(labelRef.replace("@", ""), "string", appManifest.getPackageName());
        /* Get the resource ID, use the activity to look up the actual string */
        title = RuntimeEnvironment.application.getString(labelRes);
      } else {
        title = labelRef; /* Label isn't an identifier, use it directly as the title */
      }
    }

    return title;
  }

  public ActivityController<T> create(final Bundle bundle) {
    shadowMainLooper.runPaused(new Runnable() {
      @Override
      public void run() {
        ReflectionHelpers.callInstanceMethod(Activity.class, component, "performCreate", from(Bundle.class, bundle));
      }
    });
    return this;
  }

  public ActivityController<T> create() {
    return create(null);
  }

  public ActivityController<T> restoreInstanceState(Bundle bundle) {
    invokeWhilePaused("performRestoreInstanceState", from(Bundle.class, bundle));
    return this;
  }

  public ActivityController<T> postCreate(Bundle bundle) {
    invokeWhilePaused("onPostCreate", from(Bundle.class, bundle));
    return this;
  }

  public ActivityController<T> start() {
    invokeWhilePaused("performStart");
    return this;
  }

  public ActivityController<T> restart() {
    invokeWhilePaused("performRestart");
    return this;
  }

  public ActivityController<T> resume() {
    invokeWhilePaused("performResume");
    return this;
  }

  public ActivityController<T> postResume() {
    invokeWhilePaused("onPostResume");
    return this;
  }

  public ActivityController<T> newIntent(Intent intent) {
    invokeWhilePaused("onNewIntent", from(Intent.class, intent));
    return this;
  }

  public ActivityController<T> saveInstanceState(Bundle outState) {
    invokeWhilePaused("performSaveInstanceState", from(Bundle.class, outState));
    return this;
  }

  public ActivityController<T> visible() {
    shadowMainLooper.runPaused(new Runnable() {
      @Override
      public void run() {
        ReflectionHelpers.setField(component, "mDecor", component.getWindow().getDecorView());
        ReflectionHelpers.callInstanceMethod(component, "makeVisible");
      }
    });

    ViewRootImpl root = component.getWindow().getDecorView().getViewRootImpl();
    if (root != null) {
      // If a test pause thread before creating an activity, root will be null as runPaused is waiting
      // Related to issue #1582
      Display display = Shadow.newInstanceOf(Display.class);
      Rect frame = new Rect();
      display.getRectSize(frame);
      Rect insets = new Rect(0, 0, 0, 0);
      final RuntimeAdapter runtimeAdapter = RuntimeAdapterFactory.getInstance();
      runtimeAdapter.callViewRootImplDispatchResized(
          root, frame, insets, insets, insets, insets, insets, true, null);
    }

    return this;
  }

  public ActivityController<T> pause() {
    invokeWhilePaused("performPause");
    return this;
  }

  public ActivityController<T> userLeaving() {
    invokeWhilePaused("performUserLeaving");
    return this;
  }

  public ActivityController<T> stop() {
    if (RuntimeEnvironment.getApiLevel() <= M) {
      invokeWhilePaused("performStop");
    } else {
      invokeWhilePaused("performStop", from(boolean.class, true));
    }
    return this;
  }

  public ActivityController<T> destroy() {
    invokeWhilePaused("performDestroy");
    return this;
  }

  /**
   * Calls the same lifecycle methods on the Activity called by Android the first time the Activity is created.
   *
   * @return Activity controller instance.
   */
  public ActivityController<T> setup() {
    return create().start().postCreate(null).resume().visible();
  }

  /**
   * Calls the same lifecycle methods on the Activity called by Android when an Activity is restored from previously saved state.
   *
   * @param savedInstanceState Saved instance state.
   * @return Activity controller instance.
   */
  public ActivityController<T> setup(Bundle savedInstanceState) {
    return create(savedInstanceState)
        .start()
        .restoreInstanceState(savedInstanceState)
        .postCreate(savedInstanceState)
        .resume()
        .visible();
  }
  
  /**
   * Performs a configuration change on the Activity.
   *  
   * @param newConfiguration The new configuration to be set.
   * @return Activity controller instance.
   */
  public ActivityController<T> configurationChange(final Configuration newConfiguration) {
    final Configuration currentConfig = component.getResources().getConfiguration();
    final int changedBits = currentConfig.diff(newConfiguration);
    currentConfig.setTo(newConfiguration);
    
    // Can the activity handle itself ALL configuration changes?
    if ((getActivityInfo(component.getApplication()).configChanges & changedBits) == changedBits) {
      shadowMainLooper.runPaused(new Runnable() {
        @Override
        public void run() {
          ReflectionHelpers.callInstanceMethod(Activity.class, component, "onConfigurationChanged",
            from(Configuration.class, newConfiguration));
        }
      });

      return this;
    } else {
      @SuppressWarnings("unchecked")
      final T recreatedActivity = (T) ReflectionHelpers.callConstructor(component.getClass());
      
      shadowMainLooper.runPaused(new Runnable() {
        @Override
        public void run() {
          // Set flags
          ReflectionHelpers.setField(Activity.class, component, "mChangingConfigurations", true);
          ReflectionHelpers.setField(Activity.class, component, "mConfigChangeFlags", changedBits);
          
          // Perform activity destruction
          final Bundle outState = new Bundle();
    
          ReflectionHelpers.callInstanceMethod(Activity.class, component, "onSaveInstanceState",
              from(Bundle.class, outState));
          ReflectionHelpers.callInstanceMethod(Activity.class, component, "onPause");
          ReflectionHelpers.callInstanceMethod(Activity.class, component, "onStop");
    
          final Object nonConfigInstance = ReflectionHelpers.callInstanceMethod(
              Activity.class, component, "onRetainNonConfigurationInstance");
    
          ReflectionHelpers.callInstanceMethod(Activity.class, component, "onDestroy");

          // Setup controller for the new activity
          attached = false;
          component = recreatedActivity;
          shadowReference = shadowsAdapter.getShadowActivityAdapter(component);
          attach();
          
          // Set saved non config instance
          Shadows.shadowOf(recreatedActivity).setLastNonConfigurationInstance(nonConfigInstance);
          
            // Create lifecycle
          ReflectionHelpers.callInstanceMethod(Activity.class, recreatedActivity,
              "onCreate", from(Bundle.class, outState));
          ReflectionHelpers.callInstanceMethod(Activity.class, recreatedActivity, "onStart");
          ReflectionHelpers.callInstanceMethod(Activity.class, recreatedActivity,
              "onRestoreInstanceState", from(Bundle.class, outState));
          ReflectionHelpers.callInstanceMethod(Activity.class, recreatedActivity,
              "onPostCreate", from(Bundle.class, outState));
          ReflectionHelpers.callInstanceMethod(Activity.class, recreatedActivity, "onResume");
        }
      });
    }
    
    return this;
  }
}
