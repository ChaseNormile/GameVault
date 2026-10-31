package com.gamevault.igdb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.gamevault.games.GameSearchResult;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record IgdbGame(long id, String name, List<Platform> platforms, Cover cover,
        @JsonProperty("first_release_date") Long firstReleaseDate) {
    public GameSearchResult toResult() {
        String url = cover == null ? null : cover.url();
        if (url != null && url.startsWith("//")) url = "https:" + url;
        return new GameSearchResult(id, name,
                platforms == null ? List.of() : platforms.stream().map(Platform::name).toList(),
                url, firstReleaseDate);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Platform(String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cover(String url) {}
}
