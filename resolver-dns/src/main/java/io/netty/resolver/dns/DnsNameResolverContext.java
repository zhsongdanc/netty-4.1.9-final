/*
 * Copyright 2014 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.resolver.dns;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.AddressedEnvelope;
import io.netty.channel.socket.InternetProtocolFamily;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.dns.DefaultDnsQuestion;
import io.netty.handler.codec.dns.DefaultDnsRecordDecoder;
import io.netty.handler.codec.dns.DnsQuestion;
import io.netty.handler.codec.dns.DnsRawRecord;
import io.netty.handler.codec.dns.DnsRecord;
import io.netty.handler.codec.dns.DnsRecordType;
import io.netty.handler.codec.dns.DnsResponse;
import io.netty.handler.codec.dns.DnsResponseCode;
import io.netty.handler.codec.dns.DnsSection;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.StringUtil;

import java.net.IDN;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

abstract class DnsNameResolverContext<T> {

    private static final int INADDRSZ4 = 4;
    private static final int INADDRSZ6 = 16;

    private static final FutureListener<AddressedEnvelope<DnsResponse, InetSocketAddress>> RELEASE_RESPONSE =
            new FutureListener<AddressedEnvelope<DnsResponse, InetSocketAddress>>() {
                @Override
                public void operationComplete(Future<AddressedEnvelope<DnsResponse, InetSocketAddress>> future) {
                    if (future.isSuccess()) {
                        future.getNow().release();
                    }
                }
            };

    private final DnsNameResolver parent;
    private final DnsServerAddressStream nameServerAddrs;
    private final String hostname;
    protected String pristineHostname;
    private final DnsCache resolveCache;
    private final boolean traceEnabled;
    private final int maxAllowedQueries;
    private final InternetProtocolFamily[] resolvedInternetProtocolFamilies;
    private final DnsRecord[] additionals;

    private final Set<Future<AddressedEnvelope<DnsResponse, InetSocketAddress>>> queriesInProgress =
            Collections.newSetFromMap(
                    new IdentityHashMap<Future<AddressedEnvelope<DnsResponse, InetSocketAddress>>, Boolean>());

    private List<DnsCacheEntry> resolvedEntries;
    private StringBuilder trace;
    private int allowedQueries;
    private boolean triedCNAME;

    protected DnsNameResolverContext(DnsNameResolver parent,
                                     String hostname,
                                     DnsRecord[] additionals,
                                     DnsCache resolveCache,
                                     DnsServerAddressStream nameServerAddrs) {
        this.parent = parent;
        this.hostname = hostname;
        this.additionals = additionals;
        this.resolveCache = resolveCache;

        this.nameServerAddrs = nameServerAddrs;
        maxAllowedQueries = parent.maxQueriesPerResolve();
        resolvedInternetProtocolFamilies = parent.resolvedInternetProtocolFamiliesUnsafe();
        traceEnabled = parent.isTraceEnabled();
        allowedQueries = maxAllowedQueries;
    }

    void resolve(Promise<T> promise) {
        boolean directSearch = parent.searchDomains().length == 0 || StringUtil.endsWith(hostname, '.');
        if (directSearch) {
            internalResolve(promise);
        } else {
            final Promise<T> original = promise;
            promise = parent.executor().newPromise();
            promise.addListener(new FutureListener<T>() {
                int count;
                @Override
                public void operationComplete(Future<T> future) throws Exception {
                    if (future.isSuccess()) {
                        original.trySuccess(future.getNow());
                    } else if (count < parent.searchDomains().length) {
                        String searchDomain = parent.searchDomains()[count++];
                        Promise<T> nextPromise = parent.executor().newPromise();
                        String nextHostname = hostname + '.' + searchDomain;
                        DnsNameResolverContext<T> nextContext = newResolverContext(parent,
                            nextHostname, additionals, resolveCache, nameServerAddrs);
                        nextContext.pristineHostname = hostname;
                        nextContext.internalResolve(nextPromise);
                        nextPromise.addListener(this);
                    } else {
                        original.tryFailure(future.cause());
                    }
                }
            });
            if (parent.ndots() == 0) {
                internalResolve(promise);
            } else {
                int dots = 0;
                for (int idx = hostname.length() - 1; idx >= 0; idx--) {
                    if (hostname.charAt(idx) == '.' && ++dots >= parent.ndots()) {
                        internalResolve(promise);
                        return;
                    }
                }
                promise.tryFailure(new UnknownHostException(hostname));
            }
        }
    }

    private void internalResolve(Promise<T> promise) {
        DnsServerAddressStream nameServerAddressStream = getNameServers(hostname);

        for (DnsRecordType type: parent.resolveRecordTypes()) {
            if (!query(hostname, type, nameServerAddressStream, promise)) {
                return;
            }
        }
    }

    /**
     * Add an authoritative nameserver to the cache if its not a root server.
     */
    private void addNameServerToCache(
            AuthoritativeNameServer name, InetAddress resolved, long ttl) {
        if (!name.isRootServer()) {
            // Cache NS record if not for a root server as we should never cache for root servers.
            parent.authoritativeDnsServerCache().cache(name.domainName(),
                    additionals, resolved, ttl, parent.ch.eventLoop());
        }
    }

    /**
     * Returns the {@link DnsServerAddressStream} that was cached for the given hostname or {@code null} if non
     *  could be found.
     */
    private DnsServerAddressStream getNameServersFromCache(String hostname) {
        int len = hostname.length();

        if (len == 0) {
            // We never cache for root servers.
            return null;
        }

        // We always store in the cache with a trailing '.'.
        if (hostname.charAt(len - 1) != '.') {
            hostname += ".";
        }

        int idx = hostname.indexOf('.');
        if (idx == hostname.length() - 1) {
            // We are not interested in handling '.' as we should never serve the root servers from cache.
            return null;
        }

        // We start from the closed match and then move down.
        for (;;) {
            // Skip '.' as well.
            hostname = hostname.substring(idx + 1);

            int idx2 = hostname.indexOf('.');
            if (idx2 <= 0 || idx2 == hostname.length() - 1) {
                // We are not interested in handling '.TLD.' as we should never serve the root servers from cache.
                return null;
            }
            idx = idx2;

            List<DnsCacheEntry> entries = parent.authoritativeDnsServerCache().get(hostname, additionals);
            if (entries != null && !entries.isEmpty()) {
                // Found a match in the cache... Also shuffle them so we not always use the same order for lookup.
                return DnsServerAddresses.shuffled(new DnsCacheIterable(entries)).stream();
            }
        }
    }

    private final class DnsCacheIterable implements Iterable<InetSocketAddress> {
        private final List<DnsCacheEntry> entries;

        DnsCacheIterable(List<DnsCacheEntry> entries) {
            this.entries = entries;
        }

        @Override
        public Iterator<InetSocketAddress> iterator() {
            return new Iterator<InetSocketAddress>() {
                Iterator<DnsCacheEntry> entryIterator = entries.iterator();

                @Override
                public boolean hasNext() {
                    return entryIterator.hasNext();
                }

                @Override
                public InetSocketAddress next() {
                    InetAddress address = entryIterator.next().address();
                    return new InetSocketAddress(address, parent.dnsRedirectPort(address));
                }

                @Override
                public void remove() {
                    entryIterator.remove();
                }
            };
        }
    }

    private void query(final DnsServerAddressStream nameServerAddrStream, final DnsQuestion question,
                       final Promise<T> promise) {
        if (allowedQueries == 0 || promise.isCancelled()) {
            tryToFinishResolve(promise);
            return;
        }

        allowedQueries --;

        final Future<AddressedEnvelope<DnsResponse, InetSocketAddress>> f = parent.query0(
                nameServerAddrStream.next(), question, additionals,
                parent.ch.eventLoop().<AddressedEnvelope<? extends DnsResponse, InetSocketAddress>>newPromise());
        queriesInProgress.add(f);

        f.addListener(new FutureListener<AddressedEnvelope<DnsResponse, InetSocketAddress>>() {
            @Override
            public void operationComplete(Future<AddressedEnvelope<DnsResponse, InetSocketAddress>> future) {
                queriesInProgress.remove(future);

                if (promise.isDone() || future.isCancelled()) {
                    return;
                }

                try {
                    if (future.isSuccess()) {
                        onResponse(nameServerAddrStream, question, future.getNow(), promise);
                    } else {
                        // Server did not respond or I/O error occurred; try again.
                        if (traceEnabled) {
                            addTrace(future.cause());
                        }
                        query(nameServerAddrStream, question, promise);
                    }
                } finally {
                    tryToFinishResolve(promise);
                }
            }
        });
    }

    void onResponse(final DnsServerAddressStream nameServerAddrStream, final DnsQuestion question,
                    AddressedEnvelope<DnsResponse, InetSocketAddress> envelope, Promise<T> promise) {
        try {
            final DnsResponse res = envelope.content();
            final DnsResponseCode code = res.code();
            if (code == DnsResponseCode.NOERROR) {
                if (handleRedirect(question, envelope, promise)) {
                    // Was a redirect so return here as everything else is handled in handleRedirect(...)
                    return;
                }
                final DnsRecordType type = question.type();

                if (type == DnsRecordType.A || type == DnsRecordType.AAAA) {
                    onResponseAorAAAA(type, question, envelope, promise);
                } else if (type == DnsRecordType.CNAME) {
                    onResponseCNAME(question, envelope, promise);
                }
                return;
            }

            if (traceEnabled) {
                addTrace(envelope.sender(),
                         "response code: " + code + " with " + res.count(DnsSection.ANSWER) + " answer(s) and " +
                         res.count(DnsSection.AUTHORITY) + " authority resource(s)");
            }

            // Retry with the next server if the server did not tell us that the domain does not exist.
            if (code != DnsResponseCode.NXDOMAIN) {
                query(nameServerAddrStream, question, promise);
            }
        } finally {
            ReferenceCountUtil.safeRelease(envelope);
        }
    }

    /**
     * Handle redirects if needed and returns {@code true} if there was a redirect handled. If {@code true} is returned
     * this method took ownership of the {@code promise} otherwise {@code false} is returned.
     */
    private boolean handleRedirect(
            DnsQuestion question, AddressedEnvelope<DnsResponse, InetSocketAddress> envelope, Promise<T> promise) {
        final DnsResponse res = envelope.content();

        // Check if we have answers, if not this may be an non authority NS and so redirects must be handled.
        if (res.count(DnsSection.ANSWER) == 0) {
            AuthoritativeNameServerList serverNames = extractAuthoritativeNameServers(question.name(), res);

            if (serverNames != null) {
                List<InetSocketAddress> nameServers = new ArrayList<InetSocketAddress>(serverNames.size());
                int additionalCount = res.count(DnsSection.ADDITIONAL);

                for (int i = 0; i < additionalCount; i++) {
                    final DnsRecord r = res.recordAt(DnsSection.ADDITIONAL, i);

                    if ((r.type() == DnsRecordType.A && !parent.supportsARecords()) ||
                            r.type() == DnsRecordType.AAAA && !parent.supportsAAAARecords()) {
                        continue;
                    }

                    final String recordName = r.name();
                    AuthoritativeNameServer authoritativeNameServer =
                            serverNames.remove(recordName);

                    if (authoritativeNameServer == null) {
                        // Not a server we are interested in.
                        continue;
                    }

                    InetAddress resolved = parseAddress(r, recordName);
                    if (resolved == null) {
                        // Could not parse it, move to the next.
                        continue;
                    }

                    nameServers.add(new InetSocketAddress(resolved, parent.dnsRedirectPort(resolved)));
                    addNameServerToCache(authoritativeNameServer, resolved, r.timeToLive());
                }

                if (nameServers.isEmpty()) {
                    promise.tryFailure(
                            new UnknownHostException("Unable to find correct name server for " + hostname));
                } else {
                    // Shuffle as we want to re-distribute the load across name servers.
                    query(DnsServerAddresses.shuffled(nameServers).stream(), question, promise);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the {@code {@link AuthoritativeNameServerList} which were included in {@link DnsSection#AUTHORITY}
     * or {@code null} if non are found.
     */
    private static AuthoritativeNameServerList extractAuthoritativeNameServers(String questionName, DnsResponse res) {
        int authorityCount = res.count(DnsSection.AUTHORITY);
        if (authorityCount == 0) {
            return null;
        }

        AuthoritativeNameServerList serverNames = new AuthoritativeNameServerList(questionName);
        for (int i = 0; i < authorityCount; i++) {
            serverNames.add(res.recordAt(DnsSection.AUTHORITY, i));
        }
        return serverNames;
    }

    private void onResponseAorAAAA(
            DnsRecordType qType, DnsQuestion question, AddressedEnvelope<DnsResponse, InetSocketAddress> envelope,
            Promise<T> promise) {

        // We often get a bunch of CNAMES as well when we asked for A/AAAA.
        final DnsResponse response = envelope.content();
        final Map<String, String> cnames = buildAliasMap(response);
        final int answerCount = response.count(DnsSection.ANSWER);

        boolean found = false;
        for (int i = 0; i < answerCount; i ++) {
            final DnsRecord r = response.recordAt(DnsSection.ANSWER, i);
            final DnsRecordType type = r.type();
            if (type != DnsRecordType.A && type != DnsRecordType.AAAA) {
                continue;
            }

            final String questionName = question.name().toLowerCase(Locale.US);
            final String recordName = r.name().toLowerCase(Locale.US);

            // Make sure the record is for the questioned domain.
            if (!recordName.equals(questionName)) {
                // Even if the record's name is not exactly same, it might be an alias defined in the CNAME records.
                String resolved = questionName;
                do {
                    resolved = cnames.get(resolved);
                    if (recordName.equals(resolved)) {
                        break;
                    }
                } while (resolved != null);

                if (resolved == null) {
                    continue;
                }
            }

            InetAddress resolved = parseAddress(r, hostname);
            if (resolved == null) {
                continue;
            }

            if (resolvedEntries == null) {
                resolvedEntries = new ArrayList<DnsCacheEntry>(8);
            }

            final DnsCacheEntry e = new DnsCacheEntry(hostname, resolved);
            resolveCache.cache(hostname, additionals, resolved, r.timeToLive(), parent.ch.eventLoop());
            resolvedEntries.add(e);
            found = true;

            // Note that we do not break from the loop here, so we decode/cache all A/AAAA records.
        }

        if (found) {
            return;
        }

        if (traceEnabled) {
            addTrace(envelope.sender(), "no matching " + qType + " record found");
        }

        // We aked for A/AAAA but we got only CNAME.
        if (!cnames.isEmpty()) {
            onResponseCNAME(question, envelope, cnames, false, promise);
        }
    }

    private InetAddress parseAddress(DnsRecord r, String name) {
        if (!(r instanceof DnsRawRecord)) {
            return null;
        }
        final ByteBuf content = ((ByteBufHolder) r).content();
        final int contentLen = content.readableBytes();
        if (contentLen != INADDRSZ4 && contentLen != INADDRSZ6) {
            return null;
        }

        final byte[] addrBytes = new byte[contentLen];
        content.getBytes(content.readerIndex(), addrBytes);

        try {
            return InetAddress.getByAddress(
                    parent.isDecodeIdn() ? IDN.toUnicode(name) : name, addrBytes);
        } catch (UnknownHostException e) {
            // Should never reach here.
            throw new Error(e);
        }
    }

    private void onResponseCNAME(DnsQuestion question, AddressedEnvelope<DnsResponse, InetSocketAddress> envelope,
                                 Promise<T> promise) {
        onResponseCNAME(question, envelope, buildAliasMap(envelope.content()), true, promise);
    }

    private void onResponseCNAME(
            DnsQuestion question, AddressedEnvelope<DnsResponse, InetSocketAddress> response,
            Map<String, String> cnames, boolean trace, Promise<T> promise) {

        // Resolve the host name in the question into the real host name.
        final String name = question.name().toLowerCase(Locale.US);
        String resolved = name;
        boolean found = false;
        while (!cnames.isEmpty()) { // Do not attempt to call Map.remove() when the Map is empty
                                    // because it can be Collections.emptyMap()
                                    // whose remove() throws a UnsupportedOperationException.
            final String next = cnames.remove(resolved);
            if (next != null) {
                found = true;
                resolved = next;
            } else {
                break;
            }
        }

        if (found) {
            followCname(response.sender(), name, resolved, promise);
        } else if (trace && traceEnabled) {
            addTrace(response.sender(), "no matching CNAME record found");
        }
    }

    private static Map<String, String> buildAliasMap(DnsResponse response) {
        final int answerCount = response.count(DnsSection.ANSWER);
        Map<String, String> cnames = null;
        for (int i = 0; i < answerCount; i ++) {
            final DnsRecord r = response.recordAt(DnsSection.ANSWER, i);
            final DnsRecordType type = r.type();
            if (type != DnsRecordType.CNAME) {
                continue;
            }

            if (!(r instanceof DnsRawRecord)) {
                continue;
            }

            final ByteBuf recordContent = ((ByteBufHolder) r).content();
            final String domainName = decodeDomainName(recordContent);
            if (domainName == null) {
                continue;
            }

            if (cnames == null) {
                cnames = new HashMap<String, String>();
            }

            cnames.put(r.name().toLowerCase(Locale.US), domainName.toLowerCase(Locale.US));
        }

        return cnames != null? cnames : Collections.<String, String>emptyMap();
    }

    void tryToFinishResolve(Promise<T> promise) {
        if (!queriesInProgress.isEmpty()) {
            // There are still some queries we did not receive responses for.
            if (gotPreferredAddress()) {
                // But it's OK to finish the resolution process if we got a resolved address of the preferred type.
                finishResolve(promise);
            }

            // We did not get any resolved address of the preferred type, so we can't finish the resolution process.
            return;
        }

        // There are no queries left to try.
        if (resolvedEntries == null) {
            // .. and we could not find any A/AAAA records.
            if (!triedCNAME) {
                // As the last resort, try to query CNAME, just in case the name server has it.
                triedCNAME = true;

                query(hostname, DnsRecordType.CNAME, getNameServers(hostname), promise);
                return;
            }
        }

        // We have at least one resolved address or tried CNAME as the last resort..
        finishResolve(promise);
    }

    private boolean gotPreferredAddress() {
        if (resolvedEntries == null) {
            return false;
        }

        final int size = resolvedEntries.size();
        switch (parent.preferredAddressType()) {
        case IPv4:
            for (int i = 0; i < size; i ++) {
                if (resolvedEntries.get(i).address() instanceof Inet4Address) {
                    return true;
                }
            }
            break;
        case IPv6:
            for (int i = 0; i < size; i ++) {
                if (resolvedEntries.get(i).address() instanceof Inet6Address) {
                    return true;
                }
            }
            break;
        default:
            throw new Error();
        }

        return false;
    }

    private void finishResolve(Promise<T> promise) {
        if (!queriesInProgress.isEmpty()) {
            // If there are queries in progress, we should cancel it because we already finished the resolution.
            for (Iterator<Future<AddressedEnvelope<DnsResponse, InetSocketAddress>>> i = queriesInProgress.iterator();
                 i.hasNext();) {
                Future<AddressedEnvelope<DnsResponse, InetSocketAddress>> f = i.next();
                i.remove();

                if (!f.cancel(false)) {
                    f.addListener(RELEASE_RESPONSE);
                }
            }
        }

        if (resolvedEntries != null) {
            // Found at least one resolved address.
            for (InternetProtocolFamily f: resolvedInternetProtocolFamilies) {
                if (finishResolve(f.addressType(), resolvedEntries, promise)) {
                    return;
                }
            }
        }

        // No resolved address found.
        final int tries = maxAllowedQueries - allowedQueries;
        final StringBuilder buf = new StringBuilder(64);

        buf.append("failed to resolve '");
        if (pristineHostname != null) {
          buf.append(pristineHostname);
        } else {
          buf.append(hostname);
        }
        buf.append('\'');
        if (tries > 1) {
            if (tries < maxAllowedQueries) {
                buf.append(" after ")
                   .append(tries)
                   .append(" queries ");
            } else {
                buf.append(". Exceeded max queries per resolve ")
                .append(maxAllowedQueries)
                .append(' ');
            }
        }
        if (trace != null) {
            buf.append(':')
               .append(trace);
        }
        final UnknownHostException cause = new UnknownHostException(buf.toString());

        resolveCache.cache(hostname, additionals, cause, parent.ch.eventLoop());
        promise.tryFailure(cause);
    }

    abstract boolean finishResolve(Class<? extends InetAddress> addressType, List<DnsCacheEntry> resolvedEntries,
                                   Promise<T> promise);

    abstract DnsNameResolverContext<T> newResolverContext(DnsNameResolver parent, String hostname,
                                                          DnsRecord[] additionals, DnsCache resolveCache,
                                                          DnsServerAddressStream nameServerAddrs);

    static String decodeDomainName(ByteBuf in) {
        in.markReaderIndex();
        try {
            return DefaultDnsRecordDecoder.decodeName(in);
        } catch (CorruptedFrameException e) {
            // In this case we just return null.
            return null;
        } finally {
            in.resetReaderIndex();
        }
    }

    private DnsServerAddressStream getNameServers(String hostame) {
        DnsServerAddressStream stream = getNameServersFromCache(hostame);
        return stream == null ? nameServerAddrs : stream;
    }

    private void followCname(InetSocketAddress nameServerAddr, String name, String cname, Promise<T> promise) {

        if (traceEnabled) {
            if (trace == null) {
                trace = new StringBuilder(128);
            }

            trace.append(StringUtil.NEWLINE);
            trace.append("\tfrom ");
            trace.append(nameServerAddr);
            trace.append(": ");
            trace.append(name);
            trace.append(" CNAME ");
            trace.append(cname);
        }

        // Use the same server for both CNAME queries
        DnsServerAddressStream stream = DnsServerAddresses.singleton(getNameServers(cname).next()).stream();

        if (parent.supportsARecords() && !query(hostname, DnsRecordType.A, stream, promise)) {
            return;
        }
        if (parent.supportsAAAARecords()) {
            query(hostname, DnsRecordType.AAAA, stream, promise);
        }
    }

    private boolean query(String hostname, DnsRecordType type, DnsServerAddressStream nextAddr, Promise<T> promise) {
        final DnsQuestion question;
        try {
            question = new DefaultDnsQuestion(hostname, type);
        } catch (IllegalArgumentException e) {
            // java.net.IDN.toASCII(...) may throw an IllegalArgumentException if it fails to parse the hostname
            promise.tryFailure(e);
            return false;
        }
        query(nextAddr, question, promise);
        return true;
    }

    private void addTrace(InetSocketAddress nameServerAddr, String msg) {
        assert traceEnabled;

        if (trace == null) {
            trace = new StringBuilder(128);
        }

        trace.append(StringUtil.NEWLINE);
        trace.append("\tfrom ");
        trace.append(nameServerAddr);
        trace.append(": ");
        trace.append(msg);
    }

    private void addTrace(Throwable cause) {
        assert traceEnabled;

        if (trace == null) {
            trace = new StringBuilder(128);
        }

        trace.append(StringUtil.NEWLINE);
        trace.append("Caused by: ");
        trace.append(cause);
    }

    /**
     * Holds the closed DNS Servers for a domain.
     */
    private static final class AuthoritativeNameServerList {

        private final String questionName;

        // We not expect the linked-list to be very long so a double-linked-list is overkill.
        private AuthoritativeNameServer head;
        private int count;

        AuthoritativeNameServerList(String questionName) {
            this.questionName = questionName.toLowerCase(Locale.US);
        }

        void add(DnsRecord r) {
            if (r.type() != DnsRecordType.NS || !(r instanceof DnsRawRecord)) {
                return;
            }

            // Only include servers that serve the correct domain.
            if (questionName.length() <  r.name().length()) {
                return;
            }

            String recordName = r.name().toLowerCase(Locale.US);

            int dots = 0;
            for (int a = recordName.length() - 1, b = questionName.length() - 1; a >= 0; a--, b--) {
                char c = recordName.charAt(a);
                if (questionName.charAt(b) != c) {
                    return;
                }
                if (c == '.') {
                    dots++;
                }
            }

            if (head != null && head.dots > dots) {
                // We already have a closer match so ignore this one, no need to parse the domainName etc.
                return;
            }

            final ByteBuf recordContent = ((ByteBufHolder) r).content();
            final String domainName = decodeDomainName(recordContent);
            if (domainName == null) {
                // Could not be parsed, ignore.
                return;
            }

            // We are only interested in preserving the nameservers which are the closest to our qName, so ensure
            // we drop servers that have a smaller dots count.
            if (head == null || head.dots < dots) {
                count = 1;
                head = new AuthoritativeNameServer(dots, recordName, domainName);
            } else if (head.dots == dots) {
                AuthoritativeNameServer serverName = head;
                while (serverName.next != null) {
                    serverName = serverName.next;
                }
                serverName.next = new AuthoritativeNameServer(dots, recordName, domainName);
                count++;
            }
        }

        // Just walk the linked-list and mark the entry as removed when matched, so next lookup will need to process
        // one node less.
        AuthoritativeNameServer remove(String nsName) {
            AuthoritativeNameServer serverName = head;

            while (serverName != null) {
                if (!serverName.removed && serverName.nsName.equalsIgnoreCase(nsName)) {
                    serverName.removed = true;
                    return serverName;
                }
                serverName = serverName.next;
            }
            return null;
        }

        int size() {
            return count;
        }
    }

    static final class AuthoritativeNameServer {
        final int dots;
        final String nsName;
        final String domainName;

        AuthoritativeNameServer next;
        boolean removed;

        AuthoritativeNameServer(int dots, String domainName, String nsName) {
            this.dots = dots;
            this.nsName = nsName;
            this.domainName = domainName;
        }

        /**
         * Returns {@code true} if its a root server.
         */
        boolean isRootServer() {
            return dots == 1;
        }

        /**
         * The domain for which the {@link AuthoritativeNameServer} is responsible.
         */
        String domainName() {
            return domainName;
        }
    }
}
