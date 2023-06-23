package org.cote.accountmanager.model.field.value;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.model.field.FieldEnumType;

public class ListValueType<T> extends ValueType {
	private List<T> value = null;
	
	public ListValueType(List<T> value) {
		super(FieldEnumType.LIST);
		this.value = value;
	}

	@SuppressWarnings("unchecked")
	public T getValue() {
		if(value == null) {
			// return (T)new ArrayList<T>();
			return (T)new CopyOnWriteArrayList<T>();
		}
		return (T)value;
	}

	public <T> void setValue(T value) throws ValueException {
		/// this.value = (List)value;
		// this.value = new ArrayList<>((List)value);
		this.value = new CopyOnWriteArrayList<>((List)value);
	}

}
