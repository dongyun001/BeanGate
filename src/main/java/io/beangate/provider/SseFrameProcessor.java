package io.beangate.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Frame-level SSE passthrough.
 *
 * <p>Not byte-identical, deliberately: every chunk carries a {@code model} field that must be rewritten
 * to the public name, and usage-only chunks are suppressed when the gateway itself injected
 * {@code stream_options.include_usage}. Everything else — unknown fields, ordering, the {@code [DONE]}
 * sentinel, comment/heartbeat frames — is preserved.
 *
 * <p>Bytes are accumulated before decoding so a multi-byte UTF-8 character split across two upstream
 * buffers is never corrupted.
 */
final class SseFrameProcessor {

    private static final String DONE = "[DONE]";

    private final String publicModel;
    private final boolean swallowUsageChunk;
    private final StreamStats stats;
    private final ObjectMapper mapper;
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

    SseFrameProcessor(String publicModel, boolean swallowUsageChunk, StreamStats stats, ObjectMapper mapper) {
        this.publicModel = publicModel;
        this.swallowUsageChunk = swallowUsageChunk;
        this.stats = stats;
        this.mapper = mapper;
    }

    List<byte[]> accept(byte[] chunk) {
        pending.writeBytes(chunk);
        return drain(false);
    }

    /** Flushes a trailing partial frame when the upstream ends without a final blank line. */
    List<byte[]> flush() {
        return drain(true);
    }

    private List<byte[]> drain(boolean flushPartial) {
        byte[] buffered = pending.toByteArray();
        List<byte[]> out = new ArrayList<>();
        int offset = 0;
        while (true) {
            int[] delimiter = findFrameEnd(buffered, offset);
            if (delimiter == null) {
                break;
            }
            process(Arrays.copyOfRange(buffered, offset, delimiter[0]), out);
            offset = delimiter[1];
        }
        if (flushPartial && offset < buffered.length) {
            process(Arrays.copyOfRange(buffered, offset, buffered.length), out);
            offset = buffered.length;
        }
        pending.reset();
        if (offset < buffered.length) {
            pending.writeBytes(Arrays.copyOfRange(buffered, offset, buffered.length));
        }
        return out;
    }

    private void process(byte[] frameBytes, List<byte[]> out) {
        String frame = new String(frameBytes, StandardCharsets.UTF_8);
        String trimmed = frame.strip();
        if (trimmed.isEmpty()) {
            return;
        }
        stats.markFirstChunk();

        // Comments/heartbeats (`: keep-alive`) and non-data frames pass through untouched.
        if (!trimmed.startsWith("data:")) {
            out.add((frame + "\n\n").getBytes(StandardCharsets.UTF_8));
            return;
        }

        String payload = extractPayload(frame);
        if (payload.isBlank()) {
            out.add(("data: \n\n").getBytes(StandardCharsets.UTF_8));
            return;
        }
        if (DONE.equals(payload)) {
            out.add(("data: " + DONE + "\n\n").getBytes(StandardCharsets.UTF_8));
            return;
        }

        JsonNode chunk;
        try {
            chunk = mapper.readTree(payload);
        } catch (Exception e) {
            // Not JSON (vendor extension): forward verbatim rather than corrupting it.
            out.add((frame + "\n\n").getBytes(StandardCharsets.UTF_8));
            return;
        }

        JsonNode usage = chunk.get("usage");
        boolean usageOnly = usage != null && !usage.isNull() && isEmptyChoices(chunk.get("choices"));
        if (usage != null && !usage.isNull()) {
            stats.recordUsage(new TokenUsage(intOrNull(usage.get("prompt_tokens")),
                    intOrNull(usage.get("completion_tokens")), intOrNull(usage.get("total_tokens"))));
        }

        if (usageOnly && swallowUsageChunk) {
            return;
        }

        countCompletionChars(chunk);

        if (chunk instanceof ObjectNode object && object.has("model")) {
            object.put("model", publicModel);
        }
        try {
            out.add(("data: " + mapper.writeValueAsString(chunk) + "\n\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            out.add((frame + "\n\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String extractPayload(String frame) {
        StringBuilder payload = new StringBuilder();
        for (String line : frame.split("\n")) {
            String candidate = line.strip();
            if (candidate.startsWith("data:")) {
                if (payload.length() > 0) {
                    payload.append('\n');
                }
                payload.append(candidate.substring(5).stripLeading());
            }
        }
        return payload.toString().strip();
    }

    private static boolean isEmptyChoices(JsonNode choices) {
        return choices == null || choices.isNull() || (choices.isArray() && choices.isEmpty());
    }

    private void countCompletionChars(JsonNode chunk) {
        JsonNode choices = chunk.get("choices");
        if (choices == null || !choices.isArray()) {
            return;
        }
        for (JsonNode choice : choices) {
            JsonNode content = choice.path("delta").path("content");
            if (content.isTextual()) {
                stats.addCompletionChars(content.asText().length());
            }
        }
    }

    private static Integer intOrNull(JsonNode node) {
        return node == null || !node.isNumber() ? null : node.asInt();
    }

    /** @return {index of the delimiter, index just past it} or null when no complete frame is buffered. */
    private static int[] findFrameEnd(byte[] data, int from) {
        for (int i = from; i < data.length - 1; i++) {
            if (data[i] == '\n' && data[i + 1] == '\n') {
                return new int[]{i, i + 2};
            }
            if (i < data.length - 3 && data[i] == '\r' && data[i + 1] == '\n'
                    && data[i + 2] == '\r' && data[i + 3] == '\n') {
                return new int[]{i, i + 4};
            }
        }
        return null;
    }
}
