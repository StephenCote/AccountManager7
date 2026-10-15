package org.cote.accountmanager.olio.picturebook;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.sd.SceneCompositeUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.type.PbBookTypeEnumType;

/**
 * The boot-pinned V3 render-path switch (design §28.1, P1-8 Stage 1 / P2-6 Stage 2 / P2-7 chat): one
 * value per product, {@code picturebook.renderPath}, {@code chapbook.renderPath} and
 * {@code chat.renderPath}, each {@code v1} or {@code v3}. The defaults differ by product and by stage:
 * PictureBook stays on {@link #V1} ({@link #PICTUREBOOK_DEFAULT}, Stage 1 - PB1 is live and default),
 * ChapBook defaults to {@link #V3} ({@link #CHAPBOOK_DEFAULT}, Stage 2 - the old ChapBook path is
 * disabled, and {@code chapbook.renderPath=v1} is the documented rollback value, logged at WARN when it
 * is applied), and the chat scene image stays on {@link #V1} ({@link #CHAT_DEFAULT}: the inline
 * composite in {@code ChatService.generateScene} is live and default; {@code v3} routes the scene
 * through the shared illustration service, design §24.1).
 * <p>
 * Read once at start-up by the transport's startup listener (Service7 {@code web.xml} init-param,
 * which on Docker is regenerated from {@code docker/web.xml.template} on every boot from the
 * {@code PICTUREBOOK_RENDER_PATH} / {@code CHAPBOOK_RENDER_PATH} / {@code CHAT_RENDER_PATH} environment
 * variables) and by Console7 ({@code resource.properties}). It is deliberately process-global, not
 * per-organization, and not DB-backed: rollback is "set {@code v1} and restart".
 * <p>
 * The switch is checked <b>only at facade entry</b> ({@link PbServiceFacade}; for chat, at the top of
 * the transport's scene route), never inside {@link PbRenderJobUtil} or {@link PbNodeExecutor}: a
 * value changed mid-run (which only a test can do) cannot abort a job that was admitted. Under
 * {@code v1} the Phase 1 routes refuse with a 409 whose message starts with {@link #REFUSAL_PREFIX};
 * the P0/P1-2 read views ({@code workflowView}, {@code nodeView}, {@code artifactView},
 * {@code listStale}, {@code affectedByRecord}) stay readable under either value, and the boot-time
 * {@link PbRecoveryUtil} sweep runs regardless (it only touches V3 run/node rows, which exist only if
 * V3 ever ran). The chat value never refuses: it selects which path renders.
 */
public final class PbRenderPath {

	private static final Logger logger = LogManager.getLogger(PbRenderPath.class);

	/** Service7 init-param / Console7 property for the PictureBook product. */
	public static final String PICTUREBOOK_CONFIG_KEY = "picturebook.renderPath";

	/** Service7 init-param / Console7 property for the ChapBook product. */
	public static final String CHAPBOOK_CONFIG_KEY = "chapbook.renderPath";

	/** Service7 init-param / Console7 property for the chat scene image. */
	public static final String CHAT_CONFIG_KEY = "chat.renderPath";

	public static final String V1 = "v1";
	public static final String V3 = "v3";

	/** PictureBook product default (§28.1 Stage 1): the PB1 path stays live and default. */
	public static final String PICTUREBOOK_DEFAULT = V1;

	/** ChapBook product default (§28.1 Stage 2): the old ChapBook path is disabled; {@link #V1} is the rollback. */
	public static final String CHAPBOOK_DEFAULT = V3;

	/** Chat scene default (P2-7): the inline v1 composite stays live and default; {@link #V3} is opt-in. */
	public static final String CHAT_DEFAULT = V1;

	/** Every refusal message starts with this, so clients and tests can key on it. */
	public static final String REFUSAL_PREFIX = "render path is v1";

	/** Logged at WARN whenever the ChapBook rollback value is applied, so a rolled-back deployment is visible in the log. */
	public static final String CHAPBOOK_ROLLBACK_WARNING = "legacy ChapBook render path re-enabled (rollback)";

	private static volatile String pictureBook = PICTUREBOOK_DEFAULT;
	private static volatile String chapBook = CHAPBOOK_DEFAULT;
	private static volatile String chat = CHAT_DEFAULT;

	private PbRenderPath() {
	}

	/**
	 * Pin all three values. Null/blank means "absent" and pins the product default at INFO
	 * ({@link #PICTUREBOOK_DEFAULT} / {@link #CHAPBOOK_DEFAULT} / {@link #CHAT_DEFAULT}); an unrecognised
	 * value pins the product default at WARN naming the key and the value (a typo must never silently
	 * move a product off its default); {@code chapbook.renderPath=v1} is honoured and logged at WARN as
	 * {@link #CHAPBOOK_ROLLBACK_WARNING}; a change is logged at INFO. Never throws.
	 */
	public static void configure(String pictureBookValue, String chapBookValue, String chatValue) {
		pictureBook = resolve(PICTUREBOOK_CONFIG_KEY, pictureBookValue, pictureBook, PICTUREBOOK_DEFAULT);
		chapBook = resolve(CHAPBOOK_CONFIG_KEY, chapBookValue, chapBook, CHAPBOOK_DEFAULT);
		chat = resolve(CHAT_CONFIG_KEY, chatValue, chat, CHAT_DEFAULT);
		if(V1.equals(chapBook)) {
			logger.warn(CHAPBOOK_CONFIG_KEY + "=" + V1 + ": " + CHAPBOOK_ROLLBACK_WARNING);
		}
	}

	/**
	 * The two-product form leaves the chat value exactly as it is: it exists for the Phase 1/2 tests
	 * that save and restore only the PictureBook/ChapBook pair, so restoring that pair must not move
	 * chat off whatever the test fixture pinned. Every start-up caller uses the three-value form.
	 */
	public static void configure(String pictureBookValue, String chapBookValue) {
		configure(pictureBookValue, chapBookValue, chat);
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

	public static String pictureBook() {
		return pictureBook;
	}

	public static String chapBook() {
		return chapBook;
	}

	public static String chat() {
		return chat;
	}

	/** Whether the chat scene image renders through the shared illustration service. */
	public static boolean isChatV3() {
		return V3.equals(chat);
	}

	/**
	 * Which product a book belongs to: {@link #CHAPBOOK_CONFIG_KEY} when {@code bookType} is
	 * {@code CHAPBOOK} (compared case-insensitively - enums read back upper-case from Java and
	 * lower-case from list projections), otherwise {@link #PICTUREBOOK_CONFIG_KEY}. A null book, a
	 * missing field or {@code UNKNOWN} all resolve to PictureBook, the default product.
	 */
	public static String keyFor(BaseRecord book) {
		if(book != null && book.hasField(OlioFieldNames.FIELD_PB_BOOK_TYPE)) {
			Object raw = book.get(OlioFieldNames.FIELD_PB_BOOK_TYPE);
			if(raw != null && PbBookTypeEnumType.CHAPBOOK.toString().equalsIgnoreCase(raw.toString())) {
				return CHAPBOOK_CONFIG_KEY;
			}
		}
		return PICTUREBOOK_CONFIG_KEY;
	}

	public static String valueFor(BaseRecord book) {
		return (CHAPBOOK_CONFIG_KEY.equals(keyFor(book)) ? chapBook : pictureBook);
	}

	public static boolean isV3(BaseRecord book) {
		return V3.equals(valueFor(book));
	}

	/**
	 * Refuse with a 409 unless the book's product is on {@link #V3}. The message names every surface
	 * the switch can be set from, because on Docker {@code web.xml} is regenerated at boot and an edit
	 * there is lost on the restart the message prescribes.
	 */
	public static void requireV3(BaseRecord book) {
		if(isV3(book)) {
			return;
		}
		String key = keyFor(book);
		String env = (CHAPBOOK_CONFIG_KEY.equals(key) ? "CHAPBOOK_RENDER_PATH" : "PICTUREBOOK_RENDER_PATH");
		throw new PictureBookException(409, REFUSAL_PREFIX + " for this book's product (" + key
			+ "). To enable the V3 graph render path set " + key + "=" + V3
			+ " and restart: on Docker via the " + env + " environment variable (docker-compose / .env),"
			+ " on a non-Docker Tomcat via the " + key + " init-param in WEB-INF/web.xml,"
			+ " and for Console7 via " + key + " in resource.properties. Applied at startup only.");
	}

	/**
	 * V3 §37.3 Disable (Phase 2): refuse with a 409 when the resolved composite mode is the retired
	 * Kontext pipeline (explicit {@code compositeMode=kontext} or the legacy {@code useKontext} boolean on
	 * a config with no compositeMode), with the same "replaced by V3" response the PB1 entry points
	 * return at §28.1 Stage 2. Called with {@link SceneCompositeUtil#resolveMode}'s result before any
	 * LLM or SD work; the Kontext code itself stays in the build until Phase 10 Remove. The message
	 * starts with {@link SceneCompositeUtil#KONTEXT_RETIRED_MESSAGE}; {@code scopeLabel} (e.g.
	 * {@code "scene <objectId>"}) is appended in parentheses when given.
	 */
	public static void requireEnabledCompositeMode(String mode, String scopeLabel) {
		if(!SceneCompositeUtil.isRetiredMode(mode)) {
			return;
		}
		throw new PictureBookException(409, SceneCompositeUtil.KONTEXT_RETIRED_MESSAGE
			+ (scopeLabel != null && !scopeLabel.isBlank() ? " (" + scopeLabel + ")" : ""));
	}
}
