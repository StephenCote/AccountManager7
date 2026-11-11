package org.cote.accountmanager.olio.picturebook;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * The boot-pinned render-path switch for the chat scene image (design §28.1 / §24.1, P2-7 chat; P10-A 0
 * flipped the default): one value, {@code chat.renderPath}, {@code v1} or {@code v3}. Under {@link #V3}
 * ({@link #CHAT_DEFAULT}) the scene renders through the shared illustration service; {@code chat.renderPath=v1}
 * is the rollback to the inline composite in {@code ChatService.generateScene}, which stays live and is
 * logged at WARN when applied.
 * <p>
 * Until Phase 10 this class also carried the {@code picturebook.renderPath} / {@code chapbook.renderPath}
 * keys that staged the PB1 and old-ChapBook removals (§28.1 Stages 1-3). Both products now have exactly one
 * render path - the V3 graph - so the keys, their refusals (the V3 / PB1-entry / composite-mode gates) and
 * the per-book {@code keyFor} / {@code valueFor} / {@code isV3} readers were removed at P10-1 (§6.C 248 g).
 * <p>
 * Read once at start-up by the transport's startup listener (Service7 {@code web.xml} init-param, which on
 * Docker is regenerated from {@code docker/web.xml.template} on every boot from the {@code CHAT_RENDER_PATH}
 * environment variable) and by Console7 ({@code resource.properties}). It is deliberately process-global,
 * not per-organization, and not DB-backed: rollback is "set {@code v1} and restart". The chat value never
 * refuses: it selects which path renders, at the top of the transport's scene route, after authorization.
 */
public final class PbRenderPath {

	private static final Logger logger = LogManager.getLogger(PbRenderPath.class);

	/** Service7 init-param / Console7 property for the chat scene image. */
	public static final String CHAT_CONFIG_KEY = "chat.renderPath";

	public static final String V1 = "v1";
	public static final String V3 = "v3";

	/**
	 * Chat scene default. P2-7 pinned {@link #V1} because V3 was TEXT_ONLY with no portrait references until
	 * Phase 4; the test stacks have run chat on V3 since. Flipped to {@link #V3} at P10-A 0 (§6.C 249) so a
	 * plain Docker boot renders the chat scene through the illustration service; {@link #V1} is the rollback.
	 */
	public static final String CHAT_DEFAULT = V3;

	/** Logged at WARN whenever the chat rollback value is applied (P10-A 0), so a rolled-back deployment is visible in the log. */
	public static final String CHAT_ROLLBACK_WARNING = "legacy chat scene composite re-enabled (rollback)";

	private static volatile String chat = CHAT_DEFAULT;

	private PbRenderPath() {
	}

	/**
	 * Pin the chat value. Null/blank means "absent" and pins {@link #CHAT_DEFAULT} at INFO; an unrecognised
	 * value pins the default at WARN naming the key and the value (a typo must never silently move the
	 * product off its default); {@code chat.renderPath=v1} is honoured and logged at WARN as
	 * {@link #CHAT_ROLLBACK_WARNING}; a change is logged at INFO. Never throws.
	 */
	public static void configure(String chatValue) {
		chat = resolve(CHAT_CONFIG_KEY, chatValue, chat, CHAT_DEFAULT);
		if(V1.equals(chat)) {
			logger.warn(CHAT_CONFIG_KEY + "=" + V1 + ": " + CHAT_ROLLBACK_WARNING);
		}
	}

	private static String resolve(String key, String raw, String current, String productDefault) {
		String use;
		if(raw == null || raw.trim().isEmpty()) {
			use = productDefault;
			logger.info(key + " absent; render path pinned to the product default " + productDefault);
		}
		else {
			String norm = raw.trim().toLowerCase();
			if(V1.equals(norm) || V3.equals(norm)) {
				use = norm;
			}
			else {
				use = productDefault;
				logger.warn(key + "=" + raw.trim() + " is not " + V1 + " or " + V3 + "; render path pinned to the product default " + productDefault);
			}
		}
		if(!use.equals(current)) {
			logger.info(key + " set to " + use + " (was " + current + ")");
		}
		return use;
	}

	public static String chat() {
		return chat;
	}

	/** Whether the chat scene image renders through the shared illustration service. */
	public static boolean isChatV3() {
		return V3.equals(chat);
	}
}
