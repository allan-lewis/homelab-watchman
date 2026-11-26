package com.allanlewis.homelabwatchman.ai;

import com.allanlewis.homelabwatchman.plan.ProposedUpdate;
import com.allanlewis.homelabwatchman.plan.UpdateKind;
import com.allanlewis.homelabwatchman.scan.Ecosystem;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * Only type in this package that touches Spring AI, and even this only
 * touches the provider-agnostic ChatClient. Swapping providers later is a
 * Gradle dependency + application.yml change, not a code change here.
 */
@Component
public class ChatClientPrTextGenerator implements PrTextGenerator {

    private static final Pattern DIGIT_GROUP = Pattern.compile("\\d+");

    private static final String SYSTEM_PROMPT = """
            You write terse, factual pull request descriptions for automated
            dependency updates in a home infrastructure repository.

            Rules:
            - Never invent specific changes, fixes, or features you have no
              evidence for. If no release notes are given, say only that
              none were available - do not guess at what changed, and do
              not characterize the change as "real", "significant", "minor",
              etc. unless the release notes actually say so.
            - Title: both kinds have their title overwritten afterward by
              deterministic code - write any reasonable title here, it
              won't be used.
            - Body: 2-4 plain sentences. State what changed, note the risk
              implied by the update kind (digest refresh = routine rebuild
              of the same version, low risk; version bump = a real version
              change, worth a quick look at any release notes given), and
              include the release notes link verbatim if one was given.
            - Compatibility: a "version delta" label (MAJOR/MINOR/PATCH) may
              be given alongside the old/new version - it's a weak signal
              from number position only, not a guarantee of what actually
              changed. Use it to calibrate tone (a MAJOR delta deserves a
              stronger "worth checking before merging" note than PATCH), but
              never claim a specific breaking change unless the release
              notes actually say so. If real release notes are given,
              mention anything in them that sounds like a compatibility or
              upgrade-step concern (config format changes, deprecated
              options, required migrations); if none are given, say
              plainly that compatibility can't be assessed without them.
            - A supplementary reference link (not per-version release
              notes) may be given instead of or alongside real notes - make
              clear in the body that it's general project documentation
              (e.g. a release/support-lifecycle policy page), not notes for
              this specific version.
            - If told this is a Terraform provider floor-constraint bump,
              say explicitly that merging this alone does not upgrade
              anything - the .terraform.lock.hcl stays on the old version
              until a human runs `terraform init -upgrade`, which merging
              this PR will now force to happen before any other terraform
              command works against this root module.
            """;

    private final ChatClient chatClient;

    public ChatClientPrTextGenerator(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.defaultSystem(SYSTEM_PROMPT).build();
    }

    @Override
    public PrText generate(ProposedUpdate update) {
        PrText text = chatClient.prompt()
                .user(buildUserPrompt(update))
                .call()
                .entity(PrText.class);

        return new PrText(buildTitle(update), text.body());
    }

    /**
     * Built deterministically, not left to the model - exactness matters
     * more than phrasing here, and both forms are now simple enough not to
     * need a model's judgment at all.
     *
     * DIGEST_REFRESH matches Renovate's own convention byte-for-byte except
     * the digest (confirmed live against sonarr PR #604).
     *
     * VERSION_BUMP deliberately drops the "from <old>" clause: the "old"
     * value is just whatever happens to be pinned in the file right now,
     * which isn't reliably "what's actually live in prod" once a chain of
     * superseded proposals has passed through - not worth tracking
     * accurately just to put it in a title. Only the target version is
     * ever meaningful here.
     */
    private String buildTitle(ProposedUpdate update) {
        if (update.kind() == UpdateKind.DIGEST_REFRESH) {
            return "chore(deps): update " + update.candidate().identifier() + ":" + update.oldBareVersion()
                    + " docker digest to " + update.newDigestShort();
        }
        return "chore(deps): bump " + update.candidate().identifier() + " to " + update.newBareVersion();
    }

    private String buildUserPrompt(ProposedUpdate update) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Dependency: ").append(update.candidate().identifier()).append('\n');
        prompt.append("Update kind: ").append(update.kind()).append('\n');
        prompt.append("Old: ").append(update.oldText()).append('\n');
        prompt.append("New: ").append(update.newText()).append('\n');

        if (update.kind() == UpdateKind.DIGEST_REFRESH) {
            prompt.append("Short digest: ").append(update.newDigestShort()).append('\n');
            prompt.append("Note: same version/tag, upstream rebuilt the image. "
                    + "No release notes apply.\n");
        } else if (update.candidate().ecosystem() == Ecosystem.TERRAFORM_PROVIDER) {
            prompt.append("Note: this is a Terraform provider floor-constraint bump, "
                    + "not a lock file update. No release notes apply.\n");
        } else {
            classifyVersionDelta(update.oldBareVersion(), update.newBareVersion())
                    .ifPresent(delta -> prompt.append("Version delta: ").append(delta)
                            .append(" (weak signal from number position only)\n"));
            appendReleaseInfo(prompt, update);
        }

        return prompt.toString();
    }

    private void appendReleaseInfo(StringBuilder prompt, ProposedUpdate update) {
        String url = update.releaseNotesUrl();
        String notes = update.releaseNotes();

        if (url == null && (notes == null || notes.isBlank())) {
            prompt.append("Release notes: none available for this dependency.\n");
            return;
        }
        if (url != null) {
            prompt.append("Release notes URL: ").append(url).append('\n');
        }
        if (notes != null && !notes.isBlank()) {
            prompt.append("Release notes:\n").append(notes).append('\n');
        } else {
            prompt.append("Release notes: none published for this tag - no body text, just the link above.\n");
        }
    }

    /**
     * Best-effort only: compares the numeric groups of old vs new in
     * order and reports the position of the first difference (0 = MAJOR,
     * 1 = MINOR, 2+ = PATCH). Returns empty when either side has no
     * numeric groups at all (e.g. a Terraform-style string this is never
     * actually called for, or some unexpected format) rather than guess.
     */
    static Optional<String> classifyVersionDelta(String oldVersion, String newVersion) {
        List<String> oldParts = numericGroups(oldVersion);
        List<String> newParts = numericGroups(newVersion);
        if (oldParts.isEmpty() || newParts.isEmpty()) {
            return Optional.empty();
        }

        int shared = Math.min(oldParts.size(), newParts.size());
        for (int i = 0; i < shared; i++) {
            if (!oldParts.get(i).equals(newParts.get(i))) {
                return Optional.of(i == 0 ? "MAJOR" : i == 1 ? "MINOR" : "PATCH");
            }
        }
        return oldParts.size() == newParts.size() ? Optional.empty() : Optional.of("PATCH");
    }

    private static List<String> numericGroups(String version) {
        Matcher matcher = DIGIT_GROUP.matcher(version);
        List<String> groups = new ArrayList<>();
        while (matcher.find()) {
            groups.add(matcher.group());
        }
        return groups;
    }
}
