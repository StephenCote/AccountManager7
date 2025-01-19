(function(exports){
    "use strict";
    const fs = require("fs");
    const path = require("path");
    const http = require("http");
    const axios = require("axios");
    var uuid = require('uuid');
    const base64 = require("../common/base64");
    const {Cache} = require("./cache");
    const cache = new Cache();
    const {ClientContext} = require("./clientContext");
    let config;
    let uris = {};
    let clients = {};

    function setupUris(){

        let ruri = (config.am7server + config.am7service) + "rest/";
        uris = {
            token : ruri + "token",
            login : ruri + "login",
            cache : ruri + "cache",
            principal : ruri + "principal",
        };
    }
    exports.Client = function () {
        this.newClientContext = function(org, user, id){
            let ctx = new ClientContext(org, user, id || uuid.v4()); 
            clients[ctx.id] = ctx;
            return ctx;
        };

        this.getBearer = function(ctx){
            return {
                headers: {
                    "Authorization": "Bearer " + ctx.token
                }
            };
        };
  
    this.getAttribute = function(object, name){
        if(!object || object == null){
            return null;
        }
        var r = object.attributes.filter(a => {if(a.name === name) return a;});
        return (r ? r[0] : null);
    };

        this.getEncodedPath = function(path) {
            return ("B64-" + base64.encode(path)).replace("=","%3D");
        };
       
        this.post = function(ctx, url, object){
            return new Promise((res,rej)=>{
                (object.objectId ? cache.removeCache(this.getCacheKey(ctx, object.model + "." + object.objectId)) : Promise.resolve())
                .then(()=>{
                    axios.post(url, object, this.getBearer(ctx)).then((resp)=>{
                        if(resp.data != null){
                            res(resp.data);
                            return resp.data;
                        }
                        else rej("Unexpected response");
                    });
                });
            });
        };

        this.get = function(ctx, url, cacheKey){
            const instance = axios.create();
            return new Promise((res,rej)=>{
                if(!ctx || !ctx.token){
                    console.error("Context Error");
                    console.error(ctx || "Null context");
                    console.error(url);
                    rej("Expected auth token");
                }
                (cacheKey ? cache.getCache(ctx, cacheKey) : Promise.resolve(false)).then((cached)=>{
                    if(cached){
                        res(cached);
                        return cached;
                    }
                    else{
                        console.log("Fetching: " + url);
                        instance.get(
                            url,
                            this.getBearer(ctx)
                        ).then((resp)=>{
                            if(resp.data != null || (typeof resp.data == "string" && resp.data.length)){
                                let pDat;
                                try{
                                    pDat = JSON.stringify(resp.data);
                                }
                                catch(e){
                                    console.error("JSON Parse Error - " + url);
                                    console.error("JSON Parse Error - " + resp.data);
                                }
                                (cacheKey ? cache.cache(cacheKey, pDat) : Promise.resolve(true)).then((b)=>{
                                    res(resp.data);
                                    return resp.data;
                                });
                            }
                            
                            else rej("Unexpected response");
                        }).catch((e)=>{
                            console.error("Axios Error: " + url);
                            console.error("Axios Error: " + e);
                        });
                    }
                });
         }).catch((e)=>{
             console.error("Error for " + url);
             console.error("Caught: " + e);
         });
        };

        this.authenticate = function(ctx, credential){

            let credType = {
                "model": "auth.authenticationRequest",
                "credentialType" : "HASHED_PASSWORD",
                "subject" : {
                    "model": "system.user",
                    "name": ctx.username,
                    "organizationPath" : ctx.organization
                },
                "credential" : base64.encode(credential)
            };
            delete ctx.token;
            console.log("Authenticating to " + uris.login + "/jwt/authenticate");
            return new Promise((res,rej)=>{
                let cacheKey = this.getCacheKey(ctx, "token");
                cache.getCache(cacheKey).then((b)=>{
                    if(b){
                        ctx.token = b.token;
                        this.validate(ctx).then((eb)=>{
                            console.log("Validated: " + eb);
                            res(ctx.token);
                        });
                    }
                    else{
                        console.log("Authenticate to token");
                        axios({
                            method: 'POST',
                            url: uris.login + "/jwt/authenticate",
                            data: credType
                        }).then((resp)=>{
                            if(resp.data != null && resp.data.response == "authenticated"){
                                ctx.token = resp.data.tokens[0];
                                ctx.validated = new Date();
                                cache.cache(cacheKey, JSON.stringify(ctx)).then((b)=>{
                                    res(ctx.token);
                                });
                            }
                        });
                    }
                });
            });
        };
        this.getCacheKey = function(ctx, name){
            let org = ctx.organization || "/Public";
            let user = ctx.username || "Anonymous";
            return org.replace(/^\//,"").replace(/\//gi,".") + "." + user + "." + name;
        };
        this.validate = function(ctx){
            if(!ctx.token){
                return Promise.error(0);
            }
            let credType = {
                "model": "auth.authenticationRequest",
                "credentialType" : "TOKEN",
                "credential" : base64.encode(ctx.token)
            };
         
            console.log("Validating to " + uris.login + "/jwt/validate");
            return axios({
                method: 'POST',
                url: uris.login + "/jwt/validate",
                data: credType
            }).then((resp)=>{
                if(resp.data != null && resp.data.response == "authenticated"){
                    ctx.validated = new Date();
                    return true;
                }
                else{
                    return false;
                }

            });
        };
        this.init = function(sConfig){
            return new Promise((res, rej)=>{
                this.getConfig(sConfig).then((data)=>{
                    config = data;
                    setupUris();
                    // console.log("Client prep");
                    cache.init("./cache").then(()=>{
                        // console.log("Client init");
                        res(data);
                    });
                    // res();
                });
            });
        };
        this.getConfig = function(sConfig){
            if(config) return Promise.resolve(config);
            return new Promise((res, rej)=>{
                let filename = path.format(
                    {root: "./", base: sConfig}
                );

                fs.readFile(filename, "utf8", function (err, data) {
                    if (err) {
                        console.error(err);
                        return reject(err);
                    }

                    res(JSON.parse(data));
                });
            });
        };
    };
}(exports));