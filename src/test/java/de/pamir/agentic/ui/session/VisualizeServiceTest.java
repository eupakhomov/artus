package de.pamir.agentic.ui.session;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The envelope parser — the only logic here that doesn't need a live system session. */
class VisualizeServiceTest {

	@Test
	void parsesTypeWhyAndFence() {
		var p = VisualizeService.parse("TYPE: sequence\nWHY: it is about calls over time\n```mermaid\nsequenceDiagram\n  A->>B: hi\n```", "auto");
		assertThat(p.type()).isEqualTo("sequence");
		assertThat(p.rationale()).isEqualTo("it is about calls over time");
		assertThat(p.mermaid()).isEqualTo("sequenceDiagram\n  A->>B: hi");
	}

	@Test
	void aFenceLessReplyIsTakenWholeMinusTheEnvelopeLines() {
		var p = VisualizeService.parse("TYPE: flowchart\nWHY: flow\nflowchart TD\n  A[\"x\"] --> B[\"y\"]", "auto");
		assertThat(p.type()).isEqualTo("flowchart");
		assertThat(p.mermaid()).isEqualTo("flowchart TD\n  A[\"x\"] --> B[\"y\"]");
	}

	@Test
	void anUnknownTypeIsInferredFromTheDiagramKeyword() {
		var p = VisualizeService.parse("TYPE: gantt\n```mermaid\ngraph LR\n  A --> B\n```", "auto");
		assertThat(p.type()).isEqualTo("flowchart");
	}

	@Test
	void anUninferrableBodyFallsBackToTheRequestedTypeElseFails() {
		assertThat(VisualizeService.parse("```mermaid\nA --> B\n```", "state").type()).isEqualTo("state");
		assertThatThrownBy(() -> VisualizeService.parse("```mermaid\nA --> B\n```", "auto"))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("unsupported");
	}

	@Test
	void anEmptyBodyFails() {
		assertThatThrownBy(() -> VisualizeService.parse("TYPE: flowchart\nWHY: none", "auto"))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("no diagram");
	}

	@Test
	void anOversizedDiagramIsRefusedBeforeItGutsTheJournalPayloadCap() {
		String big = "flowchart TD\n" + "  A --> B\n".repeat(6000);
		assertThatThrownBy(() -> VisualizeService.parse("TYPE: flowchart\n```mermaid\n" + big + "```", "auto"))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("oversized");
	}
}
