package org.cote.accountmanager.tools;


import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Response model for the Image Tagger API.
 */
public class ImageTagResponse {

    @JsonProperty("success")
    private Boolean success;

    @JsonProperty("tags")
    private List<TagResult> tags;

    @JsonProperty("captions")
    private List<String> captions;

    @JsonProperty("nsfw")
    private NsfwResult nsfw;

    @JsonProperty("device_used")
    private String deviceUsed;

    @JsonProperty("model_id")
    private String modelId;

    public ImageTagResponse() {
    }

    public Boolean getSuccess() {
        return success;
    }

    public void setSuccess(Boolean success) {
        this.success = success;
    }

    public List<TagResult> getTags() {
        return tags;
    }

    public void setTags(List<TagResult> tags) {
        this.tags = tags;
    }

    public List<String> getCaptions() {
        return captions;
    }

    public void setCaptions(List<String> captions) {
        this.captions = captions;
    }

    public NsfwResult getNsfw() {
        return nsfw;
    }

    public void setNsfw(NsfwResult nsfw) {
        this.nsfw = nsfw;
    }

    public String getDeviceUsed() {
        return deviceUsed;
    }

    public void setDeviceUsed(String deviceUsed) {
        this.deviceUsed = deviceUsed;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    /**
     * Get just the tag strings as a list.
     */
    public List<String> getTagStrings() {
        if (tags == null) {
            return List.of();
        }
        return tags.stream()
                .map(TagResult::getTag)
                .toList();
    }

    @Override
    public String toString() {
        return "ImageTagResponse{" +
                "success=" + success +
                ", tags=" + tags +
                ", captions=" + captions +
                ", nsfw=" + nsfw +
                ", deviceUsed='" + deviceUsed + '\'' +
                ", modelId='" + modelId + '\'' +
                '}';
    }

    /**
     * Check if the image is flagged as NSFW.
     *
     * @return true if NSFW classification indicates adult content
     */
    public boolean isNsfw() {
        return nsfw != null && Boolean.TRUE.equals(nsfw.getIsNsfw());
    }

    /**
     * Check if the image is NSFW with confidence above threshold.
     *
     * @param threshold Confidence threshold (0.0 - 1.0)
     * @return true if NSFW and confidence is above threshold
     */
    public boolean isNsfwAboveThreshold(double threshold) {
        return nsfw != null && nsfw.isNsfwAboveThreshold(threshold);
    }
}