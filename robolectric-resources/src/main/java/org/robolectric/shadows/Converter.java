package org.robolectric.shadows;

import android.content.res.Resources;
import android.util.TypedValue;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.res.*;
import org.robolectric.util.Logger;
import org.robolectric.util.Util;

public class Converter<T> {
  private static int nextStringCookie = 0xbaaa5;

  synchronized static int getNextStringCookie() {
    return nextStringCookie++;
  }

  static Converter getConverterFor(AttrData attrData, String type) {
    switch (type) {
      case "enum":
        return new EnumConverter(attrData);
      case "flag":
        return new FlagConverter(attrData);
      case "boolean":
        return new FromBoolean();
      case "color":
        return new FromColor();
      case "dimension":
        return new FromDimen();
      case "float":
        return new FromFloat();
      case "integer":
        return new FromInt();
      case "string":
        return new FromCharSequence();
      case "fraction":
        return new FromFraction();
      default:
        throw new UnsupportedOperationException("Type not supported: " + type);
    }
  }

  // TODO: Handle 'anim' resources
  public static Converter getConverter(ResType resType) {
    switch (resType) {
      case ATTR_DATA:
        return new FromAttrData();
      case BOOLEAN:
        return new FromBoolean();
      case CHAR_SEQUENCE:
        return new FromCharSequence();
      case COLOR:
      case DRAWABLE:
        return new FromColor();
      case COLOR_STATE_LIST:
      case LAYOUT:
        return new FromFilePath();
      case DIMEN:
        return new FromDimen();
      case FILE:
        return new FromFile();
      case FLOAT:
        return new FromFloat();
      case INTEGER:
        return new FromInt();
      case FRACTION:
        return new FromFraction();
      case CHAR_SEQUENCE_ARRAY:
      case INTEGER_ARRAY:
        return new FromArray();
      default:
        throw new UnsupportedOperationException(resType.name());
    }
  }

  public CharSequence asCharSequence(TypedResource typedResource) {
    throw cantDo("asCharSequence");
  }

  public int asInt(TypedResource typedResource) {
    throw cantDo("asInt");
  }

  public TypedResource[] getItems(TypedResource typedResource) {
    throw cantDo("getItems");
  }

  public boolean fillTypedValue(T data, TypedValue typedValue) {
    return false;
  }

  private UnsupportedOperationException cantDo(String operation) {
    return new UnsupportedOperationException(getClass().getName() + " doesn't support " + operation);
  }

  public static class FromAttrData extends Converter<AttrData> {
    @Override
    public CharSequence asCharSequence(TypedResource typedResource) {
      return typedResource.asString();
    }

    @Override
    public boolean fillTypedValue(AttrData data, TypedValue typedValue) {
      typedValue.type = TypedValue.TYPE_STRING;
      return false;
    }
  }

  public static class FromCharSequence extends Converter<String> {
    @Override
    public CharSequence asCharSequence(TypedResource typedResource) {
      return typedResource.asString().trim();
    }

    @Override
    public int asInt(TypedResource typedResource) {
      return convertInt(typedResource.asString().trim());
    }

    @Override
    public boolean fillTypedValue(String data, TypedValue typedValue) {
      typedValue.type = TypedValue.TYPE_STRING;
      typedValue.data = 0;
      typedValue.assetCookie = getNextStringCookie();
      typedValue.string = data;
      return true;
    }
  }

  public static class FromColor extends Converter<String> {
    @Override
    public boolean fillTypedValue(String data, TypedValue typedValue) {
      try {
        typedValue.type = TypedValue.TYPE_INT_COLOR_ARGB8;
        typedValue.data = ResourceHelper.getColor(data);
        typedValue.assetCookie = 0;
        typedValue.string = null;
        return true;
      } catch (NumberFormatException nfe) {
        return false;
      }
    }

    @Override
    public int asInt(TypedResource typedResource) {
      return ResourceHelper.getColor(typedResource.asString().trim());
    }
  }



  public static class FromFilePath extends Converter<String> {
    @Override
    public boolean fillTypedValue(String data, TypedValue typedValue) {
      typedValue.type = TypedValue.TYPE_STRING;
      typedValue.data = 0;
      typedValue.string = data;
      typedValue.assetCookie = getNextStringCookie();
      return true;
    }
  }

  public static class FromArray extends Converter {
    @Override
    public TypedResource[] getItems(TypedResource typedResource) {
      return (TypedResource[]) typedResource.getData();
    }
  }

  private static class FromInt extends Converter<String> {
    @Override
    public boolean fillTypedValue(String data, TypedValue typedValue) {
      try {
        typedValue.type = TypedValue.TYPE_INT_HEX;
        typedValue.data = convertInt(data);
        typedValue.assetCookie = 0;
        typedValue.string = null;
        return true;
      } catch (NumberFormatException nfe) {
        return false;
      }
    }

    @Override
    public int asInt(TypedResource typedResource) {
      return convertInt(typedResource.asString().trim());
    }
  }

  private static class FromFraction extends Converter<String> {
    @Override
    public boolean fillTypedValue(String data, TypedValue typedValue) {
      return ResourceHelper.parseFloatAttribute(null, data, typedValue, false);
    }
  }

  private static class FromFile extends Converter<FsFile> {
    @Override
    public boolean fillTypedValue(FsFile data, TypedValue typedValue) {
      typedValue.type = TypedValue.TYPE_STRING;
      typedValue.data = 0;
      typedValue.string = data.getPath();
      typedValue.assetCookie = getNextStringCookie();
      return true;
    }
  }

  private static class FromFloat extends Converter<String> {
    @Override
    public boolean fillTypedValue(String data, TypedValue typedValue) {
      return ResourceHelper.parseFloatAttribute(null, data, typedValue, false);
    }
  }

  private static class FromBoolean extends Converter<String> {
    @Override
    public boolean fillTypedValue(String data, TypedValue typedValue) {
      typedValue.type = TypedValue.TYPE_INT_BOOLEAN;
      typedValue.assetCookie = 0;
      typedValue.string = null;

      if ("true".equalsIgnoreCase(data)) {
        typedValue.data = 1;
      } else if ("false".equalsIgnoreCase(data)) {
        typedValue.data = 0;
      } else {
        try {
          int intValue = Integer.parseInt(data);
          typedValue.data = intValue == 0 ? 0 : 1;
        } catch (NumberFormatException e) {
          return false;
        }
      }
      return true;
    }
  }

  private static class FromDimen extends Converter<String> {
    @Override
    public boolean fillTypedValue(String data, TypedValue typedValue) {
      return ResourceHelper.parseFloatAttribute(null, data, typedValue, false);
    }
  }

  private static int convertInt(String rawValue) {
    try {
      // Decode into long, because there are some large hex values in the android resource files
      // (e.g. config_notificationsBatteryLowARGB = 0xFFFF0000 in sdk 14).
      // Integer.decode() does not support large, i.e. negative values in hex numbers.
      // try parsing decimal number
      return (int) Long.parseLong(rawValue);
    } catch (NumberFormatException nfe) {
      // try parsing hex number
      return Long.decode(rawValue).intValue();
    }
  }

  private static class EnumConverter extends EnumOrFlagConverter {
    public EnumConverter(AttrData attrData) {
      super(attrData);
    }

    @Override
    public boolean fillTypedValue(String data, TypedValue typedValue) {
      try {
        typedValue.type = TypedValue.TYPE_INT_HEX;
        typedValue.data = findValueFor(data);
        typedValue.assetCookie = 0;
        typedValue.string = null;
        return true;
      } catch (Exception e) {
        return false;
      }
    }
  }

  private static class FlagConverter extends EnumOrFlagConverter {
    public FlagConverter(AttrData attrData) {
      super(attrData);
    }

    @Override
    public boolean fillTypedValue(String data, TypedValue typedValue) {
      try {
        int flags = 0;
        for (String key : data.split("\\|")) {
          flags |= findValueFor(key);
        }

        typedValue.type = TypedValue.TYPE_INT_HEX;
        typedValue.data = flags;
        typedValue.assetCookie = 0;
        typedValue.string = null;
        return true;
      } catch (Exception e) {
        return false;
      }
    }
  }

  private static class EnumOrFlagConverter extends Converter<String> {
    private final AttrData attrData;

    public EnumOrFlagConverter(AttrData attrData) {
      this.attrData = attrData;
    }

    protected int findValueFor(String key) {
      String valueFor = attrData.getValueFor(key);
      if (valueFor == null) {
        // Maybe they have passed in the value directly, rather than the name.
        if (attrData.isValue(key)) {
          valueFor = key;
        } else {
          throw new RuntimeException("no value found for " + key);
        }
      }
      return Util.parseInt(valueFor);
    }
  }
}
