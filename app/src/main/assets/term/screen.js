'use strict';
const terminal = new Terminal({cursorBlink:true,fontFamily:'monospace',fontSize:Math.min(36,Math.max(8,Birch.fontSize())),scrollback:2500,allowProposedApi:false,theme:{background:'#151a17',foreground:'#e1e9e2',cursor:'#a0d8af',selectionBackground:'#aadbb8',selectionInactiveBackground:'#aadbb8',selectionForeground:'#102218'}});
const fit = new FitAddon.FitAddon();
terminal.loadAddon(fit);
const surface=document.getElementById('term');
terminal.open(surface);
terminal.onResize(({cols,rows})=>Birch.resize(cols,rows));
// Recognize touch gestures before letting WebView or xterm change editing focus.
function enableGestures(){
    let mode='idle', lastY=0, startX=0, pending=0, cellHeight=1, span=0, startFont=0;
    const tapLimitMs=500;
    const touchSlop=Math.max(12,Birch.touchSlopDp());
    let holdTimer=0, press=null, anchor=null, fitting=false;
    const grips=['start','end'].map(side=>({side,button:document.getElementById(side+'-grip'),stem:document.getElementById(side+'-stem')}));
    const gripSize=parseFloat(getComputedStyle(grips[0].button).width),radius=gripSize/2;
    let drag=null, lastPointerWasTouch=false;
    function hideGrips(){
        finishDrag();
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
    function finishDrag(){
        if(!drag)return;
        const {button,id}=drag;drag=null;
        button.removeAttribute('data-dragging');
        if(button.hasPointerCapture(id))button.releasePointerCapture(id);
    }
    function trackDrag(e){
        if(!drag || e.pointerId!==drag.id)return;
        consume(e);
        if(e.type!=='pointercancel'){
            if(Math.hypot(e.clientX-drag.x,e.clientY-drag.y)>3)drag.moved=true;
            if(drag.moved)adjustGrip(drag.side,hitCell(e.clientX+drag.dx,e.clientY+drag.dy));
        }
        if(e.type!=='pointermove')finishDrag();
    }
    // A moving handle can lose capture; keep tracking that contact across the WebView.
    for(const event of ['pointermove','pointerup','pointercancel'])document.addEventListener(event,trackDrag,true);
    for(const grip of grips){
        const button=grip.button;
        button.addEventListener('pointerdown',e=>{
            if(fromTouch(e)){lastPointerWasTouch=true;consume(e);}
            if(drag || e.button!==0 || !e.isPrimary)return;
            // A new primary touch can adjust a committed selection even if WebView
            // never delivered the previous contact's final touch event.
            if(e.pointerType==='touch')finishSelectionContact();
            if(mode!=='idle' || button.hidden)return;
            if(!fromTouch(e))consume(e);
            button.setPointerCapture(e.pointerId);
            drag={id:e.pointerId,side:grip.side,button,x:e.clientX,y:e.clientY,moved:false,dx:grip.cellX-e.clientX,dy:grip.cellY-e.clientY};
            button.setAttribute('data-dragging','');
        });
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
    function clearPress(){clearTimeout(holdTimer);holdTimer=0;press=null;}
    function beginPress(touch,time){
        const current={id:touch.identifier,deadline:time+tapLimitMs,selection:anchor};
        press=current;mode='pending';startX=touch.clientX;lastY=touch.clientY;pending=0;
        const screen=surface.querySelector('.xterm-screen');
        cellHeight=Math.max(1,screen.getBoundingClientRect().height/terminal.rows);
        // DOM timestamps and performance.now() share an origin. Count from physical
        // down, not from delivery to a busy renderer. Exactly 500 ms is still a tap.
        const remaining=Math.max(0,Math.floor(current.deadline-performance.now())+1);
        holdTimer=setTimeout(()=>{if(press===current)startSelection();},remaining);
    }
    function finishSelectionContact(){
        if(mode!=='select' && mode!=='extend')return;
        mode='idle';
        // A valid range in the line's blank padding has no clipboard text yet.
        // Keep its handles: the next contact can extend it onto actual output.
        if(!terminal.hasSelection())dismissSelection();
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
        if(pos && insideSelection(startX,lastY,pos,box) && insideSelection(x,y,pos,box))copySelection();
        else dismissSelection();
    }
    function startSelection(){
        if(!press || mode!=='pending')return;
        clearPress();mode='select';
        const hit=hitCell(startX,lastY);if(!hit){dismissSelection();return;}
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
    function onHistoryTrack(target){return !!(target && target.closest && target.closest('.scrollbar.vertical'));}
    function fromTouch(e){return e.pointerType==='touch' || !!(e.sourceCapabilities && e.sourceCapabilities.firesTouchEvents);}
    surface.addEventListener('pointerdown',e=>{
        lastPointerWasTouch=fromTouch(e);
        if(!lastPointerWasTouch)return;
        // Let xterm capture the pointer and map the thumb position to scrollback.
        // Prevent the browser's focus change, not propagation to the scrollbar.
        if(onHistoryTrack(e.target) && e.isPrimary && !drag && !press){e.preventDefault();return;}
        consume(e);
    },opts);
    surface.addEventListener('contextmenu',e=>{
        if(press && fromTouch(e) && e.timeStamp>press.deadline)startSelection();
        if(mode!=='idle' || lastPointerWasTouch || fromTouch(e) || e.button!==2)consume(e);
        else e.preventDefault();
    },opts);
    for(const name of ['mousedown','mouseup','click'])surface.addEventListener(name,e=>{
        // The only programmatic mouse events on this terminal are our TUI reports.
        // A real mouse/pen starts its own pointerdown, unlike a touch's trailing click.
        if(e.isTrusted && (lastPointerWasTouch || fromTouch(e)))consume(e);
    },opts);
    for(const {button} of grips){
        // Pointer events drive the drag; the handle itself must not become an IME target.
        for(const name of ['touchstart','touchend','contextmenu'])button.addEventListener(name,consume,opts);
    }
    function activateInput(x,y){
        // This runs synchronously inside the accepted touchend. IME visibility is
        // not editor focus: Back may hide the IME while the textarea stays focused.
        const editor=terminal.textarea;
        if(document.activeElement!==editor)terminal.focus();
        if(!Birch.isKeyboardVisible())Birch.showKeyboard();
        if(terminal.modes.mouseTrackingMode==='none')return;
        const target=document.elementFromPoint(x,y);
        if(!target || !surface.contains(target))return;
        target.dispatchEvent(new MouseEvent('mousedown',{bubbles:true,cancelable:true,button:0,buttons:1,clientX:x,clientY:y}));
        target.dispatchEvent(new MouseEvent('mouseup',{bubbles:true,cancelable:true,button:0,buttons:0,clientX:x,clientY:y}));
    }
    const distance=points=>Math.hypot(points[0].clientX-points[1].clientX,points[0].clientY-points[1].clientY);
    function consume(e){e.preventDefault();e.stopPropagation();}
    function finishZoom(){if(mode==='zoom' && terminal.options.fontSize!==startFont)Birch.saveFontSize(terminal.options.fontSize);}
    surface.addEventListener('touchstart',e=>{
        consume(e);lastPointerWasTouch=true;
        if(drag || mode==='rail')return;
        clearPress();
        if(onHistoryTrack(e.target)){finishZoom();mode='rail';return;}
        const p=e.touches;
        if(p.length===2){
            dismissSelection();mode='zoom';span=distance(p);startFont=terminal.options.fontSize;
        }else if(p.length===1)beginPress(p[0],e.timeStamp);
        else{finishZoom();mode='suppress';}
    },opts);
    surface.addEventListener('touchmove',e=>{
        consume(e);
        const p=e.touches;
        if(press && p.length===1 && p[0].identifier===press.id && e.timeStamp>press.deadline)startSelection();
        if(mode==='zoom' && p.length===2){
            if(!span){span=distance(p);return;}
            const size=Math.min(36,Math.max(8,Math.round(startFont*distance(p)/span)));
            if(size!==terminal.options.fontSize){terminal.options.fontSize=size;fitScreen();}
            return;
        }
        if((mode==='select' || mode==='extend') && p.length===1){
            if(mode==='select' && Math.hypot(p[0].clientX-startX,p[0].clientY-lastY)<=touchSlop)return;
            mode='extend';selectTo(hitCell(p[0].clientX,p[0].clientY));return;
        }
        if(p.length!==1 || (mode!=='pending' && mode!=='swipe' && mode!=='scroll'))return;
        const delta=lastY-p[0].clientY;
        if(mode==='pending' || mode==='swipe'){
            const dx=startX-p[0].clientX;
            if(Math.hypot(delta,dx)<=touchSlop)return;
            clearPress();
            if(Math.abs(delta)<Math.abs(dx)){mode='swipe';return;}
            dismissSelection();mode='scroll';
        }
        pending+=delta;lastY=p[0].clientY;
        const lines=Math.trunc(pending/cellHeight);if(!lines)return;
        pending-=lines*cellHeight;
        if(terminal.buffer.active.type==='normal')terminal.scrollLines(lines);
        else {
            // Full-screen programs have no scrollback: send wheel input at the finger instead.
            const target=document.elementFromPoint(p[0].clientX,p[0].clientY);
            if(target && surface.contains(target))target.dispatchEvent(new WheelEvent('wheel',{bubbles:true,cancelable:true,clientX:p[0].clientX,clientY:p[0].clientY,deltaY:lines,deltaMode:1}));
        }
    },opts);
    function end(e){
        consume(e);
        if(mode==='rail'){if(!e.touches.length)mode='idle';return;}
        if(mode==='idle')return;
        if(press && !e.touches.length){
            const up=e.changedTouches[0];
            if(up && up.identifier===press.id && Math.hypot(up.clientX-startX,up.clientY-lastY)<=touchSlop){
                if(e.timeStamp>press.deadline)startSelection();
                else if(e.type==='touchend'){
                    // A selection tap never falls through to editing, even if the
                    // selected buffer was cleared during this contact.
                    if(!press.selection)activateInput(up.clientX,up.clientY);
                    else if(press.selection===anchor)tapSelection(up.clientX,up.clientY);
                }
            }
        }
        clearPress();finishZoom();finishSelectionContact();
        mode='idle';if(e.touches.length)mode='suppress';
    }
    window.addEventListener('blur',()=>{
        clearPress();finishZoom();finishDrag();
        finishSelectionContact();
        mode='idle';
    });
    surface.addEventListener('touchend',end,opts);surface.addEventListener('touchcancel',end,opts);
    return fitScreen;
}
const fitScreen=enableGestures();
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
new ResizeObserver(fitScreen).observe(document.body);
fitScreen(); Birch.ready(terminal.cols,terminal.rows);
