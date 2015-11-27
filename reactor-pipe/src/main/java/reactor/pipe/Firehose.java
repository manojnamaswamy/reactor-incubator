package reactor.pipe;

import org.reactivestreams.Processor;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.Subscribers;
import reactor.core.processor.RingBufferWorkProcessor;
import reactor.core.subscription.SubscriptionWithContext;
import reactor.core.support.Assert;
import reactor.core.support.wait.SleepingWaitStrategy;
import reactor.fn.BiFunction;
import reactor.fn.Supplier;
import reactor.fn.timer.HashWheelTimer;
import reactor.fn.tuple.Tuple;
import reactor.fn.tuple.Tuple2;
import reactor.pipe.concurrent.LazyVar;
import reactor.pipe.consumer.KeyedConsumer;
import reactor.pipe.registry.ConcurrentRegistry;
import reactor.pipe.registry.Registration;
import reactor.pipe.registry.Registry;
import reactor.pipe.selector.Selector;
import reactor.pipe.stream.FirehoseSubscription;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongBinaryOperator;

import reactor.fn.Consumer;
import reactor.fn.Function;
import reactor.fn.Predicate;


public class Firehose<K> {

  private final static int                 DEFAULT_THREAD_POOL_SIZE   = 4;
  private final static int                 DEFAULT_RING_BUFFER_SIZE   = 65536;
  private final static Consumer<Throwable> DEFAULT_THROWABLE_CONSUMER = new Consumer<Throwable>() {
    @Override
    public void accept(Throwable throwable) {
      System.out.printf("Exception caught while dispatching: %s\n", throwable.getMessage());
      throwable.printStackTrace();
    }
  };

  private final Registry<K>                   consumerRegistry;
  private final Consumer<Throwable>           errorHandler;
  private final LazyVar<HashWheelTimer>       timer;
  private final Processor<Runnable, Runnable> processor;
  private final ThreadLocal<Boolean>          inDispatcherContext;
  private final FirehoseSubscription          firehoseSubscription;

  public Firehose() {
    this(new ConcurrentRegistry<K>(),
         RingBufferWorkProcessor.<Runnable>create(Executors.newFixedThreadPool(DEFAULT_THREAD_POOL_SIZE),
                                                  DEFAULT_RING_BUFFER_SIZE),
         DEFAULT_THREAD_POOL_SIZE,
         DEFAULT_THROWABLE_CONSUMER);
  }

  public Firehose(Consumer<Throwable> errorHandler) {
    this(new ConcurrentRegistry<K>(),
         RingBufferWorkProcessor.<Runnable>create(Executors.newFixedThreadPool(DEFAULT_THREAD_POOL_SIZE),
                                                  DEFAULT_RING_BUFFER_SIZE),
         DEFAULT_THREAD_POOL_SIZE,
         errorHandler);
  }

  public Firehose(Processor<Runnable, Runnable> processor,
                  int concurrency) {
    this(new ConcurrentRegistry<>(),
         processor,
         concurrency,
         DEFAULT_THROWABLE_CONSUMER);
  }

  public Firehose(Registry<K> registry,
                  Processor<Runnable, Runnable> processor,
                  int concurrency,
                  Consumer<Throwable> dispatchErrorHandler) {
    this.consumerRegistry = registry;
    this.errorHandler = dispatchErrorHandler;
    this.processor = processor;
    this.inDispatcherContext = new ThreadLocal<>();

    {
      for (int i = 0; i < concurrency; i++) {
        this.processor.subscribe(Subscribers.unbounded((Runnable runnable,
                                                        SubscriptionWithContext<Void> voidSubscriptionWithContext) -> {
                                                         runnable.run();
                                                       },
                                                       dispatchErrorHandler));
      }
      this.firehoseSubscription = new FirehoseSubscription();
      this.processor.onSubscribe(firehoseSubscription);
    }


    this.timer = new LazyVar<>(new Supplier<HashWheelTimer>() {
      @Override
      public HashWheelTimer get() {
        HashWheelTimer timer = new HashWheelTimer(10,
                                                  512,
                                                  new SleepingWaitStrategy());
        timer.start();
        return timer;
        // TODO: configurable hash wheel size!
      }
    });
  }

  public Firehose<K> fork(ExecutorService executorService,
                          int concurrency,
                          int ringBufferSize) {
    return new Firehose<K>(this.consumerRegistry,
                           RingBufferWorkProcessor.<Runnable>create(executorService, ringBufferSize),
                           concurrency,
                           this.errorHandler);
  }

  public <V> Firehose notify(final K key, final V ev) {
    Assert.notNull(key, "Key cannot be null.");
    Assert.notNull(ev, "Event cannot be null for key " + key.toString());

    // Backpressure
    while ((inDispatcherContext.get() == null || !inDispatcherContext.get()) &&
           !this.firehoseSubscription.maybeClaimSlot()) {
      try {
        //LockSupport.parkNanos(10000);
        Thread.sleep(500); // TODO: Obviously this is stupid use parknanos instead.
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }

    Boolean inContext = inDispatcherContext.get();
    if (inContext != null && inContext) {
      // Since we're already in the context, we can dispatch syncronously
      try {
        dispatch(key, ev);
      } catch (Throwable outer) {
        errorHandler.accept(outer);
      }
    } else {

      processor.onNext(() -> {
        try {
          inDispatcherContext.set(true);
          dispatch(key, ev);
        } catch (Throwable outer) {
          errorHandler.accept(new RuntimeException("Exception in key: " + key.toString(), outer));
        } finally {
          inDispatcherContext.set(false);
        }
      });
    }


    return this;
  }

  private <V> void dispatch(final K key, final V ev) {
    for (Registration<K> reg : consumerRegistry.select(key)) {
      try {
        reg.getObject().accept(key, ev);
      } catch (Throwable inner) {
        errorHandler.accept(inner);
      }
    }
  }

  public <V> Firehose on(final K key, final KeyedConsumer<K, V> consumer) {
    consumerRegistry.register(key, consumer);
    return this;
  }

  public <V> Firehose on(final K key, final Consumer<V> consumer) {
    consumerRegistry.register(key, new KeyedConsumer<K, V>() {
      @Override
      public void accept(final K key, final V value) {
        consumer.accept(value);
      }
    });
    return this;
  }

  public <V> Firehose<K> on(final Selector<K> matcher,
                            Consumer<V> consumer) {
    consumerRegistry.register(matcher, new Function<K, Map<K, KeyedConsumer>>() {
      @Override
      public Map<K, KeyedConsumer> apply(K k) {
        return Collections.singletonMap(k, new KeyedConsumer<K, V>() {
          @Override
          public void accept(K key, V value) {
            consumer.accept(value);
          }
        });
      }
    });
    return this;
  }

  public Firehose<K> on(final Selector<K> matcher,
                        Function<K, Map<K, KeyedConsumer>> supplier) {
    consumerRegistry.register(matcher, supplier);
    return this;
  }

  public boolean unregister(K key) {
    return consumerRegistry.unregister(key);
  }

  public boolean unregister(Predicate<K> pred) {
    return consumerRegistry.unregister(pred);
  }

  public <V> Registry<K> getConsumerRegistry() {
    return this.consumerRegistry;
  }

  public HashWheelTimer getTimer() {
    return this.timer.get();
  }

  public void shutdown() {
    processor.onComplete();
  }

  /**
   * Reactive Streams API
   */

  public <V> Subscriber<Tuple2<K, V>> makeSubscriber() {
    Firehose<K> ref = this;

    return new Subscriber<Tuple2<K, V>>() {
      private volatile Subscription subscription;

      @Override
      public void onSubscribe(Subscription subscription) {
        this.subscription = subscription;
        subscription.request(1L);
      }

      @Override
      public void onNext(Tuple2<K, V> tuple) {
        ref.notify(tuple.getT1(), tuple.getT2());
        subscription.request(1L);
      }

      @Override
      public void onError(Throwable throwable) {
        ref.errorHandler.accept(throwable);
      }

      @Override
      public void onComplete() {
        subscription.cancel();
      }
    };
  }

  public <V, K1 extends K> Subscriber<Tuple2<K, V>> makeSubscriber(BiFunction<K, V, K1> keyTransposition) {
    Firehose ref = this;

    return new Subscriber<Tuple2<K, V>>() {
      private volatile Subscription subscription;

      @Override
      public void onSubscribe(Subscription subscription) {
        this.subscription = subscription;
        subscription.request(1L);
      }

      @Override
      public void onNext(Tuple2<K, V> tuple) {
        ref.notify(keyTransposition.apply(tuple.getT1(), tuple.getT2()), tuple.getT2());
        subscription.request(1L);
      }

      @Override
      public void onError(Throwable throwable) {
        ref.errorHandler.accept(throwable);
      }

      @Override
      public void onComplete() {
        subscription.cancel();
      }
    };
  }

  public <V> Publisher<Tuple2<K, V>> makePublisher(K subscriptionKey) {
    Firehose<K> ref = this;


    return new Publisher<Tuple2<K, V>>() {
      @Override
      public void subscribe(Subscriber<? super Tuple2<K, V>> subscriber) {

        AtomicLong requested = new AtomicLong(0);

        Subscription subscription = new Subscription() {
          @Override
          public void request(long l) {
            if (l < 1) {
              throw new RuntimeException("Can't request a non-positive number");
            }

            requested.accumulateAndGet(l, new LongBinaryOperator() {
              @Override
              public long applyAsLong(long old, long diff) {
                long sum = old + diff;
                if (sum < 0 || sum == Long.MAX_VALUE) {
                  return Long.MAX_VALUE; // Effectively unbounded
                } else {
                  return sum;
                }
              }
            });
          }

          @Override
          public void cancel() {
            ref.unregister(subscriptionKey);
          }
        };

        subscriber.onSubscribe(subscription);

        ref.on(subscriptionKey, new Consumer<V>() {
          @Override
          public void accept(V value) {
            long r = requested.accumulateAndGet(-1, new LongBinaryOperator() {
              @Override
              public long applyAsLong(long old, long diff) {
                long sum = old + diff;

                if (old == Long.MAX_VALUE || sum <= 0) {
                  return Long.MAX_VALUE; // Effectively unbounded
                } else if (old == 1) {
                  return 0;
                } else {
                  return sum;
                }
              }
            });
            if (r >= 0) {
              subscriber.onNext(Tuple.of(subscriptionKey, value));
            }
          }
        });
      }
    };
  }
}
