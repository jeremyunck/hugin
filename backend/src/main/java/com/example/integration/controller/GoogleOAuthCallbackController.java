package com.example.integration.controller;

import com.example.integration.google.GoogleWorkspaceClientFactory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Handles the OAuth callback from Google's authorization server after a user consents to the
 * Google Workspace integration. Google redirects to this endpoint with an authorization code
 * ({@code ?code=...}) which is exchanged for access and refresh tokens.
 *
 * <p>The endpoint is mapped to {@code GET /Callback} — the path registered in the Google Cloud
 * Console as the OAuth redirect URI. The code is forwarded to
 * {@link GoogleWorkspaceClientFactory#handleOAuthCallback(String)} which stores the tokens
 * and makes the Workspace tools available.
 */
@Controller
public class GoogleOAuthCallbackController {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthCallbackController.class);

    private final GoogleWorkspaceClientFactory google;

    public GoogleOAuthCallbackController(GoogleWorkspaceClientFactory google) {
        this.google = google;
    }

    @GetMapping("/Callback")
    public void callback(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String code = request.getParameter("code");
        String state = request.getParameter("state");
        String error = request.getParameter("error");

        response.setContentType("text/html;charset=UTF-8");

        if (error != null && !error.isBlank()) {
            log.warn("Google OAuth callback received error: {}", error);
            writePage(response, "Google connection failed",
                    "Google returned an error: " + error + ".",
                    "https://bouw.thecognitivejunction.com/");
            return;
        }

        if (code == null || code.isBlank()) {
            log.warn("Google OAuth callback received without a code parameter");
            writePage(response, "Google connection failed",
                    "The callback didn't include an authorization code. Please try reconnecting.",
                    "https://bouw.thecognitivejunction.com/");
            return;
        }

        // Verify state for CSRF protection (if a state was expected).
        if (state != null && !state.isBlank()) {
            if (!google.verifyOAuthState(state)) {
                log.warn("Google OAuth callback with mismatched state — possible CSRF");
                writePage(response, "Google connection failed",
                        "The security check failed. Please try reconnecting.",
                        "https://bouw.thecognitivejunction.com/");
                return;
            }
        }

        boolean success = google.handleOAuthCallback(code);

        if (success) {
            String returnTo = request.getParameter("state");
            // The state parameter may also encode the return-to URL in some flows;
            // default to the main app URL.
            String target = "https://bouw.thecognitivejunction.com/";
            writePage(response, "Google connected",
                    "Your Google account is now connected to Bouw.",
                    target);
        } else {
            writePage(response, "Google connection failed",
                    "Could not exchange the authorization code for tokens. Please try reconnecting.",
                    "https://bouw.thecognitivejunction.com/");
        }
    }

    private static void writePage(HttpServletResponse response, String headline, String message, String target)
            throws IOException {
        String escapedHeadline = escapeHtml(headline);
        String escapedMessage = escapeHtml(message);
        String escapedTarget = escapeHtml(target);
        String jsTarget = escapeJsString(target);

        String page = """
                <!doctype html>
                <html lang="en">
                  <head>
                    <meta charset="utf-8" />
                    <meta name="viewport" content="width=device-width, initial-scale=1" />
                    <title>%s</title>
                    <meta http-equiv="refresh" content="0;url=%s" />
                    <style>
                      body {
                        font-family: Inter, Arial, sans-serif;
                        background: #0b1220;
                        color: #e5eefc;
                        margin: 0;
                        min-height: 100vh;
                        display: grid;
                        place-items: center;
                      }
                      .card {
                        max-width: 520px;
                        padding: 24px 28px;
                        border-radius: 16px;
                        background: rgba(15, 23, 42, 0.9);
                        border: 1px solid rgba(148, 163, 184, 0.24);
                        box-shadow: 0 24px 80px rgba(15, 23, 42, 0.45);
                      }
                      h1 {
                        margin: 0 0 8px;
                        font-size: 24px;
                      }
                      p {
                        margin: 0 0 12px;
                        line-height: 1.5;
                        color: #bfd0ea;
                      }
                      code {
                        display: block;
                        word-break: break-all;
                        padding: 10px 12px;
                        border-radius: 10px;
                        background: rgba(30, 41, 59, 0.9);
                        color: #dbeafe;
                      }
                    </style>
                  </head>
                  <body>
                    <div class="card">
                      <h1>%s</h1>
                      <p>%s</p>
                      <code>%s</code>
                    </div>
                    <script>
                      window.setTimeout(() => window.location.replace(%s), 100);
                    </script>
                  </body>
                </html>
                """.formatted(escapedHeadline, escapedTarget, escapedHeadline, escapedMessage, escapedTarget, jsTarget);

        try (PrintWriter w = response.getWriter()) {
            w.write(page);
        }
    }

    private static String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String escapeJsString(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}