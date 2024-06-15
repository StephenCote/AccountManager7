package org.cote.accountmanager.console.actions;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Options;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.Query;
import org.cote.accountmanager.io.QueryResult;
import org.cote.accountmanager.io.QueryUtil;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordFactory;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.ModelSchema;
import org.cote.accountmanager.schema.type.GroupEnumType;
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
		if(cmd.hasOption("export") && cmd.hasOption("type")) {
			int exp = 0;
			ModelSchema ms = RecordFactory.getSchema(cmd.getOptionValue("type"));
			if(ms == null) {
				logger.error("Invalid model type: " + cmd.getOptionValue("type"));
				return;
			}
			if(cmd.hasOption("path")) {
				if(ms.inherits(ModelNames.MODEL_DIRECTORY)) {
			
					logger.info("Export directory");
					BaseRecord rec = IOSystem.getActiveContext().getPathUtil().findPath(user, ModelNames.MODEL_GROUP, cmd.getOptionValue("path"), GroupEnumType.DATA.toString(), user.get(FieldNames.FIELD_ORGANIZATION_ID));
					if(rec == null) {
						logger.error("Invalid path");
					}
					try {
						exp = exportGroup(cmd, user, rec, cmd.getOptionValue("type"), cmd.hasOption("recurse"));

					} catch (ReaderException e) {
						logger.error(e);
					}
					
				}
			}
			logger.info("Exported " + exp + " object(s)");
		}
	}
	
	private int exportGroup(CommandLine cmd, BaseRecord user, BaseRecord dir, String model, boolean recurse) throws ReaderException {
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
	
	private void exportObject(CommandLine cmd, BaseRecord user, String model, BaseRecord object, String path) {
		IOSystem.getActiveContext().getReader().populate(object);
		String upath = exportPath + path;
		String fspath = upath + "/" + object.get(FieldNames.FIELD_NAME);
		FileUtil.makePath(upath);
		if(cmd.hasOption("extract")) {
			if(model.equals(ModelNames.MODEL_DATA)) {
				String fpath = fspath + "." + ContentTypeUtil.getExtensionFromType(object.get(FieldNames.FIELD_CONTENT_TYPE));
				byte[] data = object.get(FieldNames.FIELD_BYTE_STORE);
				FileUtil.emitFile(fpath, data);
			}
		}
	}

	@Override
	public void addOptions(Options options) {
		// TODO Auto-generated method stub
		options.addOption("type", true, "Model type");
		options.addOption("extract", false, "Bit to indicate extracting contained data");
		options.addOption("recurse", false, "Bit to indicate recursing through child groups");
		//options.addOption("exportPath", true, "Location to export data");
		
	}

}
