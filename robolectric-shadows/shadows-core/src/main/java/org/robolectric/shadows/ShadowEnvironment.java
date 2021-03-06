package org.robolectric.shadows;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.HashMap;
import android.os.Environment;
import org.robolectric.annotation.Resetter;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Implementation;
import org.robolectric.util.TempDirectory;

import static android.os.Build.VERSION_CODES;
import static android.os.Build.VERSION_CODES.KITKAT;
import static android.os.Build.VERSION_CODES.LOLLIPOP;

/**
 * Shadow for {@link android.os.Environment}.
 */
@Implements(Environment.class)
public class ShadowEnvironment {
  private static String externalStorageState = Environment.MEDIA_REMOVED;
  private static final Map<File, Boolean> STORAGE_EMULATED = new HashMap<>();
  private static final Map<File, Boolean> STORAGE_REMOVABLE = new HashMap<>();

  static Path EXTERNAL_CACHE_DIR;
  static Path EXTERNAL_FILES_DIR;

  @Implementation
  public static String getExternalStorageState() {
    return externalStorageState;
  }

  /**
   * Non-Android accessor. Sets the return value of {@link #getExternalStorageState()}.
   *
   * @param externalStorageState Value to return from {@link #getExternalStorageState()}.
   */
  public static void setExternalStorageState(String externalStorageState) {
    ShadowEnvironment.externalStorageState = externalStorageState;
  }

  @Implementation
  public static File getExternalStorageDirectory() {
    if (!exists(EXTERNAL_CACHE_DIR)) EXTERNAL_CACHE_DIR = TempDirectory.create();
    return EXTERNAL_CACHE_DIR.toFile();
  }

  @Implementation
  public static File getExternalStoragePublicDirectory(String type) {
    if (!exists(EXTERNAL_FILES_DIR)) EXTERNAL_FILES_DIR = TempDirectory.create();
    if (type == null) return EXTERNAL_FILES_DIR.toFile();
    Path path = EXTERNAL_FILES_DIR.resolve(type);
    try {
      Files.createDirectories(path);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return path.toFile();
  }

  @Resetter
  public static void reset() {
    TempDirectory.destroy(EXTERNAL_CACHE_DIR);
    TempDirectory.destroy(EXTERNAL_FILES_DIR);

    EXTERNAL_CACHE_DIR = null;
    EXTERNAL_FILES_DIR = null;

    STORAGE_EMULATED.clear();
    STORAGE_REMOVABLE.clear();
  }

  private static boolean exists(Path path) {
    return path != null && Files.exists(path);
  }

  @Implementation
  public static boolean isExternalStorageRemovable() {
    final Boolean exists = STORAGE_REMOVABLE.get(getExternalStorageDirectory());
    return exists != null ? exists : false;
  }

  @Implementation(minSdk = KITKAT)
  public static String getStorageState(File path) {
    return externalStorageState;
  }

  @Implementation(minSdk = LOLLIPOP)
  public static String getExternalStorageState(File path) {
    return externalStorageState;
  }

  @Implementation(minSdk = LOLLIPOP)
  public static boolean isExternalStorageRemovable(File path) {
    final Boolean exists = STORAGE_REMOVABLE.get(path);
    return exists != null ? exists : false;
  }

  @Implementation(minSdk = LOLLIPOP)
  public static boolean isExternalStorageEmulated(File path) {
    final Boolean emulated = STORAGE_EMULATED.get(path);
    return emulated != null ? emulated : false;
  }

  /**
   * Non-Android accessor. Sets the "isRemovable" flag of a particular file.
   *
   * @param file Target file.
   * @param isRemovable True if the filesystem is removable.
   */
  public static void setExternalStorageRemovable(File file, boolean isRemovable) {
    STORAGE_REMOVABLE.put(file, isRemovable);
  }

  /**
   * Non-Android accessor. Sets the "isEmulated" flag of a particular file.
   *
   * @param file Target file.
   * @param isEmulated True if the filesystem is emulated.
   */
  public static void setExternalStorageEmulated(File file, boolean isEmulated) {
    STORAGE_EMULATED.put(file, isEmulated);
  }
}
