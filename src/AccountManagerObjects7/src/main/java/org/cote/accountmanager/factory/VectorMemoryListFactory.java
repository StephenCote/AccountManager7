package org.cote.accountmanager.factory;

import java.util.ArrayList;
import java.util.List;

import org.cote.accountmanager.exceptions.FactoryException;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.ParameterList;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;

public class VectorMemoryListFactory extends VectorListFactory {
	public VectorMemoryListFactory(Factory modelFactory, ModelSchema schema) {
		super(modelFactory, schema);
	}

	@Override
	public BaseRecord newInstance(BaseRecord contextUser, BaseRecord recordTemplate, ParameterList parameterList, BaseRecord... arguments) throws FactoryException
	{
		String memoryType = null;
		String conversationId = null;
		if(parameterList != null) {
			memoryType = parameterList.getParameter("memoryType", String.class, null);
			conversationId = parameterList.getParameter("conversationId", String.class, null);
		}

		BaseRecord vlist = super.newInstance(contextUser, recordTemplate, parameterList, arguments);
		BaseRecord vlist2 = null;
		List<BaseRecord> vectors = vlist.get("vectors");
		List<BaseRecord> nvects = new ArrayList<>();
		try {
			vlist2 = RecordFactory.newInstance(ModelNames.MODEL_VECTOR_MEMORY_LIST);
			for(BaseRecord v : vectors) {
				BaseRecord v2 = IOSystem.getActiveContext().getFactory().newInstance(ModelNames.MODEL_VECTOR_MEMORY, contextUser, v, null);
				v2.set("memoryType", memoryType);
				v2.set("conversationId", conversationId);
				nvects.add(v2);
			}
			vlist2.set("vectors", nvects);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}

		return vlist2;
	}
}
