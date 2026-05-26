package com.blen.core.auth;

/**
 * Holds the current user session data (cracked/offline mode).
 */
public class Session {
    private final String username;
    private final String uuid;
    private final String accessToken;

    public Session(String username, String uuid, String accessToken) {
        this.username = username;
        this.uuid = uuid;
        this.accessToken = accessToken;
    }

    public String getUsername() { return username; }
    public String getUuid() { return uuid; }
    public String getAccessToken() { return accessToken; }

    public boolean isValid() {
        return username != null && !username.isBlank()
            && uuid != null && !uuid.isBlank()
            && accessToken != null && !accessToken.isBlank();
    }
}
