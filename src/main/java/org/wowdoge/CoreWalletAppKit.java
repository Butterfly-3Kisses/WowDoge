/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wowdoge;

import org.bitcoinj.core.*;
//import org.bitcoinj.net.discovery.IrcDiscovery;
import org.bitcoinj.core.listeners.DownloadProgressTracker;
import org.bitcoinj.core.listeners.PeerDataEventListener;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.params.RegTestParams;
import org.bitcoinj.protocols.channels.StoredPaymentChannelClientStates;
import org.bitcoinj.protocols.channels.StoredPaymentChannelServerStates;
import org.bitcoinj.store.BlockStoreException;
import org.bitcoinj.store.SPVBlockStore;
import org.bitcoinj.store.WalletProtobufSerializer;
import com.google.common.util.concurrent.AbstractIdleService;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import org.bitcoinj.wallet.Protos;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
//import java.util.Random;


import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import org.bitcoinj.kits.WalletAppKit;

/**
 * <p>Utility class that wraps the boilerplate needed to set up a new SPV bitcoinj app. Instantiate it with a directory
 * and file prefix, optionally configure a few things, then use startAsync and optionally awaitRunning. The object will
 * construct and configure a {@link BlockChain}, {@link SPVBlockStore}, {@link Wallet} and {@link PeerGroup}. Depending
 * on the value of the blockingStartup property, startup will be considered complete once the block chain has fully
 * synchronized, so it can take a while.</p>
 *
 * <p>To add listeners and modify the objects that are constructed, you can either do that by overriding the
 * {@link #onSetupCompleted()} method (which will run on a background thread) and make your changes there,
 * or by waiting for the service to start and then accessing the objects from wherever you want. However, you cannot
 * access the objects this class creates until startup is complete.</p>
 *
 * <p>The asynchronous design of this class may seem puzzling (just use {@link #awaitRunning()} if you don't want that).
 * It is to make it easier to fit bitcoinj into GUI apps, which require a high degree of responsiveness on their main
 * thread which handles all the animation and user interaction. Even when blockingStart is false, initializing bitcoinj
 * means doing potentially blocking file IO, generating keys and other potentially intensive operations. By running it
 * on a background thread, there's no risk of accidentally causing UI lag.</p>
 *
 * <p>Note that {@link #awaitRunning()} can throw an unchecked {@link java.lang.IllegalStateException}
 * if anything goes wrong during startup - you should probably handle it and use {@link Exception#getCause()} to figure
 * out what went wrong more precisely. Same thing if you just use the {@link #startAsync()} method.</p>
 */
public class CoreWalletAppKit extends WalletAppKit {
    protected String spvFilePath;

    public CoreWalletAppKit(NetworkParameters params, File directory, String filePrefix, String spvFilePath) {
        super(params, directory, filePrefix);
        this.spvFilePath = spvFilePath;
    }



    @Override
    protected void startUp() throws Exception {
        // Runs in a separate thread.
        Context.propagate(context);
        if (!directory.exists()) {
            if (!directory.mkdirs()) {
                throw new IOException("Could not create directory " + directory.getAbsolutePath());
            }
        }
        log.info("Starting up with directory = {}", directory);
        try {
            File chainFile = new File(spvFilePath); //new File(directory, filePrefix + ".spvchain");
            boolean chainFileExists = chainFile.exists();
            vWalletFile = new File(directory, filePrefix); //new File(directory, filePrefix + ".wallet");
            boolean shouldReplayWallet = (vWalletFile.exists() && !chainFileExists) || restoreFromSeed != null;
            vWallet = createOrLoadWallet(shouldReplayWallet);

            // Initiate Bitcoin network objects (block store, blockchain and peer group)
            vStore = provideBlockStore(chainFile);
            if (!chainFileExists || restoreFromSeed != null) {
                if (checkpoints == null && !Utils.isAndroidRuntime()) {
                    checkpoints = CheckpointManager.openStream(params);
                }

                if (checkpoints != null) {
                    // Initialize the chain file with a checkpoint to speed up first-run sync.
                    long time;
                    if (restoreFromSeed != null) {
                        time = restoreFromSeed.getCreationTimeSeconds();
                        if (chainFileExists) {
                            log.info("Deleting the chain file in preparation from restore.");
                            vStore.close();
                            if (!chainFile.delete())
                                throw new IOException("Failed to delete chain file in preparation for restore.");
                            vStore = new SPVBlockStore(params, chainFile);
                        }
                    } else {
                        time = vWallet.getEarliestKeyCreationTime();
                    }
                    if (time > 0)
                        CheckpointManager.checkpoint(params, checkpoints, vStore, time);
                    else
                        log.warn("Creating a new uncheckpointed block store due to a wallet with a creation time of zero: this will result in a very slow chain sync");
                } else if (chainFileExists) {
                    log.info("Deleting the chain file in preparation from restore.");
                    vStore.close();
                    if (!chainFile.delete())
                        throw new IOException("Failed to delete chain file in preparation for restore.");
                    vStore = new SPVBlockStore(params, chainFile);
                }
            }
            vChain = new BlockChain(params, vStore);
            vPeerGroup = createPeerGroup();
            if (this.userAgent != null)
                vPeerGroup.setUserAgent(userAgent, version);

            // Set up peer addresses or discovery first, so if wallet extensions try to broadcast a transaction
            // before we're actually connected the broadcast waits for an appropriate number of connections.
            if (peerAddresses != null) {
                for (PeerAddress addr : peerAddresses) vPeerGroup.addAddress(addr);
                vPeerGroup.setMaxConnections(peerAddresses.length);
                peerAddresses = null;
            } else if (params != RegTestParams.get() && !useTor) {
                vPeerGroup.addPeerDiscovery(discovery != null ? discovery : new DnsDiscovery(params));
            }
            vChain.addWallet(vWallet);
            vPeerGroup.addWallet(vWallet);
            onSetupCompleted();

            if (blockingStartup) {
                vPeerGroup.start();
                // Make sure we shut down cleanly.
                installShutdownHook();
                completeExtensionInitiations(vPeerGroup);

                // TODO: Be able to use the provided download listener when doing a blocking startup.
                final DownloadProgressTracker listener = new DownloadProgressTracker();
                vPeerGroup.startBlockChainDownload(listener);
                listener.await();
            } else {
                Futures.addCallback(vPeerGroup.startAsync(), new FutureCallback() {
                    @Override
                    public void onSuccess(@Nullable Object result) {
                        completeExtensionInitiations(vPeerGroup);
                        final PeerDataEventListener l = downloadListener == null ? new DownloadProgressTracker() : downloadListener;
                        vPeerGroup.startBlockChainDownload(l);
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        throw new RuntimeException(t);

                    }
                });
            }
        } catch (BlockStoreException e) {
            throw new IOException(e);
        }
    }

    private Wallet createOrLoadWallet(boolean shouldReplayWallet) throws Exception {
        Wallet wallet;

        maybeMoveOldWalletOutOfTheWay();

        if (vWalletFile.exists()) {
            wallet = loadWallet(shouldReplayWallet);
        } else {
            wallet = createWallet();
            wallet.freshReceiveKey();
            for (WalletExtension e : provideWalletExtensions()) {
                wallet.addExtension(e);
            }

            // Currently the only way we can be sure that an extension is aware of its containing wallet is by
            // deserializing the extension (see WalletExtension#deserializeWalletExtension(Wallet, byte[]))
            // Hence, we first save and then load wallet to ensure any extensions are correctly initialized.
            wallet.saveToFile(vWalletFile);
            wallet = loadWallet(false);
        }

        if (useAutoSave) wallet.autosaveToFile(vWalletFile, 5, TimeUnit.SECONDS, null);

        return wallet;
    }

    private Wallet loadWallet(boolean shouldReplayWallet) throws Exception {
        Wallet wallet;
        FileInputStream walletStream = new FileInputStream(vWalletFile);
        try {
            List<WalletExtension> extensions = provideWalletExtensions();
            WalletExtension[] extArray = extensions.toArray(new WalletExtension[extensions.size()]);
            Protos.Wallet proto = WalletProtobufSerializer.parseToProto(walletStream);
            final WalletProtobufSerializer serializer;
            if (walletFactory != null)
                serializer = new WalletProtobufSerializer(walletFactory);
            else
                serializer = new WalletProtobufSerializer();
            wallet = serializer.readWallet(params, extArray, proto);
            if (shouldReplayWallet)
                wallet.reset();
        } finally {
            walletStream.close();
        }
        return wallet;
    }

    private void maybeMoveOldWalletOutOfTheWay() {
        if (restoreFromSeed == null) return;
        if (!vWalletFile.exists()) return;
        int counter = 1;
        File newName;
        do {
            newName = new File(vWalletFile.getParent(), "Backup " + counter + " for " + vWalletFile.getName());
            counter++;
        } while (newName.exists());
        log.info("Renaming old wallet file {} to {}", vWalletFile, newName);
        if (!vWalletFile.renameTo(newName)) {
            // This should not happen unless something is really messed up.
            throw new RuntimeException("Failed to rename wallet for restore");
        }
    }

    /*
     * As soon as the transaction broadcaster han been created we will pass it to the
     * payment channel extensions
     */
    private void completeExtensionInitiations(TransactionBroadcaster transactionBroadcaster) {
        StoredPaymentChannelClientStates clientStoredChannels = (StoredPaymentChannelClientStates)
                vWallet.getExtensions().get(StoredPaymentChannelClientStates.class.getName());
        if(clientStoredChannels != null) {
            clientStoredChannels.setTransactionBroadcaster(transactionBroadcaster);
        }
        StoredPaymentChannelServerStates serverStoredChannels = (StoredPaymentChannelServerStates)
                vWallet.getExtensions().get(StoredPaymentChannelServerStates.class.getName());
        if(serverStoredChannels != null) {
            serverStoredChannels.setTransactionBroadcaster(transactionBroadcaster);
        }
    }


    private void installShutdownHook() {
        if (autoStop) Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override public void run() {
                try {
                    stopAsync();
                    awaitTerminated();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }
}
