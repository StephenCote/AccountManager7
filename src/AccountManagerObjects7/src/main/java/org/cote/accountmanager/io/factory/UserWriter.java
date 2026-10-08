package org.cote.accountmanager.io.factory;

import java.io.OutputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.IWriter;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.record.RecordOperation;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.util.RecordUtil;

/// The specialized writer declared by system.user's "io" block, reached through
/// RecordUtil.createRecord / createRecords -> ModelSchema.getIo().getWriter(). It exists for one reason:
/// a NEW user must be provisioned through Factory.getCreateUser (home directory, roles, credential
/// scaffolding) rather than written as a bare row. Everything else is delegated to the context's
/// system writer, which is the writer that would have been used had the model declared no "io" block.
/// RecordUtil.deleteRecord does NOT dispatch here (it always uses the system writer), so the delete
/// variants below are explicit refusals rather than silent falses.
public class UserWriter implements IWriter {
	public static final Logger logger = LogManager.getLogger(UserWriter.class);

	private static IWriter systemWriter() {
		return (IOSystem.getActiveContext() != null ? IOSystem.getActiveContext().getWriter() : null);
	}

	/// The update branch of write() hands the record to the system writer, so a flush must reach that
	/// writer too (FileWriter flushes its index there; DBWriter's flush is a no-op).
	@Override
	public void flush() {
		IWriter w = systemWriter();
		if(w != null) {
			w.flush();
		}
	}

	/// This writer stores nothing itself; the record lands wherever the system writer puts it.
	@Override
	public RecordIO getRecordIo() {
		IWriter w = systemWriter();
		return (w != null ? w.getRecordIo() : RecordIO.UNKNOWN);
	}

	/// The system writer's lifecycle belongs to IOContext; a per-call delegate must not close it.
	@Override
	public void close() throws WriterException {

	}
	
	@Override
	public int write(BaseRecord[] recs) throws WriterException {
		throw new WriterException("Bulk user write operations are not supported");
	}

	@Override
	public boolean write(BaseRecord rec) throws WriterException {
		/// An identity record is an update and goes straight to the system writer. Decide that BEFORE
		/// resolving the organization context: IOContext.findOrganizationContext reads organizationPath
		/// and organizationId with get(), which materializes a missing field at its default, so a patch
		/// that omitted organizationId was written back with organizationId = 0 and the user silently
		/// lost its organization (TestUserWriterIO caught this on 2026-10-07).
		if(RecordUtil.isIdentityRecord(rec)) {
			logger.info("Defaulting to system IO");
			return IOSystem.getActiveContext().getWriter().write(rec);
		}
		boolean outBool = false;
		OrganizationContext ctx = IOSystem.getActiveContext().findOrganizationContext(rec);
		if(ctx != null && ctx.getAdminUser() != null) {
			logger.info("Invoking createUser");
			BaseRecord user = IOSystem.getActiveContext().getFactory().getCreateUser(ctx.getAdminUser(), rec.get(FieldNames.FIELD_NAME), ctx.getOrganizationId());
			if(user != null) {
				outBool = true;
			}
		}
		else {
			logger.info("Defaulting to system IO");
			outBool = IOSystem.getActiveContext().getWriter().write(rec);
		}
		return outBool;
	}

	/// A user is not serialized to an external stream by its writer; export goes through RecordSerializer.
	@Override
	public boolean write(BaseRecord rec, OutputStream stream) throws WriterException {
		throw new WriterException("Writing a user to an external stream is not supported; serialize it with RecordSerializer");
	}

	/// Deletes never arrive here: RecordUtil.deleteRecord uses the system writer directly, and user
	/// removal must go through AccessPoint.delete so PBAC and participation cleanup run. A direct call
	/// is therefore a programming error, reported as such rather than as a quiet false.
	@Override
	public boolean delete(BaseRecord rec) throws WriterException {
		throw new WriterException("User delete is not routed through the user writer; use AccessPoint.delete");
	}

	@Override
	public boolean delete(BaseRecord rec, OutputStream stream) throws WriterException {
		throw new WriterException("User delete is not routed through the user writer; use AccessPoint.delete");
	}

	/// RecordUtil.createRecord runs the INSPECT translation through the custom writer before write().
	/// The user model's providers (urn, path, contact information) belong to the system writer, so
	/// delegate rather than silently skipping them - the update branch of write() relies on it.
	@Override
	public void translate(RecordOperation operation, BaseRecord rec) {
		IWriter w = systemWriter();
		if(w != null) {
			w.translate(operation, rec);
		}
	}

	@Override
	public int delete(Query query) throws WriterException {
		throw new WriterException("Bulk delete operations based on a query are not supported");
	}

	
}
