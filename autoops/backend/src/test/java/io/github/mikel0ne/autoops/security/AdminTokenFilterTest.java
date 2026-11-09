package io.github.mikel0ne.autoops.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class AdminTokenFilterTest {

    private static MockHttpServletResponse call(String configuredToken, String header) throws Exception {
        var request = new MockHttpServletRequest("POST", "/admin/vectorstore/drop");
        if (header != null) {
            request.addHeader(AdminTokenFilter.HEADER, header);
        }
        var response = new MockHttpServletResponse();
        new AdminTokenFilter(configuredToken).doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void lets_the_request_through_with_the_right_token() throws Exception {
        assertThat(call("s3cret", "s3cret").getStatus()).isEqualTo(200);
    }

    @Test
    void rejects_a_missing_or_wrong_token() throws Exception {
        assertThat(call("s3cret", null).getStatus()).isEqualTo(401);
        assertThat(call("s3cret", "guess").getStatus()).isEqualTo(401);
    }

    @Test
    void fails_closed_when_no_token_is_configured() throws Exception {
        assertThat(call("", "").getStatus()).isEqualTo(401);
        assertThat(call(null, "anything").getStatus()).isEqualTo(401);
    }
}
