/* 
 Copyright (C) GridGain Systems. All Rights Reserved.
 
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0
 
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */

/*  _________        _____ __________________        _____
 *  __  ____/___________(_)______  /__  ____/______ ____(_)_______
 *  _  / __  __  ___/__  / _  __  / _  / __  _  __ `/__  / __  __ \
 *  / /_/ /  _  /    _  /  / /_/ /  / /_/ /  / /_/ / _  /  _  / / /
 *  \____/   /_/     /_/   \_,__/   \____/   \__,_/  /_/   /_/ /_/
 */

package org.gridgain.grid.kernal.processors.cache.local.atomic;

import org.gridgain.grid.*;
import org.gridgain.grid.cache.*;
import org.gridgain.grid.kernal.processors.cache.*;
import org.gridgain.grid.kernal.processors.cache.local.*;
import org.gridgain.grid.lang.*;
import org.gridgain.grid.util.*;
import org.gridgain.grid.util.future.*;
import org.gridgain.grid.util.typedef.*;
import org.gridgain.grid.util.typedef.internal.*;
import org.jetbrains.annotations.*;
import sun.misc.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

import static org.gridgain.grid.cache.GridCacheFlag.*;
import static org.gridgain.grid.kernal.processors.cache.GridCacheOperation.*;

/**
 * Non-transactional local cache.
 */
public class GridLocalAtomicCache<K, V> extends GridCacheAdapter<K, V> {
    /** Unsafe instance. */
    private static final Unsafe UNSAFE = GridUnsafe.unsafe();

    /** */
    private GridCachePreloader<K,V> preldr;

    /**
     * Empty constructor required by {@link Externalizable}.
     */
    public GridLocalAtomicCache() {
        // No-op.
    }

    /**
     * @param ctx Cache context.
     */
    public GridLocalAtomicCache(GridCacheContext<K, V> ctx) {
        super(ctx, ctx.config().getStartSize());

        preldr = new GridCachePreloaderAdapter<>(ctx);
    }

    /** {@inheritDoc} */
    @Override protected void init() {
        map.setEntryFactory(new GridCacheMapEntryFactory<K, V>() {
            @Override public GridCacheMapEntry<K, V> create(GridCacheContext<K, V> ctx, long topVer, K key, int hash,
                V val, @Nullable GridCacheMapEntry<K, V> next, long ttl, int hdrId) {
                return new GridLocalCacheEntry<K, V>(ctx, key, hash, val, next, ttl, hdrId) {
                    @Override public GridCacheEntry<K, V> wrapFilterLocked() throws GridException {
                        assert Thread.holdsLock(this);

                        return new GridCacheFilterEvaluationEntry<>(key, rawGetOrUnmarshal(), this);
                    }
                };
            }
        });
    }

    /** {@inheritDoc} */
    @Override public void start() throws GridException {
        // No-op.
    }

    /** {@inheritDoc} */
    @Override public boolean isLocal() {
        return true;
    }

    /** {@inheritDoc} */
    @Override public GridCachePreloader<K, V> preloader() {
        return preldr;
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public V put(K key,
        V val,
        @Nullable GridCacheEntryEx<K, V> cached,
        long ttl,
        @Nullable GridPredicate<GridCacheEntry<K, V>>[] filter) throws GridException {
        A.notNull(key, "key", val, "val");

        ctx.denyOnLocalRead();

        return (V)updateAllInternal(UPDATE,
            Collections.singleton(key),
            Collections.singleton(val),
            ttl,
            true,
            false,
            filter,
            ctx.isStoreEnabled());
    }

    /** {@inheritDoc} */
    @Override public boolean putx(K key,
        V val,
        @Nullable GridCacheEntryEx<K, V> cached,
        long ttl,
        @Nullable GridPredicate<GridCacheEntry<K, V>>... filter) throws GridException {
        A.notNull(key, "key", val, "val");

        ctx.denyOnLocalRead();

        return (Boolean)updateAllInternal(UPDATE,
            Collections.singleton(key),
            Collections.singleton(val),
            ttl,
            false,
            false,
            filter,
            ctx.isStoreEnabled());
    }

    /** {@inheritDoc} */
    @Override public boolean putx(K key,
        V val,
        GridPredicate<GridCacheEntry<K, V>>[] filter) throws GridException {
        A.notNull(key, "key", val, "val");

        ctx.denyOnLocalRead();

        return (Boolean)updateAllInternal(UPDATE,
            Collections.singleton(key),
            Collections.singleton(val),
            -1,
            false,
            false,
            filter,
            ctx.isStoreEnabled());
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public GridFuture<V> putAsync(K key,
        V val,
        @Nullable GridCacheEntryEx<K, V> entry,
        long ttl,
        @Nullable GridPredicate<GridCacheEntry<K, V>>... filter) {
        A.notNull(key, "key", val, "val");

        ctx.denyOnLocalRead();

        return updateAllAsync0(F0.asMap(key, val), null, true, false, ttl, filter);
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public GridFuture<Boolean> putxAsync(K key,
        V val,
        @Nullable GridCacheEntryEx<K, V> entry,
        long ttl,
        @Nullable GridPredicate<GridCacheEntry<K, V>>... filter) {
        A.notNull(key, "key", val, "val");

        ctx.denyOnLocalRead();

        return updateAllAsync0(F0.asMap(key, val), null, false, false, ttl, filter);
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public V putIfAbsent(K key, V val) throws GridException {
        return put(key, val, ctx.noPeekArray());
    }

    /** {@inheritDoc} */
    @Override public GridFuture<V> putIfAbsentAsync(K key, V val) {
        return putAsync(key, val, ctx.noPeekArray());
    }

    /** {@inheritDoc} */
    @Override public boolean putxIfAbsent(K key, V val) throws GridException {
        return putx(key, val, ctx.noPeekArray());
    }

    /** {@inheritDoc} */
    @Override public GridFuture<Boolean> putxIfAbsentAsync(K key, V val) {
        return putxAsync(key, val, ctx.noPeekArray());
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public V replace(K key, V val) throws GridException {
        return put(key, val, ctx.hasPeekArray());
    }

    /** {@inheritDoc} */
    @Override public GridFuture<V> replaceAsync(K key, V val) {
        return putAsync(key, val, ctx.hasPeekArray());
    }

    /** {@inheritDoc} */
    @Override public boolean replacex(K key, V val) throws GridException {
        return putx(key, val, ctx.hasPeekArray());
    }

    /** {@inheritDoc} */
    @Override public GridFuture<Boolean> replacexAsync(K key, V val) {
        return putxAsync(key, val, ctx.hasPeekArray());
    }

    /** {@inheritDoc} */
    @Override public boolean replace(K key, V oldVal, V newVal) throws GridException {
        return putx(key, newVal, ctx.equalsPeekArray(oldVal));
    }

    /** {@inheritDoc} */
    @Override public GridFuture<Boolean> replaceAsync(K key, V oldVal, V newVal) {
        return putxAsync(key, newVal, ctx.equalsPeekArray(oldVal));
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public GridCacheReturn<V> replacex(K key, V oldVal, V newVal) throws GridException {
        A.notNull(key, "key");

        ctx.denyOnLocalRead();

        return (GridCacheReturn<V>)updateAllInternal(UPDATE,
            Collections.singleton(key),
            Collections.singleton(newVal),
            0,
            true,
            true,
            ctx.equalsPeekArray(oldVal),
            ctx.isStoreEnabled());
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public GridCacheReturn<V> removex(K key, V val) throws GridException {
        A.notNull(key, "key");

        ctx.denyOnLocalRead();

        return (GridCacheReturn<V>)updateAllInternal(DELETE,
            Collections.singleton(key),
            null,
            0,
            true,
            true,
            ctx.equalsPeekArray(val),
            ctx.isStoreEnabled());
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public GridFuture<GridCacheReturn<V>> removexAsync(K key, V val) {
        A.notNull(key, "key");

        ctx.denyOnLocalRead();

        return removeAllAsync0(F.asList(key), true, true, ctx.equalsPeekArray(val));
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public GridFuture<GridCacheReturn<V>> replacexAsync(K key, V oldVal, V newVal) {
        A.notNull(key, "key");

        ctx.denyOnLocalRead();

        return updateAllAsync0(F.asMap(key, newVal), null, true, true, 0,
            ctx.equalsPeekArray(oldVal));
    }

    /** {@inheritDoc} */
    @Override public void putAll(Map<? extends K, ? extends V> m,
        GridPredicate<GridCacheEntry<K, V>>[] filter) throws GridException {
        ctx.denyOnLocalRead();

        updateAllInternal(UPDATE,
            m.keySet(),
            m.values(),
            0,
            false,
            false,
            filter,
            ctx.isStoreEnabled());
    }

    /** {@inheritDoc} */
    @Override public GridFuture<?> putAllAsync(Map<? extends K, ? extends V> m,
        @Nullable GridPredicate<GridCacheEntry<K, V>>[] filter) {
        ctx.denyOnLocalRead();

        return updateAllAsync0(m, null, false, false, 0, filter);
    }

    /** {@inheritDoc} */
    @Override public void transform(K key, GridClosure<V, V> transformer) throws GridException {
        ctx.denyOnLocalRead();

        updateAllInternal(TRANSFORM,
            Collections.singleton(key),
            Collections.singleton(transformer),
            -1,
            false,
            false,
            null,
            ctx.isStoreEnabled());
    }

    /** {@inheritDoc} */
    @Override public GridFuture<?> transformAsync(K key,
        GridClosure<V, V> transformer,
        @Nullable GridCacheEntryEx<K, V> entry,
        long ttl) {
        ctx.denyOnLocalRead();

        return updateAllAsync0(null, Collections.singletonMap(key, transformer), false, false, ttl, null);
    }

    /** {@inheritDoc} */
    @SuppressWarnings("ConstantConditions")
    @Override public void transformAll(@Nullable Map<? extends K, ? extends GridClosure<V, V>> m) throws GridException {
        ctx.denyOnLocalRead();

        if (F.isEmpty(m))
            return;

        updateAllInternal(TRANSFORM,
            m.keySet(),
            m.values(),
            0,
            false,
            false,
            null,
            ctx.isStoreEnabled());
    }

    /** {@inheritDoc} */
    @Override public GridFuture<?> transformAllAsync(@Nullable Map<? extends K, ? extends GridClosure<V, V>> m) {
        ctx.denyOnLocalRead();

        if (F.isEmpty(m))
            return new GridFinishedFuture<Object>(ctx.kernalContext());

        return updateAllAsync0(null, m, false, false, 0, null);
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public V remove(K key,
        @Nullable GridCacheEntryEx<K, V> entry,
        @Nullable GridPredicate<GridCacheEntry<K, V>>... filter) throws GridException {
        ctx.denyOnLocalRead();

        return (V)updateAllInternal(DELETE,
            Collections.singleton(key),
            null,
            0,
            true,
            false,
            filter,
            ctx.isStoreEnabled());
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public GridFuture<V> removeAsync(K key,
        @Nullable GridCacheEntryEx<K, V> entry,
        @Nullable GridPredicate<GridCacheEntry<K, V>>... filter) {
        ctx.denyOnLocalRead();

        return removeAllAsync0(Collections.singletonList(key), true, false, filter);
    }

    /** {@inheritDoc} */
    @Override public void removeAll(Collection<? extends K> keys,
        GridPredicate<GridCacheEntry<K, V>>... filter) throws GridException {
        ctx.denyOnLocalRead();

        updateAllInternal(DELETE,
            keys,
            null,
            0,
            false,
            false,
            filter,
            ctx.isStoreEnabled());
    }

    /** {@inheritDoc} */
    @Override public GridFuture<?> removeAllAsync(Collection<? extends K> keys,
        GridPredicate<GridCacheEntry<K, V>>[] filter) {
        ctx.denyOnLocalRead();

        return removeAllAsync0(keys, false, false, filter);
    }

    /** {@inheritDoc} */
    @Override public boolean removex(K key,
        @Nullable GridCacheEntryEx<K, V> entry,
        @Nullable GridPredicate<GridCacheEntry<K, V>>... filter) throws GridException {
        A.notNull(key, "key");

        ctx.denyOnLocalRead();

        return (Boolean)updateAllInternal(DELETE,
            Collections.singleton(key),
            null,
            0,
            false,
            false,
            filter,
            ctx.isStoreEnabled());
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public GridFuture<Boolean> removexAsync(K key,
        @Nullable GridCacheEntryEx<K, V> entry,
        @Nullable GridPredicate<GridCacheEntry<K, V>>... filter) {
        A.notNull(key, "key");

        ctx.denyOnLocalRead();

        return removeAllAsync0(Collections.singletonList(key), false, false, filter);
    }

    /** {@inheritDoc} */
    @Override public boolean remove(K key, V val) throws GridException {
        A.notNull(key, "key");

        ctx.denyOnLocalRead();

        return (Boolean)updateAllInternal(DELETE,
            Collections.singleton(key),
            null,
            0,
            false,
            false,
            ctx.equalsPeekArray(val),
            ctx.isStoreEnabled());
    }

    /** {@inheritDoc} */
    @Override public GridFuture<Boolean> removeAsync(K key, V val) {
        return removexAsync(key, ctx.equalsPeekArray(val));
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public void removeAll(GridPredicate<GridCacheEntry<K, V>>[] filter) throws GridException {
        removeAll(keySet(filter));
    }

    /** {@inheritDoc} */
    @Override public GridFuture<?> removeAllAsync(GridPredicate<GridCacheEntry<K, V>>[] filter) {
        return removeAllAsync(keySet(filter), filter);
    }

    /** {@inheritDoc} */

    @SuppressWarnings("unchecked")
    @Override @Nullable public V get(K key, @Nullable GridPredicate<GridCacheEntry<K, V>> filter) throws GridException {
        ctx.denyOnFlag(LOCAL);

        Map<K, V> m = getAllInternal(Collections.singleton(key),
            filter != null ? new GridPredicate[]{filter} : null,
            ctx.isSwapOrOffheapEnabled(),
            ctx.isStoreEnabled(),
            ctx.hasFlag(CLONE));

        return m.get(key);
    }

    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public final Map<K, V> getAll(Collection<? extends K> keys, GridPredicate<GridCacheEntry<K, V>> filter)
        throws GridException {
        ctx.denyOnFlag(LOCAL);

        return getAllInternal(keys,
            filter != null ? new GridPredicate[]{filter} : null,
            ctx.isSwapOrOffheapEnabled(),
            ctx.isStoreEnabled(),
            ctx.hasFlag(CLONE));
    }


    /** {@inheritDoc} */
    @SuppressWarnings("unchecked")
    @Override public GridFuture<Map<K, V>> getAllAsync(
        @Nullable final Collection<? extends K> keys,
        final boolean forcePrimary,
        boolean skipTx,
        @Nullable final GridCacheEntryEx<K, V> entry,
        @Nullable final GridPredicate<GridCacheEntry<K, V>>[] filter
    ) {
        ctx.denyOnFlag(LOCAL);

        final boolean swapOrOffheap = ctx.isSwapOrOffheapEnabled();
        final boolean storeEnabled = ctx.isStoreEnabled();
        final boolean clone = ctx.hasFlag(CLONE);

        return asyncOp(new Callable<Map<K, V>>() {
            @Override public Map<K, V> call() throws Exception {
                return getAllInternal(keys, filter, swapOrOffheap, storeEnabled, clone);
            }
        });
    }

    /**
     * Entry point to all public API get methods.
     *
     * @param keys Keys to remove.
     * @param filter Filter.
     * @param swapOrOffheap {@code True} if swap of off-heap storage are enabled.
     * @param storeEnabled Store enabled flag.
     * @param clone {@code True} if returned values should be cloned.
     * @return Key-value map.
     * @throws GridException If failed.
     */
    @SuppressWarnings("ConstantConditions")
    private Map<K, V> getAllInternal(@Nullable Collection<? extends K> keys,
        @Nullable GridPredicate<GridCacheEntry<K, V>>[] filter,
        boolean swapOrOffheap,
        boolean storeEnabled,
        boolean clone) throws GridException {
        if (F.isEmpty(keys))
            return Collections.emptyMap();

        boolean success = true;

        Map<K, V> vals = new HashMap<>(keys.size(), 1.0f);

        for (K key : keys) {
            if (key == null)
                continue;

            GridCacheEntryEx<K, V> entry = null;

            while (true) {
                try {
                    entry = swapOrOffheap ? entryEx(key) : peekEx(key);

                    if (entry != null) {
                        V v = entry.innerGet(null,
                            /*swap*/swapOrOffheap,
                            /*read-through*/false,
                            /*fail-fast*/false,
                            /*unmarshal*/true,
                            /**update-metrics*/true,
                            /**event*/true,
                            filter);

                        if (v != null)
                            vals.put(key, v);
                        else
                            success = false;
                    }
                    else
                        success = false;

                    break; // While.
                }
                catch (GridCacheEntryRemovedException ignored) {
                    // No-op, retry.
                }
                catch (GridCacheFilterFailedException ignored) {
                    // No-op, skip the key.
                    break;
                }
                finally {
                    if (entry != null)
                        ctx.evicts().touch(entry);
                }

                if (!success && storeEnabled)
                    break;
            }
        }

        if (success || !storeEnabled) {
            if (!clone)
                return vals;

            Map<K, V> map = new GridLeanMap<>();

            for (Map.Entry<K, V> e : vals.entrySet())
                map.put(e.getKey(), ctx.cloneValue(e.getValue()));

            return map;
        }

        return getAllAsync(keys, null, false, filter).get();
    }

    /**
     * Entry point for public API update methods.
     *
     * @param map Put map. Either {@code map} or {@code transformMap} should be passed.
     * @param transformMap Transform map. Either {@code map} or {@code transformMap} should be passed.
     * @param retval Return value required flag.
     * @param rawRetval Return {@code GridCacheReturn} instance.
     * @param ttl Entry time-to-live.
     * @param filter Cache entry filter for atomic updates.
     * @return Completion future.
     */
    private GridFuture updateAllAsync0(
        @Nullable final Map<? extends K, ? extends V> map,
        @Nullable final Map<? extends K, ? extends GridClosure<V, V>> transformMap,
        final boolean retval,
        final boolean rawRetval,
        final long ttl,
        @Nullable final GridPredicate<GridCacheEntry<K, V>>[] filter
    ) {
        final GridCacheOperation op = transformMap != null ? TRANSFORM : UPDATE;
        final Collection<? extends K> keys =
            map != null ? map.keySet() : transformMap != null ? transformMap.keySet() : null;
        final Collection<?> vals = map != null ? map.values() : transformMap != null ? transformMap.values() : null;
        final boolean storeEnabled = ctx.isStoreEnabled();

        return asyncOp(new Callable<Object>() {
            @Override public Object call() throws Exception {
                return updateAllInternal(op,
                    keys,
                    vals,
                    ttl,
                    retval,
                    rawRetval,
                    filter,
                    storeEnabled);
            }
        });
    }

    /**
     * Entry point for public API remove methods.
     *
     * @param keys Keys to remove.
     * @param retval Return value required flag.
     * @param rawRetval Return {@code GridCacheReturn} instance.
     * @param filter Cache entry filter.
     * @return Completion future.
     */
    private GridFuture removeAllAsync0(
        @Nullable final Collection<? extends K> keys,
        final boolean retval,
        final boolean rawRetval,
        @Nullable final GridPredicate<GridCacheEntry<K, V>>[] filter
    ) {
        final boolean storeEnabled = ctx.isStoreEnabled();

        return asyncOp(new Callable<Object>() {
            @Override public Object call() throws Exception {
                return updateAllInternal(DELETE,
                    keys,
                    null,
                    0,
                    retval,
                    rawRetval,
                    filter,
                    storeEnabled);
            }
        });
    }

    /**
     * Entry point for all public update methods (put, remove, transform).
     *
     * @param op Operation.
     * @param keys Keys.
     * @param vals Values.
     * @param ttl Time to live.
     * @param retval Return value required flag.
     * @param rawRetval Return {@code GridCacheReturn} instance.
     * @param filter Cache entry filter.
     * @param storeEnabled Store enabled flag.
     * @return Update result.
     * @throws GridException If failed.
     */
    private Object updateAllInternal(GridCacheOperation op,
        Collection<? extends K> keys,
        @Nullable Collection<?> vals,
        long ttl,
        boolean retval,
        boolean rawRetval,
        GridPredicate<GridCacheEntry<K, V>>[] filter,
        boolean storeEnabled) throws GridException {
        GridCacheVersion ver = ctx.versions().next();

        if (storeEnabled && keys.size() > 1) {
            updateWithBatch(op, keys, vals, ver, filter);

            return null;
        }

        Iterator<?> valsIter = vals != null ? vals.iterator() : null;

        GridBiTuple<Boolean, V> res = null;

        GridCachePartialUpdateException err = null;

        for (K key : keys) {
            Object val = valsIter != null ? valsIter.next() : null;

            if (key == null)
                continue;

            while (true) {
                GridCacheEntryEx<K, V> entry = null;

                try {
                    entry = entryEx(key);

                    GridBiTuple<Boolean, V> t = entry.innerUpdateLocal(
                        ver,
                        val == null ? DELETE : op,
                        val,
                        storeEnabled,
                        retval,
                        ttl,
                        true,
                        true,
                        filter);

                    if (res == null)
                        res = t;

                    break; // While.
                }
                catch (GridCacheEntryRemovedException ignored) {
                    if (log.isDebugEnabled())
                        log.debug("Got removed entry while updating (will retry): " + key);

                    entry = null;
                }
                catch (GridException e) {
                    if (err == null)
                        err = partialUpdateException();

                    err.add(F.asList(key), e);

                    U.error(log, "Failed to update key : " + key, e);

                    break;
                }
                finally {
                    if (entry != null)
                        ctx.evicts().touch(entry);
                }
            }
        }

        if (err != null)
            throw err;

        return res == null ? null : rawRetval ?
            new GridCacheReturn<>(res.get2(), res.get1()) : retval ? res.get2() : res.get1();
    }

    /**
     * Updates entries using batched write-through.
     *
     * @param op Operation.
     * @param keys Keys.
     * @param vals Values.
     * @param ver Cache version.
     * @param filter Optional filter.
     * @throws GridCachePartialUpdateException If update failed.
     */
    @SuppressWarnings("ForLoopReplaceableByForEach")
    private void updateWithBatch(
        GridCacheOperation op,
        Collection<? extends K> keys,
        @Nullable Collection<?> vals,
        GridCacheVersion ver,
        @Nullable GridPredicate<GridCacheEntry<K, V>>[] filter
    ) throws GridException {
        List<GridCacheEntryEx<K, V>> locked = lockEntries(keys);

        try {
            int size = locked.size();

            Map<K, V> putMap = null;
            Collection<K> rmvKeys = null;
            List<GridCacheEntryEx<K, V>> filtered = new ArrayList<>(size);

            GridCachePartialUpdateException err = null;

            Iterator<?> valsIter = vals != null ? vals.iterator() : null;

            for (int i = 0; i < size; i++) {
                GridCacheEntryEx<K, V> entry = locked.get(i);

                Object val = valsIter != null ? valsIter.next() : null;

                if (val == null && op != DELETE)
                    continue;

                try {
                    try {
                        if (!ctx.isAll(entry.wrapFilterLocked(), filter)) {
                            if (log.isDebugEnabled())
                                log.debug("Entry did not pass the filter (will skip write) [entry=" + entry +
                                    ", filter=" + Arrays.toString(filter) + ']');

                            continue;
                        }
                    }
                    catch (GridException e) {
                        if (err == null)
                            err = partialUpdateException();

                        err.add(F.asList(entry.key()), e);

                        continue;
                    }

                    filtered.add(entry);

                    if (op == TRANSFORM) {
                        V old = entry.innerGet(null, true, true, false, true, true, true, CU.<K, V>empty());

                        GridClosure<V, V> transform = (GridClosure<V, V>)val;

                        V updated = transform.apply(old);

                        if (updated == null) {
                            // Update previous batch.
                            if (putMap != null) {
                                err = updatePartialBatch(
                                    filtered,
                                    ver,
                                    putMap,
                                    null,
                                    err);

                                putMap = null;

                                filtered = new ArrayList<>();
                            }

                            // Start collecting new batch.
                            if (rmvKeys == null)
                                rmvKeys = new ArrayList<>(size);

                            rmvKeys.add(entry.key());
                        }
                        else {
                            // Update previous batch.
                            if (rmvKeys != null) {
                                err = updatePartialBatch(
                                    filtered,
                                    ver,
                                    null,
                                    rmvKeys,
                                    err);

                                rmvKeys = null;

                                filtered = new ArrayList<>();
                            }

                            if (putMap == null)
                                putMap = new LinkedHashMap<>(size, 1.0f);

                            putMap.put(entry.key(), updated);
                        }
                    }
                    else if (op == UPDATE) {
                        if (putMap == null)
                            putMap = new LinkedHashMap<>(size, 1.0f);

                        putMap.put(entry.key(), (V)val);
                    }
                    else {
                        assert op == DELETE;

                        if (rmvKeys == null)
                            rmvKeys = new ArrayList<>(size);

                        rmvKeys.add(entry.key());
                    }
                }
                catch (GridException e) {
                    if (err == null)
                        err = partialUpdateException();

                    err.add(F.asList(entry.key()), e);
                }
                catch (GridCacheEntryRemovedException ignore) {
                    assert false : "Entry cannot become obsolete while holding lock.";
                }
                catch (GridCacheFilterFailedException ignore) {
                    assert false : "Filter should never fail with failFast=false and empty filter.";
                }
            }

            // Store final batch.
            if (putMap != null || rmvKeys != null) {
                err = updatePartialBatch(
                    filtered,
                    ver,
                    putMap,
                    rmvKeys,
                    err);
            }
            else
                assert filtered.isEmpty();

            if (err != null)
                throw err;
        }
        finally {
            unlockEntries(locked);
        }
    }

    /**
     * @param entries Entries to update.
     * @param ver Cache version.
     * @param putMap Values to put.
     * @param rmvKeys Keys to remove.
     * @param err Optional partial update exception.
     * @return Partial update exception.
     */
    @SuppressWarnings("ForLoopReplaceableByForEach")
    private GridCachePartialUpdateException updatePartialBatch(List<GridCacheEntryEx<K, V>> entries,
        final GridCacheVersion ver,
        @Nullable Map<K, V> putMap,
        @Nullable Collection<K> rmvKeys,
        @Nullable GridCachePartialUpdateException err) {
        assert putMap == null ^ rmvKeys == null;
        GridCacheOperation op;

        try {
            if (putMap != null) {
                ctx.store().putAllToStore(null, F.viewReadOnly(putMap, new C1<V, GridBiTuple<V, GridCacheVersion>>() {
                    @Override public GridBiTuple<V, GridCacheVersion> apply(V v) {
                        return F.t(v, ver);
                    }
                }));

                op = UPDATE;
            }
            else {
                ctx.store().removeAllFromStore(null, rmvKeys);

                op = DELETE;
            }
        }
        catch (GridException e) {
            if (err == null)
                err = partialUpdateException();

            err.add(putMap != null ? putMap.keySet() : rmvKeys, e);

            return err;
        }

        for (int i = 0; i < entries.size(); i++) {
            GridCacheEntryEx<K, V> entry = entries.get(i);

            assert Thread.holdsLock(entry);

            if (entry.obsolete())
                continue;

            try {
                // We are holding java-level locks on entries at this point.
                V writeVal = op == UPDATE ? putMap.get(entry.key()) : null;

                assert writeVal != null || op == DELETE : "null write value found.";

                entry.innerUpdateLocal(ver, op, writeVal, false, false, 0, true, true, null);
            }
            catch (GridCacheEntryRemovedException ignore) {
                assert false : "Entry cannot become obsolete while holding lock.";
            }
            catch (GridException e) {
                if (err == null)
                    err = partialUpdateException();

                err.add(Collections.singleton(entry.key()), e);
            }
        }

        return err;
    }

    /**
     * Acquires java-level locks on cache entries.
     *
     * @param keys Keys to lock.
     * @return Collection of locked entries.
     */
    private List<GridCacheEntryEx<K, V>> lockEntries(Collection<? extends K> keys) {
        List<GridCacheEntryEx<K, V>> locked = new ArrayList<>(keys.size());

        while (true) {
            for (K key : keys) {
                if (key == null)
                    continue;

                GridCacheEntryEx<K, V> entry = entryEx(key);

                locked.add(entry);
            }

            for (int i = 0; i < locked.size(); i++) {
                GridCacheEntryEx<K, V> entry = locked.get(i);

                UNSAFE.monitorEnter(entry);

                if (entry.obsolete()) {
                    // Unlock all locked.
                    for (int j = 0; j <= i; j++)
                        UNSAFE.monitorExit(locked.get(j));

                    // Clear entries.
                    locked.clear();

                    // Retry.
                    break;
                }
            }

            if (!locked.isEmpty())
                return locked;
        }
    }

    /**
     * Releases java-level locks on cache entries.
     *
     * @param locked Locked entries.
     */
    private void unlockEntries(List<GridCacheEntryEx<K, V>> locked) {
        for (GridCacheEntryEx<K, V> entry : locked)
            UNSAFE.monitorExit(entry);

        for (GridCacheEntryEx<K, V> entry : locked)
            ctx.evicts().touch(entry);
    }

    /**
     * @return New partial update exception.
     */
    private static GridCachePartialUpdateException partialUpdateException() {
        return new GridCachePartialUpdateException("Failed to update keys (retry update if possible).");
    }

    /** {@inheritDoc} */
    @Override public GridFuture<Boolean> txLockAsync(Collection<? extends K> keys,
        long timeout,
        GridCacheTxLocalEx<K, V> tx,
        boolean isRead,
        boolean retval,
        GridCacheTxIsolation isolation,
        boolean invalidate,
        GridPredicate<GridCacheEntry<K, V>>[] filter) {
        return new GridFinishedFutureEx<>(new UnsupportedOperationException("Locks are not supported for " +
            "GridCacheAtomicityMode.ATOMIC mode (use GridCacheAtomicityMode.TRANSACTIONAL instead)"));
    }

    /** {@inheritDoc} */
    @Override public GridCacheTxLocalAdapter<K, V> newTx(boolean implicit,
        boolean implicitSingle,
        GridCacheTxConcurrency concurrency,
        GridCacheTxIsolation isolation,
        long timeout,
        boolean invalidate,
        boolean syncCommit,
        boolean syncRollback,
        boolean swapEnabled,
        boolean storeEnabled,
        int txSize,
        @Nullable Object grpLockKey,
        boolean partLock) {
        throw new UnsupportedOperationException("Transactions are not supported for " +
            "GridCacheAtomicityMode.ATOMIC mode (use GridCacheAtomicityMode.TRANSACTIONAL instead)");
    }

    /** {@inheritDoc} */
    @Override public GridFuture<Boolean> lockAllAsync(@Nullable Collection<? extends K> keys,
        long timeout,
        @Nullable GridPredicate<GridCacheEntry<K, V>>... filter) {
        return new GridFinishedFutureEx<>(new UnsupportedOperationException("Locks are not supported for " +
            "GridCacheAtomicityMode.ATOMIC mode (use GridCacheAtomicityMode.TRANSACTIONAL instead)"));
    }

    /** {@inheritDoc} */
    @Override public void unlockAll(@Nullable Collection<? extends K> keys,
        @Nullable GridPredicate<GridCacheEntry<K, V>>... filter) throws GridException {
        throw new UnsupportedOperationException("Locks are not supported for " +
            "GridCacheAtomicityMode.ATOMIC mode (use GridCacheAtomicityMode.TRANSACTIONAL instead)");
    }

    /**
     * @param op Operation closure.
     * @return Future.
     */
    @SuppressWarnings("unchecked")
    protected GridFuture asyncOp(final Callable<?> op) {
        GridFuture fail = asyncOpAcquire();

        if (fail != null)
            return fail;

        FutureHolder holder = lastFut.get();

        holder.lock();

        try {
            GridFuture fut = holder.future();

            if (fut != null && !fut.isDone()) {
                GridFuture f = new GridEmbeddedFuture(fut,
                    new C2<Object, Exception, GridFuture>() {
                        @Override public GridFuture apply(Object t, Exception e) {
                            return ctx.closures().callLocalSafe(op);
                        }
                    }, ctx.kernalContext());

                saveFuture(holder, f);

                return f;
            }

            GridFuture f = ctx.closures().callLocalSafe(op);

            saveFuture(holder, f);

            return f;
        }
        finally {
            holder.unlock();
        }
    }

    /** {@inheritDoc} */
    @Override public void onDeferredDelete(GridCacheEntryEx<K, V> entry, GridCacheVersion ver) {
        assert false : "Should not be called";
    }
}
