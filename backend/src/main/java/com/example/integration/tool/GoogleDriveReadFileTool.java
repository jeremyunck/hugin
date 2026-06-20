package com.example.integration.tool;

import com.example.agent.tool.LocalTool;
import com.example.integration.google.GoogleIds;
import com.example.integration.google.GoogleWorkspaceClientFactory;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.User;
import com.google.api.services.sheets.v4.model.Sheet;
import com.google.api.services.sheets.v4.model.Spreadsheet;
import com.google.api.services.sheets.v4.model.ValueRange;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Reads metadata and, where supported, textual content from a Google Drive file. */
@Component
public class GoogleDriveReadFileTool implements LocalTool {

    private static final String GOOGLE_DOC_MIME = "application/vnd.google-apps.document";
    private static final String GOOGLE_SHEET_MIME = "application/vnd.google-apps.spreadsheet";
    private static final int SHEET_ROW_LIMIT = 10;
    private static final int SHEET_COLUMN_LIMIT = 8;

    private final GoogleWorkspaceClientFactory google;

    public GoogleDriveReadFileTool(GoogleWorkspaceClientFactory google) {
        this.google = google;
    }

    @Override
    public boolean isAvailable() {
        return google.available();
    }

    @Override
    public String name() {
        return "google_drive_read_file";
    }

    @Override
    public String description() {
        return "Read a Google Drive file by id. Returns metadata for all files, plain text content for "
                + "Google Docs and text-like files, and spreadsheet metadata plus sample rows for Google Sheets.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "fileId", Map.of(
                                "type", "string",
                                "description", "The Google Drive file id, or a full Google Drive/Docs/Sheets URL.")),
                "required", List.of("fileId"));
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        return google.guarded(() -> {
            String fileId = GoogleIds.extract(requiredString(arguments, "fileId"));
            File file = google.drive().files().get(fileId)
                    .setFields("id,name,mimeType,webViewLink,modifiedTime,owners(displayName,emailAddress),size")
                    .execute();

            StringBuilder sb = new StringBuilder();
            appendMetadata(sb, file);
            sb.append('\n');

            String mimeType = file.getMimeType() == null ? "" : file.getMimeType();
            if (GOOGLE_DOC_MIME.equals(mimeType)) {
                sb.append("content:\n").append(exportGoogleDocText(fileId));
                return sb.toString().trim();
            }
            if (GOOGLE_SHEET_MIME.equals(mimeType)) {
                sb.append(readSpreadsheetPreview(fileId));
                return sb.toString().trim();
            }
            if (isTextLike(mimeType)) {
                sb.append("content:\n").append(downloadTextContent(fileId));
                return sb.toString().trim();
            }

            sb.append("content: Binary extraction is not supported yet for this file type.");
            return sb.toString().trim();
        });
    }

    private void appendMetadata(StringBuilder sb, File file) {
        sb.append("source:\n");
        sb.append("id: ").append(orBlank(file.getId())).append('\n');
        sb.append("name: ").append(orBlank(file.getName())).append('\n');
        sb.append("mimeType: ").append(orBlank(file.getMimeType())).append('\n');
        if (file.getWebViewLink() != null && !file.getWebViewLink().isBlank()) {
            sb.append("webViewLink: ").append(file.getWebViewLink()).append('\n');
        }
        if (file.getModifiedTime() != null) {
            sb.append("modifiedTime: ").append(file.getModifiedTime()).append('\n');
        }
        String owners = formatOwners(file.getOwners());
        if (!owners.isBlank()) {
            sb.append("owners: ").append(owners).append('\n');
        }
    }

    private String exportGoogleDocText(String fileId) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        google.drive().files().export(fileId, "text/plain").executeMediaAndDownloadTo(out);
        String text = out.toString(StandardCharsets.UTF_8);
        return text.isBlank() ? "(document is empty)" : text.trim();
    }

    private String readSpreadsheetPreview(String spreadsheetId) throws Exception {
        Spreadsheet spreadsheet = google.sheets().spreadsheets().get(spreadsheetId)
                .setFields("properties.title,sheets(properties(sheetId,title,index,hidden))")
                .execute();

        StringBuilder sb = new StringBuilder();
        sb.append("spreadsheetTitle: ").append(
                spreadsheet.getProperties() == null ? "" : orBlank(spreadsheet.getProperties().getTitle()))
                .append('\n');

        List<Sheet> sheets = spreadsheet.getSheets();
        if (sheets == null || sheets.isEmpty()) {
            sb.append("sheets: (none)");
            return sb.toString();
        }

        List<Sheet> visibleSheets = sheets.stream()
                .filter(sheet -> sheet.getProperties() != null && !Boolean.TRUE.equals(sheet.getProperties().getHidden()))
                .toList();
        if (visibleSheets.isEmpty()) {
            sb.append("sheets: (no visible sheets)");
            return sb.toString();
        }

        sb.append("sheets:\n");
        for (Sheet sheet : visibleSheets) {
            String title = sheet.getProperties().getTitle();
            String range = quotedSheetTitle(title) + "!A1:" + columnLetter(SHEET_COLUMN_LIMIT) + SHEET_ROW_LIMIT;
            ValueRange values = google.sheets().spreadsheets().values().get(spreadsheetId, range).execute();
            sb.append("- ").append(title).append('\n');
            List<List<Object>> rows = values.getValues();
            if (rows == null || rows.isEmpty()) {
                sb.append("  (no visible data in first ").append(SHEET_ROW_LIMIT).append(" rows)\n");
                continue;
            }
            for (List<Object> row : rows) {
                sb.append("  ").append(row.stream().map(String::valueOf).collect(Collectors.joining("\t"))).append('\n');
            }
        }
        return sb.toString().trim();
    }

    private String downloadTextContent(String fileId) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        google.drive().files().get(fileId).executeMediaAndDownloadTo(out);
        String text = out.toString(StandardCharsets.UTF_8);
        return text.isBlank() ? "(file is empty)" : text.trim();
    }

    private boolean isTextLike(String mimeType) {
        if (mimeType == null || mimeType.isBlank()) {
            return false;
        }
        return mimeType.startsWith("text/")
                || mimeType.equals("application/json")
                || mimeType.equals("application/ld+json")
                || mimeType.equals("application/xml")
                || mimeType.equals("application/javascript")
                || mimeType.equals("application/x-javascript")
                || mimeType.equals("application/yaml")
                || mimeType.equals("application/x-yaml")
                || mimeType.equals("application/csv");
    }

    private String formatOwners(List<User> owners) {
        if (owners == null || owners.isEmpty()) {
            return "";
        }
        return owners.stream()
                .map(owner -> {
                    String name = owner.getDisplayName() == null ? "" : owner.getDisplayName().trim();
                    String email = owner.getEmailAddress() == null ? "" : owner.getEmailAddress().trim();
                    if (!name.isBlank() && !email.isBlank()) {
                        return name + " <" + email + ">";
                    }
                    return !name.isBlank() ? name : email;
                })
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining(", "));
    }

    private String quotedSheetTitle(String title) {
        return "'" + title.replace("'", "''") + "'";
    }

    private String columnLetter(int columnNumber) {
        int value = Math.max(1, columnNumber);
        StringBuilder result = new StringBuilder();
        while (value > 0) {
            int remainder = (value - 1) % 26;
            result.insert(0, (char) ('A' + remainder));
            value = (value - 1) / 26;
        }
        return result.toString();
    }

    private String orBlank(String value) {
        return value == null ? "" : value;
    }
}
