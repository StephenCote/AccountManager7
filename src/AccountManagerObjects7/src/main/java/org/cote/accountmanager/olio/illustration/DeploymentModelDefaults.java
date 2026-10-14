package org.cote.accountmanager.olio.illustration;

import org.cote.accountmanager.olio.sd.SDUtil;
import org.cote.accountmanager.olio.sd.swarm.SWUtil;

/// The deployment-wide checkpoint defaults that ModelResolver's last step
/// (Step.DEPLOYMENT_DEFAULT) falls back to (PictureBook V3 design §16, §10.2 decision 5).
///
/// These are BOOT-PINNED: `model` is `sd.default.model` (blank -> `sd.model`) as pushed into
/// SDUtil.setDefaultModel at startup (RestServiceEventListener; BaseTest for JUnit), falling back
/// to the olio.sd.config schema default; `flux2Model` is the schema's flux2Model default. Neither
/// is DB-backed, so the propagation bound for a change is a restart. Read at call time, never
/// cached here.
public final class DeploymentModelDefaults {

	private DeploymentModelDefaults() { }

	public static final class Defaults {
		private final String model;
		private final String flux2Model;

		Defaults(String model, String flux2Model) {
			this.model = model;
			this.flux2Model = flux2Model;
		}

		/// The SDXL-family default (PORTRAIT, LOCATION, INPAINT, HARMONIZE, UPSCALE).
		public String getModel() { return model; }

		/// The FLUX.2 default (SCENE_DIRECT).
		public String getFlux2Model() { return flux2Model; }

		@Override
		public String toString() {
			return "Defaults{model=" + model + ", flux2Model=" + flux2Model + "}";
		}
	}

	public static Defaults current() {
		return new Defaults(SDUtil.getDefaultModel(), SWUtil.defaultFlux2Model());
	}
}
