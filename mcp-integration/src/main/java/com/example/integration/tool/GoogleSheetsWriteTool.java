package com.example.integration.tool;

import com.example.agent.tool.LocalTool;
import com.example.integration.google.GoogleIds;
import com.example.integration.google.GoogleSheetValues;
import com.example.integration.google.GoogleWorkspaceClientFactory;
import com.google.api.services.sheets.v4.model.UpdateValuesResponse;
import com.google.api.services.sheets.v4.model.ValueRange;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Writes (overwrites) cell values into a range of a Google Sheet. */
@Component
public class GoogleSheetsWriteTool implements LocalTool {

    private final GoogleWorkspaceClientFactory google;

    public GoogleSheetsWriteTool(GoogleWorkspaceClientFactory google) {
        this.google = google;
    }

    @Override
    public String name() {
        return "google_sheets_write";
    }

    @Override
    public String description() {
        return "Write values into a Google Sheet, overwriting the given range starting at its top-left "
                + "cell. 'range' is A1 notation (e.g. 'Sheet1!A1'); 'values' is a 2-D array of rows. "
                + "Values are parsed like typed entry (USER_ENTERED), so formulas and numbers work. "
                + "Use google_sheets_append to add rows instead of overwriting.";
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
                                "description", "A1 notation anchor/range, e.g. 'Sheet1!A1' or 'Sheet1!A1:C3'."),
                        "values", Map.of(
                                "type", "array",
                                "description", "2-D array of rows, e.g. [[\"Name\",\"Age\"],[\"Ada\",36]].",
                                "items", Map.of("type", "array"))),
                "required", List.of("spreadsheet_id", "range", "values"));
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        return google.guarded(() -> {
            String spreadsheetId = GoogleIds.extract(requiredString(arguments, "spreadsheet_id"));
            String range = requiredString(arguments, "range");
            List<List<Object>> rows = GoogleSheetValues.toRows(arguments.get("values"));

            UpdateValuesResponse response = google.sheets().spreadsheets().values()
                    .update(spreadsheetId, range, new ValueRange().setValues(rows))
                    .setValueInputOption("USER_ENTERED")
                    .execute();

            return "Updated " + response.getUpdatedCells() + " cells in range " + response.getUpdatedRange() + ".\n"
                    + "url: " + GoogleIds.sheetUrl(spreadsheetId);
        });
    }
}
