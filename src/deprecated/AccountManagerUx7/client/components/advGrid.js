(function(){
    const advgrid = {};
    let icon = "videogame_asset";
    let label = "Adventure Grid";
    let status = "";
    let infoLabel = "Info";
    let infoStatus = "";
    let builderMode = false;
    let modeClear = false;
    let modeRoom = false;
    let modeCorridor = false;
    let changed = false;
    let modeSpawn = 0;

    let groups;

    let communityData = {
        communityName: 'AdvGrid - LC',
        projectName: 'AdvGrid - P1',

        countryData: ["IR"],
        locationSize: 3,
        locationSeed: 250,
        epochs: 25,
        lifecycle: null,
        project: null
    };

    let data;

    function newGameData(){
        let dat = {
            info: {},
            gridWidth: 30,
            gridHeight: 30,
            cubeWidth: 20,
            cubeHeight: 20,
            /// gridWidth, cubeWidth (note: No space after comma!)
            /// this is hard coded to be picked up by the processor
            gridColCls: "grid-cols-[repeat(30,20px)]",
            grid: [],
            level: 1,
            score: 0,
            initialLocation: 'A New World',// - ' + (new Date().getTime()),
            initialEvent: 'A New Start',// - ' + (new Date().getTime()),
            event: null,
            location: null,
            player: null,
            cellLocations:null,
            mapgen : {
                maxRooms: 5,
                minRoomSize: 5,
                maxRoomSize: 15
            }
        };
        //dat.gridColCls = "grid-cols-[repeat(" + dat.gridWidth + "," + dat.cubeWidth + "px)]";
        return dat;
    }

    function updateChanged(){
        changed = true;
    }
    function toggleBuilder(){
        builderMode = !builderMode;
    }
    function toggleModeClear(){
        modeClear = !modeClear;
        modeRoom = false;
        modeCorridor = false;
        modeSpawn = 0;
    }

    function toggleModeRoom(){
        modeClear = false;
        modeRoom = !modeRoom;
        modeCorridor = false;
        modeSpawn = 0;
    }

    function toggleModeCorridor(){
        modeClear = false;
        modeRoom = false;
        modeSpawn = 0;
        modeCorridor = !modeCorridor;
    }

    function toggleSpawn(i){
        
        if(modeSpawn == i) modeSpawn = 0;
        else modeSpawn = i;
        modeClear = false;
        modeRoom = false;
        modeCorridor = false;
    }


    function buildCell(x, y){
        let g = data.grid;
        if(g[x] && g[x][y]){
            let cell = g[x][y];
            let type = cell.type;
            if(modeClear) type = 0;
            else if(modeRoom) type = 1;
            else if(modeCorridor) type = 2;
            else if(modeSpawn == 1) type = 3;
            else if (modeSpawn == -1) type = 4;
            else if (modeSpawn == 2) type = 5;
            else if (modeSpawn == 3) type = 6;
            cell.type = type;
            updateChanged();
        }
        else{
            console.warn("No cell at " + x + ", " + y);
        }
    }





    /// TODO:   Location needs Long/Lat values
    ///         Resource needs more variant type support
    ///         Expose API to generate a single fake person or fake event

    /// Build a region based on the grid dimensions
    ///
    function constructRegion(name){

    }

    async function getGroups(){
        let aP = [];
//        if(!data) return Promise.resolve();
        groups = {};
        aP.push(page.findObject("GROUP", "DATA", am6community.getPathForType("location")).then((g)=>{ groups.location = g;}));
        aP.push(page.findObject("GROUP", "DATA", am6community.getPathForType("event")).then((g)=>{ groups.event = g;}));
        aP.push(page.findObject("GROUP", "DATA", am6community.getPathForType("trait")).then((g)=>{ groups.trait = g;}));
        aP.push(page.findObject("GROUP", "DATA", am6community.getPathForType("data")).then((g)=>{ groups.data = g;}));
        aP.push(page.findObject("GROUP", "DATA", am6community.getPathForType("person")).then((g)=>{ groups.person = g;}));
        aP.push(page.findObject("GROUP", "DATA", am6community.getPathForType("tag")).then((g)=>{ groups.tag = g;}));
        return Promise.all(aP);
    }
   

    function generateMap(){
        generateRooms();
    }
    function placeRoom(w, h){
        let g = data.grid;
        let pos = findSpaceLocation(w, h);
         if(pos){

            console.log("Place room at " + pos.left + ", " + pos.top);
            for(let i = pos.left; i < pos.left + w; i++){
                for(let b = pos.top; b < pos.top + h; b++){
                    g[i][b].type = 1;
                }
            }
        }
        else{
            console.warn("Failed to place room: " + pos);
        }

    }
    function randomVal(min, max){
        return Math.floor( (Math.random() * (max - min)) + min)
    }

    function findSpaceLocation(w, h){
        let g = data.grid;
        let pos;
        let iters = 0;
        let maxiters = 100;
        while(!pos){
            iters++;
            if(iters > maxiters){
                console.log("Exceeded location attempts");
                break;
            }
            let t = randomVal(0, data.gridHeight);
            let l = randomVal(0, data.gridWidth);
            //console.log("Test: " + t + ", " + l);
            /// Keep placement 1+ inside the margins to allow for any other border
            if(l == 0) l = 1;
            if(t == 0) t = 1;
            if(l + w >= data.gridWidth) l = data.gridWidth - w - 2;
            if(t + h >= data.gridHeight) t = data.gridHeight - h - 2;
            let collision = false;
            for(let x = l - 1; x < l + w + 1; x++){
                for(let y = t - 1; y < t + h + 1; y++){
                    //console.log("Scan: " + x + ", " + y);
                    if(!g[x] || !g[x][y]){
                        console.error("Cell error at " + x + ", " + y);
                        collision = true;
                        break;
                    }
                    let cell = g[x][y];
                    if(cell.type){
                        collision = true;
                        break;
                    }
                }
                if(collision) break;
            }
            if(!collision){
                pos = {left: l, top: t};
                break;
            }
            else{
                console.warn("Collision detected");
            }
        }
        return pos;
    }

    function generateRooms(){
        let mg = data.mapgen;
        let placedRooms = 0;
        for(let i = 0; i < mg.maxRooms; i++){
            let rw = randomVal(mg.minRoomSize, mg.maxRoomSize);
            let rh = randomVal(mg.minRoomSize, mg.maxRoomSize);
            placeRoom(rw, rh);
        }
    }

    function newMapCell(x, y){
        return {
            dropped: 0,
            active: false,
            occupied: false,
            color: "white",
            x: x,
            y: y,
            type: 0
        };
    }

    function statusCallback(s){
        status = " - " + s;
        m.redraw();
    }
    async function initLocation(){
        if(!data) return false;
        let outB = false;
        let l = await page.openObjectByName('LOCATION', groups.location.objectId, data.initialLocation);
        if(!l){
            status = " - Create a location";
            builderMode = true;
            console.log(status);
            m.redraw();
        }
        else{
            data.location = l;
            let cl = await page.listObjectsInParent('LOCATION', l.objectId, 0, l.childLocations.length);
            data.childLocations = cl;
            restoreMap();
            outB = await initEvent();
        }
        return outB;
    }
    function restoreMap(){
        if(!data || !data.childLocations) return;
        data.childLocations.forEach((l)=>{
            let y = l.longitude;
            let x = l.latitude;

            if(typeof x == 'number' || typeof y == 'number'){
                let cell = data.grid[x][y];
                let ct = 0;
                if(l.attributes) ct = AM6Client.getAttributeValue(l, "cellType");
                let cellType = 0;
                if(ct) cellType = parseInt(ct);
                cell.type = cellType;
                cell.location = l;
            }
        });
        m.redraw();
    }
    async function configureEvent(){
        if(!data.event || !communityData.project){
            console.log("No event or data project defined");
            return;
        }
        let outB = false;
        if(!data.event.location){
//            console.log("Obtain inception event ...");
            let pev = await page.findObject('GROUP', 'DATA', communityData.project.groupPath + "/Events");
            console.log("Configuring event location ...");
            let pevC = await page.countObjects('EVENT', pev.objectId);
            let lastE = await page.listObjects('EVENT', pev.objectId, pevC - 1, 1);
            console.log(pev.objectId + " / " + pevC + " / " + lastE.length);
            let randE = randomVal(1, pevC - 2);
            let oE;
            (await page.listObjects('EVENT', pev.objectId, randE, 3)).forEach((e)=>{
                if(!oE && e.name.match(/epoch/gi)){
                    oE = e;
                }
            });
            if(oE){
                let loc = await page.openObject('LOCATION', oE.location.objectId);
                let cloc = loc.childLocations[randomVal(0,loc.childLocations.length)];
                if(cloc){
                    let clocd = await page.openObject('LOCATION', cloc.objectId);
                    data.event.location = clocd;
                    /// Push the start date to the last event
                    ///
                    data.event.startDate = lastE[0].startDate;
                    data.event.endDate = lastE[0].endDate;
                    console.log("Update event");
                    console.log(data.event);
                    await page.updateObject(data.event);
                    console.log("Post update");
                    console.log(data.event);
                    outB = true;
                    console.log("Enjoy " + AM6Client.getAttributeValue(clocd, "name",clocd.name));
                }
                else{
                    console.warn("No child!");
                }
            }
            else{
                console.error("No epoch found!");
            }
            //console.log(aE);

        }
        else{
            if(!data.event.location.attributesPopulated) data.event.location = await page.openObject('LOCATION', data.event.location.objectId);
            console.log("Working with " + AM6Client.getAttributeValue(data.event.location, "name",data.event.location.name));
            outB = true;
        }
        return outB;
    }
    async function initEvent(){
        if(!data || !groups || !groups.event) return false;
        let outB = false;
        let e = await page.openObjectByName('EVENT', groups.event.objectId, data.initialEvent);
        if(!e){
            console.log("Create initial event");
            let initLoc = getCell(-1, -1, 5);
            if(initLoc && initLoc.location){
                console.log("Continue ...");
                let initEvt = am6model.newPrimitive("event", true, am6community.getPathForType("event"));
                initEvt.name = data.initialEvent;
                //initEvt.location = initLoc.location;
                initEvt.eventType = 'INCEPT';
                let b = await page.updateObject(initEvt);
                if(b){
                    console.log("Configure event ... " + groups.event.objectId + " / " + data.initialEvent);
                    let e2 = await page.openObjectByName('EVENT', groups.event.objectId, data.initialEvent);
                    if(!e2.startDate) console.warn("Error retrieving event start date");
                    data.event = e2;
                    outB = await configureEvent();
                }
                else{
                    console.error("Failed to update event");
                    return false;
                }

            }
            else{
                console.error("Spawn location not found");
                return false;
            }
        }
        else{
            console.log("Load event ... " + groups.event.objectId + " / " + data.initialEvent);
            data.event = e;
            outB = await configureEvent();
        }
        return true;

    }
    async function randomPerson(count){
        let pev = await page.findObject('GROUP', 'DATA', communityData.project.groupPath + "/Persons");
        let pevC = await page.countObjects('PERSON', pev.objectId);
        let pR = randomVal(1, pevC - count);
        let aR = await page.listObjects('PERSON', pev.objectId, pR, count);
        return aR;
    }

    async function randomPersons(count){
        let aP = [];
        for(let i = 0; i < count; i++){
            let per = await randomPerson(1);
            aP.push(per[0]);
        }
        return aP;
    }

    async function newPlayerPerson(){
        let pers = await randomPerson(1);
        let per = pers[0];
        //data.event.actors = [per];
        return per;
    }
    async function initPopulation(){
        //let bc = 0;
        if(!data || !data.event) return;
        if(!data.event.actors.length){
            let per = await newPlayerPerson();
            data.info.per = per;
            data.info.pers = await randomPersons(10);
            solicitInfo("Setup");
            //console.log("Selecting player: " + per.name);
            //bc++;
        }
        else{
            //infoStatus = " - " + data.event.actors[0].name;
            data.player = await page.openObjectByName('PERSON', groups.person.objectId, data.event.actors[0].name);
            if(!data.player){
                console.log("Cloning base person");
                let clone = await page.openObject('PERSON', data.event.actors[0].objectId);
                delete clone.id;
                delete clone.objectId;
                delete clone.contactInformation;
                clone.groupPath = am6community.getPathForType("person");
                await page.updateObject(clone);
                data.player = await page.openObjectByName('PERSON', groups.person.objectId, data.event.actors[0].name);
            }
            console.log("Working with player: ");
            console.log(data.player);
            solicitInfo("Setup2");
        }
    }

    function chooseInfo(o){
        if(!data) return;
        data.info.selected = o;
    }
    async function confirmInfo(){
        if(!data || !data.info.selected) return;
        if(data.info.step == "setup"){
            data.event.actors = [data.info.selected];
            await page.updateObject(data.event);
            //solicitInfo("Setup2");
            await initPopulation();
        }
    }
    async function refreshInfo(){
        if(!data) return;
        if(data.info.step == 'setup'){
            console.log("Refresh pop");
            await initPopulation();
        }
        else{
            console.log("Unknown step: " + data.info.step);
        }

    }

    function solicitInfo(x){
        if(!data) return;
        data.info.step = x.toLowerCase();
        infoStatus = " - " + x;
        m.redraw();
    }

    async function init(){
    
        //generateMap();
        console.log("Create empty grid");
        createNewGrid();
        if(!groups){
            await getGroups();
            if(data){
                let p = await am6community.getCreateConfiguredCommunityProject(communityData.communityName, communityData.projectName, communityData.countryData, communityData.locationSize, communityData.locationSeed, communityData.epochs, statusCallback);
                communityData.project = p;
                await initLocation();
                console.log("Init pop");
                await initPopulation();
            }
        }
    }

    function getCell(x, y, type){
        if(!data || !data.grid) return;
        let outCell;
        for(let i = 0; i < data.gridWidth; i++){
            if(typeof x == 'number' && x >= 0 && x != i) continue; 
            for(let b = 0; b < data.gridHeight; b++){
                let cell = data.grid[i][b];
                if(typeof y == 'number' && y >= 0 && y != b) continue;
                if(typeof type == 'number' && cell.type == type){
                    outCell = cell;
                }
            }
            if(outCell) break;
        }
        return outCell;
    }

    function createNewGrid(){
        console.log("Initialize grid");

        data = newGameData();

        let dgrid = data.grid;

        for(let i = 0; i < data.gridWidth; i++){
            if(!dgrid[i]) dgrid[i] = [];
            for(let b = 0; b < data.gridHeight; b++){
                dgrid[i][b] = newMapCell(i, b);
            }
        }

    }

    function refreshGrid(){
        if(!data) return m("div","[ ... ]");
        let gridView = [];
        let dgrid = data.grid;
        let w = data.cubeWidth;
        let h = data.cubeHeight;

        let gridStyle = "width: " + (data.gridWidth * w) + "px;";

        let colorPrep = [
            "bg-blue-500",
            "bg-red-500",
            "bg-green-500",
            "bg-brown-500",
            "bg-gray-500",
            "bg-orange-500",
            "bg-yellow-500",
            "bg-purple-500",
            "bg-teal-500",
            "bg-white",
            "bg-black",
            "border-white",
            "border-black"
        ];

        let defCubeColor = "white";

        let cubeTrans = "transition-all duration-300";
        let cubeClsC = cubeTrans;
        let cubeStyleS = "width:" + w + "px;"
        + "height: " + h + "px;";

        for(let b = 0; b < data.gridHeight; b++){
            for(let i = 0; i < data.gridWidth; i++){
                let cell = dgrid[i][b];
                let cellTypeColor = defCubeColor;
                if(cell.type > 0) cellTypeColor = getCellTypeStyle(cell.type);
                /*
                if(cell.type == 1) cellTypeColor = "black";
                else if (cell.type == 2) cellTypeColor = "orange";
                */
                let cubeCls = cubeClsC + " " + cubeStyle(cell.active, cell.active ? cell.color : cellTypeColor);
                let handler;
                if(builderMode){
                    handler = function(){
                        buildCell(i, b);
                    };
                }
                let n = m("div", {class: cubeCls, style: cubeStyleS, onclick: handler});
                gridView.push(n);
            }
        }
        // console.log(gridStyle);
        return [m("div", {class: "game-grid-auto " + data.gridColCls, style: gridStyle}, gridView)];
    }
    let cellColorMap = [
        "white",
        "black",
        "gray",
        "teal",
        "red",
        "green",
        "purple"
    ];

    function getCellTypeStyle(i){
        return cellColorMap[i] || "yellow";
    }
    function cubeStyle(b, g){
        let color = g + ((g.match(/^(white|black)$/) ? "" : "-500")); 
        return (b ? "border border-gray-900 shadow-lg" : "border border-" + color) 
        + " bg-" + color
    ;
    }

    let oneYearMs = 31536000000;
    function getAge(per){
        if(!data) return 0;
        let d1 = per.birthDate;
        let d2 = data.event.endDate;
        let ms = d2.getTime() - d1.getTime();
        /// Because the randomly chosen event may be before the randomly chosen person was born, then the age may be negative
        /// Just take the absolute value here
        return Math.abs(Math.round(ms / oneYearMs));
    }

    function modelInfo(){
        if(!data) return "";
        let ino = data.info;

        if(ino.step === 'setup'){
            let aR = [];
            if(ino.per){
                aR.push(m("li",m("div", {onclick: function(){ chooseInfo(ino.per);}, class: 'result-item' + (ino.selected && ino.selected.objectId == ino.per.objectId ? " result-item-active" : "")},  ino.per.name + " (" + ino.per.gender + ") (" + getAge(ino.per) + " years) (" + AM6Client.getAttributeValue(ino.per, "alignment") + ") (" + AM6Client.getAttributeValue(ino.per, "trade") + ")")));
            }
            if(ino.pers) ino.pers.forEach((p)=>{
                aR.push(m("li",m("div", {onclick: function(){ chooseInfo(p);}, class: 'result-item'  + (ino.selected && ino.selected.objectId == p.objectId ? " result-item-active" : "")}, p.name + " (" + p.gender + ")" + (AM6Client.getAttributeValue(p, "deceased") === 'true' ? " (dead)" : "") + " (" + getAge(p) + " years) (" + AM6Client.getAttributeValue(p, "alignment") + ") (" + AM6Client.getAttributeValue(p, "trade") + ")")));
            });
            return m("ul", {"class" : "list-results-overflow"}, aR);
        }
        return "";
    }
    function modelGame(){
        return refreshGrid();
    }

    function getScoreCard(){
        return m("div",{class: "result-nav-outer"},[
            m("div",{class: "result-nav-inner"},[
                //page.iconButton("button mr-4", "start", "", start),
                page.iconButton("button mr-4", "refresh", "", refreshInfo),
                //m("div",{class: "count-label mr-4"},"Level " + (data ? data.level : 1)),
                //m("div",{class: "count-label mr-4"},"Score " + (data ? data.score : 0)),
                page.iconButton("button", "check", "", confirmInfo)
            ])
        ]);
    }


    function modelPanel(){
        return [m("div",{class: "results-overflow grid grid-cols-2 gap-4"},
                    m("div",{class: "panel-card"},
                        m("p", {"class" : "card-title"}, [
                            m("span", {class : "material-icons material-icons-cm mr-2"}, icon),
                            label + status
                        ]),
                        m("div", {"class": "card-contents"}, modelGame())
                    ),
                    m("div",{class: "panel-card"},
                    m("p", {"class" : "card-title"}, [
                        m("span", {class : "material-icons material-icons-cm mr-2"}, icon),
                        infoLabel + infoStatus
                    ]),
                    m("div", {"class": "card-contents"}, modelInfo())
                )

            ),
            m("div",{class: "result-nav-outer"},[
                m("div",{class: "result-nav-inner"},[
                    m("div",{class: "result-nav tab-container"},[
                        page.iconButton("button", "west", "", moveWest),
                        page.iconButton("button", "north", "", moveNorth),
                        page.iconButton("button", "south", "", moveSouth),
                        page.iconButton("button", "east", "", moveEast),
                        page.iconButton("button", "hourglass_empty", "", wait)
                    ])
                ])
            ]),
        ];
    }
    function wait(){

    }
    function moveEast(){

    }
    function moveWest(){

    }
    function moveNorth(){

    }
    function moveSouth(){

    }

    function start(){

    }
    function stop(){

    }

    function endGame(){
        let udef;
        groups = udef;
        data = udef;
    }

    function keyControl(e){

    }

    function shuffle(){
        createNewGrid();
        generateMap();
        //m.redraw();
    }

    async function saveGame(){
        console.log("Save!");
        if(builderMode){
            return saveGrid();
        }
        else{
            console.warn("Implement save...");
        }
    }
    async function saveGrid(){
        let l1 = data.location;
        let name = document.querySelector("[rid=gridName]").value;
        if(name){
            if(!l1 || l1.name !== name) l1 = am6model.newPrimitive("location", true, am6community.getBasePath("location"));
            l1.name = name;

            console.log("Attempt to update");
            let b = await page.updateObject(l1);
            console.log("Attempt to retrieve");
            let l2 = await page.openObjectByName("LOCATION", groups.location.objectId, name);
            if(l2){
                /// Build up the grid and do a bulk load:

                let aLocs = [];
                for(let y = 0; y < data.gridHeight; y++){
                    for(let i = 0; i < data.gridWidth; i++){
                        let cell = data.grid[i][y];
                        let l3 = cell.location || am6model.newPrimitive("location", true, am6community.getBasePath("location"));
                        l3.name = "Cell x" + i + " y" + y;
                        if(cell.location && (l3.longitude != y || l3.latitude != i)){
                            console.warn("Cell miss-match: " + l3.latitude + " == " + i + " / " + l3.longitude + " == " + y);
                        }
                        l3.longitude = y;
                        l3.latitude = i;
                        l3.attributes = [AM6Client.newAttribute("cellType", cell.type)];
                        l3.geographyType = "ENVIRONMENTAL";
                        l3.parentId = l2.id;
                        aLocs.push(l3);
                    }
                }
                console.log("Attempting to bulk update " + aLocs.length + " items");
                AM6Client.updateBulk("LOCATION", aLocs, function(s, v){
                    if(v && typeof v.json != "undefined") v = v.json;
                    /// Clear the context object properly load the children
                    ///
                    page.clearContextObject(l2.objectId);
                    window.dbgEntity = aLocs;
                    console.log("Done: " + v);
                    changed = false;
                    initLocation();
                    //m.redraw();
                });

            }
            else{
                console.error("Failed to retrieve " + name);
            }

        }
    }

    function getCommands(){
        if(!am6community.getCommunityMode){
            return [
                page.iconButton("button", "group", "", function(){ page.components.breadCrumb.toggleCommunity(); })
            ];
        }
        let bldCommands = [
            page.iconButton("button" + (builderMode ? " active" : ""), (builderMode ? "draw" : "edit_off"), "", function(){ toggleBuilder(); }),
        ];
        if(builderMode) bldCommands = bldCommands.concat([
            page.iconButton("button","shuffle", "", function(){ shuffle(); }),
            page.iconButton("button" + (modeClear ? " active" : ""),"backspace", "", function(){ toggleModeClear(); }),
            page.iconButton("button" + (modeRoom ? " active" : ""),"meeting_room", "", function(){ toggleModeRoom(); }),
            page.iconButton("button" + (modeCorridor ? " active" : ""),"edit_road", "", function(){ toggleModeCorridor(); }),
            page.iconButton("button" + (modeSpawn == 1 ? " active" : ""),"thumb_up_off_alt", "", function(){ toggleSpawn(1); }),
            page.iconButton("button" + (modeSpawn == -1 ? " active" : ""),"thumb_down_off_alt", "", function(){ toggleSpawn(-1); }),
            page.iconButton("button" + (modeSpawn == 2 ? " active" : ""), "my_location", "", function(){ toggleSpawn(2); }),
            page.iconButton("button" + (modeSpawn == 3 ? " active" : ""), "place", "", function(){ toggleSpawn(3); })

        ]);
        bldCommands.push(m("input", {type: "text", class : "ml-4 text-field", rid : 'gridName', value: (data ? data.initialLocation : "")}));
        return bldCommands;
    }

    advgrid.component = {
        data : function(){ return data },
        isChanged : function(){ return changed; },
        saveGame : saveGame,
        commands : getCommands,
        keyHandler : keyControl,
        scoreCard : getScoreCard,

        oninit : function(x){
            init();
        },
        oncreate : function (x) {

        },
        onremove : function(x){
            endGame();
        },

        view: function () {
            return modelPanel();
         }
    };

    page.components.advgrid = advgrid.component;
}());
