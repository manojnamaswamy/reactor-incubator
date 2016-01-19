/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
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

package reactor.io.net.impl.zmq.tcp;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zeromq.ZContext;
import org.zeromq.ZFrame;
import org.zeromq.ZMQ;
import org.zeromq.ZMsg;
import reactor.core.publisher.Flux;
import reactor.core.subscriber.BaseSubscriber;
import reactor.core.support.NamedDaemonThreadFactory;
import reactor.core.support.UUIDUtils;
import reactor.core.timer.Timer;
import reactor.fn.Consumer;
import reactor.fn.tuple.Tuple2;
import reactor.io.buffer.Buffer;
import reactor.io.net.ReactiveChannel;
import reactor.io.net.ReactiveChannelHandler;
import reactor.io.net.Reconnect;
import reactor.io.net.config.ClientSocketOptions;
import reactor.io.net.config.SslOptions;
import reactor.io.net.impl.zmq.ZeroMQClientSocketOptions;
import reactor.io.net.tcp.TcpClient;
import reactor.rx.Promise;
import reactor.rx.broadcast.Broadcaster;

/**
 * @author Jon Brisbin
 * @author Stephane Maldini
 */
public class ZeroMQTcpClient extends TcpClient<Buffer, Buffer> {

	private final static Logger log = LoggerFactory.getLogger(ZeroMQTcpClient.class);

	public static final int DEFAULT_ZMQ_THREAD_COUNT = Integer.parseInt(
			System.getProperty("reactor.zmq.ioThreadCount", "1")
	);

	private final int                       ioThreadCount;
	private final ZeroMQClientSocketOptions zmqOpts;
	private final ExecutorService           threadPool;

	public ZeroMQTcpClient(Timer timer,
			InetSocketAddress connectAddress,
			ClientSocketOptions options,
			SslOptions sslOptions) {
		super(timer, connectAddress, options == null ? new ClientSocketOptions() : options, sslOptions);

		this.ioThreadCount = DEFAULT_ZMQ_THREAD_COUNT;

		if (options instanceof ZeroMQClientSocketOptions) {
			this.zmqOpts = (ZeroMQClientSocketOptions) options;
		} else {
			this.zmqOpts = null;
		}

		this.threadPool = Executors.newCachedThreadPool(new NamedDaemonThreadFactory("zmq-client"));
	}

	@Override
	protected Flux<Tuple2<InetSocketAddress, Integer>> doStart(ReactiveChannelHandler handler, Reconnect reconnect) {
		throw new IllegalStateException("Reconnects are handled transparently by the ZeroMQ network library");
	}

	@Override
	protected Promise<Void> doShutdown() {
		final Promise<Void> promise = Promise.ready();

		threadPool.shutdownNow();
		promise.onComplete();

		return promise;
	}

	protected ZeroMQChannel bindChannel() {

		return new ZeroMQChannel(
				getConnectAddress()
		);
	}

	@Override
	protected Promise<Void> doStart(final ReactiveChannelHandler<Buffer, Buffer, ReactiveChannel<Buffer, Buffer>> handler) {
		final UUID id = UUIDUtils.random();

		final Promise<Void> p = Promise.ready();

		final int socketType = (null != zmqOpts ? zmqOpts.socketType() : ZMQ.DEALER);
		final ZContext zmq = (null != zmqOpts ? zmqOpts.context() : null);

		final Broadcaster<ZMsg> broadcaster = Broadcaster.serialize(getDefaultTimer());

		ZeroMQWorker worker = new ZeroMQWorker(id, socketType, ioThreadCount, zmq, broadcaster) {
			@Override
			protected void configure(ZMQ.Socket socket) {
				socket.setReceiveBufferSize(getOptions().rcvbuf());
				socket.setSendBufferSize(getOptions().sndbuf());
				if (getOptions().keepAlive()) {
					socket.setTCPKeepAlive(1);
				}
				if (null != zmqOpts && null != zmqOpts.socketConfigurer()) {
					zmqOpts.socketConfigurer().accept(socket);
				}
			}

			@Override
			@SuppressWarnings("unchecked")
			protected void start(final ZMQ.Socket socket) {
				try {
					String addr = createConnectAddress();
					if (log.isInfoEnabled()) {
						String type = ZeroMQ.findSocketTypeName(socket.getType());
						log.info("CONNECT: connecting ZeroMQ {} socket to {}", type, addr);
					}

					socket.connect(addr);

					final ZeroMQChannel netChannel =
							bindChannel()
									.setConnectionId(id.toString())
									.setSocket(socket);

					handler.apply(netChannel).subscribe(new BaseSubscriber<Void>() {
						@Override
						public void onSubscribe(Subscription s) {
							s.request(Long.MAX_VALUE);
						}

						@Override
						public void onComplete() {
							log.debug("Closing Client Worker " + id);
							netChannel.close();
						}

						@Override
						public void onError(Throwable t) {
							log.error("Error during registration", t);
							netChannel.close();
						}
					});

					broadcaster.consume(new Consumer<ZMsg>() {
						@Override
						public void accept(ZMsg msg) {
							ZFrame content;
							while (null != (content = msg.pop())) {
								netChannel.inputSub.onNext(Buffer.wrap(content.getData()));
							}
							msg.destroy();
						}
					});

					p.onComplete();
				} catch (Exception e) {
					p.onError(e);
				}
			}
		};
		threadPool.submit(worker);
		return p;
	}

	private String createConnectAddress() {
		String addrs;
		if (null != zmqOpts && null != zmqOpts.connectAddresses()) {
			addrs = zmqOpts.connectAddresses();
		} else {
			addrs = "tcp://" + getConnectAddress().getHostString() + ":" + getConnectAddress().getPort();
		}
		return addrs;
	}

}