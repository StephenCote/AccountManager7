package org.cote.accountmanager.provider;

import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordOperation;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.ComputeUtil;

public class ComputeProvider  implements IProvider {
	public static final Logger logger = LogManager.getLogger(ComputeProvider.class);
	
	public void provide(BaseRecord contextUser, RecordOperation operation, ModelSchema lmodel, BaseRecord model) throws ModelException, FieldException, ValueException, ModelNotFoundException, ReaderException {
		/// Nothing to do
	}

	public void provide(BaseRecord contextUser, RecordOperation operation, ModelSchema lmodel, BaseRecord model, FieldSchema lfield, FieldType field) throws ModelException, FieldException, ValueException, ModelNotFoundException {
		if(!RecordOperation.READ.equals(operation) && !RecordOperation.INSPECT.equals(operation)) {
			return;
		}
		if(lfield.getCompute() != null && lfield.getFields().size() > 0) {
			List<String> mfields = lfield.getFields().stream().filter(f -> !model.hasField(f)).collect(Collectors.toList());
			if(mfields.size() > 0) {
				/// Don't warn on missing fields
				/// logger.warn("Missing fields: " + mfields.size());
				return;
			}
			if(lfield.getCompute() == ComputeEnumType.AVG) {
				ComputeUtil.computeAverage(model, lfield, lfield.getFields().toArray(new String[0]));
			}
			else if(lfield.getCompute() == ComputeEnumType.PERC20 && lfield.getFieldType() == FieldEnumType.DOUBLE) {
				int avg = ComputeUtil.getAverage(model, lfield.getFields().toArray(new String[0]));
				double val = (avg * 5)/100;
				DecimalFormat df = new DecimalFormat("#.#");
				df.setRoundingMode(RoundingMode.HALF_EVEN);
				model.set(lfield.getName(), Double.parseDouble(df.format(val)));
			}
		}
	
	}

	@Override
	public String describe(ModelSchema lmodel, BaseRecord model)  {
		// TODO Auto-generated method stub
		return null;
	}



}
