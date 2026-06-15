package com.example.integration.tool;

import com.example.agent.tool.LocalTool;
import com.example.integration.google.GoogleWorkspaceClientFactory;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/** Creates a Google Calendar event on the authenticated calendar account. */
@Component
public class GoogleCalendarCreateTool implements LocalTool {

    private final GoogleWorkspaceClientFactory google;

    public GoogleCalendarCreateTool(GoogleWorkspaceClientFactory google) {
        this.google = google;
    }

    @Override
    public boolean isAvailable() {
        return google.available();
    }

    @Override
    public String name() {
        return "google_calendar_create";
    }

    @Override
    public String description() {
        return "Create a Google Calendar event. Provide a summary and either ISO date-time strings "
                + "for start/end or ISO dates for an all-day event. Returns the created event id and URL.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "summary", Map.of("type", "string", "description", "Event title."),
                        "start", Map.of("type", "string", "description", "Start time in ISO-8601 format or ISO date."),
                        "end", Map.of("type", "string", "description", "End time in ISO-8601 format or ISO date."),
                        "timezone", Map.of("type", "string", "description", "IANA timezone for naive times."),
                        "description", Map.of("type", "string", "description", "Optional event description."),
                        "location", Map.of("type", "string", "description", "Optional location."),
                        "calendar_id", Map.of("type", "string", "description", "Calendar id to use. Defaults to primary.")),
                "required", List.of("summary", "start", "end"));
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        return google.guarded(() -> {
            String summary = requiredString(arguments, "summary");
            String start = requiredString(arguments, "start");
            String end = requiredString(arguments, "end");
            String timezone = optionalString(arguments, "timezone", ZoneId.systemDefault().getId());
            String description = optionalString(arguments, "description", "");
            String location = optionalString(arguments, "location", "");
            String calendarId = optionalString(arguments, "calendar_id", "primary");

            Event event = new Event()
                    .setSummary(summary)
                    .setDescription(description.isBlank() ? null : description)
                    .setLocation(location.isBlank() ? null : location);
            event.setStart(toEventDateTime(start, timezone));
            event.setEnd(toEventDateTime(end, timezone));

            Event created = google.calendar().events().insert(calendarId, event).execute();
            return "Created calendar event '" + summary + "'.\n"
                    + "eventId: " + created.getId() + "\n"
                    + "url: " + created.getHtmlLink();
        });
    }

    private static EventDateTime toEventDateTime(String value, String timezone) {
        if (value.matches("\\d{4}-\\d{2}-\\d{2}$")) {
            return new EventDateTime().setDate(com.google.api.client.util.DateTime.parseRfc3339(value));
        }
        try {
            ZonedDateTime zdt = ZonedDateTime.parse(value);
            return new EventDateTime()
                    .setDateTime(new com.google.api.client.util.DateTime(zdt.toInstant().toEpochMilli()))
                    .setTimeZone(zdt.getZone().getId());
        } catch (Exception ignored) {
            try {
                OffsetDateTime odt = OffsetDateTime.parse(value);
                return new EventDateTime()
                        .setDateTime(new com.google.api.client.util.DateTime(odt.toInstant().toEpochMilli()))
                        .setTimeZone(timezone);
            } catch (Exception e) {
                LocalDate date = LocalDate.parse(value);
                return new EventDateTime().setDate(com.google.api.client.util.DateTime.parseRfc3339(date.toString()));
            }
        }
    }
}
