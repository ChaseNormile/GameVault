package com.gamevault.igdb;

import com.gamevault.games.GameSearchResult;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Service
public class IgdbSearchService {
    private final RestClient http;
    private final TwitchTokenProvider tokens;
    private final String clientId;

    public IgdbSearchService(RestClient http, TwitchTokenProvider tokens,
            @Value("${igdb.client-id}") String clientId) {
        this.http = http;
        this.tokens = tokens;
        this.clientId = clientId;
    }

    public List<GameSearchResult> search(String term) {
        // Escape backslashes before quotes so input remains inside one string literal.
        String escaped = term.replace("\\", "\\\\").replace("\"", "\\\"");
        String query = "fields id,name,platforms.name,cover.url,first_release_date; limit 10; search \""
                + escaped + "\";";
        String token = tokens.getAccessToken();
        try {
            return request(query, token);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode() != HttpStatus.UNAUTHORIZED) throw new IgdbException();
            tokens.invalidate(token);
        } catch (RestClientException ex) {
            throw new IgdbException();
        }
        // A revoked token may fail before its expiry. Refresh and retry only once.
        try {
            return request(query, tokens.getAccessToken());
        } catch (RestClientException ex) {
            throw new IgdbException();
        }
    }

    private List<GameSearchResult> request(String query, String token) {
        IgdbGame[] games = http.post().uri("https://api.igdb.com/v4/games")
                .header("Client-ID", clientId)
                .headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.TEXT_PLAIN).accept(MediaType.APPLICATION_JSON)
                .body(query).retrieve().body(IgdbGame[].class);
        if (games == null || Arrays.stream(games).anyMatch(game -> game == null || game.name() == null)) {
            throw new IgdbException();
        }
        return Arrays.stream(games).map(IgdbGame::toResult).toList();
    }
}
