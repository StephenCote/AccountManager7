package org.cote.accountmanager.olio.llm;

import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public class OllamaOptions {
	
	@JsonProperty("num_keep")
	private int numKeep = 5;
	private int seed = -1;
	
	@JsonProperty("num_predict")
	private int numPredict = 100;
	
	@JsonProperty("top_k")
	private int topK = 50;
	
	@JsonProperty("top_p")
	private double topP = 0.9;
	
	@JsonProperty("tfs_z")
	private double tfsZ = 0.5;
	
	@JsonProperty("typical_p")
	private double typicalP = 0.7;

	@JsonProperty("repeat_last_n")
	private int repeatLastN = 33;

	private double temperature = 0.9;

	@JsonProperty("repeat_penalty")
	private double repeatPenalty = 1.2;

	@JsonProperty("mirostat")
	private int mirostat = 1;

	@JsonProperty("mirostat_tau")
	private double mirostatTau = 0.8;

	@JsonProperty("mirostat_eta")
	private double mirostatEta = 0.6;

	@JsonProperty("penalize_newline")
	private boolean penalizeNewLine = true;

	private List<String> stop = Arrays.asList(new String[]{"\n", "user:"});

	private boolean numa = false;
	
	@JsonProperty("num_ctx")
	private int numCtx = 2048;
	
	@JsonProperty("num_batch")
	private int numBatch = 2;

	@JsonProperty("num_qpa")
	private int numQpa = 1;

	@JsonProperty("num_gpu")
	private int numGpu = 1;

	@JsonProperty("low_vram")
	private boolean lowVram = false;
	
	@JsonProperty("f16_kv")
	private boolean f16Kv = true;
	
	@JsonProperty("vocab_only")
	private boolean vocabOnly = false;
	
	@JsonProperty("use_mmap")
	private boolean useMmap = true;
	
	@JsonProperty("use_mlock")
	private boolean useMlock = false;
	
	@JsonProperty("rope_frequency_base")
	private double ropeFrequencyBase = 1.1;

	@JsonProperty("rope_frequency_scale")
	private double ropeFrequencyScale = 0.8;

	@JsonProperty("num_thread")
	private double numThread = 8;

	public OllamaOptions() {
		
	}

	public int getNumKeep() {
		return numKeep;
	}

	public void setNumKeep(int numKeep) {
		this.numKeep = numKeep;
	}

	public int getSeed() {
		return seed;
	}

	public void setSeed(int seed) {
		this.seed = seed;
	}

	public int getNumPredict() {
		return numPredict;
	}

	public void setNumPredict(int numPredict) {
		this.numPredict = numPredict;
	}

	public int getTopK() {
		return topK;
	}

	public void setTopK(int topK) {
		this.topK = topK;
	}

	public double getTopP() {
		return topP;
	}

	public void setTopP(double topP) {
		this.topP = topP;
	}

	public double getTfsZ() {
		return tfsZ;
	}

	public void setTfsZ(double tfsZ) {
		this.tfsZ = tfsZ;
	}

	public double getTypicalP() {
		return typicalP;
	}

	public void setTypicalP(double typicalP) {
		this.typicalP = typicalP;
	}

	public int getRepeatLastN() {
		return repeatLastN;
	}

	public void setRepeatLastN(int repeatLastN) {
		this.repeatLastN = repeatLastN;
	}

	public double getTemperature() {
		return temperature;
	}

	public void setTemperature(double temperature) {
		this.temperature = temperature;
	}

	public double getRepeatPenalty() {
		return repeatPenalty;
	}

	public void setRepeatPenalty(double repeatPenalty) {
		this.repeatPenalty = repeatPenalty;
	}

	public int getMirostat() {
		return mirostat;
	}

	public void setMirostat(int mirostat) {
		this.mirostat = mirostat;
	}

	public double getMirostatTau() {
		return mirostatTau;
	}

	public void setMirostatTau(double mirostatTau) {
		this.mirostatTau = mirostatTau;
	}

	public double getMirostatEta() {
		return mirostatEta;
	}

	public void setMirostatEta(double mirostatEta) {
		this.mirostatEta = mirostatEta;
	}

	public boolean isPenalizeNewLine() {
		return penalizeNewLine;
	}

	public void setPenalizeNewLine(boolean penalizeNewLine) {
		this.penalizeNewLine = penalizeNewLine;
	}

	public List<String> getStop() {
		return stop;
	}

	public void setStop(List<String> stop) {
		this.stop = stop;
	}

	public boolean isNuma() {
		return numa;
	}

	public void setNuma(boolean numa) {
		this.numa = numa;
	}

	public int getNumCtx() {
		return numCtx;
	}

	public void setNumCtx(int numCtx) {
		this.numCtx = numCtx;
	}

	public int getNumBatch() {
		return numBatch;
	}

	public void setNumBatch(int numBatch) {
		this.numBatch = numBatch;
	}

	public int getNumQpa() {
		return numQpa;
	}

	public void setNumQpa(int numQpa) {
		this.numQpa = numQpa;
	}

	public int getNumGpu() {
		return numGpu;
	}

	public void setNumGpu(int numGpu) {
		this.numGpu = numGpu;
	}

	public boolean isLowVram() {
		return lowVram;
	}

	public void setLowVram(boolean lowVram) {
		this.lowVram = lowVram;
	}

	public boolean isF16Kv() {
		return f16Kv;
	}

	public void setF16Kv(boolean f16Kv) {
		this.f16Kv = f16Kv;
	}

	public boolean isVocabOnly() {
		return vocabOnly;
	}

	public void setVocabOnly(boolean vocabOnly) {
		this.vocabOnly = vocabOnly;
	}

	public boolean isUseMmap() {
		return useMmap;
	}

	public void setUseMmap(boolean useMmap) {
		this.useMmap = useMmap;
	}

	public boolean isUseMlock() {
		return useMlock;
	}

	public void setUseMlock(boolean useMlock) {
		this.useMlock = useMlock;
	}

	public double getRopeFrequencyBase() {
		return ropeFrequencyBase;
	}

	public void setRopeFrequencyBase(double ropeFrequencyBase) {
		this.ropeFrequencyBase = ropeFrequencyBase;
	}

	public double getRopeFrequencyScale() {
		return ropeFrequencyScale;
	}

	public void setRopeFrequencyScale(double ropeFrequencyScale) {
		this.ropeFrequencyScale = ropeFrequencyScale;
	}

	public double getNumThread() {
		return numThread;
	}

	public void setNumThread(double numThread) {
		this.numThread = numThread;
	}
	
	
}
