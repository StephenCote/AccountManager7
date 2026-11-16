package org.cote.accountmanager.olio.picturebook;

import java.util.LinkedHashMap;
import java.util.Map;

import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;

/// Test fixture helper (2026-11-16): a PictureBook scene render now requires the book's effective style to carry a
/// description (PbServiceFacade.STYLE_REQUIRED_MESSAGE, 409 otherwise). Fixtures that render scenes give their book
/// a described style through the production style PUT path, PbServiceFacade.putStyle, as the book's owner - which
/// creates the book's own BOOK-scope style on first use, or patches only {@code description} on an existing one, so
/// a fixture's other style fields (task models, preset) are left as the fixture set them.
public final class PbRenderStyleFixture {

	/// The description every rendering fixture gives its book. Deliberately short and neutral.
	public static final String DESCRIPTION = "Test fixture style: clean ink linework with flat, muted colour.";

	private PbRenderStyleFixture() {
	}

	/// Give {@code bookObjectId} a style with {@link #DESCRIPTION}; returns the style DTO putStyle answers.
	public static Map<String, Object> describe(BaseRecord user, String bookObjectId) {
		return describe(user, bookObjectId, DESCRIPTION);
	}

	public static Map<String, Object> describe(BaseRecord user, String bookObjectId, String description) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put(FieldNames.FIELD_DESCRIPTION, description);
		return PbServiceFacade.putStyle(user, bookObjectId, body);
	}
}
