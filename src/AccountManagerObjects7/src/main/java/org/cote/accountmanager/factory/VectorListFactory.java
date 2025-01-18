package org.cote.accountmanager.factory;

import java.util.ArrayList;
import java.util.List;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.VectorUtil;
import org.cote.accountmanager.util.VectorUtil.ChunkEnumType;

public class VectorListFactory extends FactoryBase {
	
	public VectorListFactory(Factory modelFactory, ModelSchema schema) {
		super(modelFactory, schema);
	}
	
	@Override
	public BaseRecord newInstance(BaseRecord contextUser, BaseRecord recordTemplate, ParameterList parameterList, BaseRecord... arguments) throws FactoryException
	{
		BaseRecord ref = null;
		String chunk = parameterList.getParameter(FieldNames.FIELD_CHUNK, String.class, null);
		int chunkCount = parameterList.getParameter(FieldNames.FIELD_CHUNK_COUNT, Integer.class, 0);
		ChunkEnumType cet = ChunkEnumType.UNKNOWN;
		if(chunk != null) {
			cet = ChunkEnumType.valueOf(chunk);
		}
		
		String content = parameterList.getParameter(FieldNames.FIELD_CONTENT, String.class, null);
		
		if(parameterList != null) {
			ref = parameterList.getParameter(FieldNames.FIELD_VECTOR_REFERENCE, BaseRecord.class, null);
		}
		
		BaseRecord vlist = super.newInstance(contextUser, recordTemplate, parameterList, arguments);;
		try {
			List<BaseRecord> vects = new ArrayList<>();
			if(content == null || content.length() == 0) {
				if(ref == null) {
					throw new FactoryException("Vector reference is required when no content is specified.");
				}
				vects = VectorUtil.createVectorStore(ref, cet, chunkCount);
			}
			else {
				vects = VectorUtil.createVectorStore(ref, content, cet, chunkCount);
			}
			vlist.set(FieldNames.FIELD_VECTORS, vects);
			
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		
		return vlist;
	}
	
	@Override
	public BaseRecord implement(BaseRecord contextUser, BaseRecord newRecord, ParameterList parameterList, BaseRecord... arguments) throws FactoryException {
		return newRecord;
	}
	
}
