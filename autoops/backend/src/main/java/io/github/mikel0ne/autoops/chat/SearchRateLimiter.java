package io.github.mikel0ne.autoops.chat;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

// /search is unauthenticated and every query costs several LLM calls: a global cap protects the quota.
@Component
public class SearchRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final int maxPerWindow;
    private final Clock clock;
    private Instant windowStart;
    private int used;

    public SearchRateLimiter(SearchProperties props) {
        this(props.maxRequestsPerMinute(), Clock.systemUTC());
    }

    SearchRateLimiter(int maxPerWindow, Clock clock) {
        this.maxPerWindow = maxPerWindow;
        this.clock = clock;
        this.windowStart = clock.instant();
    }

    public synchronized boolean tryAcquire() {
        Instant now = clock.instant();
        if (!now.isBefore(windowStart.plus(WINDOW))) {
            windowStart = now;
            used = 0;
        }
        if (used >= maxPerWindow) {
            return false;
        }
        used++;
        return true;
    }
}
