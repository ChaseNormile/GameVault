package com.gamevault.games;

import com.gamevault.igdb.IgdbSearchService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class GameSearchController {
    private final IgdbSearchService searchService;

    public GameSearchController(IgdbSearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/api/games/search")
    public List<GameSearchResult> search(@RequestParam(defaultValue = "") String q) {
        String term = q.strip();
        if (term.isEmpty() || term.length() > 200 || term.codePoints().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "q must contain 1–200 characters and no control characters.");
        }
        return searchService.search(term);
    }
}
