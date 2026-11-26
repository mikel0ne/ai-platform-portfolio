package io.github.mikel0ne.l1copilot.resilience;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetryingChatModelTest {

    private static final String MINUTE_QUOTA = "{\"error\":{\"code\":429,\"message\":\"Quota exceeded. "
            + "Please retry in 34.25s.\",\"details\":[{\"quotaId\":"
            + "\"GenerateContentInputTokensPerModelPerMinute-FreeTier\"}]}}";
    private static final String DAILY_QUOTA = "{\"error\":{\"code\":429,\"message\":\"Quota exceeded. "
            + "Please retry in 34.25s.\",\"details\":[{\"quotaId\":"
            + "\"GenerateRequestsPerDayPerProjectPerModel-FreeTier\"}]}}";

    private final ChatRequest request = ChatRequest.builder().messages(UserMessage.from("hola")).build();
    private final ChatResponse ok = ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
    private final ChatModel delegate = mock(ChatModel.class);
    private final List<Duration> waits = new ArrayList<>();

    private RetryingChatModel model(int maxAttempts) {
        var props = new LlmRetryProperties(true, maxAttempts, Duration.ofSeconds(2),
                Duration.ofSeconds(20), Duration.ofSeconds(70));
        return new RetryingChatModel(delegate, new GoogleAiFailureClassifier(), props, "test", waits::add);
    }

    @Test
    void retriesAnOverloadWithGrowingWaitsUntilItSucceeds() {
        when(delegate.chat(any(ChatRequest.class)))
                .thenThrow(new InternalServerException("503 high demand"))
                .thenThrow(new InternalServerException("503 high demand"))
                .thenReturn(ok);

        assertThat(model(4).chat(request)).isSameAs(ok);

        assertThat(waits).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(4));
    }

    @Test
    void retriesATimeout() {
        when(delegate.chat(any(ChatRequest.class)))
                .thenThrow(new TimeoutException("request timed out"))
                .thenReturn(ok);

        assertThat(model(4).chat(request)).isSameAs(ok);
        assertThat(waits).hasSize(1);
    }

    @Test
    void waitsForThePerMinuteQuotaWindowUsingTheProvidersHint() {
        when(delegate.chat(any(ChatRequest.class)))
                .thenThrow(new RateLimitException(MINUTE_QUOTA))
                .thenReturn(ok);

        assertThat(model(4).chat(request)).isSameAs(ok);

        assertThat(waits).containsExactly(Duration.ofMillis(36250));
    }

    @Test
    void doesNotWaitOnADailyQuota() {
        when(delegate.chat(any(ChatRequest.class))).thenThrow(new RateLimitException(DAILY_QUOTA));

        assertThatThrownBy(() -> model(4).chat(request)).isInstanceOf(RateLimitException.class);

        verify(delegate, times(1)).chat(any(ChatRequest.class));
        assertThat(waits).isEmpty();
    }

    @Test
    void doesNotRetryAnUnclassifiedRateLimit() {
        when(delegate.chat(any(ChatRequest.class))).thenThrow(new RateLimitException("429 without quota details"));

        assertThatThrownBy(() -> model(4).chat(request)).isInstanceOf(RateLimitException.class);

        verify(delegate, times(1)).chat(any(ChatRequest.class));
    }

    @Test
    void doesNotRetryAnInvalidRequest() {
        when(delegate.chat(any(ChatRequest.class))).thenThrow(new InvalidRequestException("bad schema"));

        assertThatThrownBy(() -> model(4).chat(request)).isInstanceOf(InvalidRequestException.class);

        verify(delegate, times(1)).chat(any(ChatRequest.class));
    }

    @Test
    void givesUpAfterTheMaximumNumberOfAttempts() {
        when(delegate.chat(any(ChatRequest.class))).thenThrow(new InternalServerException("503"));

        assertThatThrownBy(() -> model(3).chat(request)).isInstanceOf(InternalServerException.class);

        verify(delegate, times(3)).chat(any(ChatRequest.class));
        assertThat(waits).hasSize(2);
    }

    @Test
    void failsFastWhenThePerMinuteWaitExceedsTheCap() {
        var props = new LlmRetryProperties(true, 4, Duration.ofSeconds(2),
                Duration.ofSeconds(20), Duration.ofSeconds(10));
        var model = new RetryingChatModel(delegate, new GoogleAiFailureClassifier(), props, "test", waits::add);
        when(delegate.chat(any(ChatRequest.class))).thenThrow(new RateLimitException(MINUTE_QUOTA));

        assertThatThrownBy(() -> model.chat(request)).isInstanceOf(RateLimitException.class);

        verify(delegate, times(1)).chat(any(ChatRequest.class));
        assertThat(waits).isEmpty();
    }

    @Test
    void classifiesAFailureWrappedInAnotherException() {
        when(delegate.chat(any(ChatRequest.class)))
                .thenThrow(new RuntimeException("wrapper", new InternalServerException("503")))
                .thenReturn(ok);

        assertThat(model(4).chat(request)).isSameAs(ok);
    }
}
