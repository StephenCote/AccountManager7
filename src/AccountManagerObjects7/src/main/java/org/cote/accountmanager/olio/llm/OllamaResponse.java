package org.cote.accountmanager.olio.llm;

import java.util.ArrayList;
import java.util.List;

public class OllamaResponse{
	private String model = null;
	private String created_at = null;
	private String response = null;
	private boolean done = false;
	private List<Integer> context = new ArrayList<>();
	private long total_duration = 0L;
	private long load_duration = 0L;
	private int prompt_eval_count = 0;
	private long prompt_eval_duration = 0L;
	private int eval_count = 0;
	private long eval_duration = 0L;
	private OllamaMessage message = null;
	public OllamaResponse() {
		
	}
	
	public OllamaMessage getMessage() {
		return message;
	}

	public void setMessage(OllamaMessage message) {
		this.message = message;
	}

	public String getModel() {
		return model;
	}
	public void setModel(String model) {
		this.model = model;
	}
	public String getCreated_at() {
		return created_at;
	}
	public void setCreated_at(String created_at) {
		this.created_at = created_at;
	}
	public String getResponse() {
		return response;
	}
	public void setResponse(String response) {
		this.response = response;
	}
	public boolean isDone() {
		return done;
	}
	public void setDone(boolean done) {
		this.done = done;
	}
	public List<Integer> getContext() {
		return context;
	}
	public void setContext(List<Integer> context) {
		this.context = context;
	}
	public long getTotal_duration() {
		return total_duration;
	}
	public void setTotal_duration(long total_duration) {
		this.total_duration = total_duration;
	}
	public long getLoad_duration() {
		return load_duration;
	}
	public void setLoad_duration(long load_duration) {
		this.load_duration = load_duration;
	}
	public int getPrompt_eval_count() {
		return prompt_eval_count;
	}
	public void setPrompt_eval_count(int prompt_eval_count) {
		this.prompt_eval_count = prompt_eval_count;
	}
	public long getPrompt_eval_duration() {
		return prompt_eval_duration;
	}
	public void setPrompt_eval_duration(long prompt_eval_duration) {
		this.prompt_eval_duration = prompt_eval_duration;
	}
	public int getEval_count() {
		return eval_count;
	}
	public void setEval_count(int eval_count) {
		this.eval_count = eval_count;
	}
	public long getEval_duration() {
		return eval_duration;
	}
	public void setEval_duration(long eval_duration) {
		this.eval_duration = eval_duration;
	}
	
}
