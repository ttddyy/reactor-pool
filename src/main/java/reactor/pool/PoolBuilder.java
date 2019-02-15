/*
 * Copyright (c) 2018-Present Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.pool;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.function.Predicate;

import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.pool.util.AllocationStrategies;
import reactor.pool.util.EvictionPredicates;

/**
 * A builder for {@link Pool}.
 *
 * @author Simon Baslé
 */
@SuppressWarnings("WeakerAccess")
public class PoolBuilder<T> {

    //TODO tests

    /**
     * Start building a {@link Pool} by describing how new objects are to be asynchronously allocated.
     * Note that the {@link Mono} {@code allocator} should NEVER block its thread (thus adapting from blocking code,
     * eg. a constructor, via {@link Mono#fromCallable(Callable)} should be augmented with {@link Mono#subscribeOn(Scheduler)}).
     *
     * @param allocator the asynchronous creator of poolable resources.
     * @param <T> the type of resource created and recycled by the {@link Pool}
     * @return a builder of {@link Pool}
     */
    public static <T> PoolBuilder<T> from(Mono<T> allocator) {
        return new PoolBuilder<>(allocator);
    }

    final Mono<T> allocator;

    boolean                 isThreadAffinity     = true;
    int                     initialSize          = 0;
    AllocationStrategy      allocationStrategy   = AllocationStrategies.unbounded();
    Function<T, Mono<Void>> releaseHandler       = noopHandler();
    Function<T, Mono<Void>> destroyHandler       = noopHandler();
    Predicate<PooledRef<T>> evictionPredicate    = neverPredicate();
    Scheduler               acquisitionScheduler = Schedulers.immediate();
    PoolMetricsRecorder     metricsRecorder      = NoOpPoolMetricsRecorder.INSTANCE;

    PoolBuilder(Mono<T> allocator) {
        this.allocator = allocator;
    }

    /**
     * If {@code true} the returned {@link Pool} attempts to keep resources on the same thread, by prioritizing
     * pending {@link Pool#acquire()} {@link Mono Monos} that were subscribed on the same thread on which a resource is
     * released. In case no such borrower exists, but some are pending from another thread, it will deliver to these
     * borrowers instead (a slow path with no fairness guarantees).
     *
     * @param isThreadAffinity {@literal true} to activate thread affinity on the pool.
     * @return a builder of {@link Pool} with thread affinity.
     */
    public PoolBuilder<T> threadAffinity(boolean isThreadAffinity) {
        this.isThreadAffinity = isThreadAffinity;
        return this;
    }

    /**
     * How many resources the {@link Pool} should allocate upon creation.
     * This parameter MAY be ignored by some implementations (although they should state so in their documentation).
     * <p>
     * Defaults to {@code 0}.
     *
     * @param n the initial size of the {@link Pool}.
     * @return this {@link Pool} builder
     */
    public PoolBuilder<T> initialSize(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("initialSize must be >= 0");
        }
        this.initialSize = n;
        return this;
    }

    /**
     * Limits in how many resources can be allocated and managed by the {@link Pool} are driven by the
     * provided {@link AllocationStrategy}.
     * <p>
     * Defaults to an unbounded creation of resources, although it is not a recommended one.
     * See {@link AllocationStrategies} for readily available strategies based on counters.
     *
     * @param allocationStrategy the {@link AllocationStrategy} to use
     * @return this {@link Pool} builder
     */
    public PoolBuilder<T> allocationStrategy(AllocationStrategy allocationStrategy) {
        this.allocationStrategy = Objects.requireNonNull(allocationStrategy, "allocationStrategy");
        return this;
    }

    /**
     * Provide a {@link Function handler} that will derive a reset {@link Mono} whenever a resource is released.
     * The reset procedure is applied asynchronously before vetting the object through {@link #evictionPredicate}.
     * If the reset Mono couldn't put the resource back in a usable state, it will be {@link #destroyHandler(Function) destroyed}.
     * <p>
     * Defaults to not resetting anything.
     *
     * @param releaseHandler the {@link Function} supplying the state-resetting {@link Mono}
     * @return this {@link Pool} builder
     */
    public PoolBuilder<T> releaseHandler(Function<T, Mono<Void>> releaseHandler) {
        this.releaseHandler = Objects.requireNonNull(releaseHandler, "releaseHandler");
        return this;
    }

    /**
     * Provide a {@link Function handler} that will derive a destroy {@link Mono} whenever a resource isn't fit for
     * usage anymore (either through eviction, manual invalidation, or because something went wrong with it).
     * The destroy procedure is applied asynchronously and errors are swallowed.
     * <p>
     * Defaults to recognizing {@link Disposable} and {@link java.io.Closeable} elements and disposing them.
     *
     * @param destroyHandler the {@link Function} supplying the state-resetting {@link Mono}
     * @return this {@link Pool} builder
     */
    public PoolBuilder<T> destroyHandler(Function<T, Mono<Void>> destroyHandler) {
        this.destroyHandler = Objects.requireNonNull(destroyHandler, "destroyHandler");
        return this;
    }

    /**
     * Provide an eviction {@link Predicate} that allows to decide if a resource is fit for being placed in the {@link Pool}.
     * This can happen whenever a resource is {@link PooledRef#release() released} back to the {@link Pool} (after
     * it was processed by the {@link #releaseHandler(Function)}), but also when being {@link Pool#acquire() acquired}
     * from the pool (triggering a second pass if the object is found to be unfit, eg. it has been idle for too long).
     * Finally, some pool implementations MAY implement a reaper thread mechanism that detect idle resources through
     * this predicate and destroy them.
     * <p>
     * Defaults to never evicting. See {@link EvictionPredicates} for pre-build eviction predicates.
     *
     * @param evictionPredicate a {@link Predicate} that returns {@code true} if the resource is unfit for the pool and should be destroyed
     * @return this {@link Pool} builder
     */
    public PoolBuilder<T> evictionPredicate(Predicate<PooledRef<T>> evictionPredicate) {
        this.evictionPredicate = Objects.requireNonNull(evictionPredicate, "evictionPredicate");
        return this;
    }

    /**
     * Provide a {@link Scheduler} that can optionally be used by a {@link Pool} to deliver its resources in a more
     * deterministic (albeit potentially less efficient) way, thread-wise. Other implementations MAY completely ignore
     * this parameter.
     * <p>
     * Defaults to {@link Schedulers#immediate()}.
     *
     * @param acquisitionScheduler the {@link Scheduler} on which to deliver acquired resources
     * @return this {@link Pool} builder
     */
    public PoolBuilder<T> acquisitionScheduler(Scheduler acquisitionScheduler) {
        this.acquisitionScheduler = Objects.requireNonNull(acquisitionScheduler, "acquisitionScheduler");
        return this;
    }

    /**
     * Set up the optional {@link PoolMetricsRecorder} for {@link Pool} to use for instrumentation purposes.
     *
     * @param recorder the {@link PoolMetricsRecorder}
     * @return this {@link Pool} builder
     */
    public PoolBuilder<T> metricsRecorder(PoolMetricsRecorder recorder) {
        this.metricsRecorder = Objects.requireNonNull(recorder, "recorder");
        return this;
    }

    /**
     * Build the {@link Pool}.
     *
     * @return the {@link Pool}
     */
    public Pool<T> build() {
        AbstractPool.DefaultPoolConfig<T> config = buildConfig();
        if (isThreadAffinity) {
            return new AffinityPool<>(config);
        }
        return new QueuePool<>(config);
    }

    //kept package-private for the benefit of tests
    AbstractPool.DefaultPoolConfig<T> buildConfig() {
        return new AbstractPool.DefaultPoolConfig<>(allocator, initialSize, allocationStrategy,
                releaseHandler,
                destroyHandler,
                evictionPredicate,
                acquisitionScheduler, metricsRecorder);
    }

    @SuppressWarnings("unchecked")
    static <T> Function<T, Mono<Void>> noopHandler() {
        return (Function<T, Mono<Void>>) NOOP_HANDLER;
    }

    @SuppressWarnings("unchecked")
    static <T> Predicate<PooledRef<T>>  neverPredicate() {
        return (Predicate<PooledRef<T>>) NEVER_PREDICATE;
    }

    static final Function<?, Mono<Void>> NOOP_HANDLER    = it -> Mono.empty();
    static final Predicate<?>            NEVER_PREDICATE = it -> false;

}