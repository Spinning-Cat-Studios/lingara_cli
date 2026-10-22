# frozen_string_literal: true

module Lingara
  module CLI
    # The delete that asks twice (ADR 16.10.26ad D5; contract B3, invariant
    # I1). One function, called by every dangerous command, so no command
    # can carry a weaker copy.
    #
    # - `-y --confirm <email>` replaces both prompts, interactive or not, and
    #   sends no read for a prompt; the email is checked before anything is
    #   destroyed. Neither flag alone is confirmation.
    # - Otherwise a non-interactive run is refused **before any request**,
    #   the read and any token exchange included.
    # - Interactive: the command's read fills the prompt, `y`/`yes`
    #   continues, then the typed email must match. There is no retry loop.
    module Confirm
      # What the y/n question names: the object, its cascade, its pins.
      Prompt = Struct.new(:kind, :name, :id, :consequence, :pinned_by)

      # Trimmed and ASCII-lowercased: the comparison's rule and the form the
      # request carries.
      def self.canonical(email) = email.to_s.strip.tr("A-Z", "a-z")

      # `dev@example.com` is `d…@example.com`; a one-character local part is
      # `…@example.com`.
      def self.hint(email)
        local, _, domain = email.partition("@")
        first = (local.length > 1) ? local[0] : ""
        "#{first}…@#{domain}"
      end

      def self.not_confirmed(id) = Fail.new(Exit::NOT_CONFIRMED, id, Assets.message(id))

      # `confirm_yes_no`, the consequence, and `confirm_pinned_by` when pinned.
      def self.question(prompt)
        lines = [Assets.message("confirm_yes_no", kind: prompt.kind, name: prompt.name, id: prompt.id), prompt.consequence]
        lines << Assets.message("confirm_pinned_by", count: prompt.pinned_by.to_s) if prompt.pinned_by.positive?
        lines.join("\n")
      end

      # The ritual; the canonical email the destructive request then carries.
      # `ports` answers `interactive?`, `read_line`, `prompt(text)` and
      # `email`; `read` is the command's read, returning a `Prompt`.
      def self.destructive(ports, yes:, confirm:, &read)
        return flagged(ports, confirm) if yes && !confirm.nil?
        raise not_confirmed("confirm_non_tty_refused") unless ports.interactive?

        ports.prompt("#{question(read.call)} [y/N] ")
        answer = ports.read_line.to_s.strip.downcase
        raise not_confirmed("confirm_declined") unless %w[y yes].include?(answer)

        email = canonical(ports.email)
        ports.prompt("#{Assets.message("confirm_type_email", hint: hint(email))} ")
        raise not_confirmed("confirm_email_mismatch") unless canonical(ports.read_line) == email

        email
      end

      def self.flagged(ports, confirm)
        email = canonical(ports.email)
        raise not_confirmed("confirm_email_mismatch") unless canonical(confirm) == email

        email
      end
    end
  end
end
