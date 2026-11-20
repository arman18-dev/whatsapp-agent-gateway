package com.heydoc.gateway.llm;

import java.util.List;

/** Provider-neutral model call: history in, one response out. Implementations decide the model. */
public interface LlmClient {

    LlmResponse complete(List<LlmMessage> history);
}
