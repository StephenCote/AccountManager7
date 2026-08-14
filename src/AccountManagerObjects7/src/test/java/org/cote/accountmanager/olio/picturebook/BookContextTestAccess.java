package org.cote.accountmanager.olio.picturebook;

import org.cote.accountmanager.olio.OlioContextConfiguration;
import org.cote.accountmanager.olio.OlioException;
import org.cote.accountmanager.record.BaseRecord;

/**
 * TEST-ONLY accessor for the package-private members of {@code PbOlioContextUtil}:
 * {@code assembleBookContext(BaseRecord)} and
 * {@code newBookConfiguration(BaseRecord, String, String)}.
 * <p>
 * Both are package-private <b>by design</b>. {@code assembleBookContext} must not become a public
 * slug-addressed read entry point (that re-opens the "read up" authorization finding documented on
 * {@code PbOlioContextUtil}); {@code newBookConfiguration} creates the two per-book roles with no
 * authorization check of its own, so a public form would be a second create entry beside the
 * documented "only create path". Java package-private visibility is per-package, not
 * per-package-tree, so neither is reachable from {@code org.cote.accountmanager.olio} (where
 * {@code TestBookWorld} lives) - only from this package.
 * <p>
 * This class exists in <b>test</b> sources only. It widens nothing in production: both production
 * methods keep their package-private modifier, and no production code can see this shim.
 */
public class BookContextTestAccess {

	private BookContextTestAccess() {
		/// static utility
	}

	public static BookContext assemble(BaseRecord world) {
		return PbOlioContextUtil.assembleBookContext(world);
	}

	public static OlioContextConfiguration newBookConfiguration(BaseRecord user, String dataPath, String bookSlug) throws OlioException {
		return PbOlioContextUtil.newBookConfiguration(user, dataPath, bookSlug);
	}
}
