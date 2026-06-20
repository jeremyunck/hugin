package com.example.integration.tool;

import com.example.agent.tool.LocalTool;
import com.example.integration.google.GoogleWorkspaceClientFactory;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.api.services.drive.model.User;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Searches the authenticated Google Drive for files matching an optional text query. */
@Component
public class GoogleDriveSearchTool implements LocalTool {

    private static final int DEFAULT_MAX_RESULTS = 10;
    private static final int MAX_RESULTS_LIMIT = 50;

    private final GoogleWorkspaceClientFactory google;

    public GoogleDriveSearchTool(GoogleWorkspaceClientFactory google) {
        this.google = google;
    }

    @Override
    public boolean isAvailable() {
        return google.available();
    }

    @Override
    public String name() {
        return "google_drive_search";
    }

    @Override
    public String description() {
        return "Search or list files in Google Drive. Optionally filter by a text query and/or mime type. "
                + "Returns file ids, names, mime types, links, modified times, and owners when available.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of(
                                "type", "string",
                                "description", "Optional text query to match against Drive file names and searchable text."),
                        "mimeType", Map.of(
                                "type", "string",
                                "description", "Optional exact MIME type filter, e.g. 'application/vnd.google-apps.document'."),
                        "maxResults", Map.of(
                                "type", "integer",
                                "minimum", 1,
                                "maximum", MAX_RESULTS_LIMIT,
                                "description", "Maximum number of files to return. Defaults to 10.")),
                "required", List.of());
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        return google.guarded(() -> {
            String query = optionalString(arguments, "query", "").trim();
            String mimeType = optionalString(arguments, "mimeType", "").trim();
            int maxResults = clampResults(optionalInt(arguments, "maxResults", DEFAULT_MAX_RESULTS));

            com.google.api.services.drive.Drive.Files.List request = google.drive().files()
                    .list()
                    .setPageSize(maxResults)
                    .setFields("files(id,name,mimeType,webViewLink,modifiedTime,owners(displayName,emailAddress))");

            String driveQuery = buildDriveQuery(query, mimeType);
            if (!driveQuery.isBlank()) {
                request.setQ(driveQuery);
            }
            if (query.isBlank()) {
                request.setOrderBy("modifiedTime desc");
            }

            FileList response = request.execute();
            List<File> files = response.getFiles();
            if (files == null || files.isEmpty()) {
                return query.isBlank()
                        ? "(no Google Drive files found)"
                        : "(no Google Drive files found for query: " + query + ")";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Google Drive files");
            if (!query.isBlank()) {
                sb.append(" for query: ").append(query);
            }
            if (!mimeType.isBlank()) {
                sb.append(" [mimeType=").append(mimeType).append(']');
            }
            sb.append('\n');

            for (int i = 0; i < files.size(); i++) {
                File file = files.get(i);
                sb.append(i + 1).append(". id: ").append(orBlank(file.getId())).append('\n');
                sb.append("   name: ").append(orBlank(file.getName())).append('\n');
                sb.append("   mimeType: ").append(orBlank(file.getMimeType())).append('\n');
                if (file.getModifiedTime() != null) {
                    sb.append("   modifiedTime: ").append(file.getModifiedTime()).append('\n');
                }
                if (file.getWebViewLink() != null && !file.getWebViewLink().isBlank()) {
                    sb.append("   webViewLink: ").append(file.getWebViewLink()).append('\n');
                }
                String owners = formatOwners(file.getOwners());
                if (!owners.isBlank()) {
                    sb.append("   owners: ").append(owners).append('\n');
                }
            }
            return sb.toString().trim();
        });
    }

    private int clampResults(int requested) {
        if (requested <= 0) {
            return DEFAULT_MAX_RESULTS;
        }
        return Math.min(requested, MAX_RESULTS_LIMIT);
    }

    private String buildDriveQuery(String query, String mimeType) {
        StringBuilder sb = new StringBuilder("trashed = false");
        if (!mimeType.isBlank()) {
            sb.append(" and mimeType = '").append(escapeDriveQueryValue(mimeType)).append("'");
        }
        if (!query.isBlank()) {
            String escaped = escapeDriveQueryValue(query);
            sb.append(" and (name contains '").append(escaped)
                    .append("' or fullText contains '").append(escaped).append("')");
        }
        return sb.toString();
    }

    private String escapeDriveQueryValue(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
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

    private String orBlank(String value) {
        return value == null ? "" : value;
    }
}
