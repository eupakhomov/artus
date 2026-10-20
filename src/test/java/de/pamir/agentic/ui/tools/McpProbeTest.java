package de.pamir.agentic.ui.tools;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The HTTP leg of the Test probe against a local streamable-HTTP stand-in. The first case is the
 * one the review flagged: a server that answers over SSE and (as the spec allows) never closes
 * the stream must still yield the tool list promptly, not hang the request thread.
 */
class McpProbeTest {

	private HttpServer server;

	@AfterEach
	void stop() {
		if (server != null) {
			server.stop(0);
		}
	}

	private String start(boolean keepOpen, boolean answer) throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		CountDownLatch never = new CountDownLatch(1);
		server.createContext("/mcp", ex -> {
			String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			int id = body.contains("\"id\":1") ? 1 : body.contains("\"id\":2") ? 2 : -1;
			if (id < 0) {
				ex.sendResponseHeaders(202, -1);
				ex.close();
				return;
			}
			ex.getResponseHeaders().add("Content-Type", "text/event-stream");
			ex.getResponseHeaders().add("Mcp-Session-Id", "s1");
			ex.sendResponseHeaders(200, 0);
			try (OutputStream out = ex.getResponseBody()) {
				if (answer) {
					String result = id == 1 ? "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{}}"
							: "{\"tools\":[{\"name\":\"alpha\"},{\"name\":\"beta\"}]}";
					out.write(("event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}\n\n")
							.getBytes(StandardCharsets.UTF_8));
					out.flush();
				}
				if (keepOpen) {
					try {
						never.await(5, TimeUnit.SECONDS); // longer than the probe budget used below
					} catch (InterruptedException ignored) {
						Thread.currentThread().interrupt();
					}
				}
			} catch (IOException ignored) {
				// client went away — expected once the probe has what it needs
			}
		});
		server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
		server.start();
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
	}

	private McpProbe.Result probe(String url, Duration budget) {
		var mapper = new JsonMapper();
		return new McpProbe(mapper, budget).probe(mapper.createObjectNode().put("url", url));
	}

	@Test
	void listsToolsFromAnSseStreamTheServerNeverCloses() throws Exception {
		long started = System.nanoTime();
		McpProbe.Result result = probe(start(true, true), Duration.ofSeconds(20));

		assertThat(result.error()).isNull();
		assertThat(result.tools()).containsExactly("alpha", "beta");
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
	}

	@Test
	void aSilentServerFailsWithinTheBudget() throws Exception {
		McpProbe.Result result = probe(start(true, false), Duration.ofSeconds(2));

		assertThat(result.ok()).isFalse();
		assertThat(result.error()).contains("timed out");
	}
}
