package org.cote.accountmanager.olio;

import java.util.HashMap;
import java.util.Map;

public enum EthnicityEnumType {

	A("African"),
	B("Caribbean"),
	D("Indian"),
	E("Melanesian"),
	F("Aboriginal"),
	G("Chinese"),
	H("Guamanian"),
	J("Japanese"),
	K("Korean"),
	L("Polynesian"),
	P("European/Anglo Saxon"),
	Q("Pacific Islander"),
	S("Latin American"),
	T("Arabic"),
	V("Vietnamese"),
	W("Micronesian"),
	ZERO("American"),
	ONE("Other Hispanic"),
	TWO("US or Canadian Indian"),
	THREE("Other Asian"),
	FOUR("Puerto Rican"),
	FIVE("Filipino"),
	SIX("Mexican"),
	SEVEN("Alaskan Native"),
	EIGHT("Cuban"),
	NINE("Irish"),
	TEN("Scottish"),
	ELEVEN("British"),
	TWELVE("French"),
	THIRTEEN("German"),
	FOURTEEN("Russian");
	

	private String val = null;

    private static Map<String, EthnicityEnumType> ethMap = new HashMap<String, EthnicityEnumType>();

    static {
        for (EthnicityEnumType race : EthnicityEnumType.values()) {
            ethMap.put(race.val, race);
        }
    }

    private EthnicityEnumType(final String val) {
    	this.val = val;
    }

    public static String valueOf(EthnicityEnumType ret) {
        return ret.val;
    }
    public static EthnicityEnumType valueOfVal(String val) {
        return ethMap.get(val);
    }


}