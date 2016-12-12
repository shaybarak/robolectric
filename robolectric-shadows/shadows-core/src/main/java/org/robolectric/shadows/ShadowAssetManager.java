package org.robolectric.shadows;

import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.ParcelFileDescriptor;
import android.util.AttributeSet;
import android.util.TypedValue;
import org.jetbrains.annotations.NotNull;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.HiddenApi;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.annotation.Resetter;
import org.robolectric.res.*;
import org.robolectric.res.builder.ResourceParser;
import org.robolectric.res.builder.XmlBlock;
import org.robolectric.util.Logger;
import org.robolectric.util.ReflectionHelpers;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.List;

import static android.os.Build.VERSION_CODES.KITKAT_WATCH;
import static android.os.Build.VERSION_CODES.LOLLIPOP;
import static org.robolectric.RuntimeEnvironment.castNativePtr;
import static org.robolectric.Shadows.shadowOf;

/**
 * Shadow for {@link android.content.res.AssetManager}.
 */
@Implements(AssetManager.class)
public final class ShadowAssetManager {
  public static final int STYLE_NUM_ENTRIES = 6;
  public static final int STYLE_TYPE = 0;
  public static final int STYLE_DATA = 1;
  public static final int STYLE_ASSET_COOKIE = 2;
  public static final int STYLE_RESOURCE_ID = 3;
  public static final int STYLE_CHANGING_CONFIGURATIONS = 4;
  public static final int STYLE_DENSITY = 5;

  boolean strictErrors = false;

  private static long nextInternalThemeId = 1000;
  private static final Map<Long, NativeTheme> nativeThemes = new HashMap<>();
  private ResourceLoader resourceLoader;

  class NativeTheme {
    private ThemeStyleSet themeStyleSet;

    public NativeTheme(ThemeStyleSet themeStyleSet) {
      this.themeStyleSet = themeStyleSet;
    }

    public ShadowAssetManager getShadowAssetManager() {
      return ShadowAssetManager.this;
    }
  }

  @RealObject
  AssetManager realObject;

  private void convertAndFill(AttributeResource attribute, TypedValue outValue, String qualifiers, boolean resolveRefs) {
    if (attribute.isNull()) {
      outValue.type = TypedValue.TYPE_NULL;
      outValue.data = TypedValue.DATA_NULL_UNDEFINED;
      return;
    } else if (attribute.isEmpty()) {
      outValue.type = TypedValue.TYPE_NULL;
      outValue.data = TypedValue.DATA_NULL_EMPTY;
      return;
    }

    // short-circuit Android caching of loaded resources cuz our string positions don't remain stable...
    outValue.assetCookie = Converter.getNextStringCookie();

    // TODO: Handle resource and style references
    if (attribute.isStyleReference()) {
      return;
    }

    while (attribute.isResourceReference()) {
      Integer resourceId;
      ResName resName = attribute.getResourceReference();
      if (attribute.getReferenceResId() != null) {
        resourceId = attribute.getReferenceResId();
      } else {
        resourceId = resourceLoader.getResourceIndex().getResourceId(resName);
      }

      if (resourceId == null) {
        throw new Resources.NotFoundException("unknown resource " + resName);
      }
      outValue.type = TypedValue.TYPE_REFERENCE;
      if (!resolveRefs) {
          // Just return the resourceId if resolveRefs is false.
          outValue.data = resourceId;
          return;
      }

      outValue.resourceId = resourceId;

      TypedResource dereferencedRef = resourceLoader.getValue(resName, qualifiers);

      if (dereferencedRef == null) {
        Logger.strict("couldn't resolve %s from %s", resName.getFullyQualifiedName(), attribute);

        if (resName.type.equals("id")) {
          return;
        } else if (resName.type.equals("layout")) {
          return; // resourceId is good enough, right?
        } else if (resName.type.equals("dimen")) {
          return;
        } else if (resName.type.equals("transition")) {
          return;
        } else if (resName.type.equals("interpolator")) {
          return;
        } else if (resName.type.equals("menu")) {
          return;
        } else if (resName.type.equals("raw")) {
          return;
        } else if (DrawableResourceLoader.isStillHandledHere(resName.type)) {
          // wtf. color and drawable references reference are all kinds of stupid.
          TypedResource drawableResource = resourceLoader.getValue(resName, qualifiers);
          if (drawableResource == null) {
            throw new Resources.NotFoundException("can't find file for " + resName);
          } else {
            outValue.type = TypedValue.TYPE_STRING;
            outValue.data = 0;
            outValue.assetCookie = Converter.getNextStringCookie();
            outValue.string = (CharSequence) drawableResource.getData();
            return;
          }
        } else {
          throw new RuntimeException("huh? " + resName);
        }
      } else {
        if (dereferencedRef.isFile()) {
          outValue.type = TypedValue.TYPE_STRING;
          outValue.data = 0;
          outValue.assetCookie = Converter.getNextStringCookie();
          outValue.string = dereferencedRef.asString();
          return;
        } else if (dereferencedRef.getData() instanceof String) {
          attribute = new AttributeResource(attribute.resName, dereferencedRef.asString(), resName.packageName);
          if (attribute.isResourceReference()) {
            continue;
          }
          if (resolveRefs) {
            Converter.getConverter(dereferencedRef.getResType()).fillTypedValue(attribute.value, outValue);
            return;
          }
        }
      }
      break;
    }

    if (attribute.isNull()) {
      outValue.type = TypedValue.TYPE_NULL;
      return;
    }

    TypedResource attrTypeData = resourceLoader.getValue(attribute.resName, qualifiers);
    if (attrTypeData != null) {
      AttrData attrData = (AttrData) attrTypeData.getData();
      String format = attrData.getFormat();
      String[] types = format.split("\\|");
      for (String type : types) {
        if ("reference".equals(type)) continue; // already handled above
        Converter converter = Converter.getConverterFor(attrData, type);

        if (converter != null) {
          if (converter.fillTypedValue(attribute.value, outValue)) {
            return;
          }
        }
      }
    } else {
      /**
       * In cases where the runtime framework doesn't know this attribute, e.g: viewportHeight (added in 21) on a
       * KitKat runtine, then infer the attribute type from the value.
       *
       * TODO: When we are able to pass the SDK resources from the build environment then we can remove this
       * and replace the NullResourceLoader with simple ResourceLoader that only parses attribute type information.
       */
      ResType resType = ResType.inferFromValue(attribute.value);
      Converter.getConverter(resType).fillTypedValue(attribute.value, outValue);
    }
  }

  public void __constructor__() {
    resourceLoader = RuntimeEnvironment.getAppResourceLoader();
  }

  public void __constructor__(boolean isSystem) {
    resourceLoader = isSystem ? RuntimeEnvironment.getSystemResourceLoader() : RuntimeEnvironment.getAppResourceLoader();
  }

  public ResourceLoader getResourceLoader() {
    return resourceLoader;
  }

  @HiddenApi @Implementation
  public CharSequence getResourceText(int ident) {
    TypedResource value = getAndResolve(ident, RuntimeEnvironment.getQualifiers(), true);
    if (value == null) return null;
    return (CharSequence) value.getData();
  }

  @HiddenApi @Implementation
  public CharSequence getResourceBagText(int ident, int bagEntryId) {
    throw new UnsupportedOperationException(); // todo
  }

  @HiddenApi @Implementation
  public String[] getResourceStringArray(final int id) {
    CharSequence[] resourceTextArray = getResourceTextArray(id);
    if (resourceTextArray == null) return null;
    String[] strings = new String[resourceTextArray.length];
    for (int i = 0; i < strings.length; i++) {
      strings[i] = resourceTextArray[i].toString();
    }
    return strings;
  }

  @HiddenApi @Implementation
  public int getResourceIdentifier(String name, String defType, String defPackage) {
    ResName resName = ResName.qualifyResName(name, defPackage, defType);

    // If the resource does not exist then return 0, otherwise ResourceIndex.getResourceId() will generate a placeholder.
    if (!ResName.ID_TYPE.equals(resName.type)
        && !resourceLoader.hasValue(resName, RuntimeEnvironment.getQualifiers())) {
      return 0;
    }

    Integer resourceId = resourceLoader.getResourceIndex().getResourceId(resName);
    return resourceId == null ? 0 : resourceId;
  }

  @HiddenApi @Implementation
  public boolean getResourceValue(int ident, int density, TypedValue outValue, boolean resolveRefs) {
    TypedResource value = getAndResolve(ident, RuntimeEnvironment.getQualifiers(), resolveRefs);
    if (value == null) return false;

    getConverter(value).fillTypedValue(value.getData(), outValue);
    return true;
  }

  private Converter getConverter(TypedResource value) {
    if (value instanceof FileTypedResource.Image
        || (value instanceof FileTypedResource
            && ((FileTypedResource) value).getFsFile().getName().endsWith(".xml"))) {
      return new Converter.FromFilePath();
    }
    return Converter.getConverter(value.getResType());
  }

  @HiddenApi @Implementation
  public CharSequence[] getResourceTextArray(int resId) {
    TypedResource value = getAndResolve(resId, RuntimeEnvironment.getQualifiers(), true);
    if (value == null) return null;
    TypedResource[] items = getConverter(value).getItems(value);
    CharSequence[] charSequences = new CharSequence[items.length];
    for (int i = 0; i < items.length; i++) {
      TypedResource typedResource = resolve(items[i], RuntimeEnvironment.getQualifiers(), resId);
      charSequences[i] = getConverter(typedResource).asCharSequence(typedResource);
    }
    return charSequences;
  }

  @HiddenApi @Implementation(maxSdk = KITKAT_WATCH)
  public boolean getThemeValue(int themePtr, int ident, TypedValue outValue, boolean resolveRefs) {
    return getThemeValue((long) themePtr, ident, outValue, resolveRefs);
  }

  @HiddenApi @Implementation(minSdk = LOLLIPOP)
  public boolean getThemeValue(long themePtr, int ident, TypedValue outValue, boolean resolveRefs) {
    ResourceIndex resourceIndex = resourceLoader.getResourceIndex();
    ResName resName = resourceIndex.getResName(ident);

    ThemeStyleSet themeStyleSet = getNativeTheme(themePtr).themeStyleSet;
    AttributeResource attrValue = themeStyleSet.getAttrValue(resName);
    while(attrValue != null && attrValue.isStyleReference()) {
      ResName attrResName = attrValue.getStyleReference();
      if (attrValue.resName.equals(attrResName)) {
          Logger.info("huh... circular reference for %s?", attrResName.getFullyQualifiedName());
          return false;
      }
      attrValue = themeStyleSet.getAttrValue(attrResName);
    }
    if (attrValue != null) {
      convertAndFill(attrValue, outValue, RuntimeEnvironment.getQualifiers(), resolveRefs);
      return true;
    }
    return false;
  }

  @HiddenApi @Implementation
  public void ensureStringBlocks() {
  }

  @Implementation
  public final InputStream open(String fileName) throws IOException {
    return ShadowApplication.getInstance().getAppManifest().getAssetsDirectory().join(fileName).getInputStream();
  }

  @Implementation
  public final InputStream open(String fileName, int accessMode) throws IOException {
    return ShadowApplication.getInstance().getAppManifest().getAssetsDirectory().join(fileName).getInputStream();
  }

  @Implementation
  public final AssetFileDescriptor openFd(String fileName) throws IOException {
    File file = new File(ShadowApplication.getInstance().getAppManifest().getAssetsDirectory().join(fileName).getPath());
    ParcelFileDescriptor parcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    return new AssetFileDescriptor(parcelFileDescriptor, 0, file.length());
  }

  @Implementation
  public final String[] list(String path) throws IOException {
    FsFile file = ShadowApplication.getInstance().getAppManifest().getAssetsDirectory().join(path);
    if (file.isDirectory()) {
      return file.listFileNames();
    }
    return new String[0];
  }

  @HiddenApi @Implementation
  public final InputStream openNonAsset(int cookie, String fileName, int accessMode) throws IOException {
    final ResName resName = qualifyFromNonAssetFileName(fileName);

    final FileTypedResource typedResource =
        (FileTypedResource) resourceLoader.getValue(resName, RuntimeEnvironment.getQualifiers());

    if (typedResource == null) {
      throw new IOException("Unable to find resource for " + fileName);
    }

    if (accessMode == AssetManager.ACCESS_STREAMING) {
      return typedResource.getFsFile().getInputStream();
    } else {
      return new ByteArrayInputStream(typedResource.getFsFile().getBytes());
    }
  }

  private ResName qualifyFromNonAssetFileName(String fileName) {
    if (fileName.startsWith("jar:")) {
      // Must remove "jar:" prefix, or else qualifyFromFilePath fails on Windows
      return ResName.qualifyFromFilePath("android", fileName.replaceFirst("jar:", ""));
    } else {
      return ResName.qualifyFromFilePath(ShadowApplication.getInstance().getAppManifest().getPackageName(), fileName);
    }
  }

  @HiddenApi @Implementation
  public final AssetFileDescriptor openNonAssetFd(int cookie, String fileName) throws IOException {
    throw new UnsupportedOperationException();
  }

  @Implementation
  public final XmlResourceParser openXmlResourceParser(int cookie, String fileName) throws IOException {
    return ResourceParser.create(fileName, "fixme", "fixme", null);
  }

  public XmlResourceParser loadXmlResourceParser(int resId, String type) throws Resources.NotFoundException {
    ResName resName = getResName(resId);
    ResName resolvedResName = resolveResName(resName, RuntimeEnvironment.getQualifiers());
    if (resolvedResName == null) {
      throw new RuntimeException("couldn't resolve " + resName.getFullyQualifiedName());
    }
    resName = resolvedResName;

    ResourceLoader resourceLoader = ResourceIds.isFrameworkResource(resId) ? RuntimeEnvironment.getSystemResourceLoader() : RuntimeEnvironment.getCompiletimeResourceLoader();
    XmlBlock block = resourceLoader.getXml(resName, RuntimeEnvironment.getQualifiers());
    if (block == null) {
      throw new Resources.NotFoundException(resName.getFullyQualifiedName());
    }

    return ResourceParser.from(block, resName.packageName, resourceLoader);
  }

  @HiddenApi @Implementation
  public int addAssetPath(String path) {
    return 1;
  }

  @HiddenApi @Implementation
  public boolean isUpToDate() {
    return true;
  }

  @HiddenApi @Implementation
  public void setLocale(String locale) {
  }

  @Implementation
  public String[] getLocales() {
    return new String[0]; // todo
  }

  @HiddenApi @Implementation
  public void setConfiguration(int mcc, int mnc, String locale,
                 int orientation, int touchscreen, int density, int keyboard,
                 int keyboardHidden, int navigation, int screenWidth, int screenHeight,
                 int smallestScreenWidthDp, int screenWidthDp, int screenHeightDp,
                 int screenLayout, int uiMode, int majorVersion) {
  }

  @HiddenApi @Implementation
  public int[] getArrayIntResource(int resId) {
    TypedResource value = getAndResolve(resId, RuntimeEnvironment.getQualifiers(), true);
    if (value == null) return null;
    TypedResource[] items = getConverter(value).getItems(value);
    int[] ints = new int[items.length];
    for (int i = 0; i < items.length; i++) {
      TypedResource typedResource = resolve(items[i], RuntimeEnvironment.getQualifiers(), resId);
      ints[i] = getConverter(typedResource).asInt(typedResource);
    }
    return ints;
  }

  @HiddenApi @Implementation
  public Number createTheme() {
    synchronized (nativeThemes) {
      long nativePtr = nextInternalThemeId++;
      nativeThemes.put(nativePtr, new NativeTheme(new ThemeStyleSet()));
      return castNativePtr(nativePtr);
    }
  }

  private static NativeTheme getNativeTheme(Resources.Theme theme) {
    return getNativeTheme(shadowOf(theme).getNativePtr());
  }

  private static NativeTheme getNativeTheme(long themePtr) {
    NativeTheme nativeTheme;
    synchronized (nativeThemes) {
      nativeTheme = nativeThemes.get(themePtr);
    }
    if (nativeTheme == null) {
      throw new RuntimeException("no theme " + themePtr + " found in AssetManager");
    }
    return nativeTheme;
  }

  @HiddenApi @Implementation(maxSdk = KITKAT_WATCH)
  public void releaseTheme(int themePtr) {
    releaseTheme((long) themePtr);
  }

  @HiddenApi @Implementation(minSdk = LOLLIPOP)
  public void releaseTheme(long themePtr) {
    synchronized (nativeThemes) {
      nativeThemes.remove(themePtr);
    }
  }

  @HiddenApi @Implementation(maxSdk = KITKAT_WATCH)
  public static void applyThemeStyle(int themePtr, int styleRes, boolean force) {
    applyThemeStyle((long) themePtr, styleRes, force);
  }

  @HiddenApi @Implementation(minSdk = LOLLIPOP)
  public static void applyThemeStyle(long themePtr, int styleRes, boolean force) {
    NativeTheme nativeTheme = getNativeTheme(themePtr);
    Style style = nativeTheme.getShadowAssetManager().resolveStyle(styleRes, null);
    nativeTheme.themeStyleSet.apply(style, force);
}

  @HiddenApi @Implementation(maxSdk = KITKAT_WATCH)
  public static void copyTheme(int destPtr, int sourcePtr) {
    copyTheme((long) destPtr, (long) sourcePtr);
  }

  @HiddenApi @Implementation(minSdk = LOLLIPOP)
  public static void copyTheme(long destPtr, long sourcePtr) {
    NativeTheme destNativeTheme = getNativeTheme(destPtr);
    NativeTheme sourceNativeTheme = getNativeTheme(sourcePtr);
    destNativeTheme.themeStyleSet = sourceNativeTheme.themeStyleSet.copy();
  }

  /////////////////////////

  Style resolveStyle(int resId, Style themeStyleSet) {
    return resolveStyle(getResName(resId), themeStyleSet);
  }

  private Style resolveStyle(@NotNull ResName themeStyleName, Style themeStyleSet) {
    TypedResource themeStyleResource = resourceLoader.getValue(themeStyleName, RuntimeEnvironment.getQualifiers());
    if (themeStyleResource == null) return null;
    StyleData themeStyleData = (StyleData) themeStyleResource.getData();
    if (themeStyleSet == null) {
      themeStyleSet = new ThemeStyleSet();
    }
    return new StyleResolver(resourceLoader, shadowOf(AssetManager.getSystem()).getResourceLoader(), themeStyleData, themeStyleSet, themeStyleName, RuntimeEnvironment.getQualifiers());
  }

  private TypedResource getAndResolve(int resId, String qualifiers, boolean resolveRefs) {
    TypedResource value = resourceLoader.getValue(resId, qualifiers);
    if (resolveRefs) {
      value = resolve(value, qualifiers, resId);
    }

    // todo: make the drawable loader put stuff into the normal spot...
    String resourceTypeName = getResourceTypeName(resId);
    if (value == null && DrawableResourceLoader.isStillHandledHere(resourceTypeName)) {
      FileTypedResource typedResource = (FileTypedResource) resourceLoader.getValue(resId, qualifiers);
      return new TypedResource<>(typedResource.getFsFile(), ResType.FILE, typedResource.getXmlContext());
    }

    // todo: gross. this is so resources.getString(R.layout.foo) works for ABS.
    if (value == null && "layout".equals(resourceTypeName)) {
      throw new UnsupportedOperationException("ugh, this doesn't work still?");
    }

    return value;
  }

  TypedResource resolve(TypedResource value, String qualifiers, int resId) {
    return resolveResourceValue(value, qualifiers, resId);
  }

  public ResName resolveResName(ResName resName, String qualifiers) {
    TypedResource value = resourceLoader.getValue(resName, qualifiers);
    return resolveResource(value, qualifiers, resName);
  }

  // todo: DRY up #resolveResource vs #resolveResourceValue
  private ResName resolveResource(TypedResource value, String qualifiers, ResName resName) {
    while (value != null && value.isReference()) {
      String s = value.asString();
      if (AttributeResource.isNull(s) || AttributeResource.isEmpty(s)) {
        value = null;
      } else {
        String refStr = s.substring(1).replace("+", "");
        resName = ResName.qualifyResName(refStr, resName);
        value = resourceLoader.getValue(resName, qualifiers);
      }
    }

    return resName;
  }

  private TypedResource resolveResourceValue(TypedResource value, String qualifiers, ResName resName) {
    while (value != null && value.isReference()) {
      String s = value.asString();
      if (AttributeResource.isNull(s) || AttributeResource.isEmpty(s)) {
        value = null;
      } else {
        String refStr = s.substring(1).replace("+", "");
        resName = ResName.qualifyResName(refStr, resName);
        value = resourceLoader.getValue(resName, qualifiers);
      }
    }

    return value;
  }

  public TypedResource resolveResourceValue(TypedResource value, String qualifiers, int resId) {
    ResName resName = getResName(resId);
    return resolveResourceValue(value, qualifiers, resName);
  }

  private TypedValue buildTypedValue(AttributeSet set, int resId, int defStyleAttr, Style themeStyleSet, int defStyleRes) {
    /*
     * When determining the final value of a particular attribute, there are four inputs that come into play:
     *
     * 1. Any attribute values in the given AttributeSet.
     * 2. The style resource specified in the AttributeSet (named "style").
     * 3. The default style specified by defStyleAttr and defStyleRes
     * 4. The base values in this theme.
     */
    Style defStyleFromAttr = null;
    Style defStyleFromRes = null;
    Style styleAttrStyle = null;

    if (defStyleAttr != 0) {
      // Load the theme attribute for the default style attributes. E.g., attr/buttonStyle
      ResName defStyleName = getResName(defStyleAttr);

      // Load the style for the default style attribute. E.g. "@style/Widget.Robolectric.Button";
      AttributeResource defStyleAttribute = themeStyleSet.getAttrValue(defStyleName);
      if (defStyleAttribute != null) {
        while (defStyleAttribute.isStyleReference()) {
          AttributeResource other = themeStyleSet.getAttrValue(defStyleAttribute.getStyleReference());
          if (other == null) {
            throw new RuntimeException("couldn't dereference " + defStyleAttribute);
          }
          defStyleAttribute = other;
        }

        if (defStyleAttribute.isResourceReference()) {
          ResName defStyleResName = defStyleAttribute.getResourceReference();
          defStyleFromAttr = resolveStyle(defStyleResName, themeStyleSet);
        }
      }
    }

    if (set != null && set.getStyleAttribute() != 0) {
      ResName styleAttributeResName = getResName(set.getStyleAttribute());
      while (styleAttributeResName.type.equals("attr")) {
        AttributeResource attrValue = themeStyleSet.getAttrValue(styleAttributeResName);
        if (attrValue == null) {
          throw new RuntimeException(
                  "no value for " + styleAttributeResName.getFullyQualifiedName()
                      + " in " + themeStyleSet);
        }
        if (attrValue.isResourceReference()) {
          styleAttributeResName = attrValue.getResourceReference();
        } else if (attrValue.isStyleReference()) {
          styleAttributeResName = attrValue.getStyleReference();
        }
      }
      styleAttrStyle = resolveStyle(styleAttributeResName, themeStyleSet);
    }

    if (defStyleRes != 0) {
      ResName resName = getResName(defStyleRes);
      if (resName.type.equals("attr")) {
        AttributeResource attributeValue = findAttributeValue(defStyleRes, set, styleAttrStyle, defStyleFromAttr, defStyleFromAttr, themeStyleSet);
        if (attributeValue != null) {
          if (attributeValue.isStyleReference()) {
            resName = themeStyleSet.getAttrValue(attributeValue.getStyleReference()).getResourceReference();
          } else if (attributeValue.isResourceReference()) {
            resName = attributeValue.getResourceReference();
          }
        }
      }
      defStyleFromRes = resolveStyle(resName, themeStyleSet);
    }

    AttributeResource attribute = findAttributeValue(resId, set, styleAttrStyle, defStyleFromAttr, defStyleFromRes, themeStyleSet);
    while (attribute != null && attribute.isStyleReference()) {
      ResName otherAttrName = attribute.getStyleReference();
      if (attribute.resName.equals(otherAttrName)) {
        Logger.info("huh... circular reference for %s?", attribute.resName.getFullyQualifiedName());
        return null;
      }
      ResName resName = resourceLoader.getResourceIndex().getResName(resId);

      AttributeResource otherAttr = themeStyleSet.getAttrValue(otherAttrName);
      if (otherAttr == null) {
        strictError("no such attr %s in %s while resolving value for %s", attribute.value, themeStyleSet, resName.getFullyQualifiedName());
        attribute = null;
      } else {
        attribute = new AttributeResource(resName, otherAttr.value, otherAttr.contextPackageName);
      }
    }

    if (attribute == null || attribute.isNull()) {
      return null;
    } else {
      TypedValue typedValue = new TypedValue();
      convertAndFill(attribute, typedValue, RuntimeEnvironment.getQualifiers(), true);
      return typedValue;
    }
  }

  private void strictError(String message, Object... args) {
    if (strictErrors) {
      throw new RuntimeException(String.format(message, args));
    } else {
      Logger.strict(message, args);
    }
  }

  TypedArray attrsToTypedArray(Resources resources, AttributeSet set, int[] attrs, int defStyleAttr, long nativeTheme, int defStyleRes) {
    CharSequence[] stringData = new CharSequence[attrs.length];
    int[] data = new int[attrs.length * ShadowAssetManager.STYLE_NUM_ENTRIES];
    int[] indices = new int[attrs.length + 1];
    int nextIndex = 0;

    Style themeStyleSet = nativeTheme == 0
        ? new EmptyStyle()
        : getNativeTheme(nativeTheme).themeStyleSet;

    for (int i = 0; i < attrs.length; i++) {
      int offset = i * ShadowAssetManager.STYLE_NUM_ENTRIES;

      TypedValue typedValue = buildTypedValue(set, attrs[i], defStyleAttr, themeStyleSet, defStyleRes);
      if (typedValue != null) {
        //noinspection PointlessArithmeticExpression
        data[offset + ShadowAssetManager.STYLE_TYPE] = typedValue.type;
        data[offset + ShadowAssetManager.STYLE_DATA] = typedValue.type == TypedValue.TYPE_STRING ? i : typedValue.data;
        data[offset + ShadowAssetManager.STYLE_ASSET_COOKIE] = typedValue.assetCookie;
        data[offset + ShadowAssetManager.STYLE_RESOURCE_ID] = typedValue.resourceId;
        data[offset + ShadowAssetManager.STYLE_CHANGING_CONFIGURATIONS] = typedValue.changingConfigurations;
        data[offset + ShadowAssetManager.STYLE_DENSITY] = typedValue.density;
        stringData[i] = typedValue.string;

        indices[nextIndex + 1] = i;
        nextIndex++;
      }
    }

    indices[0] = nextIndex;

    TypedArray typedArray = ShadowTypedArray.create(resources, attrs, data, indices, nextIndex, stringData);
    if (set != null) {
      shadowOf(typedArray).positionDescription = set.getPositionDescription();
    }
    return typedArray;
  }

  private AttributeResource findAttributeValue(int resId, AttributeSet attributeSet, Style styleAttrStyle, Style defStyleFromAttr, Style defStyleFromRes, @NotNull Style themeStyleSet) {
    if (attributeSet != null) {
      for (int i = 0; i < attributeSet.getAttributeCount(); i++) {
        if (attributeSet.getAttributeNameResource(i) == resId && attributeSet.getAttributeValue(i) != null) {
          String defaultPackageName = ResourceIds.isFrameworkResource(resId) ? "android" : RuntimeEnvironment.application.getPackageName();
          ResName resName = ResName.qualifyResName(attributeSet.getAttributeName(i), defaultPackageName, "attr");
          Integer referenceResId = null;
          if (AttributeResource.isResourceReference(attributeSet.getAttributeValue(i))) {
            referenceResId = attributeSet.getAttributeResourceValue(i, -1);
          }

          return new AttributeResource(resName, attributeSet.getAttributeValue(i), "fixme!!!", referenceResId);
        }
      }
    }

    ResName attrName = resourceLoader.getResourceIndex().getResName(resId);
    if (attrName == null) return null;

    if (styleAttrStyle != null) {
      AttributeResource attribute = styleAttrStyle.getAttrValue(attrName);
      if (attribute != null) {
        return attribute;
      }
    }

    // else if attr in defStyleFromAttr, use its value
    if (defStyleFromAttr != null) {
      AttributeResource attribute = defStyleFromAttr.getAttrValue(attrName);
      if (attribute != null) {
        return attribute;
      }
    }

    if (defStyleFromRes != null) {
      AttributeResource attribute = defStyleFromRes.getAttrValue(attrName);
      if (attribute != null) {
        return attribute;
      }
    }

    // else if attr in theme, use its value
    return themeStyleSet.getAttrValue(attrName);
  }

  @NotNull private ResName getResName(int id) {
    ResName resName = resourceLoader.getResourceIndex().getResName(id);
    if (resName == null) {
      List<String> packages = new ArrayList<>(resourceLoader.getResourceIndex().getPackages());
      Collections.sort(packages);
      throw new Resources.NotFoundException("Unable to find resource ID #0x" + Integer.toHexString(id)
          + " in packages " + packages);
    }
    return resName;
  }

  @Implementation
  public String getResourceName(int resid) {
    return getResName(resid).getFullyQualifiedName();
  }

  @Implementation
  public String getResourcePackageName(int resid) {
    return getResName(resid).packageName;
  }

  @Implementation
  public String getResourceTypeName(int resid) {
    return getResName(resid).type;
  }

  @Implementation
  public String getResourceEntryName(int resid) {
   return getResName(resid).name;
  }

  @Resetter
  public static void reset() {
    ReflectionHelpers.setStaticField(AssetManager.class, "sSystem", null);
  }
}
