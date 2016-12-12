package org.robolectric.res;

import org.junit.Test;
import static org.robolectric.util.TestUtil.*;
import static org.assertj.core.api.Assertions.*;

public class PackageResourceLoaderTest {

  @Test
  public void shouldLoadDrawableXmlResources() throws Exception {
    PackageResourceLoader loader = new PackageResourceLoader(testResources());
    TypedResource value = loader.getValue(new ResName("org.robolectric", "drawable", "rainbow"), "");
    assertThat(value).isNotNull();
    assertThat(value.getResType()).isEqualTo(ResType.DRAWABLE);
    assertThat(value.isFile()).isTrue();
    assertThat((String) value.getData()).contains("rainbow.xml");
  }

  @Test
  public void shouldLoadDrawableBitmapResources() throws Exception {
    PackageResourceLoader loader = new PackageResourceLoader(testResources());
    TypedResource value = loader.getValue(new ResName("org.robolectric", "drawable", "an_image"), "");
    assertThat(value).isNotNull();
    assertThat(value.getResType()).isEqualTo(ResType.DRAWABLE);
    assertThat(value.isFile()).isTrue();
    assertThat((String) value.getData()).contains("an_image.png");
  }

  @Test
  public void shouldLoadDrawableBitmapResourcesDefinedByItemTag() throws Exception {
    PackageResourceLoader loader = new PackageResourceLoader(testResources());
    TypedResource value = loader.getValue(new ResName("org.robolectric", "drawable", "example_item_drawable"), "");
    assertThat(value).isNotNull();
    assertThat(value.getResType()).isEqualTo(ResType.DRAWABLE);
    assertThat(value.isReference()).isTrue();
    assertThat((String) value.getData()).isEqualTo("@drawable/an_image");
  }

  @Test
  public void shouldLoadResourcesFromGradleOutputDirectories() {
    PackageResourceLoader loader = new PackageResourceLoader(gradleAppResources());
    TypedResource value = loader.getValue(org.robolectric.gradleapp.R.string.from_gradle_output, "");
    assertThat(value).describedAs("String from gradle output is not loaded").isNotNull();
    assertThat(value.asString()).isEqualTo("string example taken from gradle output directory");
  }

  @Test
  public void shouldLoadDimenResourcesFromGradleOutputDirectoriesDefinedByDimenTag() {
    PackageResourceLoader loader = new PackageResourceLoader(gradleAppResources());
    TypedResource value = loader.getValue(org.robolectric.gradleapp.R.dimen.example_dimen, "");
    assertThat(value).describedAs("Dimen from gradle output is not loaded").isNotNull();
    assertThat(value.asString()).isEqualTo("8dp");
  }

  @Test
  public void shouldLoadDimenResourcesFromGradleOutputDirectoriesDefinedByItemTag() {
    PackageResourceLoader loader = new PackageResourceLoader(gradleAppResources());
    TypedResource value = loader.getValue(org.robolectric.gradleapp.R.dimen.example_item_dimen, "");
    assertThat(value).describedAs("Item dimen from gradle output is not loaded").isNotNull();
    assertThat(value.asString()).isEqualTo("3.14");
  }

  @Test
  public void shouldLoadStringResourcesFromGradleOutputDirectoriesDefinedByItemTag() {
    PackageResourceLoader loader = new PackageResourceLoader(gradleAppResources());
    TypedResource value = loader.getValue(org.robolectric.gradleapp.R.string.item_from_gradle_output, "");
    assertThat(value).describedAs("Item string from gradle output is not loaded").isNotNull();
    assertThat(value.asString()).isEqualTo("3.14");
  }

  @Test
  public void shouldLoadColorResourcesFromGradleOutputDirectoriesDefinedByColorTag() {
    PackageResourceLoader loader = new PackageResourceLoader(gradleAppResources());
    TypedResource value = loader.getValue(org.robolectric.gradleapp.R.color.example_color, "");
    assertThat(value).describedAs("Color from gradle output is not loaded").isNotNull();
    assertThat(value.asString()).isEqualTo("#00FF00FF");
  }

  @Test
  public void shouldLoadColorResourcesFromGradleOutputDirectoriesDefinedByItemTag() {
    PackageResourceLoader loader = new PackageResourceLoader(gradleAppResources());
    TypedResource value = loader.getValue(org.robolectric.gradleapp.R.color.example_item_color, "");
    assertThat(value).describedAs("Item color from gradle output is not loaded").isNotNull();
    assertThat(value.asString()).isEqualTo("1.0");
  }
}
