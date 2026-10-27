package io.beangate.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class AnthropicProtocolConverterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void liftsSystemMessagesAndDropsUnsupportedFields() throws Exception {
        JsonNode openAi = MAPPER.readTree("""
                {"model":"public-model","temperature":1.7,"top_p":0.9,"stop":"END","n":2,"max_tokens":100,
                 "presence_penalty":0.5,"frequency_penalty":0.5,"seed":7,
                 "response_format":{"type":"json_object"},"logprobs":true,
                 "stream_options":{"include_usage":true},
                 "tools":[{"type":"function","function":{"name":"x"}}],
                 "messages":[
                   {"role":"system","content":"be terse"},
                   {"role":"system","content":"and kind"},
                   {"role":"user","content":[{"type":"text","text":"hello"},{"type":"text","text":"world"}]},
                   {"role":"assistant","content":"hi"},
                   {"role":"tool","content":"ignored"}
                 ]}
                """);

        ObjectNode out = AnthropicProtocolConverter.toAnthropicRequest(openAi, "anthropic-model");

        assertThat(out.path("model").asText()).isEqualTo("anthropic-model");
        assertThat(out.path("system").asText()).isEqualTo("be terse\n\nand kind");
        assertThat(out.path("max_tokens").asInt()).isEqualTo(100);
        assertThat(out.path("temperature").asDouble()).isEqualTo(1.0d);
        assertThat(out.path("top_p").asDouble()).isEqualTo(0.9d);
        assertThat(out.path("stop_sequences")).hasSize(1);
        assertThat(out.path("stop_sequences").get(0).asText()).isEqualTo("END");
        assertThat(out.path("messages")).hasSize(2);
        assertThat(out.path("messages").get(0).path("content").asText()).isEqualTo("hello\nworld");
        assertThat(out.has("n")).isFalse();
        assertThat(out.has("presence_penalty")).isFalse();
        assertThat(out.has("frequency_penalty")).isFalse();
        assertThat(out.has("seed")).isFalse();
        assertThat(out.has("response_format")).isFalse();
        assertThat(out.has("logprobs")).isFalse();
        assertThat(out.has("stream_options")).isFalse();
        assertThat(out.has("tools")).isFalse();
        assertThat(out.has("stream")).isFalse();
    }

    @Test
    void defaultsMaxTokensAndClampsTemperature() throws Exception {
        JsonNode openAi = MAPPER.readTree("""
                {"model":"m","temperature":-0.5,"messages":[{"role":"user","content":"hi"}]}
                """);

        ObjectNode out = AnthropicProtocolConverter.toAnthropicRequest(openAi, "up");

        assertThat(out.path("max_tokens").asInt()).isEqualTo(4096);
        assertThat(out.path("temperature").asDouble()).isEqualTo(0.0d);
        assertThat(out.path("messages")).hasSize(1);
        assertThat(out.path("messages").get(0).path("content").asText()).isEqualTo("hi");
    }

    @Test
    void acceptsStopAsArray() throws Exception {
        JsonNode openAi = MAPPER.readTree("""
                {"model":"m","stop":["A","B"],"messages":[{"role":"user","content":"hi"}]}
                """);

        ObjectNode out = AnthropicProtocolConverter.toAnthropicRequest(openAi, "up");

        assertThat(out.path("stop_sequences")).hasSize(2);
        assertThat(out.path("stop_sequences").get(1).asText()).isEqualTo("B");
    }

    @Test
    void convertsResponseShapeAndUsage() throws Exception {
        JsonNode anthropic = MAPPER.readTree("""
                {"id":"msg_123","type":"message","role":"assistant","model":"claude-3-5-sonnet",
                 "content":[{"type":"text","text":"hello "},{"type":"text","text":"world"}],
                 "stop_reason":"max_tokens","usage":{"input_tokens":10,"output_tokens":5}}
                """);

        ObjectNode out = AnthropicProtocolConverter.toOpenAiResponse(anthropic, "public-model");

        assertThat(out.path("id").asText()).isEqualTo("chatcmpl-msg_123");
        assertThat(out.path("object").asText()).isEqualTo("chat.completion");
        assertThat(out.path("model").asText()).isEqualTo("public-model");
        assertThat(out.path("choices")).hasSize(1);
        assertThat(out.path("choices").get(0).path("message").path("role").asText()).isEqualTo("assistant");
        assertThat(out.path("choices").get(0).path("message").path("content").asText()).isEqualTo("hello world");
        assertThat(out.path("choices").get(0).path("finish_reason").asText()).isEqualTo("length");
        assertThat(out.path("usage").path("prompt_tokens").asInt()).isEqualTo(10);
        assertThat(out.path("usage").path("completion_tokens").asInt()).isEqualTo(5);
        assertThat(out.path("usage").path("total_tokens").asInt()).isEqualTo(15);
    }

    @Test
    void mapsStopReasons() {
        assertThat(AnthropicProtocolConverter.mapStopReason("end_turn")).isEqualTo("stop");
        assertThat(AnthropicProtocolConverter.mapStopReason("stop_sequence")).isEqualTo("stop");
        assertThat(AnthropicProtocolConverter.mapStopReason("max_tokens")).isEqualTo("length");
        assertThat(AnthropicProtocolConverter.mapStopReason("tool_use")).isEqualTo("tool_calls");
        assertThat(AnthropicProtocolConverter.mapStopReason("refusal")).isEqualTo("content_filter");
    }
}
