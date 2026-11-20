package com.allanlewis.homelabwatchman.ai;

import com.allanlewis.homelabwatchman.plan.ProposedUpdate;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

/**
 * Only type in this package that touches Spring AI, and even this only
 * touches the provider-agnostic ChatClient. Swapping providers later is a
 * Gradle dependency + application.yml change, not a code change here.
 *
 * TODO: build the prompt from update.kind()/oldText()/newText()/releaseNotes,
 * call chatClient, parse into a PrText.
 */
@Component
public class ChatClientPrTextGenerator implements PrTextGenerator {

    private final ChatClient chatClient;

    public ChatClientPrTextGenerator(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    @Override
    public PrText generate(ProposedUpdate update, String releaseNotes) {
        throw new UnsupportedOperationException("not yet implemented");
    }
}
