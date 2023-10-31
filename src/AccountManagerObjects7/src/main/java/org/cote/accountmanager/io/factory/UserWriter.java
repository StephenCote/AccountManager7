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

public class UserWriter implements IWriter {
	public static final Logger logger = LogManager.getLogger(UserWriter.class);

	@Override
	public void flush() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public RecordIO getRecordIo() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public void close() throws WriterException {
		// TODO Auto-generated method stub
		
	}
	
	@Override
	public int write(BaseRecord[] recs) throws WriterException {
		throw new WriterException("Bulk user write operations are not supported");
	}

	@Override
	public boolean write(BaseRecord rec) throws WriterException {
		RecordOperation op = RecordOperation.CREATE;
		boolean outBool = false;
		OrganizationContext ctx = IOSystem.getActiveContext().findOrganizationContext(rec);
		if(ctx != null && ctx.getAdminUser() != null && !RecordUtil.isIdentityRecord(rec)) {
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

	@Override
	public boolean write(BaseRecord rec, OutputStream stream) throws WriterException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean delete(BaseRecord rec) throws WriterException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public boolean delete(BaseRecord rec, OutputStream stream) throws WriterException {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public void translate(RecordOperation operation, BaseRecord rec) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public int delete(Query query) throws WriterException {
		throw new WriterException("Bulk delete operations based on a query are not supported");
	}

	
}
