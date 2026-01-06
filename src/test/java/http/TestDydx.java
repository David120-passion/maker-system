package http;

import com.xinyue.maker.common.Exchange;
import com.xinyue.maker.common.OrderCommand;
import com.xinyue.maker.core.oms.OrderManagementSystem;
import com.xinyue.maker.core.position.PositionManager;
import com.xinyue.maker.infra.MetricsService;
import com.xinyue.maker.infra.PersistenceDispatcher;
import com.xinyue.maker.io.output.DydxConnector;
import com.xinyue.maker.io.output.ExecutionGatewayManager;
import com.xinyue.maker.io.output.NettySidecarGateway;
import com.xinyue.maker.io.output.TradeSession;
import org.agrona.collections.Int2ObjectHashMap;
import org.agrona.collections.IntArrayList;

import java.io.IOException;

/**
 * 测试 dYdX gRPC 客户端（AllBalances 查询）。
 */
public class TestDydx {


//    public static void main(String[] args) throws IOException, InterruptedException {
//        ObjectMapper objectMapper = new ObjectMapper();
//        HttpClient httpClient = HttpClient.newBuilder()
//                .connectTimeout(Duration.ofSeconds(10))
//                .build();
////        Map<String, Object> requestBody = new HashMap<>();
////        requestBody.put("owner", "h21lmflgzs766v7syh44j2fe286f75auxt6xgx0mt");
////        requestBody.put("number", 0);
//        HttpRequest request = HttpRequest.newBuilder()
//                .uri(URI.create("https://dydx1.forcast.money/v4/addresses/h21lmflgzs766v7syh44j2fe286f75auxt6xgx0mt/subaccountNumber/0"))
//                .header("Content-Type", "application/json")
//                .GET()
//                .timeout(Duration.ofSeconds(30))
//                .build();
//
//        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
//        System.out.println(response.body());
//    }


    public static void main(String[] args) throws IOException, InterruptedException {
        // 初始化 dYdX 账户资产（从 REST API 同步）
        try {

            Int2ObjectHashMap<TradeSession> dydxSessionPool = new Int2ObjectHashMap<>();
            // TODO: 根据配置初始化实际的账户会话
            // for (int accountId : configuredAccountIds) {
            //     dydxSessionPool.put(accountId, new TradeSession((short) accountId));
            // }
            DydxConnector dydxSidecarConnector = new DydxConnector("ws://127.0.0.1:8080");
            dydxSidecarConnector.start();

            // 创建 dYdX 的 ExecutionGateway
            NettySidecarGateway dydxGateway = new NettySidecarGateway(
                    dydxSidecarConnector.getChannel(),
                    dydxSessionPool,
                    Exchange.DYDX
            );

            // 注册到 ExecutionGatewayManager
            ExecutionGatewayManager gatewayManager = new ExecutionGatewayManager()
                    .register(Exchange.DYDX, dydxGateway);

            // 创建 OMS，传入 gatewayManager
            OrderManagementSystem oms = new OrderManagementSystem(new MetricsService(), new PersistenceDispatcher(), gatewayManager,new PositionManager(null));
            int accountId = 1; // 内部账户 ID
            String accountName = "dYdX_Main";
            String mnemonicPhrase = "mobile viable ridge unfair black type retire tide select unveil insect panther document ridge destroy battle walk this butter business belt broom damage phone";
            String address = "h21lmflgzs766v7syh44j2fe286f75auxt6xgx0mt";
            int subaccountNumber = 0;
            dydxGateway.initializeSession(accountId,accountName,mnemonicPhrase);
//            IntArrayList accountsByBalance = positionManager.getAccountsByBalance(1, Long.valueOf("50").longValue());
            OrderCommand orderCommand = new OrderCommand();
//            orderCommand.internalOrderId=123;
            orderCommand.accountId=1;
            orderCommand.symbolId=3;
            orderCommand.exchangeId=2;
            orderCommand.priceE8=100;
            orderCommand.qtyE8=1;
            orderCommand.side=0;
            orderCommand.exchangeId=Exchange.DYDX.id();
            oms.submitOrder(orderCommand);
            System.out.println("send succeed");
        } catch (Exception e) {
            System.err.println("dYdX account asset init fail: " + e.getMessage());
            e.printStackTrace();
            // 根据业务需求决定是否继续启动或退出
        }
    }
}
