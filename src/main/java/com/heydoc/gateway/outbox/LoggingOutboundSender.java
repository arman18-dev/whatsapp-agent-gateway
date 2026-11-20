package com.heydoc.gateway.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Default sender: logs the reply. The real WhatsApp send needs a test number and token (not in this repo). */
@Component
@Profile("worker")
public class LoggingOutboundSender implements OutboundSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingOutboundSender.class);

    @Override
    public void send(String phone, String body) {
        log.info("OUTBOUND to={} body={}", phone, body);
    }
}
