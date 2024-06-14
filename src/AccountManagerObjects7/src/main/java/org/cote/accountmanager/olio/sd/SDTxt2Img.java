package org.cote.accountmanager.olio.sd;

import java.util.ArrayList;
import java.util.List;

public class SDTxt2Img {
	
	private Integer[] all_seeds = new Integer[0];
	private Integer[] all_subseeds = new Integer[0];
	private String[] all_prompts = new String[0];
	private String[] all_negative_prompts = new String[0];
	private Object alwayson_scripts = null;
	private int batch_size = 1;
	private int batch_count = 1;
	private int cfg_scale = 7;
	private Object comments = null;
	private double denoising_strength = 0;
    private boolean disable_extra_networks = false;
    private boolean do_not_save_grid = false;

	private boolean do_not_save_samples = false;
	private boolean enable_hr = false;
	private int eta = 0;
	private String firstpass_image = null;
	private int firstphase_height = 0;
	private int firstphase_width = 0;
	private String force_task_id = null;
	private int height = 512;
	private String hr_checkpoint_name = null;
	private String hr_negative_prompt = null;
	private String hr_prompt = null;
	private int hr_resize_x = 0;
	private int hr_resize_y = 0;
	private String hr_sampler_name = null;
	private int hr_scale = 2;
	private String hr_scheduler = null;
	private int hr_second_pass_steps = 0;
	private String hr_upscaler = null;
	private String infotext = null;
	private int n_iter = 1;
	private String negative_prompt = null;
	private Object override_settings = null;
	private boolean override_settings_restore_afterwards = true;
	//private String[] extra_generation_params = new String[0];
	private SDExtraGenerationParams extra_generation_params = null;
	/*
	 *     "extra_generation_params": {
        "Lora hashes": "add-detail-xl: 9c783c8ce46c, xl_more_art-full_v1: fe3b4816be83",
        "Schedule type": "Karras",
        "Refiner": "Juggernaut_X_RunDiffusion_Hyper [010be7341c]",
        "Refiner switch at": 0.8
    },
	 */
	private String prompt = null;
	private String refiner_checkpoint = null;
    private int refiner_switch_at = 0;

	private boolean restore_faces = false;
	private int s_churn = 0;
	private int s_min_uncond = 0;
	private int s_noise = 0;
	private int s_tmax = 0;
	private int s_tmin = 0;
    private String sampler_index = null;
    private String sampler_name = null;
    private boolean save_images = false;
    private String scheduler = null;
	private String[] script_args = new String[0];
	private String script_name = null;
	private String sd_model_hash = null;
	private String sd_model_name = null;
	private String sd_vae_hash = null;
	private String sd_vae_name = null;
	private int seed = -1;
	private int seed_resize_from_h = 0;
	private int seed_resize_from_w = 0;
	private boolean send_images = true;
	private int steps = 20;
	private String[] styles = new String[0];
	private int subseed = 0;
	private int subseed_strength = 0;
	private String tiling = null;
	private int width = 512;
	
	public SDTxt2Img() {
		
	}

	public int getBatch_count() {
		return batch_count;
	}

	public void setBatch_count(int batch_count) {
		this.batch_count = batch_count;
	}

	public String[] getAll_prompts() {
		return all_prompts;
	}

	public void setAll_prompts(String[] all_prompts) {
		this.all_prompts = all_prompts;
	}

	public String[] getAll_negative_prompts() {
		return all_negative_prompts;
	}

	public void setAll_negative_prompts(String[] all_negative_prompts) {
		this.all_negative_prompts = all_negative_prompts;
	}

	public Integer[] getAll_subseeds() {
		return all_subseeds;
	}

	public void setAll_subseeds(Integer[] all_subseeds) {
		this.all_subseeds = all_subseeds;
	}

	public SDExtraGenerationParams getExtra_generation_params() {
		return extra_generation_params;
	}

	public void setExtra_generation_params(SDExtraGenerationParams extra_generation_params) {
		this.extra_generation_params = extra_generation_params;
	}

	public String getScript_name() {
		return script_name;
	}

	public void setScript_name(String script_name) {
		this.script_name = script_name;
	}

	public Integer[] getAll_seeds() {
		return all_seeds;
	}

	public Object getAlwayson_scripts() {
		return alwayson_scripts;
	}

	public int getBatch_size() {
		return batch_size;
	}

	public int getCfg_scale() {
		return cfg_scale;
	}

	public Object getComments() {
		return comments;
	}


	public int getEta() {
		return eta;
	}

	public String getFirstpass_image() {
		return firstpass_image;
	}

	public int getFirstphase_height() {
		return firstphase_height;
	}

	public int getFirstphase_width() {
		return firstphase_width;
	}

	public String getForce_task_id() {
		return force_task_id;
	}

	public int getHeight() {
		return height;
	}

	public String getHr_checkpoint_name() {
		return hr_checkpoint_name;
	}

	public String getHr_negative_prompt() {
		return hr_negative_prompt;
	}

	public String getHr_prompt() {
		return hr_prompt;
	}

	public int getHr_resize_x() {
		return hr_resize_x;
	}

	public int getHr_resize_y() {
		return hr_resize_y;
	}

	public String getHr_sampler_name() {
		return hr_sampler_name;
	}

	public int getHr_scale() {
		return hr_scale;
	}

	public String getHr_scheduler() {
		return hr_scheduler;
	}

	public int getHr_second_pass_steps() {
		return hr_second_pass_steps;
	}

	public String getHr_upscaler() {
		return hr_upscaler;
	}

	public String getInfotext() {
		return infotext;
	}

	public int getN_iter() {
		return n_iter;
	}

	public String getNegative_prompt() {
		return negative_prompt;
	}

	public Object getOverride_settings() {
		return override_settings;
	}

	public String getPrompt() {
		return prompt;
	}

	public String getRefiner_checkpoint() {
		return refiner_checkpoint;
	}

	public int getRefiner_switch_at() {
		return refiner_switch_at;
	}

	public int getS_churn() {
		return s_churn;
	}

	public int getS_min_uncond() {
		return s_min_uncond;
	}

	public int getS_noise() {
		return s_noise;
	}

	public int getS_tmax() {
		return s_tmax;
	}

	public int getS_tmin() {
		return s_tmin;
	}

	public String getSampler_index() {
		return sampler_index;
	}

	public String getSampler_name() {
		return sampler_name;
	}

	public String getScheduler() {
		return scheduler;
	}

	public String[] getScript_args() {
		return script_args;
	}

	public String getSd_model_hash() {
		return sd_model_hash;
	}

	public String getSd_model_name() {
		return sd_model_name;
	}

	public String getSd_vae_hash() {
		return sd_vae_hash;
	}

	public String getSd_vae_name() {
		return sd_vae_name;
	}

	public int getSeed() {
		return seed;
	}

	public int getSeed_resize_from_h() {
		return seed_resize_from_h;
	}

	public int getSeed_resize_from_w() {
		return seed_resize_from_w;
	}

	public int getSteps() {
		return steps;
	}

	public String[] getStyles() {
		return styles;
	}

	public int getSubseed() {
		return subseed;
	}

	public int getSubseed_strength() {
		return subseed_strength;
	}

	public String getTiling() {
		return tiling;
	}

	public int getWidth() {
		return width;
	}

	public boolean isDisable_extra_networks() {
		return disable_extra_networks;
	}

	public boolean isDo_not_save_grid() {
		return do_not_save_grid;
	}

	public boolean isDo_not_save_samples() {
		return do_not_save_samples;
	}

	public boolean isEnable_hr() {
		return enable_hr;
	}

	public boolean isOverride_settings_restore_afterwards() {
		return override_settings_restore_afterwards;
	}

	public boolean isRestore_faces() {
		return restore_faces;
	}

	public boolean isSave_images() {
		return save_images;
	}

	public boolean isSend_images() {
		return send_images;
	}

	public void setAll_seeds(Integer[] all_seeds) {
		this.all_seeds = all_seeds;
	}

	public void setAlwayson_scripts(Object alwayson_scripts) {
		this.alwayson_scripts = alwayson_scripts;
	}

	public void setBatch_size(int batch_size) {
		this.batch_size = batch_size;
	}

	public void setCfg_scale(int cfg_scale) {
		this.cfg_scale = cfg_scale;
	}

	public void setComments(Object comments) {
		this.comments = comments;
	}


	public double getDenoising_strength() {
		return denoising_strength;
	}

	public void setDenoising_strength(double denoising_strength) {
		this.denoising_strength = denoising_strength;
	}

	public void setDisable_extra_networks(boolean disable_extra_networks) {
		this.disable_extra_networks = disable_extra_networks;
	}

	public void setDo_not_save_grid(boolean do_not_save_grid) {
		this.do_not_save_grid = do_not_save_grid;
	}

	public void setDo_not_save_samples(boolean do_not_save_samples) {
		this.do_not_save_samples = do_not_save_samples;
	}

	public void setEnable_hr(boolean enable_hr) {
		this.enable_hr = enable_hr;
	}

	public void setEta(int eta) {
		this.eta = eta;
	}

	public void setFirstpass_image(String firstpass_image) {
		this.firstpass_image = firstpass_image;
	}

	public void setFirstphase_height(int firstphase_height) {
		this.firstphase_height = firstphase_height;
	}

	public void setFirstphase_width(int firstphase_width) {
		this.firstphase_width = firstphase_width;
	}

	public void setForce_task_id(String force_task_id) {
		this.force_task_id = force_task_id;
	}

	public void setHeight(int height) {
		this.height = height;
	}

	public void setHr_checkpoint_name(String hr_checkpoint_name) {
		this.hr_checkpoint_name = hr_checkpoint_name;
	}

	public void setHr_negative_prompt(String hr_negative_prompt) {
		this.hr_negative_prompt = hr_negative_prompt;
	}

	public void setHr_prompt(String hr_prompt) {
		this.hr_prompt = hr_prompt;
	}

	public void setHr_resize_x(int hr_resize_x) {
		this.hr_resize_x = hr_resize_x;
	}

	public void setHr_resize_y(int hr_resize_y) {
		this.hr_resize_y = hr_resize_y;
	}

	public void setHr_sampler_name(String hr_sampler_name) {
		this.hr_sampler_name = hr_sampler_name;
	}

	public void setHr_scale(int hr_scale) {
		this.hr_scale = hr_scale;
	}

	public void setHr_scheduler(String hr_scheduler) {
		this.hr_scheduler = hr_scheduler;
	}

	public void setHr_second_pass_steps(int hr_second_pass_steps) {
		this.hr_second_pass_steps = hr_second_pass_steps;
	}

	public void setHr_upscaler(String hr_upscaler) {
		this.hr_upscaler = hr_upscaler;
	}

	public void setInfotext(String infotext) {
		this.infotext = infotext;
	}

	public void setN_iter(int n_iter) {
		this.n_iter = n_iter;
	}

	public void setNegative_prompt(String negative_prompt) {
		this.negative_prompt = negative_prompt;
	}

	public void setOverride_settings(Object override_settings) {
		this.override_settings = override_settings;
	}

	public void setOverride_settings_restore_afterwards(boolean override_settings_restore_afterwards) {
		this.override_settings_restore_afterwards = override_settings_restore_afterwards;
	}

	public void setPrompt(String prompt) {
		this.prompt = prompt;
	}

	public void setRefiner_checkpoint(String refiner_checkpoint) {
		this.refiner_checkpoint = refiner_checkpoint;
	}

	public void setRefiner_switch_at(int refiner_switch_at) {
		this.refiner_switch_at = refiner_switch_at;
	}

	public void setRestore_faces(boolean restore_faces) {
		this.restore_faces = restore_faces;
	}

	public void setS_churn(int s_churn) {
		this.s_churn = s_churn;
	}

	public void setS_min_uncond(int s_min_uncond) {
		this.s_min_uncond = s_min_uncond;
	}

	public void setS_noise(int s_noise) {
		this.s_noise = s_noise;
	}

	public void setS_tmax(int s_tmax) {
		this.s_tmax = s_tmax;
	}

	public void setS_tmin(int s_tmin) {
		this.s_tmin = s_tmin;
	}

	public void setSampler_index(String sampler_index) {
		this.sampler_index = sampler_index;
	}

	public void setSampler_name(String sampler_name) {
		this.sampler_name = sampler_name;
	}

	public void setSave_images(boolean save_images) {
		this.save_images = save_images;
	}

	public void setScheduler(String scheduler) {
		this.scheduler = scheduler;
	}

	public void setScript_args(String[] script_args) {
		this.script_args = script_args;
	}

	public void setSd_model_hash(String sd_model_hash) {
		this.sd_model_hash = sd_model_hash;
	}

	public void setSd_model_name(String sd_model_name) {
		this.sd_model_name = sd_model_name;
	}

	public void setSd_vae_hash(String sd_vae_hash) {
		this.sd_vae_hash = sd_vae_hash;
	}

	public void setSd_vae_name(String sd_vae_name) {
		this.sd_vae_name = sd_vae_name;
	}

	public void setSeed(int seed) {
		this.seed = seed;
	}

	public void setSeed_resize_from_h(int seed_resize_from_h) {
		this.seed_resize_from_h = seed_resize_from_h;
	}

	public void setSeed_resize_from_w(int seed_resize_from_w) {
		this.seed_resize_from_w = seed_resize_from_w;
	}

	public void setSend_images(boolean send_images) {
		this.send_images = send_images;
	}

	public void setSteps(int steps) {
		this.steps = steps;
	}

	public void setStyles(String[] styles) {
		this.styles = styles;
	}

	public void setSubseed(int subseed) {
		this.subseed = subseed;
	}

	public void setSubseed_strength(int subseed_strength) {
		this.subseed_strength = subseed_strength;
	}

	public void setTiling(String tiling) {
		this.tiling = tiling;
	}

	public void setWidth(int width) {
		this.width = width;
	}

	
	
}
