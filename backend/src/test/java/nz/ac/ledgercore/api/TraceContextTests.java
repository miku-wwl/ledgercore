package nz.ac.ledgercore.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TraceContextTests {

    private static final String VALID = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Test
    void acceptsVersionZeroTraceparentAndContinuesTheSameTrace() {
        TraceContext remote = TraceContext.parse(VALID).orElseThrow();
        TraceContext child = remote.child();
        assertThat(remote.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(child.traceId()).isEqualTo(remote.traceId());
        assertThat(child.parentSpanId()).isEqualTo("00f067aa0ba902b7");
        assertThat(child.spanId()).hasSize(16).isNotEqualTo(remote.spanId());
        assertThat(child.traceparent()).matches("00-4bf92f3577b34da6a3ce929d0e0e4736-[0-9a-f]{16}-01");
    }

    @Test
    void rejectsMalformedUnknownVersionUppercaseAndAllZeroIdentifiers() {
        assertThat(TraceContext.parse(null)).isEmpty();
        assertThat(TraceContext.parse("not-a-trace")).isEmpty();
        assertThat(TraceContext.parse("ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")).isEmpty();
        assertThat(TraceContext.parse("00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01")).isEmpty();
        assertThat(TraceContext.parse("00-00000000000000000000000000000000-00f067aa0ba902b7-01")).isEmpty();
        assertThat(TraceContext.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01")).isEmpty();
        assertThat(TraceContext.parse(VALID + "-extra")).isEmpty();
    }

    @Test
    void newRootsAreRandomSampledAndScopesRestoreThePreviousContext() {
        TraceContext first = TraceContext.root();
        TraceContext second = TraceContext.root();
        assertThat(first.traceId()).hasSize(32).isNotEqualTo(second.traceId());
        assertThat(first.sampled()).isTrue();
        assertThat(TraceContext.current()).isEmpty();
        try (TraceContext.Scope outer = first.open()) {
            assertThat(TraceContext.current()).contains(first);
            try (TraceContext.Scope inner = second.open()) {
                assertThat(TraceContext.current()).contains(second);
            }
            assertThat(TraceContext.current()).contains(first);
        }
        assertThat(TraceContext.current()).isEmpty();
    }
}
