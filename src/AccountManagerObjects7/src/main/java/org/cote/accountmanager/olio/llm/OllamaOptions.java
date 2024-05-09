package org.cote.accountmanager.olio.llm;

import com.fasterxml.jackson.annotation.JsonProperty;

public class OllamaOptions {
	/*
	@JsonProperty("num_keep")
	private int numKeep = 5;
	private int seed = -1;
	
	@JsonProperty("num_predict")
	private int numPredict = 100;
	*/
	@JsonProperty("top_k")
	private int topK = 50;
	
	@JsonProperty("top_p")
	private double topP = 0.9;
	/*
	@JsonProperty("tfs_z")
	private double tfsZ = 0.5;
	
	@JsonProperty("typical_p")
	private double typicalP = 0.7;

	@JsonProperty("repeat_last_n")
	private int repeatLastN = 33;
	*/
	private double temperature = 0.9;
	/*
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
	*/
	@JsonProperty("num_ctx")
	private int numCtx = 2048;
	/*
	@JsonProperty("num_batch")
	private int numBatch = 2;

	@JsonProperty("num_qpa")
	private int numQpa = 1;
	*/
	@JsonProperty("num_gpu")
	private int numGpu = 1;

	/*
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
	*/
	public OllamaOptions() {
		
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

	public double getTemperature() {
		return temperature;
	}

	public void setTemperature(double temperature) {
		this.temperature = temperature;
	}

	public int getNumCtx() {
		return numCtx;
	}

	public void setNumCtx(int numCtx) {
		this.numCtx = numCtx;
	}

	public int getNumGpu() {
		return numGpu;
	}

	public void setNumGpu(int numGpu) {
		this.numGpu = numGpu;
	}

	
}
