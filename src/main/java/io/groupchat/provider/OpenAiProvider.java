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
 * Provider backed by the OpenAI Chat Completions API (also works with any
 * OpenAI-compatible endpoint via {@code baseUrl}).
 */
public class OpenAiProvider implements AgentProvider {

    private static final String DEFAULT_BASE = "https://api.openai.com";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    @Override
    public String type() {
        return "openai";
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
            ArrayNode messages = body.putArray("messages");
            if (agent.systemPrompt != null && !agent.systemPrompt.isBlank()) {
                ObjectNode sys = messages.addObject();
                sys.put("role", "system");
                sys.put("content", agent.systemPrompt);
            }
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", request.composedUserContent());

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/v1/chat/completions"))
                    .timeout(Duration.ofMinutes(3))
                    .header("content-type", "application/json")
                    .header("authorization", "Bearer " + agent.apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                    .build();

            HttpResponse<String> resp = http.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                return AgentResponse.error("OpenAI HTTP " + resp.statusCode() + ": " + resp.body());
            }
            JsonNode root = Json.read(resp.body());
            String text = root.path("choices").path(0).path("message").path("content").asText("").trim();
            if (text.isEmpty()) {
                return AgentResponse.error("OpenAI returned empty content: " + resp.body());
            }
            return AgentResponse.ok(text, parseUsage(resp));
        } catch (Exception e) {
            return AgentResponse.error("OpenAI call failed: " + e.getMessage());
        }
    }

    /**
     * OpenAI has no public balance endpoint, so we surface the per-request token
     * rate-limit window exposed on the response headers.
     */
    private static Usage parseUsage(HttpResponse<String> resp) {
        Long remaining = header(resp, "x-ratelimit-remaining-tokens");
        Long limit = header(resp, "x-ratelimit-limit-tokens");
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
