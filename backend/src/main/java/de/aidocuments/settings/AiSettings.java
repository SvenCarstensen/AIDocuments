package de.aidocuments.settings;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** In der Oberfläche gespeicherte KI-Einstellungen (genau ein Datensatz mit der ID "ai"). */
@Document(collection = "settings")
public class AiSettings {

    public static final String ID = "ai";

    @Id
    private String id = ID;
    private LlmProvider provider;
    private String baseUrl;
    private String apiKeyEncrypted;
    private String model;
    private String effort;
    private String outputLanguage;
    private Boolean ocr;
    /** Scans zuerst mit der eingebauten Texterkennung lesen, dann von der KI erschließen lassen. */
    private Boolean quickOcrFirst;
    private Boolean compatibilityMode;

    public LlmProvider getProvider() {
        return provider;
    }

    public void setProvider(LlmProvider provider) {
        this.provider = provider;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKeyEncrypted() {
        return apiKeyEncrypted;
    }

    public void setApiKeyEncrypted(String apiKeyEncrypted) {
        this.apiKeyEncrypted = apiKeyEncrypted;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getEffort() {
        return effort;
    }

    public void setEffort(String effort) {
        this.effort = effort;
    }

    public String getOutputLanguage() {
        return outputLanguage;
    }

    public void setOutputLanguage(String outputLanguage) {
        this.outputLanguage = outputLanguage;
    }

    public Boolean getCompatibilityMode() {
        return compatibilityMode;
    }

    public void setCompatibilityMode(Boolean compatibilityMode) {
        this.compatibilityMode = compatibilityMode;
    }

    public Boolean getQuickOcrFirst() {
        return quickOcrFirst;
    }

    public void setQuickOcrFirst(Boolean quickOcrFirst) {
        this.quickOcrFirst = quickOcrFirst;
    }

    public Boolean getOcr() {
        return ocr;
    }

    public void setOcr(Boolean ocr) {
        this.ocr = ocr;
    }
}
