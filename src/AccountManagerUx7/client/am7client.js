
 (function(){
	
	if(!window.uwm){
		var cache = {};
		var principal = 0;
		var sCurrentOrganization = 0;
		var sBase = g_application_path + "/rest";
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
		window.uwm = {
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
						window.uwm.getUser().then((oU2)=>{
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
				return g_application_path + "";
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
				return page.uid();
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
	}

	function getCache(){
		return cache;
	}

	function cleanup(fH){
		return get(sModelSvc + "/cleanup",fH);
	}

	function clearCache(vType, bLocalOnly, fH){
		var sType = vType, oObj;
		if(vType != null && typeof vType == "object"){
			sType = vType.model;
			oObj = vType;
			return removeFromCache(sType, vType.objectId);
		}
		if(!sType){
			cache = {};
			return (bLocalOnly ? 1 : get(sCache + "/clearAll",fH));
		}
		else{
			delete cache[sType];
			if(sType.match(/^(project|lifecycle)$/gi)) delete cache["GROUP"];
			delete cache["COUNT"];
			return (bLocalOnly ? 1 : get(sCache + "/clear/" + sType,fH));
		}
	}

	function removeFromCache(vType, sObjId){
		var sType = vType;
		if(typeof vType == "object"){
			sType = vType.model;
			if(!sObjId) sObjId = vType.objectId;
		}
		if(!cache[sType]) return;
		for(var s in cache[sType]){
			delete cache[sType][s][sObjId];
		}
	}
	function getFromCache(sType, sAct, sObjId){
		if(!cache[sType]) return 0;
		if(!cache[sType][sAct]) return 0;
		if(typeof cache[sType][sAct][sObjId]=="undefined") return 0;
		return cache[sType][sAct][sObjId];
	}
	function addToCache(sType, sAct, sId, vObj){
		if(!cache[sType]) cache[sType] = {};
		if(!cache[sType][sAct]) cache[sType][sAct] = {};
		cache[sType][sAct][sId]=vObj;
		
	}
	function newPrimaryCredential(sType, sObjId, oAuthN, fH){
		return post(sCred + "/" + sType + "/" + sObjId, oAuthN, fH);
	}

	function deleteObject(sType,sObjId, fH){
		delete cache[sType];

		return del(sModelSvc + "/" + sType + "/" + sObjId,fH);
	}
	
	function patchObject(sType, oObj, fH){
	   delete cache[sType];
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
				model: "io.query",
				type: m,
				fields: [],
				order: 'ascending',
				comparator: "group_and",
				recordCount: 10,
				request: am7model
					.inheritsFrom(m)
					.filter(m => m.query)
					.map(m => m.query)
					.flat(1)
					.filter( (v, i, z) => z.indexOf(v) == i)
			}
		};
		q.field = function(name, value){
			q.entity.fields.push({
				comparator: "equals",
				name,
				value
			});
			return q;
		};
		q.keyField = function(afs){
			return "(" + afs.map(f => f.name + " " + compLabel[f.comparator] + " " + f.value).join(", ") + ")";
		};

		q.key = function(){
			let r = q.entity.request.join(",");
			if(!r.length) r = "*";
			let k = [
				q.entity.type,
				q.entity.order,
				q.entity.limit || false,
				q.entity.sortBy || "id",
				page?.user?.objectId || "000",
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

	function search(q, fH, bCount){
		var sKey = q.key();
		var o = getFromCache(q.type, "GET", sKey);
		if(o){
			if(fH) fH(o);
			return o;
		}
		var f = fH;
		var fc = function(v){if(typeof v != "undefined" && v != null){addToCache(sType,"GET",sKey,v);} if(f) f(v);};
		return post(sModelSvc + "/search" + (bCount ? "/count" : ""), q.entity, fH);
	}

	function member(sObjectType, sObjectId, sActorType, sActorId, bEnable, fH){
		return get(sAuthZ + "/" + sObjectType + "/" + sObjectId + "/member/" + sActorType + "/" + sActorId + "/" + bEnable, fH);
	}
	function getApplicationProfile(fH){
		return get(sPrincipal + "/application", fH);
	}
	function getPrincipal(fH){
		return get(sPrincipal + "/", fH);
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
	function findByTag(sType, oSearch, fH){
		console.error("REFACTOR: findByTag");
		// return Hemi.xml.postJSON(sSearch + "/" + sType + "/tags",oSearch,fH,(fH ? 1 : 0));
	}
	function countByTag(sType, oSearch, fH){
		console.error("REFACTOR: countByTag");
		// return Hemi.xml.postJSON(sSearch + "/" + sType + "/tags/count",oSearch,fH,(fH ? 1 : 0));
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

		if(bMake){
			console.log("MAKE " + sPath);
		}


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
		return get(g_application_path + "/rest/logout").then((b) => {
			if(f){
				f(b);
			}
			return b;
		});
	}

	function loginWithPassword(sOrg, sName, sCred, fH){
		let cred = {
			model: "auth.credential",
			name: sName,
			credential: Base64.encode(sCred),
			organizationPath: sOrg,
			type: "hashed_password"
		};
		return login(cred, fH);
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
			console.error("Failed to post " + url);
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
		});
	}

	function login(cred, fH){
		am7client.currentOrganization = sCurrentOrganization = cred.organizationPath;
		return post(g_application_path + "/rest/login", cred, fH);
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


	function mediaDataPath(oObj, bThumb){
		return g_application_path + "/" + (bThumb ? "thumbnail" : "media") + "/" + am7client.dotPath(o.organizationPath) + "/data.data" + o.groupPath + "/" + o.name + (bThumb ? "/100x100" : "");
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
	function newSortQuery(sField, sOrder){
		console.error("REFACTOR: newSortQuery");
		var oSort = new org.cote.objects.sortQueryType();
		oSort.sortOrder = sOrder;
		oSort.sortField = sField;
		return oSort;
	}
	function newFieldMatch(sType, sFieldName, sComp, sPattern){
		console.error("REFACTOR: newFieldMatch");
		var oF = new org.cote.objects.fieldMatch();
		oF.comparator = sComp;
		oF.dataType = sType;
		oF.encodedValue = sPattern;
		oF.fieldName = sFieldName;
		return oF;
	}
	function newSearchRequest(sType, sActorType, sActorId, iStartRecord, iRecordCount, bFull, oSort, aFields){
		console.error("REFACTOR: newSearchRequest");
		var oR = new org.cote.objects.objectSearchRequestType();
		oR.objectType = sType;
		oR.sort = oSort;
		oR.organizationId = 0;
		oR.fullRecord = true;
		oR.populateGroup = false;
		oR.startRecord = iStartRecord;
		oR.recordCount = iRecordCount;
		oR.paginate = true;
		oR.includeThumbnail = false;
		oR.contextActorType = (sActorType ? sActorType : "UNKNOWN");
		oR.contextActorId = (sActorId ? sActorId : null);
		oR.fields = (aFields ? aFields : []);
		return oR;
	}
	window.am7client = {
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
		findBy : findBy,
		search : search,
		// query : query,
		newQuery : newQuery,
		searchCount : searchCount,
		newSortQuery : newSortQuery,
		newFieldMatch : newFieldMatch,
		newSearchRequest : newSearchRequest,
		countByTag : countByTag,
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
		systemRoles : listSystemRoles,
		systemPolicies : listSystemPolicies,
		systemPermissions : listSystemPermissions,
		user: getUserObject,
		application : getApplicationProfile,
		userPerson : getUserPersonObject,
		newPrimaryCredential : newPrimaryCredential,
		clearCache,
		cleanup,
		clearAuthorizationCache : function(fH){
			return get(sCache + "/clearAuthorization",fH);
		},
		patchAttribute: async function(o, n, v){
			let a = am7client.getAttribute(o, n);
			if(!a){
				a = am7client.addAttribute(o, n, v);
				await create(a.model, a);
			}
			else{
				a.model = "common.attribute";
				a.value = v;
				await patchObject(a.model, a);
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
			a.referenceModel = o.model;
			a.referenceId = o.id || 0;
			o.attributes.push(a);
			return a;
		},
		newAttribute : function(s, v){
			var a = am7model.newPrimitive("common.attribute"),x=null;
			a.valueType = "STRING";
			a.name = s;
			
			if(typeof v == "string") x = v;
			else if(typeof v == "number"){
				let vs = "" + v;
				if(vs.match(/\./)) a.valueType = 'DOUBLE';
				else a.valueType = 'INT';
				x.push(vs);
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
		base:function(){
			return sBase;
		}
	};

	
}());

