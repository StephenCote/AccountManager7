package org.cote.accountmanager.io;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.analysis.FaceAnalysis;
import org.cote.accountmanager.analysis.FaceRequest;
import org.cote.accountmanager.analysis.FaceResponse;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.ResponseEnumType;
import org.cote.accountmanager.schema.type.SystemTaskEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.SystemTaskUtil;

public class SystemTaskAgent {
	public static final Logger logger = LogManager.getLogger(SystemTaskAgent.class);
	public static BaseRecord evaluateTaskResponse(BaseRecord request) {
		BaseRecord tr = null;
		ResponseEnumType ret = ResponseEnumType.UNKNOWN;
		try{
			tr = RecordFactory.newInstance(ModelNames.MODEL_TASK_RESPONSE);
			tr.setValue("id", request.get("id"));
			ret = ResponseEnumType.PENDING;
			SystemTaskEnumType type = request.getEnum("type");
			
			if(SystemTaskEnumType.PYTHON_FACE == type) {
				FaceRequest req = JSONUtil.importObject(request.get("taskModelData"), FaceRequest.class);

				if(req != null) {
					FaceResponse resp = FaceAnalysis.postFaceRequest(req, "http://localhost:8003", "analyze");
					if(resp != null) {
						ret = ResponseEnumType.INFO;
						tr.setValue("taskModelData", JSONUtil.exportObject(resp));
					}
					else {
						ret = ResponseEnumType.INVALID;
					}
				}
				else {
					logger.error("Null request");
					ret = ResponseEnumType.INVALID;
				}
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
	
	public static BaseRecord executeTask(BaseRecord task) {
		SystemTaskUtil.pendTask(task);
		BaseRecord resp = null;
		long maxTimeMS = 600000;
		long waited = 0L;
		long delay = 3000;
		while(resp == null) {
			if(waited >= maxTimeMS) {
				logger.error("Exceeded maximum wait threshhold");
				break;
			}
			resp = SystemTaskUtil.getResponse(task.get("id"));
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
