package com.heydoc.gateway.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Reads only entry[].changes[].value.messages[]. Status events are not messages and yield nothing.
 */
@Component
@Profile("gateway")
public class WebhookPayloadParser {

    private final ObjectMapper mapper;

    public WebhookPayloadParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<InboundMessage> parse(byte[] rawBody) {
        JsonNode root;
        try {
            root = mapper.readTree(rawBody);
        } catch (IOException e) {
            throw new MalformedPayloadException("invalid JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw new MalformedPayloadException("payload is not a JSON object");
        }

        List<InboundMessage> messages = new ArrayList<>();
        for (JsonNode entry : root.path("entry")) {
            for (JsonNode change : entry.path("changes")) {
                for (JsonNode message : change.path("value").path("messages")) {
                    String id = message.path("id").textValue();
                    String from = message.path("from").textValue();
                    String type = message.path("type").textValue();
                    if (id == null || from == null) {
                        throw new MalformedPayloadException("message without id or from");
                    }
                    String body = "text".equals(type)
                            ? message.path("text").path("body").textValue()
                            : null;
                    messages.add(new InboundMessage(id, from, type, body));
                }
            }
        }
        return messages;
    }
}
