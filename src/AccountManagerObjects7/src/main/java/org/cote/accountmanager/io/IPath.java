package org.cote.accountmanager.io;

import org.cote.accountmanager.record.BaseRecord;

public interface IPath {
	public BaseRecord findPath(BaseRecord owner, String model, String path, String type, long organizationId);
	public BaseRecord makePath(BaseRecord owner, String model, String path, String type, long organizationId);
}
