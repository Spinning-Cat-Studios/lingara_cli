# frozen_string_literal: true

# The manifest as code. `lingara apps update` runs `bundle exec ruby
# manifest.rb` and uploads what this prints, so print nothing else.

require "lingara/apps"

manifest = Lingara::Apps.manifest
  .default_locale("en")
  .name({"en" => "{{display_name}}", "zh-Hans" => "{{display_name}}"})
  .description({"en" => "{{description}}", "zh-Hans" => "{{description}}"})
  # `lingara apps dev` points your dev version at a tunnel; set the real address before a version.
  .render_url("https://dev.invalid/")
  .slots(*{{slots}})
  .context(*{{context}})
# Raises Lingara::Apps::ManifestError naming the rule the upload would refuse.
puts manifest.to_json
