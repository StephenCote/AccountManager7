(function(){
    const mimeUtil = require("../common/mimeTypeUtil");
    const fs = require("fs");
    const express = require("express");
    var compression = require('compression')
    const cors = require('cors');
    const {Client} = require("./client");
    const client = new Client();
    const {Config} = require("./config");
    const config = new Config();
    const bodyParser = require("body-parser");
    const https = require('https');

    let app = express();

    app.use(compression({filter: function(req, res) {
        if (req.headers['x-no-compression']) {
            return false
        }
        return compression.filter(req, res)
    }}));

    let port;
    let cfg;
    let clients = {};
    let clientCfg;
    let adminSession = "000-000-000-000";
    let serverKey;
    let serverCert;

    function init() {
        return new Promise(function (resolve, reject) {
            client.init("/server/client.json").then((config)=>{
                clientCfg = config;
                let ctx = client.newClientContext(config.adminOrganization,config.adminUser,adminSession);
                client.authenticate(ctx, config.adminCredential).then((token)=>{
                    clients[adminSession] = ctx;
                });
            });

            function getConfig() {
                return new Promise(function (resolve) {
                    config.read().then(function (resp) {
                        cfg = resp;
                        if(cfg.allowUntrustedCerts){
                            process.env["NODE_TLS_REJECT_UNAUTHORIZED"] = 0;
                        }
                        debug = Boolean(resp.debug);
                        serverKey = resp.serverKey;
                        serverCert = resp.serverCert;
                        port = process.env.PORT || resp.clientPort || 80;
                        resolve();
                    });
                });
            }

            Promise.resolve().then(
                getConfig
            ).then(
                resolve
            ).catch(function (err) {
                console.log(err);
                reject(err);
            });
        });
    }
    function doGetFile(req, res) {
        let url = "." + req.url;
        let defFile = cfg.production ? "index.prod.html" : "index.html";

        let file = (req.url.match(/^\/$/) ? defFile : (req.params.file || ""));
        let suffix = (
            file
            ? file.slice(file.lastIndexOf("."), file.length)
            : url.slice(url.lastIndexOf("."), url.length)
        );
        
        let mt = mimeUtil.findByExtension(suffix);
        let mimeType;
        if(mt) mimeType = {"Content-Type": mt};

        fs.readFile(url + file, function (err, resp) {
            if (err) {
                console.log("Woops: " + err);
                return;
            }
            if(mt && mt.match(/html$/)){
                resp = Buffer.from(resp.toString()
                    .replace(/\%AM7_SERVER\%/gi,clientCfg.am7server)
                );
                
            }
            res.writeHeader(200, mimeType);
            res.write(resp);
            res.end();
        });
    }
    
    async function writeFile(path, bytes) {
        return await new Promise(function (res, rej) {
            fs.writeFile(path, bytes, function (err) {
                if (err) {
                    rej(err);
                } else {
                    res();
                }
            });
        });
    }
    async function readFile(path) {
        return await new Promise(function (res, rej) {
            fs.readFile(path, function (err, resp) {
                if (err) {
                    rej(resp);
                } else {
                    res(resp);
                }
            });
        });
    }

    async function start() {

        app.use(bodyParser.urlencoded({
            extended: true
        }));
        app.use(bodyParser.json());
    

        app.use(express.static("public"));
        
        app.use(bodyParser.urlencoded({extended: false}));
        
        app.use(cors({
            origin: ['http://localhost:8080','http://localhost:8899','https://localhost:8443','https://localhost:8899']
        }));

        app.get("/", doGetFile);
        cfg.directories.forEach((dirname) => app.get(dirname + "/:filename", doGetFile));
        cfg.files.forEach((filename) => app.get(filename, doGetFile));

        let svrKey = await readFile("." + serverKey);
        let svrCert = await readFile("." + serverCert);
        let opts = {
            key: svrKey,
            cert: svrCert
        };
        //const server = app.listen(opts, port);
        https.createServer(opts, app).listen(port, () => {
            console.log('Listening on ' + port + '...')
        });

        /*
        const server = app.listen(port);
        console.log("Listening on port: " + port);
        */        
    }

    function run(){    
        return init().then(start).catch(x=>{console.log("Caught error: " + x);process.exit();});
    }

    let httpService = {
        run : run,
        init : init,
        start : start,
        process : process
    };

    module.exports = httpService;
})();