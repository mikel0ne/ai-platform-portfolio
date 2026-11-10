package io.github.mikel0ne.autoops.chat;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SearchRateLimiterTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-06-01T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    @Test
    void allows_up_to_the_limit_within_a_minute() {
        var limiter = new SearchRateLimiter(2, clock);

        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    void a_new_minute_resets_the_budget() {
        var limiter = new SearchRateLimiter(1, clock);
        limiter.tryAcquire();

        now.set(now.get().plusSeconds(60));

        assertThat(limiter.tryAcquire()).isTrue();
    }
}
