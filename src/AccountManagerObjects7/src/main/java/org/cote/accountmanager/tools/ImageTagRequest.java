package org.cote.accountmanager.tools;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request model for the Image Tagger API.
 */
public class ImageTagRequest {

    @JsonProperty("image_base64")
    private String imageBase64;

    @JsonProperty("max_tags")
    private Integer maxTags;

    @JsonProperty("include_confidence")
    private Boolean includeConfidence;

    public ImageTagRequest() {
        this.maxTags = 20;
        this.includeConfidence = true;
    }

    public ImageTagRequest(String imageBase64) {
        this();
        this.imageBase64 = imageBase64;
    }

    public ImageTagRequest(String imageBase64, Integer maxTags, Boolean includeConfidence) {
        this.imageBase64 = imageBase64;
        this.maxTags = maxTags;
        this.includeConfidence = includeConfidence;
    }

    public String getImageBase64() {
        return imageBase64;
    }

    public void setImageBase64(String imageBase64) {
        this.imageBase64 = imageBase64;
    }

    public Integer getMaxTags() {
        return maxTags;
    }

    public void setMaxTags(Integer maxTags) {
        this.maxTags = maxTags;
    }

    public Boolean getIncludeConfidence() {
        return includeConfidence;
    }

    public void setIncludeConfidence(Boolean includeConfidence) {
        this.includeConfidence = includeConfidence;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String imageBase64;
        private Integer maxTags = 20;
        private Boolean includeConfidence = true;

        public Builder imageBase64(String imageBase64) {
            this.imageBase64 = imageBase64;
            return this;
        }

        public Builder maxTags(Integer maxTags) {
            this.maxTags = maxTags;
            return this;
        }

        public Builder includeConfidence(Boolean includeConfidence) {
            this.includeConfidence = includeConfidence;
            return this;
        }

        public ImageTagRequest build() {
            return new ImageTagRequest(imageBase64, maxTags, includeConfidence);
        }
    }
}