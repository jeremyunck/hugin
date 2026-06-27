package com.example.integration.google;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;

import java.io.FileNotFoundException;

/** Maps exceptions thrown by Google API calls into concise, actionable messages for the agent. */
public final class GoogleErrors {

    private GoogleErrors() {
    }

    /**
     * Turns a Google API / credential exception into a short message the model can relay. The most
     * common real failure with a service account is a 403/404 because the target file wasn't shared
     * with the service account, so that case gets an explicit hint.
     */
    public static String describe(Exception e) {
        if (e instanceof GoogleJsonResponseException g) {
            int code = g.getStatusCode();
            String detail = (g.getDetails() != null && g.getDetails().getMessage() != null)
                    ? g.getDetails().getMessage()
                    : g.getStatusMessage();
            if ("The user's Drive storage quota has been exceeded.".equals(detail)
                    || (g.getDetails() != null && g.getDetails().getErrors() != null
                    && g.getDetails().getErrors().stream()
                    .anyMatch(err -> "storageQuotaExceeded".equals(err.getReason())))) {
                return "Google API error 403 (Drive storage quota exceeded for the service account). "
                        + "Free space in the authenticated Google account's Drive or create the document in a shared drive, "
                        + "then try again.";
            }
            if (code == 403 || code == 404) {
                return "Google API error " + code + " (" + detail + "). The document or spreadsheet may "
                        + "not exist, or the authenticated Google account may not have access. Share the file "
                        + "with that account (or set google.default-share-with for files Bouw creates), "
                        + "then try again.";
            }
            return "Google API error " + code + ": " + detail;
        }
        if (e instanceof FileNotFoundException) {
            return "Google credentials file not found: " + e.getMessage();
        }
        return "Google Workspace request failed: " + e.getMessage();
    }
}
