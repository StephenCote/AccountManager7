package org.cote.accountmanager.olio.llm;

import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.LooseRecord;
import org.cote.accountmanager.record.RecordFactory;

public class OpenAIMessage extends LooseRecord {
	/// Wrap an existing record (e.g. a message read from DB or an
	/// LLM response). The original code did setFields(rec.getFields())
	/// which wholesale-REPLACED our own field set with the source's —
	/// stripping ephemeral fields (notably `pruned`) when the source
	/// didn't carry them (LLM responses and DB-loaded messages don't
	/// persist ephemerals). Downstream: pruneCount's setPruned(true)
	/// silently no-op'd on assistant messages because the field was
	/// gone — prune marks landed only on user messages, wire payload
	/// kept growing.
	///
	/// Fix: MERGE field lists. Use rec's FieldType refs where they
	/// exist (so mutations propagate to rec via the shared instance,
	/// preserving the original wrap-as-view behavior callers rely on),
	/// and ADD our own standard fields for anything rec is missing
	/// (so ephemerals like `pruned` are present and settable).
	public OpenAIMessage(BaseRecord rec) {
		this();  // initialize with full OpenAIMessage field set (our own fresh refs)
		if (rec == null) return;
		java.util.List<FieldType> merged = new java.util.ArrayList<>();
		java.util.Set<String> recNames = new java.util.HashSet<>();
		for (FieldType f : rec.getFields()) {
			merged.add(f);          // SHARED ref — mutations propagate to rec
			recNames.add(f.getName());
		}
		for (FieldType f : this.getFields()) {
			if (!recNames.contains(f.getName())) {
				merged.add(f);      // our standard field rec is missing (e.g. ephemeral `pruned`)
			}
		}
		this.setFields(merged);
	}

	public OpenAIMessage() {

		try {
			RecordFactory.newInstance(OlioModelNames.MODEL_OPENAI_MESSAGE, this, null);
		} catch (FieldException | ModelNotFoundException e) {
			logger.error(e);
		}

	}
	
	
	public boolean isPruned() {
		return get("pruned");
	}


	public void setPruned(boolean pruned) {
		setValue("pruned", pruned);
	}


	public String getRole() {
		return get("role");
	}
	public void setRole(String role) {
		setValue("role", role);
	}
	public String getContent() {
		return get("content");
	}
	public void setContent(String content) {
		setValue("content", content);
	}
	
}
