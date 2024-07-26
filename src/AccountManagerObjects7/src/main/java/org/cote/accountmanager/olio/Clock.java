package org.cote.accountmanager.olio;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.record.BaseRecord;

public class Clock {
	public static final Logger logger = LogManager.getLogger(Clock.class);
	
	
	private ZonedDateTime start = null;
	private ZonedDateTime current = null;
	private ZonedDateTime end = null;
	private BaseRecord event = null;
	private int cycle = 0;
	
	public Clock(ZonedDateTime start, ZonedDateTime current, ZonedDateTime end) {
		this.start = start;
		this.current = current;
		this.end = end;
	}
	
	public Clock(BaseRecord evt) {
		this(evt.get("eventStart"), evt.get("eventProgress"), evt.get("eventEnd"));
		this.event = event;
	}
	public int getCycle() {
		return cycle;
	}
	public void addCycle() {
		cycle++;
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
}
