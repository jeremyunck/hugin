package com.example.integration.tool;

import com.example.agent.tool.LocalTool;
import com.example.integration.google.GoogleIds;
import com.example.integration.google.GoogleSheetValues;
import com.example.integration.google.GoogleWorkspaceClientFactory;
import com.google.api.services.sheets.v4.model.AppendValuesResponse;
import com.google.api.services.sheets.v4.model.ValueRange;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Appends rows to the end of the existing data in a Google Sheet. */
@Component
public class GoogleSheetsAppendTool implements LocalTool {

    private final GoogleWorkspaceClientFactory google;

    public GoogleSheetsAppendTool(GoogleWorkspaceClientFactory google) {
        this.google = google;
    }

    @Override
    public String name() {
        return "google_sheets_append";
    }

    @Override
    public String description() {
        return "Append rows after the last row of data in a Google Sheet (does not overwrite existing "
                + "cells). 'range' selects the table/tab to append to (e.g. 'Sheet1'); 'values' is a 2-D "
                + "array of rows. Values are parsed like typed entry (USER_ENTERED).";
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
                                "description", "A1 range identifying the table/tab to append to, e.g. 'Sheet1'."),
                        "values", Map.of(
                                "type", "array",
                                "description", "2-D array of rows to append, e.g. [[\"Ada\",36],[\"Alan\",41]].",
                                "items", Map.of("type", "array"))),
                "required", List.of("spreadsheet_id", "range", "values"));
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        return google.guarded(() -> {
            String spreadsheetId = GoogleIds.extract(requiredString(arguments, "spreadsheet_id"));
            String range = requiredString(arguments, "range");
            List<List<Object>> rows = GoogleSheetValues.toRows(arguments.get("values"));

            AppendValuesResponse response = google.sheets().spreadsheets().values()
                    .append(spreadsheetId, range, new ValueRange().setValues(rows))
                    .setValueInputOption("USER_ENTERED")
                    .setInsertDataOption("INSERT_ROWS")
                    .execute();

            String updatedRange = response.getUpdates() != null ? response.getUpdates().getUpdatedRange() : range;
            Integer updatedCells = response.getUpdates() != null ? response.getUpdates().getUpdatedCells() : null;
            return "Appended " + rows.size() + " row(s)"
                    + (updatedCells != null ? " (" + updatedCells + " cells)" : "")
                    + " to " + updatedRange + ".\n"
                    + "url: " + GoogleIds.sheetUrl(spreadsheetId);
        });
    }
}
