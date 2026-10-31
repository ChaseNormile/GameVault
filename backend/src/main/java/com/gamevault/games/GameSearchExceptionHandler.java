package com.gamevault.games;

import com.gamevault.igdb.IgdbException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = GameSearchController.class)
public class GameSearchExceptionHandler {
    @ExceptionHandler(IgdbException.class)
    ResponseEntity<ApiError> upstreamFailure(IgdbException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ApiError("GAME_SEARCH_UNAVAILABLE", ex.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ApiError> invalidQuery(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(new ApiError("INVALID_SEARCH_QUERY", ex.getReason()));
    }

    public record ApiError(String code, String message) {}
}
