package org.cote.accountmanager.analysis;

import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.SystemTaskEnumType;
import org.cote.accountmanager.util.ClientUtil;
import org.cote.accountmanager.util.JSONUtil;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.MediaType;

public class FaceAnalysis {
	public static final Logger logger = LogManager.getLogger(FaceAnalysis.class);
	// TODO: Make this configurable; I'm still experimenting with different face models, so the object model will likely change
	///
	public static synchronized FaceResponse postFaceRequest(FaceRequest req, String server, String apiName) {

		FaceResponse frep = new FaceResponse();

		// logger.info("Posting face request to " + server + "/" + apiName + "/");
		try {
			long start = System.currentTimeMillis();
			FaceResult fres = ClientUtil.post(FaceResult.class, ClientUtil.getResource(server + "/" + apiName + "/"), null, req, MediaType.APPLICATION_JSON_TYPE);
			long stop = System.currentTimeMillis();
			if(fres != null) {
				frep.setMessage("Analyzed face in " + (stop - start) + "ms");
				frep.getResults().add(fres);
			}
			else {
				frep.setMessage("Failed to analyze face");
			}

		}
		catch(ProcessingException e) {
			logger.error(e);
			frep.setMessage("Error analyzing face: " + e.getMessage());
		}
		return frep;
	}
	
	public static BaseRecord createTaskRequest(FaceRequest req) {
		BaseRecord tr = null;
		try{
			tr = RecordFactory.newInstance(ModelNames.MODEL_TASK_REQUEST);
		
			tr.setValue("taskModelData", JSONUtil.exportObject(req));
			tr.setValue("type", SystemTaskEnumType.PYTHON_FACE);
			tr.setValue("id", UUID.randomUUID().toString());
		}
		catch(ModelNotFoundException | FieldException e) {
			logger.error(e);
		}
		return tr;
	}
}
