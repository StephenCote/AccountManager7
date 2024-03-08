package org.cote.accountmanager.olio;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.MemoryReader;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelNames;

public class StatisticsUtil {
	public static final Logger logger = LogManager.getLogger(StatisticsUtil.class);
	private static SecureRandom rand = new SecureRandom();
	private static final StatisticRule[] statistics = new StatisticRule[]{
		new StatisticRule("physicalStrength"), new StatisticRule("physicalEndurance"), new StatisticRule("manualDexterity"), new StatisticRule("agility"), new StatisticRule("speed"), new StatisticRule("mentalStrength"),
		new StatisticRule("mentalEndurance"), new StatisticRule("intelligence"), new StatisticRule("wisdom"), new StatisticRule("charisma"), new StatisticRule("creativity"), new StatisticRule("spirituality"), new StatisticRule("luck")
		, new StatisticRule("perception")
	};
	
	public static void rollStatistics(BaseRecord rec) {
		rollStatistics(rec, 0);
	}
	public static void rollStatistics(BaseRecord rec, int age) {
		if(!rec.inherits(ModelNames.MODEL_CHAR_STATISTICS)) {
			logger.error("Record is not a statistics record");
			return;
		}
		// IOSystem.getActiveContext().getReader().populate(rec);
		
		/// Every statistic receives a minimum of 1
		///  - (statistics.length * Rules.INITIAL_MINIMUM_STATISTIC)
		int allotment = Rules.INITIAL_STATISTICS_ALLOTMENT;
		int maxStat = Rules.MAXIMUM_STATISTIC;
		if(age > 0 && age < 13) {
			allotment = Rules.INITIAL_STATISTICS_ALLOTMENT_CHILD;
			maxStat = 10;
		}
		

		int total = 0;
		try {
			List<StatisticRule> slist = Arrays.asList(statistics);
			Collections.shuffle(slist);
			for(StatisticRule stat : slist) {
				int max = Math.max(Math.min(allotment, maxStat), 1);
				int rstat = Math.max(rand.nextInt(max), 1) + Rules.INITIAL_MINIMUM_STATISTIC;
				allotment -= rstat;
				total += rstat;
				rec.set(stat.getName(), (rstat * stat.getMultiplier()));
			}
			// logger.info("Allotted: " + total + " / Remainder: " + allotment);
			if(allotment > 0) {
				rec.set("potential", allotment);
			}
			
			/// Invoke inspect to perform any calculations on virtual fields
			///
			(new MemoryReader()).inspect(rec);
		}
		catch(ModelNotFoundException | FieldException | ValueException | ReaderException e) {
			logger.error(e);
		}
		
	}
	
	public static void computeSum(BaseRecord model, FieldSchema field, String[] fields) throws ValueException, FieldException, ModelNotFoundException {
		model.set(field.getName(), getSum(model, fields));
	}

	public static int getSum(BaseRecord model, String[] fields) {
		int val = 0;
		for(String f : fields) {
			val += (int)model.get(f);
		}
		return val;
	}
	
	public static void computeAverage(BaseRecord model, FieldSchema field, String[] fields) throws ValueException, FieldException, ModelNotFoundException {
		model.set(field.getName(), getAverage(model, fields));
	}
	public static int getMaximumInt(BaseRecord model, String[] fields) {
		int val = 0;
		Optional<Integer> opt = Arrays.stream(fields).map(s -> (int)model.get(s)).max(Comparator.naturalOrder());
		if(opt.isPresent()) {
			val = opt.get();
		}
		return val;
	}
	public static int getMinimumInt(BaseRecord model, String[] fields) {
		int val = 0;
		Optional<Integer> opt = Arrays.stream(fields).map(s -> (int)model.get(s)).min(Comparator.naturalOrder());
		if(opt.isPresent()) {
			val = opt.get();
		}
		return val;
	}
	public static int getAverage(BaseRecord model, String[] fields) {
		int val = 0;
		int avg = 0;
		for(String f : fields) {
			if(model.hasField(f)) {
				val += (int)model.get(f);
			}
			else {
				logger.error("Field is missing: " + model.getModel() + "." + f);
			}
		}
		if(val > 0) {
			// logger.info("Compute average: " + val + " / " + fields.length);
			avg = val / fields.length;
		}
		return avg;
	}
	
	public static double getDblAverage(BaseRecord model, String[] fields) {
		double val = 0;
		double avg = 0;
		for(String f : fields) {
			if(model.hasField(f)) {
				val += (double)model.get(f);
			}
			else {
				logger.error("Field is missing: " + model.getModel() + "." + f);
			}
		}
		if(val > 0) {
			// logger.info("Compute average: " + val + " / " + fields.length);
			avg = val / fields.length;
		}
		return avg;
	}
	
	public static void addDouble(BaseRecord model, String fieldName, double val) {
		double cval = model.get(fieldName);
		double mval = val + cval;
		FieldSchema fs = RecordFactory.getSchema(model.getModel()).getFieldSchema(fieldName);
		if(fs.isValidateRange()) {
			if(mval > fs.getMaxValue()) mval = fs.getMaxValue();
			if(mval < fs.getMinValue()) mval = fs.getMinValue();
		}
		try {
			model.set(fieldName, mval);
		} catch (FieldException | ValueException | ModelNotFoundException e) {
			logger.error(e);
		}
		
		
	}
}

class StatisticRule{
	private String name = null;
	private int multiplier = 0;
	public StatisticRule(String name){
		this(name, 1);
	}
	public StatisticRule(String name, int mult){
		this.name = name;
		this.multiplier = mult;
	}
	public String getName() {
		return name;
	}
	public int getMultiplier() {
		return multiplier;
	}
}
