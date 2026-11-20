package com.heydoc.gateway.llm;

import java.util.List;

/** One model response, with its usage (token counts for cost tracking). */
public record LlmResponse(List<LlmPart> parts, String stopReason, Usage usage) {

    public record Usage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens) {
        public static final Usage NONE = new Usage(0, 0, 0, 0);
    }
}
