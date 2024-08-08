package org.cote.accountmanager.personality;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.ResourceUtil;

public class MBTIUtil {
	
	public static final Logger logger = LogManager.getLogger(MBTIUtil.class);
	
	private static Map<String, MBTI> mbtiDef = new ConcurrentHashMap<>();
	public static MBTI getMBTI(String key) {
		Map<String, MBTI> mbtiMap = getMBTIDef();
		if(key != null && mbtiMap.containsKey(key)) {
			return mbtiMap.get(key);
		}
		else {
			logger.warn("Invalid mbti key '" + key + "'");
		}

		return null;
	}
	public static Map<String, MBTI> getMBTIDef(){
		if(mbtiDef.keySet().size() == 0) {
			String[] mbtiJson = JSONUtil.importObject(ResourceUtil.getInstance().getResource("olio/mbti.json"), String[].class);
			for(String s: mbtiJson) {
				String[] pairs = s.split("\\|");
				mbtiDef.put(pairs[0], new MBTI(pairs[0], pairs[1], pairs[2], pairs[3]));
			}			
		}
		return mbtiDef;
	}
	
	/// enfj - conflict is difficult, tends to avoid.  Tries to end conflict/argument as soon as possible. Can lead to quickly settling small disputes, but neglect/avoid large disputes
	/// enfp - irritated by narrow/closed-mindedness; difficulty making decisions
	/// entj - cause conflict being too direct or disregarding other people's feelings; like quick, logical solutions
	/// entp - resolve conflict, pragmatic problem solver; love debate, but may lead to stress with others
	/// esfj - avoids conflict if it threatens relationships and values, uncomfortable with criticism; defensive of their own beliefs
	/// esfp - skilled at minimizing conflict, often able to calm people down; may be in conflict with more serious personalities
	/// estj - tend to dominate other people with thoughts and opinions, may be the source of conflict; don't like vague and lengthy decision making
	/// estp - doesn't take conflict seriously, quick to compromise, not likely to intervene
	/// infj - skilled at conflict resolution, although sometimes at their own expense
	/// infp - able to understand group dynamics, confront conflict-head-on
	/// intj - don't usually engage in conflict or they get angry, become irritated with emotional people
	/// intp - easy going, tend to avoid conflict
	/// isfj - tend to avoid conflict, apologize to try to end argument; passive-aggressive
	/// isfp - understands behavior of others and identify conflict; avoids conflict
	/// istj - conflict is part of life, tries to resolve conflict as soon as possible
	/// istp - tend to avoid conflict, blunt style can lead to creating conflict
	
	//private static Map<String, CompatibilityEnumType> mbtiCompat = new ConcurrentHashMap<>();
	private static List<MBTICompatibility> mbtiCompat = new ArrayList<>();
	static {
		/// infp
		mbtiCompat.add(new MBTICompatibility("infp", "infp", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("infp", "enfp", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("infp", "infj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("infp", "enfj", CompatibilityEnumType.IDEAL));
		mbtiCompat.add(new MBTICompatibility("infp", "intj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("infp", "entj", CompatibilityEnumType.IDEAL));
		mbtiCompat.add(new MBTICompatibility("infp", "intp", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("infp", "entp", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("infp", "isfp", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("infp", "esfp", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("infp", "istp", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("infp", "estp", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("infp", "isfj", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("infp", "esfj", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("infp", "istj", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("infp", "estj", CompatibilityEnumType.NOT_COMPATIBLE));
		/// enfp
		mbtiCompat.add(new MBTICompatibility("enfp", "infp", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("enfp", "enfp", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("enfp", "infj", CompatibilityEnumType.IDEAL));
		mbtiCompat.add(new MBTICompatibility("enfp", "enfj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("enfp", "intj", CompatibilityEnumType.IDEAL));
		mbtiCompat.add(new MBTICompatibility("enfp", "entj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("enfp", "intp", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("enfp", "entp", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("enfp", "isfp", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("enfp", "esfp", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("enfp", "istp", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("enfp", "estp", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("enfp", "isfj", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("enfp", "esfj", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("enfp", "istj", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("enfp", "estj", CompatibilityEnumType.NOT_COMPATIBLE));
		
		/// infj
		mbtiCompat.add(new MBTICompatibility("infj", "infp", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("infj", "enfp", CompatibilityEnumType.IDEAL));
		mbtiCompat.add(new MBTICompatibility("infj", "infj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("infj", "enfj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("infj", "intj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("infj", "entj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("infj", "intp", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("infj", "entp", CompatibilityEnumType.IDEAL));
		mbtiCompat.add(new MBTICompatibility("infj", "isfp", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("infj", "esfp", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("infj", "istp", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("infj", "estp", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("infj", "isfj", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("infj", "esfj", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("infj", "istj", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("infj", "estj", CompatibilityEnumType.NOT_COMPATIBLE));	
		
		/// enfj
		mbtiCompat.add(new MBTICompatibility("enfj", "infp", CompatibilityEnumType.IDEAL));
		mbtiCompat.add(new MBTICompatibility("enfj", "enfp", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("enfj", "infj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("enfj", "enfj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("enfj", "intj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("enfj", "entj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("enfj", "intp", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("enfj", "entp", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("enfj", "isfp", CompatibilityEnumType.IDEAL));
		mbtiCompat.add(new MBTICompatibility("enfj", "esfp", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("enfj", "istp", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("enfj", "estp", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("enfj", "isfj", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("enfj", "esfj", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("enfj", "istj", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("enfj", "estj", CompatibilityEnumType.NOT_COMPATIBLE));	
		
		/// intj
		mbtiCompat.add(new MBTICompatibility("intj", "infp", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("intj", "enfp", CompatibilityEnumType.IDEAL));
		mbtiCompat.add(new MBTICompatibility("intj", "infj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("intj", "enfj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("intj", "intj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("intj", "entj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("intj", "intp", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("intj", "entp", CompatibilityEnumType.IDEAL));
		mbtiCompat.add(new MBTICompatibility("intj", "isfp", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("intj", "esfp", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("intj", "istp", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("intj", "estp", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("intj", "isfj", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("intj", "esfj", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("intj", "istj", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("intj", "estj", CompatibilityEnumType.NOT_IDEAL));
		
		/// entj
		mbtiCompat.add(new MBTICompatibility("entj", "infp", CompatibilityEnumType.IDEAL));
		mbtiCompat.add(new MBTICompatibility("entj", "enfp", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("entj", "infj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("entj", "enfj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("entj", "intj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("entj", "entj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("entj", "intp", CompatibilityEnumType.IDEAL));
		mbtiCompat.add(new MBTICompatibility("entj", "entp", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("entj", "isfp", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("entj", "esfp", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("entj", "istp", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("entj", "estp", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("entj", "isfj", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("entj", "esfj", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("entj", "istj", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("entj", "estj", CompatibilityEnumType.PARTIAL));
		
		/// intp
		mbtiCompat.add(new MBTICompatibility("intp", "infp", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("intp", "enfp", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("intp", "infj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("intp", "enfj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("intp", "intj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("intp", "entj", CompatibilityEnumType.IDEAL));
		mbtiCompat.add(new MBTICompatibility("intp", "intp", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("intp", "entp", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("intp", "isfp", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("intp", "esfp", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("intp", "istp", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("intp", "estp", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("intp", "isfj", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("intp", "esfj", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("intp", "istj", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("intp", "estj", CompatibilityEnumType.IDEAL));
		
		/// entp
		mbtiCompat.add(new MBTICompatibility("entp", "infp", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("entp", "enfp", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("entp", "infj", CompatibilityEnumType.IDEAL));
		mbtiCompat.add(new MBTICompatibility("entp", "enfj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("entp", "intj", CompatibilityEnumType.IDEAL));
		mbtiCompat.add(new MBTICompatibility("entp", "entj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("entp", "intp", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("entp", "entp", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("entp", "isfp", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("entp", "esfp", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("entp", "istp", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("entp", "estp", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("entp", "isfj", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("entp", "esfj", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("entp", "istj", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("entp", "estj", CompatibilityEnumType.NOT_IDEAL));
		
		/// isfp
		mbtiCompat.add(new MBTICompatibility("isfp", "infp", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("isfp", "enfp", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("isfp", "infj", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("isfp", "enfj", CompatibilityEnumType.IDEAL));
		mbtiCompat.add(new MBTICompatibility("isfp", "intj", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("isfp", "entj", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("isfp", "intp", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("isfp", "entp", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("isfp", "isfp", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("isfp", "esfp", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("isfp", "istp", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("isfp", "estp", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("isfp", "isfj", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("isfp", "esfj", CompatibilityEnumType.IDEAL));
		mbtiCompat.add(new MBTICompatibility("isfp", "istj", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("isfp", "estj", CompatibilityEnumType.IDEAL));
		
		/// esfp
		mbtiCompat.add(new MBTICompatibility("esfp", "infp", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("esfp", "enfp", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("esfp", "infj", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("esfp", "enfj", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("esfp", "intj", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("esfp", "entj", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("esfp", "intp", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("esfp", "entp", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("esfp", "isfp", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("esfp", "esfp", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("esfp", "istp", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("esfp", "estp", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("esfp", "isfj", CompatibilityEnumType.IDEAL));
		mbtiCompat.add(new MBTICompatibility("esfp", "esfj", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("esfp", "istj", CompatibilityEnumType.IDEAL));
		mbtiCompat.add(new MBTICompatibility("esfp", "estj", CompatibilityEnumType.PARTIAL));
		
		/// istp
		mbtiCompat.add(new MBTICompatibility("istp", "infp", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("istp", "enfp", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("istp", "infj", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("istp", "enfj", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("istp", "intj", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("istp", "entj", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("istp", "intp", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("istp", "entp", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("istp", "isfp", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("istp", "esfp", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("istp", "istp", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("istp", "estp", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("istp", "isfj", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("istp", "esfj", CompatibilityEnumType.IDEAL));
		mbtiCompat.add(new MBTICompatibility("istp", "istj", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("istp", "estj", CompatibilityEnumType.IDEAL));
		
		/// estp
		mbtiCompat.add(new MBTICompatibility("estp", "infp", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("estp", "enfp", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("estp", "infj", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("estp", "enfj", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("estp", "intj", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("estp", "entj", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("estp", "intp", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("estp", "entp", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("estp", "isfp", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("estp", "esfp", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("estp", "istp", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("estp", "estp", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("estp", "isfj", CompatibilityEnumType.IDEAL));
		mbtiCompat.add(new MBTICompatibility("estp", "esfj", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("estp", "istj", CompatibilityEnumType.IDEAL));
		mbtiCompat.add(new MBTICompatibility("estp", "estj", CompatibilityEnumType.PARTIAL));
		
		/// isfj
		mbtiCompat.add(new MBTICompatibility("isfj", "infp", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("isfj", "enfp", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("isfj", "infj", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("isfj", "enfj", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("isfj", "intj", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("isfj", "entj", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("isfj", "intp", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("isfj", "entp", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("isfj", "isfp", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("isfj", "esfp", CompatibilityEnumType.IDEAL));
		mbtiCompat.add(new MBTICompatibility("isfj", "istp", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("isfj", "estp", CompatibilityEnumType.IDEAL));
		mbtiCompat.add(new MBTICompatibility("isfj", "isfj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("isfj", "esfj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("isfj", "istj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("isfj", "estj", CompatibilityEnumType.COMPATIBLE));
		
		/// esfj
		mbtiCompat.add(new MBTICompatibility("esfj", "infp", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("esfj", "enfp", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("esfj", "infj", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("esfj", "enfj", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("esfj", "intj", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("esfj", "entj", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("esfj", "intp", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("esfj", "entp", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("esfj", "isfp", CompatibilityEnumType.IDEAL));
		mbtiCompat.add(new MBTICompatibility("esfj", "esfp", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("esfj", "istp", CompatibilityEnumType.IDEAL));
		mbtiCompat.add(new MBTICompatibility("esfj", "estp", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("esfj", "isfj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("esfj", "esfj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("esfj", "istj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("esfj", "estj", CompatibilityEnumType.COMPATIBLE));
		
		/// istj
		mbtiCompat.add(new MBTICompatibility("istj", "infp", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("istj", "enfp", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("istj", "infj", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("istj", "enfj", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("istj", "intj", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("istj", "entj", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("istj", "intp", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("istj", "entp", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("istj", "isfp", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("istj", "esfp", CompatibilityEnumType.IDEAL));
		mbtiCompat.add(new MBTICompatibility("istj", "istp", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("istj", "estp", CompatibilityEnumType.IDEAL));
		mbtiCompat.add(new MBTICompatibility("istj", "isfj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("istj", "esfj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("istj", "istj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("istj", "estj", CompatibilityEnumType.COMPATIBLE));
		
		/// estj
		mbtiCompat.add(new MBTICompatibility("estj", "infp", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("estj", "enfp", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("estj", "infj", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("estj", "enfj", CompatibilityEnumType.NOT_COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("estj", "intj", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("estj", "entj", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("estj", "intp", CompatibilityEnumType.IDEAL));
		mbtiCompat.add(new MBTICompatibility("estj", "entp", CompatibilityEnumType.NOT_IDEAL));
		mbtiCompat.add(new MBTICompatibility("estj", "isfp", CompatibilityEnumType.IDEAL));
		mbtiCompat.add(new MBTICompatibility("estj", "esfp", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("estj", "istp", CompatibilityEnumType.IDEAL));
		mbtiCompat.add(new MBTICompatibility("estj", "estp", CompatibilityEnumType.PARTIAL));
		mbtiCompat.add(new MBTICompatibility("estj", "isfj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("estj", "esfj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("estj", "istj", CompatibilityEnumType.COMPATIBLE));
		mbtiCompat.add(new MBTICompatibility("estj", "estj", CompatibilityEnumType.COMPATIBLE));
	}
	public static CompatibilityEnumType getCompatibility(String key1, String key2) {
		Optional<MBTICompatibility> opt = mbtiCompat.stream().filter(mc -> mc.getKey1().equals(key1) && mc.getKey2().equals(key2)).findFirst();
		if(opt.isPresent()) {
			return opt.get().getCompatibility();
		}
		return CompatibilityEnumType.UNKNOWN;
	}
	private static final String[] MBTI_CONFLICT_PAIRS = new String[] {"tj", "fj", "tp", "fp"};
}
