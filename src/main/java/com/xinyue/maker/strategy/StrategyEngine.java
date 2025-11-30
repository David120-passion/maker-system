package com.xinyue.maker.strategy;

import com.xinyue.maker.common.CoreEvent;
import com.xinyue.maker.core.lob.OrderBookSnapshot;

public final class StrategyEngine {

    private final SignalGenerator signalGenerator;
    private final ExecutionRouter executionRouter;
    private final RiskEngine riskEngine;
    private volatile boolean killSwitch;

    public StrategyEngine(SignalGenerator signalGenerator,
                          ExecutionRouter executionRouter,
                          RiskEngine riskEngine) {
        this.signalGenerator = signalGenerator;
        this.executionRouter = executionRouter;
        this.riskEngine = riskEngine;
    }

    public void onMarketData(CoreEvent event, OrderBookSnapshot snapshot) {
        if (killSwitch) {
            return;
        }
        SignalGenerator.SignalResult quote = signalGenerator.generate(snapshot);
        executionRouter.onQuote(event, quote);
    }

    public void onExecution(CoreEvent event) {
        executionRouter.onExecution(event);
    }

    public void onCommand(CoreEvent event) {
        executionRouter.onCommand(event);
    }

    public void onConfig(CoreEvent event) {
        // TODO 解析配置更新载荷
    }

    public void onTimer(CoreEvent event) {
        riskEngine.onTimer(event);
    }

    public void killSwitch() {
        killSwitch = true;
    }
}

