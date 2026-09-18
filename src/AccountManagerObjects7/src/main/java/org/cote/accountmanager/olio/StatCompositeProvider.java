package org.cote.accountmanager.olio;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.provider.ComputeProvider;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordOperation;
import org.cote.accountmanager.schema.FieldSchema;
import org.cote.accountmanager.schema.ModelSchema;

/// Compute provider for the composite statistics on olio.statistics - the derived appearance chain
/// (wit, charm, mentalHealth, beauty).
///
/// These are declared SAVG rather than AVG because a nested chain of plain means collapses toward its
/// centre: 'beauty' averages five terms, three of which are means themselves, so it drew on eleven
/// base statistics and carried roughly a fifth of their spread. Measured over 20k rolled characters it
/// never left 3-14 of its declared 0-20 range, and four of the seven narrative labels keyed to it
/// (pretty, beautiful, gorgeous, and effectively hideous) could not be produced at any allocation.
///
/// SAVG restores that spread by amplifying each level's distance from a centre, which makes the centre
/// load-bearing. The scale midpoint is the wrong centre here: rollStatistics spreads a fixed budget
/// (Rules.INITIAL_STATISTICS_ALLOTMENT) across StatisticsUtil.getBaseStatisticNames(), which pins the
/// mean base statistic near 9.3 for an adult - and near 4.6 for a child, who rolls half the budget.
/// Centring on 10 amplifies that offset at every level; measured, it biased adults about 1.5 low and
/// made 100% of children hideous.
///
/// So the centre is the character's own mean base statistic. That keeps the transform honest in both
/// directions: it is the identity when a character's appearance statistics match their overall level
/// (all base statistics at v gives beauty v), and it reads a character against their own budget rather
/// than against an adult's.
///
/// Note physicalAppearance is deliberately NOT one of these. It is a plain mean, because the body shape
/// classifier reads it for the female HOURGLASS score and widening it made the INVERTED_TRIANGLE
/// midpoint profile classify as HOURGLASS (pinned by TestBodyStats#TestUxBodyShapeMidpointsStillClassify).
/// It makes no measurable difference to beauty's spread either way.
public class StatCompositeProvider extends ComputeProvider {
	public static final Logger logger = LogManager.getLogger(StatCompositeProvider.class);

	/// Skip entirely unless every base statistic is on the record.
	///
	/// The centre is an average over the base statistics, so unlike a plain AVG - which can only ever
	/// depend on its own declared inputs - a SAVG composite computed from a partial record would depend
	/// on which fields the caller happened to project. Measured before this guard: the same stored
	/// character returned wit=17/charm=18 under a full projection and wit=16/charm=16 under a narrow
	/// one, silently. A composite that changes with the projection is worse than an absent one, so this
	/// mirrors what ComputeProvider already does for missing declared inputs and leaves the field unset.
	@Override
	public void provide(BaseRecord contextUser, RecordOperation operation, ModelSchema lmodel, BaseRecord model, FieldSchema lfield, FieldType field) throws ModelException, FieldException, ValueException, ModelNotFoundException {
		if(RecordOperation.READ.equals(operation) || RecordOperation.INSPECT.equals(operation)) {
			String missing = firstMissingBaseStatistic(model);
			if(missing != null) {
				logger.debug("Skipping " + model.getSchema() + "." + lfield.getName()
					+ ": base statistic " + missing + " is not on the record, so the composite centre"
					+ " would depend on the projection");
				return;
			}
		}
		super.provide(contextUser, operation, lmodel, model, lfield, field);
	}

	private String firstMissingBaseStatistic(BaseRecord model) {
		for(String f : StatisticsUtil.getBaseStatisticNames()) {
			if(!model.hasField(f)) {
				return f;
			}
		}
		return null;
	}

	@Override
	protected double getSpreadCenter(ModelSchema lmodel, BaseRecord model, FieldSchema lfield) {
		String[] names = StatisticsUtil.getBaseStatisticNames();
		if(names.length == 0 || firstMissingBaseStatistic(model) != null) {
			/// provide() already refuses this case; defend the direct-call path rather than inventing
			/// a centre from a partial record.
			return super.getSpreadCenter(lmodel, model, lfield);
		}
		int sum = 0;
		for(String f : names) {
			sum += (int)model.get(f);
		}
		return (double)sum / names.length;
	}
}
