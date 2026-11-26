package io.github.mikel0ne.l1copilot.agent;

import io.github.mikel0ne.l1copilot.agent.llm.PromptData;

import java.util.ArrayList;
import java.util.List;

/** Turns a golden-set history fixture ("USER: …" / "AGENT: …" lines) into the turns production stores. */
final class GoldenHistory {

    private static final String USER = "USER: ";
    private static final String AGENT = "AGENT: ";

    private GoldenHistory() {
    }

    static String render(String fixture) {
        return RouterGoldenCases.NO_HISTORY.equals(fixture) ? fixture : PromptData.turns(parse(fixture));
    }

    // A line without a role prefix can only exist inside a multi-line user message, so it continues the previous turn.
    static List<Turn> parse(String fixture) {
        List<Turn> turns = new ArrayList<>();
        for (String line : fixture.split("\n", -1)) {
            if (line.startsWith(USER)) {
                turns.add(Turn.user(line.substring(USER.length())));
            } else if (line.startsWith(AGENT)) {
                turns.add(Turn.agent(line.substring(AGENT.length())));
            } else if (turns.isEmpty()) {
                turns.add(Turn.user(line));
            } else {
                Turn last = turns.remove(turns.size() - 1);
                turns.add(new Turn(last.role(), last.text() + "\n" + line));
            }
        }
        return turns;
    }
}
