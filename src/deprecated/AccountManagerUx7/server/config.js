(function (exports) {
    "use strict";
    const fs = require("fs");
    const path = require("path");

    exports.Config = function () {
        let config = {};

        config.read = function () {
            return new Promise(function (resolve, reject) {
                let filename = path.format(
                    {root: "./", base: "/server/config.json"}
                );

                fs.readFile(filename, "utf8", function (err, data) {
                    if (err) {
                        console.error(err);
                        return reject(err);
                    }

                    resolve(JSON.parse(data));
                });
            });
        };

        return config;
    };

}(exports));