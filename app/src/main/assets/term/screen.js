'use strict';
const terminal = new Terminal({cursorBlink:true,fontFamily:'monospace',fontSize:Math.min(36,Math.max(8,Birch.fontSize())),scrollback:2500,allowProposedApi:false,theme:{background:'#151a17',foreground:'#e1e9e2',cursor:'#a0d8af',selectionBackground:'#aadbb8',selectionInactiveBackground:'#aadbb8',selectionForeground:'#102218'}});
const fit = new FitAddon.FitAddon();
terminal.loadAddon(fit);
const surface=document.getElementById('term');
terminal.onResize(({cols,rows})=>Birch.resize(cols,rows));
window.keyboardVisibilityChanged=function(visible){
    // Keep DOM focus and composition intact. A focused textarea must not let a
    // WebView touch reopen a keyboard that Android Back has just hidden.
    if(visible)terminal.textarea.inputMode='text';
    else terminal.textarea.inputMode='none';
};
// Recognize touch gestures before letting WebView or xterm change editing focus.
function enableGestures(){
    const tapLimitMs=500;
    const touchSlop=Birch.touchSlopDp();
    const contacts=new Map();
    let gesture=null, holdTimer=0, keyboardRequest=0;
    let anchor=null, fitting=false, lastPointerWasTouch=false, reportingMouse=false;
    const grips=['start','end'].map(side=>({side,button:document.getElementById(side+'-grip'),stem:document.getElementById(side+'-stem')}));
    const gripSize=parseFloat(getComputedStyle(grips[0].button).width),radius=gripSize/2;
    function hideGrips(){
        if(gesture && gesture.kind==='grip')finishGesture();
        for(const grip of grips){grip.button.hidden=true;grip.stem.removeAttribute('d');}
    }
    function syncGrips(){
        if(!anchor || fitting)return;
        const pos=terminal.getSelectionPosition();
        if(!pos){dismissSelection();return;}
        const box=surface.querySelector('.xterm-screen').getBoundingClientRect();
        const w=document.body.clientWidth,h=document.body.clientHeight;
        const cw=box.width/terminal.cols,ch=box.height/terminal.rows;
        const points=[];
        for(const grip of grips){
            const end=grip.side==='end';let {x,y}=pos[grip.side];
            if(end && x===0 && y>0){y--;x=terminal.cols;}
            const row=y-terminal.buffer.active.viewportY;
            if(row<0 || row>=terminal.rows || !cw || !ch){grip.button.hidden=true;grip.stem.removeAttribute('d');continue;}
            const edge=box.left+x*cw,top=box.top+row*ch,bottom=top+ch;
            let cx=edge-10,cy=top-12;
            if(end){cx=edge+10;cy=bottom+12;}
            if(cy<radius)cy=bottom+12;else if(cy>h-radius)cy=top-12;
            cx=Math.max(radius,Math.min(w-radius,cx));cy=Math.max(radius,Math.min(h-radius,cy));
            grip.cellX=edge+cw/2;if(end)grip.cellX=edge-cw/2;
            grip.cellY=top+ch/2;
            // Prefer a full row of clearance: the handles' invisible touch areas
            // must not cover a short word (or a single cell at the screen edge).
            if(cy>=grip.cellY && bottom+gripSize<=h)cy=bottom+radius;
            else if(top>=gripSize)cy=top-radius;
            else if(bottom+gripSize<=h){
                cy=bottom+radius;
            }
            points.push({grip,edge,top,bottom,cx,cy});
        }
        // Keep both touch targets reachable for a one-cell selection at a screen edge.
        if(points.length===2 && Math.abs(points[0].cy-points[1].cy)<gripSize && Math.abs(points[0].cx-points[1].cx)<gripSize){
            const [left,right]=points.slice().sort((a,b)=>a.edge-b.edge);
            left.cx=Math.max(radius,Math.min(w-radius-gripSize,(left.cx+right.cx)/2-radius));right.cx=left.cx+gripSize;
        }
        for(const {grip,edge,top,bottom,cx,cy} of points){
            let from=top,to=bottom;if(cy<top){from=bottom;to=top;}
            grip.stem.setAttribute('d',`M ${edge} ${from} V ${to} L ${cx} ${cy}`);
            // Aim the droplet's pointed (top-left) corner at the exact text boundary.
            const angle=Math.atan2(to-cy,edge-cx)*180/Math.PI+135;
            grip.button.style.setProperty('--angle',angle+'deg');
            grip.button.style.left=cx+'px';grip.button.style.top=cy+'px';grip.button.hidden=false;
        }
    }
    function adjustGrip(side,cell){
        const pos=terminal.getSelectionPosition();if(!pos || !cell)return;
        let lo=pos.start.y*terminal.cols+pos.start.x,hi=pos.end.y*terminal.cols+pos.end.x;
        if(side==='start'){
            if(cell.start>=hi)cell=bufferCell(Math.floor((hi-1)/terminal.cols),(hi-1)%terminal.cols);
            if(!cell)return;
            lo=cell.start;
        }else{
            if(cell.end<=lo)cell=bufferCell(Math.floor(lo/terminal.cols),lo%terminal.cols);
            if(!cell)return;
            hi=cell.end;
        }
        setRange(lo,hi,pos);
    }
    function setRange(lo,hi,pos){
        if(pos && lo===pos.start.y*terminal.cols+pos.start.x && hi===pos.end.y*terminal.cols+pos.end.x)return;
        terminal.select(lo%terminal.cols,Math.floor(lo/terminal.cols),hi-lo);syncGrips();
    }
    for(const grip of grips){
        const button=grip.button;
        button.addEventListener('keydown',e=>{
            if(e.key==='Enter' || e.key===' '){consume(e);copySelection();return;}
            if(e.key==='Escape'){consume(e);dismissSelection();return;}
            const box=surface.querySelector('.xterm-screen').getBoundingClientRect();
            let x=grip.cellX,y=grip.cellY;
            if(e.key==='ArrowUp')y-=box.height/terminal.rows;
            else if(e.key==='ArrowDown')y+=box.height/terminal.rows;
            else if(e.key==='ArrowLeft' || e.key==='ArrowRight'){
                const cell=hitCell(x,y);if(!cell)return;
                let col=cell.col-0.5;if(e.key==='ArrowRight')col=cell.col+(cell.end-cell.start)+0.5;
                x=box.left+col*box.width/terminal.cols;
            }else return;
            consume(e);adjustGrip(grip.side,hitCell(x,y));
        });
    }
    terminal.onScroll(syncGrips);terminal.onRender(syncGrips);terminal.onSelectionChange(syncGrips);
    terminal.onResize(()=>{if(!fitting)dismissSelection();});
    function fitScreen(){
        if(!document.body.clientWidth || !document.body.clientHeight)return;
        if(!anchor){fit.fit();return;}
        const size=fit.proposeDimensions();if(!size)return;
        if(size.cols!==terminal.cols){dismissSelection();fit.fit();return;}
        if(size.rows===terminal.rows){syncGrips();return;}
        const pos=terminal.getSelectionPosition();
        if(!pos){dismissSelection();fit.fit();return;}
        // xterm clears selection inside resize(). Track the original lines, including
        // trims at the scrollback limit, rather than restoring stale row numbers.
        const buffer=terminal.buffer.active,cols=terminal.cols,cursor=buffer.baseY+buffer.cursorY;
        const first=pos.start.y*cols+pos.start.x,last=pos.end.y*cols+pos.end.x-1;
        const start=terminal.registerMarker(Math.floor(first/cols)-cursor);
        const end=terminal.registerMarker(Math.floor(last/cols)-cursor);
        fitting=true;
        try{
            fit.fit();
            const shift=start.line-Math.floor(first/cols);
            if(!anchor || buffer!==terminal.buffer.active || cols!==terminal.cols || start.isDisposed || end.isDisposed ||
                end.line-Math.floor(last/cols)!==shift || !buffer.getLine(end.line)){dismissSelection();return;}
            anchor.start+=shift*cols;anchor.end+=shift*cols;
            terminal.select(first%cols,start.line,last-first+1);
        }finally{fitting=false;start.dispose();end.dispose();}
        syncGrips();
    }
    function dismissSelection(){
        if(!anchor)return;
        anchor=null;hideGrips();terminal.clearSelection();
    }
    function hitCell(x,y,box=surface.querySelector('.xterm-screen').getBoundingClientRect()){
        if(!box.width || !box.height)return null;
        const buffer=terminal.buffer.active;
        const row=buffer.viewportY+Math.max(0,Math.min(terminal.rows-1,Math.floor((y-box.top)*terminal.rows/box.height)));
        const col=Math.max(0,Math.min(terminal.cols-1,Math.floor((x-box.left)*terminal.cols/box.width)));
        return bufferCell(row,col);
    }
    function bufferCell(row,col){
        const line=terminal.buffer.active.getLine(row);if(!line)return null;
        while(col>0 && line.getCell(col).getWidth()===0)col--;
        const cell=line.getCell(col);
        const start=row*terminal.cols+col;
        return {line,col,start,end:start+Math.max(1,cell.getWidth())};
    }
    function selectTo(cell){
        if(!cell || !anchor)return;
        const a=Math.min(anchor.start,cell.start), b=Math.max(anchor.end,cell.end);
        setRange(a,b,terminal.getSelectionPosition());
    }
    function insideSelection(x,y,pos,box){
        // Dragging may clamp to the nearest cell; a tap in the screen margin must not.
        if(x<box.left || x>=box.left+box.width || y<box.top || y>=box.top+box.height)return false;
        const cell=hitCell(x,y,box);if(!cell)return false;
        return cell.start>=pos.start.y*terminal.cols+pos.start.x && cell.start<pos.end.y*terminal.cols+pos.end.x;
    }
    function copySelection(){
        if(!anchor)return;
        const text=terminal.getSelection();if(text)Birch.copyText(text);
        dismissSelection();
    }
    function tapSelection(x,y){
        const pos=terminal.getSelectionPosition();
        const box=surface.querySelector('.xterm-screen').getBoundingClientRect();
        if(pos && insideSelection(gesture.x,gesture.y,pos,box) && insideSelection(x,y,pos,box))copySelection();
        else dismissSelection();
    }
    function startSelection(){
        clearTimeout(holdTimer);
        gesture.kind='select';
        const hit=hitCell(gesture.x,gesture.y);if(!hit){dismissSelection();return;}
        let left=hit.col,right=hit.end-hit.start+left;
        if(/\S/.test(hit.line.getCell(left).getChars())){
            while(left>0){
                let prev=left-1;
                while(prev>0 && !hit.line.getCell(prev).getWidth())prev--;
                if(!/\S/.test(hit.line.getCell(prev).getChars()))break;
                left=prev;
            }
            while(right<terminal.cols){
                const next=hit.line.getCell(right);
                if(!/\S/.test(next.getChars()))break;
                right+=Math.max(1,next.getWidth());
            }
        }
        const rowStart=hit.start-hit.col;
        anchor={start:rowStart+left,end:rowStart+right};
        selectTo(hit);
    }
    function syncHistoryTrack(){
        const buffer=terminal.buffer.active;
        surface.classList.toggle('has-scrollback',buffer.type==='normal' && buffer.baseY>0);
    }
    terminal.onScroll(syncHistoryTrack);terminal.onResize(syncHistoryTrack);
    terminal.buffer.onBufferChange(syncHistoryTrack);syncHistoryTrack();
    const opts={capture:true,passive:false};
    function consume(e){e.preventDefault();e.stopImmediatePropagation();}
    function fromTouch(e){return e.pointerType==='touch' || !!(e.sourceCapabilities && e.sourceCapabilities.firesTouchEvents);}
    function finishGesture(){
        clearTimeout(holdTimer);
        if(gesture && gesture.kind==='zoom' && terminal.options.fontSize!==gesture.fontSize)Birch.saveFontSize(terminal.options.fontSize);
        if(gesture && gesture.kind==='grip')gesture.grip.button.removeAttribute('data-dragging');
        gesture=null;
        // Remaining fingers cannot start a new tap after a pinch or cancellation.
        if(contacts.size)gesture={kind:'suppress'};
    }
    function resetContacts(){
        const previous=[...contacts.entries()];
        contacts.clear();finishGesture();
        for(const [id,point] of previous)if(point.target.hasPointerCapture(id))point.target.releasePointerCapture(id);
    }
    function pinchDistance(){
        const [first,second]=contacts.values();
        return Math.hypot(first.x-second.x,first.y-second.y);
    }
    function activateInput(x,y){
        // Only an accepted pointerup may focus the editor or request the IME.
        // Back can hide the keyboard without removing the textarea's focus.
        terminal.textarea.inputMode='text';
        if(document.activeElement!==terminal.textarea)terminal.focus();
        if(!Birch.isKeyboardVisible())Birch.showKeyboard(keyboardRequest);
        if(terminal.modes.mouseTrackingMode==='none')return;
        const target=document.elementFromPoint(x,y);
        if(!target || !surface.contains(target))return;
        reportingMouse=true;
        try{
            target.dispatchEvent(new MouseEvent('mousedown',{bubbles:true,cancelable:true,button:0,buttons:1,clientX:x,clientY:y}));
            target.dispatchEvent(new MouseEvent('mouseup',{bubbles:true,cancelable:true,button:0,buttons:0,clientX:x,clientY:y}));
        }finally{reportingMouse=false;}
    }
    document.addEventListener('pointerdown',e=>{
        const grip=grips.find(item=>item.button===e.target && !item.button.hidden);
        lastPointerWasTouch=fromTouch(e);
        if(!lastPointerWasTouch && !grip)return;
        if(e.button!==0 || (!grip && !surface.contains(e.target)))return;
        // A new primary pointer also recovers from a contact lost during a window change.
        if(e.isPrimary && contacts.size)resetContacts();
        keyboardRequest=Birch.cancelKeyboardRequest();
        if(!Birch.isKeyboardVisible())terminal.textarea.inputMode='none';
        let target=surface;
        if(grip)target=grip.button;
        contacts.set(e.pointerId,{x:e.clientX,y:e.clientY,target});
        if(gesture && gesture.kind==='rail'){
            consume(e);return;
        }
        if(contacts.size===1 && e.target.closest('.scrollbar.vertical')){
            gesture={kind:'rail',id:e.pointerId};
            // xterm owns the thumb's pointer capture and scrolling, never editing focus.
            e.preventDefault();return;
        }
        consume(e);
        target.setPointerCapture(e.pointerId);
        if(contacts.size>1){
            const suppressed=gesture && gesture.kind==='suppress';
            finishGesture();
            if(contacts.size===2 && !suppressed){
                gesture={kind:'zoom',span:pinchDistance(),fontSize:terminal.options.fontSize};
                dismissSelection();
            }
            return;
        }
        if(grip){
            gesture={kind:'grip',grip,x:e.clientX,y:e.clientY,moved:false,dx:grip.cellX-e.clientX,dy:grip.cellY-e.clientY};
            grip.button.setAttribute('data-dragging','');
            return;
        }
        gesture={kind:'pending',x:e.clientX,y:e.clientY,lastY:e.clientY,pending:0,
            deadline:e.timeStamp+tapLimitMs,selection:anchor,
            cellHeight:Math.max(1,surface.querySelector('.xterm-screen').getBoundingClientRect().height/terminal.rows)};
        const current=gesture;
        const remaining=Math.max(0,Math.floor(current.deadline-performance.now())+1);
        holdTimer=setTimeout(()=>{if(gesture===current && gesture.kind==='pending')startSelection();},remaining);
    },opts);
    function moveContact(id,point){
        const contact=contacts.get(id);
        contact.x=point.clientX;contact.y=point.clientY;
        const g=gesture;
        if(g.kind==='zoom'){
            const span=pinchDistance();
            if(!g.span){g.span=span;return;}
            const size=Math.min(36,Math.max(8,Math.round(g.fontSize*span/g.span)));
            if(size!==terminal.options.fontSize){terminal.options.fontSize=size;fitScreen();}
            return;
        }
        if(g.kind==='suppress' || g.kind==='rail')return;
        const dx=point.clientX-g.x,dy=point.clientY-g.y;
        if(g.kind==='grip'){
            if(Math.hypot(dx,dy)>3)g.moved=true;
            if(g.moved)adjustGrip(g.grip.side,hitCell(point.clientX+g.dx,point.clientY+g.dy));
            return;
        }
        if(g.kind==='select' || g.kind==='extend'){
            if(g.kind==='select' && Math.hypot(dx,dy)<=touchSlop)return;
            g.kind='extend';selectTo(hitCell(point.clientX,point.clientY));return;
        }
        if(g.kind==='swipe' && Math.abs(dy)>touchSlop && Math.abs(dy)>=Math.abs(dx)){
            g.kind='scroll';dismissSelection();
        }
        if(g.kind!=='scroll')return;
        g.pending+=g.lastY-point.clientY;g.lastY=point.clientY;
        const lines=Math.trunc(g.pending/g.cellHeight);
        if(!lines)return;
        g.pending-=lines*g.cellHeight;
        if(terminal.buffer.active.type==='normal')terminal.scrollLines(lines);
        else{
            const target=document.elementFromPoint(point.clientX,point.clientY);
            if(target && surface.contains(target))target.dispatchEvent(new WheelEvent('wheel',{bubbles:true,cancelable:true,clientX:point.clientX,clientY:point.clientY,deltaY:lines,deltaMode:1}));
        }
    }
    function trackPointer(e){
        if(!contacts.has(e.pointerId))return;
        const rail=gesture.kind==='rail' && gesture.id===e.pointerId;
        if(rail)e.preventDefault();
        else consume(e);
        const cancelled=e.type==='pointercancel' || e.type==='lostpointercapture';
        if(!cancelled){
            let points=[];
            if(e.getCoalescedEvents)points=e.getCoalescedEvents();
            points.push(e);
            if(gesture.kind==='pending'){
                // Inspect the whole batch before deciding on a late long press.
                // Returning to the start never restores a rejected tap.
                if(points.some(point=>Math.hypot(point.clientX-gesture.x,point.clientY-gesture.y)>touchSlop)){
                    clearTimeout(holdTimer);gesture.kind='swipe';
                }else if(e.timeStamp>gesture.deadline)startSelection();
            }
            for(const point of points)moveContact(e.pointerId,point);
        }
        if(e.type==='pointermove')return;
        if(!cancelled && gesture.kind==='pending'){
            // A tap that began with a selection never falls through to editing.
            if(!gesture.selection)activateInput(e.clientX,e.clientY);
            else if(gesture.selection===anchor)tapSelection(e.clientX,e.clientY);
        }
        contacts.delete(e.pointerId);
        if(rail || gesture.kind!=='rail')finishGesture();
    }
    for(const name of ['pointermove','pointerup','pointercancel','lostpointercapture'])document.addEventListener(name,trackPointer,opts);
    // No TouchEvent recognizer: suppress the browser/xterm compatibility path only.
    for(const name of ['touchstart','touchmove','touchend','touchcancel'])document.addEventListener(name,consume,opts);
    for(const name of ['mousedown','mouseup','click'])document.addEventListener(name,e=>{
        if(!reportingMouse && (lastPointerWasTouch || fromTouch(e)))consume(e);
    },opts);
    document.addEventListener('contextmenu',e=>{
        if(gesture || lastPointerWasTouch || fromTouch(e) || e.target.closest('.grip'))consume(e);
        else e.preventDefault();
    },opts);
    window.addEventListener('blur',()=>{
        keyboardRequest=Birch.cancelKeyboardRequest();resetContacts();
    });
    return fitScreen;
}
const cursorKeys = new Map([['↑','A'],['↓','B'],['→','C'],['←','D'],['HOME','H'],['END','F']]);
const pageKeys = new Map([['PGUP','5'],['PGDN','6']]);
let ctrl=false, alt=false;
function clearShortcutModifiers(){
    if(!ctrl && !alt)return;
    ctrl=false; alt=false; Birch.modifiersChanged(false,false);
}
function controlChar(ch){
    if(ch===' ' || ch==='2')return '\x00';
    if(ch==='?' || ch==='8')return '\x7f';
    if(ch>='3' && ch<='7')return String.fromCharCode(ch.charCodeAt(0)-24);
    if(/^[a-zA-Z@\[\\\]\^_]$/.test(ch))return String.fromCharCode(ch.charCodeAt(0)&31);
    return ch;
}
function shortcutSequence(key){
    let modifier=1;
    if(alt)modifier+=2;
    if(ctrl)modifier+=4;
    if(cursorKeys.has(key)){
        const suffix=cursorKeys.get(key);
        if(modifier!==1)return '\x1b[1;'+modifier+suffix;
        if(terminal.modes.applicationCursorKeysMode)return '\x1bO'+suffix;
        return '\x1b['+suffix;
    }
    if(pageKeys.has(key)){
        let prefix='\x1b['+pageKeys.get(key);
        if(modifier!==1)prefix+=';'+modifier;
        return prefix+'~';
    }
    if(key==='TAB')return '\t';
    if(key==='ESC'){
        if(alt)return '\x1b\x1b';
        return '\x1b';
    }
    return '';
}
window.pressShortcut=function(key){
    if(key==='CTRL' || key==='ALT'){
        if(key==='CTRL')ctrl=!ctrl;
        else alt=!alt;
        Birch.modifiersChanged(ctrl,alt);
    }else{
        const sequence=shortcutSequence(key);
        if(!sequence)return;
        clearShortcutModifiers();
        terminal.input(sequence,true);
    }
    terminal.focus();
};
window.clearShortcutModifiers=clearShortcutModifiers;
terminal.onData(data=>{
    if(ctrl || alt){
        // Reports from the terminal (e.g. cursor position) are not keystrokes.
        if(data.length>1 && data.startsWith('\x1b')){Birch.input(data);return;}
        if(data.length===1){
            if(ctrl)data=controlChar(data);
            if(alt)data='\x1b'+data;
        }
        clearShortcutModifiers();
    }
    Birch.input(data);
});
window.deliver = function(data){terminal.write(Uint8Array.from(atob(data),c=>c.charCodeAt(0)),()=>Birch.consumed());};
// A freshly attached WebView can load the document before its first nonzero layout.
let fitScreen;
function layoutTerminal(){
    if(!surface.clientWidth || !surface.clientHeight)return;
    if(fitScreen){fitScreen();return;}
    terminal.open(surface);
    window.keyboardVisibilityChanged(Birch.isKeyboardVisible());
    fitScreen=enableGestures();
    fitScreen();
    Birch.ready(terminal.cols,terminal.rows);
}
new ResizeObserver(layoutTerminal).observe(surface);
layoutTerminal();
