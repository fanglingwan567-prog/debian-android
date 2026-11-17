// No Android device needed: use the bundled xterm parser and input path; mock only the UI and bridge.
const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const {Terminal}=require('../app/src/main/assets/term/xterm.js');
let term,focuses=0,fit=0,ready=0,geometry,consumed=0;
let keyboardVisible=false,keyboardRequests=0,clock=0,contact={clientX:0,clientY:0},refit,dimensions;
const received=[],states=[],writes=[],saved=[],wheels=[],mouseEvents=[];
const clipboard=[],timers=new Map(),editor={};let timerId=0,nextDispatchLag=0;
function setTimeoutFake(fn,ms){assert.ok(ms>=0 && ms<=501);timers.set(++timerId,{fn,due:clock+ms});return timerId;}
function hold(){assert.equal(timers.size,1);const [id,{fn,due}]=timers.entries().next().value;clock=Math.max(clock,due);timers.delete(id);fn();}
const controls={};for(const id of ['start-grip','end-grip','start-stem','end-stem'])controls[id]={hidden:true,style:{setProperty(k,v){this[k]=v;}},setAttribute(k,v){this[k]=v;},removeAttribute(k){delete this[k];},setPointerCapture(id){this.capture=id;},hasPointerCapture(id){return this.capture===id;},releasePointerCapture(id){assert.equal(this.capture,id);delete this.capture;},addEventListener(name,fn){this[name]=fn;}};
const listeners={},options={};const wheelTarget={dispatchEvent(e){
    if(e.type==='wheel'){wheels.push(e);return;}
    const captured=surfaceMouse(e.type,{...e,isTrusted:false});if(!captured.stopped)mouseEvents.push(e);
}};
const surfaceClasses=new Set();
const surface={classList:{toggle(name,on){if(on)surfaceClasses.add(name);else surfaceClasses.delete(name);}},addEventListener(name,fn,opts){listeners[name]=fn;options[name]=opts;},contains(el){return el===wheelTarget;},querySelector(){return {getBoundingClientRect(){return {height:term.rows*20,width:term.cols*10,top:0,left:0};}};}};
const documentEvents={},windowEvents={};
function touch(name,...coords){return touchOn(surface,name,...coords);}
function touchOn(target,name,...coords){
    const touches=coords.map(([clientX,clientY],identifier)=>({clientX,clientY,identifier}));
    const e={target,type:name,timeStamp:++clock,touches,changedTouches:[contact],preventDefault(){this.prevented=true;},stopPropagation(){}};
    clock+=nextDispatchLag;nextDispatchLag=0;
    if(touches.length)contact=touches[0];listeners[name](e);return !!e.prevented;
}
function tap(x,y){touch('touchstart',[x,y]);return touch('touchend');}
function surfaceMouse(name,init){
    const e={button:0,isTrusted:true,preventDefault(){this.prevented=true;},stopPropagation(){this.stopped=true;},...init};
    listeners[name](e);return e;
}
// Dispatch capture before the target's normal action. An unlabelled compatibility
// MouseEvent can look identical to a mouse event if one only inspects this event.
function releaseMouse(name){
    const e=surfaceMouse(name,{detail:1});
    if(!e.stopped && name==='mousedown')term.clearSelection(); // xterm starts a new single-click selection
    return e;
}
class Fit {
    activate(){} dispose(){}
    proposeDimensions(){return dimensions;}
    fit(){fit++;if(dimensions)term.resize(dimensions.cols,dimensions.rows);}
}
function fitTo(cols,rows){dimensions={cols,rows};refit();dimensions=null;}
class Screen extends Terminal {
    constructor(options){super(options);term=this;}
    open(){}
    onSelectionChange(listener){this.notifySelection=listener;}
    resize(cols,rows){
        if(rows!==this.rows)this.clearSelection(); // xterm's DOM selection service clears on row changes.
        super.resize(cols,rows);
    }
    get textarea(){return editor;}
    focus(){focuses++;env.document.activeElement=editor;}
    // xterm's DOM selection service is created only by open(). Keep real parser and cell widths.
    select(x,y,length){this.mark={x,y,length};}
    getSelectionPosition(){
        if(!this.mark)return undefined;
        const {x,y,length}=this.mark, last=x+length-1;
        return {start:{x,y},end:{x:last%this.cols+1,y:y+Math.floor(last/this.cols)}};
    }
    clearSelection(){this.mark=null;if(this.notifySelection)this.notifySelection();}
    hasSelection(){return !!this.mark;}
    getSelection(){
        if(!this.mark)return '';
        let {x,y,length}=this.mark,out='';
        while(length>0){const line=this.buffer.active.getLine(y++),n=Math.min(length,this.cols-x);
            if(length!==this.mark.length && !line.isWrapped)out+='\n';
            out+=line.translateToString(true,x,x+n);length-=n;x=0;
        }
        return out;
    }
    write(data,cb){writes.push(data);super.write(data,cb);}
}
const Birch={
    showKeyboard(){assert.equal(env.document.activeElement,editor,'show requires the editor to have focus');keyboardRequests++;},isKeyboardVisible(){return keyboardVisible;},touchSlopDp(){return 14;},copyText(s){clipboard.push(s);},
    fontSize(){return 16;},saveFontSize(size){saved.push(size);},
    ready(c,r){ready++;assert.deepEqual([c,r],[80,24]);},
    input(s){received.push(s);},resize(c,r){geometry=[c,r];},
    modifiersChanged(c,a){states.push([c,a]);},consumed(){consumed++;}
};
const env={performance:{now(){return clock;}},Terminal:Screen,FitAddon:{FitAddon:Fit},getComputedStyle(){return {width:"52px"};},document:{activeElement:null,addEventListener(name,fn){documentEvents[name]=fn;},body:{clientWidth:900,clientHeight:600},getElementById(id){if(id==='term')return surface;return controls[id];},elementFromPoint(){return wheelTarget;}},MouseEvent:class{constructor(type,init){this.type=type;Object.assign(this,init);}},WheelEvent:class{constructor(type,init){this.type=type;Object.assign(this,init);}},window:{addEventListener(name,fn){windowEvents[name]=fn;}},setTimeout:setTimeoutFake,clearTimeout:id=>timers.delete(id),ResizeObserver:class{constructor(fn){refit=fn;}observe(){}},Birch,atob,Uint8Array};
vm.runInNewContext(fs.readFileSync(require.resolve('../app/src/main/assets/term/screen.js'),'utf8'),env);
const press=env.window.pressShortcut;
function key(name,bytes){press(name);assert.deepEqual(received.splice(0),[bytes],name);}
function type(text,bytes){term.input(text,true);assert.deepEqual(received.splice(0),[bytes]);}
function output(s){return new Promise(resolve=>term.write(s,resolve));}
const cursor=[['↑','A'],['↓','B'],['→','C'],['←','D'],['HOME','H'],['END','F']];
async function test(){
    assert.equal(ready,1);assert.ok(fit>0);term.resize(90,30);assert.deepEqual(geometry,[90,30]);
    assert.equal(term.options.fontSize,16);
    const start=controls['start-grip'],end=controls['end-grip'];
    await output('… ' + '\x1b[31m中e\u0301文\x1b[0m end\r\nnext line');
    assert.ok(surfaceMouse('pointerdown',{pointerType:'touch'}).prevented,'pending touch must not give WebView permission to refocus');
    // A small drift before timeout must select the original word, even across a line boundary.
    assert.equal(touch('touchstart',[35,10]),true);assert.equal(focuses,0);assert.equal(keyboardRequests,0);
    const menu=surfaceMouse('contextmenu',{button:2});assert.ok(options.contextmenu.capture && menu.prevented && menu.stopped);
    touch('touchmove',[41,22]);hold();assert.equal(term.getSelection(),'中e\u0301文');
    touch('touchmove',[41,22]);assert.equal(term.getSelection(),'中e\u0301文');
    assert.equal(touch('touchend'),true);assert.deepEqual(clipboard,[]);
    for(const name of ['mousedown','mouseup','click']){
        const synthetic=surfaceMouse(name,{sourceCapabilities:{firesTouchEvents:true}});
        assert.ok(options[name].capture && synthetic.stopped && synthetic.prevented);
    }
    assert.equal(focuses,0);assert.equal(keyboardRequests,0);
    tap(35,10);assert.deepEqual(clipboard,['中e\u0301文']);assert.ok(!term.hasSelection());
    // One completed tap opts into input. A long press after that must not refocus the editor.
    assert.equal(touch('touchstart',[40,10]),true);assert.equal(focuses,0);
    assert.equal(touch('touchmove',[41,11]),true);clock+=498;assert.equal(touch('touchend'),true); // exactly 500 ms: input
    assert.equal(focuses,1);assert.equal(keyboardRequests,1);
    // The accepted touchend has already focused the actual editing element; a trailing
    // WebView compatibility click must not own the same decision a second time.
    for(const name of ['mousedown','mouseup','click']){
        const event=surfaceMouse(name,{sourceCapabilities:{firesTouchEvents:true}});
        assert.ok(event.stopped && event.prevented);
    }
    assert.ok(surfaceMouse('mousedown',{sourceCapabilities:{firesTouchEvents:true}}).stopped);
    keyboardVisible=true; // Simulate actual Android IME insets, not merely a show request.
    assert.ok(surfaceMouse('pointerdown',{pointerType:'touch'}).prevented);
    assert.ok(touch('touchstart',[35,10]));
    assert.ok(surfaceMouse('contextmenu',{button:2}).stopped);
    clock+=500;assert.ok(touch('touchend')); // 501 ms; timer delivery lost the race to release.
    assert.ok(surfaceMouse('mousedown',{sourceCapabilities:{firesTouchEvents:true}}).stopped);
    assert.equal(keyboardRequests,1);assert.equal(focuses,1);
    assert.equal(term.getSelection(),'中e\u0301文');assert.ok(!controls['start-grip'].hidden && !controls['end-grip'].hidden);
    fitTo(90,20);fitTo(90,19);assert.equal(term.getSelection(),'中e\u0301文');assert.ok(!controls['end-grip'].hidden);
    tap(600,210);fitTo(90,30);assert.equal(focuses,1);assert.equal(keyboardRequests,1);
    assert.ok(touch('touchstart',[35,10]));assert.ok(touch('touchmove',[41,12]));hold();fitTo(90,20);
    touch('touchmove',[31,26]);assert.ok(touch('touchend'));tap(15,26);fitTo(90,30);
    assert.equal(keyboardRequests,1);assert.equal(focuses,1);
    assert.equal(clipboard.at(-1),'中e\u0301文 end\nnext');
    touch('touchstart',[20,25]);hold();touch('touchmove',[31,10]);touch('touchend');assert.equal(term.getSelection(),'中e\u0301文 end\nnext');
    tap(600,210);assert.equal(clipboard.length,2);
    function pointer(target,event,id,x,y){
        const e={type:event,target,button:0,pointerType:'touch',isPrimary:id!==8,pointerId:id,clientX:x,clientY:y,preventDefault(){},stopPropagation(){this.stopped=true;}};
        if(documentEvents[event])documentEvents[event](e);
        if(!e.stopped && target[event])target[event](e);
    }
    function moveGrip(grip,dx,dy){const x=parseFloat(grip.style.left),y=parseFloat(grip.style.top);
        pointer(grip,'pointerdown',9,x,y);assert.equal(grip.capture,9);
        pointer(grip,'pointermove',8,x+100,y+20); // A second contact must not adjust this endpoint.
        pointer(grip,'pointermove',9,x+dx,y+dy);pointer(grip,'pointerup',9,x+dx,y+dy);}
    touch('touchstart',[35,10]);hold();touch('touchend');assert.ok(!start.hidden && !end.hidden);
    assert.ok(controls['start-stem'].d.startsWith('M 20 '));assert.ok(controls['end-stem'].d.startsWith('M 70 '));
    // Grab off-center, lose element capture, and continue with just the initiating pointer.
    const x=parseFloat(start.style.left)+15,y=parseFloat(start.style.top)+5;
    pointer(start,'pointerdown',9,x,y);const originalMark=term.mark;
    start.releasePointerCapture(9);pointer(start,'lostpointercapture',9,x,y);
    pointer(surface,'pointermove',9,x+2,y+1);pointer(surface,'pointermove',9,x+9,y);
    pointer(surface,'pointermove',8,x+55,y+25);pointer(surface,'pointerup',8,x+55,y+25);
    assert.equal(term.mark,originalMark);
    touch('touchstart',[x,y],[300,210]);touch('touchend',[x,y]);assert.equal(timers.size,0);
    pointer(surface,'pointermove',9,x+20,y);pointer(surface,'pointerup',9,x+20,y);
    assert.equal(term.getSelection(),'e\u0301文');
    moveGrip(end,-20,0);assert.equal(term.getSelection(),'e\u0301');
    moveGrip(start,100,0);assert.equal(term.getSelection(),'e\u0301'); // crossing cannot invert or erase selection
    moveGrip(start,-15,0);assert.equal(term.getSelection(),'中e\u0301');
    moveGrip(end,-10,20);assert.equal(term.getSelection(),'中e\u0301文 end\nnext');
    tap(15,26);assert.equal(clipboard.at(-1),'中e\u0301文 end\nnext');assert.ok(start.hidden && end.hidden);assert.equal(controls['start-stem'].d,undefined);
    touch('touchstart',[5,10]);hold();touch('touchend');assert.ok(parseFloat(end.style.left)-parseFloat(start.style.left)>=52);
    pointer(start,'pointerdown',1,22,37);pointer(surface,'pointercancel',1,22,37);assert.equal(start.capture,undefined);
    const cancelled=term.mark;pointer(surface,'pointermove',1,200,37);assert.equal(term.mark,cancelled);
    pointer(end,'pointerdown',3,75,37);windowEvents.blur();assert.equal(end.capture,undefined);
    touch('touchstart',[195,20]);assert.equal(term.mark,cancelled);touch('touchend');assert.ok(start.hidden && end.hidden);
    touch('touchstart',[35,10]);windowEvents.blur();assert.equal(timers.size,0);
    touch('touchstart',[35,10]);hold();touch('touchend');moveGrip(start,150,0);assert.equal(term.getSelection(),'文');tap(600,210);
    touch('touchstart',[35,10]);hold();touch('touchend');moveGrip(end,-150,0);assert.equal(term.getSelection(),'中');
    // At the opposite screen edge neither handle may be under the other or outside the viewport.
    await output('\x1b[30;90Hx');touch('touchstart',[896,593]);hold();touch('touchend');assert.equal(term.getSelection(),'x');
    assert.ok(parseFloat(end.style.left)-parseFloat(start.style.left)>=52);assert.ok(parseFloat(end.style.left)<=874);
    assert.ok(parseFloat(end.style.top)<=574);
    fitTo(89,30);assert.ok(start.hidden && end.hidden);term.resize(90,30);
    await output('\x1b[30;90Hx');
    for(const [x,y] of [[5,10],[895,590]]){
        touch('touchstart',[x,y]);hold();touch('touchend');
        for(const g of [start,end])assert.ok(Math.abs(parseFloat(g.style.left)-x)>=26 || Math.abs(parseFloat(g.style.top)-y)>=26,'handle covers the only selected cell');
        const text=term.getSelection();assert.ok(text);assert.ok(tap(x,y));assert.equal(clipboard.at(-1),text);
    }
    touch('touchstart',[35,10]);touch('touchmove',[51,10]);assert.equal(timers.size,0);touch('touchend');
    // A hold and its trailing events never copy, cancel or refocus. Only a new tap can.
    const word='中e\u0301文',requestsBefore=keyboardRequests,focusBefore=focuses;
    for(const ime of [false,true]){
        keyboardVisible=ime;
        for(const ending of ['touchend','touchcancel','blur']){
            const copies=clipboard.length;
            touch('touchstart',[35,10]);hold(); // no touchmove
            assert.equal(term.getSelection(),word);
            assert.ok(!start.hidden && !end.hidden);
            if(ending==='blur'){windowEvents.blur();touch('touchend');}else touch(ending);
            for(const event of ['mousedown','mouseup','click'])releaseMouse(event);
            assert.equal(term.getSelection(),word,'an unlabelled release must not reach xterm as a new click');
            assert.ok(!start.hidden && !end.hidden);assert.equal(clipboard.length,copies);
            assert.ok(touch('touchstart',[35,10]));touch('touchcancel');
            assert.equal(term.getSelection(),word);assert.equal(clipboard.length,copies);
            assert.ok(tap(35,10)); // second cell of a wide character
            for(const event of ['mousedown','mouseup','click'])assert.ok(releaseMouse(event).stopped);
            assert.equal(clipboard.length,copies+1);assert.equal(clipboard.at(-1),word);
            assert.ok(!term.hasSelection());
        }
        const beforeCancel=clipboard.length;
        for(const [down,up] of [[70,70],[19,21],[69,71]]){
            touch('touchstart',[35,10]);hold();touch('touchend');
            touch('touchstart',[down,10]);touch('touchmove',[up,10]);assert.ok(touch('touchend'));
            assert.ok(!term.hasSelection());assert.equal(clipboard.length,beforeCancel);
        }
        touch('touchstart',[895,590]);hold();touch('touchend');tap(900,590);
        assert.ok(!term.hasSelection());assert.equal(clipboard.length,beforeCancel); // margin isn't clamped onto the selection
        touch('touchstart',[200,100]);hold();touch('touchend');tap(200,100);
        assert.equal(clipboard.length,beforeCancel);assert.ok(!term.hasSelection()); // don't overwrite clipboard with empty text
        touch('touchstart',[35,10]);hold();touch('touchend');touch('touchstart',[35,10]);
        term.clearSelection();assert.ok(touch('touchend'));assert.equal(clipboard.length,beforeCancel); // invalidated mid-tap
        touch('touchstart',[35,10]);clock+=498;touch('touchcancel'); // 499 ms
        assert.ok(!term.hasSelection() && !timers.size);
        touch('touchstart',[35,10]);clock+=500;touch('touchcancel'); // 501 ms, timer is queued
        assert.equal(term.getSelection(),word);assert.ok(!timers.size);tap(600,210);
        touch('touchstart',[35,10]);clock+=500;
        surfaceMouse('contextmenu',{pointerType:'touch',timeStamp:clock});assert.ok(!term.hasSelection());
        surfaceMouse('contextmenu',{pointerType:'touch',timeStamp:++clock});assert.ok(!timers.size);
        touch('touchcancel');assert.equal(term.getSelection(),word);tap(600,210);
        touch('touchstart',[35,10]);hold();touch('touchmove',[31,26]);touch('touchcancel');
        assert.equal(term.getSelection(),word+' end\nnext');tap(600,210);
        touch('touchstart',[35,10]);hold(); // WebView never delivers the release; next contact targets a handle.
        moveGrip(start,20,0);assert.equal(term.getSelection(),'e\u0301文');tap(600,210);
        touch('touchstart',[200,100]);hold();assert.ok(!start.hidden && !end.hidden);
        touch('touchend');assert.ok(!start.hidden && !end.hidden,'an empty cell is still an adjustable range');
        assert.equal(term.getSelection(),'');
        moveGrip(start,-200,-100);assert.ok(term.getSelection().includes(word));
        start.keydown({key:'Escape',preventDefault(){},stopPropagation(){}});assert.ok(!term.hasSelection());
        touch('touchstart',[35,10]);hold();touch('touchend');end.keydown({key:'Enter',preventDefault(){},stopPropagation(){}});
        assert.equal(clipboard.at(-1),word);assert.ok(!term.hasSelection());
        touch('touchstart',[35,10]);hold();touch('touchend');
        surfaceMouse('pointerdown',{pointerType:'mouse',isPrimary:true,button:0});
        assert.ok(!releaseMouse('mousedown').stopped);assert.ok(!term.hasSelection()); // genuine new mouse click
        assert.equal(keyboardRequests,requestsBefore);assert.equal(focuses,focusBefore);
    }
    touch('touchstart',[35,10]);touch('touchstart',[35,10],[70,10]);assert.equal(timers.size,0);touch('touchend');
    await output('history\r\n'.repeat(80));const bottom=term.buffer.active.viewportY;assert.ok(bottom>=40);
    // The track and its nested thumb must receive the original pointerdown so the bundled
    // scrollbar can capture it. The separate touch stream must never start text gestures.
    const track={closest(selector){assert.equal(selector,'.scrollbar.vertical');return this;}};
    const thumb={closest:selector=>track.closest(selector)};
    const railFocus=focuses,railKeyboard=keyboardRequests,railClipboard=clipboard.length;
    for(const ime of [false,true])for(const target of [track,thumb]){
        keyboardVisible=ime;
        const down=surfaceMouse('pointerdown',{target,pointerType:'touch',isPrimary:true});
        assert.ok(!down.stopped,'scrollbar did not receive pointerdown');
        assert.ok(down.prevented,'do not move editing focus');
        touchOn(target,'touchstart',[898,95]);assert.equal(timers.size,0);
        clock+=550;touchOn(target,'touchmove',[893,210]);touchOn(target,'touchend');
        assert.equal(term.buffer.active.viewportY,bottom,'text swipe must not scroll a second time');
        touchOn(target,'touchstart',[898,95]);touchOn(target,'touchend');
        touchOn(target,'touchstart',[898,95]);touchOn(target,'touchcancel');
        assert.ok(releaseMouse('mousedown').stopped,'trailing compatibility mouse event');
    }
    assert.ok(surfaceClasses.has('has-scrollback'));
    assert.equal(clipboard.length,railClipboard);assert.equal(keyboardRequests,railKeyboard);assert.equal(focuses,railFocus);
    assert.ok(!term.hasSelection());
    assert.equal(focuses,1);assert.equal(keyboardRequests,1); // no focus from scroll, swipe, cancel or handle actions
    assert.ok(touch('touchstart',[0,50]));clock+=498;assert.ok(touch('touchend')); // 499 ms, already editing: no IME toggle.
    assert.equal(keyboardRequests,1);assert.equal(focuses,1);
    keyboardVisible=false;
    assert.ok(surfaceMouse('pointerdown',{pointerType:'touch'}).prevented);
    assert.ok(touch('touchstart',[0,50]));assert.ok(touch('touchend'));assert.equal(keyboardRequests,2);
    // The editor may still be focused after Android Back; another short tap requests IME again.
    touch('touchstart',[0,50]);touch('touchend');assert.equal(keyboardRequests,3);
    touch('touchstart',[10,40]);touch('touchmove',[10,43]);assert.equal(term.buffer.active.viewportY,bottom);
    assert.equal(touch('touchmove',[10,109]),true);assert.equal(term.buffer.active.viewportY,bottom-3);
    touch('touchmove',[10,82]);assert.equal(term.buffer.active.viewportY,bottom-3);
    touch('touchmove',[10,67]);assert.equal(term.buffer.active.viewportY,bottom-2);
    touch('touchend');assert.equal(timers.size,0);touch('touchstart',[25,10]);hold();touch('touchend');tap(25,10);assert.equal(clipboard.at(-1),'history');touch('touchstart',[0,10]);touch('touchstart',[0,10],[100,10]);
    const fitBefore=fit;assert.equal(touch('touchmove',[0,10],[150,10]),true);assert.equal(term.options.fontSize,24);assert.ok(fit>fitBefore);
    touch('touchmove',[0,10],[400,10]);assert.equal(term.options.fontSize,36);
    touch('touchend',[0,10]);touch('touchmove',[0,210]);touch('touchend');assert.deepEqual(saved,[36]);
    touch('touchstart',[0,0],[100,0]);touch('touchmove',[0,0],[5,0]);touch('touchcancel');assert.equal(term.options.fontSize,8);
    await output('\x1b[?1049h');assert.ok(!surfaceClasses.has('has-scrollback'));touch('touchstart',[0,20]);touch('touchmove',[0,60]);touch('touchend');
    assert.equal(wheels.length,1);assert.equal(wheels[0].deltaY,-2);await output('\x1b[?1049l');
    assert.equal(keyboardRequests,3);assert.equal(focuses,1);
    await output('\x1b[?1000h');touch('touchstart',[30,10]);assert.ok(touch('touchend'));assert.equal(keyboardRequests,4);
    assert.ok(surfaceMouse('mousedown',{pointerType:'touch'}).stopped); // no duplicate TUI clicks
    assert.deepEqual(mouseEvents.map(e=>[e.type,e.clientX,e.clientY]),[['mousedown',30,10],['mouseup',30,10]]);
    touch('touchstart',[30,10]);hold();touch('touchend');tap(600,210);assert.equal(mouseEvents.length,2);
    await output('\x1b[?1000l');assert.deepEqual(received,[]);
    // A height change at the scrollback limit trims old rows. Track surviving text,
    // and never substitute the text at the old coordinates for a discarded selection.
    await output(Array.from({length:2600},(_,i)=>'row'+i+'\r\n').join(''));
    term.scrollToBottom();touch('touchstart',[15,10]);hold();touch('touchend');const kept=term.getSelection();assert.match(kept,/^row\d+$/);
    fitTo(90,23);assert.equal(term.getSelection(),kept);
    term.scrollToTop();const copiesAtTop=clipboard.length;tap(15,10);assert.ok(!term.hasSelection());assert.equal(clipboard.length,copiesAtTop);
    touch('touchstart',[15,10]);hold();touch('touchend');assert.ok(term.hasSelection());
    fitTo(90,17);assert.ok(!term.hasSelection() && start.hidden && end.hidden);
    assert.equal(keyboardRequests,4);
    // Focus ownership and IME visibility are independent. Selection may change
    // neither, even with delayed delivery or unlabelled compatibility events.
    await output('\x1b[2J\x1b[Hfocus');term.scrollToBottom();
    for(const ime of [false,true])for(const owner of [null,editor]){
        env.document.activeElement=owner;keyboardVisible=ime;
        const requests=keyboardRequests,focusCount=focuses,copies=clipboard.length;
        for(const inside of [false,true]){
            const downTime=clock+1;nextDispatchLag=275;touch('touchstart',[15,10]);
            const timer=timers.values().next().value;assert.equal(timer.due,downTime+501);
            hold();assert.equal(term.getSelection(),'focus');touch('touchend');
            const wasFocused=env.document.activeElement;
            for(const name of ['mousedown','mouseup','click'])assert.ok(releaseMouse(name).stopped);
            assert.equal(wasFocused,owner);
            if(inside)tap(15,10);else tap(200,50);
            for(const name of ['mousedown','mouseup','click'])assert.ok(releaseMouse(name).stopped);
            assert.ok(!term.hasSelection() && start.hidden && end.hidden);
            assert.equal(controls['start-stem'].d,undefined);assert.equal(controls['end-stem'].d,undefined);
            assert.equal(env.document.activeElement,owner);assert.equal(keyboardVisible,ime);
            assert.equal(keyboardRequests,requests);assert.equal(focuses,focusCount);
        }
        assert.equal(clipboard.length,copies+1);assert.equal(clipboard.at(-1),'focus');
        touch('touchstart',[15,10]);assert.equal(env.document.activeElement,owner);
        clock+=499;touch('touchend');assert.equal(env.document.activeElement,editor);
        let expectedFocus=focusCount;if(owner!==editor)expectedFocus++;
        let expectedRequests=requests;if(!ime)expectedRequests++;
        assert.equal(focuses,expectedFocus);assert.equal(keyboardRequests,expectedRequests);
    }
    // Reopen via real tap decisions after each kind of dismissal. Repeated fits may
    // not restore handles or issue their own focus, keyboard or clipboard requests.
    for(const inside of [true,false]){
        keyboardVisible=false;const requests=keyboardRequests,copies=clipboard.length,focusCount=focuses;
        touch('touchstart',[15,10]);hold();touch('touchend');assert.equal(term.getSelection(),'focus');
        if(inside)tap(15,10);else tap(200,50);
        let expectedCopies=copies;if(inside)expectedCopies++;
        assert.equal(keyboardRequests,requests);
        for(let cycle=1;cycle<=3;cycle++){
            tap(15,10);assert.equal(keyboardRequests,requests+cycle);
            keyboardVisible=true;fitTo(90,14);keyboardVisible=false;fitTo(90,17);
            assert.ok(!term.hasSelection() && start.hidden && end.hidden);
            assert.equal(clipboard.length,expectedCopies);assert.equal(focuses,focusCount);
        }
    }
    // Use insets at the tap decision, not a snapshot from down. Do not reopen an IME
    // on behalf of a selection gesture when the system hides it mid-contact.
    const beforeChange=keyboardRequests;
    keyboardVisible=false;touch('touchstart',[15,10]);keyboardVisible=true;touch('touchend');assert.equal(keyboardRequests,beforeChange);
    touch('touchstart',[15,10]);keyboardVisible=false;touch('touchend');assert.equal(keyboardRequests,beforeChange+1);
    keyboardVisible=true;touch('touchstart',[15,10]);keyboardVisible=false;hold();touch('touchend');tap(200,50);
    assert.equal(keyboardRequests,beforeChange+1);assert.ok(!term.hasSelection());
    key('ESC','\x1b');key('TAB','\t');key('PGUP','\x1b[5~');key('PGDN','\x1b[6~');
    for(const [label,code] of cursor)key(label,'\x1b['+code);
    await output('\x1b[?1h');
    for(const [label,code] of cursor)key(label,'\x1bO'+code);
    press('CTRL');assert.deepEqual(states.at(-1),[true,false]);key('→','\x1b[1;5C');
    key('→','\x1bOC'); // Automatically released; still in application cursor mode.
    await output('\x1b[?1l');key('←','\x1b[D');
    press('ALT');key('PGDN','\x1b[6;3~');press('CTRL');key('HOME','\x1b[1;5H');
    press('ALT');press('CTRL');assert.deepEqual(states.at(-1),[true,true]);key('↑','\x1b[1;7A');
    press('CTRL');type('c','\x03');assert.deepEqual(states.at(-1),[false,false]);type('c','c');
    press('ALT');type('b','\x1bb');press('CTRL');press('ALT');type('d','\x1b\x04');
    press('CTRL');type('[','\x1b');press('CTRL');type(' ','\x00');press('CTRL');type('好','好');
    press('CTRL');press('CTRL');type('c','c');press('ALT');env.window.clearShortcutModifiers();type('b','b');
    press('CTRL');await output('\x1b[5n');assert.deepEqual(received.splice(0),['\x1b[0n']);type('d','\x04');
    type('中文','中文');const ack=new Promise(resolve=>{Birch.consumed=()=>{consumed++;resolve();};});
    env.window.deliver('/wAb');await ack;assert.deepEqual(Array.from(writes.at(-1)),[255,0,27]);assert.equal(consumed,1);
    assert.ok(focuses>0);console.log('Tap selection to copy, outside tap cancels, both IME states, no copy on release/cancel, handle hit areas, Unicode/scrollback, TUI taps, zoom and shortcuts: PASS');
}
test().catch(e=>{console.error(e);process.exitCode=1;}).finally(()=>term.dispose());
