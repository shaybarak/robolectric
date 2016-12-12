package org.robolectric.res;

public class TypedResource<T> {
  private final T data;
  private final ResType resType;
  private final String qualifiers;
  private final XmlLoader.XmlContext xmlContext;

  public TypedResource(T data, ResType resType, XmlLoader.XmlContext xmlContext) {
    this.data = data;
    this.resType = resType;
    this.xmlContext = xmlContext;

    String qualifiers = xmlContext.getQualifiers();
    this.qualifiers = qualifiers == null ? "--" : "-" + qualifiers + "-";
  }

  public T getData() {
    return data;
  }

  public ResType getResType() {
    return resType;
  }

  public String getQualifiers() {
    return qualifiers;
  }

  public XmlLoader.XmlContext getXmlContext() {
    return xmlContext;
  }

  public String asString() {
    return ((String) getData());
  }

  public boolean isFile() {
    return false;
  }

  public boolean isReference() {
    Object data = getData();
    if (data instanceof String) {
      String s = (String) data;
      return !s.isEmpty() && s.charAt(0) == '@';
    }
    return false;
  }

  @Override public String toString() {
    return getClass().getSimpleName() + "{" +
        "data=" + data +
        ", resType=" + resType +
        ", xmlContext=" + xmlContext +
        '}';
  }
}
