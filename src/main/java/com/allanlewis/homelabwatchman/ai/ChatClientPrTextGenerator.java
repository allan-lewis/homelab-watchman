package com.allanlewis.homelabwatchman.ai;

import com.allanlewis.homelabwatchman.plan.ProposedUpdate;
import com.allanlewis.homelabwatchman.plan.UpdateKind;
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
              - DIGEST_REFRESH: "chore(deps): refresh <name> image digest
                (<version> unchanged)" - never phrase a digest refresh as
                "from X to X", since the version didn't change.
            - Body: 2-4 plain sentences. State what changed, note the risk
              implied by the update kind (digest refresh = routine rebuild
              of the same version, low risk; version bump = a real version
              change, worth a quick look at any release notes given), and
              include the release notes link verbatim if one was given.
            """;

    private final ChatClient chatClient;

    public ChatClientPrTextGenerator(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.defaultSystem(SYSTEM_PROMPT).build();
    }

    @Override
    public PrText generate(ProposedUpdate update) {
        return chatClient.prompt()
                .user(buildUserPrompt(update))
                .call()
                .entity(PrText.class);
    }

    private String buildUserPrompt(ProposedUpdate update) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Dependency: ").append(update.candidate().identifier()).append('\n');
        prompt.append("Update kind: ").append(update.kind()).append('\n');
        prompt.append("Old: ").append(update.oldText()).append('\n');
        prompt.append("New: ").append(update.newText()).append('\n');

        if (update.kind() == UpdateKind.DIGEST_REFRESH) {
            prompt.append("Note: same version/tag, upstream rebuilt the image. "
                    + "No release notes apply.\n");
        } else if (update.releaseNotes() != null && !update.releaseNotes().isBlank()) {
            prompt.append("Release notes URL: ").append(update.releaseNotesUrl()).append('\n');
            prompt.append("Release notes:\n").append(update.releaseNotes()).append('\n');
        } else {
            prompt.append("Release notes: none available for this dependency.\n");
        }

        return prompt.toString();
    }
}
