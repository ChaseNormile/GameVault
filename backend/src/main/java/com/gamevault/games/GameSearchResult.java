package com.gamevault.games;

import java.util.List;

/** Public API DTO. Release time is Unix seconds; unavailable metadata is null/empty. */
public record GameSearchResult(long id, String name, List<String> platforms,
        String coverUrl, Long firstReleaseDate) {}
