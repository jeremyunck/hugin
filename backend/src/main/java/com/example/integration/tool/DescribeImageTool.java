package com.example.integration.tool;

import com.example.agent.model.ChatAttachment;
import com.example.agent.tool.LocalTool;
import com.example.agent.tool.ToolContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lets the (text-only) agent "see" an image the user attached by forwarding it to a vision-capable
 * model over OpenRouter and returning a textual description.
 *
 * <p>The default chat model ({@code openai/gpt-oss-120b}) cannot process the {@code image_url}
 * content parts a user message carries, so without this tool the agent answers questions about an
 * attached photo by guessing. This tool reads the attachment from the per-request
 * {@link ToolContext#attachments()} — the model never has to (and cannot) pass the raw image data —
 * and makes a dedicated {@code POST /chat/completions} call to a vision model, returning its
 * description for the agent to use in its answer.
 */
@Component
public class DescribeImageTool implements LocalTool {

    private static final Logger log = LoggerFactory.getLogger(DescribeImageTool.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BASE_MS = 1_000;

    private static final String SYSTEM_PROMPT = "You are a precise visual analyst. Describe the "
            + "provided image(s) in clear, concrete detail so that someone who cannot see them can "
            + "understand exactly what is shown. Note the main subject, setting, notable objects, "
            + "text, colors, and anything else relevant to the user's request. If the user asked a "
            + "specific question, answer it directly using only what is visible.";

    private final String apiKey;
    private final String endpoint;
    private final String model;
    private final int maxTokens;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Autowired
    public DescribeImageTool(
            @Value("${OPEN_ROUTER_API_KEY:}") String apiKey,
            @Value("${image.describe.endpoint:https://openrouter.ai/api/v1/chat/completions}") String endpoint,
            @Value("${image.describe.model:openai/gpt-4o-mini}") String model,
            @Value("${image.describe.max-tokens:1024}") int maxTokens,
            ObjectMapper objectMapper) {
        this(apiKey, endpoint, model, maxTokens, objectMapper,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    DescribeImageTool(String apiKey, String endpoint, String model, int maxTokens,
                      ObjectMapper objectMapper, HttpClient httpClient) {
        this.apiKey = apiKey;
        this.endpoint = endpoint;
        this.model = model;
        this.maxTokens = maxTokens;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public boolean isAvailable() {
        // Only advertise the tool when the vision call is actually configured (API key present),
        // so the model never sees a capability the user has not set up.
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String name() {
        return "describe_image";
    }

    @Override
    public String description() {
        return "Describe or answer a question about an image the user attached to their message. "
                + "Use this whenever the user refers to an attached image or photo: you cannot view "
                + "images directly, so this tool sends the attachment to a vision-capable model and "
                + "returns a textual description you can use to answer.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "question", Map.of(
                                "type", "string",
                                "description", "Optional specific question to answer about the image. "
                                        + "Omit for a general description."),
                        "image_index", Map.of(
                                "type", "integer",
                                "description", "Optional 0-based index selecting which attached image "
                                        + "to describe when more than one is attached. Omit to describe "
                                        + "all attached images.")),
                "required", List.of());
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        // This tool needs the per-request attachments, which only arrive via the context-aware
        // overload. The no-context path exists for the LocalTool contract / test stubs.
        return "describe_image is unavailable: no request context with image attachments.";
    }

    @Override
    public String execute(Map<String, Object> arguments, ToolContext ctx) throws Exception {
        if (apiKey == null || apiKey.isBlank()) {
            return "describe_image is unavailable: OPEN_ROUTER_API_KEY is not set.";
        }

        List<ChatAttachment> images = imageAttachments(ctx);
        if (images.isEmpty()) {
            return "No image is attached to the current message, so there is nothing to describe.";
        }

        List<ChatAttachment> selected;
        Integer index = optionalIndex(arguments, "image_index");
        if (index != null) {
            if (index < 0 || index >= images.size()) {
                return "image_index " + index + " is out of range; " + images.size()
                        + " image(s) are attached (valid indexes 0.." + (images.size() - 1) + ").";
            }
            selected = List.of(images.get(index));
        } else {
            selected = images;
        }

        String question = optionalString(arguments, "question", null);
        String requestBody = objectMapper.writeValueAsString(Map.of(
                "model", model,
                "messages", List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", userContent(question, selected))),
                "max_tokens", maxTokens));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        IOException lastNetworkError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();

                if (status == 200) {
                    JsonNode root = objectMapper.readTree(response.body());
                    JsonNode content = root.path("choices").path(0).path("message").path("content");
                    if (content.isMissingNode() || content.isNull() || content.asText().isBlank()) {
                        log.warn("describe_image: missing content field in 200 response: {}", response.body());
                        return "Image description failed: unexpected response structure (missing content).";
                    }
                    return content.asText();
                }

                if ((status == 429 || status >= 500) && attempt < MAX_ATTEMPTS) {
                    long delay = RETRY_BASE_MS * (1L << (attempt - 1));
                    log.warn("describe_image returned {} on attempt {}; retrying in {}ms", status, attempt, delay);
                    Thread.sleep(delay);
                    continue;
                }

                log.warn("describe_image API returned {}: {}", status, response.body());
                return "Image description failed: OpenRouter API error " + status + ": " + response.body();

            } catch (IOException e) {
                lastNetworkError = e;
                if (attempt < MAX_ATTEMPTS) {
                    long delay = RETRY_BASE_MS * (1L << (attempt - 1));
                    log.warn("describe_image network error on attempt {}; retrying in {}ms: {}",
                            attempt, delay, e.getMessage());
                    Thread.sleep(delay);
                }
            }
        }

        throw new IOException("describe_image failed after " + MAX_ATTEMPTS + " attempts", lastNetworkError);
    }

    /** Image attachments (mime type {@code image/*}) carried by the current request, in order. */
    private static List<ChatAttachment> imageAttachments(ToolContext ctx) {
        List<ChatAttachment> images = new ArrayList<>();
        if (ctx == null || ctx.attachments() == null) {
            return images;
        }
        for (ChatAttachment attachment : ctx.attachments()) {
            if (attachment == null || attachment.dataUrl() == null || attachment.dataUrl().isBlank()) {
                continue;
            }
            String mime = attachment.mimeType();
            if (mime == null || mime.toLowerCase(java.util.Locale.ROOT).startsWith("image/")) {
                images.add(attachment);
            }
        }
        return images;
    }

    /** Builds the multimodal user content: the question/instruction text plus each image part. */
    private static List<Map<String, Object>> userContent(String question, List<ChatAttachment> images) {
        List<Map<String, Object>> parts = new ArrayList<>();
        String text = (question == null || question.isBlank())
                ? "Describe this image in detail."
                : question.trim();
        parts.add(Map.of("type", "text", "text", text));
        for (ChatAttachment image : images) {
            Map<String, Object> imageUrl = new LinkedHashMap<>();
            imageUrl.put("url", image.dataUrl());
            parts.add(Map.of("type", "image_url", "image_url", imageUrl));
        }
        return parts;
    }

    private static Integer optionalIndex(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
