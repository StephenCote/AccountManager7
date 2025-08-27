/// Demo game component - based on a 20+ year old 5K JavaScript submission I made
///
(function(){
    const tetris = {};
    let icon = "videogame_asset";
    let label = "Tetris";
    let preview = true;

    function newGameData(){
        let dat = {
            cube_width:25,
            cube_height:25,
            grid_width:10,
            grid_height:20,
            grid:[],
            cubes:[],
            cube_types:[
                /* square */
                "10112021101120211011202110112021",
                /* line */
                "20212223011121312021222301112131",
        
                /* right_elbow */
                "10201112011121221011120200011121",
                /* left_elbow */
                "10202122112130312021223211122131",
                /* intersect */
                "11202131312021221121223111202122",
                /* right_twist */
                "10200111101121221020011110112122",
                /* left_twist */
                "00101121100111020010112110011102"
            ],
            cube_colors:["red","green","blue"],
            next_cube_color:"blue",
        
            /* piece matrix size */
            piece_width:4,
            piece_height:4,
        
            /* default starting position */
            piece_left:4,
            piece_top:0,
        
            /* flag whether an active game piece is in play */
            piece_is_active:0,
            current_clock_value: 1000,
            next_level_requirement:10,
            next_level:0,
            lines:0,
            point_modifier:1,
            init_game:0,
            level:1,
            score: 0,
            levels:[1000,900,800,700,600,500,400,300,200,100,50,10,1]
        };

        /* mark the top-most piece */
        dat.mark_top = dat.grid_height;

        return dat;
    }

    let data;
    
    function init(){
    
        console.log("Initialize grid");

        data = newGameData();

        data.cube_types.forEach((c, i)=>{
            let n = [];
            for(let b = 0; b < data.piece_width; b++){
                n[b] = [];
                for(let k = 0; k < data.piece_height; k++){
                    n[b][k] = [];
                    /// rotation
                    for(let j = 0; j < 4; j++){
                        n[b][k][j] = 0;
                    }
                }
            }

            let mesh = c;
            let r = -1;
            for(let k = 0; k < mesh.length; k += 2){
                if(k % 8 == 0) r++;
                let j = mesh.substring(k, k + 2).split("");
                n[j[0]][j[1]][r] = 1;
            }
            
            data.cubes[i] = n;

        });

        data.next_cube_index = parseInt(Math.random() * data.cube_types.length);
        data.next_cube_color = data.cube_colors[parseInt(Math.random() * data.cube_colors.length)];
    }

    function refreshGrid(){
        if(!data) return m("div","init ...");
        let gridView = [];
        let dgrid = data.grid;
        let w = data.cube_width;
        let h = data.cube_height;

        let gridStyle = "width: " + (data.grid_width * w) + "px";
        let previewStyle = "width: " + (data.piece_width * w) + "px";

        let colorPrep = [
            "bg-blue-500",
            "bg-red-500",
            "bg-green-500",
        ];

        let defCubeColor = "white";

        let cubeTrans = "rounded-sm transition-all duration-300"
        let cubeClsC = cubeTrans;
        let cubeStyleS = "width:" + w + "px;"
        + "height: " + h + "px;";

        for(let b = 0; b < data.grid_height; b++){
            for(let i = 0; i < data.grid_width; i++){
                if(!dgrid[i]) dgrid[i] = [];
                if(!dgrid[i][b]) dgrid[i][b] = {dropped: 0};
                let cubeCls = cubeClsC + " " + cubeStyle(dgrid[i][b].active, dgrid[i][b].active ? dgrid[i][b].color : defCubeColor);
                let n = m("div", {class: cubeCls, style: cubeStyleS});
                gridView.push(n);
            }
        }
        let prev = "";
        if(preview){
            let prevGrid = [];
            let prevColor = data.next_cube_color;
            for(let b = 0; b < data.piece_height; b++){
                for(let i = 0; i < data.piece_width; i++){
                    let p = data.cubes[data.next_cube_index][i][b][0];
                    let cubeCls = cubeClsC + " " + cubeStyle((p ? true : false), (p ? prevColor : defCubeColor));
                    let n = m("div", {class: cubeCls, style: cubeStyleS});
                    prevGrid.push(n);
                }
            }
            prev = m("div", {class: "game-grid-preview-44", style: previewStyle}, prevGrid);
        }
        return [m("div", {class: "game-grid-1020", style: gridStyle}, gridView), prev];
    }
    
    function cubeStyle(b, g){
        return (b ? "border border-gray-900 shadow-lg" : "border border-white") 
        + " bg-" + g + (g.match(/^(white|black)$/) ? "" : "-500")
    ;
    }

    function evaluateGrid(){
        var s = arguments[0], h = 0,  p;
        data.piece_on_left_edge = 0;
        data.piece_on_right_edge = 0;
        for(let a = data.current_piece_left; a < (data.current_piece_left + data.piece_width); a++){
            if(typeof data.grid[a] == 'object'){
                for(let b = data.current_piece_top; b < (data.current_piece_top + data.piece_height); b++){
                    if(typeof data.grid[a][b] == 'object'){
                        let dg = data.grid[a][b];
                        p = data.cubes[data.active_cube_index][a - data.current_piece_left][b - data.current_piece_top][data.piece_rotation];
                        if(p){
                            if(dg.dropped)
                                h=1;
                            
                            if(!a)
                                data.piece_on_left_edge=1;
                            
                            if(b == (data.grid_height - 1))
                                data.piece_is_dropped = 1;
                            
                            if(a == (data.grid_width-1))
                                data.piece_on_right_edge=1;
                        }
                    }
                }
            }
            else{
                let c1 = 0;
                if(data.current_piece_left < 0){
                    c1 = Math.abs(data.current_piece_left) - 1;
                }
                else {
                    c1= Math.abs(data.current_piece_left - data.grid_width);
                }
                for(let b = 0; b < data.piece_height; b++){
                    if(data.cubes[data.active_cube_index][c1][b][data.piece_rotation]){
                        h = 1;
                    }
                }
            }
        }
        if(!h){
            for(let a = (data.current_piece_left - 1); a < (data.current_piece_left + data.piece_width + 1); a++){
                if(typeof data.grid[a] == 'object'){
                    for(let b = (data.current_piece_top - 1); b < (data.current_piece_top + data.piece_height + 1); b++){
                        if(typeof data.grid[a][b] =='object'){
                            let dg = data.grid[a][b];
                            p = 0;
                            if(typeof data.cubes[data.active_cube_index][a - data.current_piece_left] == 'object'){
                                if(typeof data.cubes[data.active_cube_index][a - data.current_piece_left][b - data.current_piece_top] == 'object'){
                                    p = data.cubes[data.active_cube_index][a - data.current_piece_left][b - data.current_piece_top][data.piece_rotation];
                                }
                            }
                            if(p){
                                dg.color = data.active_cube_color;
                                dg.active = true;
                            }
                            else{
                                if(!dg.dropped){
                                    dg.active = false;
                                    dg.color = "white";
                                }
                            }
                        }
                    }
                }
            }
        }
        let r = 0;
        if(h){
            if(s == 1)
                data.current_piece_top--;
            
            if(s == 3)
                data.current_piece_left--;
            
            if(s == 2)
                data.current_piece_left++;
            
            r = 1;
        }
    
        /* Prevent further left moves. */
        if(s == 2 && data.piece_on_left_edge)
            r = 1;
        
        /* Prevent further down moves. */
        if(s == 1 && data.piece_is_dropped)
            r = 1;
        
        /* Prevent further right moves; */
        if(s == 3 && data.piece_on_right_edge)
            r = 1;
        
        /* Return false if there were no hits, return true if there were hits. */
        return r;
    }

    function addPiece(){
        if(!data.init_game)
            data.init_game = 1;
        
        clock();
        
        data.piece_rotation = 0;
        data.active_cube_index = data.next_cube_index;
        data.next_cube_index = parseInt(Math.random() * data.cube_types.length);
        data.active_cube_color = data.next_cube_color;
        while( data.next_cube_color == data.active_cube_color && data.cube_colors.length > 1)
            data.next_cube_color = data.cube_colors[parseInt(Math.random() * data.cube_colors.length)];
        
        data.piece_is_active = 1;
        data.piece_on_left_edge = 0;
        data.piece_on_right_edge = 0;
        data.piece_is_dropped = 0;	
        data.current_piece_top = data.piece_top;
        data.current_piece_left = data.piece_left;
    
        if(evaluateGrid()){
            alert("game over");
            endGame();
        }
        else{
            console.log("Redraw for add");
            m.redraw();
            clock(1);
        }
    
    }

    function endGame(){
        clock();
        clearAllNodes();
    }
    
    function clock(z){
        if(z)
            data.timer = setInterval(down, data.current_clock_value);
        else
            if(typeof data.timer == 'number')
                clearInterval(data.timer);
    }
    
    function checkClearLine(){
        var a=[],b,c=0,d,i,e,s;
    
        let lineCount = 0;

        for(b=data.mark_top-data.piece_height;b<data.grid_height;b++){
            if(b>-1){
                d=1;
                for(i=0;i<data.grid_width;i++){
                    e=data.grid[i][b];
                    if(!e.dropped)
                        d=0;
                    
                }
                if(d){
                    c=1;
                    clock();
                    a[a.length]=b;
                }
            }
            else
                b=-1;
        }

        if(c){
            for(i=0;i<a.length;i++){
                for(b=0;b<data.grid_width;b++){
                    e = data.grid[b][a[i]];
                    e.dropped = 0;
                    e.color = "white";
                    e.active = false;
                }
        
                if(data.mark_top<data.grid_height)
                    data.mark_top++;
        
                e = a[i]-1;
                if(e>0){
                    for(b=e;b>-1;b--){
                        for(d=0;d<data.grid_width;d++){
                            c = data.grid[d][b];
                            if(c.dropped && b<data.grid_height){
                            
                                c.dropped = 0;
                                c.active = false;
                                s = c.color;
                                c.color = "white";
                                c = data.grid[d][b+1];
                                c.dropped = 1;
                                c.active = true;
                                c.color = s;
                            }
                        }
                    }
                }
                lineCount++;
                //data.lines++;
                data.next_level++;
                if(a.length > 3)
                    data.point_modifier += .5;
        
            }
        
        
            if(data.next_level >= data.next_level_requirement){
                data.next_level=0;
                if(data.level<data.levels.length-1) data.level++;
        
                data.current_clock_value = data.levels[data.level];

                clock();
                clock(1);
        
            }
        }
        if(lineCount > 0){
            data.lines += lineCount;
            data.score += parseInt(data.point_modifier * lineCount);
            console.log(data.point_modifier + " / " + lineCount);
        }
    }
    
    function tryStart(){
        if(!data.piece_is_active)
            addPiece();
        
    }
    
    function rotatePiece(i){
        /* Checking the drop status is redundant since the piece vectors should be flagged in the grid. */
        if(!data.piece_is_dropped && data.piece_is_active){
            var o = data.piece_rotation;
            data.piece_rotation += i;
            if(data.piece_rotation > 3)
                data.piece_rotation = 0;
            
            if(data.piece_rotation < 0)
                data.piece_rotation= 3;
            
            if(evaluateGrid())
                data.piece_rotation = o;
            
        }
        m.redraw();
    }

    function down(){
        var a,b,c,p;
        if(!data.piece_is_dropped && data.piece_is_active){
            data.current_piece_top++;
            if(evaluateGrid(1)){
                data.piece_is_active = 0;
                for(a = data.current_piece_left; a < data.current_piece_left + data.piece_width; a++)
                    if(typeof data.grid[a]== 'object')
                        for(b = data.current_piece_top; b < data.current_piece_top + data.piece_height;b++)
                            if(typeof data.grid[a][b]=='object'){
                                c=data.grid[a][b];
                                p=data.cubes[data.active_cube_index][a - data.current_piece_left][b - data.current_piece_top][data.piece_rotation];
                                if(p && !c.dropped){
                                    c.dropped=1;
                                    if(b < data.mark_top)
                                        data.mark_top = b;
                                    
                                }
                            }

                checkClearLine();
                clock();
                setTimeout(addPiece,1000);
            }
            else if(arguments[0] == 1){
                data.point_modifier += 0.5;
                setTimeout(function(){ down(1);}, 1);
            }
            else{
                data.point_modifier += 0.1;
            }
            m.redraw();
        }
        else{
            console.log("piece dropped or isn't active");
        }
        
    }
    function move(z){
        var p= ( z > 0 ? 'piece_on_right_edge' : 'piece_on_left_edge');
        if(!data[p] && data.piece_is_active){
            data.current_piece_left+=z;
            if(!evaluateGrid( ( z > 0 ? 3 : 2) )){
                data[p] = 0;
            }
            m.redraw();
            
        }
    }

    function clearAllNodes(){
        var c,a,b;
    
        data.level=1;
        data.score = 0;
        data.init_game=0;
        data.piece_is_active=0;
        data.lines=0;
        data.current_clock_value = 1000;
    
        clock();
        
        for(a = 0;a < data.grid_width; a++)
            for(b = 0; b < data.grid_height; b++){
                c = data.grid[a][b];
                c.dropped = 0;
                c.active = false;
            }
    }
    

    function keyControl(e){
    
        var i = e.keyCode;
        /* Move a piece left */
        if(i==37 || i==52 || i==100)
            move(-1);
    
        /* Move a piece right */
        if(i==39 || i==54 || i==102)
            move(1);
    
        /* Drop a piece */
        if(i == 38) down(1);
        if(i==40 || i==50 || i==98)
            down();

        /* Rotate left */
        if(i==44 || i==55 || i==188 || i==101)
            rotatePiece(-1);
    
        /* Rotate right */
        if(i==46 || i==57 || i==190)
            rotatePiece(1);
    
        /* Reset */
        if(i==82 || i==114)
            clearAllNodes();
    
        /* Start */
        if(i==83 || i==115)
            tryStart();
    
    }

    function moveLeft(){
        move(-1);
    }
    function moveRight(){
        move(1);
    }

    function rotateLeft(){
        rotatePiece(-1);
    }
    function rotateRight(){
        rotatePiece(1);
    }
    function drop(){
        down();
    }
    function start(){
        tryStart();
    }
    function stop(){
        endGame();
    }
    function modelPanel(){
        return [m("div",{class: "results-overflow"},
                    m("div",{class: "panel-card"},
                        m("p", {"class" : "card-title"}, [
                            m("span", {class : "material-icons material-icons-cm mr-2"}, icon),
                            label
                        ]),
                        m("div", {"class": "card-contents"}, modelGame())
                    )
            ),
            m("div",{class: "result-nav-outer"},[
                m("div",{class: "result-nav-inner"},[
                    m("div",{class: "result-nav tab-container"},[
                        page.iconButton("button", "undo", "", rotateLeft),
                        page.iconButton("button", "west", "", moveLeft),
                        page.iconButton("button", "south", "", drop),
                        page.iconButton("button", "east", "", moveRight),
                        page.iconButton("button", "redo", "", rotateRight)
                    ])
                ])
            ]),
        ];
    }

    function modelGame(){
        return refreshGrid();
    }

    function getScoreCard(){
        return m("div",{class: "result-nav-outer"},[
            m("div",{class: "result-nav-inner"},[
                page.iconButton("button mr-4", "start", "", start),
                m("div",{class: "count-label mr-4"},"Level " + (data ? data.level : 1)),
                m("div",{class: "count-label mr-4"},"Score " + (data ? data.score : 0)),
                page.iconButton("button", "stop", "", stop)
            ])
        ]);
    }

    tetris.component = {
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

    page.components.tetris = tetris.component;
}());
