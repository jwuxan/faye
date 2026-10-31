package faye.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import faye.engine.FlowStore;
import faye.engine.FlowVersions;
import faye.engine.HookStore;
import faye.engine.HostKind;
import faye.engine.HostParams;
import faye.engine.HostRequest;
import faye.engine.InMemoryFlowStore;
import faye.engine.InMemoryFlowVersions;
import faye.engine.InMemoryHookStore;
import faye.host.mock.MockFlowHost;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

/** M6, open question 5: previews are off unless configured, and then a preview client is refused. */
@SpringBootTest(properties = {"faye.admin-token=test-token"})
@AutoConfigureMockMvc
class PreviewsOffTest {

    @TestConfiguration
    static class InMemory {
        @Bean
        @Primary
        FlowStore inMemoryFlowStore() {
            return new InMemoryFlowStore();
        }

        @Bean
        @Primary
        FlowVersions inMemoryFlowVersions() {
            return new InMemoryFlowVersions();
        }

        @Bean
        @Primary
        HookStore inMemoryHookStore() {
            return new InMemoryHookStore();
        }

        @Bean
        @Primary
        TenantConfigStore inMemoryTenantConfigStore() {
            return new InMemoryTenantConfigStore();
        }

        @Bean
        @Primary
        TraceStore inMemoryTraceStore() {
            return new InMemoryTraceStore();
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    MockFlowHost host;

    @Test
    void aPreviewClientIsRefusedAndThePreviewApiIsAbsent() throws Exception {
        host.register(new HostRequest(
                HostKind.LOGIN,
                "off",
                Map.of("client_id", "preview-default", "metadata", Map.of("preview", true)),
                HostParams.fromMap(Map.of())));
        mvc.perform(get("/login").param("login_challenge", "off")).andExpect(status().isFound());
        assertThat(host.rejected().getLast().description()).isEqualTo("preview_unavailable");

        String view = mvc.perform(get("/admin/tenants/default/preview").header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(view).contains("\"enabled\":false");
        mvc.perform(get("/admin/tenants/default/preview/events").header("Authorization", "Bearer test-token"))
                .andExpect(status().isNotFound());
    }
}
