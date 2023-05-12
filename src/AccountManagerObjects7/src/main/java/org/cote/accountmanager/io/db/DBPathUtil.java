package org.cote.accountmanager.io.db;

import org.cote.accountmanager.io.IOContext;
import org.cote.accountmanager.util.PathUtil;

public class DBPathUtil extends PathUtil {
	public DBPathUtil(DBReader reader) {
		super(reader, new DBSearch(reader));
	}
	public DBPathUtil(DBReader reader, DBWriter writer, DBSearch search) {
		super(reader, writer, search);
	}
	public DBPathUtil(IOContext context) {
		super(context.getReader(), context.getWriter(), context.getSearch());
	}
}
