package org.cote.accountmanager.io;

import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.record.BaseRecord;

public interface ISearch {
	public void close() throws ReaderException;
	public BaseRecord findRecord(Query query);
	public BaseRecord[] findRecords(Query query);
	public int count(Query query);
	public QueryResult find(Query query) throws IndexException, ReaderException;
	public BaseRecord[] findByName(String model, String name) throws IndexException, ReaderException;
	public BaseRecord[] findByName(String model, String name, long organizationId) throws IndexException, ReaderException;
	public BaseRecord[] findByUrn(String model, String urn) throws IndexException, ReaderException;
	public BaseRecord findByPath(BaseRecord contextUser, String modelName, String path, long organizationId) throws IndexException, ReaderException;
	public BaseRecord findByPath(BaseRecord contextUser, String modelName, String path, String type, long organizationId) throws IndexException, ReaderException;
	public BaseRecord[] findByObjectId(String model, String objectId) throws IndexException, ReaderException;
	public BaseRecord[] findById(String model, long id) throws IndexException, ReaderException;
	public BaseRecord[] findByNameInParent(String model, long parentId, String name) throws IndexException, ReaderException;
	public BaseRecord[] findByNameInParent(String model, long parentId, String name, long organizationId) throws IndexException, ReaderException;
	public BaseRecord[] findByNameInParent(String model, long parentId, String name, String type) throws IndexException, ReaderException;
	public BaseRecord[] findByNameInParent(String model, long parentId, String name, String type, long organizationId) throws IndexException, ReaderException;
	public BaseRecord[] findByNameInGroup(String model, long groupId, String name) throws IndexException, ReaderException;
	public BaseRecord[] findByNameInGroup(String model, long groupId, String name, long organizationId) throws IndexException, ReaderException;

}
