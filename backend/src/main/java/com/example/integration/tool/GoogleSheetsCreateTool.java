package com.example.integration.tool;

import com.example.agent.tool.LocalTool;
import com.example.integration.google.GoogleIds;
import com.example.integration.google.GoogleWorkspaceClientFactory;
import com.google.api.services.sheets.v4.model.Spreadsheet;
import com.google.api.services.sheets.v4.model.SpreadsheetProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Creates a new Google Sheets spreadsheet, optionally shared with a user. */
@Component
public class GoogleSheetsCreateTool implements LocalTool {

    private final GoogleWorkspaceClientFactory google;

    public GoogleSheetsCreateTool(GoogleWorkspaceClientFactory google) {
        this.google = google;
    }

    @Override
    public boolean isAvailable() {
        return google.available();
    }

    @Override
    public String name() {
        return "google_sheets_create";
    }

    @Override
    public String description() {
        return "Create a new Google Sheets spreadsheet. Optionally provide a title. "
                + "Returns the new spreadsheet's id and shareable URL. Note: it is owned by the "
                + "service account; pass 'share_with' (an email) so a person can open it.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "title", Map.of(
                                "type", "string",
                                "description", "Title of the new spreadsheet. Defaults to 'Untitled spreadsheet'."),
                        "share_with", Map.of(
                                "type", "string",
                                "description", "Optional email address to share the new sheet with (as editor).")),
                "required", List.of());
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        return google.guarded(() -> {
            String title = optionalString(arguments, "title", "Untitled spreadsheet");
            Spreadsheet created = google.sheets().spreadsheets()
                    .create(new Spreadsheet().setProperties(new SpreadsheetProperties().setTitle(title)))
                    .execute();
            String spreadsheetId = created.getSpreadsheetId();

            String shareError = google.shareFile(spreadsheetId,
                    optionalString(arguments, "share_with", google.defaultShareWith()), "writer");

            StringBuilder sb = new StringBuilder();
            sb.append("Created Google Sheet '").append(title).append("'.\n");
            sb.append("spreadsheetId: ").append(spreadsheetId).append('\n');
            sb.append("url: ").append(GoogleIds.sheetUrl(spreadsheetId));
            if (shareError != null) {
                sb.append("\nWarning: ").append(shareError);
            }
            return sb.toString();
        });
    }
}
