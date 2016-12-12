package org.robolectric.internal;

import org.robolectric.annotation.Config;
import org.robolectric.manifest.AndroidManifest;
import org.robolectric.res.FileFsFile;
import org.robolectric.res.FsFile;
import org.robolectric.util.Logger;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.net.URL;

public class GradleManifestFactory implements ManifestFactory {
  @Override
  public ManifestIdentifier identify(Config config) {
    if (config.constants() == Void.class) {
      Logger.error("Field 'constants' not specified in @Config annotation");
      Logger.error("This is required when using Robolectric with Gradle!");
      throw new RuntimeException("No 'constants' field in @Config annotation!");
    }

    final String buildOutputDir = getBuildOutputDir(config);
    final String type = getType(config);
    final String flavor = getFlavor(config);
    final String abiSplit = getAbiSplit(config);
    final String packageName = config.packageName().isEmpty()
        ? config.constants().getPackage().getName()
        : config.packageName();

    final FileFsFile res;
    final FileFsFile assets;
    final FileFsFile manifest;

    if (FileFsFile.from(buildOutputDir, "data-binding-layout-out").exists()) {
      // Android gradle plugin 1.5.0+ puts the merged layouts in data-binding-layout-out.
      // https://github.com/robolectric/robolectric/issues/2143
      res = FileFsFile.from(buildOutputDir, "data-binding-layout-out", flavor, type);
    } else if (FileFsFile.from(buildOutputDir, "res", "merged").exists()) {
      // res/merged added in Android Gradle plugin 1.3-beta1
      res = FileFsFile.from(buildOutputDir, "res", "merged", flavor, type);
    } else if (FileFsFile.from(buildOutputDir, "res").exists()) {
      res = FileFsFile.from(buildOutputDir, "res", flavor, type);
    } else {
      res = FileFsFile.from(buildOutputDir, "bundles", flavor, type, "res");
    }

    if (FileFsFile.from(buildOutputDir, "assets").exists()) {
      assets = FileFsFile.from(buildOutputDir, "assets", flavor, type);
    } else {
      assets = FileFsFile.from(buildOutputDir, "bundles", flavor, type, "assets");
    }

    String manifestName = config.manifest();
    URL manifestUrl = getClass().getClassLoader().getResource(manifestName);
    if (manifestUrl != null && manifestUrl.getProtocol().equals("file")) {
      manifest = FileFsFile.from(manifestUrl.getPath());
    } else if (FileFsFile.from(buildOutputDir, "manifests", "full").exists()) {
      manifest = FileFsFile.from(buildOutputDir, "manifests", "full", flavor, abiSplit, type, manifestName);
    } else if (FileFsFile.from(buildOutputDir, "manifests", "aapt").exists()) {
      // Android gradle plugin 2.2.0+ can put library manifest files inside of "aapt" instead of "full"
      manifest = FileFsFile.from(buildOutputDir, "manifests", "aapt", flavor, abiSplit, type, manifestName);
    } else {
      manifest = FileFsFile.from(buildOutputDir, "bundles", flavor, abiSplit, type, manifestName);
    }

    return new ManifestIdentifier(manifest, res, assets, packageName, null);
  }

  @Override
  public AndroidManifest create(ManifestIdentifier manifestIdentifier) {
    FsFile manifestFile = manifestIdentifier.getManifestFile();
    FsFile resDir = manifestIdentifier.getResDir();
    FsFile assetDir = manifestIdentifier.getAssetDir();
    final String packageName = manifestIdentifier.getPackageName();

    Logger.debug("Robolectric assets directory: " + assetDir.getPath());
    Logger.debug("   Robolectric res directory: " + resDir.getPath());
    Logger.debug("   Robolectric manifest path: " + manifestFile.getPath());
    Logger.debug("    Robolectric package name: " + packageName);
    return new AndroidManifest(manifestFile, resDir, assetDir, packageName);
  }

  private static String getBuildOutputDir(Config config) {
    return config.buildDir() + File.separator + "intermediates";
  }

  private static String getType(Config config) {
    try {
      return ReflectionHelpers.getStaticField(config.constants(), "BUILD_TYPE");
    } catch (Throwable e) {
      return null;
    }
  }

  private static String getFlavor(Config config) {
    try {
      return ReflectionHelpers.getStaticField(config.constants(), "FLAVOR");
    } catch (Throwable e) {
      return null;
    }
  }

  private static String getAbiSplit(Config config) {
    try {
      return config.abiSplit();
    } catch (Throwable e) {
      return null;
    }
  }
}
