package org.cote.accountmanager.olio;

import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ResponseEnumType;
import org.cote.accountmanager.schema.type.SystemTaskEnumType;
import org.cote.accountmanager.util.SystemTaskUtil;

public class OlioTaskAgent  {
	public static final Logger logger = LogManager.getLogger(OlioTaskAgent.class);
	
	public static BaseRecord evaluateTaskResponse(BaseRecord request) {
		BaseRecord tr = null;
		ResponseEnumType ret = ResponseEnumType.UNKNOWN;
		try{
			tr = RecordFactory.newInstance(ModelNames.MODEL_TASK_RESPONSE);
			tr.setValue("id", request.get("id"));
			ret = ResponseEnumType.PENDING;
			SystemTaskEnumType type = request.getEnum("type");
			switch(type) {
				case CHAT:
					BaseRecord config = request.get("data");
					Chat chat = new Chat(null, config, null);
					BaseRecord req = request.get("taskModel");
					if(req != null) {
						OpenAIResponse resp = chat.chat(new OpenAIRequest(req));
						if(resp != null) {
							ret = ResponseEnumType.INFO;
							tr.setValue("taskModelType", resp.getSchema());
							tr.setValue("taskModel", resp);
						}
						else {
							ret = ResponseEnumType.INVALID;
						}
					}
					else {
						logger.error("Null request");
						ret = ResponseEnumType.INVALID;
					}
					break;
				case SD:
					break;
				default:
					logger.error("Unknown task type for id " + request.get("id"));
					ret = ResponseEnumType.INVALID;
					break;
				}
		}
		catch(Exception e) {
			logger.error(e);
			e.printStackTrace();
			ret = ResponseEnumType.INVALID;
			
		}
		tr.setValue("responseType", ret);
		return tr;		
	}
	
	public static BaseRecord createTaskRequest(OpenAIRequest req, BaseRecord config) {
		BaseRecord tr = null;
		try{
			tr = RecordFactory.newInstance(ModelNames.MODEL_TASK_REQUEST);
		
			tr.setValue("taskModelType", req.getSchema());
			tr.setValue("taskModel", req);
			tr.setValue("dataType", config.getSchema());
			tr.setValue("data", config);
			tr.setValue("type", SystemTaskEnumType.CHAT);
			tr.setValue("id", UUID.randomUUID().toString());
		}
		catch(ModelNotFoundException | FieldException e) {
			logger.error(e);
		}
		return tr;
	}
	
	public static BaseRecord executeTask(BaseRecord task) {
		SystemTaskUtil.pendTask(task);
		BaseRecord resp = null;
		long maxTimeMS = 300000;
		long waited = 0L;
		long delay = 3000;
		while(resp == null) {
			if(waited >= maxTimeMS) {
				logger.error("Exceeded maximum wait threshhold");
				break;
			}
			resp = SystemTaskUtil.getResponse(task.get("id"));
			//logger.info("Checking status of " + task.get("id"));
			//SystemTaskUtil.dumpTasks();
			if(resp == null) {
				try{
					Thread.sleep(delay);
					waited += delay;
				}
				catch (InterruptedException ex){
					/* ... */
				}				
			}
		}
		return resp;
	}
}
