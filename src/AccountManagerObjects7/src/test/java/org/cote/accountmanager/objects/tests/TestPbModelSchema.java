package org.cote.accountmanager.objects.tests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.db.DBUtil;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.olio.sd.SDAPIEnumType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.schema.type.PbBookStatusEnumType;
import org.cote.accountmanager.util.RecordUtil;
import org.junit.Test;

/// Phase 2b exit criteria for the eight olio.pb.* models: each is registered and loadable, each has a
/// table (DBUtil.getTableName), and every constraint and hint it declares - or inherits - is actually
/// present in pg_indexes with the right columns and the right uniqueness.
///
/// Index presence is read from the database itself (pg_indexes.indexdef), not from the DDL the
/// generator would emit: a statement that was generated and then rejected by PostgreSQL logs an error
/// and continues (IOSystem.java:169-178), so generated-DDL assertions would pass over exactly the
/// failure this test exists to catch.
///
public class TestPbModelSchema extends BaseTest {

	private static final List<String> PB_MODELS = Arrays.asList(
		OlioModelNames.MODEL_PB_BOOK,
		OlioModelNames.MODEL_PB_SERIES,
		OlioModelNames.MODEL_PB_SCENE,
		OlioModelNames.MODEL_PB_WORKFLOW,
		OlioModelNames.MODEL_PB_NODE,
		OlioModelNames.MODEL_PB_BINDING,
		OlioModelNames.MODEL_PB_ARTIFACT,
		OlioModelNames.MODEL_PB_RUN
	);

	/// CREATE [UNIQUE] INDEX <name> ON <schema>.<table> USING btree (<cols>)
	private static final Pattern pgIndexDef = Pattern.compile(
		"^CREATE (UNIQUE )?INDEX (\\S+) ON \\S+ USING \\w+ \\(([^)]+)\\)$"
	);

	@Test
	public void TestPbModelsAreRegisteredAndLoadable() {
		for(String m : PB_MODELS) {
			assertTrue(m + " is not registered in ModelNames.MODELS", ModelNames.MODELS.contains(m));
			ModelSchema ms = RecordFactory.getSchema(m);
			assertNotNull("Failed to load the " + m + " schema", ms);
			assertTrue("Expected " + m + " to be an identity model, or IOSystem.open will skip it",
				RecordUtil.isIdentityModel(ms));
			assertFalse("Expected " + m + " to be persistable", ioContext.getDbUtil().isConstrained(ms));

			/// Copied from olio.narrative: likeInherits data.directory for the group-only access
			/// shortcut, groupExt + baseLight for the fields, and its own plain 'name' - never
			/// common.nameId, whose \S rule makes a name-omitting PATCH fail.
			assertTrue(m + " must likeInherit data.directory", ms.getLikeInherits().contains("data.directory"));
			assertTrue(m + " must inherit common.groupExt", ms.getInherits().contains("common.groupExt"));
			assertTrue(m + " must inherit common.baseLight", ms.getInherits().contains("common.baseLight"));
			assertFalse(m + " must not inherit common.nameId", ms.getInherits().contains("common.nameId"));

			FieldSchema name = ms.getFieldSchema("name");
			assertNotNull(m + " must declare its own name field", name);
			assertEquals(m + ".name must be a string", "string", name.getType());
			assertTrue(m + ".name needs a maxLength to be indexable", name.getMaxLength() > 0);

			/// Ratification 8: urn is included on all eight for portability
			assertTrue(m + " must carry urn (common.baseLight omits it)", ms.getInherits().contains("common.urn"));
			assertNotNull(m + " must have a urn field", ms.getFieldSchema("urn"));

			/// Group-scoped, never groupless - a record with a groupId gets the group-only access
			/// shortcut, a groupless one forces field/role checks
			FieldSchema groupId = ms.getFieldSchema("groupId");
			assertNotNull(m + " must be group-scoped", groupId);
			assertNotNull(m + " should declare a group name hint", ms.getGroup());

			/// name and urn have to be in the query projection explicitly.  common.nameId is where
			/// "query": ["name"] normally comes from, and these models deliberately do not inherit it,
			/// so without this a default read returns a record whose name is null - which the
			/// documented PATCH rule (carry name, taken from what you already know) would then feed
			/// straight back into a patch.
			assertTrue(m + " must project name, or every default read returns a null name",
				ms.getQuery().contains("name"));
			assertTrue(m + " must project urn - it is the portable reference", ms.getQuery().contains("urn"));
		}
	}

	/// Rule 3 of the conventions: no schema default on any config-ish field.  Asserted across every
	/// field the eight models declare themselves (inherited fields belong to their own models).
	///
	@Test
	public void TestPbModelsDeclareNoFieldDefaults() {
		List<String> withDefaults = new ArrayList<>();
		for(String m : PB_MODELS) {
			ModelSchema ms = RecordFactory.getSchema(m);
			for(FieldSchema f : ms.getFields()) {
				if(f.isInherited()) {
					continue;
				}
				if(f.getDefaultValue() != null) {
					withDefaults.add(m + "." + f.getName() + " = " + f.getDefaultValue());
				}
			}
		}
		assertTrue("No PB2 field may declare a schema default: " + withDefaults, withDefaults.isEmpty());
	}

	/// The renames, and the shape of the artifact version chain.  These are the two places where
	/// following the plan body instead of the ratified corrections would produce a wrong model.
	///
	@Test
	public void TestRatifiedFieldNamesAndArtifactConstraint() {
		ModelSchema scene = RecordFactory.getSchema(OlioModelNames.MODEL_PB_SCENE);
		assertNotNull("scene must use sceneIndex", scene.getFieldSchema(OlioFieldNames.FIELD_PB_SCENE_INDEX));
		assertNull("scene must NOT declare a field named 'index'", scene.getFieldSchema("index"));

		ModelSchema artifact = RecordFactory.getSchema(OlioModelNames.MODEL_PB_ARTIFACT);
		assertNotNull("artifact must use selected", artifact.getFieldSchema(OlioFieldNames.FIELD_PB_SELECTED));
		assertNull("artifact must NOT declare a field named 'current'", artifact.getFieldSchema("current"));

		/// A boolean is never NULL, so a unique index over 'selected' would forbid a second superseded
		/// row - the normal case.  The version chain is constrained instead.
		List<String> constraints = RecordUtil.getConstraints(artifact);
		assertFalse("'selected' must not be part of any unique constraint: " + constraints,
			constraints.stream().anyMatch(c -> Arrays.asList(c.replaceAll(" ", "").split(","))
				.contains(OlioFieldNames.FIELD_PB_SELECTED)));
		assertTrue("Expected the (producedByNode, role, revision, organizationId) constraint: " + constraints,
			constraints.contains("producedByNode, role, revision, organizationId"));

		/// Ratification 7: the book's unique slug is the create-race serialization point
		List<String> bookConstraints = RecordUtil.getConstraints(RecordFactory.getSchema(OlioModelNames.MODEL_PB_BOOK));
		assertTrue("Expected the (slug, organizationId) constraint: " + bookConstraints,
			bookConstraints.contains("slug, organizationId"));

		/// One workflow per book
		List<String> wfConstraints = RecordUtil.getConstraints(RecordFactory.getSchema(OlioModelNames.MODEL_PB_WORKFLOW));
		assertTrue("Expected the (book, organizationId) constraint: " + wfConstraints,
			wfConstraints.contains("book, organizationId"));

		/// Ratification 12: the enum value must exist before olio.pb.artifact.backend can validate
		/// against it.  No Comfy behaviour is implied.
		assertEquals("SDAPIEnumType.COMFY must exist", "COMFY", SDAPIEnumType.COMFY.name());
		assertEquals("artifact.backend must be the SDAPIEnumType enum",
			"org.cote.accountmanager.olio.sd.SDAPIEnumType",
			artifact.getFieldSchema(OlioFieldNames.FIELD_PB_BACKEND).getBaseClass());
	}

	/// 'index: true' creates no database index (DBUtil useFieldIndexGuidance is false) and does add a
	/// per-query foreign-record read-policy scan (PolicyUtil reads isIndex()).  So no PB2 field may
	/// declare it - the reverse edges are hints.
	///
	@Test
	public void TestNoFieldLevelIndexFlags() {
		List<String> flagged = new ArrayList<>();
		for(String m : PB_MODELS) {
			ModelSchema ms = RecordFactory.getSchema(m);
			for(FieldSchema f : ms.getFields()) {
				if(!f.isInherited() && f.isIndex()) {
					flagged.add(m + "." + f.getName());
				}
			}
		}
		assertTrue("index:true creates no index and adds a PBAC scan; use hints: " + flagged, flagged.isEmpty());
	}

	/// The reverse edges named in the ratified corrections must each be covered by a hint, because they
	/// are the path downstream propagation walks.
	///
	@Test
	public void TestReverseEdgesAreHinted() {
		assertHintCovers(OlioModelNames.MODEL_PB_BINDING, OlioFieldNames.FIELD_PB_NODE);
		assertHintCovers(OlioModelNames.MODEL_PB_BINDING, OlioFieldNames.FIELD_PB_SOURCE_NODE);
		assertHintCovers(OlioModelNames.MODEL_PB_BINDING, OlioFieldNames.FIELD_PB_SOURCE_ARTIFACT);
		assertHintCovers(OlioModelNames.MODEL_PB_BINDING, OlioFieldNames.FIELD_PB_ROLE);
		assertHintCovers(OlioModelNames.MODEL_PB_ARTIFACT, OlioFieldNames.FIELD_PB_PRODUCED_BY_NODE);
		assertHintCovers(OlioModelNames.MODEL_PB_ARTIFACT, OlioFieldNames.FIELD_PB_SELECTED);
		assertHintCovers(OlioModelNames.MODEL_PB_NODE, OlioFieldNames.FIELD_PB_WORKFLOW);
		assertHintCovers(OlioModelNames.MODEL_PB_NODE, OlioFieldNames.FIELD_PB_HANDLE);
		/// The denormalized status exists so 'show me the stale nodes' is one indexed query
		assertHintCovers(OlioModelNames.MODEL_PB_NODE, OlioFieldNames.FIELD_PB_NODE_STATUS);
		/// Scene order is a column, not array position in a blob
		assertHintCovers(OlioModelNames.MODEL_PB_SCENE, OlioFieldNames.FIELD_PB_SCENE_INDEX);
	}

	private void assertHintCovers(String modelName, String fieldName) {
		List<String> hints = RecordUtil.getHints(RecordFactory.getSchema(modelName));
		boolean covered = hints.stream()
			.anyMatch(h -> Arrays.asList(h.replaceAll(" ", "").split(",")).contains(fieldName));
		assertTrue(modelName + "." + fieldName + " needs a hints entry, got " + hints, covered);
	}

	/// Every declared constraint and hint column must actually be indexable, or generateIndex returns
	/// null and the index is silently never created.  An unbounded text or varchar column is the usual
	/// way this happens: a string field with no maxLength.
	///
	@Test
	public void TestEveryDeclaredIndexIsGeneratable() {
		DBUtil dbUtil = ioContext.getDbUtil();
		for(String m : PB_MODELS) {
			ModelSchema ms = RecordFactory.getSchema(m);
			int declared = RecordUtil.getConstraints(ms).size() + RecordUtil.getHints(ms).size();
			List<String> stmts = dbUtil.generatePatchIndices(ms).stream()
				.filter(s -> dbUtil.getTableName(m).equals(tableOf(s)))
				.collect(Collectors.toList());
			assertEquals(m + " dropped an index: " + declared + " declared, generated:\n"
				+ stmts.stream().collect(Collectors.joining("\n")), declared, stmts.size());
		}
	}

	/// The exit criteria: the eight tables exist, and every constraint and hint is present in
	/// pg_indexes with the declared columns and the declared uniqueness.
	///
	@Test
	public void TestPbTablesAndIndexesExistInTheDatabase() throws SQLException {
		DBUtil dbUtil = ioContext.getDbUtil();
		Map<String, List<String>> report = new LinkedHashMap<>();
		List<String> missing = new ArrayList<>();

		for(String m : PB_MODELS) {
			ModelSchema ms = RecordFactory.getSchema(m);
			String table = dbUtil.getTableName(m);
			assertTrue("Table " + table + " for " + m + " does not exist", dbUtil.haveTable(m));

			Map<List<String>, Boolean> live = readLiveIndexes(table);
			List<String> lines = new ArrayList<>();
			live.forEach((cols, unique) -> lines.add((unique ? "UNIQUE " : "") + cols));
			report.put(table, lines);

			for(String c : RecordUtil.getConstraints(ms)) {
				List<String> cols = columnKey(c);
				Boolean unique = live.get(cols);
				if(unique == null) {
					missing.add(table + " missing UNIQUE index over " + cols);
				}
				else if(!unique.booleanValue()) {
					missing.add(table + " index over " + cols + " exists but is NOT unique");
				}
			}
			for(String h : RecordUtil.getHints(ms)) {
				List<String> cols = columnKey(h);
				if(!live.containsKey(cols)) {
					missing.add(table + " missing index over " + cols);
				}
			}
		}

		StringBuilder sb = new StringBuilder("pg_indexes for the olio.pb.* tables:");
		report.forEach((t, lines) -> sb.append("\n  " + t + " -> " + lines));
		logger.info(sb.toString());

		assertTrue("Declared constraints/hints absent from pg_indexes:\n  "
			+ missing.stream().collect(Collectors.joining("\n  ")), missing.isEmpty());
	}

	/// olio.pb.scene owns a foreign list (characters), so it declares dedicatedParticipation and gets
	/// its own participation table.  Without the table the list has nowhere to be written.
	///
	@Test
	public void TestSceneParticipationTableExists() {
		DBUtil dbUtil = ioContext.getDbUtil();
		ModelSchema scene = RecordFactory.getSchema(OlioModelNames.MODEL_PB_SCENE);
		assertTrue("olio.pb.scene owns a foreign list and must declare dedicatedParticipation",
			scene.isDedicatedParticipation());
		assertTrue("Expected the dedicated participation table for olio.pb.scene",
			dbUtil.haveTable(scene, ModelNames.MODEL_PARTICIPATION));

		/// Models that own no foreign list must not carry the flag - it creates a table nothing uses
		for(String m : PB_MODELS) {
			if(m.equals(OlioModelNames.MODEL_PB_SCENE)) {
				continue;
			}
			ModelSchema ms = RecordFactory.getSchema(m);
			boolean ownsList = ms.getFields().stream()
				.anyMatch(f -> !f.isInherited() && "list".equals(f.getType()) && f.isForeign());
			assertEquals(m + " dedicatedParticipation must match whether it owns a foreign list",
				ownsList, ms.isDedicatedParticipation());
		}
	}

	/// The workflow <-> run cycle is captured, not reshaped (ratification 1).  Recorded here as a
	/// characterization so phase 2c's planMost(true) termination test has a stated starting point:
	/// both sides of the mutual reference exist and are foreign model fields on the same column type.
	///
	@Test
	public void TestWorkflowRunCycleIsCaptured() {
		FieldSchema lastRun = RecordFactory.getSchema(OlioModelNames.MODEL_PB_WORKFLOW)
			.getFieldSchema(OlioFieldNames.FIELD_PB_LAST_RUN);
		FieldSchema wf = RecordFactory.getSchema(OlioModelNames.MODEL_PB_RUN)
			.getFieldSchema(OlioFieldNames.FIELD_PB_WORKFLOW);
		assertEquals("workflow.lastRun must reference olio.pb.run", OlioModelNames.MODEL_PB_RUN, lastRun.getBaseModel());
		assertTrue("workflow.lastRun must be foreign", lastRun.isForeign());
		assertEquals("run.workflow must reference olio.pb.workflow", OlioModelNames.MODEL_PB_WORKFLOW, wf.getBaseModel());
		assertTrue("run.workflow must be foreign", wf.isForeign());
		/// Breaking the cycle later by declaring lastRun as a long stays DDL-neutral only while both
		/// shapes emit the same column type
		assertEquals("Expected a bigint column for the foreign reference", "bigint",
			ioContext.getDbUtil().getDataType(lastRun, lastRun.getFieldType()));
	}

	/// olio.sd.config is NOT a database-persisted model (ioConstraints ['unknown']), so the three
	/// config fields cannot be foreign references - a foreign field would emit a bigint column that
	/// nothing can ever populate.  They are non-foreign model fields, serialized into a text column.
	/// This test states the dependency: if olio.sd.config is ever promoted to a persisted model, it
	/// fails and the decision gets revisited deliberately.
	///
	@Test
	public void TestSdConfigFieldsAreSerializedNotForeign() {
		DBUtil dbUtil = ioContext.getDbUtil();
		ModelSchema sdConfig = RecordFactory.getSchema(OlioModelNames.MODEL_SD_CONFIG);
		assertTrue("olio.sd.config is expected to be constrained from the database; if that changed, "
			+ "the PB2 config fields should be reconsidered as foreign references",
			dbUtil.isConstrained(sdConfig));

		assertSerializedConfig(OlioModelNames.MODEL_PB_BOOK, OlioFieldNames.FIELD_PB_SD_CONFIG);
		assertSerializedConfig(OlioModelNames.MODEL_PB_BOOK, OlioFieldNames.FIELD_PB_COMPOSITE_SD_CONFIG);
		assertSerializedConfig(OlioModelNames.MODEL_PB_ARTIFACT, OlioFieldNames.FIELD_PB_SD_CONFIG_SNAPSHOT);
	}

	/// olio.sd.config declared its own 'groupPath' meaning "target group path for generated image
	/// storage" - an OUTPUT destination - which collides with the framework groupPath that
	/// common.groupExt supplies (meaning "where this record itself lives").  Inheritance is depth-first
	/// LAST-WINS, so the moment the model is made persistable (plan S6c step S2) the collision would
	/// silently shadow one meaning with the other and every caller would still compile.  Renamed to
	/// imagePath, pairing with its sibling imageName.
	///
	/// This test is the guard: it fails if 'groupPath' is ever re-declared as a field on olio.sd.config,
	/// which is the only way the collision can come back.
	///
	@Test
	public void TestSdConfigHasNoCollidingGroupPathField() {
		ModelSchema ms = RecordFactory.getSchema(OlioModelNames.MODEL_SD_CONFIG);
		assertNotNull("Expected the olio.sd.config schema", ms);

		/// Declared directly, not inherited - the model inherits nothing today
		assertTrue("olio.sd.config is expected to inherit nothing; if that changed, re-check the "
			+ "groupPath collision this test guards", ms.getInherits().isEmpty());
		assertNull("olio.sd.config must NOT declare a 'groupPath' field - it collides with the virtual "
			+ "groupPath from common.groupExt under depth-first last-wins inheritance",
			ms.getFieldSchema("groupPath"));
		assertNotNull("olio.sd.config must declare 'imagePath' (the renamed output destination)",
			ms.getFieldSchema("imagePath"));
		assertNotNull("imagePath pairs with imageName", ms.getFieldSchema("imageName"));
	}

	private void assertSerializedConfig(String modelName, String fieldName) {
		FieldSchema fs = RecordFactory.getSchema(modelName).getFieldSchema(fieldName);
		assertNotNull(modelName + "." + fieldName + " is missing", fs);
		assertEquals(modelName + "." + fieldName + " must target olio.sd.config",
			OlioModelNames.MODEL_SD_CONFIG, fs.getBaseModel());
		assertFalse(modelName + "." + fieldName + " must not be foreign - olio.sd.config has no table",
			fs.isForeign());
		assertEquals(modelName + "." + fieldName + " must emit a text column", "text",
			ioContext.getDbUtil().getDataType(fs, fs.getFieldType()));
	}

	/// Read the live indexes for one table out of pg_indexes, keyed by their column list.
	///
	private Map<List<String>, Boolean> readLiveIndexes(String table) throws SQLException {
		Map<List<String>, Boolean> out = new LinkedHashMap<>();
		Set<String> seen = new HashSet<>();
		String sql = "SELECT indexname, indexdef FROM pg_indexes WHERE lower(tablename) = lower(?)";
		try (
			Connection con = DriverManager.getConnection(
				testProperties.getProperty("test.db.url"),
				testProperties.getProperty("test.db.user"),
				testProperties.getProperty("test.db.password"));
			PreparedStatement st = con.prepareStatement(sql);
		) {
			st.setString(1, table);
			try (ResultSet rs = st.executeQuery()) {
				while(rs.next()) {
					String def = rs.getString(2);
					seen.add(rs.getString(1));
					Matcher m = pgIndexDef.matcher(def.trim());
					if(!m.matches()) {
						/// Primary key / other constraint-backed indexes take a different form; the
						/// declared constraints and hints all come through CREATE [UNIQUE] INDEX
						logger.debug("Unparsed index definition on " + table + ": " + def);
						continue;
					}
					out.put(columnKey(m.group(3)), m.group(1) != null);
				}
			}
		}
		assertFalse("No indexes at all on " + table, seen.isEmpty());
		return out;
	}

	/// A table that exists is not the same claim as a schema that works.  Creates one olio.pb.book,
	/// reads it back, and asserts the urn was composed - urn is a not-null identity column filled by
	/// UrnProvider from 'name' and the virtual groupPath, so a create would fail outright if that
	/// composition did not work for these models.  Also exercises the serialized (non-foreign) config
	/// column, which is the one deviation from the plan's field shapes.
	///
	@Test
	public void TestBookRoundTripsAndUrnIsComposed() {
		BaseRecord user = getCreateUser("pbSchemaUser");
		assertNotNull("Expected a test user", user);
		long orgId = user.get(FieldNames.FIELD_ORGANIZATION_ID);
		String path = "~/PbSchemaCheck";
		assertNotNull("Expected the scratch group", ioContext.getPathUtil()
			.makePath(user, ModelNames.MODEL_GROUP, path, GroupEnumType.DATA.toString(), orgId));

		String slug = "pbschema-" + UUID.randomUUID().toString().substring(0, 8);
		BaseRecord book = newBook(user, slug, path, orgId);
		BaseRecord created = ioContext.getAccessPoint().create(user, book);
		assertNotNull("Failed to create an olio.pb.book - the table exists but the model does not work", created);

		BaseRecord read = ioContext.getAccessPoint().findByObjectId(user, OlioModelNames.MODEL_PB_BOOK,
			created.get(FieldNames.FIELD_OBJECT_ID));
		assertNotNull("Failed to read back the book", read);
		assertEquals("Expected the name to persist", "Book " + slug, read.get(FieldNames.FIELD_NAME));
		/// urn is in the models' query projection deliberately, so a default read carries the portable
		/// reference rather than requiring every caller to plan for it
		String urn = read.get(FieldNames.FIELD_URN);
		assertTrue("Expected UrnProvider to compose a urn, got '" + urn + "'", urn != null && urn.trim().length() > 0);
		/// UrnProvider composes schema + org path + groupPath + NAME, then normalizes: lower-cased with
		/// every non-alphanumeric run replaced by a '.'.  This is why ratification 8 requires the
		/// machine-generated names to be derived unique - the provider reads name, not handle, and
		/// common.urn carries no uniqueness constraint to catch a collision.
		String normalizedName = ("Book " + slug).toLowerCase().replaceAll("[^a-z0-9]+", ".");
		assertTrue("Expected the urn to end with the normalized name '" + normalizedName + "', got " + urn,
			urn.endsWith(normalizedName));
		logger.info("olio.pb.book round trip: urn=" + urn + " slug=" + slug);

		/// The serialized config column: a non-foreign model field, so it comes back as a record - but
		/// only when requested.  It is not a query field, and non-query fields are opt-in.
		Query q = QueryUtil.createQuery(OlioModelNames.MODEL_PB_BOOK, FieldNames.FIELD_OBJECT_ID,
			created.get(FieldNames.FIELD_OBJECT_ID));
		q.field(FieldNames.FIELD_ORGANIZATION_ID, orgId);
		q.setRequest(new String[] { FieldNames.FIELD_ID, FieldNames.FIELD_OBJECT_ID, FieldNames.FIELD_NAME,
			OlioFieldNames.FIELD_PB_SLUG, OlioFieldNames.FIELD_PB_SD_CONFIG, OlioFieldNames.FIELD_PB_BOOK_STATUS });
		BaseRecord projected = ioContext.getAccessPoint().find(user, q);
		assertNotNull("Failed to read the book with an explicit projection", projected);
		assertEquals("Expected the slug to persist", slug, projected.get(OlioFieldNames.FIELD_PB_SLUG));
		/// Enums serialize lower-case on the wire and read back UPPERCASE in Java
		assertEquals("Expected the enum to persist", PbBookStatusEnumType.DRAFT,
			projected.getEnum(OlioFieldNames.FIELD_PB_BOOK_STATUS));
		BaseRecord cfg = projected.get(OlioFieldNames.FIELD_PB_SD_CONFIG);
		assertNotNull("Expected the serialized sdConfig to round trip", cfg);
		assertEquals("Expected the sdConfig to deserialize as its own model",
			OlioModelNames.MODEL_SD_CONFIG, cfg.getSchema());
		assertEquals("Expected the sdConfig contents to survive", "pbSchemaStyle", cfg.get("style"));
	}

	/// The unique constraints are the model's invariants, so the test has to show they ENFORCE, not
	/// just that an index of that name exists.  A second book with the same slug in the same
	/// organization is the B1 create-race serialization point.
	///
	@Test
	public void TestDuplicateSlugIsRejected() {
		BaseRecord user = getCreateUser("pbSchemaUser");
		long orgId = user.get(FieldNames.FIELD_ORGANIZATION_ID);
		String path = "~/PbSchemaCheck";
		assertNotNull("Expected the scratch group", ioContext.getPathUtil()
			.makePath(user, ModelNames.MODEL_GROUP, path, GroupEnumType.DATA.toString(), orgId));

		String slug = "pbdupe-" + UUID.randomUUID().toString().substring(0, 8);
		assertNotNull("Failed to create the first book",
			ioContext.getAccessPoint().create(user, newBook(user, slug, path, orgId)));

		/// Same slug, different name, so only the (slug, organizationId) constraint can reject it
		BaseRecord dupe = newBook(user, slug, path, orgId);
		dupe.setValue(FieldNames.FIELD_NAME, "Second " + slug);
		BaseRecord second = ioContext.getAccessPoint().create(user, dupe);
		assertNull("A second book with the same slug must fail on the unique index - this is the create "
			+ "race serialization point, and without it two racers each get a book and one world orphans", second);
	}

	/// Characterizes two traps that phase 2c's create paths have to handle, found while proving the
	/// round trip.  Both are consequences of the ratified plain-'name' convention, and neither is
	/// visible at the call site:
	///
	/// 1. RecordUtil.applyNameGroupOwnership sets 'name' ONLY when the record inherits common.name
	///    (RecordUtil.java:762-764).  These models declare their own plain name to avoid the \S rule
	///    that makes a name-omitting PATCH fail, so the helper silently leaves the name null.
	///    olio.narrative has the same shape and the same trap.
	/// 2. A null name DEFEATS the unique (name, groupId, organizationId) constraint, because
	///    PostgreSQL treats NULLs as distinct.  That constraint is the guard ratification 8 asked for
	///    against colliding urns, and it does not fire for nulls.  The schema cannot close this: the
	///    only schema-level guard is the \S rule the convention deliberately avoids, and
	///    FieldSchema.isRequired() is read only by RecordTranslator, never by the writer.
	///    ==> every PB2 create path must set the derived name EXPLICITLY.
	///
	@Test
	public void TestApplyNameGroupOwnershipDoesNotSetNameOnPbModels() {
		BaseRecord user = getCreateUser("pbSchemaUser");
		long orgId = user.get(FieldNames.FIELD_ORGANIZATION_ID);
		ModelSchema ms = RecordFactory.getSchema(OlioModelNames.MODEL_PB_BOOK);
		assertFalse("This trap exists precisely because the model does not inherit common.name",
			ms.getInherits().contains(ModelNames.MODEL_NAME));

		BaseRecord book = null;
		try {
			book = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK);
			ioContext.getRecordUtil().applyNameGroupOwnership(user, book, "Named By The Helper", "~/PbSchemaCheck", orgId);
		}
		catch(Exception e) {
			fail("Failed to assemble a book: " + e.getMessage());
		}
		assertNull("applyNameGroupOwnership must be expected NOT to set the name on a model that "
			+ "declares its own plain name - PB2 create paths have to set it themselves",
			book.get(FieldNames.FIELD_NAME));
		/// The helper does still do its other two jobs
		assertTrue("Expected the group to be applied", ((long)book.get(FieldNames.FIELD_GROUP_ID)) > 0L);
		assertTrue("Expected ownership to be applied", ((long)book.get(FieldNames.FIELD_OWNER_ID)) > 0L);
	}

	private BaseRecord newBook(BaseRecord user, String slug, String path, long orgId) {
		BaseRecord book = null;
		try {
			book = RecordFactory.newInstance(OlioModelNames.MODEL_PB_BOOK);
			ioContext.getRecordUtil().applyNameGroupOwnership(user, book, "Book " + slug, path, orgId);
			/// Set explicitly: applyNameGroupOwnership does not set the name on these models.  See
			/// TestApplyNameGroupOwnershipDoesNotSetNameOnPbModels.
			book.set(FieldNames.FIELD_NAME, "Book " + slug);
			book.set(OlioFieldNames.FIELD_PB_SLUG, slug);
			book.set(OlioFieldNames.FIELD_PB_BOOK_STATUS, PbBookStatusEnumType.DRAFT.toString());
			BaseRecord cfg = RecordFactory.newInstance(OlioModelNames.MODEL_SD_CONFIG);
			cfg.set("style", "pbSchemaStyle");
			book.set(OlioFieldNames.FIELD_PB_SD_CONFIG, cfg);
		}
		catch(Exception e) {
			logger.error(e);
			fail("Failed to assemble an olio.pb.book: " + e.getMessage());
		}
		return book;
	}

	/// Normalize a comma-separated column list into a comparable key.  Lower-cased on purpose:
	/// PostgreSQL folds unquoted identifiers, so pg_indexes reports 'organizationid' where the model
	/// declares 'organizationId'.
	///
	private List<String> columnKey(String cols) {
		return Arrays.asList(cols.replaceAll("[\" ]", "").toLowerCase().split(","));
	}

	private String tableOf(String stmt) {
		Matcher m = Pattern.compile("^CREATE (UNIQUE )?INDEX IF NOT EXISTS (\\S+) on (\\S+)\\(([^)]+)\\);$")
			.matcher(stmt);
		return m.matches() ? m.group(3) : null;
	}

}
