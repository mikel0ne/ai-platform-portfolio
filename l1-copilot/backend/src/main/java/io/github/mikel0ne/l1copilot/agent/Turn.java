package io.github.mikel0ne.l1copilot.agent;

import java.io.Serializable;

public record Turn(Role role, String text) implements Serializable {

    public enum Role {
        USER, AGENT
    }

    public Turn {
        if (role == null) {
            throw new IllegalArgumentException("a turn needs a role");
        }
        text = text == null ? "" : text;
    }

    public static Turn user(String text) {
        return new Turn(Role.USER, text);
    }

    public static Turn agent(String text) {
        return new Turn(Role.AGENT, text);
    }
}
