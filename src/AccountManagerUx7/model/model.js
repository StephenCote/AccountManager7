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
					if (i.name == 'quality') {
						console.log(i, ignoreEmptyNull, emptyArray, nullOrUndef);
					}
					if (!ignoreEmptyNull || !(
						emptyArray
						||
						nullOrUndef
					)) {
						if (i.name == 'quality') {
							console.log(i.name, nfv);
						}
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

	am7model.updateListModel = function(v, f){
		/// In AM7, the 'model' property may be condensed to occur in only the first result  if the remaining results are the same
    	///
      if (v && v.length) {
		let mk = v[0][am7model.jsonModelKey];
        let m = mk || f?.baseModel;

        for (let i = (mk ? 1 : 0); i < v.length; i++) {
          if (!v[i][am7model.jsonModelKey]) v[i][am7model.jsonModelKey] = m;
        }
      }
	}

	am7model.validateInstance = function (o) {
		let r = o.model.fields.map((f) => am7model.validateField(o, f.name));

		return r;
	};

	am7model.validateField = function (o, i) {

		var r = 0,
			ir = 1,
			tir,
			pid = 0,
			po,
			v,
			c
			;

		if (DATATYPES.TS(i)) pid = i;
		else pid = o.getAttribute("pattern-id");

		/* return true if there is no pattern-id */
		if (!pid) {
			_m.sendMessage("Skipping empty pattern", "200.1");
			return 1;
		}

		po = t.getPattern(pid);

		/* return false if the pattern_id was invalid/not loaded */
		if (!DATATYPES.TO(po)) {
			_m.sendMessage("Pattern id '" + pid + "' is invalid in validateField.", "200.4", 1);
			return 0;
		}

		for (c = 0; c < po.include.length; c++) {
			/*
				imported patterns only get applied when they return false and
				the current return value is true;
			*/
			tir = t.validateField(o, po.include[c]);
			if (ir && !tir) ir = 0;
		}

		v = t._getFieldValue(o);
		/*
			If the field value is 0, then the field type was not handled.
			This is not a bug, and 0 should be fine as an integer because field values will be 
			strings.
		*/
		/* check typeof == "number" because "" == 0*/
		if (DATATYPES.TN(v) && v == 0) {
			return 1;
		}
		if (DATATYPES.TU(v)) {
			_m.sendMessage("Value is undefined for " + n, "200.4", 1);
			return 0;
		}

		if (po.match) {
			try {
				re = new RegExp(po.match);
				switch (po.type) {
					case "replace":
						r = 1;
						if (DATATYPES.TS(po.replace)) {
							v = v.replace(re, po.replace);
							t._setFieldValue(o, v);
						}
						break;
					case "bool":
						HemiEngine.logDebug("Validating " + po.match + " against " + v);
						if (
							/*
								Obviously, an allow-null won't work if the validation
								pattern includes an import to a non-empty string.
							*/
							(po.allow_null && v.length == 0)
							||
							(v.match(re) != null) == po.comp
						) {
							r = 1;
						}
						break;
				}
			}
			catch (e) {
				_m.sendMessage("Error in validator.validateField:: " + (e.description ? e.description : e.message), "200.4", 1);
			}
		}

		if (po.type == "none") r = 1;
		/*
			if the return value is true, but the include return value is false
			set the return value to false
		*/
		if (r && !ir) r = 0;

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
			else{
				v = "";
			}
			return v;
		},
		decorateIn: function (i, f, v) {
			if (v && typeof v == "string") {
				v = v.split(/\r?\n|\r|\n/g);
			}
			else{
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
			changes: [],
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
				else if(inst.field(n)) z = inst.field(n)[v];
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
			else if(o) {
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
					// if(f.name == 'dataBytesStore') console.warn(f.name, val);
					//if(!inst.changes.includes(f.name)) inst.changes.push(f.name);
					inst.change(f.name);
					report('update', f.name, inst);
				}
				return decorateInOut(false, entity[f.name], f);
			};

			inst.dapi = (pn, ...v) => {
				if (v.length) {
					let p = pn.split(".");
					let op = p[0];
					//inst.changes.push(op);
					inst.change(op);
					let o = inst.entity;

					for (let i = 0; i < (p.length - 1); i++) {
						o = o[op];
						op = p[i];
					}
					// console.log(o, op, v[0]);
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

		});

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
				else if(f.type == "string" && typeof v == "string" && v.length == 0){
					v = null;
				}
				inst.api[n](v);
				if (fh) fh(e);
				inst.action(n);
			}
		}

		inst.patch = () => {
			let pent = {};
			let b1id = 0;
			if (inst.changes.length == 0) {
				return;
			}
			inst.fields.filter(f => {
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