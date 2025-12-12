package org.cote.accountmanager.console.actions;

import java.util.ArrayList;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.io.stream.StreamSegmentUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.GroupEnumType;
import org.cote.accountmanager.util.ByteModelUtil;
import org.cote.accountmanager.util.ContentTypeUtil;
import org.cote.accountmanager.util.FileUtil;

public class ExportAction extends CommonAction {
	private String exportPath = "./export";
	@Override
	public void handleCommand(CommandLine cmd) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void handleCommand(CommandLine cmd, BaseRecord user) {
		// TODO Auto-generated method stub
		if(cmd.hasOption("export") && cmd.hasOption(FieldNames.FIELD_TYPE)) {
			int exp = 0;
			ModelSchema ms = RecordFactory.getSchema(cmd.getOptionValue(FieldNames.FIELD_TYPE));
			if(ms == null) {
				logger.error("Invalid model type: " + cmd.getOptionValue(FieldNames.FIELD_TYPE));
				return;
			}
			if(cmd.hasOption(FieldNames.FIELD_PATH)) {
				if(ms.inherits(ModelNames.MODEL_DIRECTORY)) {
			
					logger.info("Export directory");
					BaseRecord rec = IOSystem.getActiveContext().getPathUtil().findPath(user, ModelNames.MODEL_GROUP, cmd.getOptionValue(FieldNames.FIELD_PATH), GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID));
					if(rec == null) {
						logger.error("Invalid path");
					}
					try {
						exp = exportGroup(cmd, user, rec, cmd.getOptionValue(FieldNames.FIELD_TYPE), cmd.hasOption("recurse"));

					} catch (ReaderException | ValueException | FieldException e) {
						logger.error(e);
					}
					
				}
			}
			logger.info("Exported " + exp + " object(s)");
		}
	}
	
	private int exportGroup(CommandLine cmd, BaseRecord user, BaseRecord dir, String model, boolean recurse) throws ReaderException, ValueException, FieldException {
		int total = 0;
		if(recurse) {
			Query q = QueryUtil.createQuery(ModelNames.MODEL_GROUP, FieldNames.FIELD_PARENT_ID, dir.get(FieldNames.FIELD_ID));
			QueryResult qr = IOSystem.getActiveContext().getSearch().find(q);
			for(BaseRecord c: qr.getResults()) {
				total += exportGroup(cmd, user, c, model, recurse);
			}
		}
		Query eq = QueryUtil.createQuery(model, FieldNames.FIELD_GROUP_ID, dir.get(FieldNames.FIELD_ID));
		QueryResult qr = IOSystem.getActiveContext().getSearch().find(eq);
		total += qr.getResults().length;
		for(BaseRecord o : qr.getResults()) {
			exportObject(cmd, user, model, o, dir.get(FieldNames.FIELD_PATH));
		}
		return total;
	}
	
	private void exportObject(CommandLine cmd, BaseRecord user, String model, BaseRecord xobject, String path) throws ValueException, FieldException {
		Query eq = QueryUtil.createQuery(model, FieldNames.FIELD_OBJECT_ID, xobject.get(FieldNames.FIELD_OBJECT_ID));
		eq.planMost(true, new ArrayList<>());
		BaseRecord object = IOSystem.getActiveContext().getSearch().findRecord(eq);

		String upath = exportPath + path;
		String fspath = upath + "/" + object.get(FieldNames.FIELD_NAME);
		FileUtil.makePath(upath);
		if(cmd.hasOption("extract")) {
			if(model.equals(ModelNames.MODEL_DATA)) {
				byte[] value = new byte[0];
				if (object.hasField(FieldNames.FIELD_STREAM) && object.get(FieldNames.FIELD_STREAM) != null) {
					BaseRecord stream = object.get(FieldNames.FIELD_STREAM);
					StreamSegmentUtil ssu = new StreamSegmentUtil();
					value = ssu.streamToEnd(stream.get(FieldNames.FIELD_OBJECT_ID), 0, 0);
				} else {
					value = ByteModelUtil.getValue(object);
				}
				
				String fpath = fspath;
				if(fpath.indexOf(".") == -1) fpath =  fspath + "." + ContentTypeUtil.getExtensionFromType(object.get(FieldNames.FIELD_CONTENT_TYPE));
				FileUtil.emitFile(fpath, value);
			}
		}
		else if(!model.equals(ModelNames.MODEL_DATA)) {
			FileUtil.emitFile(fspath, object.toFullString());
		}
	}

	@Override
	public void addOptions(Options options) {
		// TODO Auto-generated method stub
		options.addOption(FieldNames.FIELD_TYPE, true, "Model type");
		options.addOption("extract", false, "Bit to indicate extracting contained data");
		options.addOption("recurse", false, "Bit to indicate recursing through child groups");
		//options.addOption("exportPath", true, "Location to export data");
		
	}

}
