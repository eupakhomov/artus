package de.pamir.agentic.ui.ws;

import de.pamir.agentic.ui.config.AuthTokenService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.List;
import java.util.Map;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

	private final SessionWebSocketHandler handler;
	private final AuthTokenService auth;

	public WebSocketConfig(SessionWebSocketHandler handler, AuthTokenService auth) {
		this.handler = handler;
		this.auth = auth;
	}

	/**
	 * Tomcat's default text-message limit is 8 KB; a longer client frame closes the socket (1009) and the
	 * dashboard silently reconnects, losing the message. Pasted logs/specs easily exceed that, so allow 16 MB.
	 */
	@Bean
	public ServletServerContainerFactoryBean webSocketContainer() {
		var container = new ServletServerContainerFactoryBean();
		container.setMaxTextMessageBufferSize(16 * 1024 * 1024);
		container.setMaxBinaryMessageBufferSize(16 * 1024 * 1024);
		return container;
	}

	@Override
	public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
		registry.addHandler(handler, "/ws/sessions/*")
				.addInterceptors(authInterceptor())
				// Origin policy is not Spring's job here: with the token on, any origin may connect (the
				// token is the gate); with it off, LocalClientFilter sees the handshake as a plain HTTP
				// request first and enforces Host/Origin/client address (phase 17 B).
				.setAllowedOrigins("*");
	}

	/**
	 * Token travels as a Sec-WebSocket-Protocol entry: the client requests
	 * ["artus.v1", "bearer.<token>"]; the server validates the bearer entry and
	 * echoes artus.v1 (via SubProtocolCapable) per RFC 6455.
	 */
	private HandshakeInterceptor authInterceptor() {
		return new HandshakeInterceptor() {
			@Override
			public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
										   WebSocketHandler wsHandler, Map<String, Object> attributes) {
				if (!auth.required()) {
					return true;
				}
				List<String> protocols = request.getHeaders().get("Sec-WebSocket-Protocol");
				if (protocols != null) {
					for (String headerValue : protocols) {
						for (String entry : headerValue.split(",")) {
							String candidate = entry.strip();
							if (candidate.startsWith("bearer.") && auth.matches(candidate.substring("bearer.".length()))) {
								return true;
							}
						}
					}
				}
				response.setStatusCode(HttpStatus.UNAUTHORIZED);
				return false;
			}

			@Override
			public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
									   WebSocketHandler wsHandler, Exception exception) {
			}
		};
	}
}
