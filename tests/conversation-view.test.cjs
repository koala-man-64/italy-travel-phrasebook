const {test}=require('node:test');
const assert=require('node:assert/strict');
const {paginateText,replacePage}=require('../conversation-view.js');

test('pagination retains all whitespace, long words and graphemes with contiguous offsets',()=>{
  const text=('Italian café e\u0301 👨‍👩‍👧‍👦\n\n'+'x'.repeat(100)).repeat(15);
  const pages=paginateText(text,s=>Array.from(new Intl.Segmenter(undefined,{granularity:'grapheme'}).segment(s)).length<=20);
  assert.equal(pages.map(p=>p.text).join(''),text);
  pages.forEach((p,i)=>{assert.equal(p.start,i?pages[i-1].end:0);assert.equal(p.text,text.slice(p.start,p.end));});
  assert.ok(pages.every(p=>!p.text.startsWith('\u200d')&&!p.text.endsWith('\u200d')&&!p.text.startsWith('\u0301')));
});
test('empty input and a one-grapheme viewport terminate without dropping content',()=>{
  assert.deepEqual(paginateText('',()=>true),[{start:0,end:0,text:''}]);
  assert.equal(paginateText('abc',s=>s.length<=1).length,3);
});
test('editing an interior page preserves prefix and suffix and absolute selection',()=>{
  assert.deepEqual(replacePage('before-middle-after',{start:7,end:13},'NEW',1,3),{text:'before-NEW-after',start:8,end:10});
});
test('editing respects the total message limit and preserves text on other pages',()=>{
  const result=replacePage('a'.repeat(1990),{start:10,end:20},'b'.repeat(100),100,100);
  assert.equal(result.text.length,2000);
  assert.equal(result.text.slice(0,10),'a'.repeat(10));
  assert.equal(result.text.slice(30),'a'.repeat(1970));
  assert.equal(result.start,30);
});
test('limit truncation never leaves an orphan surrogate',()=>{
  const result=replacePage('ab',{start:1,end:2},'🙂',2,2,2);
  assert.equal(result.text,'a');
});
