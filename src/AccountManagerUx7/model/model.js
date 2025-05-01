(function () {

	am7model.enums.modelNames = [];
	am7model.models.forEach((o) => {
		if (!o.abstract) {
			am7model.enums.modelNames.push(o.name);
		}
	});

	am7model.system = {
		library: {
			"data.color": "/Library/Colors"
		}
	};

	am7model.getPrototype = function (name) {
		let cat = am7model.categories;
		let proto;
		let aP = Object.keys(cat).filter((k) => {

			let vP = Object.keys(cat[k]).filter((l) => {
				if (l.match(/^prototype$/)) {
					let catO = cat[k][l];
					if (catO[name]) {
						proto = catO[name];
						proto.key = name;
						return true;
					}
					else {
						let xP = Object.keys(catO).filter((m) => {
							if (catO[m].pathName && catO[m].pathName.toLowerCase() == name) {
								proto = catO[m];
								proto.key = m;
								return true;
							}
						});
						if (xP.length) return true;
					}
				}
			});
			if (vP.length) {
				return true;
			}
		});
		if (proto) return proto;
		return null;
	}

	am7model.getModel = function (type) {
		if (type == null || !type) {
			console.error("Invalid type");
			return null;
		}
		let model = am7model.models.filter(o => o.name.toLowerCase() == type.toLowerCase());
		if (!model.length) {
			model = am7model.getPrototype(type);
		}
		else {
			model = model[0];
		}
		if (model == null) {
			// console.warn("No model for '" + type + "'");
			return null;
		}
		return model;
	};

	am7model.hasField = function (model, n) {
		let f = am7model.getModelField(model, n);
		return (f ? true : false);
	};

	am7model.getModelField = function (model, n, ftype) {
		if (typeof model == "string") {
			if (model == "$flex") {
				if (!ftype) {
					ftype = 'common.nameId';
				}
				model = ftype;
			}
			model = am7model.getModel(model);
		}
		if (!model) {
			console.error("Failed to find model definition for " + model + " from " + model + ", " + ftype);
			return;
		}

		let fld = model.fields.filter(o => o.name.toLowerCase() == n.toLowerCase());
		let field = fld[0];
		if (!field && model.inherits) {
			for (let i in model.inherits) {
				field = am7model.getModelField(model.inherits[i], n);
				if (field) {
					break;
				}
			}
		}
		return field;
	};

	am7model.getModelFields = function (model) {
		let am = [];
		if (typeof model == "string") {
			model = am7model.getModel(model);
		}

		if (!model) {
			console.error("Failed to find model definition for " + model);
			return;
		}
		am.push(...model.fields);
		for (let i in model.inherits) {
			am.push(...am7model.getModelFields(model.inherits[i]));
		}
		return [...new Set(am)];
	};

	am7model.newPrimitive = function (sLType, ignoreEmptyNull, sPath, innerCall) {
		let o = {};
		let f = am7model.getModel(sLType);
		if (!f) {
			console.error("No model for: " + sLType);
			return null;
		}
		if (f.followReference == false) {
			o[am7model.jsonModelKey] = sLType;
			return o;
		}
		if (f.inherits) {
			f.inherits.forEach(i => {
				Object.assign(o, am7model.newPrimitive(i, ignoreEmptyNull, sPath, true));
			});
		}
		if (f.fields) {
			f.fields.forEach(i => {
				if (!i.identity && !i.ephemeral && !i.virtual) {
					let nfv = am7model.defaultValue(i);
					let emptyArray = (i.type == 'list' && nfv.length == 0);
					let nullOrUndef = ((i.type == 'string' || i.type == 'object' || i.type == 'enum') && (!nfv || nfv == null));
					if (!ignoreEmptyNull || !(
						emptyArray
						||
						nullOrUndef
					)) {
						o[i.name] = nfv;
					}
					else {
						console.warn("Skip: " + i.name + " because " + emptyArray);
					}
				}
			});
		}

		if (!innerCall) {
			o[am7model.jsonModelKey] = sLType;
			let ft = am7model.getModel(sLType);
			// ft.inherits && ft.inherits.find(s => s == "directory") != null
			if (ft && am7model.isGroup(ft)) {
				let sDP = sPath || am7view.path(sLType);
				if (sLType == "auth.group") o.path = sDP;
				else o.groupPath = sDP;
			}
		}
		return o;
	};
	am7model.defaultValue = function (field) {
		let fieldDefVal = field.default;
		let defVal;
		switch (field.type) {
			case "int":
			case "double":
			case "long":
			case "number":
				defVal = 0;
				break;
			case "blob":
			case "binary":
				defVal = "";
				break;
			case "string":
				defVal = null;
				break;
			case "enum":
				defVal = "UNKNOWN";
				break;
			case "boolean":
				defVal = false;
				break;
			case "list":
			case "array":
				defVal = [];
				break;
			case "model":
			case "object":
				defVal = null;
				break;
			case "zonetime":
				defVal = new Date().toISOString().slice(0, 16);
				break;
			case "timestamp":
			case "datetime":
				defVal = (new Date()).getTime();
				break;
			case "color":
			case "flex":
				break;
			default:
				console.warn("Unhandled field type", field.type, field);
				break;
		}
		return (fieldDefVal || defVal);
	};

	am7model.inherits = function (o, i) {
		let b = false;
		if (typeof o == "string") o = am7model.getModel(o);
		if (o.inherits) {
			if (o.inherits.includes(i) || (o.likeInherits && o.likeInherits.includes(i))) {
				b = true;
			}
			else {
				for (let h in o.inherits) {
					let p = am7model.getModel(o.inherits[h]);
					if (p) {
						b = am7model.inherits(p, i);
						if (b) {
							break;
						}
					}
				};
			}
		}
		return b;
	};

	am7model.inheritsFrom = function (o) {
		let a = [];
		if (typeof o == "string") o = am7model.getModel(o);
		a.push(o);
		if (o.inherits) {
			for (let h in o.inherits) {
				let p = am7model.getModel(o.inherits[h]);
				if (p && !a.includes(p)) {
					a = [].concat(a, am7model.inheritsFrom(p));
				}
			};
		}
		return a;
	};

	am7model.isParent = function (o) {
		return am7model.inherits(o, "common.parent");
	};

	am7model.isGroup = function (o) {
		if (typeof o == "string") o = am7model.getModel(o);
		return (am7model.inherits(o, "data.directory") || o.name == 'auth.group');
	};

	am7model.observers = {
		models: {}
	};

	am7model.observe = function (o, s) {
		let m = am7model.observers.models;
		if (!m[s]) {
			m[s] = [];
		}
		m[s].push(o);
	};

	am7model.updateListModel = function (v, f) {
		/// In AM7, the 'model' property may be condensed to occur in only the first result  if the remaining results are the same
		///
		if (v && v.length) {
			let mk = v[0][am7model.jsonModelKey];
			let m = mk || f?.baseModel;

			for (let i = (mk ? 1 : 0); i < v.length; i++) {
				if (!v[i][am7model.jsonModelKey]) v[i][am7model.jsonModelKey] = m;
			}
		}
	};

	am7model.applyModelNames = function (o) {
		let af = am7model.getModelFields(o[am7model.jsonModelKey]);
		af.forEach(f => {
			if (f.type == "model" && o[f.name] && !o[f.name][am7model.jsonModelKey]) {
				o[f.name][am7model.jsonModelKey] = f.baseModel;
				am7model.applyModelNames(o[f.name]);
			}
		});
	}

	/// A more generic version of the mergeEntity function in Object
	am7model.prepareEntity = function (e, baseModel, recurse) {
		let ue = am7model.newPrimitive(e && e[am7model.jsonModelKey] ? e[am7model.jsonModelKey] : baseModel);
		if (!e[am7model.jsonModelKey]) e[am7model.jsonModelKey] = ue[am7model.jsonModelKey] || baseModel;
		if (!e[am7model.jsonModelKey]) {
			console.warn("Cannot find model", e);
			return;
		}
		let x = Object.assign(ue, e);
		let af = am7model.getModelFields(e[am7model.jsonModelKey]);
		
		if (recurse) {
			af.forEach(f => {
				if ((f.type == 'list' || f.type == 'model') && f.foreign && f.baseModel) {
					if (f.type == 'model' && !ue[f.name] && e[f.name]) {
						ue[f.name] = am7model.prepareEntity(e[f.name], f.baseModel, recurse);
					}
					else if (f.type == 'list') {
						ue[f.name] = ue[f.name].map(e2 => am7model.prepareEntity(e2, f.baseModel, recurse));
					}
					/*
					if (ue[f.name] && (f.type != 'list' || ue[f.name].length > 0)) {
						let fm = f.baseModel;
						ue[f.name] = am7model.prepareEntity(e[f.name] || ue[f.name], f.baseModel, recurse);
					}
					*/
				}
			});
		}
		
		am7model.applyModelNames(x);
		return x;
	};

	am7model.validationRule = function (n) {
		let r = am7model.validationRules.filter(r => r.name == n);
		return r.length ? r[0] : undefined;
	};

	am7model.validateInstance = function (o) {
		let r = o.model.fields.map((f) => am7model.validateField(o, f, f.rules));
		return r;
	};

	let vrcache = {};

	am7model.getValidationRuleInstance = function (ru) {
		let oru = ru;
		let vr = oru;
		let isStr = typeof oru == "string"
		if (isStr) {
			if (vrcache[oru]) {
				return vrcache[oru]
			}
			/// System rule references start with $, such as $rule
			/// Tokenized system rules are in the format of ${rule}, with the intent of being dynamically substituded when the rule is loaded on the backend
			/// Otherwise, rules would follow the foreign list model
			/// Since this is  a lookup, anything starting with $ is a system rule and $,{,} will be stripped
			if (oru.match(/^\$/)) {
				oru = oru.replace(/[\$\{\}]*/g, "");
			}
			vr = am7model.validationRule(oru);
		}
		let nvr = am7model.prepareEntity(vr, "policy.validationRule")
		let vri = am7model.prepareInstance(nvr, am7model.forms.validationRule);

		if (isStr) {
			vrcache[ru] = vri;
		}
		return vri;
	};

	am7model.validateField = function (o, f, rules) {

		var r = 0,
			ir = 1,
			tir,
			re = 1
			;

		if (f.readOnly || f.virtual || f.ephemeral || f.private) return 1;

		let v = o.api[f.name]();
		let err;
		switch (f.type) {
			case "enum":
			case "string":
				if (f.required && (v == undefined || v.length == 0)) {
					err = "Field is required";
					re = 0;
					break;
				}
				if (f.maxLength > 0 && v != undefined && v.length > f.maxLength) {
					err = "Exceeds maximum length " + f.maxLength;
					re = 0;
					break;
				}
				if (f.minLength && (v == undefined || v.length < f.minLength)) {
					err = "Does not meet the minimum length " + f.minLength;
					re = 0;
					break;
				}
				if (f.limit?.length > 0 && !f.limit.includes(v) && f.required) {
					err = "Not defined in the limit", f.limit;
					re = 0;
					break;

				}
				break;
			case "timestamp":
			case "long":
			case "int":
			case "double":
				if (f.name == "parentId" && am7model.isGroup(o.model.name)) {
					/// Allow parentId to be 0 if group is also present
					///
				}
				else if (f.required && (v == undefined || v == 0)) {
					err = "Field is required";
					re = 0;
					break;
				}
				if (f.validateRange && (v < f.minValue || v > f.maxValue)) {
					err = "Outside value range " + f.minValue + " - " + f.maxValue;
					re = 0;
					break;
				}
				break;
			case "list":
				/// ignore
				break;
			default:
				console.warn("Unhandled field type: " + f.type, f);
				break;
		}
		if (re == 0) {
			o.validationErrors[f.name] = err;
			return 0;
		}
		if (!rules || !rules.length) {
			return 1;
		}
		for (let ri in rules) {
			let ru = rules[ri];
			let vr = am7model.getValidationRuleInstance(ru);
			if (!vr) {
				o.validationErrors[f.name] = "Invalid rule: " + ru;
				return 0;
			}

			if (vr.api.rules()?.length) {
				tir = am7model.validateField(o, f, vr.api.rules());
				if (ir && !tir) {
					ir = 0;
					break;
				}
			}



			/* check typeof == "number" because "" == 0*/
			if (
				(f.type == "int" || f.type == "double" || f.type == "float") && v == f.default

			) {
				return 1;
			}

			if (v == undefined) {
				o.validationErrors[f.name] = "Field is empty or undefined";
				return 0;
			}

			if (vr.api.expression()) {
				try {
					let re = new RegExp(vr.api.expression());
					switch (vr.api.type()) {
						case "replacement":
							r = 1;
							if (typeof vr.api.replacementValue() == "string") {
								v = v.replace(re, vr.api.replacementValue());
								o.api[f.name](v);
							}
							break;
						case "boolean":
							if (
								(v.allowNull && v.length == 0)
								||
								(v.match(re) != null) == vr.api.comparison()
							) {
								r = 1;
							}
							else {
								o.validationErrors[f.name] = "Invalid value for " + vr.api.name();
							}
							break;
						case "none":
							break;
						default:
							console.warn("Unhandled expression type: " + v.type);
							break;
					}
				}
				catch (e) {
					console.error("Error in validateField:: " + (e.description ? e.description : e.message));
				}
			}
			if (vr.api.type() == "none") r = 1;
			else if (vr.api.type() == "function") {
				console.warn("Unhandled function type: " + vr.api.name());
				r = 1;
			}
			/*
				if the return value is true, but the include return value is false
				set the return value to false
			*/
			if (r && !ir) r = 0;
			if (!r) {
				console.error("Validation failed for " + f.name + " with value " + v);
				break;
			}
		}


		return r;
	};

	am7model.hasIdentity = function (entity) {
		return am7model.getModelFields(entity[am7model.jsonModelKey]).filter(f => {
			return (f.identity && entity[f.name] && entity[f.name] != am7model.defaultValue(f));
		}).length > 0;
	};



	let dateTimeDecorator = {
		decorateOut: function (i, f, v) {
			if (typeof v == "number") {
				let d = new Date(v);
				//v = new Date(d.getTime() - (d.getTimezoneOffset() * 60000)).toISOString().slice(0,16);
				v = new Date(d.getTime()).toISOString().slice(0, 16);
			}
			return v;
		},
		decorateIn: function (i, f, v) {
			if (typeof v == "string") {
				let d = new Date(v);
				v = d.getTime();
			}
			return v;
		}
	};

	let dateTimeZDecorator = {
		decorateOut: function (i, f, v) {
			if (typeof v == "string") {
				v = new Date(v).toISOString().slice(0, 16);
			}
			return v;
		},
		decorateIn: function (i, f, v) {
			return v;
		}
	};

	let numberDecorator = {
		decorateOut: function (i, f, v) {
			if (v == undefined) {
				v = f.default || 0;
			}
			if (f.type == "double" && v % 1 == 0) {
				v = v + ".0";
			}
			return v;
		},
		decorateIn: function (i, f, v) {
			if (typeof v == "string") {
				if (f.type == "double") {
					v = parseFloat(v);
				}
				else if (f.type == "int") {
					v = parseInt(v);
				}
			}
			return v;
		}
	};

	let rangeDecorator = {
		decorateOut: function (i, f, v) {
			if (typeof v == "number" && f.type == "double" && f.maxValue >= 1) {
				v = parseInt(v * 100);
			}
			else if (v == undefined) {
				v = 0;
			}
			return v;
		},
		decorateIn: function (i, f, v) {
			if (f.type == "double" && f.maxValue >= 1) {
				if (v != 0) {
					v = parseFloat(v / 100);
				}
			}
			else if (f.type == "int") {
				v = parseInt(v);
			}
			return v;
		}
	};

	let textListDecorator = {
		decorateOut: function (i, f, v) {
			if (v && (v instanceof Array)) {
				v = v.join("\r\n");
			}
			else {
				v = "";
			}
			return v;
		},
		decorateIn: function (i, f, v) {
			if (v && typeof v == "string") {
				v = v.split(/\r?\n|\r|\n/g);
			}
			else {
				v = [];
				console.log(v);
			}
			//v = ntc.name(v)[0];
			return v;
		}
	};

	let colorDecorator = {
		decorateOut: function (i, f, v) {
			//v = ntc.hex(v)[0];
			return v;
		},
		decorateIn: function (i, f, v) {
			if (i.model.name == "data.color" && v && v.length == 7) {
				i.api["red"](parseInt(v.substring(1, 3), 16));
				i.api["green"](parseInt(v.substring(3, 5), 16));
				i.api["blue"](parseInt(v.substring(5, 7), 16));

			}
			//v = ntc.name(v)[0];
			return v;
		}
	};

	let blobDecorator = {
		decorateOut: function (i, f, v) {
			if (typeof v == "string") {
				v = Base64.decode(v);
			}
			return v;
		},
		decorateIn: function (i, f, v) {
			if (typeof v == "string") {
				v = Base64.encode(v);
			}
			return v;
		}
	};

	am7model.base64ToUint8 = function (data) {
		var dec = Base64.decode(data);
		var array = new Uint8Array(new ArrayBuffer(dec.length));
		for (var i = 0; i < dec.length; i++) {
			array[i] = dec.charCodeAt(i);
		}
		return array;
	};

	am7model.queryFields = function (type) {
		let r1 = am7model.inheritsFrom(type).filter(m => m.query).map(m => m.query).flat(1);
		if (am7model.inherits(type, "system.organizationExt")) {
			r1.push("organizationId");
			r1.push("organizationPath");
		}
		if (am7model.inherits(type, "common.dateTime")) {
			r1.push("createdDate");
			r1.push("modifiedDate");
		}
		if (am7model.isParent(type)) {
			if (am7model.hasField(type, "path")) r1.push("path");
			r1.push("parentId");
		}
		if (am7model.isGroup(type) && type != 'auth.group') {
			r1.push("groupPath");
			r1.push("groupId");
		}
		return r1;
	}

	am7model.newInstance = function (model, form) {
		return am7model.prepareInstance(am7model.newPrimitive(model), form);
	};

	am7model.prepareInstance = function (entity, form) {
		form = form ?? am7model.forms[entity[am7model.jsonModelKey].substring(entity[am7model.jsonModelKey].lastIndexOf(".") + 1)];
		let inst = {
			entity,
			form,
			model: am7model.getModel(entity[am7model.jsonModelKey]),
			observers: [],
			actions: {},
			decorators: {},
			designers: {},
			viewProperties: {},
			api: {},
			validateField: {},
			changes: [],
			validations: {},
			validationErrors: {},
			resetChanges: () => {
				inst.changes = [];
			},
			fields: []
		};
		inst.fields = am7model.getModelFields(entity[am7model.jsonModelKey]);

		inst.change = function (n) {
			if (!inst.changes.includes(n)) inst.changes.push(n);
		};

		inst.unchange = function (n) {
			inst.changes = inst.changes.filter((value, index, arr) => value != n);
		};

		function report(a, e) {
			inst.observers.forEach((o) => {
				if (o.report) {
					o.report(a, e, inst);
				}
			});
		}

		function decorateInOut(bIn, v, f) {
			let d = inst.decorators;
			if (d[f.name]) {

				d[f.name].forEach((c) => {
					if (bIn && c.decorateIn) {
						v = c.decorateIn(inst, f, v);
					}
					if (!bIn && c.decorateOut) {
						v = c.decorateOut(inst, f, v);
					}
				});

			}
			return v;
		}

		inst.decorate = (e, o) => {
			let d = inst.decorators;
			if (!d[e]) {
				d[e] = [];
			}
			d[e].push(o);
		};

		inst.viewProperties = (e, o) => {
			if (o) {
				return inst.viewDesigner(e, o, "viewProperties");
			}
			else {
				return inst.viewDesign(e, "viewProperties");
			}
		};
		inst.design = (e) => {
			return inst.viewDesign(te, "designers");
		};

		inst.designer = (e, o) => {
			return inst.viewDesigner(e, o, "designers");
		};
		inst.design = (e) => {
			return inst.viewDesign(e, "designers");
		};
		inst.viewDesigner = (e, o, p) => {
			let d = inst[p];
			if (!d[e]) {
				d[e] = [];
			}
			d[e].push(o);
		};
		inst.viewDesign = (e, p) => {
			let v;
			let d = inst[p];
			let dx = [];
			let dd = dx.concat(d[e], d["all"]);
			dd.forEach(df => {
				if (df) {
					if (typeof df == "function") {
						v = df(inst, e, v);
					}
					else v = df;
				}
			});
			return v;
		};

		inst.field = (n) => {
			let f;
			//let a = inst[am7model.jsonModelKey].fields.filter(f => f.name === n);
			let a = inst.fields.filter(f => f.name === n);
			return (a.length ? a[0] : f);
		};

		inst.formField = (n, v) => {
			let x = inst?.form?.fields[n];
			if (v) {
				let z;
				if (x && x[v]) z = x[v];
				else if (inst.field(n)) z = inst.field(n)[v];
				return z;
			}
			return x;
		};

		inst.observe = (o) => {
			inst.observers.push(o);
		};
		inst.action = (n, o) => {
			if (!o && inst.actions[n]) {
				inst.actions[n](inst, n);
			}
			else if (o) {
				inst.actions[n] = o;
			}
		};
		inst.icon = () => {
			return form.icon || inst.model.icon;
		};

		inst.modelValue = (n, p) => {
			if (form.fields[n] && form.fields[n][p]) {
				return form.fields[n][p];
			}
			let mf = inst.field(n);
			if (mf && mf[p]) {
				return mf[p];
			}
			return;
		};

		inst.label = (n) => {
			if (n) {
				let r = inst.modelValue(n, "label");
				if (!r) r = inst.modelValue(n, "name");
				// console.log(n + " " + r + " " + inst.modelValue(n, "label"));
				return r || n;
			}
			return form.label || inst.model.label;
		};

		inst.placeholder = (n) => {
			return inst.modelValue(n, "placeholder");
		};

		am7model.getModelFields(inst.model).forEach((f) => {
			inst.api[f.name] = (...v) => {
				if (!f.readOnly && v.length) {
					let val = decorateInOut(true, v[0], f);
					inst.entity[f.name] = val;
					inst.change(f.name);
					report('update', f.name, inst);
				}
				return decorateInOut(false, entity[f.name], f);
			};

			inst.dapi = (pn, ...v) => {
				if (v.length) {
					let p = pn.split(".");
					let op = p[0];
					inst.change(op);
					let o = inst.entity;

					for (let i = 0; i < (p.length - 1); i++) {
						o = o[op];
						op = p[i];
					}
					o[p[p.length - 1]] = v[0];
				}
			};
			let field = am7view.formField(form, f.name);

			if (field && field.format == "color") {
				inst.decorate(f.name, colorDecorator);
			}
			else if (field && field.format == "textlist") {
				inst.decorate(f.name, textListDecorator);
			}
			/// f.type == "zonetime" || 
			else if (f.type == "zonetime") {
				inst.decorate(f.name, dateTimeZDecorator);
			}
			else if (f.type == "timestamp" || f.type == "datetime") {
				inst.decorate(f.name, dateTimeDecorator);
			}
			else if (f.type == "blob") {
				inst.decorate(f.name, blobDecorator);
			}
			else if ((f.type == 'double' || f.type == 'int') && typeof f.minValue == "number" && typeof f.maxValue == "number" && form && field && field.format == "range") {
				inst.decorate(f.name, rangeDecorator);
			}
			else if (f.type == 'double' || f.type == 'int') {
				inst.decorate(f.name, numberDecorator);
			}

			inst.validateField[f.name] = () => {
				if (field?.skipValidation) {
					return true;
				}
				let r = am7model.validateField(inst, f, f.rules);
				inst.validations[f.name] = (r == 1);
				if (r == 0 && !inst.validationErrors[f.name]) {
					inst.validationErrors[f.name] = "Invalid value for " + f.name;
				}
				else if (r == 1) {
					delete inst.validationErrors[f.name];
				}
				return (r == 1);
			}
		});

		inst.validate = () => {
			let fv = [];
			am7model.getModelFields(inst.model).forEach((f) => {
				fv.push(inst.validateField[f.name]());
			});
			return !fv.includes(false);
		}

		inst.handleChange = (n, fh) => {
			let f = inst.field(n);
			if (!f) {
				console.warn("Invalid field: '" + n + "'");
			}
			return function (e) {
				let v = e.target.value;
				if (e.target.type && e.target.type == "checkbox") {
					v = e.target.checked;
				}
				else if (f.type == "string" && typeof v == "string" && v.length == 0) {
					v = null;
				}
				inst.api[n](v);
				if (fh) fh(e);
				inst.action(n);
				inst.validateField[n]();
			}
		}

		inst.patch = () => {
			let pent = {};
			let b1id = 0;
			if (inst.changes.length == 0) {
				return;
			}
			am7model.getModelFields(inst.model).filter(f => {
				return (
					(
						(f.identity && inst.entity[f.name])
						||
						inst.changes.includes(f.name)
						||
						f.provider
					)
					&&
					/// Foreign entities can patch their own fields - except the initial foreign ref, which can only be set by updating this field
					/// !f.foreign
					/// &&
					!f.ephemeral
					&&
					!f.virtual
				);
			}).forEach(f => {
				if (!f.identity || !b1id) {
					if (f.identity) b1id++;
					pent[f.name] = entity[f.name];
				}
			});
			pent[am7model.jsonModelKey] = inst.model.name;
			return pent;
		};

		inst.hasIdentity = () => {
			return am7model.hasIdentity(entity);
		};

		let om = am7model.observers.models[entity[am7model.jsonModelKey]];
		if (om) {
			om.forEach((o) => {
				observe(o);
			});
		}
		return inst;
	};

}());