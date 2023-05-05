package org.cote.accountmanager.policy.operation;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.policy.FactUtil;

public abstract class Operation implements IOperation {
	public static final Logger logger = LogManager.getLogger(OwnerOperation.class);
	protected IReader reader = null;
	protected ISearch search = null;
	protected FactUtil factUtil = null;
	public Operation(IReader reader, ISearch search) {
		this.reader = reader;
		this.search = search;
		factUtil = new FactUtil(reader, search);

	}
}
