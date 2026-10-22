# frozen_string_literal: true

require_relative "base"

module Lingara
  module CLI
    module Registry
      # `canvas …` (16.10.26ad D1, D7).
      class Canvas < Base
        def self.words = %w[canvas]

        flag :name, "The canvas's name"
        flag :file, "The definition; absent, an empty one", banner: "definition.json"
        leaf :create, "Create a canvas, from a definition file or empty."

        leaf :list, "List your canvases."
        leaf :get, "Show one canvas."

        flag :name, "The new name", required: true
        leaf :update, "Rename a canvas."

        flag :file, "The definition", required: true, banner: "definition.json"
        leaf :draft, "Save a definition as the canvas's draft. Creates nothing else."

        flag :"dry-run", "Print what each object would do, and write nothing", type: :boolean
        flag :"print-commands", "Print the commands apply would run, and write nothing", type: :boolean
        method_exclusive :"dry-run", :"print-commands"
        leaf :apply, "Create the canvas's objects by running this CLI's own commands, then activate it."

        delete_flags
        leaf :delete, "Delete a canvas, or one version. The objects it made stay."
      end
    end
  end
end
