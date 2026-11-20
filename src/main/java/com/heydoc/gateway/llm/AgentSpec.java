package com.heydoc.gateway.llm;

import java.util.List;
import java.util.Map;

/**
 * The fixed system prompt and tool definitions. Both are identical on every request, which keeps
 * the prompt cache valid. Guardrails are enforced in code (ToolExecutor), not by these instructions.
 */
public final class AgentSpec {

    public static final String SYSTEM_PROMPT = """
            You help patients of a diagnostics lab book a test over WhatsApp.
            Rules:
            - Ask which test the patient wants and, if needed, a date range. Keep replies short.
            - To offer times, call check_slots. Only offer slots it returned. Never invent a slot_id.
            - To book, call book_test with a slot_id that check_slots returned in this conversation.
            - If book_test returns PENDING_CONFIRMATION, tell the patient the booking is being confirmed and they will hear back.
            - If book_test returns SLOT_TAKEN, apologise and offer other times from check_slots.
            - If the patient asks for a person, or you cannot finish, call handoff_to_human with a short reason.
            - Never ask for or repeat payment details, IDs, or medical history.
            """;

    public static final List<ToolSpec> TOOLS = List.of(
            new ToolSpec(
                    "check_slots",
                    "List up to 5 free slots for a test in a date range. Read-only.",
                    Map.of(
                            "test_code", Map.of("type", "string", "description", "Test code, e.g. THYROID"),
                            "date_from", Map.of("type", "string", "description", "First day, YYYY-MM-DD"),
                            "date_to", Map.of("type", "string", "description", "Last day, YYYY-MM-DD")),
                    List.of("test_code", "date_from", "date_to")),
            new ToolSpec(
                    "book_test",
                    "Book one slot returned by check_slots for the patient.",
                    Map.of(
                            "slot_id", Map.of("type", "string", "description", "A slot_id from check_slots"),
                            "test_code", Map.of("type", "string", "description", "Test code"),
                            "patient_name", Map.of("type", "string", "description", "Patient name as given")),
                    List.of("slot_id", "test_code", "patient_name")),
            new ToolSpec(
                    "handoff_to_human",
                    "Pass this conversation to lab staff. Ends automated handling.",
                    Map.of("reason", Map.of("type", "string", "description", "Short reason for the handoff")),
                    List.of("reason")));

    private AgentSpec() {
    }
}
