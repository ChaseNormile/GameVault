package com.gamevault.igdb;

/** Deliberately excludes upstream bodies, tokens, and credentials. */
public class IgdbException extends RuntimeException {
    public IgdbException() {
        super("Game search is temporarily unavailable. Please try again later.");
    }
}
