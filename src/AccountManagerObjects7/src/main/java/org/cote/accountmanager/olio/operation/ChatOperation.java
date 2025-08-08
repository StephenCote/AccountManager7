package org.cote.accountmanager.olio.operation;

import java.security.SecureRandom;
import java.util.List;
import java.util.stream.Collectors;

import org.cote.accountmanager.io.IReader;
import org.cote.accountmanager.io.ISearch;
import org.cote.accountmanager.olio.OlioUtil;
import org.cote.accountmanager.olio.Rules;
import org.cote.accountmanager.olio.TerrainUtil;
import org.cote.accountmanager.olio.llm.Chat;
import org.cote.accountmanager.olio.llm.ChatUtil;
import org.cote.accountmanager.olio.llm.OpenAIRequest;
import org.cote.accountmanager.olio.llm.OpenAIResponse;
import org.cote.accountmanager.olio.schema.OlioFieldNames;
import org.cote.accountmanager.olio.schema.OlioModelNames;
import org.cote.accountmanager.policy.FactUtil;
import org.cote.accountmanager.policy.PolicyUtil;
import org.cote.accountmanager.policy.operation.Operation;
import org.cote.accountmanager.record.BaseRecord;
import org.cote.accountmanager.schema.FieldNames;
import org.cote.accountmanager.schema.type.OperationResponseEnumType;
import org.cote.accountmanager.schema.type.TerrainEnumType;


	public class ChatOperation extends Operation {
		private SecureRandom random = new SecureRandom();
		
		public ChatOperation(IReader reader, ISearch search) {
			super(reader, search);
		}
		
		@Override
		public <T> T read(BaseRecord sourceFact, BaseRecord referenceFact) {
			// TODO Auto-generated method stub
			return null;
		}
		
		@Override
		public OperationResponseEnumType operate(BaseRecord prt, BaseRecord prr, BaseRecord pattern, BaseRecord sourceFact, BaseRecord referenceFact) {
			
			logger.info("Operating on chat ...");
			OperationResponseEnumType ort = OperationResponseEnumType.FAILED;
			BaseRecord req = sourceFact.get("factReference");
			BaseRecord cfg = referenceFact.get("chatConfig");
			BaseRecord pcfg = referenceFact.get("promptConfig");
			if(req == null || !req.getSchema().equals(OlioModelNames.MODEL_OPENAI_REQUEST)) {
				logger.error("Fact reference is null");
				return ort;
			}
			if(cfg == null) {
				logger.error("Chat config is null");
				return ort;
			}
			if(pcfg == null) {
				logger.error("Prompt config is null");
				return ort;
			}
			String reqStr = sourceFact.get("factData");
			if(reqStr == null) {
				List<BaseRecord> msgs = ((List<BaseRecord>)req.get("messages")).stream().filter(m -> "user".equals(m.get("role"))).collect(Collectors.toList());
				BaseRecord lastMsg = null;
				if(msgs.size() > 0) {
					lastMsg = msgs.get(msgs.size() - 1);
					reqStr = lastMsg.get("content");
				}
			}
			
			
			logger.info("Invoking chat ...");
			BaseRecord fcfg = OlioUtil.getFullRecord(cfg);
			BaseRecord fpcfg = OlioUtil.getFullRecord(pcfg);
			logger.info(fcfg.toFullString());
			Chat chat = new Chat(null, fcfg, pcfg);
			chat.setPersistSession(false);
			OpenAIRequest nreq = chat.getChatPrompt();
			chat.continueChat(nreq, reqStr);
			logger.info(nreq.toFullString());
			PolicyUtil.addResponseMessage(prr, "Chat Operation Pending...");

			
			return ort;
		}
	}
