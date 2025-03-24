package org.cote.accountmanager.tools;

public class Status {
	private boolean status = false;
	private long time = 0L;
	
	public Status() {
		
	}

	public boolean isStatus() {
		return status;
	}

	public void setStatus(boolean status) {
		this.status = status;
	}

	public long getTime() {
		return time;
	}

	public void setTime(long time) {
		this.time = time;
	}
	
	
}
