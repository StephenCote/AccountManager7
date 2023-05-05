package org.cote.accountmanager.util;

import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.record.BaseRecord;

public class TypeUtil {
	public static final Logger logger = LogManager.getLogger(TypeUtil.class);
	public static <Y,T> List<T> convertList( List<Y> inList)  
	{  
		List<T> outList = new ArrayList<>();
		int len = inList.size();
		try{
	    for (int i = 0; i < len; i++)
	    {  
	    	outList.add((T)inList.get(i));
	    } 
		}
		catch(ClassCastException cce){
			logger.error(cce.getMessage());
		}
		return outList;
	}
	
	public static <Y,T> List<T> convertRecordList(List<Y> inList)  
	{  
		List<T> outList = new ArrayList<>();
		int len = inList.size();
		try{
	    for (int i = 0; i < len; i++)
	    {  
	    	outList.add(((BaseRecord)inList.get(i)).toConcrete());
	    } 
		}
		catch(ClassCastException cce){
			logger.error(cce.getMessage());
		}
		return outList;
	}
	
	public static <Y,T> List<T> convertRecordList(List<Y> inList, Class<?> cls)  
	{  
		List<T> outList = new ArrayList<>();
		int len = inList.size();
		try{
	    for (int i = 0; i < len; i++)
	    {  
	    	outList.add(((BaseRecord)inList.get(i)).toConcrete(cls));
	    } 
		}
		catch(ClassCastException cce){
			logger.error(cce.getMessage());
		}
		return outList;
	}
}
