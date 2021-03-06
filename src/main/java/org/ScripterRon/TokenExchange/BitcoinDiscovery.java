/*
 * Copyright 2016 Ronald W Hoffman.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ScripterRon.TokenExchange;

import nxt.util.Logger;

import org.bitcoinj.core.AddressMessage;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Peer;
import org.bitcoinj.core.PeerAddress;
import org.bitcoinj.net.discovery.DnsDiscovery;
import org.bitcoinj.net.discovery.PeerDiscovery;
import org.bitcoinj.net.discovery.PeerDiscoveryException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Peer discovery for the Bitcoin wallet
 */
public class BitcoinDiscovery implements PeerDiscovery {

    /** A services flag that denotes whether the peer has a copy of the block chain */
    static final long NODE_NETWORK = 1;
    /** A flag that denotes whether the peer supports the getutxos message */
    static final long NODE_GETUTXOS = 2;
    /** A flag that denotes whether the peer supports Bloom filters */
    static final long NODE_BLOOM = 4;

    /** Peer address list */
    private final List<PeerAddress> peerAddresses = new ArrayList<>();

    /** Peer address map */
    private final Map<InetSocketAddress, PeerAddress> peerMap = new HashMap<>();

    /** Peer discovery map */
    private final Map<InetSocketAddress, InetSocketAddress> discoveryMap = new HashMap<>();

    /** DNS discovery */
    private DnsDiscovery dnsDiscovery;

    /**
     * Process an Address message
     *
     * @param   peer            Peer receiving the message
     * @param   message         Received ADDR message
     */
    void processAddressMessage(Peer peer, AddressMessage message) {
        List<PeerAddress> peerAddrs = message.getAddresses();
        NetworkParameters networkParams = BitcoinWallet.getNetworkParameters();
        synchronized(peerAddresses) {
            peerAddrs.forEach((peerAddr) -> {
                InetSocketAddress socketAddr = peerAddr.getSocketAddress();
                if (!socketAddr.getAddress().isLoopbackAddress()) {
                    PeerAddress addr = peerMap.get(socketAddr);
                    if (addr == null) {
                        addr = new PeerAddress(networkParams, peerAddr.getAddr(), peerAddr.getPort());
                        peerAddresses.add(addr);
                        peerMap.put(socketAddr, addr);
                        Logger.logDebugMessage("Added TokenExchange peer "
                                + addr.getAddr().toString() + ":" + addr.getPort());
                    }
                    addr.setTime(peerAddr.getTime());
                    addr.setServices(peerAddr.getServices());
                }
            });
        }
    }

    /**
     * Load the saved peers
     *
     * @throws  IOException     Unable to load saved peers
     */
    int loadPeers() throws IOException {
        synchronized(peerAddresses) {
            //
            // Read the saved peers
            //
            peerAddresses.clear();
            peerMap.clear();
            File peersFile = new File(BitcoinWallet.getWalletDirectory(), "PeerAddresses.dat");
            NetworkParameters networkParams = BitcoinWallet.getNetworkParameters();
            if (peersFile.exists()) {
                try (FileInputStream stream = new FileInputStream(peersFile)) {
                    byte[] buffer = new byte[(int)peersFile.length()];
                    int length = stream.read(buffer);
                    ByteBuffer buf = ByteBuffer.wrap(buffer);
                    buf.order(ByteOrder.LITTLE_ENDIAN);
                    while (buf.position() < length) {
                        int addrLength = buf.getShort();
                        byte[] addrBytes = new byte[addrLength];
                        buf.get(addrBytes);
                        int port = buf.getInt();
                        BigInteger services = BigInteger.valueOf(buf.getLong());
                        long time = buf.getLong();
                        InetAddress addr = InetAddress.getByAddress(addrBytes);
                        PeerAddress peerAddress = new PeerAddress(networkParams, addr, port);
                        peerAddress.setServices(services);
                        peerAddress.setTime(time);
                        peerAddresses.add(peerAddress);
                        peerMap.put(peerAddress.getSocketAddress(), peerAddress);
                    }
                }
                Logger.logInfoMessage(peerAddresses.size() + " TokenExchange peers loaded");
            }
            //
            // Randomize the address order since BitcoinJ starts with the first
            // entry and works through the list sequentially
            //
            Collections.shuffle(peerAddresses);
        }
        return peerAddresses.size();
    }

    /**
     * Store the saved peers
     *
     * @throws  IOException     Unable to store saved peers
     */
    void storePeers() throws IOException {
        synchronized(peerAddresses) {
            //
            // Sort the peer list in descending order by time so we save the
            // most recent peers and drop inactive peers
            //
            Collections.sort(peerAddresses, (o1, o2) ->
                    (o1.getTime() > o2.getTime() ? -1 : (o1.getTime() < o2.getTime() ? 1 : 0)));
            //
            // Write the first 200 peers to the save file
            //
            File peersFile = new File(BitcoinWallet.getWalletDirectory(), "PeerAddresses.dat");
            try (FileOutputStream stream = new FileOutputStream(peersFile)) {
                byte[] buffer = new byte[2 + 16 + 4 + 8 + 8];
                ByteBuffer buf = ByteBuffer.wrap(buffer);
                buf.order(ByteOrder.LITTLE_ENDIAN);
                int count = 0;
                for (PeerAddress peerAddress : peerAddresses) {
                    if (peerAddress.getAddr().isLoopbackAddress()) {
                        continue;
                    }
                    buf.position(0);
                    byte[] addrBytes = peerAddress.getAddr().getAddress();
                    buf.putShort((short)addrBytes.length);
                    buf.put(addrBytes);
                    buf.putInt(peerAddress.getPort());
                    buf.putLong(peerAddress.getServices().longValue());
                    buf.putLong(peerAddress.getTime());
                    stream.write(buffer, 0, buf.position());
                    if (++count >= 200) {
                        break;
                    }
                }
                Logger.logInfoMessage(count + " TokenExchange peers saved");
            }
        }
    }

    /**
     * Provide a list of peers that support Bloom filters (PeerDiscovery interface)
     *
     * @param   services                Bit mask of required services
     * @param   timeout                 Discovery timeout
     * @param   timeUnit                Time unit
     * @return                          Array of addresses
     * @throws  PeerDiscoveryException  Error during peer discovery
     */
    @Override
    public InetSocketAddress[] getPeers(long services, long timeout, TimeUnit timeUnit)
                                        throws PeerDiscoveryException {
        InetSocketAddress[] peers;
        //
        // Return any new peers discovered through ADDR messages
        //
        synchronized(peerAddresses) {
            peers = peerAddresses.stream()
                .filter((addr) -> {
                        InetSocketAddress socketAddr = addr.getSocketAddress();
                        if ((addr.getServices().longValue() & NODE_BLOOM) == NODE_BLOOM &&
                                    discoveryMap.get(socketAddr) == null) {
                            discoveryMap.put(socketAddr, socketAddr);
                            return true;
                        }
                        return false;
                    })
                .map((addr) -> addr.getSocketAddress())
                .toArray(InetSocketAddress[]::new);
            Logger.logDebugMessage("Returning " + peers.length + " peers from ADDR discovery");
        }
        //
        // Get peers from the DNS seeds if we have returned all of our peers (note that
        // DNS discovery cannot filter by service)
        //
        if (peers.length == 0) {
            if (dnsDiscovery == null) {
                dnsDiscovery = new DnsDiscovery(BitcoinWallet.getNetworkParameters());
            }
            peers = dnsDiscovery.getPeers(0, timeout, timeUnit);
            if (peers != null) {
                synchronized(peerAddresses) {
                    for (InetSocketAddress addr : peers) {
                        discoveryMap.put(addr, addr);
                    }
                }
                Logger.logDebugMessage("Returning " + peers.length + " peers from DNS discovery");
            }
        }
        return peers;
    }

    /**
     * Stop peer discovery
     */
    @Override
    public void shutdown() {
        // Nothing to do
    }
}
