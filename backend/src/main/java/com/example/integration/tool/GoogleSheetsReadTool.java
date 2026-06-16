package com.example.integration.tool;

import com.example.agent.tool.LocalTool;
import com.example.integration.google.GoogleIds;
import com.example.integration.google.GoogleWorkspaceClientFactory;
import com.google.api.services.sheets.v4.model.ValueRange;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Reads cell values from a range of a Google Sheet. */
@Component
public class GoogleSheetsReadTool implements LocalTool {

    private final GoogleWorkspaceClientFactory google;

    public GoogleSheetsReadTool(GoogleWorkspaceClientFactory google) {
        this.google = google;
    }

    @Override
    public boolean isAvailable() {
        return google.available();
    }

    @Override
    public String name() {
        return "google_sheets_read";
    }

    @Override
    public String description() {
        return "Read cell values from a Google Sheet. Provide an A1 'range' such as 'Sheet1!A1:C10' "
                + "(or just a sheet/tab name to read all of it). Returns the rows as tab-separated text. "
                + "Accepts a spreadsheet id or a full Google Sheets URL.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "spreadsheet_id", Map.of(
                                "type", "string",
                                "description", "The spreadsheet id, or a full docs.google.com/spreadsheets URL."),
                        "range", Map.of(
                                "type", "string",
                                "description", "A1 notation range, e.g. 'Sheet1!A1:C10', or a tab name for the whole tab.")),
                "required", List.of("spreadsheet_id", "range"));
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        return google.guarded(() -> {
            String spreadsheetId = GoogleIds.extract(requiredString(arguments, "spreadsheet_id"));
            String range = requiredString(arguments, "range");

            ValueRange response = google.sheets().spreadsheets().values()
                    .get(spreadsheetId, range).execute();

            List<List<Object>> values = response.getValues();
            if (values == null || values.isEmpty()) {
                return "(no data in range " + range + ")";
            }

            String body = values.stream()
                    .map(row -> row.stream().map(String::valueOf).collect(Collectors.joining("\t")))
                    .collect(Collectors.joining("\n"));
            return "Range: " + response.getRange() + " (" + values.size() + " rows)\n" + body;
        });
    }
}
