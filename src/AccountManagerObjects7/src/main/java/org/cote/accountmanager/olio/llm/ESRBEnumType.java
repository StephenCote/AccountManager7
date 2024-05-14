package org.cote.accountmanager.olio.llm;


import java.util.HashMap;
import java.util.Map;

public enum ESRBEnumType {
	E("Everyone"),
	E10("Everyone 10+"),
	T("Teen"),
	M("Mature 17+"),
	AO("Adults Only 18+"),
	RC("Refused Classification (Banned)")
	;

	private String val = null;

    private static Map<String, ESRBEnumType> esrbMap = new HashMap<>();
    private static Map<ESRBEnumType, String> esrbDesc = new HashMap<>();
    private static Map<ESRBEnumType, String> esrbShortDesc = new HashMap<>();
    private static Map<ESRBEnumType, String> esrbRestriction = new HashMap<>();
    private static Map<ESRBEnumType, String> esrbMpaMap = new HashMap<>();
    /// Restrictions from https://en.wikipedia.org/wiki/Video_game_content_rating_system
    private static String whiteRestrict = "Suitable for all ages / Aimed at young audiences / Exempt / Not rated / No applicable rating.";
    private static String yellowRestrict = "Parental guidance is suggested for designated age range.";
    private static String purpleRestrict = "Not recommended for a younger audience but not restricted.";
    private static String redRestrict = "Parental accompaniment required for younger audiences.";
    private static String blackRestrict = "Exclusively for older audience / Purchase age-restricted / Banned.";
    
    static {
        for (ESRBEnumType esrb : ESRBEnumType.values()) {
            esrbMap.put(esrb.val, esrb);
        }
        esrbMpaMap.put(E, "G");
        esrbMpaMap.put(E10, "PG");
        esrbMpaMap.put(T, "PG-13");
        esrbMpaMap.put(M, "R-17");
        esrbMpaMap.put(AO, "X");
        esrbMpaMap.put(RC, "XXX (Banned)");
        esrbRestriction.put(E, whiteRestrict);
        esrbRestriction.put(E10, purpleRestrict);
        esrbRestriction.put(T, purpleRestrict);
        esrbRestriction.put(M, redRestrict);
        esrbRestriction.put(AO, blackRestrict);
        esrbRestriction.put(RC, blackRestrict);
        
        /// Descriptions from https://www.esrb.org/ratings-guide/
        esrbDesc.put(E, "Content is generally suitable for all ages. May contain minimal cartoon, fantasy or mild violence and/or infrequent use of mild language.");
        esrbDesc.put(E10, "Content is generally suitable for ages 10 and up. May contain more cartoon, fantasy or mild violence, mild language and/or minimal suggestive themes.");
        esrbDesc.put(T, "Content is generally suitable for ages 13 and up. May contain violence, suggestive themes, crude humor, minimal blood, simulated gambling and/or infrequent use of strong language.");
        esrbDesc.put(M, "Content is generally suitable for ages 17 and up. May contain intense violence, blood and gore, sexual content and/or strong language.");
        esrbDesc.put(AO, "Content suitable only for adults ages 18 and up. May include prolonged scenes of intense violence, graphic sexual content and/or gambling with real currency.");
        esrbDesc.put(RC, "Content suitable only for adults ages 18 and up, and may be illegal or banned. May include prolonged scenes of intense violence, graphic sexual content, drug consumption, and/or gambling with real currency in direct violation of current law.");
        esrbShortDesc.put(E,"All ages, minimal violence.");
        esrbShortDesc.put(E10, "More violence, mild language.");
        esrbShortDesc.put(T, "Violence, suggestive themes, mild blood, language.");
        esrbShortDesc.put(M, "Intense violence, gore, sexual content, strong language.");
        esrbShortDesc.put(AO, "Graphic violence, sexual content.");
        esrbShortDesc.put(RC, "Unrated or banned. Graphic violence, sexual content.");
    }

    private ESRBEnumType(final String val) {
    	this.val = val;
    }
    public static String getESRBRestriction(ESRBEnumType e) {
    	return esrbRestriction.get(e);
    }
    public static String getESRBMPA(ESRBEnumType e) {
    	return esrbMpaMap.get(e);
    }
    public static String getESRBShortDescription(ESRBEnumType e) {
    	return esrbShortDesc.get(e);
    }
    public static String getESRBDescription(ESRBEnumType e) {
    	return esrbDesc.get(e);
    }
    public static String getESRBName(ESRBEnumType e) {
    	return e.val;
    }

    public static String valueOf(ESRBEnumType ret) {
        return ret.val;
    }
    public static ESRBEnumType valueOfVal(String val) {
        return esrbMap.get(val);
    }


}
