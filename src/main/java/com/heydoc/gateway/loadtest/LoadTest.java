package com.heydoc.gateway.loadtest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Load test (PLAN.md section 12). Sends 1,000 signed webhook payloads with 50 concurrent senders:
 * 800 unique messages (about 5% of all sends are over the per-phone limit) plus 200 duplicate
 * redeliveries. Waits for the pipeline to drain, then checks the database.
 *
 * <p>Run it against a running stack (see README). Arguments: [report path]. Env: GATEWAY_URL,
 * WHATSAPP_APP_SECRET, LOAD_DB_URL, LOAD_DB_USER, LOAD_DB_PASSWORD, LOAD_LLM_MODE (label only).
 */
public final class LoadTest {

    private static final Pattern STATUS = Pattern.compile("\"status\"\\s*:\\s*\"([a-z_]+)\"");
    private static final int CONCURRENCY = 50;

    private record Send(String waId, String phone, String text, String body) {
    }

    public static void main(String[] args) throws Exception {
        String gateway = env("GATEWAY_URL", "http://localhost:8080");
        String secret = required("WHATSAPP_APP_SECRET");
        String dbUrl = env("LOAD_DB_URL", "jdbc:postgresql://localhost:5432/gateway");
        String dbUser = env("LOAD_DB_USER", "gateway");
        String dbPassword = env("LOAD_DB_PASSWORD", "gateway");
        String llmMode = env("LOAD_LLM_MODE", "unknown");
        Path report = Path.of(args.length > 0 ? args[0] : "docs/load-test-report.md");

        List<Send> sends = plan();
        System.out.printf("plan: %d sends, %d unique messages%n", sends.size(), uniqueCount(sends));

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        ConcurrentHashMap<String, AtomicInteger> outcomes = new ConcurrentHashMap<>();
        List<Long> ackMicros = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger failedRequests = new AtomicInteger();
        AtomicReference<String> errorSample = new AtomicReference<>();

        Instant start = Instant.now();
        List<Future<?>> futures = new ArrayList<>();
        for (Send send : sends) {
            futures.add(pool.submit(() -> {
                String lastError = null;
                // Retry transport failures (timeouts, resets) so every planned message is sent.
                for (int attempt = 1; attempt <= 3; attempt++) {
                    try {
                        String signature = sign(secret, send.body());
                        HttpRequest request = HttpRequest.newBuilder(URI.create(gateway + "/webhook"))
                                .header("Content-Type", "application/json")
                                .header("X-Hub-Signature-256", signature)
                                .POST(HttpRequest.BodyPublishers.ofString(send.body(), StandardCharsets.UTF_8))
                                .timeout(Duration.ofSeconds(10))
                                .build();
                        long t0 = System.nanoTime();
                        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                        ackMicros.add((System.nanoTime() - t0) / 1_000);
                        Matcher m = STATUS.matcher(response.body());
                        String outcome = response.statusCode() == 200 && m.find()
                                ? m.group(1) : "http_" + response.statusCode();
                        outcomes.computeIfAbsent(outcome, k -> new AtomicInteger()).incrementAndGet();
                        return;
                    } catch (Exception e) {
                        lastError = e.toString();
                        try {
                            Thread.sleep(200L * attempt);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
                failedRequests.incrementAndGet();
                errorSample.compareAndSet(null, lastError);
            }));
        }
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdown();
        Duration sendPhase = Duration.between(start, Instant.now());
        System.out.println("all sends done in " + sendPhase.toMillis() + " ms");

        Instant drainStart = Instant.now();
        try (Connection db = connectWithRetry(dbUrl, dbUser, dbPassword)) {
            awaitDrain(db, Duration.ofMinutes(15));
            Duration drain = Duration.between(drainStart, Instant.now());
            String md = buildReport(db, sends.size(), outcomes, ackMicros, failedRequests.get(),
                    sendPhase, drain, llmMode, gateway)
                    + (errorSample.get() == null ? "" : "\nFirst transport error: `" + errorSample.get() + "`\n");
            Files.createDirectories(report.toAbsolutePath().getParent());
            Files.writeString(report, md, StandardCharsets.UTF_8);
            System.out.println(md);
            System.out.println("report written to " + report.toAbsolutePath());
        }
    }

    /** 800 unique messages + 200 redeliveries, shuffled. */
    static List<Send> plan() {
        List<Send> unique = new ArrayList<>();
        // 10 heavy phones with 15 messages each: 5 over the limit of 10 per minute.
        for (int p = 0; p < 10; p++) {
            String phone = "9100000" + String.format("%03d", p);
            for (int i = 0; i < 15; i++) {
                unique.add(message("lt-h-" + p + "-" + i, phone, "I want to book a thyroid test"));
            }
        }
        // 325 normal phones with 2 messages each.
        for (int p = 0; p < 325; p++) {
            String phone = "9200000" + String.format("%03d", p);
            unique.add(message("lt-n-" + p + "-0", phone, "I want to book a thyroid test"));
            unique.add(message("lt-n-" + p + "-1", phone, "hi"));
        }
        Random random = new Random(42);
        List<Send> sends = new ArrayList<>(unique);
        for (int i = 0; i < 200; i++) {
            sends.add(unique.get(random.nextInt(unique.size())));  // same wa id and body: a redelivery
        }
        Collections.shuffle(sends, random);
        return sends;
    }

    private static Send message(String waId, String phone, String text) {
        String body = """
                {"object":"whatsapp_business_account","entry":[{"id":"1","changes":[{"field":"messages",
                "value":{"messaging_product":"whatsapp","messages":[{"from":"%s","id":"%s",
                "timestamp":"1700000000","type":"text","text":{"body":"%s"}}]}}]}]}
                """.formatted(phone, waId, text);
        return new Send(waId, phone, text, body);
    }

    /** The database can be busy right after a burst; retry instead of losing the whole report. */
    private static Connection connectWithRetry(String url, String user, String password) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 10; attempt++) {
            try {
                return DriverManager.getConnection(url, user, password);
            } catch (Exception e) {
                last = e;
                Thread.sleep(3000);
            }
        }
        throw last;
    }

    private static void awaitDrain(Connection db, Duration timeout) throws Exception {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            long pending = scalar(db, "SELECT COUNT(*) FROM inbound_messages WHERE status IN ('RECEIVED','PROCESSING')")
                    + scalar(db, "SELECT COUNT(*) FROM bookings WHERE status IN ('HELD','UNKNOWN')")
                    + scalar(db, "SELECT COUNT(*) FROM outbound_messages WHERE status = 'PENDING'");
            if (pending == 0) {
                return;
            }
            Thread.sleep(1000);
        }
        System.out.println("WARNING: pipeline did not drain within " + timeout.toMinutes() + " minutes");
    }

    private static String buildReport(Connection db, int sent, Map<String, AtomicInteger> outcomes,
                                      List<Long> ackMicros, int failedRequests, Duration sendPhase,
                                      Duration drain, String llmMode, String gateway) throws Exception {
        StringBuilder md = new StringBuilder();
        md.append("# Load test report\n\n");
        md.append("- Sends: ").append(sent).append(" (800 unique, 200 duplicate redeliveries), ")
                .append("50 concurrent senders\n");
        md.append("- Gateway: ").append(gateway).append(", LLM mode: ").append(llmMode).append("\n");
        md.append("- Send phase: ").append(sendPhase.toMillis()).append(" ms; drain: ")
                .append(drain.toSeconds()).append(" s\n");
        md.append("- Transport failures: ").append(failedRequests).append("\n\n");

        md.append("## Webhook outcomes\n\n| outcome | count |\n|---|---|\n");
        for (Map.Entry<String, AtomicInteger> e : new TreeMap<>(outcomes).entrySet()) {
            md.append("| ").append(e.getKey()).append(" | ").append(e.getValue().get()).append(" |\n");
        }

        List<Long> sorted = new ArrayList<>(ackMicros);
        Collections.sort(sorted);
        md.append("\n## Ack latency (client side, ms)\n\n");
        md.append(String.format("p50 %.1f, p95 %.1f, p99 %.1f, max %.1f%n%n",
                pct(sorted, 50), pct(sorted, 95), pct(sorted, 99), pct(sorted, 100)));

        md.append("## Inbound messages by status\n\n| status | count |\n|---|---|\n");
        for (Map<String, Object> row : rows(db, "SELECT status, COUNT(*) AS n FROM inbound_messages GROUP BY status ORDER BY status")) {
            md.append("| ").append(row.get("status")).append(" | ").append(row.get("n")).append(" |\n");
        }

        md.append("\n## Bookings\n\n| status | count |\n|---|---|\n");
        for (Map<String, Object> row : rows(db, "SELECT status, COUNT(*) AS n FROM bookings GROUP BY status ORDER BY status")) {
            md.append("| ").append(row.get("status")).append(" | ").append(row.get("n")).append(" |\n");
        }
        long doubleBooked = scalar(db, """
                SELECT COUNT(*) FROM (SELECT slot_id FROM bookings
                  WHERE status IN ('HELD','CONFIRMED','UNKNOWN')
                  GROUP BY slot_id HAVING COUNT(*) > 1) d
                """);
        long unknownLeft = scalar(db, "SELECT COUNT(*) FROM bookings WHERE status = 'UNKNOWN'");
        md.append("\n- **Double bookings (must be 0): ").append(doubleBooked).append("**\n");
        md.append("- UNKNOWN left after drain (must be 0): ").append(unknownLeft).append("\n");
        md.append("- Handoffs: ").append(scalar(db, "SELECT COUNT(*) FROM handoffs")).append("\n");
        md.append("- Outbound replies: ").append(scalar(db, "SELECT COUNT(*) FROM outbound_messages")).append("\n");

        md.append("\n## LLM usage and cost\n\n");
        for (Map<String, Object> row : rows(db, """
                SELECT COUNT(*) AS calls, COALESCE(SUM(input_tokens),0) AS input_tokens,
                       COALESCE(SUM(output_tokens),0) AS output_tokens,
                       COALESCE(SUM(cache_read_tokens),0) AS cache_read_tokens,
                       COALESCE(SUM(cache_write_tokens),0) AS cache_write_tokens,
                       COALESCE(SUM(cost_usd),0) AS cost_usd FROM llm_calls
                """)) {
            md.append("- Calls: ").append(row.get("calls")).append("\n");
            md.append("- Input tokens: ").append(row.get("input_tokens"))
                    .append(" (cache read ").append(row.get("cache_read_tokens"))
                    .append(", cache write ").append(row.get("cache_write_tokens")).append(")\n");
            md.append("- Output tokens: ").append(row.get("output_tokens")).append("\n");
            md.append("- Cost (USD): ").append(row.get("cost_usd")).append("\n");
        }
        return md.toString();
    }

    private static double pct(List<Long> sortedMicros, double p) {
        if (sortedMicros.isEmpty()) {
            return 0;
        }
        int index = (int) Math.min(sortedMicros.size() - 1, Math.ceil(p / 100.0 * sortedMicros.size()) - 1);
        return sortedMicros.get(Math.max(0, index)) / 1000.0;
    }

    private static long uniqueCount(List<Send> sends) {
        return sends.stream().map(Send::waId).distinct().count();
    }

    private static long scalar(Connection db, String sql) throws Exception {
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private static List<Map<String, Object>> rows(Connection db, String sql) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int cols = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new TreeMap<>();
                for (int c = 1; c <= cols; c++) {
                    row.put(rs.getMetaData().getColumnLabel(c), rs.getObject(c));
                }
                out.add(row);
            }
        }
        return out;
    }

    private static String sign(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set (same value as the gateway)");
        }
        return value;
    }

    private LoadTest() {
    }
}
