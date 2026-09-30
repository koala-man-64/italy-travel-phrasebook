'use strict';
// Executable contract model, NOT application code or a full JSON Schema engine.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const USER_LIMIT = 524288, TRIP_LIMIT = 1048576, WIRE_LIMIT = 2097152;
const MAX_REV = 9223372036854775807n;
const schemas = JSON.parse(fs.readFileSync(path.join(__dirname,'contracts.schema.json'),'utf8'));
class ContractError extends Error { constructor(code) {super(code);this.code=code;} }
const fail = code => {throw new ContractError(code);};
const need = (ok,code='INVALID_DATA') => {if(!ok) fail(code);};
const hash = raw => crypto.createHash('sha256').update(raw,'utf8').digest('hex');
const bytes = raw => Buffer.byteLength(raw,'utf8');
const sorted = x => Array.isArray(x)?x.map(sorted):x&&typeof x==='object'?Object.fromEntries(Object.keys(x).sort().map(k=>[k,sorted(x[k])])):x;
const same = (a,b) => JSON.stringify(sorted(a))===JSON.stringify(sorted(b));
const clone = value => JSON.parse(JSON.stringify(value));

function strictJson(input,limit=USER_LIMIT) {
  need(Buffer.isBuffer(input)||typeof input==='string','MALFORMED');
  if(typeof input==='string')need(input.isWellFormed(),'MALFORMED');
  const buffer=Buffer.isBuffer(input)?input:Buffer.from(input,'utf8');
  need(buffer.length<=limit,'FILE_LIMIT');
  let raw;try{raw=new TextDecoder('utf-8',{fatal:true,ignoreBOM:true}).decode(buffer);}catch{fail('INVALID_UTF8');}
  // Scan duplicate keys before JSON.parse loses them. JSON.parse validates grammar/escapes.
  let i=0;
  function white(){while(/[\x20\t\r\n]/.test(raw[i]||'!'))i++;}
  function string(){const start=i++;while(i<raw.length){const c=raw[i++];if(c==='\\'){i++;continue;}if(c==='"'){let s;try{s=JSON.parse(raw.slice(start,i));}catch{fail('MALFORMED');}need(!/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/u.test(s),'MALFORMED');return s;}}fail('MALFORMED');}
  function value(depth){need(depth<=24,'DEPTH_LIMIT');white();const c=raw[i];
    if(c==='"'){string();return;}
    if(c==='{'||c==='['){const end=c==='{'?'}':']',keys=new Set();i++;white();if(raw[i]===end){i++;return;}
      for(;;){white();if(c==='{'){need(raw[i]==='"','MALFORMED');const key=string();need(!keys.has(key),'DUPLICATE_KEY');keys.add(key);white();need(raw[i++]===':','MALFORMED');}value(depth+1);white();if(raw[i]===end){i++;return;}need(raw[i++ ]===',','MALFORMED');}
    }
    const start=i;while(i<raw.length&&!/[\s,\]}]/.test(raw[i]))i++;need(i>start,'MALFORMED');
  }
  value(0);white();need(i===raw.length,'MALFORMED');
  try{return JSON.parse(raw);}catch{fail('MALFORMED');}
}

const supported=new Set(['$schema','$defs','$ref','title','type','properties','required','additionalProperties','items','minItems','maxItems','uniqueItems','minimum','maximum','minLength','maxLength','pattern','enum','const','oneOf','anyOf']);
function auditSchema(s){for(const k of Object.keys(s))need(supported.has(k),'UNSUPPORTED_SCHEMA_KEYWORD');
  if(s.$ref)need(!!schemas.$defs[s.$ref.split('/').at(-1)],'BAD_SCHEMA_REF');
  for(const group of ['properties','$defs'])for(const child of Object.values(s[group]||{}))auditSchema(child);
  if(s.items)auditSchema(s.items);for(const group of ['oneOf','anyOf'])for(const child of s[group]||[])auditSchema(child);
}
function shape(value,s){
  if(s.$ref)return shape(value,schemas.$defs[s.$ref.split('/').at(-1)]);
  for(const keyword of ['oneOf','anyOf'])if(s[keyword]){const n=s[keyword].filter(candidate=>{try{shape(value,candidate);return true;}catch(e){if(!(e instanceof ContractError))throw e;return false;}}).length;need(keyword==='oneOf'?n===1:n>=1);}
  if('const'in s)need(same(value,s.const));if(s.enum)need(s.enum.some(x=>same(x,value)));
  if(s.type){const ok=s.type==='null'?value===null:s.type==='array'?Array.isArray(value):s.type==='object'?value!==null&&typeof value==='object'&&!Array.isArray(value):s.type==='integer'?Number.isSafeInteger(value):typeof value===s.type;need(ok);}
  if(s.type==='object'){for(const key of s.required||[])need(Object.hasOwn(value,key));for(const key of Object.keys(value)){need(Object.hasOwn(s.properties,key)||s.additionalProperties!==false);if(s.properties[key])shape(value[key],s.properties[key]);}}
  if(s.type==='array'){need(value.length>=(s.minItems||0)&&value.length<=(s.maxItems??Infinity));for(const x of value)shape(x,s.items);if(s.uniqueItems)need(new Set(value.map(JSON.stringify)).size===value.length);}
  if(s.type==='string'){need(value.isWellFormed());const length=[...value].length;need(length>=(s.minLength||0)&&length<=(s.maxLength??Infinity));if(s.pattern)need(new RegExp(s.pattern,'u').test(value));need(!/[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]/.test(value));}
  if(s.type==='integer')need(value>=(s.minimum??-Infinity)&&value<=(s.maximum??Infinity));
}
const unique=(xs,key)=>need(new Set(xs.map(x=>x[key])).size===xs.length,'DUPLICATE_ID');
function revision(r){need(typeof r==='string'&&/^(0|[1-9][0-9]{0,18})$/.test(r),'INVALID_REVISION');need(BigInt(r)<=MAX_REV,'INVALID_REVISION');return BigInt(r);}
function nextRevision(r){const n=revision(r);need(n<MAX_REV,'REVISION_EXHAUSTED');return String(n+1n);}
function validDate(d){need(/^20\d{2}-\d{2}-\d{2}$/.test(d)&&!Number.isNaN(Date.parse(d))&&new Date(d).toISOString().slice(0,10)===d,'INVALID_DATE');}
function validUtc(d){need(/^20\d{2}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(d)&&!Number.isNaN(Date.parse(d))&&new Date(d).toISOString()===d.replace('Z','.000Z'),'INVALID_DATE');}
const romeFormatter=new Intl.DateTimeFormat('en-CA',{timeZone:'Europe/Rome',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hourCycle:'h23'});
function rome(instant){const p=Object.fromEntries(romeFormatter.formatToParts(new Date(instant)).map(x=>[x.type,x.value]));return {date:`${p.year}-${p.month}-${p.day}`,time:`${p.hour}:${p.minute}`};}
function exactRome(date,time,offset){need(/^([01]\d|2[0-3]):[0-5]\d$/.test(time),'INVALID_TIME');const iso=`${date}T${time}:00${offset}`;const local=rome(iso);need(local.date===date&&local.time===time,'ROME_OFFSET_MISMATCH');return new Date(iso).toISOString();}
function today(trip,instant,manualDayId=null){validate('TripContent',trip);validUtc(instant);const date=rome(instant).date;const sorted=[...trip.days].sort((a,b)=>a.date.localeCompare(b.date));const match=sorted.find(d=>d.date===date);let state,day;
  if(date<sorted[0].date){state='before';day=sorted[0];}else if(date>sorted.at(-1).date){state='after';day=sorted.at(-1);}else if(match){state='during';day=match;}else{state='gap';day=sorted.find(d=>d.date>date);}
  if(manualDayId!==null){const selected=trip.days.find(d=>d.id===manualDayId);need(selected,'REFERENCE_MISMATCH');return {state,dayId:selected.id,selection:'manual'};}
  return {state,dayId:day.id,selection:'automatic'};
}
function validate(name,v,context={}){
  if(name==='AudioRequestV1')fail('AMBIGUOUS_AUDIO_SCHEMA');
  const limit=name==='TripContent'?TRIP_LIMIT:name==='RecoveryImage'?4*USER_LIMIT+8192:name.startsWith('Json')?WIRE_LIMIT:USER_LIMIT;
  need(bytes(JSON.stringify(v))<=limit,'FILE_LIMIT');need(schemas.$defs[name],'UNKNOWN_CONTRACT');
  if(['UserData','TripContent','Manifest'].includes(name)&&v?.schemaVersion!==1)fail('UNSUPPORTED_SCHEMA');shape(v,schemas.$defs[name]);
  if(name==='TripContent'){
    for(const table of ['days','events','places','phrases'])unique(v[table],'id');unique(v.days,'date');unique(v.days,'order');
    const events=new Map(v.events.map(e=>[e.id,e])),days=new Map(v.days.map(d=>[d.id,d]));const places=new Set(v.places.map(p=>p.id)),phrases=new Set(v.phrases.map(p=>p.id));
    for(const d of v.days){validDate(d.date);const orders=[];for(const eid of d.eventIds){const e=events.get(eid);need(e&&e.dayId===d.id,'REFERENCE_MISMATCH');orders.push(e.order);}need(new Set(orders).size===orders.length,'DUPLICATE_ORDER');}
    for(const e of v.events){const d=days.get(e.dayId);need(d&&d.eventIds.includes(e.id),'REFERENCE_MISMATCH');for(const p of e.placeIds)need(places.has(p),'REFERENCE_MISMATCH');for(const p of e.phraseIds)need(phrases.has(p),'REFERENCE_MISMATCH');if(e.exactLocal)exactRome(d.date,e.exactLocal.time,e.exactLocal.offset);}
    for(const p of v.places)validDate(p.checkedOn);
  }
  if(name==='UserData'){
    revision(v.revision);validUtc(v.updatedAtUtc);unique(v.saved,'id');unique(v.progress,'phraseId');for(const s of v.saved)validUtc(s.createdAtUtc);for(const p of v.progress)validUtc(p.lastReviewedAtUtc);
    if(v.legacyBuilderRaw!==null){need(bytes(v.legacyBuilderRaw)<=8192,'FILE_LIMIT');const old=strictJson(v.legacyBuilderRaw,8192);need(old&&typeof old==='object'&&!Array.isArray(old),'INVALID_LEGACY_STATE');}
    if(v.wallet)revision(v.wallet.revision);need(v.wallet!==null||v.attachments.length===0,'REFERENCE_MISMATCH');
    const relationKeys=v.attachments.map(a=>[a.tripId,a.eventId,a.documentId].join('/'));need(new Set(relationKeys).size===relationKeys.length,'DUPLICATE_ID');
    if(v.wallet){need(context.native&&context.trip,'NATIVE_CONTEXT_REQUIRED');const n=context.native;need(n.generationId===v.wallet.generationId&&n.revision===v.wallet.revision,'REVISION_CONFLICT');const documents=new Set(n.documents.map(d=>d.id));need(documents.size===n.documents.length,'DUPLICATE_ID');for(const a of v.attachments)need(a.tripId===context.trip.tripId&&context.trip.events.some(e=>e.id===a.eventId)&&documents.has(a.documentId),'REFERENCE_MISMATCH');}
  }
  if(name==='Manifest'){revision(v.active.revision);if(v.previous){revision(v.previous.revision);need(v.active.slot!==v.previous.slot&&v.active.generationId!==v.previous.generationId&&revision(v.active.revision)>revision(v.previous.revision),'AMBIGUOUS_MANIFEST');}}
  if(name==='Receipt'||name==='Journal'){
    const stores=name==='Receipt'?[v]:[v.web,v.native];for(const s of stores){need(s.candidate.revision===nextRevision(s.prior.revision)&&s.prior.generationId!==s.candidate.generationId,'REVISION_CONFLICT');}
  }
  if(name==='AudioRequestHistorical18'||name==='AudioRequestSelected19'){
    need(JSON.stringify(v).length<=32768,'FILE_LIMIT');
    const fields=v.op==='present'?['en','it']:['translate','speakText'].includes(v.op)?['text']:[];
    for(const field of fields)need(v[field].replace(/^[\x00-\x20]+|[\x00-\x20]+$/g,'').length>0&&v[field].length<=2000,'INVALID_DATA');
  }
  if(name==='AudioStateSelected19'){
    need(JSON.stringify(v).length<=32768,'FILE_LIMIT');need(v.requestId<=v.ack,'INVALID_CORRELATION');
    for(const field of ['text','translation'])need(v[field].length<=2000,'INVALID_DATA');
  }
  if(name==='JsonRequest'&&v.op==='export'||name==='JsonResult'&&v.op==='import'&&v.status==='ok'){
    const payload=strictJson(v.payload);need(payload.wallet===null&&payload.attachments?.length===0,'ARCHIVE_REQUIRED');validate('UserData',payload);
  }
  if(name==='JsonResult'&&((v.op==='import'&&v.status!=='ok')||(v.op==='cancel'&&v.status==='error')))need(v.externalEffect==='none');
  return v;
}

function recover(image,context={}){
  const out=(state,slot=null,reason=null)=>({state,slot,reason,writable:state==='READY'||state==='NEW'});
  try{validate('RecoveryImage',image);}catch{return out('RECOVERY_REQUIRED',null,'INVALID_IMAGE');}
  if(image.manifest===null)return image.slots.a===null&&image.slots.b===null?out('NEW'):out('RECOVERY_REQUIRED',null,'MISSING_MANIFEST');
  // Unknown-version candidates are preserved; never overwrite them even when inactive.
  for(const raw of Object.values(image.slots))if(raw!==null){try{const v=strictJson(raw);if(v.schemaVersion!==1)return out('RECOVERY_REQUIRED',null,'UNSUPPORTED_SCHEMA');}catch{}}
  let manifest;try{manifest=strictJson(image.manifest,2048);validate('Manifest',manifest);}catch{return out('RECOVERY_REQUIRED',null,'INVALID_MANIFEST');}
  const valid=p=>{if(!p)return false;try{const raw=image.slots[p.slot];if(raw===null||hash(raw)!==p.sha256)return false;const data=strictJson(raw);validate('UserData',data,context);return data.revision===p.revision&&data.generationId===p.generationId;}catch{return false;}};
  if(valid(manifest.active))return out('READY',manifest.active.slot);
  if(valid(manifest.previous))return out('FALLBACK',manifest.previous.slot,'ACTIVE_INVALID');
  return out('RECOVERY_REQUIRED',null,'NO_VALID_COMMITTED_SLOT');
}
function pointer(slot,data,raw){return {slot,revision:data.revision,generationId:data.generationId,sha256:hash(raw)};}
function saveModel(image,candidate,expectedRevision,stopAfter='verified'){
  const result=clone(image),r=recover(result);need(r.state==='READY','RECOVERY_REQUIRED');const manifest=strictJson(result.manifest,2048);need(manifest.active.revision===expectedRevision,'REVISION_CONFLICT');validate('UserData',candidate);need(candidate.revision===nextRevision(expectedRevision)&&candidate.generationId!==manifest.active.generationId,'REVISION_CONFLICT');
  if(stopAfter==='before-slot')return result;
  const slot=r.slot==='a'?'b':'a',raw=JSON.stringify(candidate);result.slots[slot]=raw;
  if(stopAfter==='after-slot'||stopAfter==='after-slot-readback')return result;
  need(result.slots[slot]===raw);validate('UserData',strictJson(result.slots[slot]));
  result.manifest=JSON.stringify({format:'itguide-user-manifest',schemaVersion:1,active:pointer(slot,candidate,raw),previous:manifest.active});
  if(stopAfter==='after-manifest')return result;
  need(recover(result).state==='READY');return result;
}
function prepareImport(payload,current,expectedRevision,generationId,now,confirmed){need(confirmed===true,'CONFIRMATION_REQUIRED');need(current.wallet===null,'ARCHIVE_REQUIRED');need(current.revision===expectedRevision,'REVISION_CONFLICT');const incoming=strictJson(payload);need(incoming.wallet===null&&incoming.attachments?.length===0,'ARCHIVE_REQUIRED');validate('UserData',incoming);const candidate={...incoming,revision:nextRevision(current.revision),generationId,updatedAtUtc:now};need(generationId!==current.generationId,'REVISION_CONFLICT');validate('UserData',candidate);return candidate;}
function migrateLegacy(rawKeys,existing,generationId,now){
  if(existing!==null){validate('UserData',existing);return clone(existing);}
  const slow=rawKeys['itguide.slow']===undefined?false:strictJson(rawKeys['itguide.slow'],32);
  const tab=rawKeys['itguide.tab']===undefined?'builder':strictJson(rawKeys['itguide.tab'],64);
  need(typeof slow==='boolean'&&['phrases','builder','vocab','itinerary'].includes(tab),'LEGACY_RECOVERY_REQUIRED');
  const candidate={format:'itguide-user-data',schemaVersion:1,revision:'0',generationId,updatedAtUtc:now,preferences:{slow,tab},legacyBuilderRaw:rawKeys['itguide.builder']??null,saved:[],progress:[],wallet:null,attachments:[]};
  validate('UserData',candidate);return candidate;
}
function initializeModel(image,candidate){need(recover(image).state==='NEW','RECOVERY_REQUIRED');validate('UserData',candidate);need(candidate.revision==='0','REVISION_CONFLICT');const raw=JSON.stringify(candidate);return {slots:{a:raw,b:null},manifest:JSON.stringify({format:'itguide-user-manifest',schemaVersion:1,active:pointer('a',candidate,raw),previous:null})};}

function matchReceipt(journal,receipt,store,phase){validate('Journal',journal);validate('Receipt',receipt);need(receipt.transactionId===journal.transactionId&&receipt.operation===journal.operation&&receipt.store===store&&receipt.phase===phase&&same(receipt.prior,journal[store].prior)&&same(receipt.candidate,journal[store].candidate),'RECEIPT_MISMATCH');return true;}
function coordinateRecovery(journal,{priorValid,candidateValid}){validate('Journal',journal);if(journal.phase==='BEGIN')return priorValid?'ROLL_BACK_BOTH':'RECOVERY_REQUIRED';if(candidateValid)return 'ROLL_FORWARD_BOTH';return priorValid?'ROLL_BACK_BOTH':'RECOVERY_REQUIRED';}
function deletionPlan(user,native,trip,documentId,expectedWeb,expectedNative){validate('UserData',user,{native,trip});need(user.revision===expectedWeb&&native.revision===expectedNative,'REVISION_CONFLICT');need(native.documents.some(d=>d.id===documentId),'REFERENCE_MISMATCH');return {operation:'delete',webAttachments:user.attachments.filter(a=>a.documentId!==documentId),nativeDocumentIds:native.documents.filter(d=>d.id!==documentId).map(d=>d.id),physicalDelete:'after-committed-checkpoint-and-no-leases'};}

function speechCapabilities(snapshot,{source='OfflineConversation-v1',protocolBaseline='selected-1.9',fresh=true,lastSequence=0}={}){
  let knownShape=protocolBaseline==='historical-1.8';
  if(protocolBaseline==='selected-1.9'&&snapshot!==undefined){try{validate('AudioStateSelected19',snapshot);knownShape=true;}catch(e){if(!(e instanceof ContractError))throw e;}}
  const usable=knownShape&&source==='OfflineConversation-v1'&&fresh&&snapshot?.v===1&&snapshot.event==='state'&&Number.isSafeInteger(snapshot.seq)&&snapshot.seq>lastSequence;
  const report=value=>!usable||typeof value!=='boolean'?'unknown':value?'reported-ready':'reported-unavailable';
  const result={schemaVersion:1,source,protocolBaseline,freshness:usable?'current':fresh?'unknown':'stale',sequence:usable?snapshot.seq:null,
    voices:{it:report(snapshot?.voices?.it),en:report(snapshot?.voices?.en)},recognitionModels:{it:report(snapshot?.models?.it),en:report(snapshot?.models?.en)},translationModels:report(snapshot?.translationReady),microphonePermission:'unknown',offlineDeviceProof:'unverified'};validate('SpeechCapabilities',result);return result;
}
function audio19Correlation(snapshot,context){
  validate('AudioStateSelected19',snapshot);
  const {documentId,currentDocumentId,lastSequence,epoch,resetId=0,pending,currentExchange,currentRevision}=context;
  const ignored={decision:'ignore',observeReadiness:false,invalidatesPending:false,clearAcknowledged:false,correlated:false};
  if(documentId!==currentDocumentId||snapshot.seq<=lastSequence||(epoch!==null&&snapshot.sessionEpoch<epoch))return ignored;
  const changed=epoch!==null&&snapshot.sessionEpoch!==epoch;
  const base={observeReadiness:true,invalidatesPending:changed,clearAcknowledged:resetId>0&&snapshot.ack>=resetId,correlated:false};
  if(changed&&!resetId)return {...base,decision:'invalidate-pending'};
  if(resetId)return {...base,decision:base.clearAcknowledged?'clear-acknowledged':'await-clear'};
  const correlated=!!pending&&snapshot.requestId===pending.id&&pending.exchange===currentExchange&&pending.revision===currentRevision;
  return {...base,decision:correlated?'matching-operation':'state-observation',correlated};
}
class TransferSession {
  constructor(sessionId){this.sessionId=sessionId;this.pending=null;this.seen=new Set();}
  begin(request){validate('JsonRequest',request);need(request.sessionId===this.sessionId,'STALE_SESSION');need(!this.seen.has(request.requestId),'DUPLICATE_REQUEST');need(this.seen.size<1000,'SESSION_LIMIT');
    if(request.op==='cancel'){this.seen.add(request.requestId);return this.pending?.requestId===request.targetRequestId?'requested':'already-terminal';}
    need(!this.pending,'BUSY');this.seen.add(request.requestId);this.pending=request;return 'started';}
  finish(result){validate('JsonResult',result);if(!this.pending||result.sessionId!==this.sessionId||result.requestId!==this.pending.requestId)return 'ignored';need(result.op===this.pending.op,'RESPONSE_MISMATCH');this.pending=null;return 'accepted';}
}
auditSchema(schemas);
module.exports={strictJson,validate,hash,bytes,nextRevision,exactRome,today,recover,saveModel,prepareImport,migrateLegacy,initializeModel,matchReceipt,coordinateRecovery,deletionPlan,speechCapabilities,audio19Correlation,TransferSession,ContractError,USER_LIMIT,TRIP_LIMIT,WIRE_LIMIT};
if(require.main===module){try{const [, ,name,file]=process.argv;need(name&&file,'USAGE: node validate.cjs ContractName file.json');const limit=name==='TripContent'?TRIP_LIMIT:name==='RecoveryImage'?4*USER_LIMIT+8192:WIRE_LIMIT;const value=strictJson(fs.readFileSync(file),limit);validate(name,value);console.log(`ACCEPT ${name}`);}catch(e){console.error(`REJECT ${e.code||e.message}`);process.exitCode=1;}}
