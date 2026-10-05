package vn.thanhtuanle.config;

import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An SSE verdict is written by an ASYNC dispatch of a request that was already authorized. The JWT filter does not
 * run on that dispatch (OncePerRequestFilter), so authorizing it again fails on an already committed response and
 * logged four ERROR lines per verdict. ASYNC and ERROR dispatches pass; a REQUEST without a token still stops.
 */
@SpringBootTest
@ActiveProfiles("test")
class AsyncDispatchSecurityTest {

    @Autowired FilterChainProxy springSecurityFilterChain;

    /** The chain's end is reached only if security let the dispatch through. */
    private boolean passesSecurity(DispatcherType type) throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/v1/submissions/" + UUID.randomUUID() + "/stream");
        request.setDispatcherType(type);
        MockFilterChain chain = new MockFilterChain();
        springSecurityFilterChain.doFilter(request, new MockHttpServletResponse(), chain);
        return chain.getRequest() != null;
    }

    @Test
    void anAsyncDispatchIsNotAuthorizedAgain() throws Exception {
        assertThat(passesSecurity(DispatcherType.ASYNC)).isTrue();
    }

    @Test
    void anErrorDispatchIsNotAuthorizedAgain() throws Exception {
        assertThat(passesSecurity(DispatcherType.ERROR)).isTrue();
    }

    @Test
    void aRequestWithoutATokenStillStopsAtSecurity() throws Exception {
        assertThat(passesSecurity(DispatcherType.REQUEST)).isFalse();
    }
}
