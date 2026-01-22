package org.cote.accountmanager.tools;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;

/**
 * Client service for the Image Tagger API.
 * 
 * Example usage:
 * <pre>
 * ImageTaggerClient client = new ImageTaggerClient("http://localhost:8000");
 * ImageTagResponse response = client.tagImage(Path.of("/path/to/image.jpg"));
 * System.out.println("Tags: " + response.getTagStrings());
 * </pre>
 */
public class ImageTagUtil {
	private static final Logger logger = LogManager.getLogger(ImageTagUtil.class);
    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

	public ImageTagUtil() {
		this("http://localhost:8000");
	}
	
    public ImageTagUtil(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)  // Force HTTP/1.1
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        this.objectMapper = new ObjectMapper();
        this.objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /**
     * Tag an image file.
     *
     * @param imagePath Path to the image file
     * @return ImageTagResponse with tags and captions
     * @throws IOException if file cannot be read or API call fails
     * @throws InterruptedException if the request is interrupted
     */
    public ImageTagResponse tagImage(Path imagePath) throws IOException, InterruptedException {
        return tagImage(imagePath, 20);
    }

    /**
     * Tag an image file with custom max tags.
     *
     * @param imagePath Path to the image file
     * @param maxTags Maximum number of tags to return
     * @return ImageTagResponse with tags and captions
     * @throws IOException if file cannot be read or API call fails
     * @throws InterruptedException if the request is interrupted
     */
    public ImageTagResponse tagImage(Path imagePath, int maxTags) throws IOException, InterruptedException {
        byte[] imageBytes = Files.readAllBytes(imagePath);
        // Use basic encoder (no line breaks) - NOT getMimeEncoder() which adds line breaks
        String base64Image = Base64.getEncoder().encodeToString(imageBytes);
        return tagImageBase64(base64Image, maxTags);
    }

    /**
     * Tag a base64-encoded image.
     *
     * @param base64Image Base64-encoded image data
     * @return ImageTagResponse with tags and captions
     * @throws IOException if API call fails
     * @throws InterruptedException if the request is interrupted
     */
    public ImageTagResponse tagImageBase64(String base64Image) throws IOException, InterruptedException {
        return tagImageBase64(base64Image, 20);
    }

    /**
     * Tag a base64-encoded image with custom max tags.
     *
     * @param base64Image Base64-encoded image data
     * @param maxTags Maximum number of tags to return
     * @return ImageTagResponse with tags and captions
     * @throws IOException if API call fails
     * @throws InterruptedException if the request is interrupted
     */
    public ImageTagResponse tagImageBase64(String base64Image, int maxTags) throws IOException, InterruptedException {
        ImageTagRequest request = ImageTagRequest.builder()
                .imageBase64(base64Image)
                .maxTags(maxTags)
                .includeConfidence(true)
                .build();

        String requestBody = objectMapper.writeValueAsString(request);
        
        // Debug: uncomment to see what's being sent
        // System.out.println("Request body: " + requestBody.substring(0, Math.min(200, requestBody.length())) + "...");

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/tag"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("API returned status " + response.statusCode() + ": " + response.body());
        }

        return objectMapper.readValue(response.body(), ImageTagResponse.class);
    }

    /**
     * Check if the API is healthy.
     *
     * @return true if API is healthy and model is loaded
     * @throws IOException if API call fails
     * @throws InterruptedException if the request is interrupted
     */
    public boolean isHealthy() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/health"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() == 200 && response.body().contains("\"model_loaded\":true");
    }

    /**
     * Example main method demonstrating usage.
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java ImageTaggerClient <image_path> [api_url]");
            System.exit(1);
        }

        String imagePath = args[0];
        String apiUrl = args.length > 1 ? args[1] : "http://localhost:8000";

        ImageTagUtil client = new ImageTagUtil(apiUrl);

        System.out.println("Checking API health...");
        if (!client.isHealthy()) {
            System.err.println("API is not healthy or model not loaded");
            System.exit(1);
        }

        System.out.println("Tagging image: " + imagePath);
        ImageTagResponse response = client.tagImage(Path.of(imagePath));

        System.out.println("\nSuccess: " + response.getSuccess());
        System.out.println("Model: " + response.getModelId());
        System.out.println("Device: " + response.getDeviceUsed());

        System.out.println("\nTags:");
        for (int i = 0; i < response.getTags().size(); i++) {
            System.out.printf("  %2d. %s%n", i + 1, response.getTags().get(i).getTag());
        }

        if (response.getCaptions() != null && !response.getCaptions().isEmpty()) {
            System.out.println("\nCaptions:");
            for (String caption : response.getCaptions()) {
                System.out.println("  • " + caption);
            }
        }
    }
}
