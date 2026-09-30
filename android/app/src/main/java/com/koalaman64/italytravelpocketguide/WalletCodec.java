package com.koalaman64.italytravelpocketguide;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;
import java.math.BigDecimal;
import static com.koalaman64.italytravelpocketguide.WalletTypes.*;

/** Closed persistence grammar using the existing bounded duplicate-key parser. */
final class WalletCodec {
    static Map<String,Object> obj(Object... fields){return JsonTransferJson.object(fields);}
    static byte[] encode(Object value,int cap) throws Exception{return utf8(JsonTransferJson.encode(value),cap);}
    static byte[] utf8(String text,int cap)throws WalletFailure{
        if(text==null||text.length()>cap)throw new WalletFailure(Code.STORAGE_LIMIT);int count=0;
        for(int i=0;i<text.length();){int cp=text.codePointAt(i);if(cp>=0xd800&&cp<=0xdfff)throw new WalletFailure(Code.INVALID_UTF8);count+=cp<128?1:cp<2048?2:cp<65536?3:4;if(count>cap)throw new WalletFailure(Code.STORAGE_LIMIT);i+=Character.charCount(cp);}
        return text.getBytes(StandardCharsets.UTF_8);
    }
    static String decode(byte[] bytes,int cap) throws Exception {
        if(bytes.length>cap)throw new WalletFailure(Code.STORAGE_LIMIT);
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }
    static Map<String,Object> parse(byte[] bytes,int cap)throws Exception{return map(JsonTransferJson.parse(decode(bytes,cap),cap));}
    @SuppressWarnings("unchecked") static Map<String,Object> map(Object o)throws WalletFailure {if(!(o instanceof Map))throw new WalletFailure(Code.INVALID_DATA);return (Map<String,Object>)o;}
    static void keys(Map<String,Object> o,String... fields)throws WalletFailure{if(!o.keySet().equals(new HashSet<>(Arrays.asList(fields))))throw new WalletFailure(Code.INVALID_DATA);}
    static String text(Object o)throws WalletFailure{if(!(o instanceof String))throw new WalletFailure(Code.INVALID_DATA);return (String)o;}
    static long number(Object o)throws WalletFailure{try{if(!(o instanceof BigDecimal))throw new ArithmeticException();return ((BigDecimal)o).longValueExact();}catch(ArithmeticException e){throw new WalletFailure(Code.INVALID_DATA);}}
    static Map<String,Object> identity(Identity v){return v==null?null:obj("generationId",v.generationId,"revision",v.revision,"schemaVersion",1,"sha256",v.sha256);}
    static Identity identity(Object o)throws Exception{if(o==null)return null;Map<String,Object> m=map(o);keys(m,"generationId","revision","schemaVersion","sha256");if(number(m.get("schemaVersion"))!=1)throw new WalletFailure(Code.UNSUPPORTED_VERSION);return new Identity(text(m.get("generationId")),text(m.get("revision")),text(m.get("sha256")));}
    static Map<String,Object> pair(Pair p){return obj("web",identity(p.web),"native",identity(p.nativeIdentity));}
    static Pair pair(Object o)throws Exception{Map<String,Object> m=map(o);keys(m,"web","native");return new Pair(identity(m.get("web")),identity(m.get("native")));}
    static Map<String,Object> change(Change c){return obj("prior",identity(c.prior),"candidate",identity(c.candidate));}
    static Change change(Object o)throws Exception{Map<String,Object> m=map(o);keys(m,"prior","candidate");return new Change(identity(m.get("prior")),identity(m.get("candidate")));}
    static String operation(Operation op){return op.name().toLowerCase(java.util.Locale.ROOT).replace('_','-');}
    static Operation operation(Object raw)throws Exception{String value=text(raw);Operation op=Operation.valueOf(value.toUpperCase(java.util.Locale.ROOT).replace('-','_'));if(!operation(op).equals(value))throw new WalletFailure(Code.INVALID_DATA);return op;}
    static Map<String,Object> journal(Journal j){return obj("v",1,"transactionId",j.transactionId,"operation",operation(j.operation),"phase",j.phase.name(),"sequence",j.sequence,"web",change(j.web),"native",change(j.nativeChange),"webEscrowHash",j.webEscrowHash,"planHash",j.planHash);}
    static Journal journal(byte[] b)throws Exception{Map<String,Object> m=parse(b,JOURNAL_BYTES);keys(m,"v","transactionId","operation","phase","sequence","web","native","webEscrowHash","planHash");if(number(m.get("v"))!=1)throw new WalletFailure(Code.UNSUPPORTED_VERSION);return new Journal(text(m.get("transactionId")),operation(m.get("operation")),Phase.valueOf(text(m.get("phase"))),text(m.get("sequence")),change(m.get("web")),change(m.get("native")),m.get("webEscrowHash")==null?null:text(m.get("webEscrowHash")),m.get("planHash")==null?null:text(m.get("planHash")));}
    static Map<String,Object> checkpoint(Checkpoint c){return obj("v",1,"sequence",c.sequence,"pair",pair(c.pair),"transactionId",c.transactionId,"outcome",c.outcome.name());}
    static Checkpoint checkpoint(byte[] b)throws Exception{Map<String,Object> m=parse(b,JOURNAL_BYTES);keys(m,"v","sequence","pair","transactionId","outcome");if(number(m.get("v"))!=1)throw new WalletFailure(Code.UNSUPPORTED_VERSION);return new Checkpoint(text(m.get("sequence")),pair(m.get("pair")),m.get("transactionId")==null?null:text(m.get("transactionId")),Outcome.valueOf(text(m.get("outcome"))));}
    static Map<String,Object> decision(RecoveryDecision d){return obj("transactionId",d.transactionId,"decision",d.decision.name(),"pair",pair(d.pair),"reason",d.reason,"sequence",d.sequence);}
    static RecoveryDecision decision(byte[] b)throws Exception{Map<String,Object> m=parse(b,JOURNAL_BYTES);keys(m,"transactionId","decision","pair","reason","sequence");return new RecoveryDecision(text(m.get("transactionId")),Decision.valueOf(text(m.get("decision"))),pair(m.get("pair")),text(m.get("reason")),text(m.get("sequence")));}
}
