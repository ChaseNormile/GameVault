package com.gamevault.igdb;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class TwitchTokenProvider {
    private final RestClient http;
    private final Clock clock;
    private final String clientId;
    private final String clientSecret;
    private String accessToken;
    private Instant refreshAt = Instant.MIN;

    public TwitchTokenProvider(RestClient http, Clock clock,
            @Value("${igdb.client-id}") String clientId,
            @Value("${igdb.client-secret}") String clientSecret) {
        this.http = http;
        this.clock = clock;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    // One refresh at a time, even if multiple search requests arrive together.
    public synchronized String getAccessToken() {
        if (accessToken != null && clock.instant().isBefore(refreshAt)) {
            return accessToken;
        }
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw new IgdbException();
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("grant_type", "client_credentials");
        Instant requestedAt = clock.instant();
        try {
            TokenResponse token = http.post().uri("https://id.twitch.tv/oauth2/token")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().body(TokenResponse.class);
            if (token == null || token.accessToken() == null || token.accessToken().isBlank()
                    || token.expiresIn() <= 0 || !"bearer".equalsIgnoreCase(token.tokenType())) {
                throw new IgdbException();
            }
            // Refresh 60 seconds early, or halfway through unusually short lifetimes.
            refreshAt = requestedAt.plusSeconds(token.expiresIn() - Math.min(60, token.expiresIn() / 2));
            accessToken = token.accessToken();
            return accessToken;
        } catch (RestClientException | IllegalArgumentException ex) {
            throw new IgdbException();
        }
    }

    public synchronized void invalidate(String rejectedToken) {
        if (rejectedToken.equals(accessToken)) {
            accessToken = null;
            refreshAt = Instant.MIN;
        }
    }

    public record TokenResponse(@JsonProperty("access_token") String accessToken,
            @JsonProperty("expires_in") long expiresIn,
            @JsonProperty("token_type") String tokenType) {}
}
