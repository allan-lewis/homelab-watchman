package com.allanlewis.homelabwatchman.ai;

import com.allanlewis.homelabwatchman.plan.ProposedUpdate;

/**
 * Writes PR title/body for a proposed update. Deterministic code has
 * already decided *that* this update should happen and *what* its
 * UpdateKind is; this only writes the text, informed by that kind and any
 * fetched release notes. Implementations must depend only on Spring AI's
 * ChatClient abstraction, never a provider-specific type, so the provider
 * (currently Anthropic/Claude Haiku) stays swappable via config.
 */
public interface PrTextGenerator {

    PrText generate(ProposedUpdate update, String releaseNotes);
}
