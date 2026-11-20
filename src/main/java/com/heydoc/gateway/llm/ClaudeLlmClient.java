package com.heydoc.gateway.llm;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlockParam;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.heydoc.gateway.config.LlmProperties;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Claude via the official Anthropic Java SDK. Reads ANTHROPIC_API_KEY from the environment.
 * Thinking is disabled and effort is low: these are routine booking turns, and thinking tokens cost money.
 */
@Component
@Profile("worker")
@ConditionalOnProperty(prefix = "app.llm", name = "mode", havingValue = "real")
public class ClaudeLlmClient implements LlmClient {

    private final AnthropicClient client;
    private final LlmProperties properties;

    public ClaudeLlmClient(LlmProperties properties) {
        this.client = AnthropicOkHttpClient.fromEnv();
        this.properties = properties;
    }

    @Override
    public LlmResponse complete(List<LlmMessage> history) {
        MessageCreateParams.Builder params = MessageCreateParams.builder()
                .model(properties.model())
                .maxTokens(properties.maxTokens())
                .thinking(ThinkingConfigDisabled.builder().build())
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .systemOfTextBlockParams(List.of(TextBlockParam.builder()
                        .text(AgentSpec.systemPrompt(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))))
                        .cacheControl(CacheControlEphemeral.builder().build())
                        .build()));
        for (ToolSpec spec : AgentSpec.TOOLS) {
            params.addTool(toTool(spec));
        }
        for (LlmMessage message : history) {
            List<ContentBlockParam> blocks = new ArrayList<>();
            for (LlmPart part : message.parts()) {
                blocks.add(toBlock(part));
            }
            if (message.isUser()) {
                params.addUserMessageOfContentBlockParams(blocks);
            } else {
                params.addAssistantMessageOfContentBlockParams(blocks);
            }
        }

        Message response = client.messages().create(params.build());

        List<LlmPart> parts = new ArrayList<>();
        for (ContentBlock block : response.content()) {
            block.text().ifPresent(t -> parts.add(new LlmPart.Text(t.text())));
            block.toolUse().ifPresent(u -> parts.add(new LlmPart.ToolUse(
                    u.id(), u.name(), u._input().convert(JsonNode.class))));
        }
        LlmResponse.Usage usage = new LlmResponse.Usage(
                response.usage().inputTokens(),
                response.usage().outputTokens(),
                response.usage().cacheReadInputTokens().orElse(0L),
                response.usage().cacheCreationInputTokens().orElse(0L));
        String stopReason = response.stopReason().map(Object::toString).orElse("");
        return new LlmResponse(parts, stopReason, usage);
    }

    private static Tool toTool(ToolSpec spec) {
        Tool.InputSchema.Properties.Builder properties = Tool.InputSchema.Properties.builder();
        spec.properties().forEach((name, schema) -> properties.putAdditionalProperty(name, JsonValue.from(schema)));
        return Tool.builder()
                .name(spec.name())
                .description(spec.description())
                .strict(true)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(properties.build())
                        .required(spec.required())
                        .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                        .build())
                .build();
    }

    private static ContentBlockParam toBlock(LlmPart part) {
        if (part instanceof LlmPart.Text text) {
            return ContentBlockParam.ofText(text.text());
        }
        if (part instanceof LlmPart.ToolUse use) {
            ToolUseBlockParam.Input.Builder input = ToolUseBlockParam.Input.builder();
            if (use.input() != null && use.input().isObject()) {
                ObjectNode fields = (ObjectNode) use.input();
                fields.fields().forEachRemaining(e ->
                        input.putAdditionalProperty(e.getKey(), JsonValue.fromJsonNode(e.getValue())));
            }
            return ContentBlockParam.ofToolUse(ToolUseBlockParam.builder()
                    .id(use.id())
                    .name(use.name())
                    .input(input.build())
                    .build());
        }
        LlmPart.ToolResult result = (LlmPart.ToolResult) part;
        return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                .toolUseId(result.toolUseId())
                .content(result.content())
                .isError(result.isError())
                .build());
    }
}
