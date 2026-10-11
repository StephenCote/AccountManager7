package org.cote.accountmanager.olio.picturebook;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.type.PbBookTypeEnumType;

/**
 * The boot-pinned V3 render-path switch (design §28.1 Stage 1, P1-8): one value per product,
 * {@code picturebook.renderPath} and {@code chapbook.renderPath}, each {@code v1} or {@code v3},
 * default {@code v1}.
 * <p>
 * Read once at start-up by the transport's startup listener (Service7 {@code web.xml} init-param,
 * which on Docker is regenerated from {@code docker/web.xml.template} on every boot from the
 * {@code PICTUREBOOK_RENDER_PATH} / {@code CHAPBOOK_RENDER_PATH} environment variables) and by
 * Console7 ({@code resource.properties}). It is deliberately process-global, not per-organization,
 * and not DB-backed: rollback is "set {@code v1} and restart".
 * <p>
 * The switch is checked <b>only at facade entry</b> ({@link PbServiceFacade}), never inside
 * {@link PbRenderJobUtil} or {@link PbNodeExecutor}: a value changed mid-run (which only a test can
 * do) cannot abort a job that was admitted. Under {@code v1} the Phase 1 routes refuse with a 409
 * whose message starts with {@link #REFUSAL_PREFIX}; the P0/P1-2 read views ({@code workflowView},
 * {@code nodeView}, {@code artifactView}, {@code listStale}, {@code affectedByRecord}) stay readable
 * under either value, and the boot-time {@link PbRecoveryUtil} sweep runs regardless (it only touches
 * V3 run/node rows, which exist only if V3 ever ran).
 */
public final class PbRenderPath {

	private static final Logger logger = LogManager.getLogger(PbRenderPath.class);

	/** Service7 init-param / Console7 property for the PictureBook product. */
	public static final String PICTUREBOOK_CONFIG_KEY = "picturebook.renderPath";

	/** Service7 init-param / Console7 property for the ChapBook product. */
	public static final String CHAPBOOK_CONFIG_KEY = "chapbook.renderPath";

	public static final String V1 = "v1";
	public static final String V3 = "v3";

	/** Every refusal message starts with this, so clients and tests can key on it. */
	public static final String REFUSAL_PREFIX = "render path is v1";

	private static volatile String pictureBook = V1;
	private static volatile String chapBook = V1;

	private PbRenderPath() {
	}

	/**
	 * Pin both values. Null/blank means "absent" and pins {@link #V1} at INFO; an unrecognised value
	 * pins {@link #V1} at WARN naming the key and the value; a change is logged at INFO. Never throws.
	 */
	public static void configure(String pictureBookValue, String chapBookValue) {
		pictureBook = resolve(PICTUREBOOK_CONFIG_KEY, pictureBookValue, pictureBook);
		chapBook = resolve(CHAPBOOK_CONFIG_KEY, chapBookValue, chapBook);
	}

	private static String resolve(String key, String raw, String current) {
		String use;
		if(raw == null || raw.trim().isEmpty()) {
			use = V1;
			logger.info(key + " absent; render path pinned to " + V1);
		}
		else {
			String norm = raw.trim().toLowerCase();
			if(V1.equals(norm) || V3.equals(norm)) {
				use = norm;
			}
			else {
				use = V1;
				logger.warn(key + "=" + raw.trim() + " is not " + V1 + " or " + V3 + "; render path pinned to " + V1);
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
}
