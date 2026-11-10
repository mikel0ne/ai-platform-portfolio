package io.github.mikel0ne.l1copilot.controller;

public class UnknownUserException extends RuntimeException {

    public UnknownUserException(String userId) {
        super("Unknown user: " + userId);
    }
}
