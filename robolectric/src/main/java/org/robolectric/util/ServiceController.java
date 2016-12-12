package org.robolectric.util;

import android.app.Application;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.ShadowsAdapter;

import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

public class ServiceController<T extends Service> extends ComponentController<ServiceController<T>, T>{

  private String shadowActivityThreadClassName;

  public static <T extends Service> ServiceController<T> of(ShadowsAdapter shadowsAdapter, T service, Intent intent) {
    ServiceController<T> controller = new ServiceController<>(shadowsAdapter, service, intent);
    controller.attach();
    return controller;
  }

  private ServiceController(ShadowsAdapter shadowsAdapter, T service, Intent intent) {
    super(shadowsAdapter, service, intent);
    shadowActivityThreadClassName = shadowsAdapter.getShadowActivityThreadClassName();
  }

  public ServiceController<T> attach() {
    if (attached) {
      return this;
    }

    Context baseContext = RuntimeEnvironment.application.getBaseContext();

    ClassLoader cl = baseContext.getClassLoader();
    Class<?> activityThreadClass;
    try {
      activityThreadClass = cl.loadClass(shadowActivityThreadClassName);
    } catch (ClassNotFoundException e) {
      e.printStackTrace();
      throw new RuntimeException(e);
    }

    ReflectionHelpers.callInstanceMethod(Service.class, component, "attach",
        from(Context.class, baseContext),
        from(activityThreadClass, null),
        from(String.class, component.getClass().getSimpleName()),
        from(IBinder.class, null),
        from(Application.class, RuntimeEnvironment.application),
        from(Object.class, null));

    attached = true;
    return this;
  }

  public ServiceController<T> bind() {
    invokeWhilePaused("onBind", from(Intent.class, getIntent()));
    return this;
  }

  public ServiceController<T> create() {
    invokeWhilePaused("onCreate");
    return this;
  }

  public ServiceController<T> destroy() {
    invokeWhilePaused("onDestroy");
    return this;
  }

  public ServiceController<T> rebind() {
    invokeWhilePaused("onRebind", from(Intent.class, getIntent()));
    return this;
  }

  public ServiceController<T> startCommand(int flags, int startId) {
    invokeWhilePaused("onStartCommand", from(Intent.class, getIntent()), from(int.class, flags), from(int.class, startId));
    return this;
  }

  public ServiceController<T> unbind() {
    invokeWhilePaused("onUnbind", from(Intent.class, getIntent()));
    return this;
  }
}
