package org.cote.accountmanager.provider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.cache.CacheUtil;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.OrganizationContext;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.record.RecordOperation;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;

public class PathProvider implements IProvider {
	public static final Logger logger = LogManager.getLogger(PathProvider.class);
	
	public void provide(BaseRecord contextUser, RecordOperation operation, ModelSchema lmodel, BaseRecord model) throws ModelException, FieldException, ValueException, ModelNotFoundException, ReaderException {
		/// Nothing to do
	}

	
	public void provide(BaseRecord contextUser, RecordOperation operation, ModelSchema lmodel, BaseRecord model, FieldSchema lfield, FieldType field) throws ModelException, FieldException, ValueException, ModelNotFoundException {
		if(RecordOperation.NEW.equals(operation) && contextUser != null) {
			String ipath = field.getValue();
			if(ipath != null) {
				if(lfield.getBaseModel() != null && lfield.getBaseProperty() != null) {
					logger.info("Creating/Finding path: " + ipath);
					BaseRecord obj = null;
					if(lfield.getBaseModel().equals(ModelNames.MODEL_ORGANIZATION)) {
						OrganizationContext oc = IOSystem.getActiveContext().getOrganizationContext(ipath, null);
						if(oc != null && oc.isInitialized()) {
							obj = oc.getOrganization();
						}
					}
					else {
						obj = IOSystem.getActiveContext().getPathUtil().makePath(contextUser, lfield.getBaseModel(), ipath, null, contextUser.get(FieldNames.FIELD_ORGANIZATION_ID));
					}
					if(obj != null) {
						model.set(lfield.getBaseProperty(), obj.get(FieldNames.FIELD_ID));
						if(ipath.startsWith("~/") && contextUser != null) {
							String homePath = contextUser.get(FieldNames.FIELD_HOME_DIRECTORY_FIELD_PATH);
							if(homePath == null || homePath.length() == 0) {
								logger.warn("Invalid home directory path - constructing from owner");
								homePath = "/home/" + contextUser.get(FieldNames.FIELD_NAME);
							}
							model.set(field.getName(), homePath + ipath.substring(1));
						}
					}
					else {
						logger.warn("Failed to create path");
					}
				}
			}
			return;
		}
		else if(!RecordOperation.READ.equals(operation) && !RecordOperation.INSPECT.equals(operation)) {
			return;
		}
		String path = null;
		String mname = null;
		
		if(model.hasField(FieldNames.FIELD_NAME)) {
			mname = model.get(FieldNames.FIELD_NAME);
		}
		if(lfield.getBaseModel() != null && lfield.getBaseProperty() != null) {
			String baseProp = lfield.getBaseProperty();
			
			if(model.hasField(lfield.getBaseProperty())) {
				long baseId = model.get(lfield.getBaseProperty());
				if(baseId > 0L) {
					try {
						BaseRecord refRec = IOSystem.getActiveContext().getReader().read(lfield.getBaseModel(), baseId);
						if(refRec != null && refRec.hasField(FieldNames.FIELD_PATH)) {
							path = refRec.get(FieldNames.FIELD_PATH);
							logger.debug("Apply linked object id " + baseId + " path: " + path);
						}
					} catch (ReaderException e) {
						logger.error(e);
						
						logger.error(model.toString());
						throw new ValueException(e.getMessage());
					}
				}

			}
			else {
				logger.debug("Model " + model.getModel() + " does not define " + lfield.getBaseProperty());
				return;
			}
		}
		else if(model.inherits(ModelNames.MODEL_PARENT) && model.hasField(FieldNames.FIELD_PARENT_ID)) {
			if(mname == null) {
				//throw new FieldException(String.format(FieldException.FIELD_NOT_FOUND, model.getModel(), FieldNames.FIELD_NAME));
				logger.error(String.format(FieldException.FIELD_NOT_FOUND, model.getModel(), FieldNames.FIELD_NAME));
				return;
			}
			long parentId = model.get(FieldNames.FIELD_PARENT_ID);
			
			if(parentId > 0L) {
				if(IOSystem.getActiveContext().getIoType() == RecordIO.FILE || IOSystem.getActiveContext().getIoType() == RecordIO.DATABASE) {
					List<String> pathList = new ArrayList<>();
					pathList.add(mname);
					String base = model.getModel();

					if(lfield.getBaseModel() != null) {
						base = lfield.getBaseModel();
					}
					
					BaseRecord parR = null;
					try {
						
						while(parentId > 0L) {
							parR = IOSystem.getActiveContext().getReader().read(base, parentId);
							if(parR == null) {
								throw new ReaderException("Parent " + model.getModel() + " index not found for id " + parentId);
							}
							// IOSystem.getActiveContext().getReader().populate(parR);
							
							if(!parR.hasField(FieldNames.FIELD_NAME)) {
								logger.debug("Flush Cache For Partial");
							 	CacheUtil.clearCache(parR);
							 	parR = IOSystem.getActiveContext().getReader().read(base, parentId);
							}
							
							String name = parR.get(FieldNames.FIELD_NAME);
							parentId = parR.get(FieldNames.FIELD_PARENT_ID);
							pathList.add(name);
							if(name == null) {
								logger.error(parR.toString());
								throw new ReaderException("Null name value for id " + parentId);
							}
						}
					} catch (ReaderException e) {
						logger.error(e);
					}
					Collections.reverse(pathList);
					path = "/" + pathList.stream().collect(Collectors.joining("/"));
				}
				else {
					logger.warn("Unhandled IO: " + IOSystem.getActiveContext().getIoType().toString());
				}
			}
			else {
				logger.debug("Parent ID not found on " + model.getModel() + " " + mname);
			}
		}

		if(path == null && mname != null) {
			path = "/" + mname;
			logger.debug("Unhandled: Model " + model.getModel() + " " + model.get(FieldNames.FIELD_NAME) + " does not define a parent");
		}
		if(path == null) {
			//logger.warn("Empty path value");
			logger.debug(model.toString());
		}
		if(IOSystem.getActiveContext().getPathUtil().isTrace()) {
			logger.info("Apply path to " + model.getModel() + " " + lfield.getName() + " == " + path);
		}
		model.set(lfield.getName(), path);
	}
	
}
