package io.github.mikel0ne.l1copilot.agent;

import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.AbstractCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.Checkpoint;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;

/**
 * In-memory checkpointer with fixed caps: only the latest checkpoints of each conversation and only the most
 * recently used conversations are kept, so a long-running pod does not grow without bound.
 */
public class BoundedMemorySaver extends AbstractCheckpointSaver {

    private final int maxCheckpointsPerConversation;
    private final Map<String, LinkedList<Checkpoint>> conversations;

    public BoundedMemorySaver(int maxConversations, int maxCheckpointsPerConversation) {
        if (maxConversations < 1 || maxCheckpointsPerConversation < 1) {
            throw new IllegalArgumentException("the caps must be positive");
        }
        this.maxCheckpointsPerConversation = maxCheckpointsPerConversation;
        this.conversations = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, LinkedList<Checkpoint>> eldest) {
                return size() > maxConversations;
            }
        });
    }

    int conversations() {
        return conversations.size();
    }

    @Override
    protected LinkedList<Checkpoint> loadCheckpoints(RunnableConfig config) {
        return conversations.computeIfAbsent(threadId(config), id -> new LinkedList<>());
    }

    // The saver pushes the newest checkpoint first, so the oldest ones sit at the tail.
    @Override
    protected void insertedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints,
                                      Checkpoint checkpoint) {
        while (checkpoints.size() > maxCheckpointsPerConversation) {
            checkpoints.removeLast();
        }
    }

    @Override
    protected void updatedCheckpoint(RunnableConfig config, LinkedList<Checkpoint> checkpoints,
                                     Checkpoint checkpoint) {
    }

    @Override
    protected Tag releaseCheckpoints(RunnableConfig config, LinkedList<Checkpoint> checkpoints) {
        String threadId = threadId(config);
        conversations.remove(threadId);
        return new Tag(threadId, checkpoints);
    }
}
