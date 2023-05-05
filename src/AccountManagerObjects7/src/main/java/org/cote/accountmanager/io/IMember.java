package org.cote.accountmanager.io;

import org.cote.accountmanager.record.BaseRecord;

public interface IMember {
	public boolean member(BaseRecord user, BaseRecord object, BaseRecord actor, BaseRecord effect, boolean enable);
}
