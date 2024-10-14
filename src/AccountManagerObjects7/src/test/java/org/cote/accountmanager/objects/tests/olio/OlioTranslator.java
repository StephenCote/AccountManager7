package org.cote.accountmanager.objects.tests.olio;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import ai.djl.modality.nlp.DefaultVocabulary;
import ai.djl.modality.nlp.Vocabulary;
import ai.djl.modality.nlp.bert.BertToken;
import ai.djl.modality.nlp.bert.BertTokenizer;
import ai.djl.modality.nlp.qa.QAInput;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.translate.Batchifier;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;


// https://pub.towardsai.net/deploy-huggingface-nlp-models-in-java-with-deep-java-library-e36c635b2053
public class OlioTranslator implements Translator<QAInput, String> {
	  private List<String> tokens;
	  private Vocabulary vocabulary;
	  private BertTokenizer tokenizer;
	  
	  private String vocabFile = null;
	  public OlioTranslator(String vocabFile) {
		  this.vocabFile = vocabFile;
	  }
	  
	  @Override
	  public void prepare(TranslatorContext ctx) {
	    Path path = Paths.get(vocabFile);
	    try {
		    vocabulary = DefaultVocabulary.builder()
		                .optMinFrequency(1)
		                .addFromTextFile(path)
		                //.addFromCustomizedFile(path.toUri().toURL(), OlioMXNetVocabParser::parseToken)
		                .optUnknownToken("[UNK]")
		                .build();
	    }
	    catch(IOException e) {
	    	e.printStackTrace();
	    }
	    tokenizer = new BertTokenizer();
	  }
	    
	  @Override
	  public NDList processInput(TranslatorContext ctx, QAInput input){
	    BertToken token =
	      tokenizer.encode(
	        input.getQuestion().toLowerCase(),
	        input.getParagraph().toLowerCase()
	      );
	    // get the encoded tokens used in precessOutput
	    tokens = token.getTokens();
	    NDManager manager = ctx.getNDManager();
	    // map the tokens(String) to indices(long)
	    long[] indices =
	      tokens.stream().mapToLong(vocabulary::getIndex).toArray();
	    long[] attentionMask = 
	      token.getAttentionMask().stream().mapToLong(i -> i).toArray();
	    long[] tokenType = token.getTokenTypes().stream()
	      .mapToLong(i -> i).toArray();
	    NDArray indicesArray = manager.create(indices);
	    NDArray attentionMaskArray =
	      manager.create(attentionMask);
	    NDArray tokenTypeArray = manager.create(tokenType);
	    // The order matters
	    return new NDList(indicesArray, attentionMaskArray,
	      tokenTypeArray);
	  }
	    
	  @Override
	  public String processOutput(TranslatorContext ctx, NDList list) {
	    NDArray startLogits = list.get(0);
	    NDArray endLogits = list.get(1);
	    int startIdx = (int) startLogits.argMax().getLong();
	    int endIdx = (int) endLogits.argMax().getLong();
	    return tokenizer.tokenToString(tokens.subList(startIdx, endIdx + 1));
	  }
	    
	  @Override
	  public Batchifier getBatchifier() {
	    return Batchifier.STACK;
	  }
	}