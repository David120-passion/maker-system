package http;

import cn.hutool.http.HttpRequest;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;

public class ProxyTest {
    static void main() {
        System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
        Authenticator.setDefault(
                new Authenticator() {
                    @Override
                    public PasswordAuthentication getPasswordAuthentication() {
                        return new PasswordAuthentication("1evu3070-region-Rand-sid-WUB4H2t4-t-5", "xrqsnrdu".toCharArray());
                    }
                }
        );
        String result2 = HttpRequest.get("\n" +
                        "\n" +
                        "https://api.ipify.org/")
                .setProxy(new Proxy(Proxy.Type.HTTP,
                        new InetSocketAddress("us.1024proxy.io", 3000))).execute().body();
        System.out.println(result2);
    }
}
