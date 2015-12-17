package reactor.pipe;

import org.pcollections.PVector;
import org.pcollections.TreePVector;
import reactor.fn.*;
import reactor.pipe.concurrent.Atom;
import reactor.pipe.consumer.KeyedConsumer;
import reactor.pipe.key.Key;
import reactor.pipe.operation.PartitionOperation;
import reactor.pipe.operation.SlidingWindowOperation;
import reactor.pipe.state.DefaultStateProvider;
import reactor.pipe.state.StateProvider;
import reactor.pipe.stream.StreamSupplier;
import reactor.core.support.ReactiveState.Pausable;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class Pipe<INIT, CURRENT> implements IPipe<INIT, CURRENT> {

  private final StateProvider<Key>      stateProvider;
  private final PVector<StreamSupplier> suppliers;

  protected Pipe() {
    this(TreePVector.empty(), new DefaultStateProvider<>());
  }

  protected Pipe(PVector<StreamSupplier> suppliers,
                 StateProvider<Key> stateProvider) {
    this.suppliers = suppliers;
    this.stateProvider = stateProvider;
  }

  @SuppressWarnings(value = {"unchecked"})
  public <NEXT> IPipe<INIT, NEXT> map(Function<CURRENT, NEXT> mapper) {
    return next(new StreamSupplier<Key, CURRENT>() {
      @Override
      public KeyedConsumer<Key, CURRENT> get(Key src,
                                             Key dst,
                                             Firehose firehose) {
        return (key, value) -> {
          firehose.notify(dst.clone(key), mapper.apply(value));
        };
      }
    });
  }

  @SuppressWarnings(value = {"unchecked"})
  public <NEXT> IPipe<INIT, NEXT> map(Supplier<Function<CURRENT, NEXT>> supplier) {
    return next(new StreamSupplier<Key, CURRENT>() {
      @Override
      public KeyedConsumer<Key, CURRENT> get(Key src,
                                             Key dst,
                                             Firehose firehose) {
        Function<CURRENT, NEXT> mapper = supplier.get();
        return (key, value) -> {
          firehose.notify(dst.clone(key), mapper.apply(value));
        };
      }
    });
  }

  @SuppressWarnings(value = {"unchecked"})
  public <ST, NEXT> IPipe<INIT, NEXT> map(BiFunction<Atom<ST>, CURRENT, NEXT> mapper,
                                          ST init) {
    return next(new StreamSupplier<Key, CURRENT>() {
      @Override
      public KeyedConsumer<Key, CURRENT> get(Key src,
                                             Key dst,
                                             Firehose firehose) {
        Atom<ST> st = stateProvider.makeAtom(src, init);

        return (key, value) -> {
          firehose.notify(dst.clone(key), mapper.apply(st, value));
        };
      }
    });
  }

  @SuppressWarnings(value = {"unchecked"})
  public <ST> IPipe<INIT, ST> scan(BiFunction<ST, CURRENT, ST> mapper,
                                   ST init) {
    return next(new StreamSupplier<Key, CURRENT>() {
      @Override
      public KeyedConsumer<Key, CURRENT> get(Key src,
                                             Key dst,
                                             Firehose firehose) {
        Atom<ST> st = stateProvider.makeAtom(src, init);

        return (key, value) -> {
          ST newSt = st.update((old) -> mapper.apply(old, value));
          firehose.notify(dst.clone(key), newSt);
        };
      }
    });
  }

  @Override
  public IPipe<INIT, CURRENT> debounce(long period, TimeUnit timeUnit) {
    return next(new StreamSupplier<Key, CURRENT>() {
      @Override
      public KeyedConsumer<Key, CURRENT> get(Key src, Key dst, Firehose firehose) {
        final Atom<CURRENT> debounced = stateProvider.makeAtom(src, null);
        final AtomicReference<Pausable> pausable = new AtomicReference<>(null);

        return (key, value) -> {
          debounced.update(current -> value);

          if (pausable.get() == null) {
            pausable.set(
              firehose.getTimer().submit(new Consumer<Long>() {
                @Override
                public void accept(Long v) {
                  firehose.notify(dst, debounced.deref());
                  pausable.set(null);
                }
              }, period, timeUnit));
          }
        };
      }
    });
  }

  @Override
  public IPipe<INIT, CURRENT> throttle(long period, TimeUnit timeUnit) {
    return next(new StreamSupplier<Key, CURRENT>() {
      @Override
      public KeyedConsumer<Key, CURRENT> get(Key src, Key dst, Firehose firehose) {
        final Atom<CURRENT> debounced = stateProvider.makeAtom(src, null);
        final AtomicReference<Pausable> pausable = new AtomicReference<>(null);

        return (key, value) -> {
          Pausable oldScheduled = pausable.getAndUpdate((p) -> null);
          if (oldScheduled != null) {
            oldScheduled.cancel();
          }

          debounced.update(current -> value);

          pausable.set(firehose.getTimer().submit(new Consumer<Long>() {
            @Override
            public void accept(Long v) {
              firehose.notify(dst, debounced.deref());
              pausable.set(null);
            }
          }, period, timeUnit));
        };
      }
    });
  }

  @SuppressWarnings(value = {"unchecked"})
  public IPipe<INIT, CURRENT> filter(Predicate<CURRENT> predicate) {
    return next(new StreamSupplier<Key, CURRENT>() {
      @Override
      public KeyedConsumer<Key, CURRENT> get(Key src,
                                             Key dst,
                                             Firehose firehose) {
        return (key, value) -> {
          if (predicate.test(value)) {
            firehose.notify(dst.clone(key), value);
          }
        };
      }
    });
  }

  @SuppressWarnings(value = {"unchecked"})
  public Pipe<INIT, List<CURRENT>> slide(UnaryOperator<List<CURRENT>> drop) {
    return next(new StreamSupplier<Key, CURRENT>() {
      @Override
      public KeyedConsumer<Key, CURRENT> get(Key src,
                                             Key dst,
                                             Firehose firehose) {
        Atom<PVector<CURRENT>> buffer = stateProvider.makeAtom(src, TreePVector.empty());

        return new SlidingWindowOperation<>(firehose,
                                            buffer,
                                            drop,
                                            dst);
      }
    });
  }

  @SuppressWarnings(value = {"unchecked"})
  public IPipe<INIT, List<CURRENT>> partition(Predicate<List<CURRENT>> emit) {
    return next(new StreamSupplier<Key, CURRENT>() {
      @Override
      public KeyedConsumer<Key, CURRENT> get(Key src,
                                             Key dst,
                                             Firehose firehose) {
        Atom<PVector<CURRENT>> buffer = stateProvider.makeAtom(dst, TreePVector.empty());

        return new PartitionOperation<>(firehose,
                                        buffer,
                                        emit,
                                        dst);
      }
    });
  }

  /**
   * STREAM ENDS
   */

  @SuppressWarnings(value = {"unchecked"})
  public <SRC extends Key> PipeEnd consume(KeyedConsumer<SRC, CURRENT> consumer) {
    return end(new StreamSupplier<SRC, CURRENT>() {
      @Override
      public KeyedConsumer<SRC, CURRENT> get(Key src,
                                             Key dst,
                                             Firehose pipe) {
        return consumer;
      }

    });
  }


  @SuppressWarnings(value = {"unchecked"})
  public PipeEnd consume(Consumer<CURRENT> consumer) {
    return end(new StreamSupplier<Key, CURRENT>() {
      @Override
      public KeyedConsumer<Key, CURRENT> get(Key src,
                                             Key dst,
                                             Firehose pipe) {
        return (key, value) -> consumer.accept(value);
      }
    });
  }


  @SuppressWarnings(value = {"unchecked"})
  public <SRC extends Key> PipeEnd consume(Supplier<KeyedConsumer<SRC, CURRENT>> supplier) {
    return end(new StreamSupplier<SRC, CURRENT>() {
      @Override
      public KeyedConsumer<SRC, CURRENT> get(SRC src,
                                             Key dst,
                                             Firehose pipe) {
        return supplier.get();
      }

    });
  }

  public static <A> IPipe<A, A> build() {
    return new Pipe<>();
  }

  protected <NEXT> Pipe<INIT, NEXT> next(StreamSupplier supplier) {
    return new Pipe<>(suppliers.plus(supplier),
                      stateProvider);
  }

  protected <NEXT> reactor.pipe.PipeEnd<INIT, NEXT> end(StreamSupplier supplier) {
    return new reactor.pipe.PipeEnd<>(suppliers.plus(supplier));
  }

}
