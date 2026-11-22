package com.allanlewis.homelabwatchman.ai;

import com.allanlewis.homelabwatchman.plan.ProposedUpdate;
import com.allanlewis.homelabwatchman.plan.UpdateKind;
import com.allanlewis.homelabwatchman.scan.Ecosystem;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * Only type in this package that touches Spring AI, and even this only
 * touches the provider-agnostic ChatClient. Swapping providers later is a
 * Gradle dependency + application.yml change, not a code change here.
 */
@Component
public class ChatClientPrTextGenerator implements PrTextGenerator {

    private static final String SYSTEM_PROMPT = """
            You write terse, factual pull request descriptions for automated
            dependency updates in a home infrastructure repository.

            Rules:
            - Never invent specific changes, fixes, or features you have no
              evidence for. If no release notes are given, say only that
              none were available - do not guess at what changed, and do
              not characterize the change as "real", "significant", "minor",
              etc. unless the release notes actually say so.
            - Title, at most 72 characters:
              - VERSION_BUMP: "chore(deps): bump <name> from <old> to <new>"
              - DIGEST_REFRESH: title is overwritten afterward by
                deterministic code to match Renovate's exact convention -
                write any reasonable title here, it won't be used.
            - Body: 2-4 plain sentences. State what changed, note the risk
              implied by the update kind (digest refresh = routine rebuild
              of the same version, low risk; version bump = a real version
              change, worth a quick look at any release notes given), and
              include the release notes link verbatim if one was given.
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

        if (update.kind() == UpdateKind.DIGEST_REFRESH) {
            // Built deterministically, not left to the model, so the only
            // difference from Renovate's own digest-refresh title is the
            // digest itself - confirmed byte-for-byte against its current
            // "chore(deps): update <name>:<tag> docker digest to <short>"
            // convention (observed live on sonarr PR #604).
            String title = "chore(deps): update " + update.candidate().identifier() + ":" + update.oldBareVersion()
                    + " docker digest to " + update.newDigestShort();
            return new PrText(title, text.body());
        }

        return text;
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
        } else if (update.releaseNotes() != null && !update.releaseNotes().isBlank()) {
            prompt.append("Release notes URL: ").append(update.releaseNotesUrl()).append('\n');
            prompt.append("Release notes:\n").append(update.releaseNotes()).append('\n');
        } else {
            prompt.append("Release notes: none available for this dependency.\n");
        }

        return prompt.toString();
    }
}
