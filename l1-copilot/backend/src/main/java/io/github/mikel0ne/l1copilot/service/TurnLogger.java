package io.github.mikel0ne.l1copilot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Slf4j
public class TurnLogger {

    private final boolean fileEnabled;
    private final Path file;
    private final boolean dbEnabled;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final Object fileLock = new Object();

    public TurnLogger(@Value("${copilot.turn-log.file.enabled:false}") boolean fileEnabled,
                      @Value("${copilot.turn-log.file.path:./logs/turns.jsonl}") String file,
                      @Value("${copilot.turn-log.db.enabled:false}") boolean dbEnabled,
                      ObjectMapper mapper,
                      JdbcTemplate jdbc) {
        this.fileEnabled = fileEnabled;
        this.file = Path.of(file);
        this.dbEnabled = dbEnabled;
        this.mapper = mapper;
        this.jdbc = jdbc;
    }

    // Logging must never break a turn: both sinks swallow failures with a warning.
    public void record(TurnLog turn) {
        Instant ts = Instant.now();
        if (fileEnabled) {
            writeToFile(ts, turn);
        }
        if (dbEnabled) {
            writeToDb(ts, turn);
        }
    }

    private void writeToFile(Instant ts, TurnLog turn) {
        try {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ts", ts.toString());
            row.put("conversationId", turn.conversationId());
            row.put("userId", turn.userId());
            row.put("message", turn.message());
            row.put("intent", turn.intent());
            row.put("awaiting", turn.awaiting());
            row.put("orderId", turn.orderId());
            row.put("ordersShown", turn.ordersShown());
            row.put("ticketsCreated", turn.ticketsCreated());
            row.put("resolved", turn.resolved());
            row.put("reply", turn.reply());

            String line = mapper.writeValueAsString(row) + System.lineSeparator();
            synchronized (fileLock) {
                if (file.getParent() != null) {
                    Files.createDirectories(file.getParent());
                }
                Files.writeString(file, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Could not record the turn in file {}", file, e);
        }
    }

    private void writeToDb(Instant ts, TurnLog turn) {
        try {
            // Plain JDBC instead of an entity keeps turn_log out of the schema validation of the main model.
            jdbc.update("""
                    INSERT INTO turn_log
                        (ts, conversation_id, user_id, message, intent, awaiting,
                         order_id, orders_shown, tickets_created, resolved, reply)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    Timestamp.from(ts), turn.conversationId(), turn.userId(), turn.message(),
                    turn.intent(), turn.awaiting(), turn.orderId(), turn.ordersShown(),
                    turn.ticketsCreated(), turn.resolved(), turn.reply());
        } catch (RuntimeException e) {
            log.warn("Could not record the turn in table turn_log", e);
        }
    }

    public record TurnLog(String conversationId, String userId, String message,
                          String intent, String awaiting, Long orderId,
                          int ordersShown, int ticketsCreated, boolean resolved,
                          String reply) {
    }
}
