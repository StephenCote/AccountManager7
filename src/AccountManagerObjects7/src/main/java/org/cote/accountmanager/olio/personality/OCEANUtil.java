package org.cote.accountmanager.olio.personality;

import java.math.RoundingMode;
import java.text.DecimalFormat;

import org.cote.accountmanager.record.BaseRecord;

public class OCEANUtil {

	public static String getCompatibilityKey(BaseRecord per1, BaseRecord per2) {
		CompatibilityEnumType opat = getOpennessCompatibility(per1, per2);
		CompatibilityEnumType cpat = getConscientiousnessCompatibility(per1, per2);
		CompatibilityEnumType epat = getExtraversionCompatibility(per1, per2);
		CompatibilityEnumType apat = getAgreeablenessCompatibility(per1, per2);
		CompatibilityEnumType npat = getNeuroticismCompatibility(per1, per2);
		StringBuilder buff = new StringBuilder();
		if(opat == CompatibilityEnumType.IDEAL) {
			buff.append("(O)");
		}
		else if(opat == CompatibilityEnumType.COMPATIBLE) {
			buff.append("O");
		}
		else if(opat == CompatibilityEnumType.PARTIAL) {
			buff.append("o");
		}
		else {
			buff.append(".");
		}
		if(cpat == CompatibilityEnumType.IDEAL) {
			buff.append("(C)");
		}
		else if(cpat == CompatibilityEnumType.COMPATIBLE) {
			buff.append("C");
		}
		else if(cpat == CompatibilityEnumType.PARTIAL) {
			buff.append("c");
		}
		else {
			buff.append(".");
		}
		if(epat == CompatibilityEnumType.IDEAL) {
			buff.append("(E)");
		}
		else if(epat == CompatibilityEnumType.COMPATIBLE) {
			buff.append("E");
		}
		else if(epat == CompatibilityEnumType.PARTIAL) {
			buff.append("e");
		}
		else {
			buff.append(".");
		}
		if(apat == CompatibilityEnumType.IDEAL) {
			buff.append("(A)");
		}
		else if(apat == CompatibilityEnumType.COMPATIBLE) {
			buff.append("A");
		}
		else if(apat == CompatibilityEnumType.PARTIAL) {
			buff.append("a");
		}
		else {
			buff.append(".");
		}
		if(npat == CompatibilityEnumType.IDEAL) {
			buff.append("(N)");
		}
		else if(npat == CompatibilityEnumType.COMPATIBLE) {
			buff.append("N");
		}
		else if(npat == CompatibilityEnumType.PARTIAL) {
			buff.append("n");
		}
		else {
			buff.append(".");
		}
		return buff.toString();

	}
	
	public static CompatibilityEnumType getOpennessCompatibility(BaseRecord per1, BaseRecord per2) {
		return get123Compatibility(per1, per2, "openness");
	}
	public static CompatibilityEnumType getConscientiousnessCompatibility(BaseRecord per1, BaseRecord per2) {
		return get123Compatibility(per1, per2, "conscientiousness");
	}	
	public static CompatibilityEnumType getExtraversionCompatibility(BaseRecord per1, BaseRecord per2) {
		return get257Compatibility(per1, per2, "extraversion");
	}	
	public static CompatibilityEnumType getAgreeablenessCompatibility(BaseRecord per1, BaseRecord per2) {
		return get136Compatibility(per1, per2, "agreeableness");
	}	
	public static CompatibilityEnumType getNeuroticismCompatibility(BaseRecord per1, BaseRecord per2) {
		return getPolarizedCompatibility(per1, per2, "neuroticism", 0.2, 0.3, 0.5);
	}	
	
	
	private static CompatibilityEnumType get123Compatibility(BaseRecord per1, BaseRecord per2, String fieldName) {
		return getXYZCompatibility(per1, per2, fieldName, 0.1, 0.2, 0.3);
	}
	private static CompatibilityEnumType get136Compatibility(BaseRecord per1, BaseRecord per2, String fieldName) {
		return getXYZCompatibility(per1, per2, fieldName, 0.1, 0.3, 0.6);
	}
	private static CompatibilityEnumType get257Compatibility(BaseRecord per1, BaseRecord per2, String fieldName) {
		return getXYZCompatibility(per1, per2, fieldName, 0.2, 0.5, 0.7);
	}

	private static CompatibilityEnumType getXYZCompatibility(BaseRecord per1, BaseRecord per2, String fieldName, double ideal, double compat, double partial) {
		DecimalFormat df = new DecimalFormat("#.#");
		df.setRoundingMode(RoundingMode.DOWN);
		double val1 = Double.parseDouble(df.format(per1.get(fieldName)));
		double val2 = Double.parseDouble(df.format(per2.get(fieldName)));
		double irange = ideal;
		double crange = compat;
		double prange = partial;
		CompatibilityEnumType cet = CompatibilityEnumType.NOT_COMPATIBLE;
		if(val2 >= (val1 - irange) && val2 <= (val1 + irange)) {
			cet = CompatibilityEnumType.IDEAL;
		}
		else if(val2 >= (val1 - crange) && val2 <= (val1 + crange)) {
			cet = CompatibilityEnumType.COMPATIBLE;
		}
		else if(val2 >= (val1 - prange) && val2 <= (val1 + prange)) {
			cet = CompatibilityEnumType.PARTIAL;
		}
		return cet;
	}
	
	public static CompatibilityEnumType getPolarizedCompatibility(BaseRecord per1, BaseRecord per2, String fieldName, double ideal, double compat, double partial) {
		DecimalFormat df = new DecimalFormat("#.#");
		df.setRoundingMode(RoundingMode.DOWN);
		double val1 = Double.parseDouble(df.format(per1.get(fieldName)));
		double val2 = Double.parseDouble(df.format(per2.get(fieldName)));
		double irange = ideal;
		double crange = compat;
		double prange = partial;
		CompatibilityEnumType cet = CompatibilityEnumType.NOT_COMPATIBLE;
		if(val2 == val1) {
			cet = CompatibilityEnumType.NOT_COMPATIBLE;
		}
		if(val2 == (val1 - irange) || val2 == (val1 + irange)) {
			cet = CompatibilityEnumType.IDEAL;
		}
		else if(val2 >= (val1 - crange) && val2 <= (val1 + crange)) {
			cet = CompatibilityEnumType.COMPATIBLE;
		}
		else if(val2 >= (val1 - prange) && val2 <= (val1 + prange)) {
			cet = CompatibilityEnumType.PARTIAL;
		}
		return cet;
	}	
}
