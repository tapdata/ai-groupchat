package io.groupchat.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.groupchat.model.Agent;
import io.groupchat.util.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Provider backed by the Anthropic Messages API.
 */
public class AnthropicProvider implements AgentProvider {

    private static final String DEFAULT_BASE = "https://api.anthropic.com";
    private static final String VERSION = "2023-06-01";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    @Override
    public String type() {
        return "anthropic";
    }

    @Override
    public boolean supportsFreeMode(Agent agent) {
        return notBlank(agent.freeModel) || notBlank(agent.freeBaseUrl);
    }

    @Override
    public AgentResponse generate(AgentRequest request) {
        Agent agent = request.agent;
        if (agent.apiKey == null || agent.apiKey.isBlank()) {
            return AgentResponse.error("Missing apiKey for agent " + agent.id);
        }
        try {
            String baseUrl = request.effectiveBaseUrl();
            String base = (baseUrl != null && !baseUrl.isBlank())
                    ? baseUrl.replaceAll("/+$", "")
                    : DEFAULT_BASE;

            ObjectNode body = Json.obj();
            body.put("model", request.effectiveModel());
            body.put("max_tokens", 4096);
            if (agent.systemPrompt != null && !agent.systemPrompt.isBlank()) {
                body.put("system", agent.systemPrompt);
            }
            ArrayNode messages = body.putArray("messages");
            ObjectNode msg = messages.addObject();
            msg.put("role", "user");
            msg.put("content", request.composedUserContent());

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/v1/messages"))
                    .timeout(Duration.ofMinutes(3))
                    .header("content-type", "application/json")
                    .header("x-api-key", agent.apiKey)
                    .header("anthropic-version", VERSION)
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                    .build();

            HttpResponse<String> resp = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                return AgentResponse.error("Anthropic HTTP " + resp.statusCode() + ": " + resp.body());
            }
            JsonNode root = Json.read(resp.body());
            JsonNode content = root.path("content");
            StringBuilder sb = new StringBuilder();
            if (content.isArray()) {
                for (JsonNode block : content) {
                    if ("text".equals(block.path("type").asText())) {
                        sb.append(block.path("text").asText());
                    }
                }
            }
            String text = sb.toString().trim();
            if (text.isEmpty()) {
                return AgentResponse.error("Anthropic returned empty content: " + resp.body());
            }
            return AgentResponse.ok(text, parseUsage(resp));
        } catch (Exception e) {
            return AgentResponse.error("Anthropic call failed: " + e.getMessage());
        }
    }

    /**
     * Anthropic has no public balance endpoint, so we surface the per-request
     * token rate-limit window exposed on the response headers.
     */
    private static Usage parseUsage(HttpResponse<String> resp) {
        Long remaining = header(resp, "anthropic-ratelimit-tokens-remaining");
        Long limit = header(resp, "anthropic-ratelimit-tokens-limit");
        if (remaining == null && limit == null) {
            return null;
        }
        return new Usage(remaining, limit, "tokens", "rate-limit window");
    }

    private static Long header(HttpResponse<String> resp, String name) {
        return resp.headers().firstValue(name).map(v -> {
            try {
                return Long.parseLong(v.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }).orElse(null);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
