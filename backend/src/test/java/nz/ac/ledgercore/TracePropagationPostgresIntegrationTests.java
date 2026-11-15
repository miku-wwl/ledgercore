package nz.ac.ledgercore;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import nz.ac.ledgercore.events.OutboxDispatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Round 4 / E9: an inbound W3C traceparent is continued by the SERVER span, persisted as the producer context of the
 * outbox work committed by that request, and linked (not continued) by the separate CONSUMER trace of its dispatch.
 */
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TracePropagationPostgresIntegrationTests {

    private static final Pattern TOKEN = Pattern.compile("\"token\":\"([^\"]+)\"");
    private static final Pattern ID = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    OutboxDispatcher dispatcher;

    private final HttpClient client = HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();

    @Test
    void inboundTraceIsContinuedPersistedWithOutboxWorkAndLinkedFromItsDispatchTrace(CapturedOutput output)
            throws Exception {
        String email = "e9-trace-" + UUID.randomUUID().toString().substring(0, 12) + "@example.test";
        assertThat(call("POST", "/api/auth/register", "{\"email\":\"" + email
                + "\",\"password\":\"E9-Trace-Password!\",\"displayName\":\"E9 Trace\"}", null).statusCode()).isEqualTo(201);
        String remoteTrace = "4bf92f3577b34da6a3ce929d0e0e" + Long.toHexString(System.nanoTime()).substring(0, 4);
        String inbound = "00-" + remoteTrace + "-00f067aa0ba902b7-01";

        HttpResponse<String> created = call("POST", "/api/organizations", "{\"displayName\":\"E9 Trace Org\"}", null);
        assertThat(created.statusCode()).isEqualTo(201);
        UUID organization = UUID.fromString(capture(ID, created.body()));
        String memberEmail = registerAnotherCustomer();

        // Adding a member commits an organization.role.granted outbox event inside the traced request.
        HttpResponse<String> added = call("POST", "/api/organizations/" + organization + "/members",
                "{\"email\":\"" + memberEmail + "\",\"roles\":[\"FINANCE_VIEWER\"]}", inbound);

        assertThat(added.statusCode()).isEqualTo(201);
        String server = added.headers().firstValue("traceresponse").orElseThrow();
        assertThat(server).startsWith("00-" + remoteTrace + "-").doesNotContain("00f067aa0ba902b7");
        String eventKey = jdbc.queryForObject("""
                SELECT event_key FROM outbox_events WHERE organization_id = ? AND event_type = 'organization.role.granted'
                """, String.class, organization);
        assertThat(jdbc.queryForObject("SELECT trace_parent FROM outbox_events WHERE event_key = ?", String.class,
                eventKey)).isEqualTo(server);

        Instant deadline = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline) && !"DELIVERED".equals(jdbc.queryForObject(
                "SELECT status FROM outbox_events WHERE event_key = ?", String.class, eventKey))) {
            if (!dispatcher.dispatchOne()) {
                Thread.sleep(100);
            }
        }

        List<String> lines = output.getAll().lines().toList();
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("span SERVER POST /api/organizations/{organizationId}/members OK")
                .contains(remoteTrace).contains("00f067aa0ba902b7"));
        String dispatchSpan = lines.stream()
                .filter(line -> line.contains("span CONSUMER outbox.dispatch organization.role.granted OK")
                        && line.contains(server)).findFirst().orElseThrow(() -> new AssertionError("no linked dispatch span"));
        // The dispatch is a new trace: the remote trace id appears only inside the link to the producer span.
        assertThat(dispatchSpan.split(remoteTrace, -1)).hasSize(2);

        HttpResponse<String> forged = call("GET", "/api/organizations", null, "00-zz-not-a-trace-01");
        assertThat(forged.statusCode()).isEqualTo(200);
        assertThat(forged.headers().firstValue("traceresponse").orElseThrow()).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01");
        assertThat(output.getAll()).doesNotContain("E9-Trace-Password!");
    }

    private String registerAnotherCustomer() throws Exception {
        HttpClient other = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String email = "e9-member-" + UUID.randomUUID().toString().substring(0, 12) + "@example.test";
        HttpResponse<String> csrf = other.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/auth/csrf"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> registered = other.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                        + "/api/auth/register")).header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", capture(TOKEN, csrf.body()))
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + email
                        + "\",\"password\":\"E9-Trace-Password!\",\"displayName\":\"E9 Member\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(registered.statusCode()).isEqualTo(201);
        return email;
    }

    private HttpResponse<String> call(String method, String path, String body, String traceparent) throws Exception {
        HttpResponse<String> csrf = send("GET", "/api/auth/csrf", null, null, null);
        return send(method, path, body, capture(TOKEN, csrf.body()), traceparent);
    }

    private HttpResponse<String> send(String method, String path, String body, String token, String traceparent)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (token != null) {
            request.header("X-CSRF-TOKEN", token);
        }
        if (traceparent != null) {
            request.header("traceparent", traceparent);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String capture(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        assertThat(matcher.find()).as(text).isTrue();
        return matcher.group(1);
    }
}
