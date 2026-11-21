package de.aidocuments.settings;

/** Die auswählbaren Anbieter für das Sprachmodell samt dem, was die Anwendung über sie wissen muss. */
public enum LlmProvider {

    ANTHROPIC("Anthropic", Protocol.ANTHROPIC, "https://api.anthropic.com", true, true),
    OPENAI("OpenAI", Protocol.OPENAI, "https://api.openai.com/v1", true, true),
    MOONSHOT("Moonshot", Protocol.OPENAI, "https://api.moonshot.ai/v1", true, true),
    /** LM Studio auf demselben Rechner; aus einem Docker-Container heraus: http://host.docker.internal:1234/v1 */
    LM_STUDIO("LM Studio", Protocol.OPENAI, "http://localhost:1234/v1", false, false),
    /** Jeder Server, der die OpenAI-Schnittstelle (Chat Completions) spricht – Adresse frei wählbar. */
    OPENAI_COMPATIBLE(null, Protocol.OPENAI, "", false, false);

    /** Die Schnittstelle, über die der Anbieter angesprochen wird. */
    public enum Protocol {
        /** Anthropic Messages API */
        ANTHROPIC,
        /** OpenAI Chat Completions API */
        OPENAI
    }

    private final String label;
    private final Protocol protocol;
    private final String defaultBaseUrl;
    private final boolean requiresApiKey;
    private final boolean cloud;

    LlmProvider(String label, Protocol protocol, String defaultBaseUrl, boolean requiresApiKey, boolean cloud) {
        this.label = label;
        this.protocol = protocol;
        this.defaultBaseUrl = defaultBaseUrl;
        this.requiresApiKey = requiresApiKey;
        this.cloud = cloud;
    }

    /** Eigenname des Anbieters; {@code null}, wenn die Bezeichnung zu übersetzen ist (siehe {@link #labelKey()}). */
    public String label() {
        return label;
    }

    /** Schlüssel der Bezeichnung in messages.properties, sofern der Anbieter keinen Eigennamen hat. */
    public String labelKey() {
        return this == OPENAI_COMPATIBLE ? "ai.provider.openaiCompatible" : null;
    }

    public Protocol protocol() {
        return protocol;
    }

    public String defaultBaseUrl() {
        return defaultBaseUrl;
    }

    public boolean requiresApiKey() {
        return requiresApiKey;
    }

    /** Ein Dienst im Internet: Dokumentinhalte verlassen bei der Analyse den eigenen Rechner. */
    public boolean cloud() {
        return cloud;
    }
}
