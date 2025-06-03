package org.cote.accountmanager.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.IPath;
import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.io.IWriter;
import org.cote.accountmanager.objects.generated.PolicyResponseType;
import org.cote.accountmanager.policy.PolicyUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordOperation;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.PolicyResponseEnumType;

public abstract class PathUtil implements IPath {
	
	public static final Logger logger = LogManager.getLogger(PathUtil.class);
	
	private final IReader reader;
	private final IWriter writer;
	private final ISearch search;
	private boolean trace = false;
	
	public PathUtil(IReader reader, ISearch search) {
		this(reader, null, search);
	}
	public PathUtil(IReader reader, IWriter writer, ISearch search) {
		this.reader = reader;
		this.writer = writer;
		this.search = search;
	}
	
	
	
	public boolean isTrace() {
		return trace;
	}
	public void setTrace(boolean trace) {
		this.trace = trace;
	}
	public BaseRecord findPath(BaseRecord owner, String model, String path, String type, long organizationId) {
		return makePath(owner, model, path, type, organizationId, false);
	}
	
	/// Synchronized make path - when concurrent sessions hit the hierarchical create method, it's possible that the same object can be created twice, violating any constraint condition
	/// This in turn MAY result in a corrupted cache entry (still looking into that currency issue)
	///
	public synchronized BaseRecord makePath(BaseRecord owner, String model, String path, String type, long organizationId) {
		return makePath(owner, model, path, type, organizationId, true);
	}
	private BaseRecord makePath(BaseRecord owner, String model, String path, String type, long organizationId, boolean doCreate) {
		BaseRecord node = null;

		if(owner != null) {
			IOSystem.getActiveContext().getRecordUtil().populate(owner);
		}
		if(doCreate) {
			//logger.warn("MAKE PATH: " + path);
			// ErrorUtil.printStackTrace();
		}
		if(path.startsWith("~/")) {
			if(owner != null) {
				String homePath = owner.get(FieldNames.FIELD_HOME_DIRECTORY_FIELD_PATH);
				if(homePath == null || homePath.length() == 0) {
					logger.warn("Invalid home directory path - constructing from owner");
					homePath = "/home/" + owner.get(FieldNames.FIELD_NAME);
				}
				path = homePath + path.substring(1);
				if(trace) {
					logger.info("Path: " + path);
				}
			}
			else {
				logger.error("Cannot resolve a relative user path without a user reference");
				return null;
			}
		}

		String[] pathE = path.split("/");
		long parentId = 0L;
		

		try {
			for(String e : pathE) {
				if(e == null || e.length() == 0) {
					continue;
				}
				String utype = type;
				
				/// When trying to get type specific paths, allow to build off a singular base such as /home/{name} vs. duplicating /home/{name}
				/// TODO: This needs to be configurable because it would also be helpful in the Community layout
				///
				if(owner != null && (e.equals("home") || e.equals(owner.get(FieldNames.FIELD_NAME)))) {
					if(model.equals(ModelNames.MODEL_GROUP)) {
						utype = "DATA";
					}
					else if(model.equals(ModelNames.MODEL_PERMISSION) || model.equals(ModelNames.MODEL_ROLE)) {
						utype = "USER";
					}
				}
				
				BaseRecord[] nodes = search.findByNameInParent(model, parentId, e, utype, organizationId);
				if(trace) {
					logger.info("Found " + nodes.length + " " + model + " named " + e + " in #" + parentId);
				}
				if(nodes.length == 0) {
					if(trace) {
						logger.info("Create in parent #" + parentId);
					}
					if(!doCreate) {
						if(trace) {
							logger.warn("Failed to find '" + e + "' " + (utype != null ? "of type (" + utype + ") " : "") + "in parent " + parentId + " in path " + path + " in organization " + organizationId + ", create = false");
						}
						node = null;
						break;
					}
					else {
						node = RecordFactory.model(model).newInstance();
						node.set(FieldNames.FIELD_NAME, e);
						node.set(FieldNames.FIELD_PARENT_ID, parentId);
						node.set(FieldNames.FIELD_ORGANIZATION_ID, organizationId);
						if(type != null && node.hasField(FieldNames.FIELD_TYPE)) {
							node.set(FieldNames.FIELD_TYPE, type);
						}
						if(owner != null) {
							node.set(FieldNames.FIELD_OWNER_ID, owner.get(FieldNames.FIELD_ID));
						}

						writer.translate(RecordOperation.READ, node);
						PolicyResponseType prr = null;
						if(IOSystem.getActiveContext().isEnforceAuthorization()
							&& (
								owner == null
								||
								(prr = IOSystem.getActiveContext().getPolicyUtil().evaluateResourcePolicy(owner, PolicyUtil.POLICY_SYSTEM_CREATE_OBJECT, owner, node)).getType() != PolicyResponseEnumType.PERMIT)
						) {
							logger.error("Not authorized to create " + model + " " + (type != null ? "of type (" + type + ") " : "") + "node " + e + " with parent #" + parentId + " in path " + path);
							return null;
						}
						
						writer.write(node);
						writer.flush();
						parentId = node.get(FieldNames.FIELD_ID);
					}
				}
				else if(nodes.length == 1) {
					node = nodes[0];
					parentId = node.get(FieldNames.FIELD_ID);
					if(type == null) {
						type = node.get(FieldNames.FIELD_TYPE);
					}
				}
				else {
					logger.error("Invalid search for " + model + " type " + type + " parent " + parentId + " org " + organizationId + " from '" + e + "' with " + nodes.length + " results");
				}
			}
			if(doCreate) {
				writer.flush();
			}

		}
		catch(ValueException | WriterException | ReaderException | FieldException | ModelNotFoundException e) {
			logger.error(e.getMessage());
			node = null;
		}

		return node;

	}
}
