package com.heydoc.gateway.webhook;

import com.heydoc.gateway.config.AppProperties;
import com.heydoc.gateway.ingest.IngestResult;
import com.heydoc.gateway.ingest.IngestService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * WhatsApp Cloud API webhook. Does only: verify the signature, parse, dedupe and rate-limit,
 * store. No LLM or lab call happens on this path.
 */
@RestController
@Profile("gateway")
public class WebhookController {

    private final SignatureVerifier signatures;
    private final WebhookPayloadParser parser;
    private final IngestService ingest;
    private final AppProperties properties;

    public WebhookController(SignatureVerifier signatures,
                             WebhookPayloadParser parser,
                             IngestService ingest,
                             AppProperties properties) {
        this.signatures = signatures;
        this.parser = parser;
        this.ingest = ingest;
        this.properties = properties;
    }

    /** Meta's verification handshake. */
    @GetMapping(value = "/webhook", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verify(@RequestParam(name = "hub.mode", required = false) String mode,
                                         @RequestParam(name = "hub.verify_token", required = false) String token,
                                         @RequestParam(name = "hub.challenge", required = false) String challenge) {
        String expected = properties.whatsappVerifyToken();
        boolean tokenMatches = expected != null && !expected.isBlank() && token != null
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8));
        if ("subscribe".equals(mode) && tokenMatches && challenge != null) {
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("forbidden");
    }

    @PostMapping(value = "/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> receive(
            @RequestBody byte[] rawBody,
            @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature) {

        if (!signatures.isValid(rawBody, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "bad_signature"));
        }
        List<InboundMessage> messages = parser.parse(rawBody);
        IngestResult result = ingest.ingest(messages);
        return ResponseEntity.ok(Map.of("status", result.wireName()));
    }

    @ExceptionHandler(MalformedPayloadException.class)
    public ResponseEntity<Map<String, String>> malformed(MalformedPayloadException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "malformed"));
    }
}
