package org.cote.accountmanager.io;

import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.record.BaseRecord;

public interface ISearch {
	public void close() throws ReaderException;
	public BaseRecord findRecord(Query query);
	public BaseRecord[] findRecords(Query query);
	public int count(Query query);
	public QueryResult find(Query query) throws ReaderException;
	public BaseRecord[] findByName(String model, String name) throws ReaderException;
	public BaseRecord[] findByName(String model, String name, long organizationId) throws ReaderException;
	public BaseRecord[] findByUrn(String model, String urn) throws ReaderException;
	public BaseRecord findByPath(BaseRecord contextUser, String modelName, String path, long organizationId) throws ReaderException;
	public BaseRecord findByPath(BaseRecord contextUser, String modelName, String path, String type, long organizationId) throws ReaderException;
	public BaseRecord[] findByObjectId(String model, String objectId) throws ReaderException;
	public BaseRecord[] findById(String model, long id) throws ReaderException;
	public BaseRecord[] findByNameInParent(String model, long parentId, String name) throws ReaderException;
	public BaseRecord[] findByNameInParent(String model, long parentId, String name, long organizationId) throws ReaderException;
	public BaseRecord[] findByNameInParent(String model, long parentId, String name, String type) throws ReaderException;
	public BaseRecord[] findByNameInParent(String model, long parentId, String name, String type, long organizationId) throws ReaderException;
	public BaseRecord[] findByNameInGroup(String model, long groupId, String name) throws ReaderException;
	public BaseRecord[] findByNameInGroup(String model, long groupId, String name, long organizationId) throws ReaderException;
	public IOStatistics getStatistics();
	public void enableStatistics(boolean enabled);

}
