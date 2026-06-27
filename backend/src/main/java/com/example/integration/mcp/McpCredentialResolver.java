package com.example.integration.mcp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolves the bearer credential to send to a server, by auth type:
 * <ul>
 *   <li>{@link McpAuthType#NONE} → none ({@code null}).</li>
 *   <li>{@link McpAuthType#BEARER_TOKEN} → the user's decrypted static token.</li>
 *   <li>{@link McpAuthType#OAUTH} → a valid OAuth access token, refreshed transparently when it is
 *       expired or about to expire (see {@link McpOAuthService}).</li>
 * </ul>
 *
 * <p>Decrypted secrets never leave the MCP package and are never logged.
 */
@Service
public class McpCredentialResolver {

    private static final Logger log = LoggerFactory.getLogger(McpCredentialResolver.class);

    private final McpSecretEncryptionService encryption;
    private final McpOAuthService oauthService;

    public McpCredentialResolver(McpSecretEncryptionService encryption, McpOAuthService oauthService) {
        this.encryption = encryption;
        this.oauthService = oauthService;
    }

    /** The bearer token to attach for this server, or {@code null} when none applies. */
    public String resolveBearer(McpServerEntity server) {
        return switch (server.authType()) {
            case NONE -> null;
            case BEARER_TOKEN -> decryptStatic(server);
            case OAUTH -> oauthService.currentAccessToken(server);
        };
    }

    private String decryptStatic(McpServerEntity server) {
        if (!server.hasToken()) {
            return null;
        }
        try {
            return encryption.decrypt(server.accessTokenEncrypted());
        } catch (RuntimeException e) {
            log.warn("Could not decrypt bearer token for MCP server {}: {}", server.id(), e.getMessage());
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Stored credentials for this MCP server could not be decrypted. Re-enter the bearer token.");
        }
    }
}
