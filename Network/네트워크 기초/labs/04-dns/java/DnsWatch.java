import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.Security;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * 2초마다 같은 이름을 InetAddress로 조회해 결과와 걸린 시간을 출력한다.
 * JVM InetAddress 캐시가 언제 OS 리졸버에 다시 묻는지(= 걸린 시간이 늘어나는 순간)를 관찰한다.
 *
 * 실행 (JDK 11+ 단일 파일 실행):
 *   java DnsWatch.java api.lab.internal
 *   java -Dsun.net.inetaddr.ttl=5 DnsWatch.java api.lab.internal
 *
 * Java 8 문법만 사용했다. Java 8에서는 javac DnsWatch.java 후 java DnsWatch api.lab.internal 로 실행한다.
 */
public class DnsWatch {

    public static void main(String[] args) throws InterruptedException {
        String host = args.length > 0 ? args[0] : "api.lab.internal";

        // null이면 JVM 기본값을 쓴다: 성공 30초(SecurityManager가 없을 때), 실패 10초(java.security 파일 기본)
        System.out.println("security  networkaddress.cache.ttl          = " + Security.getProperty("networkaddress.cache.ttl"));
        System.out.println("security  networkaddress.cache.negative.ttl = " + Security.getProperty("networkaddress.cache.negative.ttl"));
        System.out.println("system    sun.net.inetaddr.ttl              = " + System.getProperty("sun.net.inetaddr.ttl"));
        System.out.println("java.version                                = " + System.getProperty("java.version"));

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("HH:mm:ss");
        while (true) {
            long start = System.nanoTime();
            String result;
            try {
                InetAddress[] addrs = InetAddress.getAllByName(host);
                result = Arrays.stream(addrs)
                        .map(InetAddress::getHostAddress)
                        .collect(Collectors.joining(", "));
            } catch (UnknownHostException e) {
                result = "UnknownHostException: " + e.getMessage();
            }
            long micros = (System.nanoTime() - start) / 1_000;
            // 캐시 hit는 수십 us, OS 리졸버까지 다녀오면 수백 us~수 ms (us = 마이크로초. 컨테이너 로캘 문제로 µ 대신 사용)
            System.out.printf("[%s] %s -> %s  (%,d us)%n", LocalTime.now().format(fmt), host, result, micros);
            Thread.sleep(2000);
        }
    }
}
