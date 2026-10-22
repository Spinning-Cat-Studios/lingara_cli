package app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.getlingara.apps.Manifest;
import com.getlingara.apps.model.AppSlotName;
import com.getlingara.apps.model.ContextSliceKind;
import java.util.List;
import java.util.Map;

/**
 * The manifest as code. {@code lingara apps update} runs {@code gradle --quiet manifest} and
 * uploads what this prints, so print nothing else.
 */
public final class ManifestMain {
  private ManifestMain() {}

  public static void main(String[] args) {
    ObjectMapper json = new ObjectMapper();
    AppSlotName[] slots = json.convertValue({{slots}}, AppSlotName[].class);
    ContextSliceKind[] context = json.convertValue({{context}}, ContextSliceKind[].class);
    String manifest =
        Manifest.manifest()
            .defaultLocale("en")
            .name(Map.of("en", "{{display_name}}", "zh-Hans", "{{display_name}}"))
            .description(Map.of("en", "{{description}}", "zh-Hans", "{{description}}"))
            // `lingara apps dev` points your dev version at a tunnel; set the real address before a version.
            .renderUrl("https://dev.invalid/")
            .slots(slots)
            .context(context)
            .build() // refuses what the upload would, naming the rule
            .toJson();
    System.out.println(manifest);
  }
}
