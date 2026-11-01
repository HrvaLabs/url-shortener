package cc.hrva.urlshortener.service;

import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class SignInFormCheckTestConfiguration {

    @Bean
    SignInFormCheckService signInFormCheckService() {
        return Mockito.mock(SignInFormCheckService.class);
    }

}
