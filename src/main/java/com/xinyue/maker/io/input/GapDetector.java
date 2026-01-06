package com.xinyue.maker.io.input;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xinyue.maker.common.CoreEvent;
import com.xinyue.maker.common.Exchange;
import com.xinyue.maker.core.lob.ILocalOrderBook;
import com.xinyue.maker.core.lob.LobManager;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 负责 Binance 参考盘口的缺口检测与本地订单簿初始化。
 * <p>
 * 目前仅对 Binance 的深度更新做 gap 检测，其他交易所（例如 dYdX）不在此处理。
 * <p>
 * 职责（仅针对 Binance）：
 * 1. 缓存 L2 中收到的 Binance depthUpdate 事件；
 * 2. 通过 REST 获取深度快照，直到 lastUpdateId 覆盖到第一条缓存事件的 U；
 * 3. 使用 {@link ILocalOrderBook} 应用快照 + 回放缓冲区事件；
 * 4. 将 bestBid/bestAsk 同步到 {@link LobManager} 的参考盘口快照。
 */
public final class GapDetector {

    private static final long SCALE_E8 = 100_000_000L;

    // 目前仅为一个 Binance 币对做 gap 检测（示例：GPSUSDT）。
    // 如需支持多币对，可扩展为按 symbolId 维护不同的 URL。
    private static final String BINANCE_DEPTH_URL =
            "https://api.binance.com/api/v3/depth?symbol=REDUSDT&limit=50";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final LobManager lobManager;

    /**
     * 仅用于「启动初始化 / gap 重建」阶段的 WS 增量缓冲。
     * <p>
     * 线程模型：
     * - 主线程（CoreEventHandler）持续 addLast
     * - 后台线程（binance-lob-bootstrap）pollFirst 回放
     * 因此必须使用线程安全容器；且回放不能使用 for-each + clear，否则会误删并发新增的事件。
     */
    private final Deque<DepthEvent> buffer = new ConcurrentLinkedDeque<>();

    /** 是否已经完成一次「快照 + 回放」对齐（后台线程会写，需可见性）。 */
    private volatile boolean initialized = false;
    /** 是否处于对齐阶段（启动 / gap 重建）（后台线程会写，需可见性）。 */
    private volatile boolean bootstrapping = false;
    /** 避免重复启动 bootstrap 线程（CAS 门闩）。 */
    private final AtomicBoolean bootstrapThreadStarted = new AtomicBoolean(false);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public GapDetector(LobManager lobManager, AccessLayerCoordinator coordinator) {
        this.lobManager = lobManager;
    }

    /**
     * 启动缺口检测与本地订单簿初始化流程。
     * <p>
     * 当前实现为阻塞式一次性初始化，后续可以扩展为定时检测/重建。
     */
    public void start() {
        // 目前不在启动时主动做快照，
        // 而是在检测到 Binance depthUpdate gap 时再触发快照对齐流程。
    }
    /**
     * 由核心层（CoreEventHandler）在消费到 DEPTH_UPDATE 类型的 CoreEvent 时调用。
     * <p>
     * 这是推荐的方式，因为事件已经通过 Disruptor 队列，在单线程核心层处理，避免并发问题。
     */
    /**
     * @return true 表示参考簿已经对齐完成，可以继续执行后续逻辑（例如策略层读取 bestBid/bestAsk）。
     *         false 表示仍在对齐/重建阶段，本次建议直接 return（不要用参考簿驱动策略）。
     */
    public boolean onDepthUpdateEvent(com.xinyue.maker.common.CoreEvent event) {
        final Exchange exchange = Exchange.BINANCE;
        final short symbolId = event.symbolId;
        final ILocalOrderBook localOrderBook = lobManager.getOrderBook(exchange, symbolId);

        final DepthEvent depthEvent = toDepthEvent(event);

        // 1) 启动 / 重建阶段：只缓冲，等待后台线程完成「快照 + 回放」对齐
        maybeBuffer(depthEvent);

        // 启动阶段：本地簿未对齐 -> 起 bootstrap 线程并返回（不做增量 apply）
        if (!initialized) {
            ensureBootstrapStarted(exchange, symbolId);
            return false;
        }

        // bootstrap 结束到下一条事件之间，buffer 可能残留（竞态窗口新增）
        // 在进入正常增量 apply 前先 drain 一次，避免丢事件/顺序错位
        if (!bootstrapping) drainIfNeeded(exchange, symbolId);

        // 正常运行阶段：增量 apply（若返回 false 表示发现 gap）
        final boolean ok = localOrderBook.applyEvent(
                depthEvent.firstUpdateId,
                depthEvent.lastUpdateId,
                depthEvent.bidPricesE8, depthEvent.bidQtysE8, depthEvent.bidCount,
                depthEvent.askPricesE8, depthEvent.askQtysE8, depthEvent.askCount
        );

        if (!ok) {
            onIncrementalGap(exchange, symbolId, localOrderBook, depthEvent);
            return false;
        }

        // 同步 bestBid/bestAsk 到 LobManager
        lobManager.syncFromLocalOrderBook(exchange, symbolId, localOrderBook);
        return true;
    }


    public void onTestDepthUpdateEvent(CoreEvent event) {
        final Exchange exchange = Exchange.Test;
        final short symbolId = event.symbolId;
        final ILocalOrderBook localOrderBook = lobManager.getOrderBook(exchange, symbolId);

        final DepthEvent depthEvent = toDepthEvent(event);
        // 正常运行阶段：增量 apply（若返回 false 表示发现 gap）
        final boolean ok = localOrderBook.applyTestEvent(
                depthEvent.firstUpdateId,
                depthEvent.lastUpdateId,
                depthEvent.bidPricesE8, depthEvent.bidQtysE8, depthEvent.bidCount,
                depthEvent.askPricesE8, depthEvent.askQtysE8, depthEvent.askCount
        );
        // 同步 bestBid/bestAsk 到 LobManager
        lobManager.syncFromLocalOrderBook(exchange, symbolId, localOrderBook);
    }

    /**
     * 由上游（如 WebSocket 处理器）在收到 depthUpdate JSON 时调用（保留用于向后兼容）。
     * <p>
     * 注意：推荐使用 {@link #onDepthUpdateEvent(com.xinyue.maker.common.CoreEvent)}，
     * 因为事件已经通过 Disruptor 队列，在单线程核心层处理。
     */
    @Deprecated
    public void onDepthUpdateJson(byte[] payload) {
        // 旧的 JSON 直连路径已废弃，当前仅通过 CoreEvent 在 L2 中调用 onDepthUpdateEvent。
    }

    /**
     * 根据 orderbook.txt 的流程：
     * 1. 缓冲 WS 事件并记录第一条 U；
     * 2. 通过 REST 获取深度快照，直到 lastUpdateId >= 第一条 U；
     * 3. 丢弃所有 u <= lastUpdateId 的缓冲事件；
     * 4. 将快照应用到 LocalOrderBook，并依次回放缓冲事件；
     * 5. 同步 bestBid/bestAsk 到 LobManager。
     */
     /**
      * @return true 表示对齐成功（可以切到增量），false 表示对齐失败（保持 initialized=false，等待后续重试）
      */
    /**
     * 后台线程执行：REST 拉快照 + 回放 buffer，对齐本地簿。
     *
     * @return true 对齐成功（允许切到增量），false 对齐失败（保持 initialized=false，等待后续重试）
     */
    private boolean bootstrapOrderBookFromRestAndBuffer(Exchange exchange, short symbolId) throws IOException, InterruptedException {
        DepthEvent firstEvent = buffer.peekFirst();
        if (firstEvent == null) return false;
        long firstU = firstEvent.firstUpdateId;

        long lastUpdateId;
        Snapshot snapshot;

        // 1) REST 拉快照，直到 lastUpdateId 覆盖到第一条缓冲事件的 U
        while (true) {
            snapshot = fetchSnapshot();
            lastUpdateId = snapshot.lastUpdateId;
            if (lastUpdateId >= firstU) {
                break;
            }
        }

        // 2) 丢弃所有 u <= snapshot.lastUpdateId 的缓冲事件（Binance 官方流程）
        while (!buffer.isEmpty() && buffer.peekFirst().lastUpdateId <= lastUpdateId) {
            buffer.pollFirst();
        }

        // 3) 应用快照（重置并覆盖本地簿）
        ILocalOrderBook localOrderBook = lobManager.getOrderBook(exchange, symbolId);
        localOrderBook.applySnapshot(
                snapshot.lastUpdateId,
                snapshot.bidPricesE8, snapshot.bidQtysE8, snapshot.bidCount,
                snapshot.askPricesE8, snapshot.askQtysE8, snapshot.askCount
        );

        // 4) 回放缓冲区事件：必须 pollFirst() 消费，避免并发 addLast + clear 误删
        while (true) {
            DepthEvent event = buffer.pollFirst();
            if (event == null) {
                break;
            }
            boolean ok = localOrderBook.applyEvent(
                    event.firstUpdateId,
                    event.lastUpdateId,
                    event.bidPricesE8, event.bidQtysE8, event.bidCount,
                    event.askPricesE8, event.askQtysE8, event.askCount
            );
            if (!ok) {
                // 中途发现 gap，则放弃当前流程，等待新的缓冲与重建
                localOrderBook.reset();
                return false;
            }
        }

        // 5. 同步 bestBid/bestAsk 到 LobManager
        lobManager.syncFromLocalOrderBook(exchange, symbolId, localOrderBook);
        return true;
    }

    /**
     * 在切回正常增量路径前，把残留的 buffer 再回放一次。
     * 只会在 initialized=true && bootstrapping=false 的场景触发，属于小概率竞态兜底。
     */
    private void drainBufferIntoOrderBook(Exchange exchange, short symbolId) {
        ILocalOrderBook localOrderBook = lobManager.getOrderBook(exchange, symbolId);
        while (true) {
            DepthEvent e = buffer.pollFirst();
            if (e == null) {
                return;
            }
            boolean ok = localOrderBook.applyEvent(
                    e.firstUpdateId,
                    e.lastUpdateId,
                    e.bidPricesE8, e.bidQtysE8, e.bidCount,
                    e.askPricesE8, e.askQtysE8, e.askCount
            );
            if (!ok) {
                // 如果这里又发现 gap，说明竞态窗口里发生了不连续事件：直接触发重建
                localOrderBook.reset();
                buffer.clear();
                buffer.addLast(e);
                initialized = false;
                bootstrapping = true;
                startBootstrapThread(exchange, symbolId);
                return;
            }
        }
    }

    /**
     * 简化版：直接起一个后台线程跑 bootstrap，主线程继续往 buffer 加事件。
     * 对齐完成后后台线程负责把 initialized/bootstrapping 状态翻转。
     */
    private void startBootstrapThread(Exchange exchange, short symbolId) {
        if (!bootstrapThreadStarted.compareAndSet(false, true)) {
            return;
        }
        //todo  改成线程池
        Thread t = new Thread(() -> {
            try {
                boolean ok = bootstrapOrderBookFromRestAndBuffer(exchange, symbolId);
                // 只有对齐成功才切到增量逻辑
                initialized = ok;
            } catch (Throwable e) {
                System.err.println("bootstrap 本地订单簿失败: " + e.getMessage());
                // 失败时允许后续事件再次触发重试
                initialized = false;
            } finally {
                bootstrapping = false;
                bootstrapThreadStarted.set(false);
            }
        }, "binance-lob-bootstrap");
        t.setDaemon(true);
        t.start();
    }

    private boolean isBinance(short exchangeId) {
        return exchangeId == Exchange.BINANCE.id();
    }

    private DepthEvent toDepthEvent(com.xinyue.maker.common.CoreEvent event) {
        final int[] counts = computeBidAskCount(event);
        final int bidCount = counts[0];
        final int askCount = counts[1];
        return new DepthEvent(
                event.firstUpdateId,
                event.sequence, // u
                event.bidPrices, event.bidQtys, bidCount,
                event.askPrices, event.askQtys, askCount
        );
    }

    private int[] computeBidAskCount(com.xinyue.maker.common.CoreEvent event) {
        int bidCount = 0;
        int askCount = 0;
        final int max = Math.min(event.depthCount, com.xinyue.maker.common.CoreEvent.MAX_DEPTH);
        for (int i = 0; i < max; i++) {
            if (event.bidPrices[i] != 0 || event.bidQtys[i] != 0) bidCount = i + 1;
            if (event.askPrices[i] != 0 || event.askQtys[i] != 0) askCount = i + 1;
        }
        return new int[]{bidCount, askCount};
    }

    private void maybeBuffer(DepthEvent depthEvent) {
        if (!initialized || bootstrapping) {
            buffer.addLast(depthEvent);
        }
    }

    private void ensureBootstrapStarted(Exchange exchange, short symbolId) {
        if (!bootstrapping) {
            bootstrapping = true;
            startBootstrapThread(exchange, symbolId);
        }
    }

    private void drainIfNeeded(Exchange exchange, short symbolId) {
        if (!buffer.isEmpty()) {
            drainBufferIntoOrderBook(exchange, symbolId);
        }
    }

    private void onIncrementalGap(Exchange exchange, short symbolId, ILocalOrderBook localOrderBook, DepthEvent currentEvent) {
        // 发现 gap：本地簿不可信，进入重建流程
        localOrderBook.reset();
        buffer.clear();
        buffer.addLast(currentEvent);
        initialized = false;
        bootstrapping = true;
        startBootstrapThread(exchange, symbolId);
    }

    private Snapshot fetchSnapshot() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BINANCE_DEPTH_URL))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("调用 Binance depth 接口失败, status=" + response.statusCode());
        }

        JsonNode root = OBJECT_MAPPER.readTree(response.body());
        long lastUpdateId = root.path("lastUpdateId").asLong();

        // 解析 bids/asks 数组
        JsonNode bids = root.path("bids");
        JsonNode asks = root.path("asks");

        int bidCount = bids.size();
        int askCount = asks.size();

        long[] bidPricesE8 = new long[bidCount];
        long[] bidQtysE8 = new long[bidCount];
        long[] askPricesE8 = new long[askCount];
        long[] askQtysE8 = new long[askCount];

        for (int i = 0; i < bidCount; i++) {
            JsonNode level = bids.get(i);
            String priceStr = level.get(0).asText();
            String qtyStr = level.get(1).asText();
            bidPricesE8[i] = parseDecimal(priceStr);
            bidQtysE8[i] = parseDecimal(qtyStr);
        }

        for (int i = 0; i < askCount; i++) {
            JsonNode level = asks.get(i);
            String priceStr = level.get(0).asText();
            String qtyStr = level.get(1).asText();
            askPricesE8[i] = parseDecimal(priceStr);
            askQtysE8[i] = parseDecimal(qtyStr);
        }

        return new Snapshot(lastUpdateId,
                bidPricesE8, bidQtysE8, bidCount,
                askPricesE8, askQtysE8, askCount);
    }

    /**
     * 将十进制字符串转换为 long（放大 1e8）。
     */
    private long parseDecimal(String decimalStr) {
        if (decimalStr == null || decimalStr.isEmpty()) {
            return 0L;
        }
        int dotIndex = decimalStr.indexOf('.');
        if (dotIndex == -1) {
            long integerPart = Long.parseLong(decimalStr);
            return integerPart * SCALE_E8;
        }
        String integerPartStr = decimalStr.substring(0, dotIndex);
        String fractionalPartStr = decimalStr.substring(dotIndex + 1);
        long integerPart = integerPartStr.isEmpty() ? 0L : Long.parseLong(integerPartStr);
        if (fractionalPartStr.isEmpty()) {
            return integerPart * SCALE_E8;
        }
        int fractionalDigits = fractionalPartStr.length();
        if (fractionalDigits > 8) {
            fractionalPartStr = fractionalPartStr.substring(0, 8);
            fractionalDigits = 8;
        }
        long fractionalPart = Long.parseLong(fractionalPartStr);
        long scale = (long) Math.pow(10, 8 - fractionalDigits);
        return integerPart * SCALE_E8 + fractionalPart * scale;
    }


    private record DepthEvent(
            long firstUpdateId,
            long lastUpdateId,
            long[] bidPricesE8,
            long[] bidQtysE8,
            int bidCount,
            long[] askPricesE8,
            long[] askQtysE8,
            int askCount
    ) {
    }

    private record Snapshot(
            long lastUpdateId,
            long[] bidPricesE8,
            long[] bidQtysE8,
            int bidCount,
            long[] askPricesE8,
            long[] askQtysE8,
            int askCount
    ) {
    }
}

