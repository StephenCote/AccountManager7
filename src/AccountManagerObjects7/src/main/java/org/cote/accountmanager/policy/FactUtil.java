/*******************************************************************************
 * Copyright (C) 2002, 2020 Stephen Cote Enterprises, LLC. All rights reserved.
 * Redistribution without modification is permitted provided the following conditions are met:
 *
 *    1. Redistribution may not deviate from the original distribution,
 *        and must reproduce the above copyright notice, this list of conditions
 *        and the following disclaimer in the documentation and/or other materials
 *        provided with the distribution.
 *    2. Products may be derived from this software.
 *    3. Redistributions of any form whatsoever must retain the following acknowledgment:
 *        "This product includes software developed by Stephen Cote Enterprises, LLC"
 *
 * THIS SOFTWARE IS PROVIDED BY STEPHEN COTE ENTERPRISES, LLC ``AS IS''
 * AND ANY EXPRESSED OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO,
 * THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THIS PROJECT OR ITS CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS
 * OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, 
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY 
 * OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *******************************************************************************/
package org.cote.accountmanager.policy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.cote.accountmanager.exceptions.FieldException;
import org.cote.accountmanager.exceptions.IndexException;
import org.cote.accountmanager.exceptions.ModelNotFoundException;
import org.cote.accountmanager.exceptions.ReaderException;
import org.cote.accountmanager.exceptions.ScriptException;
import org.cote.accountmanager.exceptions.ValueException;
import org.cote.accountmanager.io.IOSystem;
import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.model.field.FieldEnumType;
import org.cote.accountmanager.model.field.FieldFactory;
import org.cote.accountmanager.model.field.FieldType;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.record.RecordSerializerConfig;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.ModelNames;
import org.cote.accountmanager.schema.type.FactEnumType;
import org.cote.accountmanager.schema.type.FunctionEnumType;
import org.cote.accountmanager.util.JSONUtil;
import org.cote.accountmanager.util.RecordUtil;
import org.cote.accountmanager.util.ResourceUtil;
import org.cote.accountmanager.util.ScriptUtil;

public class FactUtil {
	public static final Logger logger = LogManager.getLogger(FactUtil.class);
	public static final Pattern idPattern = Pattern.compile("^\\d+$");
	private IReader reader = null;
	private ISearch search = null;
	//private IPath path = null;
	public FactUtil(IReader reader, ISearch search){
		this.reader = reader;
		this.search = search;
		//path = IOSystem.getActiveContext().getPathUtil();
			//IOFactory.getPathUtil(reader);
	}
	
	public static BaseRecord getParameter(BaseRecord fact, String name) {
		List<BaseRecord> fparams = fact.get(FieldNames.FIELD_PARAMETERS);
		return getParameter(fparams, name);
	}
	
	public static BaseRecord getParameter(List<BaseRecord> factParams, String name) {
		Optional<BaseRecord> param = factParams.stream().filter(p -> name.equals(p.get(FieldNames.FIELD_NAME))).findFirst();
		return (param.isPresent() ? param.get() : null);
	}
	public static BaseRecord getParameterValue(BaseRecord fact, String name) {
		List<BaseRecord> fparams = fact.get(FieldNames.FIELD_PARAMETERS);
		return getParameterValue(fparams, name);
	}
	public static <T> T getParameterValue(List<BaseRecord> factParams, String name) {
		T obj = null;
		BaseRecord parm = getParameter(factParams, name);
		if(parm != null) {
			obj = parm.get("value");
			if(obj == null) {
				logger.warn("Null value for parameter: " + name);
				logger.warn(parm.toFullString());
			}
		}
		else {
			logger.warn("Null parameter: " + name);
		}
		return obj;
	}

	public void setFactReference(BaseRecord contextUser, BaseRecord sourceFact, BaseRecord matchFact) throws FieldException, ValueException, ModelNotFoundException{
		if(sourceFact.get("factReference") != null) return;
		
		BaseRecord obj = recordRead(contextUser, sourceFact, matchFact);
		if(obj == null){
			return;
		}
		sourceFact.set("factReference", obj);
	}

	public String getFactAttributeValue(BaseRecord contextUser, BaseRecord sourceFact, BaseRecord matchFact) throws FieldException, ValueException, ModelNotFoundException{
		setFactReference(contextUser, sourceFact, matchFact);
		BaseRecord frec = sourceFact.get("factReference");
		if(frec == null)
			return null;
		
		if(!frec.hasField(FieldNames.FIELD_ATTRIBUTES)) {
			logger.error("Need to populate/extend field - attributes");
			return null;
		}
		List<BaseRecord> attrs = frec.get(FieldNames.FIELD_ATTRIBUTES);
		String matchName = matchFact.get(FieldNames.FIELD_SOURCE_URN);
		List<BaseRecord> attrs2 = attrs.stream().filter(o -> {
			String n1 = o.get(FieldNames.FIELD_NAME);
			return n1.equals(matchName);

		}).collect(Collectors.toList());
		String attrVal = null;
		if(attrs2.size() > 0) {
			attrVal = attrs2.get(0).get(FieldNames.FIELD_VALUE);
		}
		return attrVal;
	}
	public FieldType getFactValue(BaseRecord prt, BaseRecord prr, BaseRecord sourceFact, BaseRecord matchFact) throws FieldException, ValueException, ModelNotFoundException{
		FieldType outVal = null;
		//String outVal = null;
		/// Fact value is driven by a combination of what the source fact has and what  the matchFact expects
		/// The source fact provides context, and the match fact provides specificity
		///
		FactEnumType fet = FactEnumType.valueOf(matchFact.get(FieldNames.FIELD_TYPE));
		BaseRecord contextUser = prt.get(FieldNames.FIELD_CONTEXT_USER);
		switch(fet){
			case STATIC:
			case FUNCTION:
				//outVal = sourceFact.get("factData");
				outVal = FieldFactory.fieldByType(FieldEnumType.STRING, "fact", sourceFact.get("factData"));
				break;
			case PROPERTY:
				
				String prop = matchFact.get("propertyName");
				if(sourceFact.getEnum("valueType") == FieldEnumType.MODEL) {
					BaseRecord srec = sourceFact.get("factReference");
					if(prop != null && srec != null) {
						//outVal = srec.get(prop);
						outVal = FieldFactory.fieldByType(matchFact.getEnum("valueType"), "fact", srec.get(prop));
					}
					else {
						logger.warn("Property (" + prop + ") or factReference (" + srec + ") was null");
						logger.warn(sourceFact.toFullString());
					}
				}
				else {
					logger.warn("Unhandled valueType: " + sourceFact.get("valueType"));
				}
				break;
			case ATTRIBUTE:
				outVal = FieldFactory.fieldByType(FieldEnumType.STRING, "fact", getFactAttributeValue(contextUser, sourceFact, matchFact));
				break;
			default:
				logger.error("Unhandled source fact type: " + fet);
				break;
		}
		return outVal;
	}
	public FieldType getMatchFactValue(BaseRecord prt, BaseRecord prr, BaseRecord sourceFact, BaseRecord matchFact){
		//String outVal = null;
		FieldType outVal = null;
		FactEnumType fet = FactEnumType.valueOf(matchFact.get(FieldNames.FIELD_TYPE));
		switch(fet){
			/// Note: The match of an attribute fact is presently the static value
			/// This is because the source type got cross-purposed to parameter
			case PROPERTY:
				outVal = FieldFactory.fieldByType(matchFact.getEnum("valueType"), "fact", matchFact.get("value"));
				break;
			case ATTRIBUTE:
			case STATIC:
				//outVal = matchFact.get("factData");
				outVal = FieldFactory.fieldByType(FieldEnumType.STRING, "fact", matchFact.get("factData"));
				break;
			case FUNCTION:
				outVal = FieldFactory.fieldByType(FieldEnumType.STRING, "fact", evaluateFunctionFact(String.class, prt, prr, sourceFact, matchFact));
				break;
			default:
				logger.error("Unhandled match fact type: " + fet);
				break;
		}
		return outVal;
	}
	
	public <T> T evaluateFunctionFact(Class<T> cls, BaseRecord prt, BaseRecord prr, BaseRecord fact, BaseRecord matchFact){
		FactEnumType mtype = FactEnumType.valueOf(matchFact.get(FieldNames.FIELD_TYPE));
		if(mtype != FactEnumType.FUNCTION){
			logger.error("Match fact must be a function fact");
			return null;
		}
		T outResponse = null;

		BaseRecord subject = null;
		try {
			if(prt.get(FieldNames.FIELD_SUBJECT) != null){
				/// FieldNames.FIELD_SUBJECT_TYPE), 
				BaseRecord[] subs = search.findByUrn(prt.get(FieldNames.FIELD_SUBJECT_TYPE), prt.get(FieldNames.FIELD_SUBJECT));
				if(subs.length > 0) {
					subject = subs[0];
					String[] fields = RecordUtil.getPossibleFields(subject.getAMModel(), PolicyEvaluator.FIELD_POPULATION);
					reader.populate(subject, fields);
							
				}
			}
			if(prt.get(FieldNames.FIELD_CONTEXT_USER) != null){
				reader.populate(prt.get(FieldNames.FIELD_CONTEXT_USER));
			}
			Map<String,Object> params = ScriptUtil.getCommonParameterMap(prt.get(FieldNames.FIELD_CONTEXT_USER));
			// params.put("contextUser", prt.get("contextUser"));
			params.put("subject", subject);
			params.put("ioReader", reader);
			params.put("ioSearch", search);
			//logger.info(JSONUtil.exportObject(matchFact, RecordSerializerConfig.getUnfilteredModule()));
			String surl = matchFact.get(FieldNames.FIELD_SOURCE_URL);
			String surn = matchFact.get(FieldNames.FIELD_SOURCE_URN);
			byte[] funcdata = matchFact.get("sourceData");
			String furn = fact.get(FieldNames.FIELD_SOURCE_URN);
			BaseRecord factData = null;
			if(furn != null) {
				BaseRecord[] factDatas = search.findByUrn(matchFact.get(FieldNames.FIELD_MODEL_TYPE), furn);
				if(factDatas.length > 0) {
					factData = factDatas[0];
				}
			}
			// logger.info("Data test: " + funcdata.length);
			if((funcdata == null || funcdata.length == 0) && surl != null && surl.length() > 0) {
				if(surl.startsWith("resource:")) {
					surl = surl.replace("resource:", "");
					String recData = ResourceUtil.getInstance().getResource(surl);
					if(recData != null) {
						funcdata = recData.getBytes();
					}
					else {
						logger.error("Failed to load resource: " + surl);
					}
				}
				else {
					logger.error("Remote URL not currently implemented");
				}
			}
			if(funcdata != null && funcdata.length > 0) {
				StringBuilder sbuff = new StringBuilder();
				sbuff.append("let fact = " + JSONUtil.exportObject(fact, RecordSerializerConfig.getFilteredModule()) + ";\n");
				//sbuff.append("let factData = " + JSONUtil.exportObject(factData, RecordSerializerConfig.getFilteredModule()) + ";\n");
				sbuff.append("let responseType = org.cote.accountmanager.schema.type.OperationResponseEnumType;\n");
				sbuff.append("let fieldNames = org.cote.accountmanager.schema.FieldNames;\n");
				sbuff.append("let modelNames = org.cote.accountmanager.schema.ModelNames;\n");
				//sbuff.append("let match = " + JSONUtil.exportObject(matchFact, RecordSerializerConfig.getFilteredModule()) + ";\n");
				sbuff.append(new String(funcdata));
				//logger.info("Running Script:");
				//logger.info(sbuff.toString());
				outResponse = ScriptUtil.run(cls, sbuff.toString(), params);
			}
			else if(surn != null && surn.length() > 0) {
				params.put("fact", fact);
				params.put("match", matchFact);

				BaseRecord func = null;
				BaseRecord[] funcs = search.findByUrn(matchFact.get(FieldNames.FIELD_MODEL_TYPE), surn);
				if(funcs.length > 0) {
					func = funcs[0];
				}
				if(func == null){
					logger.error("Function '" + matchFact.get(FieldNames.FIELD_SOURCE_URN) + "' is null");
					return null;
				}
			
				if(func.get(FieldNames.FIELD_TYPE) == FunctionEnumType.JAVASCRIPT){
					outResponse = ScriptUtil.run(cls, params, func);
				}
				else{
					logger.warn("Intentionally ignoring BeanShell.");
				}
			}
			else {
				logger.error("Missing sourceUrn or sourceData on match fact");
				logger.error(matchFact.toString());
			}
		}
		 catch (ScriptException | ReaderException e) {
				logger.error(e);
			}

		return outResponse;

	}
	
	private BaseRecord getDirectoryFromFact(BaseRecord contextUser, BaseRecord sourceFact, BaseRecord referenceFact) throws ValueException {
		if(sourceFact.get(FieldNames.FIELD_SOURCE_URL) == null){
			logger.error("Source URL is null");
			return null;
		}
		BaseRecord dir = IOSystem.getActiveContext().getPathUtil().findPath(contextUser, ModelNames.MODEL_GROUP, sourceFact.get(FieldNames.FIELD_SOURCE_URL), "DATA", contextUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		if(dir == null) {
			throw new ValueException("Invalid group path " + sourceFact.get(FieldNames.FIELD_SOURCE_URL));
		}
		return dir;
	}
	

	private BaseRecord getRecordFromFactPath(BaseRecord contextUser, String model, BaseRecord sourceFact, BaseRecord referenceFact) throws ValueException {
		BaseRecord permission = IOSystem.getActiveContext().getPathUtil().findPath(contextUser, model, sourceFact.get(FieldNames.FIELD_SOURCE_URL), sourceFact.get(FieldNames.FIELD_SOURCE_TYPE), contextUser.get(FieldNames.FIELD_ORGANIZATION_ID));
		if(permission == null) throw new ValueException("Invalid " + model + " path " + sourceFact.get(FieldNames.FIELD_SOURCE_URL));
		return permission;
	}

	/* 
	 * NOTE: Authorization factories intentionally not included in the lookup by name for rules
	 */

	// private Pattern contextUserExp = Pattern.compile("\\$\\{contextUser\\}");
	
	@SuppressWarnings("unchecked")
	public <T> T recordRead(BaseRecord contextUser, BaseRecord sourceFact, final BaseRecord referenceFact){
		T outObj = null;
		
		BaseRecord useRef = sourceFact;
		
		String stype = sourceFact.get(FieldNames.FIELD_MODEL_TYPE);
		String rtype = referenceFact.get(FieldNames.FIELD_MODEL_TYPE);
		String surn = sourceFact.get(FieldNames.FIELD_SOURCE_URN);
		String surl = sourceFact.get(FieldNames.FIELD_SOURCE_URL);
		String fdata = sourceFact.get(FieldNames.FIELD_FACT_DATA);
		String fdataType = sourceFact.get(FieldNames.FIELD_FACT_DATA_TYPE);
		long organizationId = contextUser.get(FieldNames.FIELD_ORGANIZATION_ID);
		//logger.error(JSONUtil.exportObject(referenceFact, RecordSerializerConfig.getUnfilteredModule()));
		if(stype == null || rtype == null){
			logger.error("Source fact (" + stype + ") or reference fact (" + rtype + ") is not configured for a factory read operation");
			return null;
		}
		if(fdata == null && surn == null){
			logger.error("Source URN and Fact Data were null");
			// logger.error(JSONUtil.exportObject(sourceFact, RecordSerializerConfig.getUnfilteredModule()));
			return outObj;
		}
		try {
			if(surn != null) {
				// logger.info("Find: " + surn);
				if(surn.equals("${contextUser}")) {
					surn = contextUser.get(FieldNames.FIELD_URN);
				}
				if(idPattern.matcher(surn).matches()){
					// logger.warn("Read by id: " + surn);
					outObj = (T)reader.read(stype, Long.parseLong(surn));
				}
				else if(surn.length() > 0){
					// logger.warn("Read by urn: " + surn);
					outObj = (T)reader.readByUrn(stype, surn);
				}
				else {
					/// sink empty urn left from tokenization
				}
			}
			else if(fdata != null) {
				// logger.info("Find: " + stype + " " + fdata);
				outObj = (T)IOSystem.getActiveContext().getPathUtil().findPath(null, stype, fdata, fdataType.substring(fdataType.lastIndexOf(".") + 1), organizationId);
				if(outObj == null) {
					logger.error("Failed to find: " + stype + " " + fdata);
				}
			}

		} catch (ReaderException e) {
			logger.error(e.getMessage());
			
		}
		return outObj;
	}

	public BaseRecord getFactSource(BaseRecord f) throws IndexException, ReaderException {
		String surn = f.get(FieldNames.FIELD_SOURCE_URN);
		BaseRecord[] recs = search.findByUrn(f.get(FieldNames.FIELD_MODEL_TYPE), surn);
		BaseRecord rec = null;
		if(recs.length > 0) {
			rec = recs[0];
		}
		String fmtype = f.get(FieldNames.FIELD_MODEL_TYPE);
		if(rec != null && ModelNames.MODEL_DATA.equals(fmtype)){
			boolean enciphered = rec.get(FieldNames.FIELD_ENCIPHERED);
			if(enciphered){
				/*
				SecurityBean cipher = KeyService.getSymmetricKeyByObjectId(data.getKeyId(), data.getOrganizationId());
				if(cipher == null){
					logger.error("Cipher is null for '" + data.getUrn() + "'");
					return null;
				}
				DataUtil.setCipher(data,cipher);
				*/
			}
		}
		return rec;
	}
	
}
