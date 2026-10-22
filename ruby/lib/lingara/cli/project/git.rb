# frozen_string_literal: true

module Lingara
  module CLI
    module Project
      # 16.10.26ae D4's two git questions: is a file tracked (a warning), and
      # does git ignore a path that would hold secrets (a refusal). Outside a
      # work tree, or with no `git` on PATH, there is no index to commit a
      # file into, so both answers are the permissive ones.
      module Git
        def self.git?(dir, *args)
          system("git", "-C", dir, *args, out: File::NULL, err: File::NULL, in: File::NULL) == true
        end

        def self.in_work_tree?(dir) = git?(dir, "rev-parse", "--is-inside-work-tree")

        # Whether git tracks `path`; false outside a work tree.
        def self.tracked?(path)
          dir = File.dirname(path)
          in_work_tree?(dir) && git?(dir, "ls-files", "--error-unmatch", "--", File.basename(path))
        end

        # Whether git ignores `path`; true outside a work tree.
        def self.ignored?(path)
          dir = File.dirname(path)
          !in_work_tree?(dir) || git?(dir, "check-ignore", "-q", "--", File.basename(path))
        end
      end
    end
  end
end
