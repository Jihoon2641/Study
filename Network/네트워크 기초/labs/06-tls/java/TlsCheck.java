import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;

import javax.net.ssl.SSLSession;

/**
 * 지정한 HTTPS URL에 접속해서 협상 결과와 서버가 보낸 인증서 체인을 출력한다.
 * 실패하면 예외 원인 체인(Caused by)을 끝까지 출력한다. TLS 오류는 맨 안쪽 원인이 핵심이다.
 *
 * 실행 (JDK 11+ 단일 파일 실행):
 *   java TlsCheck.java https://web:8444/
 *   java -Djavax.net.ssl.trustStore=/tmp/truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit TlsCheck.java https://web:8444/
 *   java -Djavax.net.debug=ssl:handshake TlsCheck.java https://web:8444/   # 핸드셰이크 상세 로그
 *
 * 문법은 Java 8 호환이지만 java.net.http.HttpClient는 Java 11부터 제공된다.
 * Java 8이라면 HttpsURLConnection으로 같은 검증 동작을 확인할 수 있다.
 */
public class TlsCheck {

    public static void main(String[] args) {
        String url = args.length > 0 ? args[0] : "https://web:8444/";
        System.out.println("target      = " + url);
        System.out.println("trustStore  = " + System.getProperty("javax.net.ssl.trustStore", "(JVM 기본 cacerts)"));
        System.out.println("java.version= " + System.getProperty("java.version"));
        System.out.println("---");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            System.out.println("검증 성공");
            System.out.println("status      = " + response.statusCode());
            System.out.println("body        = " + response.body().trim());

            SSLSession session = response.sslSession().orElse(null);
            if (session != null) {
                System.out.println("protocol    = " + session.getProtocol());
                System.out.println("cipher      = " + session.getCipherSuite());
                Certificate[] chain = session.getPeerCertificates();
                System.out.println("서버가 보낸 인증서 수 = " + chain.length + " (루트는 보내지 않는 것이 정상)");
                for (int i = 0; i < chain.length; i++) {
                    X509Certificate cert = (X509Certificate) chain[i];
                    System.out.println("  [" + i + "] subject = " + cert.getSubjectX500Principal());
                    System.out.println("      issuer  = " + cert.getIssuerX500Principal());
                    System.out.println("      valid   = " + cert.getNotBefore() + "  ~  " + cert.getNotAfter());
                }
            }
        } catch (Exception e) {
            System.out.println("검증 실패");
            System.out.println("  " + e.getClass().getName() + ": " + e.getMessage());
            Throwable cause = e.getCause();
            while (cause != null) {
                System.out.println("  Caused by: " + cause.getClass().getName() + ": " + cause.getMessage());
                cause = cause.getCause();
            }
        }
    }
}
