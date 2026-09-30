package com.koalaman64.italytravelpocketguide;

/** Evidence only: elapsed time and failed callbacks never establish retirement. */
final class WalletRendererRetirement {
    private boolean descriptorClosed=true,submitted,closeAcknowledged;
    synchronized void descriptorOwned(){descriptorClosed=false;}
    synchronized void descriptorClosed(){descriptorClosed=true;}
    synchronized void submitted(){submitted=true;}
    synchronized void closeAcknowledged(){closeAcknowledged=true;}
    synchronized boolean proven(boolean workerIdle){return descriptorClosed&&workerIdle&&(!submitted||closeAcknowledged);}
}
