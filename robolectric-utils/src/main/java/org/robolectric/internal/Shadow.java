package org.robolectric.internal;

import org.robolectric.internal.bytecode.DirectObjectMarker;
import org.robolectric.internal.bytecode.InvokeDynamic;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import org.robolectric.util.ReflectionHelpers.StringParameter;

public class Shadow {
  private static final ProxyMaker PROXY_MAKER = new ProxyMaker(new ProxyMaker.MethodMapper() {
    @Override public String getName(String className, String methodName) {
      return directMethodName(methodName);
    }
  });

  public static <T> T newInstanceOf(Class<T> clazz) {
    return ReflectionHelpers.callConstructor(clazz);
  }

  public static Object newInstanceOf(String className) {
    try {
      Class<?> clazz = Class.forName(className);
      if (clazz != null) {
        return newInstanceOf(clazz);
      }
    } catch (ClassNotFoundException e) {
    }
    return null;
  }

  public static <T> T newInstance(Class<T> clazz, Class[] parameterTypes, Object[] params) {
    return ReflectionHelpers.callConstructor(clazz, ClassParameter.fromComponentLists(parameterTypes, params));
  }

  public static <T> T directlyOn(T shadowedObject, Class<T> clazz) {
    return createProxy(shadowedObject, clazz);
  }

  private static <T> T createProxy(T shadowedObject, Class<T> clazz) {
    try {
      if (InvokeDynamic.ENABLED) {
        return PROXY_MAKER.createProxy(clazz, shadowedObject);
      } else {
        return ReflectionHelpers.callConstructor(clazz,
            ClassParameter.fromComponentLists(new Class[] { DirectObjectMarker.class, clazz },
                new Object[] { DirectObjectMarker.INSTANCE, shadowedObject }));
      }
    } catch (Exception e) {
      throw new RuntimeException("error creating direct call proxy for " + clazz, e);
    }
  }

  @SuppressWarnings("unchecked")
  public static <R> R directlyOn(Object shadowedObject, String clazzName, String methodName, ClassParameter... paramValues) {
    try {
      Class<Object> aClass = (Class<Object>) shadowedObject.getClass().getClassLoader().loadClass(clazzName);
      return directlyOn(shadowedObject, aClass, methodName, paramValues);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  public static <R, T> R directlyOn(T shadowedObject, Class<T> clazz, String methodName, ClassParameter... paramValues) {
    String directMethodName = directMethodName(methodName);
    return (R) ReflectionHelpers.callInstanceMethod(clazz, shadowedObject, directMethodName, paramValues);
  }

  public static <R, T> R directlyOn(Class<T> clazz, String methodName, ClassParameter... paramValues) {
    String directMethodName = directMethodName(methodName);
    return (R) ReflectionHelpers.callStaticMethod(clazz, directMethodName, paramValues);
  }

  public static <R> R invokeConstructor(Class<? extends R> clazz, R instance, ClassParameter... paramValues) {
    String directMethodName = directMethodName(ShadowConstants.CONSTRUCTOR_METHOD_NAME);
    return (R) ReflectionHelpers.callInstanceMethod(clazz, instance, directMethodName, paramValues);
  }

  public static String directMethodName(String methodName) {
    return ShadowConstants.ROBO_PREFIX + methodName;
  }
}
