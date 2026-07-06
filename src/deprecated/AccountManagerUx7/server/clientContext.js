(function(exports){

    exports.ClientContext = function(org, uname, id){
        let udef;
        let context = {
            id : id,
            username : uname,
            organization : org,
            token : udef,
            validated : udef,
            user : udef

        };
        return context;

    };
}(exports));