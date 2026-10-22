import { manifest, type AppSlotName, type ContextSliceKind } from "@lingara/apps";

const slots: AppSlotName[] = {{slots}};
const context: ContextSliceKind[] = {{context}};

// The manifest as code. `lingara apps update` runs `npm run --silent manifest`
// and uploads what this prints, so print nothing else.
const json = manifest()
  .defaultLocale("en")
  .name({ en: "{{display_name}}", "zh-Hans": "{{display_name}}" })
  .description({ en: "{{description}}", "zh-Hans": "{{description}}" })
  // `lingara apps dev` points your dev version at a tunnel; set the real address before a version.
  .renderUrl("https://dev.invalid/")
  .slots(...slots)
  .context(...context)
  .toJson(); // Throws ManifestError naming the rule an upload would refuse.

process.stdout.write(`${json}\n`);
