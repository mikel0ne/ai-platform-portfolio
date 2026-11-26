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
    @DisplayName("Up to the compaction threshold the history just accumulates")
    void historyAccumulatesUpToTheThreshold() {
        List<Turn> next = SupportState.appendCapped(turns(0, SupportState.COMPACT_ABOVE_TURNS - 1),
                List.of(Turn.agent("last")));

        assertThat(next).hasSize(SupportState.COMPACT_ABOVE_TURNS);
        assertThat(next.get(0).text()).isEqualTo("m0");
        assertThat(next.get(next.size() - 1).text()).isEqualTo("last");
    }

    @Test
    @DisplayName("Crossing the threshold compacts to the latest retained turns, in order")
    void historyCompactsToTheLatestTurns() {
        List<Turn> next = SupportState.appendCapped(turns(0, SupportState.COMPACT_ABOVE_TURNS),
                List.of(Turn.agent("last")));

        assertThat(next).hasSize(SupportState.RETAINED_TURNS);
        assertThat(next.get(0).text()).isEqualTo("m11");
        assertThat(next.get(next.size() - 1).text()).isEqualTo("last");
        assertThat(next.subList(0, next.size() - 1)).extracting(Turn::text)
                .containsExactly("m11", "m12", "m13", "m14", "m15", "m16", "m17", "m18", "m19");
    }

    @Test
    @DisplayName("Several turns added at once that cross the threshold also compact")
    void severalTurnsAtOnceCompact() {
        List<Turn> next = SupportState.appendCapped(turns(0, 19),
                List.of(Turn.user("a"), Turn.agent("b"), Turn.user("c")));

        assertThat(next).hasSize(SupportState.RETAINED_TURNS);
        assertThat(next).extracting(Turn::text).containsExactly(
                "m12", "m13", "m14", "m15", "m16", "m17", "m18", "a", "b", "c");
    }

    @Test
    @DisplayName("After compacting, the next appends accumulate again up to the threshold")
    void accumulatesAgainAfterCompaction() {
        List<Turn> history = SupportState.appendCapped(turns(0, SupportState.COMPACT_ABOVE_TURNS),
                List.of(Turn.agent("x")));
        assertThat(history).hasSize(SupportState.RETAINED_TURNS);

        for (int i = SupportState.RETAINED_TURNS + 1; i <= SupportState.COMPACT_ABOVE_TURNS; i++) {
            history = SupportState.appendCapped(history, List.of(Turn.user("n" + i)));
            assertThat(history).hasSize(i);
        }
        assertThat(history.get(0).text()).isEqualTo("m11");

        history = SupportState.appendCapped(history, List.of(Turn.user("again")));
        assertThat(history).hasSize(SupportState.RETAINED_TURNS);
        assertThat(history.get(history.size() - 1).text()).isEqualTo("again");
    }

    @Test
    @DisplayName("A null stored list with a null addition stays empty")
    void nullStoredAndNullAdded() {
        assertThat(SupportState.appendCapped(null, null)).isEmpty();
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

    private static List<Turn> turns(int fromInclusive, int toExclusive) {
        return IntStream.range(fromInclusive, toExclusive).mapToObj(i -> Turn.user("m" + i)).toList();
    }

    private static Checkpoint checkpoint(int n) {
        return Checkpoint.builder().state(Map.of("n", n)).nodeId("node").nextNodeId("next").build();
    }
}
