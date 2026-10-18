package de.pamir.agentic.ui.web;

import de.pamir.agentic.ui.library.EmbeddingClient;
import de.pamir.agentic.ui.memory.MemoryDocService;
import de.pamir.agentic.ui.memory.MemoryEpisodeRepository;
import de.pamir.agentic.ui.memory.MemoryProposalRepository;
import de.pamir.agentic.ui.memory.MemoryRepository;
import de.pamir.agentic.ui.memory.ProposalEvidenceService;
import de.pamir.agentic.ui.memory.ReflectionService;
import tools.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Human-facing memory API: cross-scope search, doc CRUD (archive-not-delete), episode browsing. */
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

	public record SearchHitView(String kind, UUID id, String name, String scope, String servicePath,
								 String description, List<String> tags, String sessionName, Instant ts,
								 double score) {
	}

	public record DocView(UUID id, String scope, String servicePath, String name, String description,
						   List<String> tags, String status, Instant createdAt, Instant updatedAt) {
	}

	public record DocDetailView(DocView doc, String content, int page, int totalPages,
								 List<MemoryRepository.LinkRef> outgoing, List<MemoryRepository.LinkRef> backlinks) {
	}

	public record CreateDocRequest(String scope, String servicePath, String name, String description,
									List<String> tags, String content) {
	}

	public record UpdateDocRequest(String description, List<String> tags, String content) {
	}

	/** A proposal plus the transcript snippets its {@code evidence} seqs resolve to (phase 21). */
	public record ProposalView(UUID id, UUID sessionId, String sessionName, String servicePath, Long reflectedSeq,
							   String episode, JsonNode ops, String status, Instant createdAt, Instant decidedAt,
							   String kind, java.util.Map<Long, String> evidence) {
	}

	public record ApproveProposalRequest(String episode, JsonNode ops) {
	}

	private final MemoryRepository docs;
	private final MemoryEpisodeRepository episodes;
	private final MemoryDocService docService;
	private final EmbeddingClient embeddings;
	private final MemoryProposalRepository proposals;
	private final ReflectionService reflection;
	private final ProposalEvidenceService evidence;

	public MemoryController(MemoryRepository docs, MemoryEpisodeRepository episodes, MemoryDocService docService,
							 EmbeddingClient embeddings, MemoryProposalRepository proposals,
							 ReflectionService reflection, ProposalEvidenceService evidence) {
		this.docs = docs;
		this.episodes = episodes;
		this.docService = docService;
		this.embeddings = embeddings;
		this.proposals = proposals;
		this.reflection = reflection;
		this.evidence = evidence;
	}

	@GetMapping("/search")
	public List<SearchHitView> search(@RequestParam String q, @RequestParam(defaultValue = "all") String kind,
									   @RequestParam(required = false) String servicePath,
									   @RequestParam(required = false) List<String> tags,
									   @RequestParam(defaultValue = "20") int limit) {
		if (q == null || q.isBlank()) {
			throw new IllegalArgumentException("q is required");
		}
		int k = Math.max(1, Math.min(100, limit));
		float[] embedding = embeddings.tryEmbed(q, true);
		List<SearchHitView> hits = new ArrayList<>();
		if (!"episodic".equals(kind)) {
			for (var hit : docs.hybridSearch(q, embedding, servicePath, tags, k)) {
				var d = hit.doc();
				hits.add(new SearchHitView("semantic", d.id(), d.name(), d.scope(), d.servicePath(),
						d.description(), d.tags(), null, null, hit.score()));
			}
		}
		if (!"semantic".equals(kind)) {
			for (var hit : episodes.hybridSearch(q, embedding, servicePath, k)) {
				var e = hit.episode();
				hits.add(new SearchHitView("episodic", e.id(), null, null, e.servicePath(), e.summary(),
						List.of(), e.sessionName(), e.ts(), hit.score()));
			}
		}
		return hits.stream().sorted((a, b) -> Double.compare(b.score(), a.score())).limit(k).toList();
	}

	@GetMapping("/docs")
	public List<DocView> listDocs(@RequestParam(required = false) String scope,
								   @RequestParam(required = false) String servicePath,
								   @RequestParam(required = false) String status,
								   @RequestParam(required = false) String q,
								   @RequestParam(required = false) Integer limit,
								   @RequestParam(required = false) Integer offset) {
		return docs.findAll(scope, servicePath, status, q, limit, offset).stream().map(MemoryController::view).toList();
	}

	@GetMapping("/docs/{id}")
	public DocDetailView getDoc(@PathVariable UUID id, @RequestParam(defaultValue = "1") int page) {
		var result = docService.read(id, page);
		return new DocDetailView(view(result.doc()), result.pageContent(), result.page(), result.totalPages(),
				result.outgoing(), result.backlinks());
	}

	@PostMapping("/docs")
	public DocView createDoc(@RequestBody CreateDocRequest request) {
		validateScope(request.scope(), request.servicePath());
		var doc = docService.write(request.scope(), request.servicePath(), request.name(),
				request.description() == null ? "" : request.description(),
				request.tags() == null ? List.of() : request.tags(),
				request.content() == null ? "" : request.content());
		return view(doc);
	}

	@PutMapping("/docs/{id}")
	public DocView updateDoc(@PathVariable UUID id, @RequestBody UpdateDocRequest request) {
		var existing = docs.get(id);
		var updated = docService.write(existing.scope(), existing.servicePath(), existing.name(),
				request.description() != null ? request.description() : existing.description(),
				request.tags() != null ? request.tags() : existing.tags(),
				request.content() != null ? request.content() : existing.content());
		return view(updated);
	}

	@DeleteMapping("/docs/{id}")
	public ResponseEntity<Void> archiveDoc(@PathVariable UUID id) {
		docService.archive(id);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/docs/{id}/restore")
	public DocView restoreDoc(@PathVariable UUID id) {
		docService.restore(id);
		return view(docs.get(id));
	}

	/** Just the number the topbar badge needs — the dashboard polls this every minute, so no evidence resolution. */
	@GetMapping("/proposals/pending-count")
	public java.util.Map<String, Long> pendingCount() {
		return java.util.Map.of("count", proposals.countPending());
	}

	@GetMapping("/proposals")
	public List<ProposalView> listProposals(@RequestParam(defaultValue = "PENDING") String status) {
		return proposals.findByStatus(status).stream()
				.map(p -> new ProposalView(p.id(), p.sessionId(), p.sessionName(), p.servicePath(), p.reflectedSeq(),
						p.episode(), p.ops(), p.status(), p.createdAt(), p.decidedAt(), p.kind(),
						evidence.resolve(p.sessionId(), p.ops())))
				.toList();
	}

	@PostMapping("/proposals/{id}/approve")
	public ResponseEntity<Void> approveProposal(@PathVariable UUID id, @RequestBody(required = false) ApproveProposalRequest request) {
		reflection.approveProposal(id, request == null ? null : request.episode(), request == null ? null : request.ops());
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/proposals/{id}/discard")
	public ResponseEntity<Void> discardProposal(@PathVariable UUID id) {
		reflection.discardProposal(id);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/episodes")
	public List<MemoryEpisodeRepository.Episode> listEpisodes(@RequestParam String servicePath,
															   @RequestParam(required = false) Integer limit,
															   @RequestParam(required = false) Integer offset) {
		return episodes.findByService(servicePath, limit, offset);
	}

	private static void validateScope(String scope, String servicePath) {
		if (!Set.of("ecosystem", "service").contains(scope)) {
			throw new IllegalArgumentException("scope must be 'ecosystem' or 'service'");
		}
		if ("service".equals(scope) && (servicePath == null || servicePath.isBlank())) {
			throw new IllegalArgumentException("servicePath is required for scope 'service'");
		}
	}

	private static DocView view(MemoryRepository.MemoryDoc doc) {
		return new DocView(doc.id(), doc.scope(), doc.servicePath(), doc.name(), doc.description(), doc.tags(),
				doc.status(), doc.createdAt(), doc.updatedAt());
	}
}
