package org.cote.accountmanager.olio.illustration;

/// A visual-QA backend (PictureBook V3 design §17): judges one rendered image against the
/// {@link RenderPlan} it was rendered from and returns a {@link QaReport}. Pluggable so the default
/// vision-LLM provider and the Swarm YOLO fallback share one contract and one storage shape.
///
/// A provider never blocks or deletes anything. It reports; the executor's hook persists the report,
/// orders the attempt's variants and flags images for review (ledger §6.C 220 c, d, g).
public interface QaProvider {

	/// Short stable name recorded on every report ({@link QaReport#getProvider()}), e.g. {@code vlm},
	/// {@code swarmYolo}.
	String name();

	/// Whether the backend can be reached right now. The selector asks before choosing a provider; a
	/// provider that cannot tell should answer true and let {@link #check} report UNAVAILABLE.
	default boolean isAvailable() {
		return true;
	}

	/// Why {@link #isAvailable} answered false the last time it was asked (the probe's one-line reason), or
	/// null when it answered true or was never asked. The selector quotes it in the {@code QA_UNAVAILABLE}
	/// degradation when no provider can be chosen (P8-4).
	default String availabilityNote() {
		return null;
	}

	/// Judge {@code imageBytes} (PNG) against {@code plan}. Must not throw for an ordinary backend failure -
	/// return {@link QaReport#unavailable} with the reason instead. Anything it does throw is caught by the
	/// hook and recorded as a {@code QA_UNAVAILABLE} degradation, never as a failed render (220 g).
	QaReport check(RenderPlan plan, byte[] imageBytes);
}
