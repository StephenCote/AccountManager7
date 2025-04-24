(function(){
    let mimeTypeUtil = {
        findByExtension : function(sExt){
            let mimetype;

            switch (sExt) {
            case ".mjs":
            case ".js":
                mimetype = "application/javascript";
                break;
            case ".scss":
            case ".css":
                mimetype = "text/css";
                break;
            case ".webp":
                mimeType = "image/webp";
                break;
            case ".png":
                mimeType = "image/png";
                break;
            case ".gif":
                mimeType = "image/gif";
                break;
            case ".jpg":
                mimeType = "image/jpg";
                break;
            case ".html":
                mimetype = "text/html";
                break;
            case ".json":
                mimetype = "application/json";
                break;
            case ".png":
                mimetype = "image/png";
                break;
            case ".ttf":
                mimetype = "application/x-font-ttf";
                break;
            case ".woff":
                mimetype = "font/woff";
                break;
            case ".woff2":
                mimetype = "font/woff2";
                break;
            default:
                throw new Error("Unknown file type " + sExt);
            }
            return mimetype;
        }
    };
    if(typeof module == "undefined"){
        window.mimeTyepUtil = mimeType;
    }
    else{
        module.exports = mimeTypeUtil;
    }
}());