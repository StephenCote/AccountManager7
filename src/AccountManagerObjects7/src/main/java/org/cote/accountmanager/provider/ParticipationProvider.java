package org.cote.accountmanager.provider;

import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.exceptions.WriterException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.record.RecordIO;
import org.cote.accountmanager.record.RecordOperation;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;

public class ParticipationProvider implements IProvider {
	public static final Logger logger = LogManager.getLogger(ParticipationProvider.class);

	public void provide(BaseRecord contextUser, RecordOperation operation, ModelSchema lmodel, BaseRecord model, FieldSchema lfield, FieldType field) throws ModelException, FieldException, ValueException, ModelNotFoundException, ReaderException {
		/// Nothing to do
		
	}

	public void provide(BaseRecord contextUser, RecordOperation operation, ModelSchema lmodel, BaseRecord model) throws ModelException, FieldException, ValueException, ModelNotFoundException, ReaderException {
		if(
			operation == RecordOperation.UNKNOWN
			|| operation == RecordOperation.READ
			|| IOSystem.getActiveContext().getIoType() != RecordIO.FILE
			|| !model.inherits(ModelNames.MODEL_PARTICIPATION)
		) {
			// logger.warn("Skip " + model.getModel());
			return;
		}
		
		//logger.info("Process " + model.getModel());
		//logger.info(JSONUtil.exportObject(model, RecordSerializerConfig.getUnfilteredModule()));
		
		if(IOSystem.getActiveContext().getWriter() == null) {
			throw new ModelException("Missing write controller");
		}
		
		//IPath putil = IOFactory.getPathUtil(controller.getReader(), controller.getWriter());
		try {
			if(operation == RecordOperation.CREATE || operation == RecordOperation.UPDATE) {
				updateEntry(operation, lmodel, model);
			}
			else if(operation == RecordOperation.DELETE) {
				removeEntry(operation, lmodel, model);
			}
		}
		catch(WriterException | IndexException e) {
			logger.error(e);
			
		}

	}
	

	private BaseRecord getCreateList(BaseRecord model, String listName, String fieldName) throws FieldException, ModelNotFoundException, IndexException, ReaderException, ValueException, WriterException {
		BaseRecord list = getList(model, listName);
		if(list == null) {
			// logger.info("Create new list - " + listName);
			list = RecordFactory.newInstance(ModelNames.MODEL_PARTICIPATION_LIST);
			list.set(FieldNames.FIELD_NAME, listName);
			list.set(FieldNames.FIELD_ORGANIZATION_ID, model.get(FieldNames.FIELD_ORGANIZATION_ID));
			list.set(FieldNames.FIELD_PART_ID, model.get(fieldName));
			IOSystem.getActiveContext().getWriter().write(list);
			IOSystem.getActiveContext().getWriter().flush();
		}
		return list;
	}

	
	private BaseRecord getList(BaseRecord model, String listName) throws IndexException, ReaderException {

		BaseRecord[] recc = IOSystem.getActiveContext().getSearch().findByName(ModelNames.MODEL_PARTICIPATION_LIST, listName);
		if(recc.length > 0) {
			return recc[0];
		}
		return null;
	}
	
	private void updateEntry(RecordOperation operation, ModelSchema lmodel, BaseRecord model) throws WriterException, IndexException, ReaderException, FieldException, ValueException, ModelNotFoundException {
		// logger.info("Update entry");
		String partcName = model.get(FieldNames.FIELD_PARTICIPATION_ID) + "-ParticipationList";
		String partpName = model.get(FieldNames.FIELD_PARTICIPANT_ID) + "-ParticipantList";
		addEntryToList(model, partcName, FieldNames.FIELD_PARTICIPATION_ID, FieldNames.FIELD_PARTICIPANT_ID, FieldNames.FIELD_PARTICIPANT_MODEL);
		addEntryToList(model, partpName, FieldNames.FIELD_PARTICIPANT_ID, FieldNames.FIELD_PARTICIPATION_ID, FieldNames.FIELD_PARTICIPATION_MODEL);

	}
	
	private void addEntryToList(BaseRecord model, String listName, String listFieldName, String fieldName, String fieldType) throws WriterException, IndexException, ReaderException, FieldException, ValueException, ModelNotFoundException {
		BaseRecord listc = getCreateList(model, listName, listFieldName);

		List<BaseRecord> parts = listc.get(FieldNames.FIELD_PARTS);
		BaseRecord npart = RecordFactory.newInstance(ModelNames.MODEL_PARTICIPATION_ENTRY);
		npart.set(FieldNames.FIELD_PART_ID, model.get(fieldName));
		npart.set(FieldNames.FIELD_TYPE, model.get(fieldType));
		if(model.hasField(FieldNames.FIELD_PERMISSION_ID)) {
			npart.set(FieldNames.FIELD_PERMISSION_ID, model.get(FieldNames.FIELD_PERMISSION_ID));
		}
		parts.add(npart);
		IOSystem.getActiveContext().getWriter().write(listc);
		IOSystem.getActiveContext().getWriter().flush();

	}
	

	private void removeEntry(RecordOperation operation, ModelSchema lmodel, BaseRecord model) throws IndexException, ReaderException, WriterException {
		// logger.info("Remove entry");
		// logger.info(JSONUtil.exportObject(model, RecordSerializerConfig.getUnfilteredModule()));
		String partcName = model.get(FieldNames.FIELD_PARTICIPATION_ID) + "-ParticipationList";
		String partpName = model.get(FieldNames.FIELD_PARTICIPANT_ID) + "-ParticipantList";
		removeEntryFromList(model, partcName, FieldNames.FIELD_PARTICIPANT_ID);
		removeEntryFromList(model, partpName, FieldNames.FIELD_PARTICIPATION_ID);
	}
	
	private void removeEntryFromList(BaseRecord model, String listName, String fieldName) throws WriterException, IndexException, ReaderException {
		BaseRecord listc = getList(model, listName);
		long id = model.get(fieldName);
		if(listc != null) {
			List<BaseRecord> parts = listc.get(FieldNames.FIELD_PARTS);
			List<BaseRecord> nparts = parts.stream().filter(o ->{
				long mid = o.get(FieldNames.FIELD_PART_ID);
				return id != mid;
			}).collect(Collectors.toList());
			// logger.info("Removed entry " + listName + ": " + parts.size() + " -> " + nparts.size());
			parts.clear();
			parts.addAll(nparts);
			IOSystem.getActiveContext().getWriter().write(listc);
			IOSystem.getActiveContext().getWriter().flush();
		}
		else {
			logger.info("Skip removing entry from nonexistent list: " + listName);
		}
	}

}
