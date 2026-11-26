package io.github.mikel0ne.l1copilot.agent;

import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class BoundedStateTest {

    @Test
    @DisplayName("The stored history keeps only the latest turns")
    void historyIsCapped() {
        List<Turn> stored = IntStream.range(0, SupportState.MAX_STORED_TURNS)
                .mapToObj(i -> Turn.user("m" + i)).toList();

        List<Turn> next = SupportState.appendCapped(stored, List.of(Turn.agent("last")));

        assertThat(next).hasSize(SupportState.MAX_STORED_TURNS);
        assertThat(next.get(0).text()).isEqualTo("m1");
        assertThat(next.get(next.size() - 1).text()).isEqualTo("last");
    }

    @Test
    @DisplayName("Appending to an empty history starts it")
    void appendToEmptyHistory() {
        assertThat(SupportState.appendCapped(null, List.of(Turn.user("hola"))))
                .containsExactly(Turn.user("hola"));
    }

    @Test
    @DisplayName("The saver keeps the latest checkpoints and evicts the least recently used conversation")
    void saverIsBounded() throws Exception {
        BoundedMemorySaver saver = new BoundedMemorySaver(2, 3);
        RunnableConfig a = RunnableConfig.builder().threadId("a").build();
        RunnableConfig b = RunnableConfig.builder().threadId("b").build();
        RunnableConfig c = RunnableConfig.builder().threadId("c").build();

        for (int i = 0; i < 5; i++) {
            saver.put(a, checkpoint(i));
        }
        saver.put(b, checkpoint(0));
        saver.get(a);
        saver.put(c, checkpoint(0));

        assertThat(saver.list(a)).hasSize(3);
        assertThat(saver.get(a)).get().extracting(cp -> cp.getState().get("n")).isEqualTo(4);
        assertThat(saver.conversations()).isEqualTo(2);
        assertThat(saver.list(b)).isEmpty();
    }

    private static Checkpoint checkpoint(int n) {
        return Checkpoint.builder().state(Map.of("n", n)).nodeId("node").nextNodeId("next").build();
    }
}
