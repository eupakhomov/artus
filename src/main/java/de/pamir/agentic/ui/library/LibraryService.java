package de.pamir.agentic.ui.library;

import de.pamir.agentic.ui.config.SettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Imports scanned candidates into the managed skill/agent roots and owns asset CRUD +
 * semantic search. Copies never overwrite an existing different-content entry (suffixed
 * instead); identical content is reused. Embeddings are best-effort — a Voyage failure
 * surfaces as a warning, never a failed import.
 */
@Service
public class LibraryService {

	private static final Logger log = LoggerFactory.getLogger(LibraryService.class);
	private static final int EMBED_CONTENT_CHARS = 16_000;
	private static final int PREVIEW_CONTENT_CHARS = 40_000;

	public record ImportItem(String path, String kind, String name, String description, List<String> tags) {
	}

	public record ImportItemResult(String path, UUID assetId, String warning) {
	}

	public record ContentPreview(String content, boolean truncated, String sourceFile) {
	}

	private final SettingsService settings;
	private final AssetScanService scanner;
	private final LibraryRepository assets;
	private final AssetSourceRepository sources;
	private final EmbeddingClient embeddings;

	public LibraryService(SettingsService settings, AssetScanService scanner, LibraryRepository assets,
						   AssetSourceRepository sources, EmbeddingClient embeddings) {
		this.settings = settings;
		this.scanner = scanner;
		this.assets = assets;
		this.sources = sources;
		this.embeddings = embeddings;
	}

	// --- import ---

	public List<ImportItemResult> importItems(String type, String ref, boolean syncEnabled, List<ImportItem> items) {
		if (items == null || items.isEmpty()) {
			throw new IllegalArgumentException("no items to import");
		}
		Path root = scanner.resolveRoot(type, ref);
		var source = sources.upsert(type, ref, syncEnabled);
		List<ImportItemResult> results = new ArrayList<>();
		for (ImportItem item : items) {
			try {
				results.add(importOne(root, source.id(), item));
				sources.deleteDiscovery(source.id(), item.path());
			} catch (RuntimeException e) {
				log.warn("import of {} from {} failed", item.path(), ref, e);
				results.add(new ImportItemResult(item.path(), null, "import failed: " + e.getMessage()));
			}
		}
		return results;
	}

	private ImportItemResult importOne(Path root, UUID sourceId, ImportItem item) {
		if (!Set.of("skill", "agent").contains(item.kind())) {
			throw new IllegalArgumentException("bad kind: " + item.kind());
		}
		Path sourcePath = ".".equals(item.path()) ? root : root.resolve(item.path()).normalize();
		// real-path check so a symlink inside a (potentially third-party) source can't
		// make the import copy files from outside the source root
		if (!sourcePath.startsWith(root) || !AssetScanService.staysInside(root, sourcePath)) {
			throw new IllegalArgumentException("path escapes the source root: " + item.path());
		}
		if (!Files.exists(sourcePath)) {
			throw new IllegalArgumentException("no longer present in source: " + item.path());
		}
		String warning = null;
		String hash = scanner.hash(sourcePath);
		Path destRoot = Path.of("skill".equals(item.kind())
				? settings.current().librarySkillsRoot() : settings.current().libraryAgentsRoot());
		Path dest = destRoot.resolve(destName(root, sourcePath, item));
		if (Files.exists(dest)) {
			if (hash.equals(scanner.hash(dest))) {
				warning = "identical copy already present at " + dest + "; reused";
			} else {
				dest = firstFree(dest);
				warning = "name collision; imported as " + dest.getFileName();
			}
		}
		if (!Files.exists(dest)) {
			copyAsset(sourcePath, dest, item.kind());
		}
		String name = item.name() == null || item.name().isBlank()
				? dest.getFileName().toString() : item.name().strip();
		var asset = assets.insert(sourceId, item.kind(), name,
				item.description() == null ? "" : item.description().strip(),
				dest.toString(), item.path(), hash, item.tags());
		String embedWarning = maybeEmbed(asset.id(), name, asset.description(), dest);
		return new ImportItemResult(item.path(), asset.id(),
				warning != null ? warning : embedWarning);
	}

	/** Skill dirs keep their name; a bare .md skill becomes <basename>/SKILL.md; agents keep the file name. */
	private String destName(Path root, Path sourcePath, ImportItem item) {
		if (sourcePath.equals(root)) {
			// whole source root is one skill — name it after the last ref segment
			String base = root.getFileName().toString();
			return base.isBlank() ? "imported-skill" : base;
		}
		String fileName = sourcePath.getFileName().toString();
		if ("skill".equals(item.kind()) && Files.isRegularFile(sourcePath)) {
			return fileName.replaceFirst("\\.md$", "");
		}
		return fileName;
	}

	private void copyAsset(Path source, Path dest, String kind) {
		try {
			if (Files.isDirectory(source)) {
				copyRecursive(source, dest);
			} else if ("skill".equals(kind)) {
				Files.createDirectories(dest);
				Files.copy(source, dest.resolve("SKILL.md"), StandardCopyOption.REPLACE_EXISTING);
			} else {
				Files.createDirectories(dest.getParent());
				Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static Path firstFree(Path dest) {
		String base = dest.getFileName().toString();
		String stem = base;
		String ext = "";
		int dot = base.lastIndexOf('.');
		if (dot > 0) {
			stem = base.substring(0, dot);
			ext = base.substring(dot);
		}
		for (int i = 2; ; i++) {
			Path candidate = dest.resolveSibling(stem + "-" + i + ext);
			if (!Files.exists(candidate)) {
				return candidate;
			}
		}
	}

	// --- skills authored inside Artus (phase 21 skill extraction) ---

	private static final java.util.regex.Pattern SKILL_NAME = java.util.regex.Pattern.compile("[a-z0-9][a-z0-9-]{0,62}");

	/** The one definition of a legal skill slug — shared with skill extraction so the two can't drift. */
	public static boolean isValidSkillName(String name) {
		return name != null && SKILL_NAME.matcher(name).matches();
	}

	/** Names of every skill already in the library — shown to the extraction prompt so it avoids duplicates. */
	public List<String> skillNames() {
		return assets.findAll("skill", null, null).stream().map(LibraryRepository.AssetEntity::name).toList();
	}

	/**
	 * Writes a brand-new skill into the managed skills root as {@code <root>/<name>/SKILL.md} and
	 * registers it like an import (no upstream source, so the sync loop never touches it). A name
	 * that already exists — as a directory under the root or as a library skill — is refused rather
	 * than overwritten or suffixed: the caller edits the name and tries again.
	 */
	public LibraryRepository.AssetEntity createSkill(String name, String description, List<String> tags, String body) {
		if (!isValidSkillName(name)) {
			throw new IllegalArgumentException("skill name must be a lowercase kebab-case slug (a-z, 0-9, '-'), got: " + name);
		}
		if (body == null || body.isBlank()) {
			throw new IllegalArgumentException("skill content is empty");
		}
		String root = settings.current().librarySkillsRoot();
		if (root == null || root.isBlank()) {
			throw new IllegalStateException("no skills root configured (Settings -> Skill library)");
		}
		Path dir = Path.of(root).resolve(name).normalize();
		if (!dir.startsWith(Path.of(root).normalize())) {
			throw new IllegalArgumentException("skill path escapes the skills root: " + name);
		}
		if (Files.exists(dir) || assets.findAll("skill", null, name).stream().anyMatch(a -> a.name().equalsIgnoreCase(name))) {
			throw new IllegalStateException("a skill named '" + name + "' already exists - rename it and approve again");
		}
		try {
			Files.createDirectories(dir);
			Files.writeString(dir.resolve("SKILL.md"), renderSkillMd(name, description, body), StandardCharsets.UTF_8);
			var asset = assets.insert(null, "skill", name, description == null ? "" : description.strip(),
					dir.toString(), name, scanner.hash(dir), tags == null ? List.of() : tags);
			maybeEmbed(asset.id(), name, asset.description(), dir);
			return asset;
		} catch (IOException e) {
			deleteRecursively(dir);
			throw new UncheckedIOException(e);
		} catch (RuntimeException e) {
			deleteRecursively(dir);
			throw e;
		}
	}

	/** SKILL.md = YAML frontmatter (name, description) + the body, the format the scanner and Claude/Codex read. */
	static String renderSkillMd(String name, String description, String body) {
		String oneLine = description == null ? "" : description.strip().replaceAll("\\s+", " ");
		String quoted = "\"" + oneLine.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
		return "---\nname: " + name + "\ndescription: " + quoted + "\n---\n\n" + body.strip() + "\n";
	}

	// --- sync support (used by LibrarySyncService) ---

	/** Replaces the managed copy with fresh upstream content and updates the stored hash. */
	public void refreshAsset(LibraryRepository.AssetEntity asset, Path upstream, String newHash) {
		Path dest = Path.of(asset.location());
		deleteRecursively(dest);
		copyAsset(upstream, dest, asset.kind());
		assets.updateHash(asset.id(), newHash);
		maybeEmbed(asset.id(), asset.name(), asset.description(), dest);
	}

	// --- CRUD ---

	public LibraryRepository.AssetEntity updateAsset(UUID id, String name, String description, List<String> tags,
													  String status) {
		var asset = assets.get(id);
		boolean metaChanged = false;
		if (name != null || description != null) {
			String newName = name != null && !name.isBlank() ? name.strip() : asset.name();
			String newDescription = description != null ? description.strip() : asset.description();
			assets.updateMeta(id, newName, newDescription);
			metaChanged = !newName.equals(asset.name()) || !newDescription.equals(asset.description());
		}
		if (tags != null) {
			assets.replaceTags(id, tags);
		}
		if (status != null) {
			if (!Set.of("ACTIVE", "ARCHIVED").contains(status)) {
				throw new IllegalArgumentException("bad status: " + status);
			}
			assets.updateStatus(id, status);
		}
		var updated = assets.get(id);
		if (metaChanged) {
			maybeEmbed(id, updated.name(), updated.description(), Path.of(updated.location()));
		}
		return updated;
	}

	public void deleteAsset(UUID id) {
		var asset = assets.get(id);
		deleteRecursively(Path.of(asset.location()));
		assets.delete(id);
	}

	// --- embeddings & search ---

	/** Best-effort; returns a warning message on failure, null on success or when disabled. */
	public String maybeEmbed(UUID assetId, String name, String description, Path location) {
		if (!settings.current().libraryVectorize() || !embeddings.configured()) {
			return null;
		}
		try {
			String text = name + "\n" + description + "\n" + readContent(location);
			assets.upsertEmbedding(assetId, embeddings.embed(text, false), embeddings.model());
			return null;
		} catch (RuntimeException e) {
			log.warn("embedding failed for asset {}: {}", assetId, e.getMessage());
			return "embedding failed: " + e.getMessage();
		}
	}

	/**
	 * Hybrid (dense+sparse+trigram) search — dense arm skipped when Voyage isn't configured or
	 * when embedding the query fails once configured (docs/plan/phase-9-production-hardening.md
	 * O3, docs/plan/phase-10-review-followups.md R2 — same {@code tryEmbed} degrade-to-sparse
	 * pattern as memory search), so this never throws for either case.
	 */
	public List<LibraryRepository.SearchHit> search(String query, int limit, String kind) {
		float[] embedding = embeddings.tryEmbed(query, true);
		return assets.hybridSearch(query, embedding, kind, limit);
	}

	/** Full text of an asset's primary file, for the library detail drawer; capped for display. */
	public ContentPreview previewContent(LibraryRepository.AssetEntity asset) {
		Path file = contentFile(Path.of(asset.location()));
		try {
			byte[] bytes = Files.readAllBytes(file);
			String content = new String(bytes, StandardCharsets.UTF_8);
			boolean truncated = content.length() > PREVIEW_CONTENT_CHARS;
			return new ContentPreview(truncated ? content.substring(0, PREVIEW_CONTENT_CHARS) : content,
					truncated, file.getFileName().toString());
		} catch (IOException e) {
			return new ContentPreview("", false, file.getFileName().toString());
		}
	}

	/** A skill's primary file is its SKILL.md; an agent's is the file itself. */
	private static Path contentFile(Path location) {
		return Files.isDirectory(location) ? location.resolve("SKILL.md") : location;
	}

	private static String readContent(Path location) {
		Path file = contentFile(location);
		try {
			byte[] bytes = Files.readAllBytes(file);
			String content = new String(bytes, StandardCharsets.UTF_8);
			return content.length() > EMBED_CONTENT_CHARS ? content.substring(0, EMBED_CONTENT_CHARS) : content;
		} catch (IOException e) {
			return "";
		}
	}

	private static void deleteRecursively(Path path) {
		if (!Files.exists(path)) {
			return;
		}
		try (Stream<Path> walk = Files.walk(path)) {
			walk.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.delete(p);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			});
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void copyRecursive(Path source, Path target) throws IOException {
		try (Stream<Path> walk = Files.walk(source)) {
			for (Path p : walk.toList()) {
				if (!AssetScanService.staysInside(source, p)) {
					continue; // escaping symlink — mirrors the hashTree filter, so hashes stay honest
				}
				Path dest = target.resolve(source.relativize(p).toString());
				if (Files.isDirectory(p)) {
					Files.createDirectories(dest);
				} else {
					Files.createDirectories(dest.getParent());
					Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}
}
