package org.cote.accountmanager.olio;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;

public class Clock {
	public static final Logger logger = LogManager.getLogger(Clock.class);
	
	private ZonedDateTime start = null;
	private ZonedDateTime end = null;
	private ZonedDateTime current = null;
	
	/// For olio, an epoch is 1 year
	private BaseRecord epoch = null;
	/// For olio realms, an event is 1 year
	private BaseRecord event = null;
	/// For olio realms, an increment is 1 hour
	private BaseRecord increment = null;
	
	private BaseRecord realm = null;
	
	private Clock parent = null;
	
	private Map<Long, Clock> realmClocks = new ConcurrentHashMap<>();
	
	private int cycle = 0;
	private ChronoUnit cycleUnit = ChronoUnit.SECONDS;
	
	public Clock(ZonedDateTime start, ZonedDateTime current, ZonedDateTime end) {
		this.start = start;
		this.current = current;
		this.end = end;
	}
	

	public Clock(BaseRecord epoch) {
		setEpoch(epoch);
	}
	public Clock(BaseRecord epoch, BaseRecord root) {
		setEpoch(epoch);
		if(epoch == null) {
			setTime(root);
		}
	}
	public Clock(Clock parent) {
		this(parent.getEpoch());
		this.parent = parent;
	}
	
	public void untap() {
		event = null;
		increment = null;
		realm = null;
	}
	
	public void tap() throws ClockException {
		if(parent != null) {
			parent.setEvent(event);
			parent.setIncrement(increment);
			parent.setRealm(realm);
		}
	}

	public BaseRecord getRealm() {
		return realm;
	}


	public void setRealm(BaseRecord realm) {
		this.realm = realm;
	}


	public Clock realmClock(BaseRecord realm) throws ClockException {
		long rid = realm.get(FieldNames.FIELD_ID);
		if(!realmClocks.containsKey(rid)) {
			Clock rc = new Clock(this);
			rc.setRealm(realm);
			BaseRecord currInc = realm.get(OlioFieldNames.FIELD_CURRENT_INCREMENT);
			if(currInc != null) {
				IOSystem.getActiveContext().getReader().populate(currInc, EventUtil.EVENT_QUERY_FIELDS);
			}
			
			BaseRecord currEvt = realm.get(OlioFieldNames.FIELD_CURRENT_EVENT);
			if(currEvt != null) {
				IOSystem.getActiveContext().getReader().populate(currEvt, EventUtil.EVENT_QUERY_FIELDS);
			}
			rc.setEvent(currEvt);
			rc.setIncrement(currInc);
			realmClocks.put(rid, rc);
		}
		Clock rc = realmClocks.get(rid);
		rc.tap();
		return rc;
	}
	
	public BaseRecord getEpoch() {
		return epoch;
	}

	public void setEpoch(BaseRecord epoch) {
		this.epoch = epoch;
		setTime(epoch);
	}
	
	private void setTime(BaseRecord evt) {
		if(evt != null) {
			this.start = evt.get(OlioFieldNames.FIELD_EVENT_START);
			this.current = evt.get(OlioFieldNames.FIELD_EVENT_PROGRESS);
			this.end = evt.get(OlioFieldNames.FIELD_EVENT_END);
			if(start.until(current, ChronoUnit.SECONDS) < 0) {
				logger.warn("Progress before start");
				logger.warn(evt.toFullString());
			}
		}
	}
	
	public void setEvent(BaseRecord event) throws ClockException {
		this.event = event;
		if(event != null) {
			this.current = event.get(OlioFieldNames.FIELD_EVENT_PROGRESS);
		}
		tap();
	}

	public BaseRecord getEvent() {
		return event;
	}


	public BaseRecord getIncrement() {
		return increment;
	}

	public void setIncrement(BaseRecord increment) throws ClockException {
		this.increment = increment;
		if(increment != null) {
			checkClockSkew(increment);
			this.current = increment.get(OlioFieldNames.FIELD_EVENT_PROGRESS);

		}
		tap();
	}
	
	private void checkClockSkew(BaseRecord evt) throws ClockException {
		if (evt != null) {
			if(start.until(increment.get(OlioFieldNames.FIELD_EVENT_PROGRESS), ChronoUnit.SECONDS) < 0) {
				throw new ClockException("Event progress is chronologically before event start for event #" + increment.get(FieldNames.FIELD_ID));
			}
		}
	}

	public int getCycle() {
		return cycle;
	}
	public void addCycle() {
		cycle++;
	}
	
	public void addMilliseconds(long sec) {
		current = current.plus(sec, ChronoUnit.MILLIS);
	}
	
	public void addSeconds(long sec) {
		current = current.plusSeconds(sec);
	}
	public void addMinutes(long sec) {
		current = current.plusMinutes(sec);
	}
	public long remainingSeconds() {
		return current.until(end, ChronoUnit.SECONDS);
	}
	public long remainingMinutes() {
		return current.until(end, ChronoUnit.MINUTES);
	}
	public long remainingHours() {
		return current.until(end, ChronoUnit.HOURS);
	}
	public long remainingDays() {
		return current.until(end, ChronoUnit.DAYS);
	}


	public ZonedDateTime getStart() {
		return start;
	}


	public ZonedDateTime getEnd() {
		return end;
	}


	public ZonedDateTime getCurrent() {
		return current;
	}
	
	
	
}
