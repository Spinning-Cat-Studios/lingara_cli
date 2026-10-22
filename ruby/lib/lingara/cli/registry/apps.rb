# frozen_string_literal: true

require_relative "base"

module Lingara
  module CLI
    module Registry
      # `apps versions …` (16.10.26ad D1).
      class AppsVersions < Base
        def self.words = %w[apps versions]

        leaf :list, "List an app's versions."
        leaf :publish, "Publish an approved version, or a private app's draft."
        leaf :withdraw, "Withdraw a version."
      end

      # `apps …` (16.10.26ad D1, D2; 16.10.26ae D1, D4, D6, D7, D9).
      class Apps < Base
        def self.words = %w[apps]

        MANIFEST_CONFLICTS = %i[lang bare dir no-register description port slot context].freeze
        RESET_CONFLICTS = %i[port tunnel render-url take-over write-env show-env].freeze

        flag :client, "The OAuth client's id (with --manifest)", banner: "id"
        flag :manifest, "The app's manifest file (with --client)", banner: "path"
        flag :lang, "The app's language", banner: "app-kit-lang"
        flag :bare, "The manifest as code in the app's language, and a handler on the kit's primitives", type: :boolean
        flag :dir, "Where to write the project; default ./<name>", banner: "path"
        flag :"no-register", "Scaffold only; register later with apps update", type: :boolean
        flag :description, "One sentence about the app", banner: "text"
        flag :port, "The port the app listens on; default 8787", banner: "n"
        flag :slot, "Where the app's card may appear; default home.side", repeatable: true, banner: "slot"
        flag :context, "A context slice the app receives", repeatable: true, banner: "slice"
        config_flag
        %i[lang bare dir].each { |other| method_exclusive :client, other }
        MANIFEST_CONFLICTS.each { |other| method_exclusive :manifest, other }
        leaf :create, "Scaffold an app project and register it; or, with --client and --manifest, register an app from a manifest file."

        flag :pinned, "List platform.toml's rows instead, reading nothing from the platform", type: :boolean
        config_flag
        leaf :list, "List your apps, or the apps platform.toml pins."

        leaf :get, "Show one app."

        flag :manifest, "The app's manifest file: makes a new version", banner: "path"
        flag :version, "Cut a draft version from the project's manifest", banner: "semver"
        config_flag
        method_exclusive :manifest, :version
        leaf :update, "Without --manifest, push the project's manifest to its dev version (--version cuts a draft); " \
          "with an id and --manifest, make a new version from a file."

        flag :port, "The port the app listens on; default the row's", banner: "n"
        flag :tunnel, "The tunnel to open; none needs --render-url"
        flag :"render-url", "With --tunnel none, the URL the dev version renders from", banner: "https-url"
        flag :"take-over", "End another terminal's session on this app first", type: :boolean
        flag :"write-env", "Also write the session's variables to this gitignored file, mode 0600", banner: "file"
        flag :"show-env", "List the names of the variables the app receives", type: :boolean
        flag :reset, "Return your install to the serving version, and do nothing else", type: :boolean
        config_flag
        RESET_CONFLICTS.each { |other| method_exclusive :reset, other }
        leaf :dev, "Run the app behind a tunnel and point your private dev version at it, until Ctrl+C."

        config_flag
        leaf :status, "Show where the app's dev version renders from: live, parked, or the serving version."

        flag :path, "The project directory; needed for a name", banner: "dir"
        config_flag
        leaf :pin, "Add an app to platform.toml (and its example), by id or by name."

        config_flag
        leaf :unpin, "Remove an app's row from platform.toml; the example changes by review."

        delete_flags
        leaf :delete, "Delete an app, or one of its versions."

        flag :version, "The version to submit", required: true, banner: "semver"
        leaf :submit, "Submit a version of an app for review, to make it public."

        desc "versions", "An app's versions."
        subcommand "versions", AppsVersions
      end
    end
  end
end
