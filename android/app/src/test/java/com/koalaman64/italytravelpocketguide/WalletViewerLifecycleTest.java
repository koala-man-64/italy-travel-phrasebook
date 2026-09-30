package com.koalaman64.italytravelpocketguide;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import static org.junit.Assert.*;
import static com.koalaman64.italytravelpocketguide.WalletTypes.*;

public class WalletViewerLifecycleTest {
    static final class Port implements WalletViewerLifecycle.Port<String,String,String> {
        final CompletableFuture<Result<String>> pin=new CompletableFuture<>(),file=new CompletableFuture<>(),info=new CompletableFuture<>();
        final CompletableFuture<Result<Void>> retired=new CompletableFuture<>(),released=new CompletableFuture<>();
        final List<String> releasedPins=new ArrayList<>();int opens,retires,shows,hides,resolves;
        Runnable shown=()->{},removed=()->{};
        public CompletionStage<Result<String>> acquire(){return pin;}
        public CompletionStage<Result<String>> resolve(String l){resolves++;return file;}
        public CompletionStage<Result<String>> open(String f){opens++;return info;}
        public CompletionStage<Result<Void>> retire(){retires++;return retired;}
        public CompletionStage<Result<Void>> release(String l){releasedPins.add(l);return released;}
        public void show(String i){shows++;shown.run();}
        public void hide(){hides++;removed.run();}
        void start(){pin.complete(Result.ok("pin"));file.complete(Result.ok("file"));}
    }
    @Test public void failedBindReleasesOnlyAfterVerifiedRetirement(){
        WalletViewerLifecycle<String,String,String> host=new WalletViewerLifecycle<>(Runnable::run);Port p=new Port();List<Code> failures=new ArrayList<>();
        host.open(p,failures::add);p.start();p.info.complete(Result.failed(Code.UNSUPPORTED));
        assertEquals(1,p.retires);assertTrue(p.releasedPins.isEmpty());assertTrue(host.busy());
        p.retired.complete(Result.ok(null));assertEquals(List.of("pin"),p.releasedPins);assertTrue(host.busy());
        p.released.complete(Result.ok(null));assertFalse(host.busy());assertEquals(List.of(Code.UNSUPPORTED),failures);
        Port retry=new Port();host.open(retry,failures::add);retry.start();retry.info.complete(Result.ok("info"));assertEquals(1,retry.shows);
    }
    @Test public void closeDuringLeaseAcquisitionReleasesLatePinWithoutOpening(){
        WalletViewerLifecycle<String,String,String> host=new WalletViewerLifecycle<>(Runnable::run);Port p=new Port();host.open(p,c->fail());host.close();
        p.pin.complete(Result.ok("late-pin"));assertEquals(List.of("late-pin"),p.releasedPins);assertEquals(0,p.resolves);assertEquals(0,p.retires);
        p.released.complete(Result.ok(null));assertFalse(host.busy());
    }
    @Test public void closeDuringFileResolutionNeverStartsRenderer(){
        WalletViewerLifecycle<String,String,String> host=new WalletViewerLifecycle<>(Runnable::run);Port p=new Port();host.open(p,c->fail());p.pin.complete(Result.ok("pin"));host.close();
        p.file.complete(Result.ok("late-file"));assertEquals(0,p.opens);assertEquals(List.of("pin"),p.releasedPins);
    }
    @Test public void closeDuringOpenWaitsForLateCallbackAndRetirement(){
        WalletViewerLifecycle<String,String,String> host=new WalletViewerLifecycle<>(Runnable::run);Port p=new Port();host.open(p,c->fail());p.start();host.close();
        assertEquals(0,p.retires);p.info.complete(Result.ok("late-info"));assertEquals(0,p.shows);assertEquals(1,p.retires);assertTrue(p.releasedPins.isEmpty());
        p.retired.complete(Result.ok(null));p.released.complete(Result.ok(null));assertFalse(host.busy());
    }
    @Test public void timeoutWithoutRetirementProofKeepsPinAndBlocksReplacement(){
        WalletViewerLifecycle<String,String,String> host=new WalletViewerLifecycle<>(Runnable::run);Port p=new Port();host.open(p,c->{});p.start();
        p.info.complete(Result.failed(Code.RENDERER_TIMEOUT));p.retired.complete(Result.failed(Code.RENDERER_DIED));host.close();
        assertTrue(p.releasedPins.isEmpty());assertTrue(host.busy());List<Code> errors=new ArrayList<>();host.open(new Port(),errors::add);assertEquals(List.of(Code.BUSY),errors);
    }
    @Test public void repeatedCloseAndOldCompletionsCannotReleaseNewAttempt(){
        WalletViewerLifecycle<String,String,String> host=new WalletViewerLifecycle<>(Runnable::run);Port old=new Port();host.open(old,c->fail());old.start();old.info.complete(Result.ok("info"));
        host.close();host.close();assertEquals(1,old.retires);old.retired.complete(Result.ok(null));old.released.complete(Result.ok(null));
        Port next=new Port();host.open(next,c->fail());next.pin.complete(Result.ok("new-pin"));next.file.complete(Result.ok("file"));next.info.complete(Result.ok("info"));
        assertFalse(old.info.complete(Result.ok("stale")));assertFalse(old.retired.complete(Result.ok(null)));assertTrue(next.releasedPins.isEmpty());assertTrue(host.busy());
        host.close();next.retired.complete(Result.ok(null));assertEquals(List.of("new-pin"),next.releasedPins);assertEquals(List.of("pin"),old.releasedPins);
    }
    @Test public void failedLeaseReleaseKeepsAttemptBlocked(){
        WalletViewerLifecycle<String,String,String> host=new WalletViewerLifecycle<>(Runnable::run);Port p=new Port();host.open(p,c->{});p.start();p.info.complete(Result.failed(Code.UNSUPPORTED));
        p.retired.complete(Result.ok(null));p.released.complete(Result.uncertain(null));assertTrue(host.busy());
    }
    @Test public void throwingFailureObserverCannotStrandFailedAcquisition(){
        WalletViewerLifecycle<String,String,String> host=new WalletViewerLifecycle<>(Runnable::run);Port p=new Port();
        host.open(p,c->{throw new IllegalStateException("observer");});p.pin.complete(Result.failed(Code.BUSY));
        assertFalse(host.busy());assertEquals(0,p.retires);
    }
    @Test public void throwingFailureObserverCannotPreventRendererRetirement(){
        WalletViewerLifecycle<String,String,String> host=new WalletViewerLifecycle<>(Runnable::run);Port p=new Port();
        host.open(p,c->{throw new IllegalStateException("observer");});p.start();p.info.complete(Result.failed(Code.UNSUPPORTED));
        assertEquals(1,p.retires);assertTrue(p.releasedPins.isEmpty());
        p.retired.complete(Result.ok(null));p.released.complete(Result.ok(null));assertFalse(host.busy());
    }
    @Test public void throwingRemovedObserverCannotPreventRendererRetirement(){
        WalletViewerLifecycle<String,String,String> host=new WalletViewerLifecycle<>(Runnable::run);Port p=new Port();
        p.removed=()->{throw new IllegalStateException("removed observer");};host.open(p,c->{});p.start();p.info.complete(Result.ok("info"));host.close();
        assertEquals(1,p.retires);assertTrue(p.releasedPins.isEmpty());
        p.retired.complete(Result.ok(null));p.released.complete(Result.ok(null));assertFalse(host.busy());
    }
    @Test public void throwingShowFailureAndRemovedObserversStillRequireRetirementProof(){
        WalletViewerLifecycle<String,String,String> host=new WalletViewerLifecycle<>(Runnable::run);Port p=new Port();
        p.shown=()->{throw new IllegalStateException("show observer");};p.removed=()->{throw new IllegalStateException("removed observer");};
        host.open(p,c->{throw new IllegalStateException("failure observer");});p.start();p.info.complete(Result.ok("info"));
        assertEquals(1,p.retires);assertEquals(1,p.hides);assertTrue(p.releasedPins.isEmpty());
        p.retired.complete(Result.failed(Code.RENDERER_TIMEOUT));assertTrue(p.releasedPins.isEmpty());assertTrue(host.busy());
    }
    @Test public void retirementEvidenceRequiresDescriptorAndWorkerRetirement(){
        WalletRendererRetirement proof=new WalletRendererRetirement();proof.descriptorOwned();assertFalse(proof.proven(true));
        proof.descriptorClosed();assertTrue(proof.proven(true));assertFalse(proof.proven(false));
        proof.submitted();assertFalse(proof.proven(true)); // Includes timeout, disconnect, and delayed worker completion.
        proof.closeAcknowledged();assertFalse(proof.proven(false));assertTrue(proof.proven(true));
    }
}
