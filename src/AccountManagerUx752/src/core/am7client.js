import m from 'mithril';
import { applicationPath } from './config.js';
import { am7model } from './model.js';
import Base64 from './base64.js';
import { cacheDb } from './cacheDb.js';

	var cache = {};
	var _cacheDbReady = false;

	// Initialize localStorage cache
	cacheDb.init().then(function(ok) {
		_cacheDbReady = ok;
	});
	var principal = 0;
	var sCurrentOrganization = 0;
	var sBase = applicationPath + "/rest";
	var sCache = sBase + "/cache";
	var sResource = sBase + "/resource";
	var sModelSvc = sBase + "/model";
	var sPrincipal = sBase + "/principal";
	var sSearch = sBase + "/search";
	var sPathSvc = sBase + "/path";
	var sMake = sBase + "/make";
	var sList = sBase + "/list";
	var sStreamSvc = sBase + "/stream";
	var sAuthZ = sBase + "/authorization";
	var sComm = sBase + "/community";
	var sCred = sBase + "/credential";
	var sPol = sBase + "/policy";
	var sCtl = sBase + "/control";
	var sReq = sBase + "/request";
	var sToken = sBase + "/token";
	var sAppr = sBase + "/approval";
	var sScr = sBase + "/script";
	var sTagSvc = sBase +"/tag";
	var sOlio = sBase + "/olio";
	var uwm = {
		consoleMode : 0,
		developerMode : 0,
		debugMode : 1,
		showDescription : 0,

		getUser : function(){
			return getPrincipal();
		},

		login : function(o, u, p, v2, fH){
			var vParms = (v2 ? v2 : {});
			am7client.loginWithPassword(o, u, p, function(b){
				if(b){
					am7client.clearCache(0,1);
					uwm.getUser().then((oU2)=>{
						if(uwm.altFlushSession) uwm.altFlushSession();
						vParms.user = oU2;
						if(fH) fH(oU2);
					});
				}
				else{
					console.log("Login: " + b);
					vParms.user = 0;
					if(fH) fH(null);
				}
			});

			return 1;

		},

		logout : async function(){
			return am7client.logout().then((b)=>{
				return b;
			});
		},

		getApiTypeView : function(sType){
			return applicationPath + "";
		},
		getDefaultParentForType : function(sType, vDef){
			if(uwm.defaultParentProvider){
				return uwm.defaultParentProvider(sType,vDef);
			}
			return Promise.resolve(vDef);
		},
		base64Encode : function(a){
			return Base64.encode(a);
		},
		base64Decode : function(a){
			return Base64.decode(a);
		},
		strToBin : function(s){
			var a = [];
			for(var i = 0; i < s.length;) a.push(s.charCodeAt(i++));
			return a;
		},
		binToStr : function(a){
			var s = [];
			if(!a) return s;
			for(var i = 0; i < a.length;) s.push(String.fromCharCode(a[i++]));
			return s.join("");
		},
		guid : function(){
			return am7model._page ? am7model._page.uid() : ("xxxxxxxx-xxxx-9xxx-yxxx-xxxxxxxxxxxx").replace(/[xy]/g, function(c) { const r = Math.random() * 16 | 0; return (c == 'x' ? r : (r & 0x3 | 0x8)).toString(16); });
		},
		/// sKey and sIv in Base64 string format
		///
		encipher : function(sText, sKey, sIv){
		 var enc = slowAES.encrypt(uwm.strToBin(sText),
        		slowAES.modeOfOperation.CBC,
        		slowAES.padding.PKCS7,
                uwm.strToBin(uwm.base64Decode(sKey)),
                uwm.strToBin(uwm.base64Decode(sIv))
          );
          return enc.cipher;

		},
		decipher : function(sText, sKey, sIv){

        	return slowAES.decrypt(uwm.strToBin(uwm.base64Decode(sText)),
        			slowAES.modeOfOperation.CBC,
        			slowAES.padding.PKCS7,
        			uwm.strToBin(uwm.base64Decode(sKey)),
        			uwm.strToBin(uwm.base64Decode(sIv))
        	);
		},

		handlers : {
			load : [],
			unload : []
		},
		addPageLoadHandler : function(f){
			uwm.handlers.load.push(f);
		},
		processLoadHandlers : function(){
			var aH = uwm.handlers.load, i = 0;
			for(; i < aH.length;) aH[i++]();
			aH.length = 0;
		},

	};

	function getCache(){
		return cache;
	}

	function cleanup(fH){
		return get(sModelSvc + "/cleanup",fH);
	}

	function clearCache(vType, bLocalOnly, fH){
		var sType = vType, oObj;
		if(vType != null && typeof vType == "object"){
			sType = vType[am7model.jsonModelKey];
			oObj = vType;
			return removeFromCache(sType, vType.objectId);
		}
		if(!sType){
			cache = {};
			if (_cacheDbReady) cacheDb.clearAll();
			return (bLocalOnly ? Promise.resolve(true) : get(sCache + "/clearAll",fH));
		}
		else{
			delete cache[sType];
			if (_cacheDbReady) cacheDb.removeByType(sType);
			if(sType.match(/^(project|lifecycle)$/gi)){
				delete cache["GROUP"];
				if (_cacheDbReady) cacheDb.removeByType("GROUP");
			}
			delete cache["COUNT"];
			if (_cacheDbReady) cacheDb.removeByType("COUNT");
			return (bLocalOnly ? Promise.resolve(true) : get(sCache + "/clear/" + sType,fH));
		}
	}

	function removeFromCache(vType, sObjId){
		var sType = vType;
		if(typeof vType == "object"){
			sType = vType[am7model.jsonModelKey];
			if(!sObjId) sObjId = vType.objectId;
		}
		if (_cacheDbReady) cacheDb.remove(sType, sObjId);
		if(!cache[sType]) return;
		for(var s in cache[sType]){
			delete cache[sType][s][sObjId];
		}
	}
	function getFromCache(sType, sAct, sObjId){
		// Try in-memory first (fastest)
		if(cache[sType] && cache[sType][sAct] && typeof cache[sType][sAct][sObjId] !== "undefined"){
			return cache[sType][sAct][sObjId];
		}
		// Try SQLite cache
		if (_cacheDbReady) {
			var v = cacheDb.get(sType, sAct, sObjId);
			if (v !== undefined) {
				// Promote to in-memory for subsequent fast access
				if(!cache[sType]) cache[sType] = {};
				if(!cache[sType][sAct]) cache[sType][sAct] = {};
				cache[sType][sAct][sObjId] = v;
				return v;
			}
		}
		return 0;
	}
	function addToCache(sType, sAct, sId, vObj){
		if(!cache[sType]) cache[sType] = {};
		if(!cache[sType][sAct]) cache[sType][sAct] = {};
		cache[sType][sAct][sId]=vObj;
		// Write-through to SQLite
		if (_cacheDbReady) {
			cacheDb.put(sType, sAct, sId, vObj);
		}
	}
	function newPrimaryCredential(sType, sObjId, oAuthN, fH){
		return post(sCred + "/" + sType + "/" + sObjId, oAuthN, fH);
	}

	function deleteObject(sType,sObjId, fH){
		delete cache[sType];
		delete cache["COUNT"];
		if (_cacheDbReady) {
			cacheDb.removeByType(sType);
			cacheDb.removeByType("COUNT");
		}
		return del(sModelSvc + "/" + sType + "/" + sObjId,fH);
	}

	function patchObject(sType, oObj, fH){
	   delete cache[sType];
	   // console.log(oObj);
	   return patch(sModelSvc, oObj, fH);
	}

	function create(sType,oObj, fH){
		delete cache[sType];
	   return post(sModelSvc, oObj, fH);
	}

	function updateBulk(sType,aObj, fH){
		console.error("REFACTOR: updateBulk");
		delete cache[sType];
	   //return Hemi.xml.postJSON(sResource + "/" + sType + "/bulk", aObj,fH,(fH ? 1 : 0));
	}

	function evaluate(oObj, fH){
		console.error("REFACTOR: evaluate");
		// return Hemi.xml.postJSON(sPol + "/evaluate",oObj,fH,(fH ? 1 : 0));
	}
	function executeScript(urn, fH){
		console.error("REFACTOR: execute");
		// return Hemi.xml.getJSON(sScr + "/execurn/" + urn,fH,(fH ? 1 : 0));
	}
	function executeScriptById(sObjectId, fH){
		console.error("REFACTOR: executeScriptById");
		//return Hemi.xml.getJSON(sScr + "/execid/" + sObjectId,fH,(fH ? 1 : 0));
	}
	function define(sObjectId, fH){
		console.error("REFACTOR: define");
	   //return Hemi.xml.getJSON(sPol + "/define/" + sObjectId,fH,(fH ? 1 : 0));
	}
	function searchCount(q, fH){
		return search(q, fH, 1);
	}
	let compLabel = {
		less_than: "<",
		less_than_or_equals: "<=",
		equals: "=",
		greater_than: ">",
		greater_than_or_equals: ">=",
		group_and: "&&",
		group_or: "||"
	};


	function newQuery(m){
		let q = {
			entity : {
				schema: "io.query",
				type: m,
				fields: [],
				order: 'ascending',
				comparator: "group_and",
				recordCount: 10,
				cache: true,
				request: am7model
					.inheritsFrom(m)
					.filter(m => m.query)
					.map(m => m.query)
					.flat(1)
					.filter( (v, i, z) => z.indexOf(v) == i)
			}
		};

		q.cache = (b) => {
			q.entity.cache = b;
		};

		q.range = function(s, c){
			q.entity.startRecord = parseInt(s);
			q.entity.recordCount = parseInt(c);
		};

		q.sort = function(s){
			if(s != undefined){
				q.entity.sortField = s;
			}
			return s;
		}
		q.order = function(o){
			if(o != undefined){
				q.entity.order = o;
			}
			return q.entity.order;
		};
		q.field = function(name, value){
			let fld = {
				comparator: "equals",
				name,
				value
			};
			q.entity.fields.push(fld);
			return fld;
		};
		q.keyField = function(afs){
			return "(" + afs.map(f =>  f.fields ? q.keyField(f.fields) : f.name + " " + compLabel[f.comparator] + " " + f.value).join(", ") + ")";
		};

		q.key = function(){
			let r = q.entity.request.join(",");
			if(!r.length) r = "*";
			let k = [
				q.entity.type,
				q.entity.order,
				q.entity.limit || false,
				q.entity.sortField || "id",
				q.entity.startRecord || 0,
				q.entity.recordCount || 10,
				am7model._page?.user?.objectId || "000",
				q.keyField(q.entity.fields)
			];
			return k.join("-");
		};
		return q;
	}
	/*
	function query(m, f){
		let r = [];
		let r1 = am7model.queryFields(m);
		let request = (f || r1).filter( (v, i, z) => z.indexOf(v) == i);
		return {
			type: m,
			comparator: "group_and",
			fields: [
				{
			  		"comparator" : "equals",
			  		"name" : "organizationId",
			  		"value" : page?.user?.organizationId
				}
			],
			order: 'ascending',
			recordCount: 10,
			request

		};
	}
	*/

	// In-flight request deduplication map: key -> Promise
	var _inflight = {};

	function search(q, fH, bCount){

		var sKey = q.key();
		let type = (q.entity.type + (bCount ? "-Count" : ""));
		if(q.entity.cache){
			var o = getFromCache(type, "GET", sKey);
			if(o){
				if(fH) fH(o);
				return o;
			}
		}
		// Deduplicate: if same query is already in-flight, reuse the promise
		var dedupKey = type + ":" + sKey;
		if (_inflight[dedupKey]) {
			return _inflight[dedupKey].then(function(v) { if(fH) fH(v); return v; });
		}
		var f = fH;
		var fc = function(v){if(q.entity.cache && typeof v != "undefined" && v != null){addToCache(type,"GET",sKey,v);} if(f) f(v);};
		var p = post(sModelSvc + "/search" + (bCount ? "/count" : ""), q.entity, fc);
		if (p && p.then) {
			_inflight[dedupKey] = p;
			p.then(function() { delete _inflight[dedupKey]; }, function() { delete _inflight[dedupKey]; });
		}
		return p;
	}
	function trace( bEnable, fH){
		if(typeof bEnable != "boolean") bEnable = true;
		return get(sAuthZ + "/unknown/trace/" + bEnable, fH);
	}
	function member(sObjectType, sObjectId, sField, sActorType, sActorId, bEnable, fH){
		return get(sAuthZ + "/" + sObjectType + "/" + sObjectId + "/member/" + sField + "/" + sActorType + "/" + sActorId + "/" + bEnable, fH);
	}
	function getApplicationProfile(fH){
		return get(sPrincipal + "/application", fH);
	}
	function getPrincipal(fH){
		return get(sPrincipal + "/", fH);
	}
	function getImageTags(sObjectId, fH){
		if(!sObjectId){
			console.error("Invalid objectId (" + sObjectId + ")");
			return;
		}
		let sType = "data.data";
		var o = getFromCache(sType, "GET", sObjectId);
		if(o){
			if(fH) fH(o);
			return o;
		}
		var f = fH;
		var fc = function(v){if(typeof v != "undefined" && v != null){addToCache(sType,"GET",sObjectId,v);} if(f) f(v);};
	    return get(sTagSvc + "/" + sObjectId, fc);
	}
	function applyImageTags(sObjectId, fH){
		if(!sObjectId){
			console.error("Invalid objectId (" + sObjectId + ")");
			return;
		}
		// Always call server — do NOT use cache for tag application
		var f = fH;
		var fc = function(v){if(f) f(v);};
	    return get(sTagSvc + "/apply/" + sObjectId, fc);
	}
	function getByObjectId(sType,sObjectId, fH){
		if(!sType || !sObjectId){
			console.error("Invalid type (" + sType + ") or objectId (" + sObjectId + ")");
			return;
		}
		var o = getFromCache(sType, "GET", sObjectId);
		if(o){
			if(fH) fH(o);
			return o;
		}
		var f = fH;
		var fc = function(v){if(typeof v != "undefined" && v != null){addToCache(sType,"GET",sObjectId,v);} if(f) f(v);};
	    return get(sModelSvc + "/" + sType + "/" + sObjectId, fc);
	}
	function getFullByObjectId(sType, sObjectId){
		return get(sModelSvc + "/" + sType + "/" + sObjectId + "/full");
	}
	function getByUrn(sType, urn, fH){
		var sKey = sType + "-" + urn;
		var o = getFromCache(sType, "GET", sKey);
		if(o){
			if(fH) fH(o);
			return o;
		}
		var f = fH;
		var fc = function(v){if(typeof v != "undefined" && v != null){addToCache(sType, "GET", sKey, v);} if(f) f(v);};
		console.error("REFACTOR: getByUrn");
	}

	function getByName(sType,sObjectId,sName,fH, bParent){
		var sKey = sObjectId + "-" + sName;
		//console.log("Get by name", sName);
		var o = getFromCache(sType, "GET", sKey);
		if(o){
			if(fH) fH(o);
			return o;
		}
		var f = fH;
		var fc = function(v){if(typeof v != "undefined" && v != null){addToCache(sType,"GET",sKey,v);} if(f) f(v);};
		return get(sModelSvc + "/" + sType + "/" + (bParent ? "parent/" : "") + sObjectId + "/" + sName,fc);
	}
	function getByNameInGroupParent(sType,sObjectId,sName,fH){
		console.warn("REFACTOR: getByNameInGroupParent");
		return getByName(sType, sObjectId, sName, fH, 1);
	}

    function stream(sObjectId, i, l, fH){
	    return get(sStreamSvc + "/" + sObjectId + "/" + i + "/" + (l || 0), fH);
	}

	function count(sType,sObjectId,fH){
		var o = getFromCache(sType, "COUNT", sObjectId);
		if(o){
			if(fH) fH(o);
			return o;
		}
		var f = fH;
		var fc = function(v){if(typeof v != "undefined" && v != null){addToCache(sType,"COUNT",sObjectId,v);} if(f) f(v);};
		//console.error("REFACTOR: count");
	    return get(sList + "/" + sType + "/" + sObjectId + "/count",fc);
	}
	/*
	function countInParent(sType,sObjectId,fH){
		var o = getFromCache(sType, "COUNT", sObjectId);
		if(o){
			if(fH) fH(o);
			return o;
		}
		var f = fH;
		var fc = function(v){if(typeof v != "undefined" && v != null){addToCache(sType,"COUNT",sObjectId,v);} if(f) f(v);};
	   return get(sList + "/" + sType + "/parent/" + sObjectId + "/count",fc);
	}
	*/
	function list(sType, sObjectId, sFields, iStart, iLength, fH){
		var sK = "LIST-" + sType + "-" + (sObjectId ? sObjectId : "0") + "-" + sFields + "-" + iStart + "-" + iLength;
		var o = getFromCache(sType, sK, sObjectId);
		if(o){
			if(fH) fH(o);
			return o;
		}
		var f = fH;
		var fc = function(v){if(typeof v != "undefined" && v != null){addToCache(sType,sK,sObjectId,v);} if(f) f(v);};
		return get(sList + "/" + sType + "/" + sObjectId + "/" + (sFields ? sFields + "/" : "") + iStart + "/" + iLength,fc);
	}
	/*
	function listInParent(sType, sObjectId, iStart, sFields, iLength, fH){

		var sK = "LIST-" + sType + "-" + (sObjectId ? sObjectId : "0") + "-" + sFields + "-" + iStart + "-" + iLength;
		var o = getFromCache(sType, sK, sObjectId);
		if(o){
			if(fH) fH(o);
			return o;
		}
		var f = fH;
		var fc = function(v){if(typeof v != "undefined" && v != null){addToCache(sType,sK,sObjectId,v);} if(f) f(v);};
		return get(sList + "/" + sType + "/parent/" + sObjectId + "/" + iStart + "/" + iLength,fc);
	}
	*/
	function findTags(sType, sObjId, fH){
		console.error("REFACTOR: findTags");
		// return Hemi.xml.getJSON(sSearch + "/" + sType + "/tags/" + sObjId,fH,(fH ? 1 : 0));
	}
	function findBy(sType, sObjId, oSearch, fH){
		console.error("REFACTOR: findBy");
		// return Hemi.xml.postJSON(sSearch + "/" + sType + "/" + sObjId,oSearch,fH,(fH ? 1 : 0));
	}
	function findByTag(sType, aTags, fH){
		var sKey = sType + "-" + aTags.map(t => t.id).join(",");
		var o = getFromCache("TAG-ALL", "POST", sKey);
		if(o){ if(fH) fH(o); return Promise.resolve(o); }
		var fc = function(v){ if(v) addToCache("TAG-ALL", "POST", sKey, v); if(fH) fH(v); };
		return post(sTagSvc + "/search/" + sType, aTags, fc);
	}
	function countByTag(sType, aTags, fH){
		var sKey = sType + "-count-" + aTags.map(t => t.id).join(",");
		var o = getFromCache("TAG-ALL", "POST", sKey);
		if(o){ if(fH) fH(o); return Promise.resolve(o); }
		var fc = function(v){ if(typeof v != "undefined" && v != null) addToCache("TAG-ALL", "POST", sKey, v); if(fH) fH(v); };
		return post(sTagSvc + "/search/" + sType + "/count", aTags, fc);
	}
	function findByAnyTag(sType, aTags, fH){
		var sKey = sType + "-" + aTags.map(t => t.id).join(",");
		var o = getFromCache("TAG-ANY", "POST", sKey);
		if(o){ if(fH) fH(o); return Promise.resolve(o); }
		var fc = function(v){ if(v) addToCache("TAG-ANY", "POST", sKey, v); if(fH) fH(v); };
		return post(sTagSvc + "/search/" + sType + "/any", aTags, fc);
	}
	function countByAnyTag(sType, aTags, fH){
		var sKey = sType + "-count-" + aTags.map(t => t.id).join(",");
		var o = getFromCache("TAG-ANY", "POST", sKey);
		if(o){ if(fH) fH(o); return Promise.resolve(o); }
		var fc = function(v){ if(typeof v != "undefined" && v != null) addToCache("TAG-ANY", "POST", sKey, v); if(fH) fH(v); };
		return post(sTagSvc + "/search/" + sType + "/any/count", aTags, fc);
	}
	function find(sType,sObjType,sPath,fH){
		// console.error("REFACTOR: find");
		return makeFind(sType,sObjType,sPath,0,fH);
	}
	function make(sType,sObjType,sPath,fH){
		// console.error("REFACTOR: make");
		return makeFind(sType,sObjType,sPath,1,fH);
	}

	function makeFind(sType, sObjType, sPath, bMake,fH){
		var sK = "FIND-" + sObjType;

		/// Band-aid - need to better encode these
		///
		if(sPath.match(/^\//) || sPath.match(/\./)){
			sPath = "B64-" + uwm.base64Encode(sPath).replace(/=/gi,"%3D");
		}
		var o = getFromCache(sType, sK, sPath);
		if(o){
			if(fH) fH(o);
			return o;
		}
		var f = fH;
		var fc = function(v){if(typeof v != "undefined" && v != null){addToCache(sType,sK,sPath,v);} if(f) f(v);};

		return get(sPathSvc + (bMake ? "/make/" : "/find/") + sType + "/" + sObjType + "/" + sPath,fc);
	}

	function getDotPath(path, sAlt){
		if(!path){
			return;
		}
		return path.replace(/^\//,"").replace(/\//gi,(sAlt ? sAlt : "."));
	}

	function logout(fH){
		var f = fH;
		am7client.currentOrganization = sCurrentOrganization = 0;
		am7client.clearCache(0,1);
		return get(applicationPath + "/rest/logout").then((b) => {
			if(f){
				f(b);
			}
			return b;
		});
	}

	function loginWithPassword(sOrg, sName, sCred, fH){
		let cred = {
			schema: "auth.credential",
			name: sName,
			credential: Base64.encode(sCred),
			organizationPath: sOrg,
			type: "hashed_password"
		};
		return login(cred, fH);
	}

	var sConfig = sBase + "/config";

	async function getFeatureConfig() {
		return get(sConfig + "/features");
	}

	async function updateFeatureConfig(config) {
		return m.request({
			method: 'PUT',
			url: sConfig + "/features",
			withCredentials: true,
			body: config
		}).catch(function(e) {
			console.error("Failed to update feature config", e);
			return null;
		});
	}

	async function getAvailableFeatures() {
		return get(sConfig + "/features/available");
	}

	var sAccess = sBase + "/access";

	async function accessRequestList(view, status, startIndex, count) {
		var params = [];
		if (view) params.push("view=" + encodeURIComponent(view));
		if (status) params.push("status=" + encodeURIComponent(status));
		if (startIndex > 0) params.push("startIndex=" + startIndex);
		if (count > 0) params.push("count=" + count);
		var qs = params.length > 0 ? "?" + params.join("&") : "";
		return get(sAccess + "/requests" + qs);
	}

	async function accessRequestSubmit(requestBody) {
		return post(sAccess + "/requests", requestBody);
	}

	async function accessRequestUpdate(objectId, patchBody) {
		return patch(sAccess + "/requests/" + encodeURIComponent(objectId), patchBody);
	}

	async function accessRequestableList(type, startIndex, count) {
		var params = [];
		if (type) params.push("type=" + encodeURIComponent(type));
		if (startIndex > 0) params.push("startIndex=" + startIndex);
		if (count > 0) params.push("count=" + count);
		var qs = params.length > 0 ? "?" + params.join("&") : "";
		return get(sAccess + "/requestable" + qs);
	}

	async function accessRequestNotify(objectId) {
		return post(sAccess + "/requests/" + encodeURIComponent(objectId) + "/notify");
	}

	var sWebAuthn = sBase + "/credential/webauthn";

	function webauthnSupported() {
		return !!(window.PublicKeyCredential && navigator.credentials);
	}

	async function webauthnGetRegistrationOptions() {
		return get(sWebAuthn + "/register");
	}

	async function webauthnRegister(name) {
		let options = await get(sWebAuthn + "/register");
		if (!options || !options.challenge) return null;

		options.challenge = _b64ToBuffer(options.challenge);
		options.user.id = _b64ToBuffer(options.user.id);
		if (options.excludeCredentials) {
			options.excludeCredentials = options.excludeCredentials.map(c => ({
				...c, id: _b64ToBuffer(c.id)
			}));
		}

		let credential;
		try {
			credential = await navigator.credentials.create({ publicKey: options });
		} catch (e) {
			console.error("WebAuthn create failed", e);
			return null;
		}

		let regData = {
			credentialId: _bufferToB64(credential.rawId),
			publicKey: _bufferToB64(credential.response.getPublicKey ? credential.response.getPublicKey() : new Uint8Array()),
			clientDataJSON: _bufferToB64(credential.response.clientDataJSON),
			attestationObject: _bufferToB64(credential.response.attestationObject),
			name: name || "Passkey"
		};

		return post(sWebAuthn + "/register", regData);
	}

	async function webauthnAuthenticate(sOrg, sName) {
		let userPath = sOrg + "/" + sName;
		let options = await get(sWebAuthn + "/auth?user=" + encodeURIComponent(userPath));
		if (!options || !options.challenge) return null;

		options.challenge = _b64ToBuffer(options.challenge);
		if (options.allowCredentials) {
			options.allowCredentials = options.allowCredentials.map(c => ({
				...c, id: _b64ToBuffer(c.id)
			}));
		}

		let assertion;
		try {
			assertion = await navigator.credentials.get({ publicKey: options });
		} catch (e) {
			console.error("WebAuthn get failed", e);
			return null;
		}

		let authData = {
			credentialId: _bufferToB64(assertion.rawId),
			clientDataJSON: _bufferToB64(assertion.response.clientDataJSON),
			authenticatorData: _bufferToB64(assertion.response.authenticatorData),
			signature: _bufferToB64(assertion.response.signature),
			organizationPath: sOrg
		};

		return post(sWebAuthn + "/auth", authData);
	}

	async function webauthnListCredentials() {
		return get(sWebAuthn + "/credentials");
	}

	async function webauthnDeleteCredential(credentialId) {
		return del(sWebAuthn + "/" + encodeURIComponent(credentialId));
	}

	function _b64ToBuffer(b64) {
		let s = b64.replace(/-/g, '+').replace(/_/g, '/');
		while (s.length % 4) s += '=';
		let binary = atob(s);
		let bytes = new Uint8Array(binary.length);
		for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
		return bytes.buffer;
	}

	function _bufferToB64(buffer) {
		let bytes = new Uint8Array(buffer);
		let binary = '';
		for (let i = 0; i < bytes.length; i++) binary += String.fromCharCode(bytes[i]);
		return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
	}

	function _handle401(e, url) {
		if (e && e.code === 401) {
			console.warn("[am7client] 401 Unauthorized on " + url + " — redirecting to login");
			let currentRoute = m.route.get();
			if (currentRoute && currentRoute !== "/sig") {
				sessionStorage.setItem("am7.returnRoute", currentRoute);
			}
			m.route.set("/sig");
			return true;
		}
		return false;
	}

	function get(url, fH){
		return m.request({
			method: 'GET',
			url,
			withCredentials: true
		}).then((x) => {
			if(fH){
				fH(x);
			}
			return x;
		}).catch((x) =>{
			if (_handle401(x, url)) return;
			console.error("Failed to get " + url);
			if(fH){
				fH();
			}
		});
	}

	function del(url, fH){
		return m.request({
			method: 'DELETE',
			url,
			withCredentials: true
		}).then((x) => {
			if(fH){
				fH(x);
			}
			return x;
		}).catch((e) => {
			if (_handle401(e, url)) return null;
			console.error("Failed to delete " + url, e);
			if(fH){
				fH(null);
			}
			return null;
		});
	}

	function post(url, data, fH){
		return m.request({
			method: 'POST',
			url,
			withCredentials: true,
			body: data
		}).then((x) => {
			if(fH){
				fH(x);
			}
			return x;
		}).catch((x) =>{
			if (_handle401(x, url)) return;
			console.error("Failed to post " + url, x);
			if(fH){
				fH();
			}
		});
	}

	function patch(url, data, fH){
		return m.request({
			method: 'PATCH',
			url,
			withCredentials: true,
			body: data
		}).then((x) => {
			if(fH){
				fH(x);
			}
			return x;
		}).catch((x) => {
			if (_handle401(x, url)) return;
			console.error("Failed to patch " + url, x);
			if(fH){
				fH();
			}
		});
	}

	function login(cred, fH){
		am7client.currentOrganization = sCurrentOrganization = cred.organizationPath;
		return post(applicationPath + "/rest/login", cred, fH);
	}

	function getObjectAuthorization(sType, sObjectId, fH){
		var sK = sObjectId + " Authorization";
		var o = getFromCache(sType, "GET", sK);
		if(o){
			if(fH) fH("", o);
			return o;
		}
		var f = fH;
		var fc = function(v){if(typeof v != "undefined" && v != null){addToCache(sType,"GET",sK, v);} if(f) f(v);};
		console.error("REFACTOR: getObjectAuthorization");
		// return Hemi.xml.getJSON(sAuthZ + "/" + sType + "/system/" + sObjectId,fc,(fH ? 1 : 0));
	}
	function listSystemPermissions(fH){
		var sK = sCurrentOrganization + " SystemPermissions";
		var o = getFromCache("PERMISSION", "GET", sK);
		if(o){
			if(fH) fH(o);
			return o;
		}
		var f = fH;
		var fc = function(v){if(typeof v != "undefined" && v != null){addToCache("PERMISSION","GET",sK,v);} if(f) f(v);};
		console.error("REFACTOR: listSystemPermissions - moved to ApplicationProfile");
		// return Hemi.xml.getJSON(sAuthZ + "/PERMISSION/systemPermissions",fc,(fH ? 1 : 0));
	}
	function listSystemRoles(fH){
		var sK = sCurrentOrganization + " SystemRoles";
		var o = getFromCache("ROLE", "GET", sK);
		if(o){
			if(fH) fH(o);
			return o;
		}
		var f = fH;
		var fc = function(v){if(typeof v != "undefined" && v != null){addToCache("ROLE","GET",sK,v);} if(f) f(v);};
		console.error("REFACTOR: listSystemRoles - moved to ApplicationProfile");
		// return Hemi.xml.getJSON(sAuthZ + "/ROLE/systemRoles",fc,(fH ? 1 : 0));
	}
	function listSystemPolicies(fH){
		var sK = sCurrentOrganization + " SystemPolicies";
		var o = getFromCache("POLICY", "GET", sK);
		if(o){
			if(fH) fH(o);
			return o;
		}
		var f = fH;
		var fc = function(v){if(typeof v != "undefined" && v != null){addToCache("POLICY","GET",sK,v);} if(f) f(v);};
		console.error("REFACTOR: listSystemPolicies");
		//return Hemi.xml.getJSON(sAuthZ + "/ROLE/systemPolicies",fc,(fH ? 1 : 0));
	}

	function permitSystem(sType, sObjectId, sActorType, sActorId, bView, bEdit, bDelete, bCreate, fH){
		console.error("REFACTOR: permitSystem");
		// return Hemi.xml.getJSON(sAuthZ + "/" + sType + "/" + sObjectId + "/permit/" + sActorType + "/" + sActorId + "/" + bView + "/" + bEdit + "/" + bDelete + "/" + bCreate,fH,(fH ? 1 : 0));
	}
	function permit(sType, sObjectId, sActorType, sActorId, sPermId, bEnable, fH){
		console.error("REFACTOR: permit");
		//return Hemi.xml.getJSON(sAuthZ + "/" + sType + "/" + sObjectId + "/permit/" + sActorType + "/" + sActorId + "/" + sPermId + "/" + bEnable,fH,(fH ? 1 : 0));
	}
	function listMembers(sType, sObjectId, sActorType, iStart, iCount, fH){
		var sK = "LIST-" + sType + "-" + (sObjectId ? sObjectId : "0") + "-" + sActorType + "-" + iStart + "-" + iCount;
		var o = getFromCache(sType, sK, sObjectId);
		if(o){
			if(fH) fH(o);
			return o;
		}
		var f = fH;
		var fc = function(v){if(typeof v != "undefined" && v != null){addToCache(sType,sK,sObjectId,v);} if(f) f(v);};
		return get(sAuthZ + "/" + sType + "/" + sObjectId + "/" + sActorType + "/" + iStart + "/" + iCount, fc);
	}
	function countMembers(sType, sObjectId, sActorType, fH){
		var sK = "COUNT-" + sType + "-" + (sObjectId ? sObjectId : "0") + "-" + sActorType;
		var o = getFromCache(sType, sK, sObjectId);
		if(o){
			if(fH) fH(o);
			return o;
		}
		var f = fH;
		var fc = function(v){if(typeof v != "undefined" && v != null){addToCache(sType,sK,sObjectId,v);} if(f) f(v);};
		return get(sAuthZ + "/" + sType + "/" + sObjectId + "/" + sActorType + "/count",fc);
	}
	function membershipStats(sType, sParticipantType, iContainerId, iLimit, fH){
		var sPart = sParticipantType || "any";
		var iCont = iContainerId || 0;
		var iLim = iLimit || 0;
		var sK = "STATS-" + sType + "-" + sPart + "-" + iCont + "-" + iLim;
		var o = getFromCache(sType, sK, null);
		if(o){
			if(fH) fH(o);
			return o;
		}
		var f = fH;
		var fc = function(v){if(typeof v != "undefined" && v != null){addToCache(sType,sK,null,v);} if(f) f(v);};
		return get(sAuthZ + "/" + sType + "/stats/" + sPart + "/" + iCont + "/" + iLim, fc);
	}

	function personalityProfile(sObjectId, fH){
		return get(sOlio + "/profile/" + sObjectId, fH);
	}

	function profileComparison(sObjectId1, sObjectId2, fH){
		return get(sOlio + "/compare/" + sObjectId1 + "/" + sObjectId2, fH);
	}

	/**
	 * Apply patches to a cached object to keep it in sync with server state.
	 * Patches are in the format: { path: "state.currentLocation", value: {...} }
	 * @param {Object} obj - The object to patch
	 * @param {Array} patches - Array of patch objects with 'path' and 'value' properties
	 */
	function applyPatches(obj, patches) {
		if (!obj || !patches || !Array.isArray(patches)) return;

		patches.forEach(function(patch) {
			if (!patch.path || patch.value === undefined) return;

			let pathParts = patch.path.split('.');
			let target = obj;

			// Navigate to parent of final property
			for (let i = 0; i < pathParts.length - 1; i++) {
				let part = pathParts[i];
				if (target[part] === undefined || target[part] === null) {
					target[part] = {};
				}
				target = target[part];
			}

			// Set the final property
			let finalProp = pathParts[pathParts.length - 1];
			target[finalProp] = patch.value;
		});
	}

	/**
	 * Refresh a nested object reference from the server.
	 * Useful when a nested foreign reference becomes stale.
	 * @param {Object} obj - The parent object containing the nested reference
	 * @param {string} path - Dot-separated path to the nested object (e.g., "state.currentLocation")
	 * @param {string} type - The schema type of the nested object (e.g., "data.geoLocation")
	 * @param {boolean} full - Whether to fetch full object with all fields (default: true)
	 * @returns {Promise} - Resolves with the refreshed nested object
	 */
	async function refreshNested(obj, path, type, full) {
		if (!obj || !path || !type) return null;
		if (full === undefined) full = true;

		let pathParts = path.split('.');
		let target = obj;

		// Navigate to the nested object
		for (let i = 0; i < pathParts.length; i++) {
			if (target[pathParts[i]] === undefined || target[pathParts[i]] === null) {
				return null;
			}
			target = target[pathParts[i]];
		}

		// Get the objectId of the nested object
		let nestedId = target.objectId || target.id;
		if (!nestedId) return null;

		// Fetch fresh data from server - use full fetch by default to get all fields
		let fresh = full ? await getFullByObjectId(type, nestedId) : await getByObjectId(type, nestedId);
		if (!fresh) return null;

		// Navigate to parent and update the reference
		let parent = obj;
		for (let i = 0; i < pathParts.length - 1; i++) {
			parent = parent[pathParts[i]];
		}
		let finalProp = pathParts[pathParts.length - 1];
		parent[finalProp] = fresh;

		return fresh;
	}

	/**
	 * Sync a cached object with a partial update from the server.
	 * Merges the update into the cached object, preserving unaffected properties.
	 * @param {Object} cached - The cached object to update
	 * @param {Object} update - Partial update from the server
	 * @param {boolean} deep - Whether to perform deep merge (default: true)
	 */
	function syncObject(cached, update, deep) {
		if (!cached || !update) return cached;
		deep = deep !== false;

		Object.keys(update).forEach(function(key) {
			if (deep && typeof update[key] === 'object' && update[key] !== null && !Array.isArray(update[key])) {
				if (typeof cached[key] === 'object' && cached[key] !== null) {
					syncObject(cached[key], update[key], deep);
				} else {
					cached[key] = update[key];
				}
			} else {
				cached[key] = update[key];
			}
		});

		return cached;
	}

	/**
	 * Sync multiple nested paths on an object from a server response.
	 * Handles the sync data format returned by GameUtil.createSyncData().
	 *
	 * @param {Object} obj - The cached object to sync (e.g., player character)
	 * @param {Object} syncData - Server response containing sync data and optional patches
	 * @param {Object} pathMapping - Optional mapping of response keys to object paths
	 *                               e.g., { "stateSnapshot": "state", "statistics": "statistics" }
	 *
	 * Example usage:
	 *   am7client.syncFromResponse(player, resp, {
	 *     "stateSnapshot": "state",
	 *     "location": "state.currentLocation",
	 *     "statistics": "statistics",
	 *     "profile": "profile"
	 *   });
	 */
	function syncFromResponse(obj, syncData, pathMapping) {
		if (!obj || !syncData) return;

		// Apply patches if present (most precise method)
		if (syncData.patches && Array.isArray(syncData.patches)) {
			applyPatches(obj, syncData.patches);
		}

		// Apply mapped snapshots if no patches or as supplement
		if (pathMapping) {
			Object.keys(pathMapping).forEach(function(responseKey) {
				if (syncData[responseKey]) {
					let targetPath = pathMapping[responseKey];
					let data = syncData[responseKey];

					// Navigate to target and sync
					let pathParts = targetPath.split('.');
					let target = obj;
					for (let i = 0; i < pathParts.length - 1; i++) {
						if (!target[pathParts[i]]) {
							target[pathParts[i]] = {};
						}
						target = target[pathParts[i]];
					}

					let finalKey = pathParts[pathParts.length - 1];
					if (typeof data === 'object' && !Array.isArray(data)) {
						if (!target[finalKey]) {
							target[finalKey] = {};
						}
						syncObject(target[finalKey], data, true);
					} else {
						target[finalKey] = data;
					}
				}
			});
		}
	}

	/**
	 * Standard path mappings for common Olio sync scenarios
	 */
	var SYNC_MAPPINGS = {
		// For situation/move responses
		situation: {
			"stateSnapshot": "state",
			"location": "state.currentLocation"
		},
		// For full character sync
		full: {
			"state": "state",
			"statistics": "statistics",
			"instinct": "instinct",
			"store": "store",
			"profile": "profile"
		},
		// For combat-related sync
		combat: {
			"stateSnapshot": "state",
			"statistics": "statistics"
		},
		// For apparel/store sync
		apparel: {
			"store": "store",
			"apparel": "store.apparel"
		}
	};

	function getUserPersonObject(sId,fH){
		return new Promise((res, rej) => {
			if(!sId){
				getPrincipal().then((o)=>{
					var o = getPrincipal();
					if(o) sId = o.objectId;
					get(sPrincipal + "/person" + (sId ? "/" + sId : "")).then((x)=>{
						if(fH) fH("",x);
						res(x);
					});
				});
			}
			else get(sPrincipal + "/person" + (sId ? "/" + sId : ""),fH).then((x)=>{
				if(fH) fH("",x);
				res(x);
			});
		});
	}

	function getUserObject(sType,sOType,fH){
		if(!sType && !sOType) return getPrincipal(fH);
		return get(sModelSvc + "/" + sType + "/user/" + sOType,fH);
	}

	function listUserRoles(sObjId, fH){
		if(!sObjId) sObjId = null;
		console.error("REFACTOR: listUserRoles");
		// return Hemi.xml.getJSON(sAuthZ + "/USER/roles/" + sObjId,fH,(fH ? 1 : 0));
	}

	function listEntitlementsForType(sType, sObjId, fH){
		if(!sObjId) sObjId = null;
		console.error("REFACTOR: listEntitlementsForType");
		// return Hemi.xml.getJSON(sAuthZ + "/" + sType + "/entitlements/" + sObjId,fH,(fH ? 1 : 0));
	}


	function mediaDataPath(oObj, bThumb, sSize){
		if(!sSize) sSize = "100x100";
		return applicationPath + "/" + (bThumb ? "thumbnail" : "media") + "/" + am7client.dotPath(oObj.organizationPath) + "/data.data" + oObj.groupPath + "/" + oObj.name + (bThumb ? "/" + sSize : "");
	}

	function getIsRequestable(sType,sId,fH){
		if(!sType || !sId) return 0;
		console.error("REFACTOR: getIsRequestable");
		// return Hemi.xml.getJSON(sAppr + "/requestable/" + sType + "/" + sId,fH,(fH ? 1 : 0));
	}

	function getOwnerApprovalPolicy(fH){
		console.error("REFACTOR: getOwnerApprovalPolicy");
		// return Hemi.xml.getJSON(sAppr + "/policy/owner",fH,(fH ? 1 : 0));
	}

	function attachPolicy(sType,sId,sPid,fH){
		if(!sType || !sId || !sPid) return 0;
		console.error("REFACTOR: attachPolicy");
		// return Hemi.xml.getJSON(sAppr + "/policy/attach/" + sType + "/" + sId + "/" + sPid,fH,(fH ? 1 : 0));
	}

	var am7client = {
		executeScript : executeScript,
		executeScriptById : executeScriptById,
		dotPath : getDotPath,
		define : define,
		member,
		evaluate : evaluate,
		find : find,
		ownerApprovalPolicy : getOwnerApprovalPolicy,
		isRequestable : getIsRequestable,
		attachPolicy : attachPolicy,
		findByTag : findByTag,
		findByAnyTag : findByAnyTag,
		findBy : findBy,
		search : search,
		// query : query,
		newQuery : newQuery,
		searchCount : searchCount,
		countByTag : countByTag,
		countByAnyTag : countByAnyTag,
		findTags : findTags,
		make : make,
		mediaDataPath : mediaDataPath,
		/*
		listInParent : listInParent,
		countInParent : countInParent,
		*/
		list : list,
		count: count,
		stream: stream,
		get : getByObjectId,
		getFull: getFullByObjectId,
		getByUrn: getByUrn,
		getByName : getByName,
		getByNameInGroupParent : getByNameInGroupParent,
		patch: patchObject,
		create,
		updateBulk : updateBulk,
		delete : deleteObject,
		cache : getCache,
		getCache: getFromCache,
		principal : getPrincipal,
		entitlements : listEntitlementsForType,
		userRoles : listUserRoles,
		authorization : getObjectAuthorization,
		permit : permit,
		permitSystem : permitSystem,
		members : listMembers,
		countMembers,
		membershipStats,
		systemRoles : listSystemRoles,
		systemPolicies : listSystemPolicies,
		systemPermissions : listSystemPermissions,
		user: getUserObject,
		application : getApplicationProfile,
		userPerson : getUserPersonObject,
		newPrimaryCredential : newPrimaryCredential,
		clearCache,
		cleanup,
		trace,
		clearAuthorizationCache : function(fH){
			return get(sCache + "/clearAuthorization",fH);
		},
		patchAttribute: async function(o, n, v){
			let a = am7client.getAttribute(o, n);
			if(!a){
				a = am7client.addAttribute(o, n, v);
				await create(a[am7model.jsonModelKey], a);
			}
			else{
				a[am7model.jsonModelKey] = "common.attribute";
				a.value = v;
				await patchObject(a[am7model.jsonModelKey], a);
			}
			return a;
		},
		getAttribute : function(o,n){
			if(!o || o == null || !o.attributes){
                return null;
            }
            let r = o.attributes.filter(a => {if(a.name === n) return a;});
            return (r ? r[0] : null);
		},
		getAttributeValue : function(o,n,d){
			var a = am7client.getAttribute(o,n);
			if(!a) return d;
			return a.value;
		},
		addAttribute : function(o,s,v){
			if(!o.attributes) o.attributes = [];
			let a = am7client.newAttribute(s,v);
			a.referenceModel = o[am7model.jsonModelKey];
			a.referenceId = o.id || 0;
			o.attributes.push(a);
			return a;
		},
		newAttribute : function(s, v){
			var a = am7model.newPrimitive("common.attribute"),x=null;
			a.valueType = "string";
			a.name = s;

			if(typeof v == "string") x = v;
			else if(typeof v == "number"){
				let vs = "" + v;
				if(vs.match(/\./)) a.valueType = 'double';
				else a.valueType = 'int';
				x = v;
			}
			else if(typeof v == "object" && v instanceof Array) x = v;
			else console.warn("Expected string or array object for value");
			a.value = x;
			return a;
		},
		removeAttribute : function(o, n){
			if(!o.attributes || o.attributes == null) return 0;
			for(var i = 0; i < o.attributes.length; i++){
				if(o.attributes[i].name == n){
					o.attributes.splice(i,1);
					break;
				}
			}
		},
		login : login,
		loginWithPassword : loginWithPassword,
		logout : logout,
		webauthnSupported,
		webauthnRegister,
		webauthnAuthenticate,
		webauthnListCredentials,
		webauthnDeleteCredential,
		accessRequestList,
		accessRequestSubmit,
		accessRequestUpdate,
		accessRequestableList,
		accessRequestNotify,
		getFeatureConfig,
		updateFeatureConfig,
		getAvailableFeatures,
		base:function(){
			return sBase;
		},
		getImageTags,
		applyImageTags,
		personalityProfile,
		profileComparison,
		// State synchronization helpers
		applyPatches,
		refreshNested,
		syncObject,
		syncFromResponse,
		SYNC_MAPPINGS,
		// Cache metrics (dev mode)
		cacheMetrics: function() { return _cacheDbReady ? cacheDb.getMetrics() : { hits: 0, misses: 0, writes: 0, evictions: 0, backend: 'memory' }; },
		cacheEntryCount: function() { return _cacheDbReady ? cacheDb.entryCount() : Object.keys(cache).length; }
	};

export { uwm, am7client };
export default am7client;
