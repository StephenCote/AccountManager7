package org.cote.accountmanager.tools;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * NSFW classification result.
 */
public class NsfwResult {

    @JsonProperty("is_nsfw")
    private Boolean isNsfw;

    @JsonProperty("confidence")
    private Double confidence;

    @JsonProperty("label")
    private String label;

    public NsfwResult() {
    }

    public Boolean getIsNsfw() {
        return isNsfw;
    }

    public void setIsNsfw(Boolean isNsfw) {
        this.isNsfw = isNsfw;
    }

    public Double getConfidence() {
        return confidence;
    }

    public void setConfidence(Double confidence) {
        this.confidence = confidence;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    /**
     * Check if content is NSFW with confidence above threshold.
     *
     * @param threshold Confidence threshold (0.0 - 1.0)
     * @return true if NSFW and confidence is above threshold
     */
    public boolean isNsfwAboveThreshold(double threshold) {
        return Boolean.TRUE.equals(isNsfw) && confidence != null && confidence >= threshold;
    }

    @Override
    public String toString() {
        return String.format("%s (%.2f%%)", label, confidence != null ? confidence * 100 : 0);
    }
}