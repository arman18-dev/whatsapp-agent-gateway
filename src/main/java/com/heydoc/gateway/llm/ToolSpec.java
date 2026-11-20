package com.heydoc.gateway.llm;

import java.util.List;
import java.util.Map;

/** Provider-neutral tool definition. properties maps each field to its JSON-schema fragment. */
public record ToolSpec(String name, String description, Map<String, Object> properties, List<String> required) {
}
