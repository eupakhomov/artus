package de.pamir.agentic.ui.tools;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * One-off "Test" for a catalog server (docs/plan/phase-26-tool-catalog.md decision 8): starts it,
 * runs the MCP {@code initialize} + {@code tools/list} handshake and stops it. Reads only —
 * no tool is ever called. Same posture as the code-intel root validation: bounded by a budget.
 */
public class McpProbe {

	public record Result(boolean ok, List<String> tools, String error) {
	}

	private static final String PROTOCOL = "2025-06-18";

	private final ObjectMapper mapper;
	private final Duration budget;

	public McpProbe(ObjectMapper mapper, Duration budget) {
		this.mapper = mapper;
		this.budget = budget;
	}

	/** {@code server} must already have its {@code ${VAR}}s expanded. */
	public Result probe(JsonNode server) {
		try {
			List<String> tools = server.hasNonNull("command") ? viaStdio(server) : viaHttp(server);
			return new Result(true, tools, null);
		} catch (Exception e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			return new Result(false, List.of(), e.getMessage() == null ? e.toString() : e.getMessage());
		}
	}

	private String request(int id, String method, String params) {
		return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + params + "}";
	}

	private static final String INIT_PARAMS = "{\"protocolVersion\":\"" + PROTOCOL
			+ "\",\"capabilities\":{},\"clientInfo\":{\"name\":\"artus\",\"version\":\"1\"}}";

	private List<String> viaStdio(JsonNode server) throws IOException, InterruptedException {
		List<String> cmd = new ArrayList<>();
		cmd.add(server.get("command").asText());
		server.path("args").forEach(a -> cmd.add(a.asText()));
		ProcessBuilder pb = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD);
		server.path("env").properties().forEach(e -> pb.environment().put(e.getKey(), e.getValue().asText()));
		Process p = pb.start();
		long deadline = System.nanoTime() + budget.toNanos();
		try (Writer out = new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8);
			 BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			out.write(request(1, "initialize", INIT_PARAMS) + "\n");
			out.flush();
			readResponse(in, 1, deadline, p);
			out.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n");
			out.write(request(2, "tools/list", "{}") + "\n");
			out.flush();
			return toolNames(readResponse(in, 2, deadline, p));
		} finally {
			p.destroy();
			if (!p.waitFor(2, TimeUnit.SECONDS)) {
				p.destroyForcibly();
			}
		}
	}

	/** Reads lines until the response with {@code id}; a reader thread keeps the deadline enforceable. */
	private JsonNode readResponse(BufferedReader in, int id, long deadline, Process p) throws IOException, InterruptedException {
		while (true) {
			if (System.nanoTime() > deadline) {
				throw new IOException("timed out after " + budget.toSeconds() + " s");
			}
			if (!in.ready()) {
				if (!p.isAlive()) {
					throw new IOException("server exited (code " + p.exitValue() + ") before answering");
				}
				Thread.sleep(50);
				continue;
			}
			String line = in.readLine();
			if (line == null) {
				throw new IOException("server closed its output before answering");
			}
			JsonNode msg;
			try {
				msg = mapper.readTree(line);
			} catch (RuntimeException e) {
				continue; // a server logging to stdout; not protocol
			}
			if (msg.path("id").asInt(-1) == id) {
				if (msg.has("error")) {
					throw new IOException(msg.path("error").path("message").asText("MCP error"));
				}
				return msg.path("result");
			}
		}
	}

	private List<String> viaHttp(JsonNode server) throws IOException, InterruptedException {
		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
		URI uri = URI.create(server.get("url").asText());
		String[] session = new String[1];
		JsonNode init = post(client, uri, server, request(1, "initialize", INIT_PARAMS), session, 1);
		if (init == null) {
			throw new IOException("no initialize response");
		}
		post(client, uri, server, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", session, -1);
		JsonNode list = post(client, uri, server, request(2, "tools/list", "{}"), session, 2);
		return toolNames(list);
	}

	private JsonNode post(HttpClient client, URI uri, JsonNode server, String body, String[] session, int id)
			throws IOException, InterruptedException {
		HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(budget)
				.header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
				.header("MCP-Protocol-Version", PROTOCOL)
				.POST(HttpRequest.BodyPublishers.ofString(body));
		server.path("headers").properties().forEach(e -> b.header(e.getKey(), e.getValue().asText()));
		if (session[0] != null) {
			b.header("Mcp-Session-Id", session[0]);
		}
		HttpResponse<String> res = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
		if (res.statusCode() == 401 || res.statusCode() == 403) {
			throw new IOException("HTTP " + res.statusCode() + " — the server needs authentication (OAuth-only servers cannot be used headless)");
		}
		if (res.statusCode() >= 400) {
			throw new IOException("HTTP " + res.statusCode());
		}
		res.headers().firstValue("Mcp-Session-Id").ifPresent(s -> session[0] = s);
		if (id < 0) {
			return null;
		}
		String text = res.body();
		for (String chunk : text.split("\n")) {
			String payload = chunk.startsWith("data:") ? chunk.substring(5).strip() : chunk.strip();
			if (!payload.startsWith("{")) {
				continue;
			}
			JsonNode msg = mapper.readTree(payload);
			if (msg.path("id").asInt(-1) == id) {
				if (msg.has("error")) {
					throw new IOException(msg.path("error").path("message").asText("MCP error"));
				}
				return msg.path("result");
			}
		}
		throw new IOException("no response to request " + id);
	}

	private static List<String> toolNames(JsonNode result) {
		List<String> names = new ArrayList<>();
		result.path("tools").forEach(t -> names.add(t.path("name").asText()));
		return names;
	}
}
