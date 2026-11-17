// Run the APK's actual DOM selection renderer without a WebView. Only layout, row
// painting and DOM node storage are stand-ins; neither selection nor resize is mocked.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const bundle = fs.readFileSync(require.resolve('../app/src/main/assets/term/xterm.js'), 'utf8');
const entry = 'var s={};return(()=>{var e=s;';
assert.equal(bundle.split(entry).length, 2, 'expected xterm 6.0.0 module loader');
const context = {exports: {}, module: {}, setTimeout, clearTimeout};
vm.runInNewContext(bundle.replace(entry, 'return i;' + entry), context);
const load = context.module.exports;
const {DomRenderer} = load(3955);
const {DomRendererRowFactory} = load(1433);
const {createSelectionRenderModel} = load(2274);

class NodeStub {
    constructor() { this.children = []; this.style = {}; }
    appendChild(child) { this.children.push(child); }
    replaceChildren() { this.children = []; }
}
const terminal = {buffers: {active: {ydisp: 0}}, rows: 24};
const renderer = Object.assign(Object.create(DomRenderer.prototype), {
    _terminal: terminal, _bufferService: {cols: 80, rows: terminal.rows},
    _rowFactory: Object.create(DomRendererRowFactory.prototype),
    _selectionRenderModel: createSelectionRenderModel(),
    _selectionContainer: new NodeStub(),
    _document: {createElement: () => new NodeStub(), createDocumentFragment: () => new NodeStub()},
    dimensions: {css: {canvas: {width: 800}, cell: {width: 10, height: 20}}},
    _refreshRowElements() {}, _updateDimensions() {}, renderRows() {}
});
function resize(rows) {
    terminal.rows = rows;
    renderer._bufferService.rows = rows;
    renderer.handleResize(80, rows);
}
function checkActive(x, y) {
    const [fragment] = renderer._selectionContainer.children;
    assert.ok(fragment, 'active range should paint an overlay');
    assert.equal(fragment.children[0].style.left, x * 10 + 'px');
    assert.equal(fragment.children[0].style.top, y * 20 + 'px');
    assert.equal(renderer._rowFactory._isCellInSelection(x, y), true);
}
function checkCleared(x, y) {
    assert.equal(renderer._selectionContainer.children.length, 0, 'a dismissed highlight was painted again');
    assert.equal(renderer._rowFactory._isCellInSelection(x, y), false, 'old cells still have selection colors');
}
for (const [x, y] of [[2, 3], [14, 1]]) {
    renderer.handleSelectionChanged([x, y], [x + 4, y], false);
    checkActive(x, y);
    resize(16);
    checkActive(x, y);
    renderer.handleSelectionChanged(undefined, undefined, false);
    checkCleared(x, y);
    for (const rows of [24, 14, 24, 14, 24]) {
        resize(rows);
        checkCleared(x, y);
    }
}
console.log('DOM renderer: active range survives resize; dismissed overlay and cell colors stay cleared: PASS');
