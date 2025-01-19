const { resolve } = require("path");
const { fips } = require("crypto");

(function(exports){

const fs = require("fs");
const path = require("path");
let cachePath;

exports.Cache = function(){
    this.init = function(inPath){
        cachePath = inPath || "./cache";
        return makePath(cachePath);
    };

    this.removeAll = function(pattern, includeToken, includeSelf){
        let rx;
        let vP = [];
        console.info("Remove all: " + cachePath);
        return new Promise((res,rej)=>{

            fs.readdir(cachePath, (err, files) => {
                files.forEach(file => {
                    if(!rx || file.startsWith(pattern)){
                        if(
                            (includeSelf || !file.match(/\.self\.json$/))
                            &&
                            (includeToken || !file.match(/\.token\.json$/))
                        ){
                            vP.push(new Promise((fres,frej)=>{fs.unlink(cachePath + "/" + file, err => {
                                if (err){
                                    console.error("Failed to unlink " + file + " with error " + err);
                                }
                                else fres();
                            })}));
                        }
                    }
                    else{
                        console.log("Skip " + file + " because rx = " + file.match(rx));
                    }
                });
                Promise.all(vP).then(()=>{res();});
            });

            
        });
    };

    this.removeCache = function(key){
        return new Promise((res,rej)=>{
            let cacheFile = path.format(
                {root: cachePath, base: "/" + key + ".json"}
            );
            fs.access(cacheFile, fs.F_OK, (err) => {
                if (err) {
                  res(false);
                }
                else fs.unlink(cacheFile, function(err){
                    if(err){
                        rej(err);
                    }
                    else{
                        res(true);
                    }
                });

            });
        });
    };

    this.cache = function(key, value){
        return new Promise((res,rej)=>{
            let cacheFile = path.format(
                {root: cachePath, base: "/" + key + ".json"}
            );
            if(value.length == 0) rej("empty value");
            else fs.writeFile(cacheFile, value, function(err){
                res(err || true);
            });
        });
    };

    this.getCache = function(key){
        return new Promise((res, rej)=>{
            let cacheFile = path.format(
                {root: cachePath, base: "/" + key + ".json"}
            );
            fs.access(cacheFile, fs.F_OK, (err) => {
                if (err) {
                  res(false);
                }
               else  fs.readFile(cacheFile, "utf8", function (err2, data) {
                    if (err2) {
                        console.error("ERROR: " + err);
                        return rej(err);
                    }
                    res(JSON.parse(data));
                });
            });

        });
    }
};

function makePath(inPath){
    return new Promise((res,rej)=>{
        fs.mkdir(cachePath,{recursive:true},function(err, data){
            if(err) rej(err,data);
            else res();
        });
    });
}

}(exports));