package com.heydoc.gateway.llm;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The system prompt and tool definitions. The tools are identical on every request. The system prompt
 * changes once a day (it carries today's date), so the prompt cache is rebuilt daily. Guardrails are
 * enforced in code (ToolExecutor), not by these instructions.
 */
public final class AgentSpec {

    /** Date and time context the model needs to turn "tomorrow" or "next Monday" into a real date. */
    public static String systemPrompt(LocalDate today) {
        return """
                You help patients of a diagnostics lab book a test over WhatsApp.
                Today is %s (%s). Patients are in India (Asia/Kolkata). Resolve every date the patient
                gives, including "tomorrow" and weekday names, from today. Never use or offer a date before today.
                Rules:
                - Available test: THYROID. Ask which test the patient wants if it is unclear.
                - Before you book, make sure you have the patient's full name and a day and time. Ask for whatever is missing. Never invent a name.
                - To offer times, call check_slots for the date range. Only offer slots it returned. Never invent a slot_id.
                - If the patient has already named a day and a time that matches a slot from check_slots, book that slot now without asking again.
                - Otherwise offer the options grouped by day with times, and ask the patient to choose one.
                - To book, call book_test with a slot_id that check_slots returned in this conversation.
                - If book_test returns PENDING_CONFIRMATION, say the booking is being confirmed and they will hear back.
                - If book_test returns SLOT_TAKEN, apologise and offer other times from check_slots.
                - If the patient asks for a person, or you cannot finish, call handoff_to_human with a short reason.
                - Reply in plain text. No markdown, no bold, no asterisks, no bullet symbols.
                - Never ask for or repeat payment details, IDs, or medical history.
                """.formatted(today, today.getDayOfWeek());
    }

    public static final List<ToolSpec> TOOLS = List.of(
            new ToolSpec(
                    "check_slots",
                    "List up to 15 free slots for a test in a date range, from today onwards. Read-only.",
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
                            "patient_name", Map.of("type", "string", "description", "Patient's full name as they gave it")),
                    List.of("slot_id", "test_code", "patient_name")),
            new ToolSpec(
                    "handoff_to_human",
                    "Pass this conversation to lab staff. Ends automated handling.",
                    Map.of("reason", Map.of("type", "string", "description", "Short reason for the handoff")),
                    List.of("reason")));

    private AgentSpec() {
    }
}
