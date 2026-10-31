package com.gamevault.igdb;

import com.gamevault.games.GameSearchController;
import com.gamevault.games.GameSearchExceptionHandler;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.*;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class GameSearchIntegrationTest {
    private MockRestServiceServer server;
    private MockMvc mvc;
    private TwitchTokenProvider tokens;
    private final MutableClock clock = new MutableClock();

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient http = builder.build();
        tokens = new TwitchTokenProvider(http, clock, "test-client", "test-secret");
        var service = new IgdbSearchService(http, tokens, "test-client");
        mvc = MockMvcBuilders.standaloneSetup(new GameSearchController(service))
                .setControllerAdvice(new GameSearchExceptionHandler()).build();
    }

    private void expectToken(String token, long lifetime) {
        server.expect(requestTo("https://id.twitch.tv/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(MockRestRequestMatchers.content().contentType(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(MockRestRequestMatchers.content().string("client_id=test-client&client_secret=test-secret&grant_type=client_credentials"))
                .andRespond(withSuccess("{\"access_token\":\"" + token
                        + "\",\"expires_in\":" + lifetime + ",\"token_type\":\"bearer\"}", MediaType.APPLICATION_JSON));
    }

    private void expectGames(String token, String response) {
        server.expect(requestTo("https://api.igdb.com/v4/games"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Client-ID", "test-client"))
                .andExpect(header("Authorization", "Bearer " + token))
                .andExpect(MockRestRequestMatchers.content().contentType(MediaType.TEXT_PLAIN))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    @Test
    void mapsMetadataAndReusesTokenAcrossSearches() throws Exception {
        expectToken("cached", 3600);
        expectGames("cached", """
                [{"id":1942,"name":"Skyrim","platforms":[{"id":6,"name":"PC"}],
                  "cover":{"url":"//images.igdb.com/cover.jpg"},"first_release_date":1320969600}]
                """);
        expectGames("cached", "[{\"id\":1,\"name\":\"Unknown metadata\"}]");
        mvc.perform(get("/api/games/search").param("q", "Skyrim"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1942))
                .andExpect(jsonPath("$[0].platforms[0]").value("PC"))
                .andExpect(jsonPath("$[0].coverUrl").value("https://images.igdb.com/cover.jpg"))
                .andExpect(jsonPath("$[0].firstReleaseDate").value(1320969600));
        mvc.perform(get("/api/games/search").param("q", "Other"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].platforms").isEmpty())
                .andExpect(jsonPath("$[0].coverUrl").isEmpty())
                .andExpect(jsonPath("$[0].firstReleaseDate").isEmpty());
        server.verify();
    }

    @Test
    void escapesQuerySyntaxAndKeepsTheFixedFieldsAndLimit() throws Exception {
        expectToken("token", 3600);
        server.expect(requestTo("https://api.igdb.com/v4/games"))
                .andExpect(MockRestRequestMatchers.content().string("fields id,name,platforms.name,cover.url,first_release_date; limit 10; search \"x\\\\\\\"; limit 500;\\\"\";"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        mvc.perform(get("/api/games/search").param("q", "  x\\\"; limit 500;\"  "))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        server.verify();
    }

    @Test
    void refreshesAtTheExpirySafetyWindow() {
        expectToken("first", 3600);
        expectToken("second", 3600);
        assertEquals("first", tokens.getAccessToken());
        clock.now = clock.now.plusSeconds(3539);
        assertEquals("first", tokens.getAccessToken());
        clock.now = clock.now.plusSeconds(1);
        assertEquals("second", tokens.getAccessToken());
        server.verify();
    }

    @Test
    void refreshesARejectedTokenAndRetriesOnce() throws Exception {
        expectToken("old", 3600);
        server.expect(requestTo("https://api.igdb.com/v4/games"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        expectToken("new", 3600);
        expectGames("new", "[]");
        mvc.perform(get("/api/games/search").param("q", "Skyrim"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "bad\nquery", "bad\u0000query"})
    void rejectsInvalidQueriesWithoutCallingUpstream(String query) throws Exception {
        mvc.perform(get("/api/games/search").param("q", query))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SEARCH_QUERY"));
        server.verify();
    }

    @Test
    void rejectsMissingAndOverlongQueries() throws Exception {
        mvc.perform(get("/api/games/search")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/games/search").param("q", "x".repeat(201)))
                .andExpect(status().isBadRequest());
        server.verify();
    }

    @Test
    void hidesAuthenticationFailureDetails() throws Exception {
        server.expect(requestTo("https://id.twitch.tv/oauth2/token"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body("test-secret upstream details"));
        assertUnavailable();
    }

    @Test
    void hidesIgdbRateLimitDetails() throws Exception {
        expectToken("private-token", 3600);
        server.expect(requestTo("https://api.igdb.com/v4/games"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("private-token"));
        assertUnavailable();
    }

    @Test
    void handlesNetworkFailure() throws Exception {
        expectToken("token", 3600);
        server.expect(requestTo("https://api.igdb.com/v4/games"))
                .andRespond(withException(new IOException("sensitive network details")));
        assertUnavailable();
    }

    @Test
    void handlesMalformedIgdbResponse() throws Exception {
        expectToken("token", 3600);
        expectGames("token", "not-json");
        assertUnavailable();
    }

    @Test
    void rejectsInvalidTokenResponses() throws Exception {
        server.expect(requestTo("https://id.twitch.tv/oauth2/token"))
                .andRespond(withSuccess("{\"access_token\":\"\",\"expires_in\":3600}", MediaType.APPLICATION_JSON));
        assertUnavailable();
    }

    @Test
    void missingCredentialsDoNotTriggerNetworkRequests() {
        var provider = new TwitchTokenProvider(RestClient.create(), clock, "", "");
        assertThrows(IgdbException.class, provider::getAccessToken);
        server.verify();
    }

    private void assertUnavailable() throws Exception {
        mvc.perform(get("/api/games/search").param("q", "Skyrim"))
                .andExpect(status().isBadGateway())
                .andExpect(content().json("""
                        {"code":"GAME_SEARCH_UNAVAILABLE",
                         "message":"Game search is temporarily unavailable. Please try again later."}
                        """, org.springframework.test.json.JsonCompareMode.STRICT));
        server.verify();
    }

    private static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-30T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
