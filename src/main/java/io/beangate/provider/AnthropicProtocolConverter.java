package io.beangate.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;

/**
 * OpenAI ⇄ Anthropic request/response mapping for the non-streaming, text-only MVP.
 *
 * <p>Dropped silently because Anthropic has no equivalent: tools/tool_choice, response_format,
 * logprobs, n, presence_penalty, frequency_penalty, seed, user, stream_options. {@code temperature} is
 * clamped from OpenAI's 0..2 to Anthropic's 0..1. {@code max_tokens} is mandatory for Anthropic and
 * defaults to 4096 when the caller omitted it.
 */
public final class AnthropicProtocolConverter {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int DEFAULT_MAX_TOKENS = 4096;

    private AnthropicProtocolConverter() {
    }

    public static ObjectNode toAnthropicRequest(JsonNode openAiRequest, String upstreamModel) {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("model", upstreamModel);

        StringBuilder system = new StringBuilder();
        ArrayNode messages = MAPPER.createArrayNode();

        JsonNode sourceMessages = openAiRequest.path("messages");
        if (sourceMessages.isArray()) {
            for (JsonNode message : sourceMessages) {
                String role = message.path("role").asText("");
                String text = extractText(message.path("content"));
                if ("system".equals(role)) {
                    if (system.length() > 0) {
                        system.append("\n\n");
                    }
                    system.append(text);
                    continue;
                }
                if (!"user".equals(role) && !"assistant".equals(role)) {
                    continue; // tool/function messages are out of scope for the MVP
                }
                ObjectNode converted = MAPPER.createObjectNode();
                converted.put("role", role);
                converted.put("content", text);
                messages.add(converted);
            }
        }
        if (system.length() > 0) {
            out.put("system", system.toString());
        }

        int maxTokens = openAiRequest.hasNonNull("max_tokens")
                ? Math.max(1, openAiRequest.path("max_tokens").asInt())
                : DEFAULT_MAX_TOKENS;
        out.put("max_tokens", maxTokens);

        if (openAiRequest.hasNonNull("temperature")) {
            double temperature = openAiRequest.path("temperature").asDouble();
            out.put("temperature", Math.max(0.0d, Math.min(1.0d, temperature)));
        }
        if (openAiRequest.hasNonNull("top_p")) {
            out.put("top_p", openAiRequest.path("top_p").asDouble());
        }

        JsonNode stop = openAiRequest.get("stop");
        if (stop != null && !stop.isNull()) {
            ArrayNode stopSequences = out.putArray("stop_sequences");
            if (stop.isTextual()) {
                stopSequences.add(stop.asText());
            } else if (stop.isArray()) {
                for (JsonNode candidate : stop) {
                    if (candidate.isTextual()) {
                        stopSequences.add(candidate.asText());
                    }
                }
            }
            if (stopSequences.isEmpty()) {
                out.remove("stop_sequences");
            }
        }

        out.set("messages", messages);
        return out;
    }

    public static ObjectNode toOpenAiResponse(JsonNode anthropicResponse, String modelName) {
        ObjectNode out = MAPPER.createObjectNode();
        String id = anthropicResponse.path("id").asText("");
        out.put("id", id.startsWith("chatcmpl-") || id.isEmpty() ? id : "chatcmpl-" + id);
        out.put("object", "chat.completion");
        out.put("created", Instant.now().getEpochSecond());
        out.put("model", modelName);

        StringBuilder content = new StringBuilder();
        JsonNode blocks = anthropicResponse.path("content");
        if (blocks.isArray()) {
            for (JsonNode block : blocks) {
                if ("text".equals(block.path("type").asText())) {
                    content.append(block.path("text").asText());
                }
            }
        }

        ObjectNode choice = MAPPER.createObjectNode();
        choice.put("index", 0);
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        message.put("content", content.toString());
        choice.put("finish_reason", mapStopReason(anthropicResponse.path("stop_reason").asText("")));
        out.putArray("choices").add(choice);

        JsonNode usage = anthropicResponse.path("usage");
        int promptTokens = usage.path("input_tokens").asInt(0);
        int completionTokens = usage.path("output_tokens").asInt(0);
        ObjectNode convertedUsage = out.putObject("usage");
        convertedUsage.put("prompt_tokens", promptTokens);
        convertedUsage.put("completion_tokens", completionTokens);
        convertedUsage.put("total_tokens", promptTokens + completionTokens);

        return out;
    }

    static String mapStopReason(String anthropicStopReason) {
        return switch (anthropicStopReason) {
            case "max_tokens" -> "length";
            case "tool_use" -> "tool_calls";
            case "refusal" -> "content_filter";
            case "end_turn", "stop_sequence", "" -> "stop";
            default -> "stop";
        };
    }

    private static String extractText(JsonNode content) {
        if (content == null || content.isNull()) {
            return "";
        }
        if (content.isTextual()) {
            return content.asText();
        }
        if (content.isObject()) {
            return content.path("text").asText("");
        }
        if (content.isArray()) {
            StringBuilder text = new StringBuilder();
            for (JsonNode part : content) {
                String value = part.isTextual()
                        ? part.asText()
                        : part.path("text").asText("");
                if (value.isEmpty()) {
                    continue;
                }
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(value);
            }
            return text.toString();
        }
        return "";
    }
}
