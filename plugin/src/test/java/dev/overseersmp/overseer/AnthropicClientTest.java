package dev.overseersmp.overseer;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AnthropicClientTest {
    private static final String KEY = "sk-" + "ant-api03-TESTKEYTESTKEYTESTKEYTESTKEY";
    private HttpServer server;
    private final AtomicReference<String> seenKey = new AtomicReference<>(), seenBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String responseBody = "";

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", ex -> {
            seenKey.set(ex.getRequestHeaders().getFirst("x-api-key"));
            seenBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] b = responseBody.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("content-type", "application/json");
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    private Settings settings() {
        return Settings.with(Map.of("anthropic.api-key", KEY, "anthropic.api-url", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages",
                "anthropic.timeout-seconds", 5));
    }

    @Test void bodyHasTheExpectedShape() {
        var o = JsonParser.parseString(AnthropicClient.buildBody(Settings.defaults(), "SYS", "USER")).getAsJsonObject();
        assertEquals("claude-haiku-5-5", o.get("model").getAsString());
        assertEquals(150, o.get("max_tokens").getAsInt());
        assertEquals("SYS", o.get("system").getAsString());
        assertEquals("disabled", o.getAsJsonObject("thinking").get("type").getAsString());
        assertEquals("low", o.getAsJsonObject("output_config").get("effort").getAsString());
        var fmt = o.getAsJsonObject("output_config").getAsJsonObject("format");
        assertEquals("json_schema", fmt.get("type").getAsString());
        assertEquals(EffectCatalog.allIds().size() + 1, fmt.getAsJsonObject("schema").getAsJsonObject("properties").getAsJsonObject("effect").getAsJsonArray("enum").size());
        assertEquals("user", o.getAsJsonArray("messages").get(0).getAsJsonObject().get("role").getAsString());
        assertFalse(o.has("temperature"), "sampling params are rejected by this model");
    }

    @Test void structuredOutputCanBeTurnedOff() {
        var o = JsonParser.parseString(AnthropicClient.buildBody(Settings.with(Map.of("anthropic.structured-output", false)), "s", "u")).getAsJsonObject();
        assertFalse(o.getAsJsonObject("output_config").has("format"));
    }

    @Test void sendsKeyInHeaderOnlyAndParsesTheReply() {
        responseBody = "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"reply\\\":\\\"hi\\\"}\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":386,\"output_tokens\":62}}";
        var r = new AnthropicClient().complete(settings(), "sys", "user").join();
        assertEquals("{\"reply\":\"hi\"}", r.text());
        assertEquals(386, r.inputTokens());
        assertEquals(62, r.outputTokens());
        assertEquals(KEY, seenKey.get());
        assertFalse(seenBody.get().contains(KEY), "the key never appears in the body");
    }

    @Test void errorsCarryStatusAndTypeButNeverTheKey() {
        status = 401;
        responseBody = "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key " + KEY + "\"}}";
        var ex = assertThrows(CompletionException.class, () -> new AnthropicClient().complete(settings(), "s", "u").join());
        assertTrue(ex.getCause() instanceof AnthropicClient.ApiException);
        assertTrue(ex.getCause().getMessage().contains("401"));
        assertTrue(ex.getCause().getMessage().contains("authentication_error"));
        assertFalse(ex.getCause().getMessage().contains(KEY));
        assertFalse(ex.getMessage().contains(KEY));
    }

    @Test void refusalsAndGarbageAreErrors() {
        assertThrows(AnthropicClient.ApiException.class, () -> AnthropicClient.parseResponse(200, "{\"stop_reason\":\"refusal\",\"content\":[]}", 1));
        assertThrows(AnthropicClient.ApiException.class, () -> AnthropicClient.parseResponse(200, "<html>nope</html>", 1));
        assertThrows(AnthropicClient.ApiException.class, () -> AnthropicClient.parseResponse(529, "{\"error\":{\"type\":\"overloaded_error\"}}", 1));
    }

    @Test void missingKeyFailsWithoutNetwork() {
        var ex = assertThrows(CompletionException.class, () -> new AnthropicClient().complete(Settings.defaults(), "s", "u").join());
        assertInstanceOf(AnthropicClient.ApiException.class, ex.getCause());
    }

    @Test void unreachableServerFailsCleanly() {
        server.stop(0);
        var ex = assertThrows(CompletionException.class, () -> new AnthropicClient().complete(settings(), "s", "u").join());
        assertFalse(String.valueOf(ex.getCause().getMessage()).contains(KEY));
    }
}
