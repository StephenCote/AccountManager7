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
			else if(lfield.getCompute() == ComputeEnumType.SAVG) {
				ComputeUtil.computeSpreadAverage(model, lfield, lfield.getFields().toArray(new String[0]), getSpreadCenter(lmodel, model, lfield));
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

	/// The value a SAVG composite holds fixed while it restores spread. Defaults to the midpoint of
	/// the field's declared range (NaN lets ComputeUtil resolve that), which is correct only when the
	/// inputs are actually centred there. Subclasses that know where their inputs really sit should
	/// override this - see org.cote.accountmanager.olio.StatCompositeProvider.
	protected double getSpreadCenter(ModelSchema lmodel, BaseRecord model, FieldSchema lfield) {
		return Double.NaN;
	}

	@Override
	public String describe(ModelSchema lmodel, BaseRecord model)  {
		// TODO Auto-generated method stub
		return null;
	}



}
