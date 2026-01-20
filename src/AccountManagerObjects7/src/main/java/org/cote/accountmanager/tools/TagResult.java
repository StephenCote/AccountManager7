package org.cote.accountmanager.tools;


import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Individual tag result with optional confidence score.
 */
public class TagResult {

    @JsonProperty("tag")
    private String tag;

    @JsonProperty("confidence")
    private Double confidence;

    public TagResult() {
    }

    public TagResult(String tag) {
        this.tag = tag;
    }

    public TagResult(String tag, Double confidence) {
        this.tag = tag;
        this.confidence = confidence;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    @Override
    public String toString() {
        if (confidence != null) {
            return String.format("%s (%.2f%%)", tag, confidence * 100);
        }
        return tag;
    }
}