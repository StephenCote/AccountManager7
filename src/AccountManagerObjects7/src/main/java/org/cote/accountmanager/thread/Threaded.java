package org.cote.accountmanager.thread;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FactoryException;

public abstract class Threaded implements Runnable {
	public static final Logger logger = LogManager.getLogger(Threaded.class);
	private int threadDelay = 1000;
	private boolean stopRequested=false;
	private Thread svcThread = null;
	
	public Threaded(){
		svcThread = new Thread(this);
		svcThread.setPriority(Thread.MIN_PRIORITY);
		svcThread.start();
	}

	public int getThreadDelay() {
		return threadDelay;
	}

	public void setThreadDelay(int threadDelay) {
		this.threadDelay = threadDelay;
	}

	public void requestStop(){
		stopRequested=true;
		svcThread.interrupt();
		try{
			execute();
		}
		catch(Exception e){
			logger.error(e);
		}
		
	}
	
	public void execute(){
		
	}
	
	@Override
	public void run(){
		while (!stopRequested){
			try{
				Thread.sleep(threadDelay);
			}
			catch (InterruptedException ex){
				/* ... */
			}
			try{
				execute();
			}
			catch(Exception e){
				logger.error(e);
			}
		}
	}

}
