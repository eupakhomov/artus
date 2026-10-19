package de.pamir.agentic.ui.session;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory {@link SessionRepository} double — no real Postgres, no Mockito. Only overrides the
 * methods SessionService/SystemSessionService's tested paths actually call; see
 * docs/plan/phase-9-production-hardening.md T2.
 */
final class FakeSessionRepository extends SessionRepository {

	private final Map<UUID, SessionEntity> byId = new ConcurrentHashMap<>();
	private final Map<UUID, List<QueuedMessage>> queues = new ConcurrentHashMap<>();
	private final AtomicLong posSeq = new AtomicLong();

	FakeSessionRepository() {
		super(null, null);
	}

	void seed(SessionEntity entity) {
		byId.put(entity.id(), entity);
	}

	@Override
	public void insert(SessionEntity s) {
		byId.put(s.id(), s);
	}

	@Override
	public Optional<SessionEntity> find(UUID id) {
		return Optional.ofNullable(byId.get(id));
	}

	@Override
	public SessionEntity get(UUID id) {
		return find(id).orElseThrow(() -> new NoSuchElementException("session " + id + " not found"));
	}

	@Override
	public Optional<SessionEntity> findSystemSession(String name) {
		return findLiveSystemSessions().stream().filter(e -> name.equals(e.name())).findFirst();
	}

	@Override
	public List<SessionEntity> findLiveSystemSessions() {
		return byId.values().stream()
				.filter(e -> "system".equals(e.kind()) && e.state() != SessionState.CLOSED && e.state() != SessionState.FAILED)
				.toList();
	}

	@Override
	public List<SessionEntity> findAll() {
		return List.copyOf(byId.values());
	}

	@Override
	public List<SessionEntity> findByStates(List<SessionState> states) {
		return byId.values().stream().filter(e -> states.contains(e.state())).toList();
	}

	@Override
	public void updateState(UUID id, SessionState state) {
		byId.computeIfPresent(id, (k, e) -> e.toBuilder().state(state).build());
	}

	@Override
	public void markClosed(UUID id) {
		byId.computeIfPresent(id, (k, e) -> e.toBuilder().state(SessionState.CLOSED).closedAt(java.time.Instant.now()).build());
	}

	@Override
	public void updateCapabilities(UUID id, tools.jackson.databind.JsonNode capabilities) {
		byId.computeIfPresent(id, (k, e) -> e.toBuilder().capabilities(capabilities).build());
	}

	@Override
	public void updateProviderSessionId(UUID id, String providerSessionId) {
		byId.computeIfPresent(id, (k, e) -> e.toBuilder().providerSessionId(providerSessionId).build());
	}

	@Override
	public void updateModel(UUID id, String model) {
		byId.computeIfPresent(id, (k, e) -> e.toBuilder().model(model).build());
	}

	@Override
	public void updatePermissionMode(UUID id, String mode) {
		byId.computeIfPresent(id, (k, e) -> e.toBuilder().permissionMode(mode).build());
	}

	@Override
	public void updateName(UUID id, String name) {
		byId.computeIfPresent(id, (k, e) -> e.toBuilder().name(name).build());
	}

	@Override
	public void updateCostBudget(UUID id, java.math.BigDecimal budget) {
		byId.computeIfPresent(id, (k, e) -> e.toBuilder().costBudgetUsd(budget).build());
	}

	@Override
	public void updateHidden(UUID id, boolean hidden) {
		byId.computeIfPresent(id, (k, e) -> e.toBuilder().hidden(hidden).build());
	}

	@Override
	public void updateReflectionEnabled(UUID id, boolean enabled) {
		byId.computeIfPresent(id, (k, e) -> e.toBuilder().reflectionEnabled(enabled).build());
	}

	@Override
	public void attachPr(UUID id, String prUrl, String headSha) {
		byId.computeIfPresent(id, (k, e) -> e.toBuilder().prUrl(prUrl).prHeadSha(headSha).prCheckStatus("PENDING").build());
	}

	@Override
	public void enqueue(UUID sessionId, String text) {
		queues.computeIfAbsent(sessionId, k -> new CopyOnWriteArrayList<>())
				.add(new QueuedMessage(posSeq.incrementAndGet(), text));
	}

	@Override
	public List<QueuedMessage> queued(UUID sessionId) {
		return List.copyOf(queues.getOrDefault(sessionId, List.of()));
	}

	@Override
	public Optional<QueuedMessage> peekQueue(UUID sessionId) {
		var list = queues.get(sessionId);
		return list == null || list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
	}

	@Override
	public boolean deleteQueued(UUID sessionId, long pos) {
		var list = queues.get(sessionId);
		return list != null && list.removeIf(m -> m.pos() == pos);
	}

	@Override
	public long countByStates(List<SessionState> states) {
		return byId.values().stream().filter(e -> states.contains(e.state())).count();
	}

	@Override
	public long countChildren(UUID parentId) {
		return byId.values().stream().filter(e -> parentId.equals(e.parentSessionId())).count();
	}

	@Override
	public List<SessionEntity> findByParent(UUID parentId) {
		return byId.values().stream().filter(e -> parentId.equals(e.parentSessionId())).toList();
	}
}
