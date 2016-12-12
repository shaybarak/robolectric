package org.robolectric.res;

import javax.xml.xpath.XPathExpressionException;

public class ColorResourceLoader extends XpathResourceXmlLoader {
  private final ResBunch data;

  public ColorResourceLoader(ResBunch data) {
    super("/selector");
    this.data = data;
  }

  @Override
  protected void processNode(String name, XmlNode xmlNode, XmlContext xmlContext) throws XPathExpressionException {
    TypedResource value = new FileTypedResource(xmlContext.getXmlFile(), ResType.COLOR_STATE_LIST, xmlContext);
    data.put("color", xmlContext.getXmlFile().getBaseName(), value);
  }
}
