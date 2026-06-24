package com.example.integration.google;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Carries out the actual move-to-Trash for Gmail messages once a user has approved a
 * {@code google_gmail_delete} request. Kept separate from {@link com.example.integration.tool.GoogleGmailDeleteTool}
 * (which only gathers a preview and asks for approval) so the deletion runs from the approval
 * endpoint, never from the agent loop.
 *
 * <p>Uses {@code trash} rather than a permanent delete: it is reversible from Gmail and only needs the
 * {@code gmail.modify} scope, keeping the integration's footprint smaller than a hard delete would.
 */
@Component
public class GmailDeletionService {

    private static final Logger log = LoggerFactory.getLogger(GmailDeletionService.class);

    private final GoogleWorkspaceClientFactory google;

    public GmailDeletionService(GoogleWorkspaceClientFactory google) {
        this.google = google;
    }

    /** The outcome of a batch trash: how many succeeded and which ids failed. */
    public record Result(int deleted, List<String> failures) {}

    /** Moves each message id to Trash, tolerating per-message failures so one bad id can't block the rest. */
    public Result trash(List<String> messageIds) {
        int deleted = 0;
        List<String> failures = new ArrayList<>();
        if (messageIds == null) {
            return new Result(0, failures);
        }
        for (String id : messageIds) {
            if (id == null || id.isBlank()) {
                continue;
            }
            try {
                google.gmail().users().messages().trash("me", id).execute();
                deleted++;
            } catch (Exception e) {
                log.warn("Failed to move Gmail message {} to Trash: {}", id, e.getMessage());
                failures.add(id);
            }
        }
        return new Result(deleted, failures);
    }
}
