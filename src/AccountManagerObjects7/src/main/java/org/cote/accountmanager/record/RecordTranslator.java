package org.cote.accountmanager.record;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.model.field.value.ZoneTimeValueType;
import org.cote.accountmanager.provider.IProvider;
import org.cote.accountmanager.provider.ProviderUtil;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelSchema;

public abstract class RecordTranslator {
	public static final Logger logger = LogManager.getLogger(RecordTranslator.class);
	protected String storageBase = null;
	protected RecordIO recordIo = RecordIO.UNKNOWN;
		
	public RecordIO getRecordIo() {
		return recordIo;
	}

	protected void translateModel(RecordOperation operation, RecordIO io, ModelSchema lmodel, BaseRecord model) {
		if(lmodel.getProvider() != null && lmodel.getProvider().length() > 0) {
			IProvider prov = ProviderUtil.getProviderInstance(lmodel.getProvider());
			try {
				prov.provide(null, operation, lmodel, model);
			} catch (ModelException | FieldException | ValueException | ModelNotFoundException | ReaderException e) {
				logger.error(e);
				e.printStackTrace();
				
			}
		}
	}
	
	protected void translateField(RecordOperation operation, RecordIO io, ModelSchema lmodel, BaseRecord model, FieldSchema lfield, FieldType field) {
		
		FieldType bfield = RecordFactory.model(model.getModel()).getField(lfield.getName());
		
		if(lfield.getProvider() != null && lfield.getProvider().length() > 0) {
			IProvider prov = ProviderUtil.getProviderInstance(lfield.getProvider());
			try {
				prov.provide(null, operation, lmodel, model, lfield, field);
			} catch (ModelException | FieldException | ValueException | ModelNotFoundException | ReaderException e) {
				logger.error(e);
				e.printStackTrace();
			}
		}
		
		if(operation.equals(RecordOperation.CREATE)) {
			if(lfield.isIdentity()){
				try {
					if(recordIo.equals(RecordIO.FILE) && bfield.getValueType().equals(FieldEnumType.LONG)) {
						long cid = 0L;
						if(model.hasField(lfield.getName())){
							cid = model.get(lfield.getName());
						}
						if(cid <= 0L) {
							long id = IOSystem.getActiveContext().getIndexManager().getInstance(model.getModel()).nextId();
							model.set(lfield.getName(), id);
						}
					}
					else if(bfield.getValueType().equals(FieldEnumType.STRING)) {
						String oid = null;
						if(model.hasField(lfield.getName())){
							oid = model.get(lfield.getName());
						}
						if(oid == null) {
							model.set(lfield.getName(), UUID.randomUUID().toString());
						}
						else {
							//logger.info("*** Skip " + lfield.getName());
						}
					}
					else {
						// logger.info("**** Skip: " + lfield.getName());
					}
				} catch (NullPointerException | FieldException | ValueException | ModelNotFoundException | IndexException e) {
					logger.error(e);
				}
			}
			else if(lfield.isRequired()) {
				if(field == null || field.getValue() == null) {
					
					try {
						if(bfield.getValueType().equals(FieldEnumType.LONG)) {
							model.set(lfield.getName(), 0L);
						}
						else if(bfield.getValueType().equals(FieldEnumType.BOOLEAN)) {
							model.set(lfield.getName(), false);							
						}
						else if(bfield.getValueType().equals(FieldEnumType.DOUBLE)) {
							model.set(lfield.getName(), 0.0);							
						}
						else if(bfield.getValueType().equals(FieldEnumType.INT)) {
							model.set(lfield.getName(), 1.0);							
						}
						else if(bfield.getValueType().equals(FieldEnumType.TIMESTAMP)) {
							model.set(lfield.getName(), new Date());							
						}
						else if(bfield.getValueType().equals(FieldEnumType.ZONETIME)) {
							model.set(lfield.getName(), new ZoneTimeValueType(ZonedDateTime.ofInstant(Instant.ofEpochSecond(0), ZoneOffset.UTC)));							
						}

					} catch (FieldException | ValueException | ModelNotFoundException e) {
						logger.error(e);
					}
				}
			}
		}
		
		
		
	}

	
	
	
}
