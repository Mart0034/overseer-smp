package dev.overseersmp.overseer;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** Async call to the Anthropic Messages API. The API key is only ever placed in the request header; it is never logged or included in errors. */
public final class AnthropicClient {
    public record Reply(String text, int inputTokens, int outputTokens, long latencyMs) {}

    /** Message is safe to log: status code and error type only. */
    public static final class ApiException extends RuntimeException {
        public ApiException(String m) { super(m); }
    }

    private static final Gson GSON = new Gson();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public CompletableFuture<Reply> complete(Settings s, String system, String user) {
        if (!s.hasKey()) return CompletableFuture.failedFuture(new ApiException("no api key configured"));
        long t0 = System.nanoTime();
        HttpRequest req = HttpRequest.newBuilder(URI.create(s.apiUrl))
                .timeout(Duration.ofSeconds(s.timeoutSeconds))
                .header("x-api-key", s.apiKey())
                .header("anthropic-version", s.apiVersion)
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(buildBody(s, system, user)))
                .build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .handle((resp, err) -> {
                    if (err != null) throw new ApiException("request failed: " + err.getClass().getSimpleName());
                    return parseResponse(resp.statusCode(), resp.body(), (System.nanoTime() - t0) / 1_000_000);
                });
    }

    static String buildBody(Settings s, String system, String user) {
        JsonObject b = new JsonObject();
        b.addProperty("model", s.model);
        b.addProperty("max_tokens", s.maxTokens);
        b.addProperty("system", system);
        JsonObject th = new JsonObject();
        th.addProperty("type", "disabled");   // short replies: thinking would eat the 150-token cap
        b.add("thinking", th);
        JsonObject oc = new JsonObject();
        oc.addProperty("effort", "low");
        if (s.structuredOutput) oc.add("format", schema());
        b.add("output_config", oc);
        JsonArray msgs = new JsonArray();
        JsonObject m = new JsonObject();
        m.addProperty("role", "user");
        m.addProperty("content", user);
        msgs.add(m);
        b.add("messages", msgs);
        return GSON.toJson(b);
    }

    private static JsonObject schema() {
        JsonObject props = new JsonObject();
        props.add("reply", type("string"));
        JsonObject action = type("string");
        JsonArray ae = new JsonArray();
        for (String a : new String[] {"bless", "curse", "smite", "none"}) ae.add(a);
        action.add("enum", ae);
        props.add("action", action);
        JsonObject effect = type("string");
        JsonArray ee = new JsonArray();
        for (String id : EffectCatalog.allIds()) ee.add(id);
        ee.add("none");
        effect.add("enum", ee);
        props.add("effect", effect);
        props.add("favor_delta", type("integer"));
        JsonObject sch = new JsonObject();
        sch.addProperty("type", "object");
        sch.add("properties", props);
        sch.addProperty("additionalProperties", false);
        JsonArray req = new JsonArray();
        for (String r : new String[] {"reply", "action", "effect", "favor_delta"}) req.add(r);
        sch.add("required", req);
        JsonObject fmt = new JsonObject();
        fmt.addProperty("type", "json_schema");
        fmt.add("schema", sch);
        return fmt;
    }

    private static JsonObject type(String t) {
        JsonObject o = new JsonObject();
        o.addProperty("type", t);
        return o;
    }

    static Reply parseResponse(int status, String body, long latencyMs) {
        JsonObject o;
        try {
            o = JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new ApiException("HTTP " + status + " with unreadable body");
        }
        if (status != 200) {
            String type = "unknown";
            JsonElement err = o.get("error");
            if (err != null && err.isJsonObject() && err.getAsJsonObject().has("type")) type = err.getAsJsonObject().get("type").getAsString();
            throw new ApiException("HTTP " + status + " " + type);
        }
        if (o.has("stop_reason") && !o.get("stop_reason").isJsonNull() && "refusal".equals(o.get("stop_reason").getAsString()))
            throw new ApiException("model refused");
        StringBuilder text = new StringBuilder();
        JsonElement content = o.get("content");
        if (content != null && content.isJsonArray()) {
            for (JsonElement c : content.getAsJsonArray()) {
                if (c.isJsonObject() && c.getAsJsonObject().has("text")) text.append(c.getAsJsonObject().get("text").getAsString());
            }
        }
        int in = 0, out = 0;
        JsonElement u = o.get("usage");
        if (u != null && u.isJsonObject()) {
            JsonObject uo = u.getAsJsonObject();
            if (uo.has("input_tokens")) in = uo.get("input_tokens").getAsInt();
            if (uo.has("output_tokens")) out = uo.get("output_tokens").getAsInt();
        }
        return new Reply(text.toString(), in, out, latencyMs);
    }
}
