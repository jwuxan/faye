package faye.app;

import jakarta.servlet.http.Cookie;
import java.net.HttpCookie;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.ConfigurableMockMvcBuilder;

/**
 * Makes each test context's MockMvc behave like one browser: cookies a response sets are sent with
 * every later request, so a flow started through {@code /login} carries its binder (ADR 0024).
 */
@Configuration(proxyBeanMethods = false)
class BrowserCookies {

    /** The jar of the context's one browser, by cookie name. */
    final Map<String, String> jar = new ConcurrentHashMap<>();

    @Bean
    MockMvcBuilderCustomizer cookieJar() {
        return builder -> {
            ConfigurableMockMvcBuilder<?> b = builder;
            b.alwaysDo(result -> result.getResponse()
                    .getHeaders(HttpHeaders.SET_COOKIE)
                    .forEach(header -> {
                        for (HttpCookie c : HttpCookie.parse(header)) {
                            if (c.getMaxAge() == 0) {
                                jar.remove(c.getName());
                            } else {
                                jar.put(c.getName(), c.getValue());
                            }
                        }
                    }));
            b.defaultRequest(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/")
                    .with(send()));
        };
    }

    private RequestPostProcessor send() {
        return request -> {
            if (!jar.isEmpty() && request.getCookies() == null) {
                request.setCookies(jar.entrySet().stream()
                        .map(e -> new Cookie(e.getKey(), e.getValue()))
                        .toArray(Cookie[]::new));
            }
            return request;
        };
    }
}
