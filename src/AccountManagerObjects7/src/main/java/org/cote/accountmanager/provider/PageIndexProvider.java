package org.cote.accountmanager.provider;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordOperation;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.util.DocumentUtil;

/**
 * Opt-in provider for the PageIndex capability, mirroring {@link org.cote.accountmanager.olio.VectorProvider}.
 * A model opts in with <code>"pageIndex": "org.cote.accountmanager.provider.PageIndexProvider"</code> at
 * the JSON root. {@link #describe(ModelSchema, BaseRecord)} extracts the content to index by delegating to
 * {@link DocumentUtil#getStringContent(BaseRecord)} (reusing PDF/docx/text handling); the {@link #provide}
 * lifecycle methods are intentionally empty because PageIndex building is triggered explicitly via
 * {@code AccessPoint.pageIndex(...)}, not on record CREATE/UPDATE. A model may ship a subclass with a
 * custom {@code describe()} for structured records, exactly as VectorProvider special-cases narratives.
 */
public class PageIndexProvider implements IProvider {
	public static final Logger logger = LogManager.getLogger(PageIndexProvider.class);

	@Override
	public String describe(ModelSchema lmodel, BaseRecord model) {
		return DocumentUtil.getStringContent(model);
	}

	@Override
	public void provide(BaseRecord contextUser, RecordOperation operation, ModelSchema lmodel, BaseRecord model)
			throws ModelException, FieldException, ValueException, ModelNotFoundException, ReaderException {

	}

	@Override
	public void provide(BaseRecord contextUser, RecordOperation operation, ModelSchema lmodel, BaseRecord model,
			FieldSchema lfield, FieldType field)
			throws ModelException, FieldException, ValueException, ModelNotFoundException, ReaderException {

	}

}
